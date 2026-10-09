package com.jarvys.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Host serializer/schema tests only. These do not claim Android rendering/runtime success. */
class BotMascotSceneCompilerTest {
    private fun fixture(name: String = "miga"): String = requireNotNull(javaClass.getResourceAsStream("/bot-mascot-scenes/$name.json"))
        .use { String(it.readBytes(), StandardCharsets.UTF_8) }
    private fun scene() = JSONObject(fixture())
    private fun nodes(value: JSONObject) = value.getJSONArray("nodes")
    private fun animation(value: JSONObject, state: String = "Idle") = value.getJSONObject("animations").getJSONObject(state)
    private fun track(value: JSONObject, state: String = "Idle", index: Int = 0) = animation(value, state).getJSONArray("tracks").getJSONObject(index)
    private fun keys(vararg pairs: Pair<Int, Number>) = JSONArray().apply {
        pairs.forEach { put(JSONArray().put(it.first).put(it.second)) }
    }
    private fun rejects(source: String, label: String = "invalid JSON scene") {
        val failure = runCatching { BotMascotSceneCompiler.compile(source) }.exceptionOrNull()
        assertTrue("$label: expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
    }
    private fun rejects(label: String = "invalid scene", change: (JSONObject) -> Unit) {
        rejects(scene().also(change).toString(), label)
    }

    @Test fun originalFixturesMatchReviewedPythonBytesAndExportForIndependentRuntime() {
        val expected = linkedMapOf(
            "miga" to (1303 to "72bca638c9c6e8e8ad588cd8dbb6c0320f64378cf6cb40c8d126a3297efbe642"),
            "tallo" to (1158 to "e42d0d7a0b7b8c61898fa3a11e0ac22ea552403a6c8e3307c79b2ba96dd4cddc"),
        )
        // Default is relative to the Gradle module working directory. The environment
        // option works without forwarding a system property through Gradle's test JVM.
        val output = File(System.getProperty("jarvys.mascot.outputDir")
            ?: System.getenv("JARVYS_MASCOT_OUTPUT_DIR")
            ?: "build/test-results/bot-mascot-scene-compiler")
        assertTrue("Could not create independent-runtime artifact directory", output.isDirectory || output.mkdirs())
        val results = JSONArray()
        expected.forEach { (name, expectedOutput) ->
            val source = fixture(name)
            val compiled = BotMascotSceneCompiler.compile(source)
            assertEquals(expectedOutput.first, compiled.bytes.size)
            assertEquals(expectedOutput.second, compiled.sha256)
            val independentHash = MessageDigest.getInstance("SHA-256").digest(compiled.bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expectedOutput.second, independentHash)
            assertArrayEquals(compiled.bytes, BotMascotSceneCompiler.compile(source).bytes)
            assertArrayEquals(compiled.bytes, BotMascotSceneCompiler.compile(source.toByteArray(StandardCharsets.UTF_8)).bytes)
            assertEquals(compiled.validation, BotMascotSceneCompiler.validate(source))
            assertArrayEquals(byteArrayOf(82, 73, 86, 69, 7, 4, 0), compiled.bytes.copyOfRange(0, 7))
            File(output, "$name.riv").writeBytes(compiled.bytes)
            results.put(JSONObject().put("name", name).put("bytes", compiled.bytes.size)
                .put("sha256", compiled.sha256).put("binaryObjectCount", compiled.validation.complexity.binaryObjectCount))
        }
        assertFalse(File(output, "miga.riv").readBytes().contentEquals(File(output, "tallo.riv").readBytes()))
        File(output, "kotlin-writer-results.json").writeText(JSONObject()
            .put("scope", "Pure Kotlin bounded-source serializer tests; Android runtime/device validation not performed by these tests")
            .put("format", "7.4").put("mascots", results).toString(2) + "\n")
    }

    @Test fun reportsExactSourceAndComplexityCounts() {
        val miga = BotMascotSceneCompiler.validate(fixture())
        assertEquals("Miga", miga.name)
        assertEquals(fixture().toByteArray(StandardCharsets.UTF_8).size, miga.sourceBytes)
        assertEquals(BotMascotSceneCompiler.Complexity(11, 10, 2, 15, 33, 122), miga.complexity)
        assertEquals(BotMascotSceneCompiler.Complexity(13, 9, 4, 9, 20, 102),
            BotMascotSceneCompiler.validate(fixture("tallo")).complexity)
    }

    @Test fun sourceNameIsMetadataAndNotACharacterTemplateSelector() {
        val renamed = scene().put("name", "A newly authored character")
        assertArrayEquals(BotMascotSceneCompiler.compile(fixture()).bytes, BotMascotSceneCompiler.compile(renamed.toString()).bytes)
        val changed = scene().also { nodes(it).getJSONObject(1).put("width", 13) }
        assertFalse(BotMascotSceneCompiler.compile(fixture()).bytes.contentEquals(BotMascotSceneCompiler.compile(changed.toString()).bytes))
    }

    @Test fun fieldOrderDoesNotChangeOutputAndTracksAreGroupedInFirstAppearanceOrder() {
        fun reverseObjects(value: Any): Any = when (value) {
            is JSONObject -> JSONObject().apply { value.keys().asSequence().toList().reversed().forEach { put(it, reverseObjects(value.get(it))) } }
            is JSONArray -> JSONArray().apply { repeat(value.length()) { put(reverseObjects(value.get(it))) } }
            else -> value
        }
        assertArrayEquals(BotMascotSceneCompiler.compile(fixture()).bytes,
            BotMascotSceneCompiler.compile(reverseObjects(scene()).toString()).bytes)
        val splitTracks = scene().apply {
            val tracks = animation(this).getJSONArray("tracks")
            animation(this).put("tracks", JSONArray().put(tracks.get(0)).put(tracks.get(3)).put(tracks.get(1)).put(tracks.get(4)).put(tracks.get(2)))
        }
        assertArrayEquals(BotMascotSceneCompiler.compile(fixture()).bytes,
            BotMascotSceneCompiler.compile(splitTracks.toString()).bytes)
    }

    @Test fun allContainerAndPrimitiveTypesAreExplicit() {
        rejects("[]")
        rejects("null")
        rejects("nodes string") { it.put("nodes", "[]") }
        rejects("nodes object") { it.put("nodes", JSONObject()) }
        rejects("node null") { nodes(it).put(0, JSONObject.NULL) }
        rejects("animations array") { it.put("animations", JSONArray()) }
        rejects("animation null") { it.getJSONObject("animations").put("Idle", JSONObject.NULL) }
        rejects("track string") { animation(it).getJSONArray("tracks").put(0, "track") }
        rejects("tracks object") { animation(it).put("tracks", JSONObject()) }
        rejects("keys object") { track(it).put("keys", JSONObject()) }
        rejects("key object") { track(it).getJSONArray("keys").put(0, JSONObject()) }
        rejects("loop number") { animation(it).put("loop", 1) }
        rejects("duration string") { animation(it).put("duration", "120") }
        rejects("width string") { nodes(it).getJSONObject(1).put("width", "8") }
        rejects("coordinate boolean") { nodes(it).getJSONObject(0).put("y", true) }
        rejects("color boolean") { nodes(it).getJSONObject(1).put("color", false) }
        rejects("parent array") { nodes(it).getJSONObject(1).put("parent", JSONArray()) }
        rejects("property array") { track(it).put("property", JSONArray()) }
        rejects("name number") { it.put("name", 7) }
    }

    @Test fun unknownMissingAndExecutableResourceFieldsAreRejected() {
        rejects("unknown scene") { it.put("script", "untrusted()") }
        rejects("scene version") { it.put("version", 8) }
        rejects("missing scene field") { it.remove("name") }
        rejects("unknown geometry") { nodes(it).getJSONObject(1).put("kind", "path") }
        rejects("script geometry") { nodes(it).getJSONObject(1).put("kind", "script") }
        rejects("URL field") { nodes(it).getJSONObject(1).put("url", "https://example.invalid/a.png") }
        rejects("source code field") { nodes(it).getJSONObject(0).put("code", "Runtime.exec()") }
        rejects("external asset") { nodes(it).getJSONObject(1).put("asset", "image.png") }
        rejects("missing node kind") { nodes(it).getJSONObject(1).remove("kind") }
        rejects("missing width") { nodes(it).getJSONObject(1).remove("width") }
        rejects("ellipse radius") { nodes(it).getJSONObject(1).put("radius", 8) }
        rejects("group geometry") { nodes(it).getJSONObject(0).put("width", 8) }
        rejects("unknown animation") { animation(it).put("script", "animate()") }
        rejects("missing animation loop") { animation(it).remove("loop") }
        rejects("unknown track") { track(it).put("easing", "spring") }
        rejects("missing track keys") { track(it).remove("keys") }
        rejects("unsupported property") { track(it).put("property", "opacity") }
    }

    @Test fun exactThreeStateContractRequired() {
        rejects("missing state") { it.getJSONObject("animations").remove("Reduced") }
        rejects("extra state") { it.getJSONObject("animations").put("Listening", animation(it)) }
        rejects("case-sensitive state") { it.getJSONObject("animations").put("idle", it.getJSONObject("animations").remove("Idle")) }
    }

    @Test fun boundedNamesUseUtf8BytesAndRejectControlCharacters() {
        rejects("empty name") { it.put("name", "") }
        rejects("name 81 bytes") { it.put("name", "a".repeat(81)) }
        rejects("Unicode name 82 bytes") { it.put("name", "é".repeat(41)) }
        rejects("NUL label") { it.put("name", "safe\u0000hidden") }
        rejects("newline label") { it.put("name", "line\nbreak") }
        assertEquals("é".repeat(40), BotMascotSceneCompiler.validate(scene().put("name", "é".repeat(40)).toString()).name)
        assertEquals("🌱", BotMascotSceneCompiler.validate(scene().put("name", "🌱").toString()).name)
    }

    @Test fun nodeReferencesAreUniqueOrderedAcyclicAndBounded() {
        rejects("duplicate") { nodes(it).getJSONObject(1).put("name", "Body") }
        rejects("reserved root") { nodes(it).getJSONObject(1).put("name", "Artboard") }
        rejects("missing parent") { nodes(it).getJSONObject(1).put("parent", "Missing") }
        rejects("self parent") { nodes(it).getJSONObject(1).put("parent", "EyeL") }
        rejects("forward reference") { nodes(it).getJSONObject(1).put("parent", "EyeR") }
        rejects("unknown target") { track(it).put("node", "Missing") }
        rejects("root animation target") { track(it).put("node", "Artboard") }
        rejects("hierarchy depth") {
            var parent = "Body"
            repeat(8) { depth ->
                val child = "Deep$depth"
                nodes(it).put(JSONObject().put("kind", "group").put("name", child).put("parent", parent))
                parent = child
            }
        }
    }

    @Test fun geometryAndTransformBoundsRejectInvalidNumbers() {
        for (value in listOf(-2049, 2049)) rejects("coordinate $value") { nodes(it).getJSONObject(0).put("x", value) }
        for (value in listOf(-4.01, 4.01)) rejects("scale $value") { nodes(it).getJSONObject(0).put("scaleY", value) }
        for (value in listOf(-1, 0, 257)) rejects("width $value") { nodes(it).getJSONObject(1).put("width", value) }
        for (value in listOf(-1, 129)) rejects("radius $value") { nodes(it).getJSONObject(3).put("radius", value) }
        for (value in listOf(-1L, 4294967296L)) rejects("color $value") { nodes(it).getJSONObject(1).put("color", value) }
        rejects(fixture().replace("\"y\": 132", "\"y\": NaN"))
        rejects(fixture().replace("\"y\": 132", "\"y\": Infinity"))
        rejects(fixture().replace("\"y\": 132", "\"y\": 1e400"))
        rejects(fixture().replace("\"color\": 4280563530", "\"color\": 4280563530.0"))
        rejects(fixture().replace("\"duration\": 120", "\"duration\": 120.0"))
    }


    @Test fun sourceRadiusOmissionPrecedesFloat32Rounding() {
        // Independently generated with the reviewed Python writer: a positive
        // double that underflows float32 still writes a zero-valued property 31.
        val tiny = BotMascotSceneCompiler.compile(fixture().replace("\"radius\": 2", "\"radius\": 1e-300"))
        assertEquals(1303, tiny.bytes.size)
        assertEquals("512edf31406abd7b14a32aa764b9f88d571b18abe542a398b6de5c1bc3adad2a", tiny.sha256)
        val zero = BotMascotSceneCompiler.compile(fixture().replace("\"radius\": 2", "\"radius\": 0"))
        assertEquals(1298, zero.bytes.size)
        assertEquals("996fc0e33ee282caf587c8117709fc3e106903a9487f17114c09204ac3294da0", zero.sha256)
        assertArrayEquals(zero.bytes,
            BotMascotSceneCompiler.compile(fixture().replace("\"radius\": 2", "\"radius\": -0.0")).bytes)
    }

    @Test fun inclusiveGeometryBoundsAndUint32ColorsCompile() {
        val changed = scene().apply {
            nodes(this).getJSONObject(0).put("x", -2048).put("y", 2048).put("scaleX", -4).put("scaleY", 4)
            nodes(this).getJSONObject(1).put("width", 1).put("height", 256).put("color", 4294967295L)
            nodes(this).getJSONObject(3).put("radius", 128).put("color", 0)
        }
        assertTrue(BotMascotSceneCompiler.compile(changed.toString()).bytes.isNotEmpty())
    }

    @Test fun everyStateMustExplicitlyResetTheSameTargetsAtFrameZero() {
        rejects("missing target reset") { animation(it, "Reduced").getJSONArray("tracks").remove(0) }
        rejects("nonzero initial frame") { track(it).put("keys", keys(1 to 132)) }
        rejects("duplicate target") { animation(it).getJSONArray("tracks").put(track(it)) }
        rejects("Reduced loop") { animation(it, "Reduced").put("loop", true) }
        rejects("Reduced motion") { track(it, "Reduced").put("keys", keys(0 to 132, 1 to 140)) }
    }

    @Test fun keyframesMustBeStrictlyOrderedBoundedPairs() {
        rejects("unsorted") { track(it).put("keys", keys(0 to 132, 60 to 130, 30 to 132)) }
        rejects("duplicate frame") { track(it).put("keys", keys(0 to 132, 0 to 130)) }
        rejects("negative frame") { track(it).put("keys", keys(-1 to 132)) }
        rejects("beyond duration") { track(it).put("keys", keys(0 to 132, 121 to 132)) }
        rejects("fractional frame") { track(it).getJSONArray("keys").getJSONArray(0).put(0, 0.1) }
        rejects("string frame") { track(it).getJSONArray("keys").getJSONArray(0).put(0, "0") }
        rejects("boolean value") { track(it).getJSONArray("keys").getJSONArray(0).put(1, true) }
        rejects("oversized value") { track(it).put("keys", keys(0 to 2049)) }
        rejects("oversized animated scale") { track(it, index = 2).put("keys", keys(0 to 4.01)) }
        rejects("one-entry key") { track(it).getJSONArray("keys").put(0, JSONArray().put(0)) }
        rejects("three-entry key") { track(it).getJSONArray("keys").put(0, JSONArray().put(0).put(132).put(1)) }
    }

    @Test fun countAndDurationBudgetsFailClosed() {
        rejects("zero nodes") { it.put("nodes", JSONArray()) }
        rejects("97 nodes") { value -> repeat(97 - nodes(value).length()) { nodes(value).put(JSONObject().put("kind", "group").put("name", "Extra$it")) } }
        rejects("zero tracks") { animation(it).put("tracks", JSONArray()) }
        rejects("65 tracks") { value -> repeat(65 - animation(value).getJSONArray("tracks").length()) { animation(value).getJSONArray("tracks").put(track(value)) } }
        rejects("zero keys") { track(it).put("keys", JSONArray()) }
        rejects("65 keys") { track(it).put("keys", JSONArray().apply { repeat(65) { put(JSONArray().put(it).put(132)) } }) }
        rejects("zero duration") { animation(it).put("duration", 0) }
        rejects("601 frames") { animation(it).put("duration", 601) }
        assertTrue(BotMascotSceneCompiler.compile(scene().apply { animation(this).put("duration", 600) }.toString()).bytes.isNotEmpty())
    }

    @Test fun outputBudgetIsCheckedDuringBothCompileAndValidation() {
        // Within source/node/track/key budgets, but over 64 KiB after binary serialization.
        val value = JSONObject().put("name", "Output budget").put("nodes", JSONArray().apply {
            repeat(64) { put(JSONObject().put("kind", "group").put("name", "N$it")) }
        })
        val animations = JSONObject()
        for (state in listOf("Idle", "Active", "Reduced")) {
            val tracks = JSONArray()
            repeat(64) { node ->
                tracks.put(JSONObject().put("node", "N$node").put("property", "x").put("keys", JSONArray().apply {
                    repeat(if (state == "Reduced") 1 else 64) { put(JSONArray().put(it).put(1)) }
                }))
            }
            animations.put(state, JSONObject().put("duration", 64).put("loop", state != "Reduced").put("tracks", tracks))
        }
        val source = value.put("animations", animations).toString()
        assertTrue(source.toByteArray().size < BotMascotSceneCompiler.MAX_SOURCE_BYTES)
        rejects(source, "output budget")
        val error = runCatching { BotMascotSceneCompiler.validate(source) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("Output byte budget"))
    }

    @Test fun ingestionRejectsOversizeBeforeParsingAndAllowsExactByteLimit() {
        rejects(" ".repeat(BotMascotSceneCompiler.MAX_SOURCE_BYTES + 1), "source limit")
        rejects("é".repeat(BotMascotSceneCompiler.MAX_SOURCE_BYTES / 2 + 1), "UTF-8 source limit")
        val padded = fixture().padEnd(BotMascotSceneCompiler.MAX_SOURCE_BYTES, ' ')
        assertEquals(BotMascotSceneCompiler.MAX_SOURCE_BYTES, BotMascotSceneCompiler.validate(padded).sourceBytes)
        val error = runCatching { BotMascotSceneCompiler.compile(ByteArray(BotMascotSceneCompiler.MAX_SOURCE_BYTES + 1)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun malformedUtf8AndUnpairedSurrogatesAreRejected() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xff.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), byteArrayOf())) {
            assertTrue(runCatching { BotMascotSceneCompiler.compile(bytes) }.exceptionOrNull() is IllegalArgumentException)
        }
        rejects("\ud800", "unpaired raw surrogate")
        rejects(fixture().replace("Miga", "\\ud800"))
        rejects(fixture().replace("Miga", "\\udc00"))
        rejects(fixture().replace("Miga", "\\ud800x"))
        assertEquals("🌱", BotMascotSceneCompiler.validate(fixture().replace("Miga", "\\ud83c\\udf31")).name)
    }

