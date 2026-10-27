package com.jarvys.agent.linux

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Exact legacy journal-byte persistence only, not an execution/source receipt. No decode/retry API.
 * The shared root lock serializes IO (normally redundant with the conversation lock); it is NOT
 * cross-process liveness, a revision CAS, or protection against a later stale legacy snapshot.
 * Lock order: job -> conversation -> writer. No callbacks into those owners or Room from here.
 */
internal class CodingJobJournalWriter(root: File, private val backend: Backend = RealBackend) {
    enum class Stage { OPEN, WRITE, SYNC, CLOSE, RENAME, DIRECTORY_SYNC }
    sealed interface Candidate {
        val target: File
        val jobId: String
        val schema: Int
        val attemptId: String
        fun copyBytes(): ByteArray
    }
    /** Internal producer token, not a security boundary or a public execution receipt. */
    sealed interface Commit { val candidate: Candidate }
    private class ExactCandidate(override val target: File, override val jobId: String,
        override val schema: Int, bytes: ByteArray) : Candidate {
        override val attemptId = UUID.randomUUID().toString()
        private val payload = bytes.copyOf()
        override fun copyBytes() = payload.copyOf()
    }
    private class WrittenCommit(override val candidate: Candidate) : Commit
    class Failure internal constructor(val candidate: Candidate, val attempted: Stage,
        val confirmed: Stage?, cause: Exception) : IOException("Coding journal persistence is unconfirmed at $attempted", cause)

    interface Sink : AutoCloseable {
        fun write(bytes: ByteArray)
        fun sync()
        override fun close()
    }
    interface Backend {
        fun open(staged: File): Sink
        fun rename(staged: File, target: File)
        fun syncDirectory(directory: File)
        fun deleteStaged(staged: File)
    }
    internal object RealBackend : Backend {
        override fun open(staged: File): Sink {
            val stream = FileOutputStream(staged)
            return object : Sink {
                override fun write(bytes: ByteArray) { stream.write(bytes) }
                override fun sync() { stream.fd.sync() }
                override fun close() { stream.close() }
            }
        }
        override fun rename(staged: File, target: File) {
            if (!staged.renameTo(target)) throw IOException("Could not commit coding job journal")
        }
        override fun syncDirectory(directory: File) {
            verifyPath(directory)
            if (Build.VERSION.SDK_INT >= 26 || System.getProperty("java.vm.name")?.contains("Dalvik") != true) {
                DirectorySync.sync(directory)
            } else {
                val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
                try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
            }
        }
        override fun deleteStaged(staged: File) {
            if (staged.exists() && !staged.delete()) throw IOException("Could not remove staged coding journal")
        }
    }
    // Preserve the legacy lazy API-26 linkage boundary for java.nio.file.
    private object DirectorySync {
        fun sync(directory: File) = FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
    private val root = root.absoluteFile.also(::verifyPath)
    private val lock = locks.computeIfAbsent(this.root.path) { Any() }

    /** A fresh candidate is captured once; callers cannot resubmit a previous Candidate. */
    fun write(target: File, jobId: String, schema: Int, bytes: ByteArray): Commit {
        require(schema == 1 || schema == 2) { "Only legacy journal formats are supported" }
        return writeBytes(target, jobId, schema, bytes)
    }
    /** Raw IO only. The Main schema-3 producer validates its envelope and owns its separate receipt. */
    fun writeEnvelope(target: File, jobId: String, bytes: ByteArray): Commit = writeBytes(target, jobId, 3, bytes)

    private fun writeBytes(target: File, jobId: String, schema: Int, bytes: ByteArray): Commit {
        require(UUID.fromString(jobId).toString() == jobId) { "Noncanonical job identity" }
        val exactTarget = target.absoluteFile
        verifyPath(exactTarget)
        require(exactTarget.path.startsWith(root.path + File.separator) && exactTarget.name == "$jobId.json") {
            "Journal path does not match its owner root and job"
        }
        val directory = requireNotNull(exactTarget.parentFile) { "Journal target requires a parent directory" }
        val candidate = ExactCandidate(exactTarget, jobId, schema, bytes)
        return synchronized(lock) {
            var attempted = Stage.OPEN
            var confirmed: Stage? = null
            var sink: Sink? = null
            var failure: Failure? = null
            val staged = File(directory, "$jobId.${candidate.attemptId}.tmp")
            try {
                verifyPath(exactTarget); verifyPath(staged)
                sink = backend.open(staged); confirmed = Stage.OPEN
                attempted = Stage.WRITE; sink.write(candidate.copyBytes()); confirmed = Stage.WRITE
                attempted = Stage.SYNC; sink.sync(); confirmed = Stage.SYNC
                attempted = Stage.CLOSE; sink.close(); sink = null; confirmed = Stage.CLOSE
                attempted = Stage.RENAME; verifyPath(exactTarget); verifyPath(staged)
                backend.rename(staged, exactTarget); confirmed = Stage.RENAME
                attempted = Stage.DIRECTORY_SYNC; backend.syncDirectory(directory); confirmed = Stage.DIRECTORY_SYNC
                WrittenCommit(candidate)
            } catch (cause: Exception) {
                Failure(candidate, attempted, confirmed, cause).also { failure = it }.let { throw it }
            } finally {
                // Preserve the primary failure. Once rename succeeded there is normally no staging file.
                try { sink?.close() } catch (close: Exception) { failure?.addSuppressed(close) }
                try { backend.deleteStaged(staged) } catch (cleanup: Exception) { failure?.addSuppressed(cleanup) }
            }
        }
    }
    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private fun verifyPath(file: File) {
            if (file.absoluteFile != file.canonicalFile) throw IOException("Coding job storage cannot use symlinks")
        }
    }
}
