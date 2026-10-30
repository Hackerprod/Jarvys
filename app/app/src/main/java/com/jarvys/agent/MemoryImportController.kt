package com.jarvys.agent

import android.content.Context
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** One explicit import lifetime. Neither a URI, a catalog entry nor a prepared plan is an authority grant. */
class MemoryImportController private constructor(private val context: Context, private val dataRoot: File) {
    private data class Seal(val identity: Pair<Long, Long>, val size: Long, val hash: String)
    private class Active(val token: CancellationToken) {
        val handle = MemoryImportOperationHandle()
        val lock = java.lang.Object()
        var worker = false
        var cleaning = false
        var closeUnconfirmed = false
        var submitted = false
        var root: File? = null
        var rootIdentity: Pair<Long, Long>? = null
        val files = LinkedHashMap<File, Seal>() // Six fixed artifacts, never one entry per imported record.
        var source: MemoryImportSource? = null
        var mapping: MemoryImportMapping? = null
        var prepared: PreparedMemoryImport? = null
        var unregister: Runnable? = null
        var cleanup: MemoryImportCleanupResult? = null
    }
    private val active = AtomicReference<Active?>()
    private val cleanupStatus = AtomicReference<MemoryImportCleanupResult?>()
    private fun bridge() = ChatDatabaseBridge.getInstance(context) // Never acquired by get()/opening the dialog.
    fun activeOperation(): MemoryImportOperationHandle? = active.get()?.handle
    fun isRunning() = active.get() != null
    fun lastCleanup(): MemoryImportCleanupResult? = cleanupStatus.get()
    fun cancelIfCurrent(expected: MemoryImportOperationHandle): Boolean {
        val operation = active.get() ?: return false
        if (operation.handle !== expected) return false
        operation.token.cancel(); return true
    }
    fun destinations(token: CancellationToken): List<String> {
        workerRequired(); token.throwIfCancelled()
        val current = active.get()
        if (current == null) return bridge().importMemoryDestinations(token)
        require(current.token === token) { "memory_import_token_invalid" }
        return phase(current) { bridge().importMemoryDestinations(token).also { token.throwIfCancelled() } }
    }
    private fun workerRequired() { check(Looper.myLooper() != Looper.getMainLooper()) { "memory_import_worker_required" } }
    private fun <T> phase(operation: Active, body: () -> T): T {
        workerRequired(); operation.token.throwIfCancelled()
        synchronized(operation.lock) {
            check(active.get() === operation && !operation.worker && !operation.cleaning && !operation.closeUnconfirmed) { "memory_import_busy" }
            operation.worker = true
        }
        try { return body() }
        finally { synchronized(operation.lock) { operation.worker = false; operation.lock.notifyAll() } }
    }
    fun read(token: CancellationToken, selectAndOpen: () -> MemoryImportInput): MemoryImportSource {
        workerRequired(); token.throwIfCancelled()
        val operation = Active(token)
        check(active.compareAndSet(null, operation)) { "memory_import_busy" }
        cleanupStatus.set(null)
        operation.unregister = token.registerCancelAction {
            if (active.get() === operation) cleanupStatus.set(MemoryImportCleanupResult("pending", true))
            CompletableFuture.runAsync { cleanup(operation) }
        }
        try {
            val received = phase(operation) {
                val cache = context.cacheDir.canonicalFile
                require(cache.path.startsWith(dataRoot.path + File.separator)) { "memory_import_private_path_invalid" }
                val root = Files.createTempDirectory(cache.toPath(), "memory-import-").toFile()
                operation.root = root; operation.rootIdentity = identity(root, true); Os.chmod(root.path, 448)
                val artifacts = Artifacts(operation)
                val raw = artifacts.create("raw.json")
                // The owner phase starts BEFORE the picker and includes metadata, read and physical close.
                val selected = selectAndOpen()
                useOwned(selected.stream, operation) { input ->
                    token.throwIfCancelled()
                    artifacts.reset(raw).use { output -> copy(input, output, token) }
                }
                token.throwIfCancelled(); artifacts.verify()
                val rawSeal = checkNotNull(operation.files[raw])
                val cells = artifacts.create("cells.bin"); val catalog = artifacts.create("catalog.bin")
                val parsed = artifacts.open(raw).use { input ->
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    InputStreamReader(input, decoder).use { reader ->
                        BufferedOutputStream(artifacts.reset(cells), 8192).use { cellOutput ->
                            DataOutputStream(BufferedOutputStream(artifacts.reset(catalog), 8192)).use { catalogOutput ->
                                MemoryImportParser(reader, cellOutput, catalogOutput, token).parse()
                            }
                        }
                    }
                }
                artifacts.verify(); token.throwIfCancelled()
                val data = MemoryImportData(raw, cells, catalog, rawSeal.hash, rawSeal.size, parsed.counts, parsed.riskyFieldCount,
                    parsed.hasExtension, token, artifacts::open)
                val mapping = bridge().importMemoryInitialize(data, artifacts, token)
                artifacts.verify(); token.throwIfCancelled()
                MemoryImportSource(this, data, operation.handle, selected.providerLabel, selected.displayName).also {
                    operation.source = it; operation.mapping = mapping
                }
            }
            token.throwIfCancelled(); return received
        } catch (failure: Throwable) { cleanup(operation); throw failure }
    }
    private fun operation(source: MemoryImportSource, token: CancellationToken): Active {
        require(source.owner === this) { "memory_import_source_invalid" }
        val current = active.get()
        check(current != null && current.source === source && current.handle === source.operationHandle && !current.submitted) { "memory_import_source_invalid" }
        require(current.token === token) { "memory_import_token_invalid" }
        return current
    }
    fun projectPage(source: MemoryImportSource, offset: Long, limit: Int = 32, token: CancellationToken): MemoryImportProjectPage {
        val current = operation(source, token)
        return phase(current) {
            val files = Artifacts(current); files.verify()
            val result = checkNotNull(current.mapping).page(current.handle, offset, limit, token)
            files.verify(); token.throwIfCancelled(); result
        }
    }
    fun setProject(source: MemoryImportSource, ref: MemoryImportProjectRef, destination: String, token: CancellationToken) {
        val current = operation(source, token)
        phase(current) {
            current.prepared = null // Invalidate before any journal mutation, including a failure.
            val files = Artifacts(current); files.verify()
            val owners = bridge().importMemoryDestinations(token)
            checkNotNull(current.mapping).set(current.handle, ref, destination, owners, token)
            files.verify(); token.throwIfCancelled()
        }
    }
    fun validate(source: MemoryImportSource, riskOffConfirmed: Boolean, token: CancellationToken): PreparedMemoryImport {
        val current = operation(source, token)
        return phase(current) {
            current.prepared = null // Resetting fixed plan.bin can never leave an earlier plan eligible.
            val files = Artifacts(current); files.verify()
            val offTicket = if (source.riskyFieldCount > 0) {
                require(riskOffConfirmed) { "memory_import_risk_consent_required" }
                val policy = MemoryWritePolicy.forContext(context)
                policy.captureTicket().also { ticket ->
                    policy.gate().commitManualImportWhileOff(ticket, { token.throwIfCancelled(); true }, java.util.concurrent.Callable { Unit })
                }
            } else null
            val mapping = checkNotNull(current.mapping)
            val plan = bridge().importMemoryPlan(source.data, mapping, files, token)
            files.verify(); token.throwIfCancelled()
            if (offTicket != null) MemoryWritePolicy.forContext(context).gate().commitManualImportWhileOff(
                offTicket, { token.throwIfCancelled(); true }, java.util.concurrent.Callable { Unit })
            PreparedMemoryImport(this, source, plan, mapping.version, offTicket).also { current.prepared = it }
        }
    }
    fun preview(source: MemoryImportSource, offset: Long, maxBytes: Int = 32768, token: CancellationToken): MemoryImportPreviewPage {
        val current = operation(source, token)
        require(offset >= 0 && offset <= source.sizeBytes && maxBytes in 4..32768) { "memory_import_preview_invalid" }
        return phase(current) {
            val files = Artifacts(current); files.verify()
            val result = files.open(source.data.raw).use { input ->
                val skip = ByteArray(8192); var remaining = offset
                while (remaining > 0) { token.throwIfCancelled(); val count = input.read(skip, 0, minOf(remaining, skip.size.toLong()).toInt()); check(count > 0); remaining -= count }
                val bytes = ByteArray(minOf(maxBytes.toLong(), source.sizeBytes - offset).toInt()); var size = 0
                while (size < bytes.size) { token.throwIfCancelled(); val count = input.read(bytes, size, bytes.size - size); check(count > 0); size += count }
                var end = size
                if (offset + size < source.sizeBytes && end > 0) {
                    var start = end - 1
                    while (start >= 0 && (bytes[start].toInt() and 0xc0) == 0x80) start--
                    if (start >= 0) {
                        val lead = bytes[start].toInt() and 255
                        val length = when { lead < 128 -> 1; (lead and 0xe0) == 0xc0 -> 2; (lead and 0xf0) == 0xe0 -> 3; else -> 4 }
                        if (end - start < length) end = start
                    }
                }
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, end)).toString()
                MemoryImportPreviewPage(text, if (offset + end < source.sizeBytes) offset + end else null, source.sizeBytes)
            }
            files.verify(); token.throwIfCancelled(); result
        }
    }
    /** Full decoded project/anchor value, selected only by a reference belonging to this operation. */
    fun previewProject(source: MemoryImportSource, ref: MemoryImportProjectRef, offset: Long,
        maxBytes: Int = 32768, token: CancellationToken): MemoryImportPreviewPage {
        val current = operation(source, token)
        return phase(current) {
            val files = Artifacts(current); files.verify()
            val cell = requireNotNull(checkNotNull(current.mapping).projectCell(current.handle, ref, token)) { "memory_import_project_undeclared" }
            require(cell.type == MemoryImportWire.STRING && offset >= 0 && offset <= cell.length && maxBytes in 4..32768) { "memory_import_preview_invalid" }
            val result = source.data.open(cell).use { input ->
                val skip = ByteArray(8192); var remaining = offset
                while (remaining > 0) { token.throwIfCancelled(); val count = input.read(skip, 0, minOf(remaining, skip.size.toLong()).toInt()); check(count > 0); remaining -= count }
                val bytes = ByteArray(minOf(maxBytes.toLong(), cell.length - offset).toInt()); var size = 0
                while (size < bytes.size) { token.throwIfCancelled(); val count = input.read(bytes, size, bytes.size - size); check(count > 0); size += count }
                var end = size
                if (offset + size < cell.length && end > 0) {
                    var start = end - 1
                    while (start >= 0 && (bytes[start].toInt() and 0xc0) == 0x80) start--
                    if (start >= 0) {
                        val lead = bytes[start].toInt() and 255
                        val length = when { lead < 128 -> 1; (lead and 0xe0) == 0xc0 -> 2; (lead and 0xf0) == 0xe0 -> 3; else -> 4 }
                        if (end - start < length) end = start
                    }
                }
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, end)).toString()
                MemoryImportPreviewPage(text, if (offset + end < cell.length) offset + end else null, cell.length)
            }
            files.verify(); token.throwIfCancelled(); result
        }
    }
    fun apply(prepared: PreparedMemoryImport, promptsConfirmed: Boolean, riskConfirmed: Boolean, token: CancellationToken): MemoryImportReceipt {
        require(prepared.owner === this) { "memory_import_preparation_invalid" }
        val current = operation(prepared.source, token)
        try {
            val counts = phase(current) {
                check(current.prepared === prepared && checkNotNull(current.mapping).version == prepared.mappingVersion) { "memory_import_preparation_invalid" }
                require(!prepared.source.requiresPromptConsent || promptsConfirmed) { "memory_import_prompt_consent_required" }
                require(prepared.riskyFieldCount == 0L || riskConfirmed) { "memory_import_risk_consent_required" }
                current.submitted = true // Even a committed-return failure cannot resubmit this preparation.
                current.prepared = null
                val files = Artifacts(current); files.verify(); token.throwIfCancelled()
                bridge().importMemoryApply(prepared.plan, prepared.offTicket, token)
            }
            val result = cleanup(current)
            token.throwIfCancelled()
            return MemoryImportReceipt("imported", counts, prepared.sha256, prepared.sizeBytes, result.cleanupState)
        } catch (failure: Throwable) { cleanup(current); throw failure }
    }
    fun discard(source: MemoryImportSource, token: CancellationToken): MemoryImportCleanupResult {
        workerRequired(); val current = operation(source, token)
        current.token.cancel(); return cleanup(current)
    }
    private fun copy(input: InputStream, output: OutputStream, token: CancellationToken) {
        val buffer = ByteArray(32768)
        while (true) { token.throwIfCancelled(); val count = input.read(buffer); if (count < 0) return; if (count > 0) output.write(buffer, 0, count) }
    }
    private fun identity(file: File, directory: Boolean): Pair<Long, Long> {
        val stat = Os.lstat(file.path)
        check(if (directory) OsConstants.S_ISDIR(stat.st_mode) else OsConstants.S_ISREG(stat.st_mode)) { "memory_import_staging_invalid" }
        return stat.st_dev to stat.st_ino
    }
    private fun root(current: Active): File {
        val value = checkNotNull(current.root)
        check(identity(value, true) == current.rootIdentity) { "memory_import_staging_changed" }; return value
    }
    private fun <T : Closeable, R> useOwned(value: T, current: Active, block: (T) -> R): R {
        var original: Throwable? = null
        try { return block(value) } catch (failure: Throwable) { original = failure; throw failure }
        finally { try { closeOwned(value, current) } catch (close: Throwable) { if (original != null) original.addSuppressed(close) else throw close } }
    }
    private fun closeOwned(value: Closeable, current: Active) {
        try { value.close() } catch (failure: Throwable) { current.closeUnconfirmed = true; throw failure }
    }
    private inner class Artifacts(private val current: Active): MemoryImportArtifacts {
        private fun valid() { check(active.get() === current && current.worker && !current.cleaning) { "memory_import_artifact_owner_invalid" }; current.token.throwIfCancelled(); root(current) }
        override fun create(name: String): File {
            valid(); require(name in FILE_NAMES) { "memory_import_artifact_name_invalid" }
            val file = File(root(current), name)
            current.files[file]?.let { verifyFile(file, it); return file }
            val fd = Os.open(file.path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
            var stream: FileOutputStream? = null
            try {
                val stat = Os.fstat(fd); check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size == 0L) { "memory_import_staging_invalid" }
                current.files[file] = Seal(stat.st_dev to stat.st_ino, 0L, hex(MessageDigest.getInstance("SHA-256").digest()))
                stream = FileOutputStream(fd); root(current)
            } finally { if (stream != null) closeOwned(stream, current) else try { Os.close(fd) } catch (failure: Throwable) { current.closeUnconfirmed = true; throw failure } }
            verifyFile(file, checkNotNull(current.files[file])); return file
        }
        private fun descriptor(file: File, seal: Seal, flags: Int): FileDescriptor {
            valid(); check(file.parentFile == root(current) && identity(file, false) == seal.identity) { "memory_import_staging_changed" }
            val fd = Os.open(file.path, flags or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
            try {
                val stat = Os.fstat(fd)
                check(OsConstants.S_ISREG(stat.st_mode) && (stat.st_dev to stat.st_ino) == seal.identity && stat.st_size == seal.size) { "memory_import_staging_changed" }
                root(current); return fd
            } catch (failure: Throwable) { try { Os.close(fd) } catch (close: Throwable) { current.closeUnconfirmed = true; failure.addSuppressed(close) }; throw failure }
        }
        private fun openExact(file: File, seal: Seal): FileInputStream {
            val fd = descriptor(file, seal, OsConstants.O_RDONLY)
            return try { FileInputStream(fd) } catch (failure: Throwable) { try { Os.close(fd) } catch (close: Throwable) { current.closeUnconfirmed = true; failure.addSuppressed(close) }; throw failure }
        }
        private fun digest(input: InputStream, digest: MessageDigest, mirror: MessageDigest? = null): Long {
            val buffer = ByteArray(32768); var size = 0L
            while (true) { current.token.throwIfCancelled(); val count = input.read(buffer); if (count < 0) return size; if (count > 0) { digest.update(buffer, 0, count); mirror?.update(buffer, 0, count); size = Math.addExact(size, count.toLong()) } }
        }
        private fun verifyFile(file: File, seal: Seal) {
            val input = openExact(file, seal)
            try {
                val hash = MessageDigest.getInstance("SHA-256"); val size = digest(input, hash)
                check(size == seal.size && hex(hash.digest()) == seal.hash) { "memory_import_staging_changed" }
                val stat = Os.fstat(input.fd)
                check((stat.st_dev to stat.st_ino) == seal.identity && stat.st_size == seal.size && identity(file, false) == seal.identity) { "memory_import_staging_changed" }
            } finally { closeOwned(input, current) }
        }
        override fun open(file: File): InputStream {
            valid(); val seal = current.files[file] ?: error("memory_import_artifact_unknown")
            val input = openExact(file, seal)
            return object: FilterInputStream(input) {
                private var closed = false
                override fun read(): Int { current.token.throwIfCancelled(); return input.read() }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int { current.token.throwIfCancelled(); return input.read(bytes, offset, length) }
                override fun close() { if (!closed) { closed = true; closeOwned(input, current) } }
            }
        }
        override fun append(file: File): OutputStream = output(file, false)
        override fun reset(file: File): OutputStream = output(file, true)
        private fun output(file: File, reset: Boolean): OutputStream {
            valid(); val old = current.files[file] ?: error("memory_import_artifact_unknown")
            verifyFile(file, old)
            val hash = MessageDigest.getInstance("SHA-256"); var size = 0L
            if (!reset) {
                val checkHash = MessageDigest.getInstance("SHA-256")
                useOwned(openExact(file, old), current) { input -> size = digest(input, hash, checkHash) }
                check(size == old.size && hex(checkHash.digest()) == old.hash) { "memory_import_staging_changed" }
            }
            val fd = descriptor(file, old, OsConstants.O_WRONLY or (if (reset) 0 else OsConstants.O_APPEND))
            val output = try { FileOutputStream(fd) } catch (failure: Throwable) { try { Os.close(fd) } catch (close: Throwable) { current.closeUnconfirmed = true; failure.addSuppressed(close) }; throw failure }
            try { if (reset) Os.ftruncate(fd, 0L) } catch (failure: Throwable) { try { closeOwned(output, current) } catch (close: Throwable) { failure.addSuppressed(close) }; throw failure }
            return object: OutputStream() {
                private var closed = false
                override fun write(value: Int) { current.token.throwIfCancelled(); output.write(value); hash.update(value.toByte()); size = Math.addExact(size, 1L) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { current.token.throwIfCancelled(); output.write(bytes, offset, length); hash.update(bytes, offset, length); size = Math.addExact(size, length.toLong()) }
                override fun flush() = output.flush()
                override fun close() {
                    if (closed) return; closed = true
                    closeOwned(output, current)
                    val next = Seal(old.identity, size, hex(hash.digest()))
                    // Compare actual bytes against bytes written, not a new seal adopted from an arbitrary path.
                    verifyFile(file, next); current.files[file] = next
                }
            }
        }
        override fun verify() { valid(); current.files.forEach { (file, seal) -> verifyFile(file, seal) }; root(current) }
    }
    private fun cleanup(current: Active): MemoryImportCleanupResult {
        var interrupted = false
        try {
            synchronized(current.lock) {
                while (current.worker || current.cleaning) try { current.lock.wait() } catch (_: InterruptedException) { interrupted = true }
                if (active.get() !== current) return checkNotNull(current.cleanup)
                if (current.closeUnconfirmed) return MemoryImportCleanupResult("pending", true).also { current.cleanup = it; cleanupStatus.set(it) }
                current.cleaning = true
            }
            var success = true
            try {
                current.prepared = null; current.submitted = true
                current.root?.let { directory ->
                    if (current.rootIdentity == null || runCatching { identity(directory, true) == current.rootIdentity }.getOrDefault(false).not()) success = false
                    else {
                        current.files.forEach { (file, seal) ->
                            val found = try { identity(file, false) } catch (missing: android.system.ErrnoException) { if (missing.errno == OsConstants.ENOENT) null else throw missing }
                            if (found != null) { if (found != seal.identity || !file.delete()) success = false }
                        }
                        if (success && !directory.delete()) success = false
                    }
                }
            } catch (_: Exception) { success = false }
            finally {
                current.unregister?.run()
                synchronized(current.lock) {
                    current.cleanup = MemoryImportCleanupResult(if (success) "cleaned" else "failed", false)
                    cleanupStatus.set(current.cleanup); active.compareAndSet(current, null)
                    current.cleaning = false; current.lock.notifyAll()
                }
            }
            return checkNotNull(current.cleanup)
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
    companion object {
        private val FILE_NAMES = setOf("raw.json", "cells.bin", "catalog.bin", "mapping.bin", "plan.bin", "decisions.bin")
        private val instances = ConcurrentHashMap<String, MemoryImportController>()
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        @JvmStatic fun get(context: Context): MemoryImportController {
            val app = context.applicationContext
            require(!app.isDeviceProtectedStorage) { "memory_import_storage_unsupported" }
            val root = File(requireNotNull(app.applicationInfo.dataDir)).canonicalFile
            require(root.isDirectory) { "memory_import_storage_unavailable" }
            return instances.computeIfAbsent(root.path) { MemoryImportController(app, root) }
        }
    }
}
