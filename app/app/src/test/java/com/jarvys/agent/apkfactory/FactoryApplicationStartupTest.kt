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

/** Executes the real production Application.onCreate, using a strict filesystem shadow rather
 * than Robolectric's lstat model that invents a StructStat even for a nonexistent path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [FactoryFileShareOsShadow::class])
class FactoryApplicationStartupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-file-sharing")
    private val journal get() = File(root, "interaction.json")
    @Before fun before() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        root.deleteRecursively()
        File(context.cacheDir, "factory-file-shares").deleteRecursively()
        File(context.noBackupFilesDir, "factory-documents").deleteRecursively()
        File(context.noBackupFilesDir, "factory-install").deleteRecursively()
    }
    @After fun after() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        root.deleteRecursively()
        File(context.cacheDir, "factory-file-shares").deleteRecursively()
    }
    private fun startup() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val blocker = FactoryFileShareCoordinator.WORKER.submit {
            entered.countDown(); check(unblock.await(10, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            // Attach a real instance to the test application context, then run its unmodified entrypoint.
            val app = Instrumentation.newApplication(FactoryInstallApplication::class.java, context)
            assertEquals(FactoryInstallApplication::class.java, app.javaClass)
            app.onCreate()
            assertFalse("Read-only startup must not create a global human-only surface", MemoryUiAutomationGuard.isProtected())
            assertFalse("The sharing family keeps its actual cleanup resource slot", FactoryInteractionAdmission.available())
            val sharing = FactoryFileShareCoordinator.get(context)
            assertFalse(FactoryInteractionAdmission.available(sharing)); assertFalse(sharing.canBegin())
            assertTrue(FactoryFileShareCoordinator.startupPending())
            assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isSuccess)
            assertTrue(FactoryDocumentCoordinator.get(context).canBegin())
        } finally { unblock.countDown() }
        blocker.get(5, TimeUnit.SECONDS)
        // Completion barrier on the same worker, never cancellation or an arbitrary timing sleep.
        FactoryStartupTestIsolation.awaitSharingWorkerCompletion()
        assertFalse(FactoryFileShareCoordinator.startupPending())
    }
    @Test fun fixtureIsolationPreservesAnUnrelatedLiveLeaseAndAdmissionOwner() {
        startup()
        val other = Any()
        FactoryInteractionAdmission.restore(other)
        MemoryUiAutomationGuard.enterProtectedSurface().use {
            try {
                FactoryStartupTestIsolation.releaseCompletedSharingStartup()
                assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
            } finally { FactoryInteractionAdmission.release(other) }
        }
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
    }
}
