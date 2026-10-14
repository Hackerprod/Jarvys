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


    @Test fun closeAndBackPreserveCommittedPendingSessionWithoutReplay() {
        fixture.close(); fixture = InstallTestFixture()
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
}
