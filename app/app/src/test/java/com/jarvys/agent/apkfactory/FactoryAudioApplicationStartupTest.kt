package com.jarvys.agent.apkfactory

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Actual Application entrypoint with synthetic journals; native sound is never constructed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [FactoryFileShareOsShadow::class])
class FactoryAudioApplicationStartupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-audio")
    private fun audioBarrier() {
        val worker = FactoryAudioCoordinator.WORKER; worker.prestartCoreThread()
        val barrier = FutureTask(Callable { Unit })
        check(worker.queue.offer(barrier, 5, TimeUnit.SECONDS)); barrier.get(5, TimeUnit.SECONDS)
    }
    private fun resetClosedAudio() {
        audioBarrier()
        val field = FactoryAudioCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = field.get(null) as? FactoryAudioCoordinator
        if (previous != null) {
            check(!previous.isBusy() && !previous.needsRecovery() && previous.session() == null)
            val lease = FactoryAudioCoordinator::class.java.getDeclaredField("lease").apply { isAccessible = true }
            check(lease.get(previous) == null) { "Never erase a live protection lease" }
            FactoryInteractionAdmission.release(previous)
        }
        field.set(null, null)
    }
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup(); resetClosedAudio()
        for (name in listOf("factory-audio", "factory-photos", "factory-file-sharing", "factory-documents", "factory-install")) File(context.noBackupFilesDir, name).deleteRecursively()
        File(context.cacheDir, "factory-file-shares").deleteRecursively()
    }
    @After fun cleanup() {
        audioBarrier()
        val singleton = FactoryAudioCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val audio = singleton.get(null) as? FactoryAudioCoordinator
        if (audio != null && audio.needsRecovery()) {
            val close = FutureTask(Callable { audio.close(null, true) {} })
            check(FactoryAudioCoordinator.WORKER.queue.offer(close, 5, TimeUnit.SECONDS)); close.get(5, TimeUnit.SECONDS)
        }
        resetClosedAudio()
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        root.deleteRecursively()
        File(context.noBackupFilesDir, "factory-file-sharing").deleteRecursively()
        File(context.cacheDir, "factory-file-shares").deleteRecursively()
    }
    private fun startup(body: String) {
        root.mkdirs(); File(root, "interaction.json").writeText(body)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = FactoryAudioCoordinator.WORKER
        worker.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            Instrumentation.newApplication(FactoryInstallApplication::class.java, context).onCreate()
            val audio = FactoryAudioCoordinator.get(context)
            assertTrue(audio.isBusy()); assertFalse(audio.canBegin()); assertNull(audio.session())
            assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
            assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isFailure)
            assertFalse(FactoryDocumentCoordinator.get(context).canBegin())
        } finally { release.countDown() }
        audioBarrier(); FactoryStartupTestIsolation.awaitSharingWorkerCompletion()
        val audio = FactoryAudioCoordinator.get(context)
        assertFalse(audio.isBusy()); assertTrue(audio.needsRecovery()); assertEquals("outcome_unknown", audio.status())
        assertNull(audio.session()); assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        val refused = FutureTask(Callable { audio.close(null, true) { error("No human confirmation") } })
        check(worker.queue.offer(refused, 5, TimeUnit.SECONDS)); assertTrue(runCatching { refused.get(5, TimeUnit.SECONDS) }.isFailure)
        assertTrue(audio.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
        val close = FutureTask(Callable { audio.close(null, true) {} })
        check(worker.queue.offer(close, 5, TimeUnit.SECONDS)); assertNull(close.get(5, TimeUnit.SECONDS))
        assertEquals("closed_outcome_unknown", audio.status()); assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun openAudioJournalBlocksAutomationBeforeAsyncRestoreStarts() = startup(JSONObject()
        .put("schemaVersion", 1).put("state", "play_pending").put("open", true).put("nonce", "a".repeat(64)).toString())
    @Test fun corruptAudioJournalRetainsProtectionThroughRealApplicationStartup() = startup("{")
}
