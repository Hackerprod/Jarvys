package com.jarvys.agent.chatdb

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.MemoryDoctorPaths
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/** Hash/verification in inventory is historical, never a claim of a freshly verified restore. */
data class MemoryRepairBackupInfo(
    val operationId: String, val createdAt: Long?, val status: String, val sizeBytes: Long?,
    val sha256: String?, val availability: String, val repairOutcome: String, val kind: String = "unspecified",
)

/**
 * One fixed private root. VACUUM opens a pathname: Android UID isolation and trusted app code are
 * the boundary, not resistance to malicious same-UID races. No caller or sidecar controls a path.
 * Every created copy is retained, including failed and cancelled operations. This class never deletes.
 */
class MemoryRepairBackupStore(context: Context) {
    private val data: File = MemoryDoctorPaths.root(context.applicationContext)
    private val noBackup: File = File(data, "no_backup")
    private val root: File = File(noBackup, "memory-repair")
    internal data class Identity(val device: Long, val inode: Long)
    internal class Attempt internal constructor(
        val id: String, val createdAt: Long, val directory: File, val file: File,
        val directories: List<Pair<File, Identity>>, val fileIdentity: Identity, val kind: String,
    ) {
        var size: Long? = null
        var hash: String? = null
        var verified = false
    }
    private fun error(code: String): Nothing = throw MemorySourceTitleRepairException(code)
    private fun identity(file: File, directory: Boolean): Identity {
        val stat = Os.lstat(file.absolutePath)
        if (!(if (directory) OsConstants.S_ISDIR(stat.st_mode) else OsConstants.S_ISREG(stat.st_mode))) error("memory_repair_backup_path_invalid")
        return Identity(stat.st_dev, stat.st_ino)
    }
    private fun exists(file: File): Boolean = try { Os.lstat(file.absolutePath); true }
        catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) false else throw failure }
    private fun privateDirectory(file: File): Identity {
        if (!exists(file)) {
            if (!file.mkdir()) error("memory_repair_backup_directory_failed")
            Os.chmod(file.absolutePath, 448)
        }
        return identity(file, true)
    }
    private fun requireDirectories(attempt: Attempt) {
        if (attempt.directory.parentFile != root || attempt.file.parentFile != attempt.directory || attempt.file.name != COPY) error("memory_repair_backup_path_invalid")
        for ((file, seal) in attempt.directories) if (identity(file, true) != seal) error("memory_repair_backup_changed")
    }
    private fun requireFile(attempt: Attempt) {
        requireDirectories(attempt)
        if (identity(attempt.file, false) != attempt.fileIdentity) error("memory_repair_backup_changed")
    }
    private fun close(fd: FileDescriptor, original: Throwable? = null) {
        try { Os.close(fd) } catch (failure: Throwable) { if (original != null) original.addSuppressed(failure) else throw failure }
    }
    private fun syncDirectory(directory: File, expected: Identity) {
        if (identity(directory, true) != expected) error("memory_repair_backup_changed")
        // Public Android SDK exposes no O_DIRECTORY. Validate the opened descriptor type and sealed inode before fsync.
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        var failure: Throwable? = null
        try {
            val stat = Os.fstat(fd)
            if (!OsConstants.S_ISDIR(stat.st_mode) || Identity(stat.st_dev, stat.st_ino) != expected) error("memory_repair_backup_changed")
            Os.fsync(fd)
            if (identity(directory, true) != expected) error("memory_repair_backup_changed")
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { close(fd, failure) }
    }
    internal fun create(token: CancellationToken, kind: String = "source_title"): Attempt {
        require(kind == "source_title" || kind == "orphan_pending_relations") { "memory_repair_kind_invalid" }
        token.throwIfCancelled()
        val directories = ArrayList<Pair<File, Identity>>()
        directories.add(data to identity(data, true))
        directories.add(noBackup to privateDirectory(noBackup))
        directories.add(root to privateDirectory(root))
        token.throwIfCancelled()
        val id = UUID.randomUUID().toString(); val directory = File(root, id)
        if (exists(directory) || !directory.mkdir()) error("memory_repair_backup_directory_failed")
        Os.chmod(directory.absolutePath, 448)
        directories.add(directory to identity(directory, true))
        val file = File(directory, COPY)
        val fd = Os.open(file.absolutePath, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
        var failure: Throwable? = null
        val seal: Identity
        try {
            val stat = Os.fstat(fd)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != 0L) error("memory_repair_backup_path_invalid")
            seal = Identity(stat.st_dev, stat.st_ino)
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { close(fd, failure) }
        val attempt = Attempt(id, System.currentTimeMillis(), directory, file, Collections.unmodifiableList(directories), seal, kind)
        requireFile(attempt)
        metadata(attempt, MANIFEST, JSONObject().put("version", 1).put("operation_id", id).put("created_at", attempt.createdAt).put("status", "reserved").put("kind", kind))
        token.throwIfCancelled()
        return attempt
    }
    private fun metadata(attempt: Attempt, name: String, body: JSONObject) {
        require(name in setOf(MANIFEST, VERIFIED, OUTCOME))
        requireFile(attempt)
        val file = File(attempt.directory, name)
        val fd = Os.open(file.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
        var output: FileOutputStream? = null
        var failure: Throwable? = null
        try {
            val stat = Os.fstat(fd)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != 0L) error("memory_repair_backup_metadata_failed")
            output = FileOutputStream(fd)
            output.write(body.toString().toByteArray(Charsets.UTF_8)); output.flush(); Os.fsync(fd)
            if (identity(file, false) != Identity(stat.st_dev, stat.st_ino)) error("memory_repair_backup_changed")
            requireFile(attempt)
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            try { if (output == null) Os.close(fd) else output.close() }
            catch (caught: Throwable) { if (failure != null) failure.addSuppressed(caught) else throw caught }
        }
        syncDirectory(attempt.directory, attempt.directories.last().second)
    }
    /** Opens a fresh protected descriptor after SQLite has finished writing, never a stale reservation fd. */
    private fun digest(attempt: Attempt, token: CancellationToken, flush: Boolean): Pair<Long, String> {
        requireFile(attempt)
        val fd = Os.open(attempt.file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        var input: FileInputStream? = null
        var failure: Throwable? = null
        try {
            val before = Os.fstat(fd)
            if (!OsConstants.S_ISREG(before.st_mode) || Identity(before.st_dev, before.st_ino) != attempt.fileIdentity) error("memory_repair_backup_changed")
            input = FileInputStream(fd)
            val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(32768); var size = 0L
            while (true) {
                token.throwIfCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) { hash.update(buffer, 0, count); size = Math.addExact(size, count.toLong()) }
            }
            if (size == 0L || size != before.st_size) error("memory_repair_backup_changed")
            if (flush) Os.fsync(fd)
            val after = Os.fstat(fd)
            if (Identity(after.st_dev, after.st_ino) != attempt.fileIdentity || after.st_size != size) error("memory_repair_backup_changed")
            requireFile(attempt); token.throwIfCancelled()
            return size to hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            try { if (input == null) Os.close(fd) else input.close() }
            catch (caught: Throwable) { if (failure != null) failure.addSuppressed(caught) else throw caught }
        }
    }
    internal fun copyAndVerify(attempt: Attempt, source: File, token: CancellationToken, requireSource: () -> Unit,
        verifyContents: (ChatSqlDatabase) -> Unit): MemoryRepairBackupInfo {
        requireFile(attempt); requireSource(); token.throwIfCancelled()
        // The caller holds Room IMMEDIATE and its lifecycle fence. No source mutation has occurred.
        BundledSQLiteDriver().open(source.absolutePath, 1).use { reader ->
            requireSource(); requireFile(attempt); token.throwIfCancelled()
            reader.prepare("VACUUM INTO ?").use { statement ->
                statement.bindText(1, attempt.file.absolutePath)
                while (statement.step()) { token.throwIfCancelled() }
            }
        }
        requireSource(); requireFile(attempt); token.throwIfCancelled()
        BundledSQLiteDriver().open(attempt.file.absolutePath, 1).use { copy ->
            val sql = DriverChatSqlDatabase(copy)
            sql.execSQL("PRAGMA query_only=ON")
            val valid = sql.query("PRAGMA integrity_check").use { c -> c.moveToFirst() && c.getString(0) == "ok" && !c.moveToNext() }
            if (!valid) error("memory_repair_backup_verification_failed")
            verifyContents(sql)
            requireSource(); requireFile(attempt); token.throwIfCancelled()
        }
        // Both SQLite connections have physically closed before hashing/receipt publication.
        val seal = digest(attempt, token, true)
        for ((directory, id) in attempt.directories.asReversed()) { token.throwIfCancelled(); syncDirectory(directory, id) }
        requireSource(); requireFile(attempt); token.throwIfCancelled()
        attempt.size = seal.first; attempt.hash = seal.second
        metadata(attempt, VERIFIED, JSONObject().put("version", 1).put("operation_id", attempt.id)
            .put("size", seal.first).put("sha256", seal.second).put("device", attempt.fileIdentity.device).put("inode", attempt.fileIdentity.inode)
            .put("verification", "sqlite_integrity_schema_generation_plan"))
        token.throwIfCancelled(); requireSource(); requireFile(attempt)
        attempt.verified = true
        return info(attempt, "unknown")
    }
    internal fun requireVerified(attempt: Attempt, token: CancellationToken) {
        if (!attempt.verified) error("memory_repair_backup_verification_failed")
        val seal = digest(attempt, token, false)
        if (seal.first != attempt.size || seal.second != attempt.hash) error("memory_repair_backup_changed")
    }
    internal fun outcome(attempt: Attempt, value: String, failure: String?) {
        require(value in setOf("applied", "unknown"))
        val target = File(attempt.directory, OUTCOME)
        // A prior complete outcome is historical evidence, never overwritten after response failure.
        if (exists(target)) {
            if (value == "applied") error("memory_repair_backup_metadata_failed")
            return
        }
        metadata(attempt, OUTCOME, JSONObject().put("version", 1).put("operation_id", attempt.id).put("repair_outcome", value)
            .put("operation_status", failure ?: "completed"))
    }
    internal fun info(attempt: Attempt, outcome: String): MemoryRepairBackupInfo = MemoryRepairBackupInfo(
        attempt.id, attempt.createdAt, if (attempt.verified) "verified" else "incomplete", attempt.size, attempt.hash,
        "verified_at_operation", outcome, attempt.kind,
    )
    /** Metadata-only inventory. No database opener, mkdir, JSON import, restore or automatic cleanup. */
    fun inventory(token: CancellationToken): List<MemoryRepairBackupInfo> {
        token.throwIfCancelled()
        val ancestors = ArrayList<Pair<File, Identity>>()
        ancestors.add(data to identity(data, true))
        if (!exists(noBackup)) return emptyList()
        ancestors.add(noBackup to identity(noBackup, true))
        if (!exists(root)) return emptyList()
        ancestors.add(root to identity(root, true))
        fun current() { token.throwIfCancelled(); ancestors.forEach { (file, id) -> if (identity(file, true) != id) error("memory_repair_backup_changed") } }
        current()
        val entries = root.listFiles() ?: error("memory_repair_backup_inventory_failed")
        val result = entries.sortedBy { it.name }.map { directory ->
            current()
            val operationId = directory.name
            val directoryId = try { identity(directory, true) } catch (_: Exception) { null }
            if (directoryId == null || runCatching { UUID.fromString(operationId).toString() == operationId }.getOrDefault(false).not()) {
                MemoryRepairBackupInfo(operationId, null, "unknown", null, null, "unsafe_entry", "unknown")
            } else {
                fun read(name: String): JSONObject? {
                    val file = File(directory, name)
                    if (!exists(file)) return null
                    val id = identity(file, false)
                    val fd = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
                    var stream: FileInputStream? = null; var failure: Throwable? = null
                    try {
                        val stat = Os.fstat(fd)
                        if (!OsConstants.S_ISREG(stat.st_mode) || Identity(stat.st_dev, stat.st_ino) != id || stat.st_size !in 1L..16384L) return null
                        stream = FileInputStream(fd)
                        val bytes = ByteArray(stat.st_size.toInt()); var count = 0
                        while (count < bytes.size) { token.throwIfCancelled(); val got = stream.read(bytes, count, bytes.size - count); if (got < 0) return null; count += got }
                        if (stream.read() != -1 || identity(file, false) != id || identity(directory, true) != directoryId) return null
                        val body = JSONObject(String(bytes, Charsets.UTF_8))
                        return body.takeIf { it.optInt("version", -1) == 1 && it.optString("operation_id") == operationId }
                    } catch (caught: Throwable) { failure = caught; throw caught }
                    finally {
                        try { if (stream == null) Os.close(fd) else stream.close() }
                        catch (caught: Throwable) { if (failure != null) failure.addSuppressed(caught) else throw caught }
                    }
                }
                try {
                    val manifest = read(MANIFEST); val verified = read(VERIFIED); val outcome = read(OUTCOME)
                    val file = File(directory, COPY)
                    val copyId = if (exists(file)) identity(file, false) else null
                    val hash = verified?.optString("sha256")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                    val size = verified?.optLong("size", -1L)?.takeIf { it > 0L }
                    val historical = verified != null && hash != null && size != null && verified.optString("verification") == "sqlite_integrity_schema_generation_plan"
                    val matches = historical && copyId != null && copyId.device == verified!!.optLong("device", Long.MIN_VALUE) && copyId.inode == verified.optLong("inode", Long.MIN_VALUE) && Os.lstat(file.absolutePath).st_size == size
                    val availability = when { copyId == null -> "missing"; !historical -> "present_not_reverified"; !matches -> "identity_changed"; else -> "present_not_reverified" }
                    val state = outcome?.optString("repair_outcome")?.takeIf { it in setOf("applied", "unknown") } ?: "unknown"
                    val status = when { historical -> "historically_verified"; manifest != null -> outcome?.optString("operation_status")?.takeIf { it in setOf("failed", "cancelled") } ?: "incomplete"; else -> "unknown" }
                    current()
                    if (identity(directory, true) != directoryId) error("memory_repair_backup_changed")
                    MemoryRepairBackupInfo(operationId, manifest?.optLong("created_at", -1L)?.takeIf { it >= 0L }, status, size, hash, availability, state,
                        manifest?.optString("kind")?.takeIf { it == "source_title" || it == "orphan_pending_relations" } ?: "unspecified")
                } catch (failure: java.util.concurrent.CancellationException) { throw failure }
                catch (_: Exception) {
                    current()
                    MemoryRepairBackupInfo(operationId, null, "unknown", null, null, "unsafe_entry", "unknown")
                }
            }
        }
        current()
        return Collections.unmodifiableList(result)
    }
    private companion object {
        const val COPY = "snapshot.sqlite"
        const val MANIFEST = "manifest.json"
        const val VERIFIED = "verified.json"
        const val OUTCOME = "outcome.json"
    }
}
