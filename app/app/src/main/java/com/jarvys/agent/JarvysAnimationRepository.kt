package com.jarvys.agent

import android.content.Context
import android.os.SystemClock
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.jarvys.agent.LocalDiagnostics.AnimationStage
import com.jarvys.agent.LocalDiagnostics.AnimationStatus
import com.jarvys.agent.LocalDiagnostics.AnimationId
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** NEW reconstruction from APK95 (04f2b91a…522205), not recovered historical source. */
data class JarvysAnimationEntry(val url: URI, val bytes: Long, val sha256: String)

data class JarvysAnimationSelection(
    val requestedPackage: String,
    val packageId: String,
    val animations: Map<String, JarvysAnimationEntry>
) {
    val sad: JarvysAnimationEntry? get() = animations["sad"]
}

data class JarvysVerifiedAnimation(
    val file: File,
    val sha256: String,
    val bytes: Long,
    val packageId: String,
    val animationId: String = "sad"
)

object JarvysAnimationSite {
    const val INDEX = "https://www.jarvys-agent.com/animations/"
    private val hosts = setOf("www.jarvys-agent.com", "jarvys-agent.com")

    fun checked(raw: String): URI {
        val uri = try { URI(raw) } catch (_: URISyntaxException) {
            throw IOException("Invalid animation URL")
        }
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" ||
            uri.host?.lowercase(Locale.ROOT) !in hosts || uri.port !in setOf(-1, 443) ||
            uri.rawUserInfo != null || uri.rawFragment != null || uri.isOpaque
        ) throw IOException("Animation URL is outside the Jarvys site")
        return uri.normalize()
    }
}

object JarvysAnimationCatalog {
    val remoteIds: Set<String> = setOf("sad", "working", "thinking", "waiting", "interrupted", "taunt")

    fun packageId(value: String?): String = value?.takeUnless { it.isBlank() } ?: "default"

    fun requireAnimationId(value: String): String {
        require(value in remoteIds) { "Unknown Jarvys animation identity" }
        return value
    }

