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

/** Actual Application startup; durable state is synthetic, and no provider task is launched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [FactoryFileShareOsShadow::class])
class FactoryDataBackupApplicationStartupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-data-backup")
    private val roots = listOf("factory-data-backup", "factory-contacts", "factory-external-launch", "factory-browser", "factory-audio", "factory-photos", "factory-file-sharing", "factory-documents", "factory-install")
    private fun <T> worker(block: () -> T): T {
        val executor = FactoryDataBackupCoordinator.WORKER; executor.prestartCoreThread()
        val task = FutureTask(Callable(block)); check(executor.queue.offer(task, 5, TimeUnit.SECONDS)); return task.get(5, TimeUnit.SECONDS)
    }
    private fun created() = checkNotNull(FactoryDataBackupCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null) as? FactoryDataBackupCoordinator)
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        roots.forEach { File(context.noBackupFilesDir, it).deleteRecursively() }; File(context.cacheDir, "factory-file-shares").deleteRecursively()
    }
    @After fun cleanup() {
        FactoryStartupTestIsolation.awaitSharingWorkerCompletion()
        val value = created(); if (value.needsRecovery()) worker { value.close(null, true, true) {} }
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        roots.forEach { File(context.noBackupFilesDir, it).deleteRecursively() }; File(context.cacheDir, "factory-file-shares").deleteRecursively()
    }
    private fun startup(body: String) {
        root.mkdirs(); File(root, "interaction.json").writeText(body)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        FactoryDataBackupCoordinator.WORKER.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            Instrumentation.newApplication(FactoryInstallApplication::class.java, context).onCreate()
            val value = created()
            assertTrue(value.isBusy()); assertNull(value.session()); assertFalse(value.canBegin())
            assertFalse(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
            assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isSuccess)
        } finally { release.countDown() }
        FactoryStartupTestIsolation.awaitSharingWorkerCompletion()
        val value = created(); assertTrue(value.needsRecovery()); assertEquals("outcome_unknown", value.status()); assertNull(value.session())
        assertTrue(runCatching { worker { value.close(null, true, false) {} } }.isFailure)
        assertTrue(runCatching { worker { value.close(null, true, true) { error("No human acknowledgement") } } }.isFailure)
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        worker { value.close(null, true, true) {} }
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        val saved = JSONObject(File(root, "interaction.json").readText())
        assertEquals(setOf("schemaVersion", "state", "open", "nonce"), saved.keys().asSequence().toSet())
        assertEquals("closed_outcome_unknown", saved.getString("state")); assertFalse(saved.getBoolean("open"))
    }
    @Test fun pickerJournalProtectsBeforeRestoreWorkerCanRunAndNeverReplays() = startup(JSONObject()
        .put("schemaVersion", 1).put("state", "picker").put("open", true).put("nonce", "a".repeat(64)).toString())
    @Test fun malformedJournalAlsoProtectsBeforeAnyStartupAction() = startup("{")
}
