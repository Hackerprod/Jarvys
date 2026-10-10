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

/** Production photo native handlers with synthetic proof, bytes, and grants; isolated Application. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryPhotoActivityTest {
    class GrantedPhotoActivity : FactoryPhotoActivity() {
        override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int) = PackageManager.PERMISSION_GRANTED
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-photos")
    private val proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private val request = FactoryPhotoCoordinator.Request("pick", "d".repeat(64))
    private lateinit var coordinator: FactoryPhotoCoordinator
    private val controllers = mutableListOf<ActivityController<out FactoryPhotoActivity>>()
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        root.deleteRecursively()
        coordinator = FactoryPhotoCoordinator(context) { caller -> check(caller == proof.appId); proof }
        FactoryPhotoCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
    }
    @After fun cleanup() {
        controllers.forEach { runCatching { drain(it.get()); it.pause().stop().destroy() } }
        FactoryPhotoCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        for (name in listOf("protectedSurfaces", "taintedSurfaces", "activeActions")) MemoryUiAutomationGuard::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(null, 0)
        FactoryInteractionAdmission::class.java.getDeclaredField("owners").apply { isAccessible = true }.let { (it.get(null) as MutableSet<*>).clear() }
        root.deleteRecursively()
    }
    private fun launch(saved: Bundle? = null): ActivityController<GrantedPhotoActivity> {
        val control = Robolectric.buildActivity(GrantedPhotoActivity::class.java, FactoryPhotoCoordinator.recoveryIntent(context))
        controllers += control
        control.create(saved).start().resume().visible()
        return control
    }
    private fun button(activity: FactoryPhotoActivity, name: String) = activity.window.decorView.findViewWithTag<Button>("factory-photos-$name")
    private fun set(activity: FactoryPhotoActivity, name: String, value: Any?) = FactoryPhotoActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun render(activity: FactoryPhotoActivity) = FactoryPhotoActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
    private fun drain(activity: FactoryPhotoActivity) {
        val executor = FactoryPhotoActivity::class.java.getDeclaredField("WORKER").apply { isAccessible = true }.get(null) as ExecutorService
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
    private fun bindSelected(activity: FactoryPhotoActivity): String {
        val owner = coordinator.begin(proof.appId, request)
        coordinator.launch(owner) {}; coordinator.pickerTerminal(owner, true)
        set(activity, "token", owner); set(activity, "request", request)
        set(activity, "selected", FactoryPhotoCapture.Image(byteArrayOf(1, 2, 3), "image/png", 2, 3));
        set(activity, "admission", com.jarvys.factory.runtime.FileShareTransfer.reserve()); render(activity)
        return owner
    }
    @Test fun exportedUnknownCallerGetsNoAuthorityAndSecureControls() {
        val control = Robolectric.buildActivity(FactoryPhotoActivity::class.java, Intent().putExtra("operation", "pick").putExtra("nonce", request.nonce))
        controllers += control
        shadowOf(control.get()).setCallingPackage("evil.app")
        control.setup(); val activity = control.get(); drain(activity)
        assertTrue(activity.packageManager.getActivityInfo(ComponentName(activity, FactoryPhotoActivity::class.java), 0).exported)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertFalse(button(activity,"choose").isEnabled); assertFalse(button(activity,"use").isEnabled)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertEquals("idle", coordinator.status())
        for (name in listOf("choose","use","close","recover")) assertTrue(button(activity,name).filterTouchesWhenObscured)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun backNeverReturnsSelectedPhoto() {
        val activity = launch().get(); bindSelected(activity)
        activity.onBackPressedDispatcher.onBackPressed(); drain(activity)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).resultIntent)
        assertEquals("cancelled", coordinator.status())
    }
    @Test fun closeNeverReturnsSelectedPhoto() {
        val activity = launch().get(); bindSelected(activity)
        button(activity,"close").performClick(); drain(activity)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertEquals("cancelled", coordinator.status())
    }
    @Test fun explicitUseAloneReturnsBoundNonceAndNativeBinderWithoutUriOrRawBytes() {
        val info = android.content.pm.PackageInfo().apply {
            packageName = proof.appId
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = proof.appId; uid = 20002 }
        }
        shadowOf(context.packageManager).installPackage(info)
        shadowOf(context.packageManager).setPackagesForUid(20002, proof.appId)
        val activity = launch().get(); bindSelected(activity)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        button(activity,"use").performClick(); drain(activity)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val result = shadowOf(activity).resultIntent
        assertEquals(request.nonce, result.getStringExtra("nonce"))
        assertEquals(setOf("nonce", "transfer", "size", "sha256", "mimeType", "width", "height"), result.extras!!.keySet())
        assertEquals(0, result.flags); assertNull(result.data); assertNull(result.clipData)
        assertEquals(3, result.getIntExtra("size", -1)); assertEquals("image/png", result.getStringExtra("mimeType"))
        assertEquals(2, result.getIntExtra("width", -1)); assertEquals(3, result.getIntExtra("height", -1))
        val binder = result.extras!!.getBinder("transfer")
        assertTrue(binder is com.jarvys.factory.runtime.FileShareTransfer)
        (binder as com.jarvys.factory.runtime.FileShareTransfer).close()
        assertTrue(activity.isFinishing)
    }
    @Test fun backgroundRevokesSelectionAndLateCallbackCannotRestoreIt() {
        val control = launch(); val activity = control.get(); bindSelected(activity)
        control.pause()
        assertTrue(coordinator.needsRecovery())
        shadowOf(activity).callOnActivityResult(6901, Activity.RESULT_OK, Intent().setData(Uri.parse("content://synthetic/photo/late")).addFlags(1))
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
        assertNull(next.window.decorView.findViewWithTag<Button>("factory-photos-use"))
        assertTrue(runCatching { coordinator.pickerTerminal(owner,true) }.isFailure)
        assertNull(shadowOf(next).nextStartedActivity)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(next).resultCode)
    }
    @Test fun taintedScreenCannotApproveEvenAfterAutomationEnds() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        lateinit var activity: FactoryPhotoActivity
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
            val control = Robolectric.buildActivity(FactoryPhotoActivity::class.java,intent)
            controllers += control; control.setup()
            assertTrue(control.get().isFinishing)
            assertEquals(Activity.RESULT_CANCELED,shadowOf(control.get()).resultCode)
            assertFalse(MemoryUiAutomationGuard.isProtected())
        }
    }
    @Test fun nativeRecoveryTokenIsOneShot() {
        val intent = FactoryPhotoCoordinator.recoveryIntent(context)
        assertTrue(FactoryPhotoCoordinator.consumeRecoveryToken(intent))
        assertFalse(FactoryPhotoCoordinator.consumeRecoveryToken(intent))
        assertFalse(FactoryPhotoCoordinator.consumeRecoveryToken(Intent().putExtra("nativeRecoveryToken", "forged")))
    }

    @Test fun successCallbackWithoutOwnedExternalLaunchNeverSelectsOrReturns() {
        val activity = launch().get()
        shadowOf(activity).callOnActivityResult(6901, Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://synthetic/photo/late")).addFlags(1))
        assertFalse(button(activity, "use").isEnabled)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertEquals("idle", coordinator.status())
    }
    @Test fun externalCancellationRequiresNativeCloseAndCannotBeTurnedIntoSuccess() {
        val activity = launch().get(); val owner = coordinator.begin(proof.appId, request)
        coordinator.launch(owner) {}
        set(activity, "token", owner); set(activity, "request", request); set(activity, "externalOwned", true)
        shadowOf(activity).callOnActivityResult(6901, Activity.RESULT_CANCELED, null)
        assertEquals("cancelled", coordinator.status()); assertFalse(button(activity, "use").isEnabled)
        assertTrue(MemoryUiAutomationGuard.isProtected())
        button(activity, "close").performClick()
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertTrue(activity.isFinishing)
    }
    @Test fun nativeRecoveryTokenRejectsInjectedUriAndGrantEnvelope() {
        val original = FactoryPhotoCoordinator.recoveryIntent(context)
        for (bad in listOf(Intent(original).setData(Uri.parse("content://synthetic/x")),
            Intent(original).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            Intent(original).apply { clipData = android.content.ClipData.newRawUri("x", Uri.parse("content://synthetic/x")) },
            Intent(original).apply { selector = Intent("nested") })) {
            assertFalse(FactoryPhotoCoordinator.consumeRecoveryToken(bad))
        }
        assertTrue(FactoryPhotoCoordinator.consumeRecoveryToken(original))
    }

}