    @Test fun strictJsonRejectsLenientSyntaxDuplicatesAndTrailingContent() {
        val valid = fixture()
        for (source in listOf("", " ", "{} trailing", "$valid {}", "```json\n$valid\n```", "\ufeff$valid",
            valid.replaceFirst("\"name\": \"Miga\"", "name: 'Miga'"),
            valid.replaceFirst("\"name\": \"Miga\"", "\"name\": \"Miga\", \"name\": \"Other\""),
            valid.replaceFirst("\"name\": \"Miga\"", "\"name\": \"Miga\", \"na\\u006de\": \"Other\""),
            valid.replaceFirst("\"x\": 128", "\"x\": 0128"),
            valid.replaceFirst("\"x\": 128", "\"x\": +128"),
            valid.replaceFirst("\"x\": 128", "\"x\": 128."),
            valid.replaceFirst("\"x\": 128", "\"x\": .5"),
            valid.replaceFirst("\"x\": 128", "\"x\": 1e"),
            valid.replaceFirst("\"x\": 128", "\"x\": 1e+"),
            valid.replaceFirst("\"x\": 128", "\"x\": 0x80"),
            valid.replaceFirst("\"x\": 128", "\"x\": 9223372036854775808"),
            valid.replaceFirst("\"x\": 128", "\"x\": 0.123456789012345678901234567890123456789"),
            valid.replaceFirst("\"x\": 128", "\"x\": /* comment */ 128"),
            valid.replaceFirst("\"x\": 128", "\"x\": 128,"),
            valid.replaceFirst("Miga", "Miga\\q"),
            valid.replaceFirst("Miga", "Miga\\u00zz"),
            valid.replaceFirst("Miga", "Miga\\u00١١"),
            valid.replaceFirst("Miga", "Miga\n"))) rejects(source)
    }

