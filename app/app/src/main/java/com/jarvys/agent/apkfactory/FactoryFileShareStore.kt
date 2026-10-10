package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.core.content.FileProvider
import com.jarvys.factory.runtime.FileShareTransfer
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Private, one-operation cache. The coordinator must call all I/O on its bounded worker. */
internal class FactoryFileShareStore(
    context: Context,
    private val copy: (IBinder, String, Int, String, OutputStream, Runnable) -> Unit =
        { binder, nonce, size, sha256, output, active -> FileShareTransfer.copy(binder, nonce, size, sha256, output, active) }
) {
    private val context = context.applicationContext

    data class Snapshot(val uri: Uri, val filename: String, val mimeType: String, val size: Int, val expiresAt: Long)

    fun stage(nonce: String, filename: String, mimeType: String, size: Int, sha256: String,
              transfer: IBinder, checkActive: () -> Unit): Snapshot {
        require(nonce.matches(Regex("[a-f0-9]{64}")))
        require(filename.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9 _.-]{0,119}")) && !filename.endsWith(".") && !filename.contains(".."))
        require(mimeType.length <= 127 && mimeType.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]*")))
        require(size in 1..MAX_BYTES && sha256.matches(Regex("[a-f0-9]{64}")))
        val operation = FactoryFileShareRegistry.begin()
        var staging: File? = null
        var completed: File? = null
        var published = false
        val active = Runnable { checkActive(); FactoryFileShareRegistry.requireActive(operation) }
        try {
            active.run()
            val root = privateRoot(context, create = true)!!
            // Never reconstruct registration from disk, including after process death.
            check(root.list()?.isEmpty() == true) { "Stale file-share cache requires cleanup" }
            val rootIdentity = Identity.directory(Os.lstat(root.path))
            staging = File(root, "staging-${UUID.randomUUID()}.tmp")
            completed = File(root, "snapshot-${UUID.randomUUID()}.bin")
            val descriptor = Os.open(staging.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or
                OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, OWNER_RW)
            FileOutputStream(descriptor).use { stream ->
                val checked = CheckedOutput(stream, size, sha256, active)
                copy(transfer, nonce, size, sha256, checked, active)
                checked.verify()
                active.run()
                stream.flush()
                Os.fsync(descriptor)
                Os.fchmod(descriptor, OWNER_READ)
                check(Os.fstat(descriptor).st_size == size.toLong())
            }
            active.run()
            requireRootIdentity(root, rootIdentity)
            // Link fails rather than overwriting an existing destination; unlink leaves one immutable inode.
            Os.link(staging.path, completed.path)
            Os.remove(staging.path)
            staging = null
            val identity = Identity.file(Os.lstat(completed.path), size)
            check(completed.canonicalFile == completed)
            val uri = FileProvider.getUriForFile(context, authority(context), completed)
            val now = SystemClock.elapsedRealtime()
            check(now >= 0 && now <= Long.MAX_VALUE - LIFETIME_MILLIS)
            val result = Snapshot(uri, filename, mimeType, size, now + LIFETIME_MILLIS)
            active.run()
            FactoryFileShareRegistry.register(operation, result, completed, rootIdentity, identity)
            published = true
            return result
        } finally {
            // No registry lock is held while a remote Binder may be stalled.
            try {
                if (!published) {
                    staging?.let(::deleteCacheEntry)
                    completed?.let(::deleteCacheEntry)
                }
            } finally { FactoryFileShareRegistry.finish(operation) }
        }
    }

    /** Invalidate first, revoke globally (including forwarded grants), then remove only our flat cache. */
    fun cleanup() {
        val uris = FactoryFileShareRegistry.invalidate()
        var failure: Exception? = null
        for (uri in uris) {
            try { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (e: Exception) { if (failure == null) failure = e else failure.addSuppressed(e) }
        }
        try {
            FactoryFileShareRegistry.closeInvalidated()
            val root = privateRoot(context, create = false)
            if (root != null) {
                val entries = root.listFiles() ?: error("Cannot inspect file-share cache")
                check(entries.size <= MAX_CACHE_ENTRIES) { "Unexpected file-share cache entries" }
                for (entry in entries) {
                    check(CACHE_NAME.matches(entry.name)) { "Unexpected file-share cache entry" }
                    deleteCacheEntry(entry)
                }
                check(root.list()?.isEmpty() == true) { "File-share cleanup incomplete" }
            }
        } catch (e: Exception) { if (failure == null) failure = e else failure.addSuppressed(e) }
        // Preserve failed revocations for another cleanup attempt. They remain unreadable in the registry.
        if (failure != null) {
            FactoryFileShareRegistry.retainRevocations(uris)
            throw failure
        }
        FactoryFileShareRegistry.cleaned(uris)
    }

    private class CheckedOutput(private val target: OutputStream, private val size: Int,
                                private val sha256: String, private val active: Runnable) : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var count = 0
        override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            active.run()
            require(length <= size - count) { "File share exceeds declared size" }
            target.write(bytes, offset, length)
            digest.update(bytes, offset, length)
            count += length
        }
        fun verify() {
            check(count == size) { "Incomplete file-share transfer" }
            check(digest.digest().joinToString("") { "%02x".format(it) } == sha256) { "File-share digest mismatch" }
        }
    }

    companion object {
        const val MAX_BYTES = 8_388_608
        const val CHUNK_BYTES = 32_768
        const val LIFETIME_MILLIS = 300_000L
        internal const val OWNER_READ = 256 // 0400
        private const val OWNER_RW = 384 // 0600
        private const val OWNER_DIR = 448 // 0700
        private const val MAX_CACHE_ENTRIES = 8
        private val CACHE_NAME = Regex("(?:staging-[0-9a-f-]{36}\\.tmp|snapshot-[0-9a-f-]{36}\\.bin)")
        internal fun authority(context: Context) = context.packageName + ".factory.files"

        internal fun privateRoot(context: Context, create: Boolean): File? {
            val root = File(context.cacheDir.canonicalFile, "factory-file-shares")
            if (create) {
                try { Os.mkdir(root.path, OWNER_DIR) }
                catch (e: ErrnoException) { if (e.errno != OsConstants.EEXIST) throw e }
            }
            val stat = try { Os.lstat(root.path) }
            catch (e: ErrnoException) { if (!create && e.errno == OsConstants.ENOENT) return null else throw e }
            Identity.directory(stat)
            check(root.canonicalFile == root) { "Symlinked file-share directory" }
            return root
        }

        internal fun requireRootIdentity(root: File, identity: Identity) {
            check(root.canonicalFile == root)
            check(Identity.directory(Os.lstat(root.path)) == identity) { "File-share directory replaced" }
        }

        private fun deleteCacheEntry(file: File) {
            val parent = file.parentFile ?: error("Missing file-share directory")
            check(parent.canonicalFile == parent) { "Symlinked file-share directory" }
            val parentStat = try { Os.lstat(parent.path) }
            catch (e: ErrnoException) { if (e.errno == OsConstants.ENOENT) return else throw e }
            Identity.directory(parentStat)
            val stat = try { Os.lstat(file.path) }
            catch (e: ErrnoException) { if (e.errno == OsConstants.ENOENT) return else throw e }
            // Unlink a link itself, never its target. Refuse directories and every special-file kind.
            check(OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode)) { "Unexpected file-share cache type" }
            Os.remove(file.path)
            try { Os.lstat(file.path); error("File-share cache entry still exists") }
            catch (e: ErrnoException) { if (e.errno != OsConstants.ENOENT) throw e }
        }
    }

    internal data class Identity(val device: Long, val inode: Long, val size: Long, val mode: Int,
                                 val uid: Int, val modified: Long, val changed: Long) {
        companion object {
            fun directory(stat: StructStat): Identity {
                check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Os.getuid() && stat.st_mode and 511 == OWNER_DIR)
                // Directory time/size changes as entries are created; identity is the directory inode.
                return Identity(stat.st_dev, stat.st_ino, 0, stat.st_mode, stat.st_uid, 0, 0)
            }
            fun file(stat: StructStat, size: Int): Identity {
                check(OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink == 1L && stat.st_uid == Os.getuid())
                check(stat.st_mode and 511 == OWNER_READ && stat.st_size == size.toLong())
                return Identity(stat.st_dev, stat.st_ino, stat.st_size, stat.st_mode, stat.st_uid, stat.st_mtime, stat.st_ctime)
            }
        }
    }
}

