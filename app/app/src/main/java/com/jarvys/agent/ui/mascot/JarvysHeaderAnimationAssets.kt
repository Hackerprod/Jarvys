package com.jarvys.agent.ui.mascot

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.jarvys.agent.JarvysVerifiedAnimation
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** New source reconstruction from APK95 contracts; original RIV bytes are never transformed. */
internal object JarvysHeaderAnimationAssets {
    const val IDLE_ASSET = "jarvys_animations/jarvys_idle.riv"
    const val IDLE_BYTES = 5_091_693
    const val IDLE_SHA256 = "332b63d9b482d8b340c25bf3ab7e3d9f4bc77e7c8e6d5b0eee63ba78d30be1ff"
    private val lock = Any()
    @Volatile private var idle: ByteArray? = null

    suspend fun idle(context: Context): ByteArray = withContext(Dispatchers.IO) {
        ensureActive()
        val bytes = idle ?: synchronized(lock) {
            idle ?: context.applicationContext.assets.open(IDLE_ASSET).use {
                readVerified(it, IDLE_BYTES, IDLE_SHA256)
            }.also { idle = it }
        }
        ensureActive()
        bytes
    }

    suspend fun remote(context: Context, handle: JarvysVerifiedAnimation): ByteArray = withContext(Dispatchers.IO) {
        ensureActive()
        val expected = verifiedRemoteFile(context.applicationContext.cacheDir, handle)
        val bytes = openVerifiedInput(expected, handle.bytes).use { readVerified(it, handle.bytes.toInt(), handle.sha256) }
        ensureActive()
        bytes
    }

    /** Only Context's cache spelling or its canonical spelling is an admissible handle path. */
    internal fun verifiedRemoteFile(cacheDir: File, handle: JarvysVerifiedAnimation): File {
        require(handle.animationId in setOf("sad", "thinking", "working", "waiting", "interrupted", "taunt"))
        require(handle.bytes in 8..Int.MAX_VALUE.toLong())
        require(handle.packageId.isNotBlank())
        require(handle.sha256.matches(Regex("[a-f0-9]{64}")))
        val name = "${handle.animationId}-${handle.sha256}.riv"
        val directory = File(cacheDir.canonicalFile, "jarvys_animations")
        require(directory.canonicalFile == directory.absoluteFile && directory.isDirectory)
        val expected = File(directory, name)
        val contextSpelling = File(File(cacheDir.absoluteFile, "jarvys_animations"), name)
        require(handle.file.absoluteFile == expected.absoluteFile || handle.file.absoluteFile == contextSpelling.absoluteFile)
        require(handle.file.canonicalFile == expected && expected.isFile)
        return expected
    }

    /** API21+ no-follow open: a swapped final symlink or non-regular file cannot be decoded. */
    private fun openVerifiedInput(file: File, expectedBytes: Long): FileInputStream {
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        var ownsDescriptor = true
        try {
            val opened = Os.fstat(fd)
            require(OsConstants.S_ISREG(opened.st_mode) && opened.st_size == expectedBytes)
            // Revalidate the named path after opening; do not accept a changed dedicated directory.
            require(file.canonicalFile == file.absoluteFile)
            val named = Os.lstat(file.path)
            require(OsConstants.S_ISREG(named.st_mode) && named.st_dev == opened.st_dev && named.st_ino == opened.st_ino)
            val input = FileInputStream(fd)
            ownsDescriptor = false // Ownership transfers to the stream before any read can fail.
            return input
        } finally {
            if (ownsDescriptor) Os.close(fd)
        }
    }

    internal fun readVerified(input: InputStream, expectedBytes: Int, expectedSha256: String): ByteArray {
        require(expectedBytes >= 8 && expectedSha256.matches(Regex("[a-f0-9]{64}")))
        val bytes = ByteArray(expectedBytes)
        var offset = 0
        while (offset < expectedBytes) {
            val read = input.read(bytes, offset, expectedBytes - offset)
            require(read > 0) { "Truncated animation" }
            offset += read
        }
        require(input.read() == -1) { "Animation exceeds declared size" }
        require(bytes[0] == 82.toByte() && bytes[1] == 73.toByte() && bytes[2] == 86.toByte() && bytes[3] == 69.toByte())
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(actual == expectedSha256) { "Animation integrity failed" }
        return bytes
    }
}
