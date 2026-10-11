package com.jarvys.agent.apkfactory

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Looper
import android.view.View
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import android.view.WindowManager
import android.widget.Button
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

/** Native host-only UI tests; all installation state uses an injected fake backend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryInstallActivityTest {
    private lateinit var fixture: InstallTestFixture
    private var controller: ActivityController<FactoryInstallActivity>? = null
    @Before fun before() { FactoryStartupTestIsolation.releaseCompletedSharingStartup(); fixture = InstallTestFixture() }
    @After fun after() { controller?.pause()?.stop()?.destroy(); fixture.close() }
    private fun launch(token: String? = null): FactoryInstallActivity {
        val intent = Intent().apply { token?.let { putExtra("launch_token", it) } }
        controller = Robolectric.buildActivity(FactoryInstallActivity::class.java, intent).setup()
        return controller!!.get()
    }
    private fun button(activity: FactoryInstallActivity, tag: String) = activity.window.decorView.findViewWithTag<Button>("factory-install-$tag")

    @Test fun secureNonExportedRecoveryScreenNeverCreatesInstallAuthority() {
        val activity = launch("forged")
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertFalse(activity.packageManager.getActivityInfo(ComponentName(activity, FactoryInstallActivity::class.java), 0).exported)
        assertFalse(button(activity, "approve").isEnabled)
        assertFalse(button(activity, "system").isEnabled)
        assertFalse(button(activity, "cancel").isEnabled)
        assertTrue(button(activity, "close").isEnabled)
        for (tag in listOf("approve", "system", "cancel", "close")) assertTrue(button(activity, tag).filterTouchesWhenObscured)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, fixture.backend.creates)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun permissionStatusSurvivesBackgroundAndManualGrantWithoutGrantingAuthority() {
        fixture.backend.allowed = false
        val result = fixture.prepare()
        val activity = launch(result.getString("launch_token"))
        assertEquals("permission_required", fixture.state())
        assertFalse(button(activity, "approve").isEnabled)
        assertFalse(button(activity, "system").isEnabled)
        assertTrue(button(activity, "close").isEnabled)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(MemoryUiAutomationGuard.isProtected())
        controller!!.pause().stop()
        button(activity, "close").performClick()
        assertFalse(activity.isFinishing)
        fixture.backend.allowed = true
        controller!!.restart().start().resume().visible()
        drain(activity)
        assertEquals("permission_required", fixture.state())
        assertFalse(button(activity, "approve").isEnabled)
        assertFalse(button(activity, "system").isEnabled)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
        button(activity, "close").performClick()
        assertTrue(activity.isFinishing)
        assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
    }

    @Test fun permissionQueryFailureScreenCannotApproveOrLaunchSettings() {
        fixture.backend.availabilityFailure = true
        val result = fixture.prepare()
        val activity = launch(result.getString("launch_token"))
        assertEquals("permission_unavailable", fixture.state())
        assertFalse(button(activity, "approve").isEnabled)
        assertFalse(button(activity, "system").isEnabled)
        assertTrue(button(activity, "close").isEnabled)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(MemoryUiAutomationGuard.isProtected())
        button(activity, "close").performClick()
        assertTrue(activity.isFinishing)
        assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
        assertEquals(0, fixture.backend.creates)
    }

    @Test fun unsupportedInstallerScreenCannotApproveOrLaunchSettings() {
        fixture.backend.availabilityOverride = FactoryInstallCoordinator.Availability.UNSUPPORTED
        val result = fixture.prepare()
        val activity = launch(result.getString("launch_token"))
        assertEquals("installation_unavailable", fixture.state())
        assertFalse(button(activity, "approve").isEnabled)
        assertFalse(button(activity, "system").isEnabled)
        assertTrue(button(activity, "close").isEnabled)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(MemoryUiAutomationGuard.isProtected())
        button(activity, "close").performClick()
        assertTrue(activity.isFinishing)
        assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
        assertEquals(0, fixture.backend.creates)
    }

    @Test fun damagedRecoveryButtonRequiresForegroundAndDoesNotLaunchExternalUi() {
        fixture.journal.parentFile!!.mkdirs(); fixture.journal.writeText("{broken")
        fixture.coordinator.restore()
        val activity = launch()
        assertTrue(button(activity, "recover").isEnabled)
        assertFalse(button(activity, "approve").isEnabled)
        assertFalse(button(activity, "system").isEnabled)
        assertTrue(button(activity, "recover").filterTouchesWhenObscured)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller!!.pause()
        button(activity, "recover").performClick()
        assertEquals(0, fixture.backend.ownedQueries)
        assertEquals(0, fixture.backend.abandons)
        assertEquals("journal_unavailable", fixture.state())
        assertFalse(MemoryUiAutomationGuard.isProtected())
        controller!!.resume()
    }

    @Test fun openingReviewDoesNotCommitAndPausingRevokesApproval() {
        val result = fixture.prepare()
        val activity = launch(result.getString("launch_token"))
        assertTrue(button(activity, "approve").isEnabled)
        assertFalse(button(activity, "system").isEnabled)
        assertEquals(0, fixture.backend.creates)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller!!.pause()
        assertEquals("revoked", fixture.state())
        controller!!.resume()
        assertFalse(button(activity, "approve").isEnabled)
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun automatedDispatchCannotPressNativeControlsOrObserveProtectedScreen() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        fixture.prepare(); val activity = launch()
        var executed = false
        assertFalse(MemoryUiAutomationGuard.runAutomated(epoch) { executed = true; button(activity, "approve").performClick() })
        assertFalse(executed)
        assertTrue(runCatching { MemoryUiAutomationGuard.captureAutomationEpoch() }.isFailure)
        assertEquals("awaiting_user", fixture.state())
        assertEquals(0, fixture.backend.creates)
    }

    @Test fun unboundStatusOpenedDuringAutomationCannotCreateInstallAuthority() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        lateinit var activity: FactoryInstallActivity
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) { activity = launch() })
        for (tag in listOf("approve", "system", "cancel")) assertFalse(button(activity, tag).isEnabled)
        assertTrue(button(activity, "close").isEnabled)
        button(activity, "close").performClick()
        assertTrue(activity.isFinishing)
        assertEquals(0, fixture.backend.creates)
    }

    @Test fun closeCancelsPendingAskAndDismissesWithoutCreatingSession() {
        fixture.prepare(); val activity = launch()
        assertTrue(MemoryUiAutomationGuard.isProtected())
        button(activity, "close").performClick()
        assertTrue(activity.isFinishing)
        assertEquals("cancelled_before_commit", fixture.state())
        assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun backCancelsPendingAskAndDismissesWithoutCreatingSession() {
        fixture.prepare(); val activity = launch()
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        assertFalse(fixture.record().getBoolean("interactionOpen"))
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.creates)
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun historicalPendingCallbackCannotLaunchSystemUiWithoutLiveAuthority() {
        fixture.seed("committing", 17)
        val activity = launch()
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_PENDING_USER_ACTION,
            Intent("org.example.synthetic.INSTALL_CONFIRMATION"))
        drain(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
        button(activity, "system").performClick()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("pending_system", fixture.state())
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun defaultAllowInstallsAutomaticallyWithoutHostApprovalCard() {
        fixture.close(); fixture = InstallTestFixture(policy = FactoryOperationPolicy.inMemory())
        fixture.signed(); val prepared = fixture.prepare()
        val activity = launch(prepared.getString("launch_token"))
        assertEquals(View.GONE, button(activity, "approve").visibility)
        drain(activity)
        assertEquals("committing", fixture.state())
        assertEquals(1, fixture.backend.creates)
        assertEquals(1, fixture.backend.commits)
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun allowAutomaticallyOpensActualAndroidConfirmationWithGuard() {
        verifyAutomaticSystemConfirmation(FactoryOperationPolicy.Mode.ALLOW_ALWAYS)
    }

    @Test fun askNeedsOneApprovalThenAutomaticallyOpensActualAndroidConfirmationWithGuard() {
        verifyAutomaticSystemConfirmation(FactoryOperationPolicy.Mode.ASK)
    }

    private fun verifyAutomaticSystemConfirmation(mode: FactoryOperationPolicy.Mode) {
        fixture.close(); fixture = InstallTestFixture(policy = FactoryOperationPolicy.inMemory(mode))
        fixture.signed(); val prepared = fixture.prepare()
        val activity = launch(prepared.getString("launch_token"))
        if (mode == FactoryOperationPolicy.Mode.ASK) {
            drain(activity)
            assertEquals(0, fixture.backend.creates)
            assertTrue(button(activity, "approve").isEnabled)
            assertTrue(MemoryUiAutomationGuard.isProtected())
            button(activity, "approve").performClick()
        }
        drain(activity)
        assertEquals("committing", fixture.state())
        assertFalse(fixture.coordinator.approvalRequired())
        assertEquals(View.GONE, button(activity, "approve").visibility)
        assertFalse(MemoryUiAutomationGuard.isProtected())
        val confirmation = Intent("org.example.synthetic.INSTALL_CONFIRMATION")
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_PENDING_USER_ACTION, confirmation)
        drain(activity)
        assertEquals(confirmation.action, shadowOf(activity).nextStartedActivity.action)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("system_ui_open", fixture.state())
        assertTrue(MemoryUiAutomationGuard.isProtected())
        controller!!.pause().resume()
        assertEquals("system_ui_open", fixture.state())
        assertTrue(MemoryUiAutomationGuard.isProtected())
        assertEquals(1, fixture.backend.commits)
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_SUCCESS, null)
        drain(activity)
        assertEquals("succeeded", fixture.state())
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun closeAndBackPreserveCommittedPendingSessionWithoutReplay() {
        fixture.close(); fixture = InstallTestFixture(policy = FactoryOperationPolicy.inMemory())
        fixture.signed(); fixture.prepare()
        val activity = launch(); drain(activity)
        button(activity, "close").performClick()
        assertFalse(activity.isFinishing)
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(activity.isFinishing)
        assertEquals("committing", fixture.state())
        assertTrue(fixture.record().getBoolean("interactionOpen"))
        assertEquals(1, fixture.backend.commits)
        assertEquals(0, fixture.backend.abandons)
    }

    private fun drain(activity: FactoryInstallActivity) {
        val worker = FactoryInstallActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(activity) as ExecutorService
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            worker.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun restartedPendingSessionCannotRelaunchSystemConfirmationOrApprove() {
        fixture.seed("pending_system", 17); fixture.restart()
        val activity = launch("old-token")
        assertEquals("outcome_unknown", fixture.state())
        assertFalse(button(activity, "approve").isEnabled)
        assertFalse(button(activity, "system").isEnabled)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, fixture.backend.commits)
    }
}