/** Process-local authority only. No persisted file, URI, or journal can repopulate this registry. */
internal object FactoryFileShareRegistry {
    private data class Entry(val snapshot: FactoryFileShareStore.Snapshot, val file: File,
                             val root: FactoryFileShareStore.Identity, val identity: FactoryFileShareStore.Identity,
                             val pinned: FileDescriptor)
    private var operation: String? = null
    private var cancelled = false
    private var entry: Entry? = null
    private val pendingRevocations = linkedSetOf<Uri>()
    private val pendingClose = mutableListOf<FileDescriptor>()

    @Synchronized fun begin(): String {
        check(operation == null && entry == null && pendingRevocations.isEmpty() && pendingClose.isEmpty()) { "A file-share operation is already pending" }
        return UUID.randomUUID().toString().also { operation = it; cancelled = false }
    }
    @Synchronized fun requireActive(token: String) { check(token == operation && !cancelled) { "File-share operation revoked" } }
    @Synchronized fun finish(token: String) { if (operation == token) operation = null }
    @Synchronized fun register(token: String, snapshot: FactoryFileShareStore.Snapshot, file: File,
                               root: FactoryFileShareStore.Identity, identity: FactoryFileShareStore.Identity) {
        requireActive(token)
        check(entry == null && pendingRevocations.isEmpty())
        check(snapshot.uri.scheme == "content" && snapshot.uri.query == null && snapshot.uri.fragment == null)
        check(snapshot.expiresAt > SystemClock.elapsedRealtime())
        // Keep this inode alive until invalidation: an unlinked/replaced path cannot reuse its inode.
        val pinned = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try {
            check(FactoryFileShareStore.Identity.file(Os.fstat(pinned), snapshot.size) == identity)
            entry = Entry(snapshot, file, root, identity, pinned)
        } catch (e: Throwable) { Os.close(pinned); throw e }
    }
    @Synchronized fun invalidate(): List<Uri> {
        cancelled = true
        entry?.let { pendingRevocations.add(it.snapshot.uri); pendingClose.add(it.pinned) }
        entry = null
        return pendingRevocations.toList()
    }
    @Synchronized fun closeInvalidated() {
        val iterator = pendingClose.iterator()
        while (iterator.hasNext()) {
            val descriptor = iterator.next()
            // Android invalidates the FileDescriptor even when close reports an OS error.
            if (descriptor.valid()) Os.close(descriptor)
            iterator.remove()
        }
    }
    @Synchronized fun retainRevocations(uris: List<Uri>) { pendingRevocations.addAll(uris) }
    @Synchronized fun cleaned(uris: List<Uri>) { pendingRevocations.removeAll(uris.toSet()) }

    /** The lock only covers bounded local metadata/open work, never transfer or caller code. */
    @Synchronized fun <T> read(context: Context, uri: Uri,
                              block: (FactoryFileShareStore.Snapshot, File, FactoryFileShareStore.Identity) -> T): T {
        val current = entry ?: throw java.io.FileNotFoundException("Unknown file share")
        if (uri.toString() != current.snapshot.uri.toString() || uri.scheme != "content" ||
            uri.authority != FactoryFileShareStore.authority(context) || uri.query != null || uri.fragment != null ||
            SystemClock.elapsedRealtime() !in 0 until current.snapshot.expiresAt) {
            throw java.io.FileNotFoundException("Unknown or expired file share")
        }
        FactoryFileShareStore.requireRootIdentity(current.file.parentFile!!, current.root)
        check(current.file.canonicalFile == current.file)
        check(FactoryFileShareStore.Identity.file(Os.lstat(current.file.path), current.snapshot.size) == current.identity)
        return block(current.snapshot, current.file, current.identity)
    }
}
