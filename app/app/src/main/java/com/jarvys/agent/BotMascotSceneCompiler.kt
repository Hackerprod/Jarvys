package com.jarvys.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Original, bounded in-process writer for the reviewed UX40 Rive 7.4 subset.
 * This is not RML, the Rive editor/CLI, an arbitrary .riv validator, or a renderer.
 * Geometry and keyframes come only from the supplied scene. No design templates,
 * files, scripts, URLs, native executables or network services are loaded here.
 * Names are opaque labels; they are never interpreted as code or locations.
 *
 * Contract: one 256x256 Mascot artboard, Idle/Active/Reduced animations, and
 * MascotController with numeric mode and boolean reducedMotion inputs.
 * Source name is metadata, not the stable artboard name. Reduced overrides mode.
 *
 * Public format: https://rive.app/docs/runtimes/advanced-topic/format
 * Type/property IDs and ToC layout: Rive MIT runtime at
 * 6f3510dcc545bc8b2a78f1004a06929d17cd022b (tests/include/riv_bytes.hpp,
 * include/rive/runtime_header.hpp and include/rive/generated).
 * See docs/ux40-rive-proof/local-writer/SOURCES.md and LICENSE-RIVE-MIT.txt.
 * Compatibility still requires independent runtime and Android-device validation.
 *
 * MIT License, Copyright (c) 2020 Rive
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
object BotMascotSceneCompiler {
    const val MAX_SOURCE_BYTES = 131072
    const val MAX_OUTPUT_BYTES = 65536
    const val MAX_NODES = 96
    const val MAX_HIERARCHY_DEPTH = 8
    const val MAX_TRACKS_PER_STATE = 64
    const val MAX_KEYS_PER_TRACK = 64
    const val MAX_TOTAL_TRACKS = MAX_TRACKS_PER_STATE * 3
    const val MAX_TOTAL_KEYFRAMES = MAX_TOTAL_TRACKS * MAX_KEYS_PER_TRACK
    const val MAX_DURATION_FRAMES = 600
    const val MAX_NAME_BYTES = 80
    const val FORMAT_MAJOR = 7
    const val FORMAT_MINOR = 4
    const val ARTBOARD_NAME = "Mascot"
    const val STATE_MACHINE_NAME = "MascotController"

    data class Complexity(
        val nodeCount: Int,
        val shapeCount: Int,
        val maxHierarchyDepth: Int,
        val trackCount: Int,
        val keyframeCount: Int,
        val binaryObjectCount: Int,
    )

    data class Validation(val name: String, val sourceBytes: Int, val complexity: Complexity)
    data class CompiledScene(val bytes: ByteArray, val validation: Validation, val sha256: String)

    /** All invalid or over-budget sources fail with IllegalArgumentException. */
    @JvmStatic fun compile(source: String): CompiledScene = compile(encodeSource(source))
    @JvmStatic fun compile(source: ByteArray): CompiledScene {
        val scene = parseAndValidate(source)
        val bytes = serialize(scene)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
        return CompiledScene(bytes, scene.validation, sha)
    }

    /** Includes the output budget check, so validation and compilation accept the same sources. */
    @JvmStatic fun validate(source: String): Validation = validate(encodeSource(source))
    @JvmStatic fun validate(source: ByteArray): Validation {
        val scene = parseAndValidate(source)
        serialize(scene)
        return scene.validation
    }

    private val transformIds = linkedMapOf("x" to 13, "y" to 14, "rotation" to 15, "scaleX" to 16, "scaleY" to 17)
    private val stateNames = listOf("Idle", "Active", "Reduced")
    private val nodeFields = setOf("kind", "name", "parent") + transformIds.keys
    private data class Node(
        val kind: String, val name: String, val parent: String, val transforms: Map<String, Float>,
        val width: Float?, val height: Float?, val color: Long?, val radius: Float?,
    )
    private data class Key(val frame: Int, val value: Float)
    private data class Track(val node: String, val property: String, val keys: List<Key>)
    private data class Animation(val name: String, val duration: Int, val loop: Boolean, val tracks: List<Track>)
    private data class Scene(val nodes: List<Node>, val animations: List<Animation>, val validation: Validation)

