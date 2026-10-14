package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
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
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Exercises the production native handlers; only URI permission checks and signed proof are fake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryDocumentActivityTest {
    class GrantedDocumentActivity : FactoryDocumentActivity() {
        override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int) = PackageManager.PERMISSION_GRANTED
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-documents")
    private val proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private val request = FactoryDocumentCoordinator.Request("open", "text/plain", null, "d".repeat(64))
    private lateinit var coordinator: FactoryDocumentCoordinator
    private val controllers = mutableListOf<ActivityController<out FactoryDocumentActivity>>()
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        root.deleteRecursively()
        coordinator = FactoryDocumentCoordinator(context) { caller -> check(caller == proof.appId); proof }
        FactoryDocumentCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
    }
    @After fun cleanup() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        FactoryDocumentCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        for (name in listOf("protectedSurfaces", "taintedSurfaces", "activeActions")) MemoryUiAutomationGuard::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(null, 0)
        FactoryInteractionAdmission::class.java.getDeclaredField("owners").apply { isAccessible = true }.let { (it.get(null) as MutableSet<*>).clear() }
        root.deleteRecursively()
    }
    private fun launch(saved: Bundle? = null): ActivityController<GrantedDocumentActivity> {
        val control = Robolectric.buildActivity(GrantedDocumentActivity::class.java, Intent(context))
        controllers += control
        control.create(saved).start().resume().visible()
        return control
    }
    private fun button(activity: FactoryDocumentActivity, name: String) = activity.window.decorView.findViewWithTag<Button>("factory-documents-$name")
    private fun set(activity: FactoryDocumentActivity, name: String, value: Any?) = FactoryDocumentActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun advance(activity: FactoryDocumentActivity) = FactoryDocumentActivity::class.java.getDeclaredMethod("advance").apply { isAccessible = true }.invoke(activity)
    private fun render(activity: FactoryDocumentActivity) = FactoryDocumentActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
    private fun drain(activity: FactoryDocumentActivity) {
        val executor = FactoryDocumentActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(activity) as ExecutorService
        var accepted = false
        repeat(1000) {
            if (!accepted) {
                try { executor.submit {}.get(5, TimeUnit.SECONDS); accepted = true }
                catch (_: java.util.concurrent.RejectedExecutionException) { Thread.sleep(5) }
            }
        }
        assertTrue("Worker did not become idle", accepted)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun bindSelected(activity: FactoryDocumentActivity): String {
        val owner = coordinator.begin(proof.appId, request)
        coordinator.launch(owner) {}; coordinator.pickerTerminal(owner, true)
        set(activity, "token", owner); set(activity, "request", request)
        set(activity, "selected", Uri.parse("content://synthetic/document/one")); render(activity)
        return owner
    }
    @Test fun exportedUnknownCallerGetsNoAuthorityAndSecureControls() {
        val control = Robolectric.buildActivity(FactoryDocumentActivity::class.java, Intent().putExtra("operation", "open").putExtra("mimeType", "text/plain").putExtra("nonce", request.nonce))
        controllers += control
        shadowOf(control.get()).setCallingPackage("evil.app")
        control.setup(); val activity = control.get(); drain(activity)
        assertTrue(activity.packageManager.getActivityInfo(ComponentName(activity, FactoryDocumentActivity::class.java), 0).exported)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertEquals("idle", coordinator.status())
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun backNeverReturnsSelectedDocument() {
        val activity = launch().get(); bindSelected(activity)
        activity.onBackPressedDispatcher.onBackPressed(); drain(activity)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).resultIntent)
        assertEquals("cancelled", coordinator.status())
    }
    @Test fun closeNeverReturnsSelectedDocument() {
        val activity = launch().get(); bindSelected(activity)
        button(activity,"close").performClick(); drain(activity)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertEquals("cancelled", coordinator.status())
    }
    @Test fun validatedSelectionAutomaticallyReturnsBoundNonceAndMinimalTemporaryReadGrant() {
        val activity = launch().get(); bindSelected(activity)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        advance(activity); drain(activity)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val result = shadowOf(activity).resultIntent
        assertEquals(request.nonce, result.getStringExtra("nonce"))
        assertEquals(setOf("nonce"), result.extras!!.keySet())
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, result.flags)
        assertEquals(result.data, result.clipData!!.getItemAt(0).uri)
        assertTrue(activity.isFinishing)
    }
    @Test fun nullCallerCannotOpenSystemPicker() {
        val control = Robolectric.buildActivity(FactoryDocumentActivity::class.java, Intent())
        controllers += control; control.setup()
        assertTrue(control.get().isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(control.get()).resultCode)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    private fun launchRequest(): ActivityController<GrantedDocumentActivity> {
        val picker = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "example.android.picker"; name = "example.android.picker.Picker"; exported = true; enabled = true
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "example.android.picker"; flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM; enabled = true }
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain"), picker)
        val control = Robolectric.buildActivity(GrantedDocumentActivity::class.java, Intent().putExtra("operation", "open").putExtra("mimeType", request.mimeType).putExtra("nonce", request.nonce))
        controllers += control; shadowOf(control.get()).setCallingPackage(proof.appId)
        control.create().start().resume().visible(); repeat(3) { drain(control.get()) }
        return control
    }
    @Test fun authenticatedRequestStartsSystemPickerWithoutJarvysApproval() {
        val action = MemoryUiAutomationGuard.beginAsyncAction(MemoryUiAutomationGuard.captureAutomationEpoch())
        try {
            val activity = launchRequest().get()
            assertEquals("picker", coordinator.status()); assertNotNull(shadowOf(activity).nextStartedActivityForResult)
            assertFalse(MemoryUiAutomationGuard.isProtected())
            assertNotNull(shadowOf(activity).nextStartedActivityForResult)
        } finally { action.close() }
    }

}