    fun read(input: InputStream, requested: String, token: CancellationToken): JarvysAnimationSelection {
        val requestedPackage = packageId(requested)
        var fallback = emptyMap<String, JarvysAnimationEntry>()
        var selected = emptyMap<String, JarvysAnimationEntry>()
        var selectedSeen = false
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            val seen = HashSet<String>()
            var schema: Long? = null
            var packagesSeen = false
            while (reader.hasNext()) {
                token.throwIfCancelled()
                when (val name = reader.nextName()) {
                    "schema" -> { unique(seen, name); schema = integer(reader) }
                    "packages" -> {
                        unique(seen, name)
                        reader.beginObject()
                        var defaultSeen = false
                        while (reader.hasNext()) {
                            token.throwIfCancelled()
                            when (reader.nextName()) {
                                "default" -> {
                                    if (defaultSeen) throw IOException("Duplicate animation package")
                                    fallback = readPackage(reader, token)
                                    defaultSeen = true
                                    if (requestedPackage == "default") selectedSeen = true
                                }
                                requestedPackage -> {
                                    if (selectedSeen) throw IOException("Duplicate animation package")
                                    selected = readPackage(reader, token)
                                    selectedSeen = true
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        packagesSeen = true
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT || schema != 1L || !packagesSeen) {
                throw IOException("Invalid animation catalog")
            }
        }
        return if (selectedSeen && requestedPackage != "default") {
            JarvysAnimationSelection(requestedPackage, requestedPackage, selected)
        } else JarvysAnimationSelection(requestedPackage, "default", fallback)
    }

    private fun readPackage(reader: JsonReader, token: CancellationToken): Map<String, JarvysAnimationEntry> {
        val animations = LinkedHashMap<String, JarvysAnimationEntry>()
        reader.beginObject()
        var animationsSeen = false
        while (reader.hasNext()) {
            token.throwIfCancelled()
            if (reader.nextName() == "animations") {
                if (animationsSeen) throw IOException("Duplicate animation collection")
                reader.beginObject()
                val seen = HashSet<String>()
                while (reader.hasNext()) {
                    token.throwIfCancelled()
                    val id = reader.nextName()
                    if (id == "idle" || id in remoteIds) {
                        unique(seen, id)
                        val entry = readEntry(reader)
                        if (id in remoteIds) animations[id] = entry
                    } else reader.skipValue()
                }
                reader.endObject()
                animationsSeen = true
            } else reader.skipValue()
        }
        reader.endObject()
        if (!animationsSeen) throw IOException("Missing animation collection")
        return animations
    }

    internal fun readEntry(reader: JsonReader): JarvysAnimationEntry {
        val seen = HashSet<String>()
        var url: URI? = null
        var bytes: Long? = null
        var sha256: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = reader.nextName()) {
                "url" -> { unique(seen, name); url = JarvysAnimationSite.checked(string(reader)) }
                "bytes" -> {
                    unique(seen, name)
                    bytes = integer(reader).takeIf { it > 0 } ?: throw IOException("Invalid animation size")
                }
                "sha256" -> {
                    unique(seen, name)
                    sha256 = string(reader).lowercase(Locale.ROOT).takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                        ?: throw IOException("Invalid animation digest")
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return JarvysAnimationEntry(url ?: throw IOException("Missing animation URL"),
            bytes ?: throw IOException("Missing animation size"), sha256 ?: throw IOException("Missing animation digest"))
    }

    internal fun string(reader: JsonReader): String {
        if (reader.peek() != JsonToken.STRING) throw IOException("Expected catalog string")
        return reader.nextString()
    }

    private fun integer(reader: JsonReader): Long {
        if (reader.peek() != JsonToken.NUMBER) throw IOException("Expected catalog integer")
        val raw = reader.nextString()
        if (!raw.matches(Regex("0|[1-9][0-9]*"))) throw IOException("Expected positive catalog integer")
        return raw.toLongOrNull() ?: throw IOException("Catalog integer overflow")
    }

    private fun unique(seen: MutableSet<String>, name: String) {
        if (!seen.add(name)) throw IOException("Duplicate catalog field")
    }
}

interface JarvysAnimationTransport {
    interface Response : Closeable {
        val status: Int
        val contentLength: Long
        val body: InputStream
    }
    fun get(uri: URI, token: CancellationToken): Response
}

class JarvysAnimationHttpTransport(
    private val connectionFactory: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }
) : JarvysAnimationTransport {
    override fun get(uri: URI, token: CancellationToken): JarvysAnimationTransport.Response {
        var current = JarvysAnimationSite.checked(uri.toString())
        val visited = HashSet<URI>()
        while (true) {
            token.throwIfCancelled()
            if (!visited.add(current) || visited.size > 8) throw IOException("Animation redirect loop")
            // Parity with APK95: fail closed rather than leak process-default cookies.
            if (CookieHandler.getDefault() != null) throw IOException("Animation transport requires no cookie handler")
            val connection = connectionFactory(current)
            val unregister = token.registerCancelAction { connection.disconnect() }
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.useCaches = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                val status = connection.responseCode
                token.throwIfCancelled()
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect URL")
                    current = JarvysAnimationSite.checked(current.resolve(location).toString())
                    unregister.run()
                    connection.disconnect()
                    continue
                }
                if (status != 200) throw IOException("Animation request was unsuccessful")
                val encoding = connection.getHeaderField("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                    throw IOException("Unexpected animation transfer encoding")
                }
                val rawLength = connection.getHeaderField("Content-Length")
                val length = if (rawLength == null) -1L else rawLength.toLongOrNull()?.takeIf { it >= 0 }
                    ?: throw IOException("Invalid animation response length")
                val stream = connection.inputStream
                return object : JarvysAnimationTransport.Response {
                    override val status: Int = status
                    override val contentLength: Long = length
                    override val body: InputStream = stream
                    override fun close() {
                        try { body.close() } finally { unregister.run(); connection.disconnect() }
                    }
                }
            } catch (failure: Throwable) {
                unregister.run()
                connection.disconnect()
                throw failure
            }
        }
    }
}

/** Diagnostic extension added after reconstruction; enum-only metadata, no external text. */
internal fun interface JarvysAnimationObserver {
    fun event(stage: AnimationStage, status: AnimationStatus, id: AnimationId, failure: Throwable?)
}

class JarvysAnimationCache(
    private val parent: File,
    private val transport: JarvysAnimationTransport,
    private val usableSpace: (File) -> Long = { it.usableSpace }
) {
    private val lock = Any()
    private val root: File get() = File(parent.canonicalFile, "jarvys_animations")
    private var initialized = false
    private val exposed = HashSet<String>()
    internal var diagnostics = JarvysAnimationObserver { _, _, _, _ -> }

    private fun diagnosticId(id: String): AnimationId = when (id) {
        "idle" -> AnimationId.IDLE
        "sad" -> AnimationId.SAD
        "thinking" -> AnimationId.THINKING
        "working" -> AnimationId.WORKING
        "waiting" -> AnimationId.WAITING
        "interrupted" -> AnimationId.INTERRUPTED
        "taunt" -> AnimationId.TAUNT
        else -> AnimationId.CATALOG
    }

    private fun report(stage: AnimationStage, status: AnimationStatus, id: String, failure: Throwable? = null) {
        try { diagnostics.event(stage, status, diagnosticId(id), failure) } catch (_: Throwable) { }
    }

    private inline fun <T> traced(stage: AnimationStage, id: String,
        ready: (T) -> AnimationStatus = { AnimationStatus.READY }, action: () -> T
    ): T {
        report(stage, AnimationStatus.STARTED, id)
        try {
            return action().also { report(stage, ready(it), id) }
        } catch (failure: Exception) {
            if (failure !is CancellationException) report(stage, AnimationStatus.FAILED, id, failure)
            throw failure
        }
    }

    fun cached(requested: String, token: CancellationToken): JarvysVerifiedAnimation? = cached(requested, "sad", token)

    fun cached(requested: String, animationId: String, token: CancellationToken): JarvysVerifiedAnimation? = traced(AnimationStage.CACHE, animationId,
        { value -> if (value == null) AnimationStatus.UNAVAILABLE else AnimationStatus.READY }
    ) { synchronized(lock) {
        JarvysAnimationCatalog.requireAnimationId(animationId)
        prepareDirectory()
        val selection = readSaved()?.takeIf { it.requestedPackage == JarvysAnimationCatalog.packageId(requested) }
        if (!initialized) { cleanBeforePublication(selection); initialized = true }
        selection?.let { verified(it, animationId, token) }?.also { exposed.add(it.file.name) }
    } }

    fun refresh(requested: String, token: CancellationToken, publish: (JarvysVerifiedAnimation?) -> Unit) =
        refresh(requested, setOf("sad"), token) { _, value -> publish(value) }

    fun refresh(requested: String, animationIds: Set<String>, token: CancellationToken,
        publish: (String, JarvysVerifiedAnimation?) -> Unit
    ): Unit = synchronized(lock) {
        val ids = checkedIds(animationIds)
        if (ids.isNotEmpty()) {
            prepareDirectory()
            token.throwIfCancelled()
            val selection = traced(AnimationStage.INDEX, "catalog") {
                transport.get(JarvysAnimationSite.checked(JarvysAnimationSite.INDEX), token).use { response ->
                    if (response.status != 200) throw IOException("Invalid animation catalog response")
                    JarvysAnimationCatalog.read(response.body, requested, token)
                }
            }
            val existing = ids.associateWith { id -> try { verified(selection, id, token) } catch (_: IOException) { null } }
            // Commit the new identity before publishing null, so old files cannot be resurrected.
            save(selection, token) { publishExisting(existing, publish) }
            fulfillSelection(selection, ids, existing, token, publish)
        }
    }

    fun fulfill(requested: String, animationIds: Set<String>, token: CancellationToken,
        publish: (String, JarvysVerifiedAnimation?) -> Unit
    ): Unit = synchronized(lock) {
        val ids = checkedIds(animationIds)
        if (ids.isNotEmpty()) {
            prepareDirectory()
            val selection = readSaved()?.takeIf { it.requestedPackage == JarvysAnimationCatalog.packageId(requested) }
            val existing = ids.associateWith { id -> try { selection?.let { verified(it, id, token) } } catch (_: IOException) { null } }
            if (!token.runIfActive { publishExisting(existing, publish) }) token.throwIfCancelled()
            if (selection != null) fulfillSelection(selection, ids, existing, token, publish)
        }
    }

    private fun checkedIds(ids: Set<String>): Set<String> = ids.mapTo(LinkedHashSet(), JarvysAnimationCatalog::requireAnimationId)

    private fun publishExisting(existing: Map<String, JarvysVerifiedAnimation?>, publish: (String, JarvysVerifiedAnimation?) -> Unit) {
        existing.forEach { (id, value) ->
            if (value != null) exposed.add(value.file.name)
            report(AnimationStage.CACHE, if (value == null) AnimationStatus.UNAVAILABLE else AnimationStatus.READY, id)
            publish(id, value)
        }
    }

    private fun fulfillSelection(selection: JarvysAnimationSelection, ids: Set<String>,
        existing: Map<String, JarvysVerifiedAnimation?>, token: CancellationToken,
        publish: (String, JarvysVerifiedAnimation?) -> Unit
    ) {
        var firstFailure: IOException? = null
        for (id in ids) {
            token.throwIfCancelled()
            val entry = selection.animations[id] ?: continue
            if (existing[id] != null) continue
            try {
                val value = traced(AnimationStage.DOWNLOAD, id) { download(selection, id, entry, token) }
                if (!token.runIfActive {
                    exposed.add(value.file.name)
                    report(AnimationStage.CACHE, AnimationStatus.READY, id)
                    publish(id, value)
                }) token.throwIfCancelled()
            } catch (failure: IOException) {
                token.throwIfCancelled()
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
    }

    private fun prepareDirectory() {
        val directory = root
        if (directory.canonicalFile != directory.absoluteFile) throw IOException("Unsafe animation cache directory")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Animation cache unavailable")
        if (directory.canonicalFile != directory.absoluteFile || !directory.isDirectory) throw IOException("Unsafe animation cache directory")
    }

    private fun child(name: String): File {
        val file = File(root, name)
        if (file.canonicalFile != file.absoluteFile || file.canonicalFile.parentFile != root) throw IOException("Unsafe animation cache file")
        if (file.exists() && !file.isFile) throw IOException("Animation cache entry is not a regular file")
        return file
    }

    private fun asset(animationId: String, entry: JarvysAnimationEntry): File =
        child("${JarvysAnimationCatalog.requireAnimationId(animationId)}-${entry.sha256}.riv")

    private fun verified(selection: JarvysAnimationSelection, animationId: String, token: CancellationToken): JarvysVerifiedAnimation? {
        val entry = selection.animations[animationId] ?: return null
        val file = asset(animationId, entry)
        if (!file.isFile || file.length() != entry.bytes) return null
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32_768)
            while (true) {
                token.throwIfCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                if (read.toLong() > entry.bytes - count) return null
                count += read
                digest.update(buffer, 0, read)
            }
        }
        token.throwIfCancelled()
        return if (count == entry.bytes && hex(digest.digest()) == entry.sha256) {
            JarvysVerifiedAnimation(file, entry.sha256, entry.bytes, selection.packageId, animationId)
        } else null
    }

    private fun download(selection: JarvysAnimationSelection, animationId: String, entry: JarvysAnimationEntry,
        token: CancellationToken
    ): JarvysVerifiedAnimation {
        val destination = asset(animationId, entry)
        if (destination.exists() && (destination.name in exposed || !destination.delete())) throw IOException("Animation destination is already in use")
        if (usableSpace(root) < entry.bytes) throw IOException("Insufficient animation cache storage")
        val staging = File.createTempFile("download-", ".part", root)
        try {
            if (staging.canonicalFile != staging.absoluteFile) throw IOException("Unsafe animation staging file")
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            transport.get(entry.url, token).use { response ->
                if (response.status != 200 || (response.contentLength >= 0 && response.contentLength != entry.bytes)) {
                    throw IOException("Animation response size does not match catalog")
                }
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(32_768)
                    while (true) {
                        token.throwIfCancelled()
                        val read = response.body.read(buffer)
                        if (read < 0) break
                        if (read.toLong() > entry.bytes - count) throw IOException("Animation exceeds declared size")
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        count += read
                    }
                    traced(AnimationStage.INTEGRITY, animationId) {
                        if (count != entry.bytes || hex(digest.digest()) != entry.sha256) throw IOException("Animation integrity failed")
                    }
                    token.throwIfCancelled()
                    output.fd.sync()
                }
            }
            token.throwIfCancelled()
            if (!token.runIfActive {
                child(destination.name)
                if (destination.exists()) throw IOException("Animation destination is already occupied")
                if (!staging.renameTo(destination)) throw IOException("Animation cache commit failed")
            }) token.throwIfCancelled()
            return JarvysVerifiedAnimation(destination, entry.sha256, entry.bytes, selection.packageId, animationId)
        } finally { staging.delete() }
    }

    private fun readSaved(): JarvysAnimationSelection? = try {
        val file = child("selection.json")
        if (!file.isFile) null else FileInputStream(file).use { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.isLenient = false
                val animations = LinkedHashMap<String, JarvysAnimationEntry>()
                val seen = HashSet<String>()
                var requested: String? = null
                var selected: String? = null
                var animationsSeen = false
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (!seen.add(name)) throw IOException("Duplicate cached catalog field")
                    when (name) {
                        "requestedPackage" -> requested = JarvysAnimationCatalog.string(reader)
                        "package" -> selected = JarvysAnimationCatalog.string(reader)
                        "animations" -> {
                            reader.beginObject()
                            val ids = HashSet<String>()
                            while (reader.hasNext()) {
                                val id = reader.nextName()
                                if (id !in JarvysAnimationCatalog.remoteIds || !ids.add(id)) throw IOException("Invalid cached animation identity")
                                animations[id] = JarvysAnimationCatalog.readEntry(reader)
                            }
                            reader.endObject()
                            animationsSeen = true
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT || !animationsSeen || requested == null || selected == null ||
                    JarvysAnimationCatalog.packageId(requested) != requested || JarvysAnimationCatalog.packageId(selected) != selected
                ) throw IOException("Invalid cached animation identity")
                JarvysAnimationSelection(requested, selected, animations)
            }
        }
    } catch (_: IOException) { null } catch (_: IllegalStateException) { null }

    private fun save(selection: JarvysAnimationSelection, token: CancellationToken, publish: () -> Unit) {
        val destination = child("selection.json")
        val staging = File.createTempFile("selection-", ".part", root)
        try {
            FileOutputStream(staging).use { output ->
                val writer = JsonWriter(OutputStreamWriter(output, Charsets.UTF_8))
                writer.beginObject().name("requestedPackage").value(selection.requestedPackage)
                    .name("package").value(selection.packageId).name("animations").beginObject()
                selection.animations.forEach { (id, entry) ->
                    writer.name(id).beginObject().name("url").value(entry.url.toString())
                        .name("bytes").value(entry.bytes).name("sha256").value(entry.sha256).endObject()
                }
                writer.endObject().endObject().flush()
                output.fd.sync()
            }
            if (!token.runIfActive {
                child(destination.name)
                if (!staging.renameTo(destination)) throw IOException("Animation identity commit failed")
                publish()
            }) token.throwIfCancelled()
        } finally { staging.delete() }
    }

    private fun cleanBeforePublication(selection: JarvysAnimationSelection?) {
        val keep = selection?.animations?.map { (id, entry) -> "$id-${entry.sha256}.riv" }?.toSet() ?: emptySet()
        root.listFiles()?.forEach { file ->
            val managed = file.name.matches(Regex("(?:sad|working|thinking|waiting|interrupted|taunt)-[0-9a-f]{64}\\.riv")) ||
                file.name.matches(Regex("(?:download|selection)-[a-zA-Z0-9-]+\\.part"))
            if (managed && file.name !in keep && file.name !in exposed && file.canonicalFile == file.absoluteFile && file.isFile) file.delete()
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

class JarvysAnimationRefreshGate(private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    private var inFlight = false
    private var lastAttempt: Long? = null
    @Synchronized fun tryStart(): Boolean {
        val now = clock()
        val previous = lastAttempt
        if (inFlight || (previous != null && now - previous < 30_000L)) return false
        inFlight = true
        lastAttempt = now
        return true
    }
    @Synchronized fun finished() { inFlight = false }
}

/** Functional addition: warm known remote identities without creating playback requests. */
internal fun jarvysAnimationRefreshIds(observed: Set<String>): Set<String> =
    LinkedHashSet<String>().apply {
        observed.forEach { add(JarvysAnimationCatalog.requireAnimationId(it)) }
        addAll(JarvysAnimationCatalog.remoteIds)
    }

object JarvysAnimationRepository {
    @Volatile private var instance: Background? = null

    fun sad(context: Context): StateFlow<JarvysVerifiedAnimation?> = animation(context, "sad")
    fun animation(context: Context, animationId: String): StateFlow<JarvysVerifiedAnimation?> =
        background(context).request(JarvysAnimationCatalog.requireAnimationId(animationId))
    fun requestRefresh(context: Context) { background(context).requestRefresh() }

    private fun background(context: Context): Background = instance ?: synchronized(this) {
        instance ?: Background(context.applicationContext).also { instance = it }
    }

    private class Background(private val context: Context) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val refreshGate = JarvysAnimationRefreshGate()
        private val cache by lazy {
            JarvysAnimationCache(context.cacheDir, JarvysAnimationIsolatedHttpTransport()).also { cache ->
                cache.diagnostics = JarvysAnimationObserver { stage, status, id, failure ->
                    LocalDiagnostics.animation(context, stage, status, id)
                    if (failure != null) LocalDiagnostics.recoverable(context, LocalDiagnostics.Origin.RUNTIME, failure)
                }
            }
        }
        private val lock = Any()
        private val states = LinkedHashMap<String, MutableStateFlow<JarvysVerifiedAnimation?>>()
        private val pending = LinkedHashSet<String>()
        private var workerActive = false
        private var refreshPending = false
        private var started = false

        fun request(id: String): StateFlow<JarvysVerifiedAnimation?> = synchronized(lock) {
            val state = states.getOrPut(id) { pending.add(id); MutableStateFlow(null) }
            if (!started) { started = true; refreshPending = refreshGate.tryStart() }
            launchPending()
            state.asStateFlow()
        }

        fun requestRefresh(): Unit = synchronized(lock) {
            if (states.isNotEmpty() && refreshGate.tryStart()) {
                refreshPending = true
                pending.addAll(states.keys)
                launchPending()
            }
        }

        private fun launchPending() {
            if (workerActive || (pending.isEmpty() && !refreshPending)) return
            workerActive = true
            scope.launch {
                while (true) {
                    val batch = synchronized(lock) {
                        if (pending.isEmpty() && !refreshPending) {
                            workerActive = false
                            null
                        } else {
                            val refresh = refreshPending
                            refreshPending = false
                            // Keep observed identities first; preload the remaining package assets on this same IO worker.
                            val ids = if (refresh) jarvysAnimationRefreshIds(states.keys) else pending.toSet()
                            pending.clear()
                            ids to refresh
                        }
                    } ?: return@launch
                    try {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            val token = CancellationToken.cancellable()
                            continuation.invokeOnCancellation { token.cancel() }
                            try {
                                val preference = try {
                                    context.getSharedPreferences("jarvys_ui_preferences", Context.MODE_PRIVATE)
                                        .getString("mascot_animation_package", "default")
                                } catch (_: ClassCastException) { "default" }
                                val requested = JarvysAnimationCatalog.packageId(preference)
                                val publish: (String, JarvysVerifiedAnimation?) -> Unit = { id, value ->
                                    synchronized(lock) { states[id]?.value = value }
                                }
                                for (id in batch.first) {
                                    val value = try { cache.cached(requested, id, token) } catch (_: IOException) { null }
                                    if (!token.runIfActive { publish(id, value) }) token.throwIfCancelled()
                                }
                                if (batch.second) cache.refresh(requested, batch.first, token, publish)
                                else cache.fulfill(requested, batch.first, token, publish)
                                continuation.resume(Unit)
                            } catch (failure: Exception) { continuation.resumeWithException(failure) }
                        }
                    } catch (cancelled: CancellationException) {
                        synchronized(lock) { workerActive = false }
                        throw cancelled
                    } catch (failure: Exception) {
                        // Storage/setup failures may occur before an index/download stage begins.
                        LocalDiagnostics.recoverable(context, LocalDiagnostics.Origin.RUNTIME, failure)
                        // APK95 recovered behavior: keep Idle fallback and continue pending work.
                    } finally {
                        if (batch.second) refreshGate.finished()
                    }
                }
            }
        }
    }
}
