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
        val control = Robolectric.buildActivity(GrantedDocumentActivity::class.java, FactoryDocumentCoordinator.recoveryIntent(context))
        controllers += control
        control.create(saved).start().resume().visible()
        return control
    }
    private fun button(activity: FactoryDocumentActivity, name: String) = activity.window.decorView.findViewWithTag<Button>("factory-documents-$name")
    private fun set(activity: FactoryDocumentActivity, name: String, value: Any?) = FactoryDocumentActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
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
        assertFalse(button(activity,"choose").isEnabled); assertFalse(button(activity,"use").isEnabled)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertEquals("idle", coordinator.status())
        for (name in listOf("choose","use","close","recover")) assertTrue(button(activity,name).filterTouchesWhenObscured)
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
    @Test fun explicitUseAloneReturnsBoundNonceAndMinimalTemporaryReadGrant() {
        val activity = launch().get(); bindSelected(activity)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        button(activity,"use").performClick(); drain(activity)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val result = shadowOf(activity).resultIntent
        assertEquals(request.nonce, result.getStringExtra("nonce"))
        assertEquals(setOf("nonce"), result.extras!!.keySet())
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, result.flags)
        assertEquals(result.data, result.clipData!!.getItemAt(0).uri)
        assertTrue(activity.isFinishing)
    }
    @Test fun backgroundRevokesSelectionAndLateCallbackCannotRestoreIt() {
        val control = launch(); val activity = control.get(); bindSelected(activity)
        control.pause()
        assertTrue(coordinator.needsRecovery())
        shadowOf(activity).callOnActivityResult(6701, Activity.RESULT_OK, Intent().setData(Uri.parse("content://synthetic/document/late")).addFlags(1))
        control.resume(); button(activity,"use").performClick()
        assertFalse(button(activity,"use").isEnabled)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }
    @Test fun recreationNeverReplaysOldPickerOrRecoversSelection() {
        val control = launch(); val activity = control.get(); val owner = bindSelected(activity)
        control.pause().stop().destroy(); controllers.remove(control)
        val next = launch(Bundle()).get()
        assertTrue(coordinator.needsRecovery())
        assertTrue(next.isFinishing)
        assertNull(next.window.decorView.findViewWithTag<Button>("factory-documents-use"))
        assertTrue(runCatching { coordinator.pickerTerminal(owner,true) }.isFailure)
        assertNull(shadowOf(next).nextStartedActivity)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(next).resultCode)
    }
    @Test fun taintedScreenCannotApproveEvenAfterAutomationEnds() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        lateinit var activity: FactoryDocumentActivity
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) { activity = launch().get() })
        for (name in listOf("choose","use","recover")) {
            assertFalse(button(activity,name).isEnabled); button(activity,name).performClick()
        }
        assertEquals("idle", coordinator.status())
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun recoveryRequiresHumanForegroundAndDoesNotRestoreGrants() {
        val owner = coordinator.begin(proof.appId,request); coordinator.launch(owner) {}; coordinator.revoke(owner)
        val control = launch(); val activity = control.get()
        assertTrue(button(activity,"recover").isEnabled)
        control.pause(); button(activity,"recover").performClick()
        assertTrue(coordinator.needsRecovery())
        control.resume(); button(activity,"recover").performClick()
        assertEquals("closed_outcome_unknown",coordinator.status())
        assertEquals(Activity.RESULT_CANCELED,shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).resultIntent)
    }
    @Test fun nullCallerForgedRecoveryAndMalformedRequestFinishWithoutProtection() {
        for (intent in listOf(Intent(), Intent().putExtra("nativeRecoveryToken", "forged"))) {
            val control = Robolectric.buildActivity(FactoryDocumentActivity::class.java,intent)
            controllers += control; control.setup()
            assertTrue(control.get().isFinishing)
            assertEquals(Activity.RESULT_CANCELED,shadowOf(control.get()).resultCode)
            assertFalse(MemoryUiAutomationGuard.isProtected())
        }
    }
    @Test fun nativeRecoveryTokenIsOneShot() {
        val intent = FactoryDocumentCoordinator.recoveryIntent(context)
        assertTrue(FactoryDocumentCoordinator.consumeRecoveryToken(intent))
        assertFalse(FactoryDocumentCoordinator.consumeRecoveryToken(intent))
        assertFalse(FactoryDocumentCoordinator.consumeRecoveryToken(Intent().putExtra("nativeRecoveryToken", "forged")))
    }

}
