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

/** Actual Application startup with synthetic durable journals. No external app, call, or navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [FactoryFileShareOsShadow::class])
class FactoryExternalLaunchApplicationStartupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-external-launch")
    private val browserRoot get() = File(context.noBackupFilesDir, "factory-browser")
    private fun <T> worker(executor: java.util.concurrent.ThreadPoolExecutor = FactoryExternalLaunchCoordinator.WORKER, block: () -> T): T {
        executor.prestartCoreThread()
        val task = FutureTask(Callable(block)); check(executor.queue.offer(task, 5, TimeUnit.SECONDS)); return task.get(5, TimeUnit.SECONDS)
    }
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        for (name in listOf("factory-external-launch", "factory-browser", "factory-audio", "factory-photos", "factory-file-sharing", "factory-documents", "factory-install")) File(context.noBackupFilesDir, name).deleteRecursively()
        File(context.cacheDir, "factory-file-shares").deleteRecursively()
    }
    @After fun cleanup() {
        FactoryStartupTestIsolation.awaitSharingWorkerCompletion()
        val externalField = FactoryExternalLaunchCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val external = externalField.get(null) as? FactoryExternalLaunchCoordinator
        if (external?.needsRecovery() == true) worker { external.close(null, true, true) {} }
        val browserField = FactoryBrowserCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val browser = browserField.get(null) as? FactoryBrowserCoordinator
        if (browser?.needsRecovery() == true) worker(FactoryBrowserCoordinator.WORKER) { browser.close(null, true, true) {} }
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        for (name in listOf("factory-external-launch", "factory-browser", "factory-audio", "factory-file-sharing")) File(context.noBackupFilesDir, name).deleteRecursively()
        File(context.cacheDir, "factory-file-shares").deleteRecursively()
    }
    private fun externalCreatedByApplication(): FactoryExternalLaunchCoordinator {
        val field = FactoryExternalLaunchCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        return checkNotNull(field.get(null) as? FactoryExternalLaunchCoordinator) { "Application.onCreate must initialize external recovery before automation" }
    }
    private fun browserCreatedByApplication(): FactoryBrowserCoordinator {
        val field = FactoryBrowserCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        return checkNotNull(field.get(null) as? FactoryBrowserCoordinator) { "Application.onCreate must retain the legacy browser recovery owner" }
    }
    private fun startup(body: String) {
        root.mkdirs(); File(root, "interaction.json").writeText(body)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        FactoryExternalLaunchCoordinator.WORKER.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            Instrumentation.newApplication(FactoryInstallApplication::class.java, context).onCreate()
            val external = externalCreatedByApplication()
            assertTrue(external.isBusy()); assertFalse(external.canBegin()); assertNull(external.session())
            assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
            assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isFailure)
            assertFalse(FactoryDocumentCoordinator.get(context).canBegin()); assertFalse(FactoryAudioCoordinator.get(context).canBegin())
            assertFalse(browserCreatedByApplication().canBegin())
        } finally { release.countDown() }
        worker {}; FactoryStartupTestIsolation.awaitSharingWorkerCompletion()
        val external = externalCreatedByApplication()
        assertFalse(external.isBusy()); assertTrue(external.needsRecovery()); assertEquals("outcome_unknown", external.status()); assertNull(external.session())
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        assertTrue(runCatching { worker { external.close(null, true, false) {} } }.isFailure)
        assertTrue(runCatching { worker { external.close(null, true, true) { error("No human confirmation") } } }.isFailure)
        assertTrue(external.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
        assertNull(worker { external.close(null, true, true) {} })
        assertEquals("closed_outcome_unknown", external.status()); assertNull(external.session())
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        val journal = JSONObject(File(root, "interaction.json").readText())
        assertEquals(setOf("schemaVersion", "state", "open", "nonce"), journal.keys().asSequence().toSet()); assertFalse(journal.getBoolean("open"))
    }
    @Test fun openExternalJournalBlocksAutomationBeforeAsynchronousRestoreEvenStarts() = startup(JSONObject()
        .put("schemaVersion", 1).put("state", "launch_pending").put("open", true).put("nonce", "a".repeat(64)).toString())
    @Test fun corruptExternalJournalRetainsGuardThroughRealApplicationStartupAndHumanRecovery() = startup("{")
    @Test fun reviewJournalRestoresOnlyUnknownOutcomeWithoutRecreatingReviewAuthority() = startup(JSONObject()
        .put("schemaVersion", 1).put("state", "review").put("open", true).put("nonce", "b".repeat(64)).toString())
    @Test fun realStartupRestoresBothJournalsAndClosingExternalPreservesBrowserRecovery() = simultaneousRecovery(externalFirst = true)
    @Test fun realStartupRestoresBothJournalsAndClosingBrowserPreservesExternalRecovery() = simultaneousRecovery(externalFirst = false)
    private fun simultaneousRecovery(externalFirst: Boolean) {
        root.mkdirs(); browserRoot.mkdirs()
        File(root, "interaction.json").writeText(JSONObject().put("schemaVersion", 1).put("state", "launch_pending").put("open", true).put("nonce", "c".repeat(64)).toString())
        File(browserRoot, "interaction.json").writeText(JSONObject().put("schemaVersion", 1).put("state", "review").put("open", true).put("nonce", "d".repeat(64)).toString())
        val externalEntered = CountDownLatch(1); val externalRelease = CountDownLatch(1)
        val browserEntered = CountDownLatch(1); val browserRelease = CountDownLatch(1)
        FactoryExternalLaunchCoordinator.WORKER.execute { externalEntered.countDown(); check(externalRelease.await(10, TimeUnit.SECONDS)) }
        FactoryBrowserCoordinator.WORKER.execute { browserEntered.countDown(); check(browserRelease.await(10, TimeUnit.SECONDS)) }
        try {
            assertTrue(externalEntered.await(5, TimeUnit.SECONDS)); assertTrue(browserEntered.await(5, TimeUnit.SECONDS))
            Instrumentation.newApplication(FactoryInstallApplication::class.java, context).onCreate()
            val external = externalCreatedByApplication(); val browser = browserCreatedByApplication()
            assertTrue(external.isBusy()); assertTrue(browser.isBusy()); assertNull(external.session()); assertNull(browser.session())
            assertFalse(external.canBegin()); assertFalse(browser.canBegin())
            assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
            assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isFailure)
        } finally { externalRelease.countDown(); browserRelease.countDown() }
        FactoryStartupTestIsolation.awaitSharingWorkerCompletion()
        val external = externalCreatedByApplication(); val browser = browserCreatedByApplication()
        assertFalse(external.isBusy()); assertFalse(browser.isBusy())
        assertTrue(external.needsRecovery()); assertTrue(browser.needsRecovery()); assertNull(external.session()); assertNull(browser.session())
        assertEquals("outcome_unknown", external.status()); assertEquals("outcome_unknown", browser.status())
        assertFalse(FactoryInteractionAdmission.available()); assertTrue(MemoryUiAutomationGuard.isProtected())
        val otherFile: File
        if (externalFirst) {
            assertNull(worker { external.close(null, true, true) {} })
            assertEquals("closed_outcome_unknown", external.status()); assertFalse(external.needsRecovery())
            assertTrue(browser.needsRecovery()); assertFalse(external.canBegin()); otherFile = File(browserRoot, "interaction.json")
        } else {
            assertNull(worker(FactoryBrowserCoordinator.WORKER) { browser.close(null, true, true) {} })
            assertEquals("closed_outcome_unknown", browser.status()); assertFalse(browser.needsRecovery())
            assertTrue(external.needsRecovery()); assertFalse(browser.canBegin()); otherFile = File(root, "interaction.json")
        }
        val stillOpen = JSONObject(otherFile.readText())
        assertTrue(stillOpen.getBoolean("open")); assertEquals("outcome_unknown", stillOpen.getString("state"))
        assertFalse(FactoryInteractionAdmission.available()); assertTrue(MemoryUiAutomationGuard.isProtected())
        assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isFailure)
        assertFalse(FactoryDocumentCoordinator.get(context).canBegin()); assertFalse(FactoryAudioCoordinator.get(context).canBegin())
        if (externalFirst) assertNull(worker(FactoryBrowserCoordinator.WORKER) { browser.close(null, true, true) {} })
        else assertNull(worker { external.close(null, true, true) {} })
        assertNull(external.session()); assertNull(browser.session()); assertFalse(external.needsRecovery()); assertFalse(browser.needsRecovery())
        assertTrue(FactoryInteractionAdmission.available()); assertFalse(MemoryUiAutomationGuard.isProtected())
        assertTrue(external.canBegin()); assertTrue(browser.canBegin())
        for (directory in listOf(root, browserRoot)) {
            val closed = JSONObject(File(directory, "interaction.json").readText())
            assertEquals(setOf("schemaVersion", "state", "open", "nonce"), closed.keys().asSequence().toSet())
            assertEquals("closed_outcome_unknown", closed.getString("state")); assertFalse(closed.getBoolean("open"))
        }
    }

}
