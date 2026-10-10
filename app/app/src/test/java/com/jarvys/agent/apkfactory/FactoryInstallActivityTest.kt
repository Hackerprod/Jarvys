package com.jarvys.agent.apkfactory

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInstaller
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
@Config(sdk = [34])
class FactoryInstallActivityTest {
    private lateinit var fixture: InstallTestFixture
    private var controller: ActivityController<FactoryInstallActivity>? = null
    @Before fun before() { fixture = InstallTestFixture() }
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
        assertTrue(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun permissionReviewBlocksAutomatedCloseAndSurvivesBackgroundAndManualGrant() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        fixture.backend.allowed = false
        val result = fixture.prepare()
        val activity = launch(result.getString("launch_token"))
        assertEquals("permission_required", fixture.state())
        assertFalse(button(activity, "approve").isEnabled)
        assertFalse(button(activity, "system").isEnabled)
        assertTrue(button(activity, "close").isEnabled)
        assertNull(shadowOf(activity).nextStartedActivity)
        var dispatched = false
        assertFalse(MemoryUiAutomationGuard.runAutomated(epoch) {
            dispatched = true
            button(activity, "close").performClick()
        })
        assertFalse(dispatched)
        assertFalse(activity.isFinishing)
        controller!!.pause().stop()
        assertEquals("permission_required", fixture.state())
        assertTrue(MemoryUiAutomationGuard.isProtected())
        button(activity, "close").performClick()
        assertFalse(activity.isFinishing)
        fixture.backend.allowed = true
        controller!!.restart().start().resume().visible()
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
        assertTrue(MemoryUiAutomationGuard.isProtected())
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
        assertTrue(MemoryUiAutomationGuard.isProtected())
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
        assertTrue(MemoryUiAutomationGuard.isProtected())
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

    @Test fun screenEnteredDuringAutomatedActionRemainsTaintedAfterActionEnds() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        lateinit var activity: FactoryInstallActivity
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) { activity = launch() })
        for (tag in listOf("approve", "system", "cancel")) assertFalse(button(activity, tag).isEnabled)
        assertTrue(button(activity, "close").isEnabled)
        button(activity, "close").performClick()
        assertTrue(activity.isFinishing)
        assertEquals(0, fixture.backend.creates)
    }

    @Test fun closeBeforeCancellationCannotDismissOutstandingInteraction() {
        fixture.prepare(); val activity = launch()
        button(activity, "close").performClick()
        assertFalse(activity.isFinishing)
        assertEquals("awaiting_user", fixture.state())
        assertTrue(MemoryUiAutomationGuard.isProtected())
        button(activity, "cancel").performClick()
        assertEquals("cancelled_before_commit", fixture.state())
        button(activity, "close").performClick()
        assertTrue(activity.isFinishing)
        assertFalse(fixture.coordinator.status().getBoolean("automation_protected"))
        assertEquals(0, fixture.backend.commits)
    }

    @Test fun pendingCallbackDoesNotLaunchSystemUiUntilNativeButton() {
        fixture.seed("committing", 17)
        val activity = launch()
        val confirmation = Intent("org.example.synthetic.INSTALL_CONFIRMATION")
        fixture.coordinator.callback(fixture.nonce(), 17, PackageInstaller.STATUS_PENDING_USER_ACTION, confirmation)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(button(activity, "system").isEnabled)
        button(activity, "system").performClick()
        assertEquals(confirmation.action, shadowOf(activity).nextStartedActivity.action)
        assertEquals("system_ui_open", fixture.state())
        assertFalse(button(activity, "system").isEnabled)
        assertTrue(MemoryUiAutomationGuard.isProtected())
        assertEquals(0, fixture.backend.commits)
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