    private fun encodeSource(source: String): ByteArray {
        require(source.length <= MAX_SOURCE_BYTES) { "Source byte budget exceeded." }
        return try {
            val buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(source))
            require(buffer.remaining() in 1..MAX_SOURCE_BYTES) { "Source byte budget exceeded or empty source." }
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Source must contain valid UTF-8 text.")
        }
    }

    private fun parseAndValidate(source: ByteArray): Scene {
        require(source.size in 1..MAX_SOURCE_BYTES) { "Source byte budget exceeded or empty source." }
        val text = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Source must contain valid UTF-8 text.")
        }
        // Do not use lenient JSONTokener coercions: reject duplicates, comments, trailing
        // text, single quotes, missing values and non-JSON numbers before schema validation.
        val root = objectValue(StrictJson(text).read(), "scene")
        fields(root, setOf("name", "nodes", "animations"))
        val name = name(root.opt("name"))
        val rawNodes = array(root.opt("nodes"), "nodes")
        require(rawNodes.length() in 1..MAX_NODES) { "Node count budget exceeded." }
        val depths = linkedMapOf("Artboard" to 0)
        var maxDepth = 0
        var shapes = 0
        val nodes = (0 until rawNodes.length()).map { index ->
            val raw = objectValue(rawNodes.opt(index), "node")
            val kind = raw.opt("kind")
            require(kind is String && kind in setOf("group", "ellipse", "rectangle")) { "Unsupported geometry." }
            val geometryFields = if (kind == "group") emptySet() else setOf("width", "height", "color")
            val allowed = nodeFields + geometryFields + if (kind == "rectangle") setOf("radius") else emptySet()
            val required = setOf("kind", "name") + geometryFields
            fields(raw, allowed, required)
            val nodeName = name(raw.opt("name"))
            require(nodeName !in depths) { "Duplicate or reserved node name." }
            val parent = if (raw.has("parent")) name(raw.opt("parent")) else "Artboard"
            val depth = (depths[parent] ?: throw IllegalArgumentException("Parent must exist before child.")) + 1
            require(depth <= MAX_HIERARCHY_DEPTH) { "Hierarchy depth budget exceeded." }
            depths[nodeName] = depth
            maxDepth = maxOf(maxDepth, depth)
            val transforms = linkedMapOf<String, Float>()
            transformIds.keys.forEach { property ->
                if (raw.has(property)) transforms[property] = transformNumber(raw.opt(property), property)
            }
            if (kind != "group") shapes++
            Node(kind, nodeName, parent, transforms,
                if (kind != "group") number(raw.opt("width"), 1.0, 256.0) else null,
                if (kind != "group") number(raw.opt("height"), 1.0, 256.0) else null,
                if (kind != "group") integer(raw.opt("color"), 0, 0xffffffffL) else null,
                if (raw.has("radius")) number(raw.opt("radius"), 0.0, 128.0).takeIf {
                    // Python/public proof omits numeric zero before float32 conversion.
                    // A tiny positive source radius still emits a rounded 0f property.
                    (raw.opt("radius") as Number).toDouble() != 0.0
                } else null)
        }
        val rawAnimations = objectValue(root.opt("animations"), "animations")
        fields(rawAnimations, stateNames.toSet())
        var totalTracks = 0
        var totalKeys = 0
        var animatedObjects = 0
        var expectedTargets: Set<Pair<String, String>>? = null
        val animations = stateNames.map { state ->
            val raw = objectValue(rawAnimations.opt(state), "animation")
            fields(raw, setOf("duration", "loop", "tracks"))
            val duration = integer(raw.opt("duration"), 1, MAX_DURATION_FRAMES.toLong()).toInt()
            val loop = raw.opt("loop")
            require(loop is Boolean) { "Loop must be boolean." }
            val rawTracks = array(raw.opt("tracks"), "tracks")
            require(rawTracks.length() in 1..MAX_TRACKS_PER_STATE) { "Track budget exceeded." }
            val targets = linkedSetOf<Pair<String, String>>()
            val tracks = (0 until rawTracks.length()).map { index ->
                val track = objectValue(rawTracks.opt(index), "track")
                fields(track, setOf("node", "property", "keys"))
                val target = name(track.opt("node"))
                require(target != "Artboard" && target in depths) { "Missing animation target." }
                val property = track.opt("property")
                require(property is String && property in transformIds) { "Unsupported animated property." }
                require(targets.add(target to property)) { "Duplicate animation track." }
                val rawKeys = array(track.opt("keys"), "keys")
                require(rawKeys.length() in 1..MAX_KEYS_PER_TRACK) { "Keyframe budget exceeded." }
                var previous = -1
                val keys = (0 until rawKeys.length()).map { keyIndex ->
                    val pair = array(rawKeys.opt(keyIndex), "keyframe")
                    require(pair.length() == 2) { "Keyframe must be [frame, value]." }
                    val frame = integer(pair.opt(0), 0, duration.toLong()).toInt()
                    require(frame > previous) { "Keyframes must have strictly increasing frames." }
                    previous = frame
                    Key(frame, transformNumber(pair.opt(1), property))
                }
                require(keys.first().frame == 0) { "Explicit frame-zero reset required." }
                require(state != "Reduced" || (!loop && keys.size == 1)) { "Reduced must contain only constant tracks and must not loop." }
                totalKeys += keys.size
                Track(target, property, keys)
            }
            if (expectedTargets == null) expectedTargets = targets
            else require(targets == expectedTargets) { "Every state must reset every animated target." }
            totalTracks += tracks.size
            animatedObjects += tracks.map { it.node }.toSet().size
            Animation(state, duration, loop, tracks)
        }
        require(totalTracks <= MAX_TOTAL_TRACKS && totalKeys <= MAX_TOTAL_KEYFRAMES) { "Total animation complexity budget exceeded." }
        // 2 scene roots + authored nodes + 3 per shape + 3 animations + animated
        // objects/properties/keys + 19 fixed controller objects.
        val complexity = Complexity(nodes.size, shapes, maxDepth, totalTracks, totalKeys,
            2 + nodes.size + 3 * shapes + 3 + animatedObjects + totalTracks + totalKeys + 19)
        return Scene(nodes, animations, Validation(name, source.size, complexity))
    }

    private fun fields(value: JSONObject, allowed: Set<String>, required: Set<String> = allowed) {
        val keys = value.keys().asSequence().toSet()
        require(keys.all { it in allowed } && keys.containsAll(required)) { "Unknown or missing scene fields." }
    }
    private fun objectValue(value: Any?, label: String): JSONObject {
        require(value is JSONObject) { "$label must be an object." }
        return value
    }
    private fun array(value: Any?, label: String): JSONArray {
        require(value is JSONArray) { "$label must be an array." }
        return value
    }
    private fun name(value: Any?): String {
        require(value is String && value.toByteArray(StandardCharsets.UTF_8).size in 1..MAX_NAME_BYTES &&
            value.none { it.code < 32 || it.code == 127 }) { "Invalid bounded name." }
        return value
    }
    private fun integer(value: Any?, low: Long, high: Long): Long {
        require(value is Long && value in low..high) { "Expected bounded integer." }
        return value
    }
    private fun number(value: Any?, low: Double, high: Double): Float {
        require(value is Long || value is Double) { "Expected numeric value." }
        val number = (value as Number).toDouble()
        require(number.isFinite() && number in low..high) { "Non-finite or out-of-bounds number." }
        return number.toFloat()
    }
    private fun transformNumber(value: Any?, property: String): Float {
        val limit = if (property.startsWith("scale")) 4.0 else 2048.0
        return number(value, -limit, limit)
    }

    private fun serialize(scene: Scene): ByteArray {
        val writer = Binary()
        writer.obj(23)
        writer.obj(1, s(4, ARTBOARD_NAME), f(7, 256f), f(8, 256f), u(236, 0))
        val ids = linkedMapOf("Artboard" to 0)
        var index = 1
        scene.nodes.forEach { node ->
            val ownId = index++
            ids[node.name] = ownId
            val properties = mutableListOf(s(4, node.name), u(5, ids.getValue(node.parent)))
            node.transforms.forEach { (name, value) -> properties.add(f(transformIds.getValue(name), value)) }
            writer.obj(if (node.kind == "group") 2 else 3, *properties.toTypedArray())
            if (node.kind != "group") {
                val shape = mutableListOf(u(5, ownId), f(20, requireNotNull(node.width)), f(21, requireNotNull(node.height)))
                node.radius?.let { shape.add(f(31, it)) }
                writer.obj(if (node.kind == "ellipse") 4 else 7, *shape.toTypedArray())
                index++
                val fillId = index++
                writer.obj(20, u(5, ownId))
                writer.obj(18, u(5, fillId), Property(37, 3, requireNotNull(node.color)))
                index++
            }
        }
        scene.animations.forEach { animation ->
            writer.obj(31, s(55, animation.name), u(56, 60), u(57, animation.duration), u(59, if (animation.loop) 1 else 0))
            animation.tracks.groupBy { it.node }.forEach { (node, tracks) ->
                writer.obj(25, u(51, ids.getValue(node)))
                tracks.forEach { track ->
                    writer.obj(26, u(53, transformIds.getValue(track.property)))
                    track.keys.forEach { key -> writer.obj(30, u(67, key.frame), u(68, 1), f(70, key.value)) }
                }
            }
        }
        writer.obj(53, s(55, STATE_MACHINE_NAME))
        writer.obj(56, s(138, "mode"), f(140, 0f))
        writer.obj(59, s(138, "reducedMotion"), u(141, 0))
        writer.obj(57, s(138, "Presence"))
        writer.obj(63) // Entry, state index 0.
        writer.obj(65, u(151, 3))
        writer.obj(62) // Any, state index 1. Reduced transition has priority.
        writer.obj(65, u(151, 5))
        writer.obj(71, u(155, 1), u(156, 0))
        writer.obj(65, u(151, 4))
        writer.obj(70, u(155, 0), u(156, 5), f(157, 0f))
        writer.obj(71, u(155, 1), u(156, 1))
        writer.obj(65, u(151, 3))
        writer.obj(70, u(155, 0), u(156, 2), f(157, 0f))
        writer.obj(71, u(155, 1), u(156, 1))
        writer.obj(64) // Exit, state index 2.
        repeat(3) { writer.obj(61, u(149, it)) }
        check(writer.objectCount == scene.validation.complexity.binaryObjectCount) { "Serializer complexity count mismatch." }
        return writer.finish()
    }

    private data class Property(val id: Int, val wireType: Int, val value: Any)
    private fun u(id: Int, value: Int) = Property(id, 0, value.toLong())
    private fun s(id: Int, value: String) = Property(id, 1, value)
    private fun f(id: Int, value: Float) = Property(id, 2, value)

    private class Binary {
        private val body = ByteArrayOutputStream()
        private val types = sortedMapOf<Int, Int>()
        var objectCount = 0
            private set
        fun obj(type: Int, vararg properties: Property) {
            uint(body, type.toLong())
            properties.forEach { property ->
                val existing = types.put(property.id, property.wireType)
                check(existing == null || existing == property.wireType) { "Inconsistent property wire type." }
                uint(body, property.id.toLong())
                when (property.wireType) {
                    0 -> uint(body, property.value as Long)
                    1 -> {
                        val bytes = (property.value as String).toByteArray(StandardCharsets.UTF_8)
                        uint(body, bytes.size.toLong())
                        body.write(bytes, 0, bytes.size)
                    }
                    2 -> littleEndian(body, java.lang.Float.floatToRawIntBits(property.value as Float).toLong())
                    3 -> littleEndian(body, property.value as Long)
                    else -> error("Unsupported property wire type.")
                }
            }
            body.write(0)
            objectCount++
            require(body.size() <= MAX_OUTPUT_BYTES) { "Output byte budget exceeded." }
        }
        fun finish(): ByteArray {
            val output = ByteArrayOutputStream()
            output.write(byteArrayOf(82, 73, 86, 69)) // RIVE
            uint(output, FORMAT_MAJOR.toLong()); uint(output, FORMAT_MINOR.toLong()); uint(output, 0)
            val keys = types.keys.toList()
            keys.forEach { uint(output, it.toLong()) }
            output.write(0)
            keys.chunked(4).forEach { group ->
                var packed = 0
                group.forEachIndexed { index, key -> packed = packed or (types.getValue(key) shl (2 * index)) }
                littleEndian(output, packed.toLong())
            }
            require(output.size() + body.size() <= MAX_OUTPUT_BYTES) { "Output byte budget exceeded." }
            body.writeTo(output)
            return output.toByteArray()
        }
        private fun uint(output: ByteArrayOutputStream, value: Long) {
            check(value in 0..0xffffffffL) { "Unsigned integer outside uint32." }
            var remaining = value
            while (remaining >= 128) {
                output.write(((remaining and 127) or 128).toInt())
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }
        private fun littleEndian(output: ByteArrayOutputStream, value: Long) {
            repeat(4) { output.write(((value ushr (8 * it)) and 255).toInt()) }
        }
    }

    /** Bounded strict JSON, independent of Android/JVM JSONTokener differences. */
    private class StrictJson(private val source: String) {
        private var position = 0
        private var values = 0
        fun read(): Any {
            val result = value(0)
            whitespace()
            require(position == source.length) { "Trailing content after scene." }
            return result
        }
        private fun value(depth: Int): Any {
            require(depth <= 12 && ++values <= 65536) { "JSON complexity budget exceeded." }
            whitespace()
            require(position < source.length) { "Incomplete JSON value." }
            return when (source[position]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", JSONObject.NULL)
                '-', in '0'..'9' -> numeric()
                else -> throw IllegalArgumentException("Invalid JSON value.")
            }
        }
        private fun readObject(depth: Int): JSONObject {
            position++
            val result = JSONObject()
            val names = hashSetOf<String>()
            whitespace()
            if (consume('}')) return result
            while (true) {
                whitespace()
                val key = string()
                require(names.add(key)) { "Duplicate JSON field." }
                require(names.size <= 16) { "JSON object field budget exceeded." }
                whitespace(); expect(':')
                result.put(key, value(depth))
                whitespace()
                if (consume('}')) return result
                expect(',')
            }
        }
        private fun readArray(depth: Int): JSONArray {
            position++
            val result = JSONArray()
            whitespace()
            if (consume(']')) return result
            while (true) {
                require(result.length() < MAX_NODES) { "JSON array budget exceeded." }
                result.put(value(depth))
                whitespace()
                if (consume(']')) return result
                expect(',')
            }
        }
        private fun string(): String {
            expect('"')
            val result = StringBuilder()
            while (position < source.length) {
                val character = source[position++]
                if (character == '"') {
                    val text = result.toString()
                    var index = 0
                    while (index < text.length) {
                        val current = text[index++]
                        if (Character.isHighSurrogate(current)) {
                            require(index < text.length && Character.isLowSurrogate(text[index++])) { "Unpaired JSON surrogate." }
                        } else require(!Character.isLowSurrogate(current)) { "Unpaired JSON surrogate." }
                    }
                    return text
                }
                require(character.code >= 32) { "Control character in JSON string." }
                if (character != '\\') result.append(character)
                else {
                    require(position < source.length) { "Incomplete JSON escape." }
                    when (val escape = source[position++]) {
                        '"', '\\', '/' -> result.append(escape)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(position + 4 <= source.length) { "Incomplete Unicode escape." }
                            var code = 0
                            repeat(4) {
                                val hex = source[position++]
                                require(hex in '0'..'9' || hex in 'a'..'f' || hex in 'A'..'F') { "Invalid Unicode escape." }
                                val digit = hex.digitToInt(16)
                                code = code * 16 + digit
                            }
                            result.append(code.toChar())
                        }
                        else -> throw IllegalArgumentException("Invalid JSON escape.")
                    }
                }
                require(result.length <= 256) { "JSON string budget exceeded." }
            }
            throw IllegalArgumentException("Unterminated JSON string.")
        }
        private fun numeric(): Number {
            val start = position
            consume('-')
            require(position < source.length) { "Incomplete JSON number." }
            if (consume('0')) {
                require(position == source.length || source[position] !in '0'..'9') { "Leading zero in JSON number." }
            } else {
                require(source[position] in '1'..'9') { "Invalid JSON number." }
                digits()
            }
            var fractional = false
            if (consume('.')) { fractional = true; digits() }
            if (consume('e') || consume('E')) {
                fractional = true
                if (!consume('+')) consume('-')
                digits()
            }
            require(position - start <= 32) { "JSON number budget exceeded." }
            val token = source.substring(start, position)
            if (!fractional) return token.toLongOrNull() ?: throw IllegalArgumentException("JSON integer overflow.")
            val number = token.toDoubleOrNull()
            require(number != null && number.isFinite()) { "Non-finite JSON number." }
            return number
        }
        private fun digits() {
            val start = position
            while (position < source.length && source[position] in '0'..'9') position++
            require(position > start) { "Missing JSON number digits." }
        }
        private fun literal(token: String, result: Any): Any {
            require(source.startsWith(token, position)) { "Invalid JSON literal." }
            position += token.length
            return result
        }
        private fun whitespace() {
            while (position < source.length && source[position] in " \t\r\n") position++
        }
        private fun consume(character: Char): Boolean {
            if (position < source.length && source[position] == character) { position++; return true }
            return false
        }
        private fun expect(character: Char) {
            require(consume(character)) { "Malformed JSON scene." }
        }
    }
}
