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

        val browserField = FactoryBrowserCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val browser = browserField.get(null) as? FactoryBrowserCoordinator

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
            assertFalse(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
            assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isSuccess)
            assertFalse(FactoryInteractionAdmission.available(external))
            val documents = FactoryDocumentCoordinator.get(context)
            assertTrue(documents.canBegin()); assertTrue(FactoryInteractionAdmission.available(documents))
            val audio = FactoryAudioCoordinator.get(context); val browser = browserCreatedByApplication()
            worker(FactoryAudioCoordinator.WORKER) {}; worker(FactoryBrowserCoordinator.WORKER) {}
            assertFalse(audio.isBusy()); assertTrue(audio.canBegin()); assertTrue(FactoryInteractionAdmission.available(audio))
            assertFalse(browser.isBusy()); assertTrue(browser.canBegin()); assertTrue(FactoryInteractionAdmission.available(browser))
        } finally { release.countDown() }
        worker {}; FactoryStartupTestIsolation.awaitSharingWorkerCompletion()
        val external = externalCreatedByApplication()
        assertFalse(external.isBusy()); assertEquals("closed_outcome_unknown", external.status()); assertNull(external.session())
        assertTrue(external.canBegin()); assertTrue(FactoryInteractionAdmission.available(external))
        assertTrue(FactoryDocumentCoordinator.get(context).canBegin())
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        val journal = JSONObject(File(root, "interaction.json").readText())
        assertEquals(setOf("schemaVersion", "state", "open", "nonce"), journal.keys().asSequence().toSet()); assertFalse(journal.getBoolean("open"))
    }
    @Test fun openExternalJournalDoesNotBlockAutomationBeforeAsynchronousRestoreEvenStarts() = startup(JSONObject()
        .put("schemaVersion", 1).put("state", "launch_pending").put("open", true).put("nonce", "a".repeat(64)).toString())
    @Test fun corruptExternalJournalIsClosedDuringRealApplicationStartup() = startup("{")
    @Test fun interruptedReviewJournalIsClosedDuringStartup() = startup(JSONObject()
        .put("schemaVersion", 1).put("state", "review").put("open", true).put("nonce", "b".repeat(64)).toString())
    @Test fun realStartupAutomaticallyClosesBothExternalJournals() = simultaneousRecovery(externalFirst = true)
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
            assertFalse(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
            assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isSuccess)
        } finally { externalRelease.countDown(); browserRelease.countDown() }
        FactoryStartupTestIsolation.awaitSharingWorkerCompletion()
        val external = externalCreatedByApplication(); val browser = browserCreatedByApplication()
        assertFalse(external.isBusy()); assertFalse(browser.isBusy())
        assertEquals("closed_outcome_unknown", external.status()); assertEquals("closed_outcome_unknown", browser.status()); assertNull(external.session()); assertNull(browser.session())
        assertTrue(external.canBegin()); assertTrue(browser.canBegin())
        assertTrue(FactoryInteractionAdmission.available()); assertFalse(MemoryUiAutomationGuard.isProtected())
        assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isSuccess)
        val documents = FactoryDocumentCoordinator.get(context); val audio = FactoryAudioCoordinator.get(context)
        worker(FactoryAudioCoordinator.WORKER) {}
        assertTrue(documents.canBegin()); assertTrue(FactoryInteractionAdmission.available(documents))
        assertFalse(audio.isBusy()); assertTrue(audio.canBegin()); assertTrue(FactoryInteractionAdmission.available(audio))
        assertNull(external.session()); assertNull(browser.session()); assertTrue(external.canBegin()); assertTrue(browser.canBegin())
        assertTrue(FactoryInteractionAdmission.available()); assertFalse(MemoryUiAutomationGuard.isProtected())
        assertTrue(external.canBegin()); assertTrue(browser.canBegin())
        for (directory in listOf(root, browserRoot)) {
            val closed = JSONObject(File(directory, "interaction.json").readText())
            assertEquals(setOf("schemaVersion", "state", "open", "nonce"), closed.keys().asSequence().toSet())
            assertEquals("closed_outcome_unknown", closed.getString("state")); assertFalse(closed.getBoolean("open"))
        }
    }

}
