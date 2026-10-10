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

/** Actual Application startup, synthetic no-backup journal, and no external browser calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [FactoryFileShareOsShadow::class])
class FactoryBrowserApplicationStartupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-browser")
    private fun <T> worker(block: () -> T): T {
        val executor = FactoryBrowserCoordinator.WORKER; executor.prestartCoreThread()
        val task = FutureTask(Callable(block)); check(executor.queue.offer(task, 5, TimeUnit.SECONDS)); return task.get(5, TimeUnit.SECONDS)
    }
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        for (name in listOf("factory-browser", "factory-audio", "factory-photos", "factory-file-sharing", "factory-documents", "factory-install")) File(context.noBackupFilesDir, name).deleteRecursively()
        File(context.cacheDir, "factory-file-shares").deleteRecursively()
    }
    @After fun cleanup() {
        worker {}
        val field = FactoryBrowserCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val browser = field.get(null) as? FactoryBrowserCoordinator
        if (browser?.needsRecovery() == true) worker { browser.close(null, true, true) {} }
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        for (name in listOf("factory-browser", "factory-audio", "factory-file-sharing")) File(context.noBackupFilesDir, name).deleteRecursively()
        File(context.cacheDir, "factory-file-shares").deleteRecursively()
    }
    private fun startup(body: String) {
        root.mkdirs(); File(root, "interaction.json").writeText(body)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        FactoryBrowserCoordinator.WORKER.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            Instrumentation.newApplication(FactoryInstallApplication::class.java, context).onCreate()
            val browser = FactoryBrowserCoordinator.get(context)
            assertTrue(browser.isBusy()); assertFalse(browser.canBegin()); assertNull(browser.session())
            assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
            assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isFailure)
            assertFalse(FactoryDocumentCoordinator.get(context).canBegin()); assertFalse(FactoryAudioCoordinator.get(context).canBegin())
        } finally { release.countDown() }
        worker {}; FactoryStartupTestIsolation.awaitSharingWorkerCompletion()
        val browser = FactoryBrowserCoordinator.get(context)
        assertFalse(browser.isBusy()); assertTrue(browser.needsRecovery()); assertEquals("outcome_unknown", browser.status()); assertNull(browser.session())
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        assertTrue(runCatching { worker { browser.close(null, true, false) {} } }.isFailure)
        assertTrue(runCatching { worker { browser.close(null, true, true) { error("No human confirmation") } } }.isFailure)
        assertTrue(browser.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
        assertNull(worker { browser.close(null, true, true) {} })
        assertEquals("closed_outcome_unknown", browser.status()); assertNull(browser.session())
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        val journal = JSONObject(File(root, "interaction.json").readText())
        assertEquals(setOf("schemaVersion", "state", "open", "nonce"), journal.keys().asSequence().toSet()); assertFalse(journal.getBoolean("open"))
    }
    @Test fun openBrowserJournalBlocksAutomationBeforeAsynchronousRestoreEvenStarts() = startup(JSONObject()
        .put("schemaVersion", 1).put("state", "launch_pending").put("open", true).put("nonce", "a".repeat(64)).toString())
    @Test fun corruptBrowserJournalRetainsGuardThroughRealApplicationStartupAndHumanRecovery() = startup("{")
    @Test fun reviewJournalRestoresOnlyUnknownOutcomeWithoutRecreatingReviewAuthority() = startup(JSONObject()
        .put("schemaVersion", 1).put("state", "review").put("open", true).put("nonce", "b".repeat(64)).toString())
}
