package com.jarvys.agent.apkfactory

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.coding.FactoryPreviewRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Native controls/lifecycle only. The fake handle does not render WebView or execute JavaScript. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FactoryPreviewActivityTest {
    private var controller: ActivityController<TestActivity>? = null
    private var token = CancellationToken.cancellable()
    class TestActivity : FactoryPreviewActivity() {
        override fun createRuntime(content: FrameLayout, active: FactoryPreviewRegistry.Session): RuntimeHandle {
            if (cancelDuringCreate) active.revoke()
            return object : RuntimeHandle {
            override fun start(): Boolean { starts++; return succeeds }
            override fun reset(): Boolean { resets++; return succeeds }
            override fun close() { closes++ }
        }
        }
        companion object { var cancelDuringCreate = false; var starts = 0; var resets = 0; var closes = 0; var succeeds = true }
    }
    @Before fun before() { TestActivity.cancelDuringCreate = false; TestActivity.succeeds = true; TestActivity.starts = 0; TestActivity.resets = 0; TestActivity.closes = 0; token = CancellationToken.cancellable() }
    @After fun after() { controller?.pause()?.stop()?.destroy(); token.cancel() }
    private fun snapshot() = FactoryPreviewRegistry.Snapshot(JSONObject()
        .put("project_id", "fixture").put("scope_version", 1).put("apk_sha256", "a".repeat(64))
        .put("app_id", "com.example.preview").put("version_code", 1),
        "{}".toByteArray(), mapOf("www/index.html" to "<html></html>".toByteArray()))
    private fun launch(key: String? = null, state: Bundle? = null): TestActivity {
        val intent = Intent().apply { key?.let { putExtra(FactoryPreviewActivity.EXTRA_TOKEN, it) } }
        controller = Robolectric.buildActivity(TestActivity::class.java, intent).create(state).start().resume().visible()
        return controller!!.get()
    }
    private fun issue(validate: () -> Unit = {}) = FactoryPreviewRegistry.issue(snapshot(), validate, token)
    private fun button(activity: TestActivity, name: String) = activity.window.decorView.findViewWithTag<Button>(name)
    @Test fun revokedDuringConstructionClosesLateHandleWithoutStarting() {
        TestActivity.cancelDuringCreate = true
        val activity = launch(issue())
        assertEquals(0, TestActivity.starts)
        assertEquals(1, TestActivity.closes)
        assertFalse(button(activity, "factory-preview-reset").isEnabled)
    }
    @Test fun runtimeStartupFailureDoesNotEnableReset() {
        TestActivity.succeeds = false
        val activity = launch(issue())
        assertEquals(1, TestActivity.starts)
        assertFalse(button(activity, "factory-preview-reset").isEnabled)
        assertTrue(button(activity, "factory-preview-close").isEnabled)
    }
    @Test fun missingTokenFailsClosedWithNoRuntime() {
        val activity = launch()
        assertEquals(0, TestActivity.starts)
        assertFalse(button(activity, "factory-preview-reset").isEnabled)
        assertTrue(button(activity, "factory-preview-close").isEnabled)
    }
    @Test fun privateActivityKeepsNativeDisclosureAndControls() {
        val activity = launch(issue())
        assertEquals(1, TestActivity.starts)
        assertFalse(activity.packageManager.getActivityInfo(ComponentName(activity, FactoryPreviewActivity::class.java), 0).exported)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val text = activity.window.decorView.findViewWithTag<TextView>("factory-preview-status").text.toString()
        assertTrue(text.contains("com.example.preview"))
        assertTrue(text.contains("simulated"))
        assertTrue(text.contains("not as an installed app"))
        assertFalse(activity.intent.hasExtra(FactoryPreviewActivity.EXTRA_TOKEN))
    }
    @Test fun repeatedResetUsesSameRuntimeAndCloseStopsItOnce() {
        val activity = launch(issue())
        repeat(2) { button(activity, "factory-preview-reset").performClick() }
        assertEquals(2, TestActivity.resets)
        button(activity, "factory-preview-close").performClick()
        assertEquals(1, TestActivity.closes)
        assertTrue(activity.isFinishing)
    }
    @Test fun cancellationClosesAndDisablesReset() {
        val activity = launch(issue())
        token.cancel()
        assertEquals(1, TestActivity.closes)
        assertFalse(button(activity, "factory-preview-reset").isEnabled)
    }
    @Test fun invalidatedScopeBeforeResetCannotRestartRuntime() {
        var valid = true
        val activity = launch(issue { check(valid) })
        valid = false
        button(activity, "factory-preview-reset").performClick()
        assertEquals(0, TestActivity.resets)
        assertEquals(1, TestActivity.closes)
    }
    @Test fun pauseWithoutStopClosesPreviewAndNeverResumesAuthority() {
        val activity=launch(issue())
        controller!!.pause()
        assertEquals(1,TestActivity.closes)
        assertFalse(button(activity,"factory-preview-reset").isEnabled)
        controller!!.resume().visible()
        assertEquals(1,TestActivity.starts)
        assertEquals(1,TestActivity.closes)
        assertFalse(button(activity,"factory-preview-reset").isEnabled)
    }
    @Test fun backgroundAndReturnNeverSilentlyResumePreview() {
        val activity = launch(issue())
        controller!!.pause().stop().restart().start().resume().visible()
        assertEquals(1, TestActivity.starts)
        assertEquals(1, TestActivity.closes)
        assertFalse(button(activity, "factory-preview-reset").isEnabled)
    }
    @Test fun savedStateCannotReplayLaunchToken() {
        launch(issue(), Bundle())
        assertEquals(0, TestActivity.starts)
    }
    @Test fun revokedLaunchCannotCreateRuntime() {
        val key = issue(); token.cancel(); launch(key)
        assertEquals(0, TestActivity.starts)
    }
    @Test @Config(sdk = [24, 26, 29]) fun missingTokenIsSafeOnSupportedAndroidApis() {
        launch()
        assertEquals(0, TestActivity.starts)
    }
}
