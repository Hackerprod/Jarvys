package com.jarvys.agent.apkfactory

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.jarvys.factory.contract.ResourceBudgets
import com.jarvys.factory.runtime.FileShareSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Private synthetic disk/Unix metadata model; Android FD grants and external targets need device QA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = android.app.Application::class, shadows = [FactoryFileShareOsShadow::class])
internal class FactoryFileShareStreamingTest : FactoryFileShareTestSupport() {
    private fun <T> io(work: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try { executor.submit(Callable { work() }).get(30, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
    }
    private fun pattern(position: Long) = (position * 31 + 7).toByte()
    private fun hash(size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(32768)
        var offset = 0L
        while (offset < size) {
            val count = minOf(bytes.size.toLong(), size - offset).toInt()
            for (i in 0 until count) bytes[i] = pattern(offset + i)
            digest.update(bytes, 0, count); offset += count
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun producer(size: Long): Binder = object : Binder() {
        var next = 0L
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            require(code == IBinder.FIRST_CALL_TRANSACTION && flags == 0)
            require(String(data.createByteArray()!!, Charsets.US_ASCII) == "com.jarvys.factory.runtime.FileShareTransfer.v2")
            require(String(data.createByteArray()!!, Charsets.US_ASCII) == nonce)
            val offset = data.readLong(); require(offset == next && data.dataAvail() == 0)
            val count = minOf(32768L, size - next).toInt()
            val bytes = ByteArray(count) { pattern(next + it) }
            reply!!.writeInt(count); reply.writeByteArray(bytes); next += count
            return true
        }
    }
    @Test fun receiverStagesNineMiBAsOneReadOnlyInodeUsingBoundedChunks() {
        val size = 9L * 1024 * 1024 + 3
        val budget = ResourceBudgets.parse(mapOf("version" to 1, "fileShare" to mapOf("sourceBytes" to size, "leaseMs" to 900000))).fileShare
        val store = FactoryFileShareStore(context)
        val staged = io { store.stageStreaming(nonce, "large.bin", "application/octet-stream", size, hash(size), producer(size), 2, budget, {}, {}) }
        assertEquals(size, staged.size)
        assertEquals(size, snapshotFile(staged).length())
        provider.openFile(staged.uri, "r").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                val buffer = ByteArray(32768); var position = 0L
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    for (i in 0 until count) assertEquals(pattern(position + i), buffer[i])
                    position += count
                }
                assertEquals(size, position)
            }
        }
        store.cleanup()
    }
    @Test fun v2TransferCannotExceedSignedBudgetOrReuseV1Envelope() {
        val store = FactoryFileShareStore(context)
        val size = 8388609L
        io {
            reject { store.stageStreaming(nonce, "large.bin", "application/octet-stream", size, hash(size), producer(size), 2, ResourceBudgets.legacy().fileShare, {}, {}) }
            val budget = ResourceBudgets.parse(mapOf("version" to 1, "fileShare" to mapOf("sourceBytes" to size))).fileShare
            reject { store.stageStreaming(nonce, "large.bin", "application/octet-stream", size, hash(size), producer(size), 1, budget, {}, {}) }
        }
        assertFalse(root.exists())
    }
    @Test fun sourceSnapshotUnlinksBeforePublicationAndCannotFollowOrReopenSource() = io {
        val snapshot = FileShareSnapshot(context)
        val size = 9L * 1024 * 1024 + 7
        try {
            snapshot.materialize(size, { output, active ->
                val buffer = ByteArray(32768); var offset = 0L
                while (offset < size) {
                    active.run(); val count = minOf(buffer.size.toLong(), size - offset).toInt()
                    for (i in 0 until count) buffer[i] = pattern(offset + i)
                    output.write(buffer, 0, count); offset += count
                }
                buffer.fill(0)
            }, {})
            assertEquals(size, snapshot.size()); assertEquals(hash(size), snapshot.sha256())
            assertTrue(File(context.cacheDir, FileShareSnapshot.FILE_SHARE_NAMESPACE).list()!!.isEmpty())
            val sameRoot = FileShareSnapshot(context)
            try { reject { sameRoot.materialize(1, { output, _ -> output.write(1) }, {}) } } finally { sameRoot.close() }
            val buffer = ByteArray(32768); var offset = 0L
            while (true) {
                val count = snapshot.read(buffer, 0, buffer.size); if (count < 0) break
                for (i in 0 until count) assertEquals(pattern(offset + i), buffer[i])
                offset += count
            }
            assertEquals(size, offset)
        } finally { snapshot.close() }
    }
    @Test fun sourceFailureRetainsNamespaceUntilPrivateCleanupCompletes() = io {
        val first = FileShareSnapshot(context)
        val second = FileShareSnapshot(context)
        try {
            reject { first.materialize(3, { output, _ -> output.write(byteArrayOf(1, 2, 3, 4)) }, {}) }
            reject { second.materialize(1, { output, _ -> output.write(1) }, {}) }
            first.close()
            second.materialize(1, { output, _ -> output.write(1) }, {})
            assertEquals(1L, second.size())
        } finally { first.close(); second.close() }
    }
    @Test fun refusedOrphanCleanupRetainsNamespaceUntilConfirmedRepair() = io {
        val cache = File(context.cacheDir.canonicalFile, FileShareSnapshot.FILE_SHARE_NAMESPACE)
        cache.mkdirs(); android.system.Os.chmod(cache.path, 448)
        val unknown = File(cache, "unexpected.bin").apply { writeText("retain") }
        val first = FileShareSnapshot(context); val second = FileShareSnapshot(context)
        try {
            reject { first.materialize(1, { output, _ -> output.write(1) }, {}) }
            reject { first.close() }
            assertEquals("retain", unknown.readText())
            reject { second.materialize(1, { output, _ -> output.write(1) }, {}) }
            assertTrue(unknown.delete())
            first.close()
            second.materialize(1, { output, _ -> output.write(2) }, {})
            assertEquals(1L, second.size())
        } finally { unknown.delete(); first.close(); second.close() }
    }
    @Test fun audioAndShareNamespacesNeverCleanEachOthersActiveFiles() = io {
        val sharing = FileShareSnapshot(context)
        val audio = FileShareSnapshot(context, FileShareSnapshot.AUDIO_NAMESPACE)
        try {
            sharing.materialize(1, { output, _ -> output.write(1) }, {})
            audio.materialize(1, { output, _ -> output.write(2) }, {})
            val byte = ByteArray(1)
            assertEquals(1, sharing.read(byte, 0, 1)); assertEquals(1, byte[0].toInt())
            assertEquals(1, audio.read(byte, 0, 1)); assertEquals(2, byte[0].toInt())
            reject { FileShareSnapshot(context, "../other") }
        } finally { sharing.close(); audio.close() }
    }
}