    @Test fun nestingTokenStringAndContainerBudgetsBoundInvalidInput() {
        rejects("[".repeat(200) + "0" + "]".repeat(200), "deep JSON")
        rejects("[" + "0,".repeat(10000) + "0]", "array work budget")
        rejects("{\"name\":\"" + "a".repeat(10000) + "\"}", "string work budget")
        rejects("{" + (0 until 17).joinToString(",") { "\"k$it\":0" } + "}", "object work budget")
    }

    @Test fun validEscapesExponentsAndNegativeZeroRemainData() {
        val changed = fixture().replace("Miga", "Mi\\u0067a").replaceFirst("\"x\": 128", "\"x\": 1.28e2")
        assertArrayEquals(BotMascotSceneCompiler.compile(fixture()).bytes, BotMascotSceneCompiler.compile(changed).bytes)
        assertTrue(BotMascotSceneCompiler.compile(fixture().replaceFirst("\"x\": 0", "\"x\": -0.0")).bytes.isNotEmpty())
    }

    // Synthetic contract probe, not a shipped design or a production appearance template.
    private fun productScene(trackCount: Int = 1): JSONObject {
        val nodes = JSONArray()
        repeat(trackCount) { nodes.put(JSONObject().put("kind", "group").put("name", "Probe$it")) }
        val animations = JSONObject()
        BotMascotSceneCompiler.productModeNames.forEachIndexed { mode, name ->
            for (reduced in listOf(false, true)) {
                val tracks = JSONArray()
                repeat(trackCount) { target ->
                    tracks.put(JSONObject().put("node", "Probe$target").put("property", "x")
                        .put("keys", if (reduced) keys(0 to mode) else keys(0 to mode, 1 to mode + 1, 2 to mode)))
                }
                animations.put(name + if (reduced) "Reduced" else "", JSONObject().put("duration", 2)
                    .put("loop", !reduced).put("tracks", tracks))
            }
        }
        return JSONObject().put("contract", "bot-mascot-v1").put("name", "Synthetic contract probe")
            .put("nodes", nodes).put("animations", animations)
    }

