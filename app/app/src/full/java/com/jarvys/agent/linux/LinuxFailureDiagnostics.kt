package com.jarvys.agent.linux

import android.system.ErrnoException
import android.system.OsConstants

/** Relative tar-member context retained when an operation on a staging entry fails. */
data class LinuxTarEntryContext(
    val path: String,
    val type: Char,
    val mode: Int,
    val entriesProcessed: Int,
)

class LinuxTarEntryFailure(
    val entry: LinuxTarEntryContext,
    val degradedEntries: Int,
    val hardlinksCopied: Int,
    cause: Throwable,
) : java.io.IOException(cause.message ?: cause.javaClass.simpleName, cause)

/** Produces one copyable, sanitized diagnostic line for persisted state and the chat result. */
object LinuxFailureDiagnostics {
    fun format(
        phase: String,
        failure: Throwable,
        entriesProcessed: Int = 0,
        sanitize: (String) -> String,
    ): String {
        val tarFailure = causeChain(failure).filterIsInstance<LinuxTarEntryFailure>().firstOrNull()
        val original = if (tarFailure != null) causeChain(tarFailure).firstOrNull { it !== tarFailure } ?: tarFailure
            else failure
        val errno = causeChain(failure).filterIsInstance<ErrnoException>().firstOrNull()
        val fields = mutableListOf(
            "phase=$phase",
            "exception=${original.javaClass.name}",
            "message=${original.message ?: original.javaClass.simpleName}",
        )
        if (failure !== original) {
            fields += "wrapped_by=${failure.javaClass.name}"
            if (failure.message != null && failure.message != original.message) fields += "outer_message=${failure.message}"
        }
        errno?.let {
            fields += "errno=${errnoName(it.errno)}"
            fields += "function=${functionName(it)}"
        }
        if (tarFailure != null) {
            fields += "tar_entry=${tarFailure.entry.path}"
            fields += "tar_type=${tarType(tarFailure.entry.type)}(${tarFailure.entry.type})"
            fields += "tar_mode=${tarFailure.entry.mode.toString(8).padStart(4, '0')}"
            fields += "entries_processed=${tarFailure.entry.entriesProcessed}"
        } else {
            fields += "entries_processed=$entriesProcessed"
            fields += "tar_entry=none"
        }
        return sanitize(fields.joinToString("; ").replace(Regex("\\s+"), " ").trim()).take(MAX_DETAIL_CHARS)
    }

    fun probe(probe: LinuxProbeResult, sanitize: (String) -> String): String {
        val fields = mutableListOf(
            "phase=PROBE",
            "exception=${probe.failureClass ?: "${probe.status} (probe result)"}",
            "message=${probe.error.ifBlank { probe.stderr.ifBlank { probe.status.name } }}",
            "status=${probe.status}",
            "exit_code=${probe.exitCode ?: "none"}",
        )
        probe.failureErrno?.let { fields += "errno=${errnoName(it)}" }
        probe.failureFunction?.let { fields += "function=$it" }
        return sanitize(fields.joinToString("; ").replace(Regex("\\s+"), " ").trim()).take(MAX_DETAIL_CHARS)
    }

    private fun causeChain(failure: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = failure
        val seen = hashSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            chain += current
            current = current.cause
        }
        return chain
    }

    private fun errnoName(errno: Int): String = runCatching { OsConstants.errnoName(errno) }
        .getOrNull()?.takeIf(String::isNotBlank) ?: when (errno) {
        OsConstants.EACCES -> "EACCES"
        OsConstants.EPERM -> "EPERM"
        OsConstants.EXDEV -> "EXDEV"
        OsConstants.ENOSPC -> "ENOSPC"
        OsConstants.EIO -> "EIO"
        OsConstants.ENOENT -> "ENOENT"
        else -> "ERRNO_$errno"
    }

    fun functionName(failure: ErrnoException): String {
        val message = failure.message.orEmpty()
        return message.substringBefore(" failed").takeIf { it != message && it.isNotBlank() } ?: "unknown"
    }

    private fun tarType(type: Char): String = when (type) {
        '0', '\u0000', '7' -> "regular"
        '1' -> "hardlink"
        '2' -> "symlink"
        '5' -> "directory"
        else -> "other"
    }

    private const val MAX_DETAIL_CHARS = 2_000
}
