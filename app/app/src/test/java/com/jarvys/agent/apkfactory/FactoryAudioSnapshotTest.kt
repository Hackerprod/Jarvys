package com.jarvys.agent.apkfactory

import android.content.Context
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import com.jarvys.factory.contract.ResourceBudgets
import com.jarvys.factory.runtime.FileShareTransfer
import com.jarvys.factory.runtime.StreamingFileShareTransfer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic disk/Binder loopback only. No real sound, provider, APK, or external recipient. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = android.app.Application::class, shadows = [FactoryFileShareOsShadow::class])
class FactoryAudioSnapshotTest {
    @Test fun largeCanonicalWavUsesPrivateImmutableSnapshotAndWorkerCleanup() = snapshot(false)
    @Test fun rejectedPcmRetainsDiskOwnershipUntilExplicitRecovery() = snapshot(true)
    @Test fun oldAuthenticatedV1EndpointStillReceivesIntoDiskWithoutExtraApproval() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = Executors.newSingleThreadExecutor()
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        val root = File(context.noBackupFilesDir, "factory-audio"); root.deleteRecursively()
        val proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
        val coordinator = FactoryAudioCoordinator(context, FactoryOperationPolicy.inMemory(), { proof })
        val admission = FileShareTransfer.reserve()
        try {
            val transfer = worker.submit(Callable { admission.complete(syntheticWav(), "d".repeat(64)) { true } }).get(5, TimeUnit.SECONDS)
            val request = FactoryAudioCoordinator.Request("d".repeat(64), transfer.size, transfer.sha256, transfer, Binder())
            val session = worker.submit(Callable { coordinator.begin(proof.appId, request) {} }).get(5, TimeUnit.SECONDS)
            assertEquals(1, request.protocolVersion); assertFalse(session.authority.requiresApproval)
            assertEquals(10L, session.pcm!!.durationMs); assertEquals(0, session.pcm!!.bytes.size)
            worker.submit(Callable { coordinator.close(session, false) {} }).get(5, TimeUnit.SECONDS)
            assertTrue(FactoryInteractionAdmission.available())
        } finally {
            admission.close(); worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); root.deleteRecursively()
        }
    }
    private fun snapshot(invalid: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = Executors.newSingleThreadExecutor()
        fun <T> io(block: () -> T) = worker.submit(Callable(block)).get(15, TimeUnit.SECONDS)
        val root = File(context.noBackupFilesDir, "factory-audio")
        FactoryStartupTestIsolation.releaseCompletedSharingStartup(); root.deleteRecursively()
        val budgets = ResourceBudgets.maxima()
        val proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64), budgets)
        val coordinator = FactoryAudioCoordinator(context, FactoryOperationPolicy.inMemory(), { proof })
        val nonce = "d".repeat(64)
        val size = 12 * 1024 * 1024
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put((if (invalid) "NOPE" else "RIFF").toByteArray()); putInt(size + 36); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(48000); putInt(192000); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(size)
        }.array()
        val admission = StreamingFileShareTransfer.reserve(context, size + 44L, 300000)
        try {
            val transfer = io { admission.prepare(StreamingFileShareTransfer.SnapshotWriter { output, active ->
                output.write(header); val zeros = ByteArray(32768)
                repeat(size / zeros.size) { active.run(); output.write(zeros) }
            }, nonce, FileShareTransfer.HostVerifier { true }, Runnable {}, FactoryAudioPcm.MAX_BYTES) }
            assertEquals(2, transfer.protocolVersion)
            val request = FactoryAudioCoordinator.Request(nonce, transfer.size, transfer.sha256, transfer, Binder(), 2)
            if (invalid) {
                val failed = runCatching { io { coordinator.begin(proof.appId, request) {} } }.exceptionOrNull()
                assertNotNull(failed)
                assertNotNull("Expected retained session after invalid PCM: $failed / ${failed?.cause}", coordinator.session())
                assertNotNull(coordinator.session()!!.snapshot); assertNull(coordinator.session()!!.pcm)
                assertFalse(FactoryInteractionAdmission.available())
                io { coordinator.close(coordinator.session(), true) {}; Unit }
            } else {
                val session = io { coordinator.begin(proof.appId, request) {} }
                assertEquals(0, session.pcm!!.bytes.size); assertEquals(size.toLong(), session.pcm!!.pcmBytes)
                assertEquals(65536L, session.pcm!!.durationMs)
                assertEquals(0, File(context.cacheDir, "factory-audio-snapshots").listFiles()!!.size)
                val receipt = io { coordinator.close(session, false) {} }!!
                assertFalse(receipt.getBooleanExtra("audibilityConfirmed", true)); assertFalse(receipt.getBooleanExtra("playbackAttempted", true))
            }
            assertTrue(FactoryInteractionAdmission.available()); assertNull(coordinator.session())
        } finally {
            admission.close()
            val clean = admission.javaClass.getDeclaredMethod("cleanedForTest").apply { isAccessible = true }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (clean.invoke(admission) != true && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(true, clean.invoke(admission))
            worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); root.deleteRecursively()
        }
    }
}