    private fun rejectsProduct(label: String, change: (JSONObject) -> Unit) {
        val failure = runCatching { BotMascotSceneCompiler.compileProduct(productScene().also(change).toString()) }.exceptionOrNull()
        assertTrue("$label: expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
    }

    private fun exportDirectory(): File = File(System.getProperty("jarvys.mascot.outputDir")
        ?: System.getenv("JARVYS_MASCOT_OUTPUT_DIR") ?: "build/test-results/bot-mascot-scene-compiler")
        .apply { assertTrue(isDirectory || mkdirs()) }

    @Test fun authoredProductFixturesExportDeterministicBytesForIndependentRuntime() {
        val expected = linkedMapOf(
            "nimbo" to BotMascotSceneCompiler.Complexity(59, 44, 3, 576, 621, 1978),
            "folio" to BotMascotSceneCompiler.Complexity(60, 45, 4, 558, 589, 1932),
        )
        val results = JSONArray()
        val output = exportDirectory()
        expected.forEach { (name, counts) ->
            val source = fixture(name)
            val compiled = BotMascotSceneCompiler.compileProduct(source)
            assertEquals("bot-mascot-v1", compiled.validation.contract)
            assertEquals(counts, compiled.validation.complexity)
            assertEquals(source.toByteArray(StandardCharsets.UTF_8).size, compiled.validation.sourceBytes)
            assertArrayEquals(compiled.bytes, BotMascotSceneCompiler.compileProduct(source).bytes)
            assertArrayEquals(compiled.bytes, BotMascotSceneCompiler.compileProduct(source.toByteArray(StandardCharsets.UTF_8)).bytes)
            assertEquals(compiled.validation, BotMascotSceneCompiler.validateProduct(source))
            assertTrue(compiled.bytes.size <= BotMascotSceneCompiler.MAX_OUTPUT_BYTES)
            val decoded = decodeBinary(compiled.bytes)
            assertEquals(counts.binaryObjectCount, decoded.records.size)
            val modes = BotMascotSceneCompiler.productModeNames
            assertEquals(modes + modes.map { it + "Reduced" }, decoded.records.filter { it.type == 31 }.map { it.text(55) })
            assertEquals(18, decoded.records.count { it.type == 61 })
            assertEquals(1, decoded.records.count { it.type == 53 })
            assertEquals(1, decoded.records.count { it.type == 435 })
            File(output, "$name.riv").writeBytes(compiled.bytes)
            results.put(JSONObject().put("name", name).put("contract", compiled.validation.contract)
                .put("sourceBytes", compiled.validation.sourceBytes).put("bytes", compiled.bytes.size)
                .put("sha256", compiled.sha256).put("nodeCount", counts.nodeCount)
                .put("trackCount", counts.trackCount).put("keyframeCount", counts.keyframeCount)
                .put("binaryObjectCount", counts.binaryObjectCount))
        }
        assertFalse(File(output, "nimbo.riv").readBytes().contentEquals(File(output, "folio.riv").readBytes()))
        File(output, "kotlin-product-results.json").writeText(JSONObject()
            .put("scope", "Deterministic Kotlin serialization and structure only; independent runtime and Android-device validation are separate")
            .put("format", "7.4").put("mascots", results).toString(2) + "\n")
    }

    @Test fun metadataStringLimitIsExplicitBoundedAndDoesNotWidenSceneDefaults() {
        fun source(length: Int) = JSONObject().put("visualDescription", "é".repeat(length)).toString().toByteArray(StandardCharsets.UTF_8)
        assertEquals(1200, BotMascotSceneCompiler.parseBoundedJsonObject(source(1200), 1200).getString("visualDescription").length)
        assertEquals(256, BotMascotSceneCompiler.parseBoundedJsonObject(source(256)).getString("visualDescription").length)
        assertTrue(runCatching { BotMascotSceneCompiler.parseBoundedJsonObject(source(257)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { BotMascotSceneCompiler.parseBoundedJsonObject(source(1201), 1200) }.exceptionOrNull() is IllegalArgumentException)
        for (limit in listOf(-1, 0, 1201, Int.MAX_VALUE)) {
            assertTrue(runCatching { BotMascotSceneCompiler.parseBoundedJsonObject(source(1), limit) }.exceptionOrNull() is IllegalArgumentException)
        }
        rejectsProduct("metadata override never widens product scenes") { it.put("visualDescription", "x".repeat(1200)) }
    }

    @Test fun sharedStrictObjectParserAllowsStoreSchemasWithoutRelaxingSyntax() {
        val source = "{\"schema\":1,\"hash\":\"abcd\",\"label\":\"A 'quoted' [label]\",\"details\":{\"enabled\":true}}"
        val parsed = BotMascotSceneCompiler.parseBoundedJsonObject(source.toByteArray(StandardCharsets.UTF_8))
        assertEquals(1L, parsed.getLong("schema"))
        assertEquals("A 'quoted' [label]", parsed.getString("label"))
        assertTrue(parsed.getJSONObject("details").getBoolean("enabled"))
        assertEquals(18, BotMascotSceneCompiler.parseBoundedJsonObject(fixture("nimbo").toByteArray()).getJSONObject("animations").length())
        fun reject(input: String) {
            assertTrue(runCatching { BotMascotSceneCompiler.parseBoundedJsonObject(input.toByteArray(StandardCharsets.UTF_8)) }
                .exceptionOrNull() is IllegalArgumentException)
        }
        reject("{'schema':1}")
        reject("{\"schema\":/* comment */1}")
        reject("{\"schema\":1,// comment\n\"hash\":\"abcd\"}")
        reject("{\"schema\":1,\"schema\":2}")
        reject("{\"schema\":1} trailing")
        reject("[]")
        reject("{\"label\":\"'\",\"payload\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}")
        reject("{\"label\":\"escaped \\\" [\",\"payload\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}")
        reject("""{"label":'double quote "',"payload":""" + "[".repeat(100) + "0" + "]".repeat(100) + "}")
        reject("{" + (0 until 19).joinToString(",") { "\"k$it\":0" } + "}")
        assertTrue(runCatching { BotMascotSceneCompiler.parseBoundedJsonObject(byteArrayOf(0xff.toByte())) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { BotMascotSceneCompiler.parseBoundedJsonObject(ByteArray(BotMascotSceneCompiler.MAX_SOURCE_BYTES + 1)) }
            .exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun modernThreeStateBytesMatchIndependentPublicFormatProbe() {
        val expected = mapOf(
            "miga" to (1489 to "c2d95a65a9cb92d5334d91b4892826949da48e3138c28d9d3074733692e15a90"),
            "tallo" to (1344 to "fb0307dfa2da512ee4400ce034fe7828c27246acf0667322b8a07bb073cf5aca"),
        )
        expected.forEach { (name, golden) ->
            val result = BotMascotSceneCompiler.compileViewModelProof(fixture(name))
            assertEquals(golden.first, result.bytes.size)
            assertEquals(golden.second, result.sha256)
            assertNull(result.validation.contract)
            assertArrayEquals(result.bytes, BotMascotSceneCompiler.compileViewModelProof(fixture(name).toByteArray()).bytes)
            assertEquals(if (name == "miga") 146 else 126, result.validation.complexity.binaryObjectCount)
            File(exportDirectory(), "$name-viewmodel.riv").writeBytes(result.bytes)
        }
    }

    @Test fun productRequiresExplicitExactVersionAndIsSeparateFromProof() {
        rejectsProduct("missing contract") { it.remove("contract") }
        rejectsProduct("unknown contract") { it.put("contract", "bot-mascot-v2") }
        rejectsProduct("contract type") { it.put("contract", 1) }
        rejectsProduct("extra root version") { it.put("version", 1) }
        rejects(productScene().toString(), "proof rejects product source")
        assertTrue(runCatching { BotMascotSceneCompiler.compileProduct(fixture()) }.exceptionOrNull() is IllegalArgumentException)
        val source = productScene().toString()
        val result = BotMascotSceneCompiler.compileProduct(source)
        assertEquals("bot-mascot-v1", result.validation.contract)
        assertEquals(7, result.validation.formatMajor)
        assertEquals(4, result.validation.formatMinor)
        assertEquals(result.validation, BotMascotSceneCompiler.validateProduct(source))
        assertEquals(result.validation, BotMascotSceneCompiler.validateProduct(source.toByteArray()))
        assertArrayEquals(result.bytes, BotMascotSceneCompiler.compileProduct(source.toByteArray()).bytes)
        assertArrayEquals(result.bytes, BotMascotSceneCompiler.compileProduct(source).bytes)
        assertEquals(321, result.validation.complexity.binaryObjectCount)
        File(exportDirectory(), "contract-probe.riv").writeBytes(result.bytes)
        File(exportDirectory(), "contract-probe.json").writeText(source)
    }

    @Test fun productRequiresExactlyAllEighteenTimelinesAndCompleteResets() {
        for (name in BotMascotSceneCompiler.productModeNames) {
            rejectsProduct("missing normal state") { it.getJSONObject("animations").remove(name) }
            rejectsProduct("missing Reduced variant") { it.getJSONObject("animations").remove(name + "Reduced") }
        }
        rejectsProduct("unknown state") { it.getJSONObject("animations").put("Active", animation(it)) }
        rejectsProduct("case-sensitive state") { it.getJSONObject("animations").put("thinking", it.getJSONObject("animations").remove("Thinking")) }
        rejectsProduct("incomplete reset") { track(it, "Thinking").put("property", "y") }
        rejectsProduct("no frame zero") { track(it, "Done").put("keys", keys(1 to 1)) }
        for (name in BotMascotSceneCompiler.productModeNames) {
            rejectsProduct("Reduced must not loop") { animation(it, name + "Reduced").put("loop", true) }
            rejectsProduct("Reduced must have one key") { track(it, name + "Reduced").put("keys", keys(0 to 1, 1 to 1)) }
            rejectsProduct("Reduced requires frame zero") { track(it, name + "Reduced").put("keys", keys(1 to 1)) }
        }
    }

    @Test fun eachNormalProductStateRequiresMotionAfterFloat32Serialization() {
        for (name in BotMascotSceneCompiler.productModeNames) {
            rejectsProduct("all-static normal state") { track(it, name).put("keys", keys(0 to 1, 1 to 1, 2 to 1)) }
            rejectsProduct("motion lost in float32 rounding") { track(it, name).put("keys", keys(0 to 1.0, 1 to 1.00000001, 2 to 1.0)) }
            rejectsProduct("signed zero is not motion") { track(it, name).put("keys", keys(0 to 0.0, 1 to -0.0, 2 to 0.0)) }
        }
        // A state may retain explicit static reset tracks as long as one track moves.
        val mixed = productScene(2).apply {
            BotMascotSceneCompiler.productModeNames.forEach { name ->
                track(this, name, 1).put("keys", keys(0 to 0))
            }
        }
        assertTrue(BotMascotSceneCompiler.compileProduct(mixed.toString()).bytes.isNotEmpty())
        // The historical proof remains permissive; only the versioned product contract changes.
        val legacyStatic = scene().apply {
            for (name in listOf("Idle", "Active")) {
                val tracks = animation(this, name).getJSONArray("tracks")
                repeat(tracks.length()) { index ->
                    val value = tracks.getJSONObject(index)
                    value.put("keys", JSONArray().put(value.getJSONArray("keys").getJSONArray(0)))
                }
            }
        }.toString()
        assertTrue(BotMascotSceneCompiler.compile(legacyStatic).bytes.isNotEmpty())
        assertTrue(BotMascotSceneCompiler.compileViewModelProof(legacyStatic).bytes.isNotEmpty())
    }

    @Test fun productAggregateBudgetsRetainSixtyFourTracksPerState() {
        assertEquals(192, BotMascotSceneCompiler.MAX_TOTAL_TRACKS)
        assertEquals(12288, BotMascotSceneCompiler.MAX_TOTAL_KEYFRAMES)
        assertEquals(1152, BotMascotSceneCompiler.MAX_PRODUCT_TOTAL_TRACKS)
        assertEquals(73728, BotMascotSceneCompiler.MAX_PRODUCT_TOTAL_KEYFRAMES)
        val source = productScene(64).toString()
        assertTrue(source.toByteArray().size <= BotMascotSceneCompiler.MAX_SOURCE_BYTES)
        val result = BotMascotSceneCompiler.compileProduct(source)
        assertEquals(1152, result.validation.complexity.trackCount)
        assertEquals(2304, result.validation.complexity.keyframeCount)
        assertTrue(result.bytes.size <= BotMascotSceneCompiler.MAX_OUTPUT_BYTES)
        assertTrue(runCatching { BotMascotSceneCompiler.compileProduct(productScene(65).toString()) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { BotMascotSceneCompiler.compileProduct(" ".repeat(BotMascotSceneCompiler.MAX_SOURCE_BYTES + 1)) }.exceptionOrNull() is IllegalArgumentException)
        rejectsProduct("preserved node bound") { it.put("nodes", JSONArray().apply {
            repeat(97) { put(JSONObject().put("kind", "group").put("name", "N$it")) }
        }) }
        rejectsProduct("preserved per-track key bound") { track(it).put("keys", JSONArray().apply {
            repeat(65) { put(JSONArray().put(it).put(0)) }
        }) }
    }

    @Test fun productHasFixedFileViewModelDefaultAssociationAndNoLegacyInputs() {
        val result = BotMascotSceneCompiler.compileProduct(productScene().toString())
        val decoded = decodeBinary(result.bytes)
        val records = decoded.records
        assertEquals(listOf(23, 435, 431, 448, 437, 442, 449, 1), records.take(8).map { it.type })
        assertEquals("MascotState", records[1].text(557))
        assertEquals("mode", records[2].text(557))
        assertEquals("reducedMotion", records[3].text(557))
        assertEquals("Default", records[4].text(4))
        assertEquals(0L, records[4].fields[566])
        assertEquals(0L, records[5].fields[554]); assertEquals(0f, records[5].fields[575])
        assertEquals(1L, records[6].fields[554]); assertEquals(0L, records[6].fields[593])
        assertEquals("Mascot", records[7].text(4))
        assertEquals(0L, records[7].fields[583]); assertEquals(0L, records[7].fields[236])
        assertEquals(256f, records[7].fields[7]); assertEquals(256f, records[7].fields[8])
        assertTrue(records.subList(1, 7).all { 5 !in it.fields })
        assertEquals("MascotController", records.single { it.type == 53 }.text(55))
        assertTrue(records.none { it.type in setOf(56, 59, 70, 71) })
        assertEquals(result.validation.complexity.binaryObjectCount, records.size)
        assertEquals(1, decoded.types[588])
        assertEquals(1, decoded.types[557]); assertEquals(2, decoded.types[575])
        assertEquals(0, decoded.types[593]); assertEquals(0, decoded.types[647]); assertEquals(2, decoded.types[652])
    }

    @Test fun productControllerBindsFixedOrderedConditionsToEveryModeAndReducedVariant() {
        val records = decodeBinary(BotMascotSceneCompiler.compileProduct(productScene().toString()).bytes).records
        val modes = listOf("Idle", "Thinking", "Working", "Queued", "WaitingProvider", "WaitingUser", "Done", "Error", "Interrupted")
        assertEquals(modes, BotMascotSceneCompiler.productModeNames)
        assertEquals(modes + modes.map { it + "Reduced" }, records.filter { it.type == 31 }.map { it.text(55) })
        assertEquals((0L..17L).toList(), records.filter { it.type == 61 }.map { it.fields[149] })
        assertEquals(19, records.count { it.type == 65 }) // One entry plus 18 branches.
        val any = records.indexOfFirst { it.type == 62 }
        var cursor = any + 1
        for (reduced in listOf(true, false)) repeat(9) { mode ->
            assertEquals(65, records[cursor].type)
            assertEquals((3 + mode + if (reduced) 9 else 0).toLong(), records[cursor++].fields[151])
            val number = records.subList(cursor, cursor + 5); cursor += 5
            assertEquals(listOf(482, 473, 447, 479, 484), number.map { it.type })
            assertEquals(0L, number[0].fields[650]); assertEquals(mode.toFloat(), number[4].fields[652])
            assertEquals(636L, number[2].fields[586]); assertEquals(0L, number[2].fields[587])
            assertArrayEquals(byteArrayOf(0, 0), number[2].fields[588] as ByteArray)
            val boolean = records.subList(cursor, cursor + 5); cursor += 5
            assertEquals(listOf(482, 472, 447, 479, 481), boolean.map { it.type })
            assertEquals(0L, boolean[0].fields[650]); assertEquals(if (reduced) 1L else 0L, boolean[4].fields[647])
            assertEquals(634L, boolean[2].fields[586]); assertEquals(0L, boolean[2].fields[587])
            assertArrayEquals(byteArrayOf(0, 1), boolean[2].fields[588] as ByteArray)
        }
        assertEquals(64, records[cursor].type)
        assertEquals(36, records.count { it.type == 482 })
        assertTrue(records.filter { it.type == 447 }.all { it.fields.keys == setOf(586, 587, 588) })
    }

    @Test fun explicitBytesPrimitiveKeepsArbitraryBytesAndUsesTocKindOne() {
        val payload = byteArrayOf(0, 0xff.toByte(), 0x80.toByte(), 1)
        val binary = BotMascotSceneCompiler.Binary()
        binary.obj(447, BotMascotSceneCompiler.Property(588, BotMascotSceneCompiler.Primitive.BYTES, payload))
        val result = decodeBinary(binary.finish())
        assertEquals(1, result.types[588])
        assertArrayEquals(payload, result.records.single().fields[588] as ByteArray)
        for (size in listOf(0, BotMascotSceneCompiler.MAX_RAW_PROPERTY_BYTES)) {
            val bounded = BotMascotSceneCompiler.Binary()
            bounded.obj(447, BotMascotSceneCompiler.Property(588, BotMascotSceneCompiler.Primitive.BYTES, ByteArray(size)))
            assertEquals(size, (decodeBinary(bounded.finish()).records.single().fields[588] as ByteArray).size)
        }
        for (value in listOf("00 01", ByteArray(BotMascotSceneCompiler.MAX_RAW_PROPERTY_BYTES + 1))) {
            assertTrue(runCatching {
                BotMascotSceneCompiler.Binary().obj(447, BotMascotSceneCompiler.Property(588, BotMascotSceneCompiler.Primitive.BYTES, value))
            }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun propertyKindsCannotConflictEvenWhenSharingWireKind() {
        for (kind in listOf(BotMascotSceneCompiler.Primitive.UINT, BotMascotSceneCompiler.Primitive.STRING)) {
            val binary = BotMascotSceneCompiler.Binary()
            binary.obj(447, BotMascotSceneCompiler.Property(588, BotMascotSceneCompiler.Primitive.BYTES, byteArrayOf(0, 1)))
            assertTrue(runCatching {
                binary.obj(447, BotMascotSceneCompiler.Property(588, kind, if (kind == BotMascotSceneCompiler.Primitive.UINT) 0L else "path"))
            }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching {
            BotMascotSceneCompiler.Binary().obj(449, BotMascotSceneCompiler.Property(593, BotMascotSceneCompiler.Primitive.BOOLEAN, 1L))
        }.exceptionOrNull() is IllegalArgumentException)
    }

    private data class DecodedRecord(val type: Int, val fields: Map<Int, Any>) {
        fun text(key: Int) = String(fields.getValue(key) as ByteArray, StandardCharsets.UTF_8)
    }
    private data class DecodedBinary(val types: Map<Int, Int>, val records: List<DecodedRecord>)

    /** Independent test decoder for the writer's four public wire types, not an app input API. */
    private fun decodeBinary(bytes: ByteArray): DecodedBinary {
        var cursor = 0
        fun byte(): Int { check(cursor < bytes.size); return bytes[cursor++].toInt() and 255 }
        fun uint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val value = byte()
                result = result or ((value and 127).toLong() shl shift)
                if (value < 128) return result
                shift += 7; check(shift <= 28)
            }
        }
        fun word(): Long = (0 until 4).fold(0L) { result, index -> result or (byte().toLong() shl (8 * index)) }
        assertEquals("RIVE", String(bytes.copyOfRange(0, 4), StandardCharsets.US_ASCII)); cursor = 4
        assertEquals(7L, uint()); assertEquals(4L, uint()); assertEquals(0L, uint())
        val keys = mutableListOf<Int>()
        while (true) { val key = uint().toInt(); if (key == 0) break; keys.add(key) }
        assertEquals(keys.sorted(), keys)
        val types = linkedMapOf<Int, Int>()
        keys.chunked(4).forEach { group ->
            val packed = word()
            assertEquals(0L, packed ushr 8)
            group.forEachIndexed { index, key -> types[key] = ((packed ushr (2 * index)) and 3).toInt() }
        }
        val records = mutableListOf<DecodedRecord>()
        while (cursor < bytes.size) {
            val type = uint().toInt()
            val fields = linkedMapOf<Int, Any>()
            while (true) {
                val key = uint().toInt()
                if (key == 0) break
                check(key !in fields)
                fields[key] = when (types.getValue(key)) {
                    0 -> uint()
                    1 -> { val size = uint().toInt(); check(size >= 0 && size <= bytes.size - cursor)
                        bytes.copyOfRange(cursor, cursor + size).also { cursor += size } }
                    2 -> java.lang.Float.intBitsToFloat(word().toInt())
                    3 -> word()
                    else -> error("Unknown wire type")
                }
            }
            records.add(DecodedRecord(type, fields))
        }
        return DecodedBinary(types, records)
    }
}
