package com.jarvys.agent.apkfactory

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Synthetic native authority editor: no real app permission or signing identity is modified. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryPermissionsActivityTest {
    class TestPermissionsActivity : FactoryPermissionsActivity() {
        internal override fun policyStore(): FactoryOperationPolicy = provider()
        companion object { internal lateinit var provider: () -> FactoryOperationPolicy }
    }

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var policy: FactoryOperationPolicy
    private var controller: ActivityController<TestPermissionsActivity>? = null
    private val first = FactoryOperationPolicy.Subject.signedArtifact("org.example.editor", "a".repeat(64))
    private val second = FactoryOperationPolicy.Subject.signedArtifact("org.example.editor", "b".repeat(64))

    @Before fun before() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        context = ApplicationProvider.getApplicationContext()
        root = File(context.noBackupFilesDir, "factory-operation-policy")
        root.deleteRecursively()
        policy = FactoryOperationPolicy.persisted(context)
        TestPermissionsActivity.provider = { policy }
    }
    @After fun after() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        root.deleteRecursively()
    }
    private fun launch(drain: Boolean = true): TestPermissionsActivity {
        controller = Robolectric.buildActivity(TestPermissionsActivity::class.java).setup()
        return controller!!.get().also { if (drain) drain(it) }
    }
    private fun button(activity: FactoryPermissionsActivity, tag: String) =
        activity.window.decorView.findViewWithTag<Button>("factory-permissions-$tag")
    private fun worker(activity: FactoryPermissionsActivity) =
        FactoryPermissionsActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(activity) as ExecutorService
    private fun drain(activity: FactoryPermissionsActivity) {
        repeat(2) { worker(activity).submit {}.get(10, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() }
    }
    private fun latestDialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
    private fun choose(dialog: AlertDialog, position: Int) {
        val list = dialog.listView
        val row: View = list.adapter.getView(position, null, list)
        list.performItemClick(row, position, list.adapter.getItemId(position))
    }
    private fun observe(subject: FactoryOperationPolicy.Subject) {
        policy.acquire(subject, FactoryOperationPolicy.Operation.SIGN, FactoryOperationPolicy.binding("editor fixture", subject.key)).close()
    }

    @Test fun authorityEditorIsSecureNonExportedAndBlocksAutomatedEdits() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        val activity = launch()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertFalse(activity.packageManager.getActivityInfo(ComponentName(activity, FactoryPermissionsActivity::class.java), 0).exported)
        assertTrue(button(activity, "default").filterTouchesWhenObscured)
        assertTrue(button(activity, "default").isEnabled)
        assertTrue(MemoryUiAutomationGuard.isProtected())
        var executed = false
        assertFalse(MemoryUiAutomationGuard.runAutomated(epoch) { executed = true; button(activity, "default").performClick() })
        assertFalse(executed)
        assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, policy.defaultMode())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun repeatedDefaultModeEditsPersistAndInvalidateOldRequests() {
        val activity = launch()
        for (mode in listOf(FactoryOperationPolicy.Mode.DENY, FactoryOperationPolicy.Mode.ASK, FactoryOperationPolicy.Mode.ALLOW_ALWAYS)) {
            val old = if (policy.defaultMode() != FactoryOperationPolicy.Mode.DENY)
                policy.acquire(first, FactoryOperationPolicy.Operation.INSTALL, FactoryOperationPolicy.binding("before", mode.name)) else null
            button(activity, "default").performClick()
            choose(latestDialog(), FactoryOperationPolicy.Mode.entries.indexOf(mode))
            drain(activity)
            assertEquals(mode, policy.defaultMode())
            assertEquals(mode, FactoryOperationPolicy.persisted(context).defaultMode())
            old?.let { assertTrue(runCatching { it.requireCurrent() }.isFailure) }
            assertTrue(button(activity, "default").isEnabled)
            assertTrue(MemoryUiAutomationGuard.isProtected())
        }
    }

    @Test fun perCertificateOverridesPersistAcrossRepeatedEditsAndReturnToInheritedDefault() {
        observe(first); observe(second)
        val activity = launch()
        assertTrue(button(activity, "app").text.contains("aaaaaaaaaaaaaaaa"))
        for (mode in listOf(FactoryOperationPolicy.Mode.DENY, FactoryOperationPolicy.Mode.ASK, FactoryOperationPolicy.Mode.ALLOW_ALWAYS)) {
            button(activity, "SIGN").performClick()
            choose(latestDialog(), FactoryOperationPolicy.Mode.entries.indexOf(mode) + 1)
            drain(activity)
            val restored = FactoryOperationPolicy.persisted(context)
            assertEquals(mode, restored.overrideMode(first, FactoryOperationPolicy.Operation.SIGN))
            assertNull(restored.overrideMode(second, FactoryOperationPolicy.Operation.SIGN))
            assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, restored.mode(second, FactoryOperationPolicy.Operation.SIGN))
        }
        button(activity, "default").performClick(); choose(latestDialog(), 2); drain(activity)
        assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, policy.mode(first, FactoryOperationPolicy.Operation.SIGN))
        assertEquals(FactoryOperationPolicy.Mode.DENY, policy.mode(second, FactoryOperationPolicy.Operation.SIGN))
        button(activity, "SIGN").performClick(); choose(latestDialog(), 0); drain(activity)
        val restored = FactoryOperationPolicy.persisted(context)
        assertNull(restored.overrideMode(first, FactoryOperationPolicy.Operation.SIGN))
        assertEquals(FactoryOperationPolicy.Mode.DENY, restored.mode(first, FactoryOperationPolicy.Operation.SIGN))
        button(activity, "app").performClick(); choose(latestDialog(), 1)
        assertTrue(button(activity, "app").text.contains("bbbbbbbbbbbbbbbb"))
        button(activity, "SIGN").performClick(); choose(latestDialog(), 1); drain(activity)
        assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, policy.mode(second, FactoryOperationPolicy.Operation.SIGN))
        assertEquals(FactoryOperationPolicy.Mode.DENY, policy.mode(first, FactoryOperationPolicy.Operation.SIGN))
    }

    @Test fun authorityDialogsAreSecureAndRejectObscuredTouches() {
        observe(first); observe(second)
        val activity = launch()
        for (tag in listOf("default", "app", "SIGN")) {
            button(activity, tag).performClick()
            val dialog = latestDialog()
            assertTrue("Secure authority dialog: $tag", dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue("Obscured authority list: $tag", dialog.listView.filterTouchesWhenObscured)
            dialog.dismiss()
        }
    }

    @Test fun revokeEndsCurrentSelectedAppRequestsWithoutChangingFuturePolicy() {
        observe(first); observe(second)
        val grant = policy.acquire(first, FactoryOperationPolicy.Operation.INSTALL, FactoryOperationPolicy.binding("first active"))
        val other = policy.acquire(second, FactoryOperationPolicy.Operation.INSTALL, FactoryOperationPolicy.binding("second active"))
        val activity = launch()
        button(activity, "revoke").performClick(); drain(activity)
        assertTrue(runCatching { grant.checkBeforeEffect() }.isFailure)
        other.checkBeforeEffect(); other.close()
        policy.acquire(first, FactoryOperationPolicy.Operation.INSTALL, FactoryOperationPolicy.binding("new active")).use { it.checkBeforeEffect() }
        assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, policy.defaultMode())
    }

    @Test fun automationOpenedEditorRemainsTaintedUntilClosedAndReopened() {
        lateinit var activity: TestPermissionsActivity
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) { activity = launch(drain = false) })
        drain(activity)
        assertFalse(button(activity, "default").isEnabled)
        button(activity, "default").performClick()
        assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, policy.defaultMode())
        controller!!.pause().stop().destroy(); controller = null
        assertFalse(MemoryUiAutomationGuard.isProtected())
        activity = launch()
        assertTrue(button(activity, "default").isEnabled)
    }

    @Test fun pauseDismissesDialogAndRejectsStaleDialogChoiceUntilResumed() {
        val activity = launch()
        button(activity, "default").performClick()
        val stale = latestDialog()
        controller!!.pause()
        assertFalse(stale.isShowing)
        choose(stale, 2)
        button(activity, "default").performClick()
        drain(activity)
        assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, policy.defaultMode())
        assertTrue(MemoryUiAutomationGuard.isProtected())
        controller!!.resume()
        button(activity, "default").performClick(); choose(latestDialog(), 1); drain(activity)
        assertEquals(FactoryOperationPolicy.Mode.ASK, policy.defaultMode())
    }

    @Test fun destroyReleasesGuardAndLatePolicyLoadCannotReopenOrEdit() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        TestPermissionsActivity.provider = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); policy }
        val activity = launch(drain = false)
        val loadingWorker = worker(activity)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller!!.pause().stop().destroy(); controller = null
            assertFalse(MemoryUiAutomationGuard.isProtected())
            release.countDown()
            assertTrue(loadingWorker.awaitTermination(10, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(MemoryUiAutomationGuard.isProtected())
            assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, policy.defaultMode())
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { release.countDown() }
    }
}
