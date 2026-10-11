package com.jarvys.agent.apkfactory

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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

    @Test
    @Config(sdk = [29, 34])
    fun fullyAndPartiallyObscuredButtonsCannotOpenAuthorityDialogsOrRevoke() {
        observe(first); observe(second)
        val grant = policy.acquire(first, FactoryOperationPolicy.Operation.INSTALL, FactoryOperationPolicy.binding("obscured buttons"))
        val activity = launch()
        for (tag in listOf("default", "app", "SIGN", "INSTALL", "revoke")) {
            val control = button(activity, tag)
            control.layout(0, 0, 600, 120)
            for (flags in obscurationFlags) {
                val touch = event(MotionEvent.ACTION_DOWN, flags, 20f, 20f)
                try { assertFalse("Blocked $tag flags=$flags", control.onFilterTouchEventForSecurity(touch)) } finally { touch.recycle() }
                gesture(control, 20f, 20f, flags)
                drain(activity)
                assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
                grant.checkBeforeEffect()
                assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, policy.defaultMode())
                assertNull(policy.overrideMode(first, FactoryOperationPolicy.Operation.SIGN))
            }
            for (flags in obscurationFlags) {
                gesture(control, 20f, 20f, midGestureFlags = flags)
                drain(activity)
                assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
                grant.checkBeforeEffect()
            }
        }
        val revoke = button(activity, "revoke")
        val clean = event(MotionEvent.ACTION_DOWN, 0, 20f, 20f)
        try { assertTrue(revoke.onFilterTouchEventForSecurity(clean)) } finally { clean.recycle() }
        gesture(revoke, 20f, 20f)
        drain(activity)
        assertTrue("A clean touch still revokes", runCatching { grant.checkBeforeEffect() }.isFailure)
    }

    @Test
    @Config(sdk = [29, 34])
    fun defaultModeListRejectsFullPartialAndMidGestureOverlayButAcceptsCleanTouch() {
        val activity = launch()
        button(activity, "default").performClick()
        val dialog = latestDialog()
        for (flags in obscurationFlags) {
            touchRow(dialog, 2, flags)
            drain(activity)
            assertTrue(dialog.isShowing)
            assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, policy.defaultMode())
            assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, FactoryOperationPolicy.persisted(context).defaultMode())
        }
        for (flags in obscurationFlags) {
            touchRow(dialog, 2, midGestureFlags = flags)
            drain(activity)
            assertTrue(dialog.isShowing)
            assertEquals(FactoryOperationPolicy.Mode.ALLOW_ALWAYS, policy.defaultMode())
        }
        touchRow(dialog, 2)
        drain(activity)
        assertFalse(dialog.isShowing)
        assertEquals(FactoryOperationPolicy.Mode.DENY, FactoryOperationPolicy.persisted(context).defaultMode())
    }

    @Test
    @Config(sdk = [29, 34])
    fun appAndOverrideListsRejectOverlayTouchesAndCleanTouchEditsOnlySelectedCertificate() {
        observe(first); observe(second)
        val activity = launch()
        button(activity, "app").performClick()
        val apps = latestDialog()
        for (flags in obscurationFlags) {
            touchRow(apps, 1, flags)
            drain(activity)
            assertTrue(apps.isShowing)
            assertTrue(button(activity, "app").text.contains("aaaaaaaaaaaaaaaa"))
        }
        for (flags in obscurationFlags) {
            touchRow(apps, 1, midGestureFlags = flags)
            drain(activity)
            assertTrue(apps.isShowing)
            assertTrue(button(activity, "app").text.contains("aaaaaaaaaaaaaaaa"))
        }
        touchRow(apps, 1)
        drain(activity)
        assertFalse(apps.isShowing)
        assertTrue(button(activity, "app").text.contains("bbbbbbbbbbbbbbbb"))
        button(activity, "SIGN").performClick()
        val modes = latestDialog()
        for (flags in obscurationFlags) {
            touchRow(modes, 3, flags)
            drain(activity)
            assertTrue(modes.isShowing)
            assertNull(policy.overrideMode(first, FactoryOperationPolicy.Operation.SIGN))
            assertNull(policy.overrideMode(second, FactoryOperationPolicy.Operation.SIGN))
        }
        for (flags in obscurationFlags) {
            touchRow(modes, 3, midGestureFlags = flags)
            drain(activity)
            assertNull(policy.overrideMode(second, FactoryOperationPolicy.Operation.SIGN))
        }
        touchRow(modes, 3)
        drain(activity)
        val persisted = FactoryOperationPolicy.persisted(context)
        assertNull(persisted.overrideMode(first, FactoryOperationPolicy.Operation.SIGN))
        assertEquals(FactoryOperationPolicy.Mode.DENY, persisted.overrideMode(second, FactoryOperationPolicy.Operation.SIGN))
    }

    private val obscurationFlags = listOf(MotionEvent.FLAG_WINDOW_IS_OBSCURED,
        MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED,
        MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
    private fun event(action: Int, flags: Int, x: Float, y: Float): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
            arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
    }
    private fun gesture(view: View, x: Float, y: Float, flags: Int = 0, midGestureFlags: Int? = null,
                        dispatch: (MotionEvent) -> Boolean = view::dispatchTouchEvent) {
        val touches = if (midGestureFlags != null) listOf(MotionEvent.ACTION_DOWN to 0,
            MotionEvent.ACTION_MOVE to midGestureFlags, MotionEvent.ACTION_UP to 0)
            else listOf(MotionEvent.ACTION_DOWN to flags, MotionEvent.ACTION_UP to flags)
        for ((action, obscuration) in touches) {
            val touch = event(action, obscuration, x, y)
            try { dispatch(touch) } finally { touch.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
    }
    private fun touchRow(dialog: AlertDialog, position: Int, flags: Int = 0, midGestureFlags: Int? = null) {
        val window = dialog.window!!
        val decor = window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 720, 720)
        val list = dialog.listView
        val row = checkNotNull(list.getChildAt(position - list.firstVisiblePosition)) { "Dialog row $position must be laid out" }
        val location = IntArray(2).also(list::getLocationInWindow)
        gesture(list, location[0] + (row.left + row.right) / 2f, location[1] + (row.top + row.bottom) / 2f,
            flags, midGestureFlags, checkNotNull(window.callback)::dispatchTouchEvent)
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
