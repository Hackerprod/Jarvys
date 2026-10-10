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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Application entrypoint, synthetic journals, no media or external UI. Never resets live guards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [FactoryFileShareOsShadow::class])
class FactoryPhotoApplicationStartupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-photos")
    private val journal get() = File(root, "interaction.json")
    private fun resetClosedPhotoSingleton() {
        val field = FactoryPhotoCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val prior = field.get(null) as? FactoryPhotoCoordinator
        if (prior != null) {
            check(!prior.needsRecovery()) { "Cannot reset a live photo recovery interaction" }
            val lease = FactoryPhotoCoordinator::class.java.getDeclaredField("lease").apply { isAccessible = true }
            check(lease.get(prior) == null) { "Cannot erase a live photo protection lease" }
            FactoryInteractionAdmission.release(prior)
        }
        field.set(null, null)
    }
    @Before fun before() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup(); resetClosedPhotoSingleton()
        for (name in listOf("factory-photos", "factory-file-sharing", "factory-documents", "factory-install")) File(context.noBackupFilesDir, name).deleteRecursively()
        File(context.cacheDir, "factory-file-shares").deleteRecursively()
    }
    @After fun after() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        val photo = FactoryPhotoCoordinator.get(context)
        if (photo.needsRecovery()) photo.acknowledgeRecovery {}
        resetClosedPhotoSingleton(); root.deleteRecursively()
        File(context.noBackupFilesDir, "factory-file-sharing").deleteRecursively()
        File(context.cacheDir, "factory-file-shares").deleteRecursively()
    }
    private fun startup() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = FactoryFileShareCoordinator.WORKER.submit { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            val app = Instrumentation.newApplication(FactoryInstallApplication::class.java, context)
            app.onCreate()
            assertTrue(MemoryUiAutomationGuard.isProtected())
            assertFalse(FactoryInteractionAdmission.available())
            assertTrue(FactoryFileShareCoordinator.startupPending())
            assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isFailure)
            assertFalse(FactoryDocumentCoordinator.get(context).canBegin())
            assertTrue(FactoryPhotoCoordinator.get(context).needsRecovery())
        } finally { release.countDown() }
        blocker.get(5, TimeUnit.SECONDS); FactoryStartupTestIsolation.awaitSharingWorkerCompletion()
        assertFalse(FactoryFileShareCoordinator.startupPending())
        assertFalse(FactoryFileShareCoordinator.get(context).needsRecovery())
        assertTrue(MemoryUiAutomationGuard.isProtected())
        assertFalse(FactoryInteractionAdmission.available())
    }
    @Test fun openPhotoJournalRestoresProtectionBeforeOtherBrokerCanAdmit() {
        root.mkdirs(); journal.writeText(JSONObject().put("schemaVersion", 1).put("state", "picker").put("open", true).put("nonce", "a".repeat(64)).toString())
        startup()
        val photo = FactoryPhotoCoordinator.get(context)
        assertEquals("outcome_unknown", photo.status()); assertTrue(photo.needsRecovery())
        assertFalse(photo.canBegin()); assertFalse(FactoryDocumentCoordinator.get(context).canBegin())
        assertTrue(runCatching { photo.acknowledgeRecovery { error("background") } }.isFailure)
        assertTrue(MemoryUiAutomationGuard.isProtected())
        photo.acknowledgeRecovery {}
        assertEquals("closed_outcome_unknown", photo.status())
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun malformedPhotoJournalRetainsProtectionAfterSharingStartupCompletes() {
        root.mkdirs(); journal.writeText("{")
        startup()
        val photo = FactoryPhotoCoordinator.get(context)
        assertTrue(photo.needsRecovery()); assertEquals("outcome_unknown", photo.status())
        photo.acknowledgeRecovery {}
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
    }
}
