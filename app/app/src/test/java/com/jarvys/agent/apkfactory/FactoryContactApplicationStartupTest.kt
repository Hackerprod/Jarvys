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
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Actual Application startup from synthetic nonce/state journals, with no contact data or provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [FactoryFileShareOsShadow::class])
class FactoryContactApplicationStartupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-contacts")
    private val externalRoot get() = File(context.noBackupFilesDir, "factory-external-launch")
    private fun <T> worker(executor: ThreadPoolExecutor = FactoryContactCoordinator.WORKER, block: () -> T): T {
        executor.prestartCoreThread(); val task = FutureTask(Callable(block))
        check(executor.queue.offer(task, 5, TimeUnit.SECONDS)); return task.get(5, TimeUnit.SECONDS)
    }
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        for (name in DIRECTORIES) File(context.noBackupFilesDir, name).deleteRecursively()
        File(context.cacheDir, "factory-file-shares").deleteRecursively()
    }
    @After fun cleanup() {
        FactoryStartupTestIsolation.awaitSharingWorkerCompletion()
        val contacts = FactoryContactCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null) as? FactoryContactCoordinator
        if (contacts?.needsRecovery() == true) worker { contacts.close(null, true, true) {} }
        val external = FactoryExternalLaunchCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null) as? FactoryExternalLaunchCoordinator
        if (external?.needsRecovery() == true) worker(FactoryExternalLaunchCoordinator.WORKER) { external.close(null, true, true) {} }
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        for (name in DIRECTORIES) File(context.noBackupFilesDir, name).deleteRecursively()
        File(context.cacheDir, "factory-file-shares").deleteRecursively()
    }
    private fun contactsCreatedByApplication(): FactoryContactCoordinator = checkNotNull(
        FactoryContactCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null) as? FactoryContactCoordinator
    ) { "Application.onCreate must synchronously reserve contact recovery before automation" }
    private fun externalCreatedByApplication(): FactoryExternalLaunchCoordinator = checkNotNull(
        FactoryExternalLaunchCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null) as? FactoryExternalLaunchCoordinator
    ) { "Application.onCreate must preserve the existing external recovery owner" }
    private fun startup(body: String) {
        root.mkdirs(); File(root, "interaction.json").writeText(body)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        FactoryContactCoordinator.WORKER.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            Instrumentation.newApplication(FactoryInstallApplication::class.java, context).onCreate()
            val contacts = contactsCreatedByApplication()
            assertTrue(contacts.isBusy()); assertFalse(contacts.canBegin()); assertNull(contacts.session())
            assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
            assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isFailure)
            assertFalse(FactoryDocumentCoordinator.get(context).canBegin()); assertFalse(FactoryAudioCoordinator.get(context).canBegin())
            assertFalse(FactoryBrowserCoordinator.get(context).canBegin()); assertFalse(externalCreatedByApplication().canBegin())
        } finally { release.countDown() }
        FactoryStartupTestIsolation.awaitSharingWorkerCompletion()
        val contacts = contactsCreatedByApplication()
        assertFalse(contacts.isBusy()); assertTrue(contacts.needsRecovery()); assertEquals("outcome_unknown", contacts.status()); assertNull(contacts.session())
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        assertTrue(runCatching { worker { contacts.close(null, true, false) {} } }.isFailure)
        assertTrue(runCatching { worker { contacts.close(null, true, true) { error("No human confirmation") } } }.isFailure)
        assertEquals(Unit, worker { contacts.close(null, true, true) {} })
        assertEquals("closed_outcome_unknown", contacts.status()); assertNull(contacts.session())
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        val journal = JSONObject(File(root, "interaction.json").readText())
        assertEquals(setOf("schemaVersion", "state", "open", "nonce"), journal.keys().asSequence().toSet()); assertFalse(journal.getBoolean("open"))
    }
    @Test fun openPickerJournalBlocksAutomationBeforeAsynchronousRestoreStarts() = startup(saved("launch_pending"))
    @Test fun selectedJournalRestoresNoContactDatumOrCallbackAuthority() = startup(saved("selected"))
    @Test fun initialReviewJournalRestoresOnlyUnknownOutcome() = startup(saved("review"))
    @Test fun corruptJournalRetainsProtectionUntilHumanRecovery() = startup("{")
    @Test fun realStartupKeepsExternalRecoveryWhenContactRecoveryClosesFirst() = simultaneousRecovery(contactsFirst = true)
    @Test fun realStartupKeepsContactRecoveryWhenExternalRecoveryClosesFirst() = simultaneousRecovery(contactsFirst = false)
    private fun simultaneousRecovery(contactsFirst: Boolean) {
        root.mkdirs(); externalRoot.mkdirs()
        File(root, "interaction.json").writeText(saved("selected")); File(externalRoot, "interaction.json").writeText(saved("launch_pending", "e".repeat(64)))
        val contactsEntered = CountDownLatch(1); val contactsRelease = CountDownLatch(1)
        val externalEntered = CountDownLatch(1); val externalRelease = CountDownLatch(1)
        FactoryContactCoordinator.WORKER.execute { contactsEntered.countDown(); check(contactsRelease.await(10, TimeUnit.SECONDS)) }
        FactoryExternalLaunchCoordinator.WORKER.execute { externalEntered.countDown(); check(externalRelease.await(10, TimeUnit.SECONDS)) }
        try {
            assertTrue(contactsEntered.await(5, TimeUnit.SECONDS)); assertTrue(externalEntered.await(5, TimeUnit.SECONDS))
            Instrumentation.newApplication(FactoryInstallApplication::class.java, context).onCreate()
            assertTrue(contactsCreatedByApplication().isBusy()); assertTrue(externalCreatedByApplication().isBusy())
            assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        } finally { contactsRelease.countDown(); externalRelease.countDown() }
        FactoryStartupTestIsolation.awaitSharingWorkerCompletion()
        val contacts = contactsCreatedByApplication(); val external = externalCreatedByApplication()
        assertTrue(contacts.needsRecovery()); assertTrue(external.needsRecovery()); assertNull(contacts.session()); assertNull(external.session())
        if (contactsFirst) worker { contacts.close(null, true, true) {} } else worker(FactoryExternalLaunchCoordinator.WORKER) { external.close(null, true, true) {} }
        assertTrue(if (contactsFirst) external.needsRecovery() else contacts.needsRecovery())
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isFailure)
        if (contactsFirst) worker(FactoryExternalLaunchCoordinator.WORKER) { external.close(null, true, true) {} } else worker { contacts.close(null, true, true) {} }
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        for (directory in listOf(root, externalRoot)) {
            val saved = JSONObject(File(directory, "interaction.json").readText())
            assertEquals(setOf("schemaVersion", "state", "open", "nonce"), saved.keys().asSequence().toSet())
            assertEquals("closed_outcome_unknown", saved.getString("state")); assertFalse(saved.getBoolean("open"))
        }
    }
    private fun saved(state: String, nonce: String = "d".repeat(64)) = JSONObject().put("schemaVersion", 1).put("state", state).put("open", true).put("nonce", nonce).toString()
    companion object {
        private val DIRECTORIES = listOf("factory-contacts", "factory-external-launch", "factory-browser", "factory-audio", "factory-photos", "factory-file-sharing", "factory-documents", "factory-install")
    }
}
