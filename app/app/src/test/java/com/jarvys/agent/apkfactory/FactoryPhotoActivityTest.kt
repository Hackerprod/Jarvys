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
@Config(sdk = [34], application = android.app.Application::class, shadows = [FactoryPhotoCaptureTest.PipeDescriptor::class, FactoryPhotoCaptureTest.PipeOs::class])
class FactoryPhotoActivityTest {
    class GrantedPhotoActivity : FactoryPhotoActivity() {
        override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int) = PackageManager.PERMISSION_GRANTED
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-photos")
    private val proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private val request = FactoryPhotoCoordinator.Request("pick", "d".repeat(64))
    private val policy = FactoryOperationPolicy.inMemory()
    private lateinit var coordinator: FactoryPhotoCoordinator
    private val controllers = mutableListOf<ActivityController<out FactoryPhotoActivity>>()
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        root.deleteRecursively()
        coordinator = FactoryPhotoCoordinator(context, policy) { caller -> check(caller == proof.appId); proof }
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
    private fun advance(activity: FactoryPhotoActivity) = FactoryPhotoActivity::class.java.getDeclaredMethod("advance").apply { isAccessible = true }.invoke(activity)
    private fun render(activity: FactoryPhotoActivity) = FactoryPhotoActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
    private fun awaitWorker() {
        val executor = FactoryPhotoActivity::class.java.getDeclaredField("WORKER").apply { isAccessible = true }.get(null) as ExecutorService
        var accepted = false
        repeat(1000) {
            if (!accepted) {
                try { executor.submit {}.get(5, TimeUnit.SECONDS); accepted = true }
                catch (_: java.util.concurrent.RejectedExecutionException) { Thread.sleep(5) }
            }
        }
        assertTrue("Worker did not become idle", accepted)
    }
    private fun drain(activity: FactoryPhotoActivity) { awaitWorker(); shadowOf(Looper.getMainLooper()).idle() }
    private fun bindSelected(activity: FactoryPhotoActivity): String {
        val owner = coordinator.begin(proof.appId, request)
        coordinator.launch(owner) {}; coordinator.pickerTerminal(owner, true)
        set(activity, "token", owner); set(activity, "request", request)
        set(activity, "selected", FactoryPhotoCapture.Image(byteArrayOf(1, 2, 3), "image/png", 2, 3));
        set(activity, "admission", com.jarvys.factory.runtime.FileShareTransfer.reserve());
        set(activity, "selectionDeadline", android.os.SystemClock.elapsedRealtime() + FactoryPhotoCapture.LIFETIME); render(activity)
        return owner
    }
    @Test fun exportedUnknownCallerGetsNoAuthorityAndSecureControls() {
        val control = Robolectric.buildActivity(FactoryPhotoActivity::class.java, Intent().putExtra("operation", "pick").putExtra("nonce", request.nonce))
        controllers += control
        shadowOf(control.get()).setCallingPackage("evil.app")
        control.setup(); val activity = control.get(); drain(activity)
        assertTrue(activity.packageManager.getActivityInfo(ComponentName(activity, FactoryPhotoActivity::class.java), 0).exported)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertFalse(button(activity,"choose").isEnabled); assertNull(button(activity, "use"))
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertEquals("idle", coordinator.status())
        for (name in listOf("choose","close","recover")) assertTrue(button(activity,name).filterTouchesWhenObscured)
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
    @Test fun validatedSelectionAutomaticallyReturnsBoundNonceAndNativeBinderWithoutUriOrRawBytes() {
        val info = android.content.pm.PackageInfo().apply {
            packageName = proof.appId
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = proof.appId; uid = 20002 }
        }
        shadowOf(context.packageManager).installPackage(info)
        shadowOf(context.packageManager).setPackagesForUid(20002, proof.appId)
        val activity = launch().get(); bindSelected(activity)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        advance(activity); drain(activity)
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
        control.resume(); advance(activity)
        assertNull(button(activity, "use"))
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
        for (name in listOf("choose","recover")) {
            assertFalse(button(activity,name).isEnabled); button(activity,name).performClick()
        }
        assertEquals("idle", coordinator.status())
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun recoveryRequiresActiveForegroundAndDoesNotRestoreGrants() {
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
        assertNull(button(activity, "use"))
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertEquals("idle", coordinator.status())
    }
    @Test fun externalCancellationAutomaticallyFinishesWithoutSuccess() {
        val activity = launch().get(); val owner = coordinator.begin(proof.appId, request)
        coordinator.launch(owner) {}
        set(activity, "token", owner); set(activity, "request", request); set(activity, "externalOwned", true)
        shadowOf(activity).callOnActivityResult(6901, Activity.RESULT_CANCELED, null)
        assertEquals("cancelled", coordinator.status()); assertNull(button(activity, "use"))
        assertTrue(activity.isFinishing)
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

    @Test fun foregroundLossSchedulesPotentiallyBlockingProviderCancellationOffMainThread() {
        val control = launch(); val activity = control.get(); bindSelected(activity)
        val signal = FactoryPhotoActivity::class.java.getDeclaredField("signal").apply { isAccessible = true }.get(activity) as android.os.CancellationSignal
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        signal.setOnCancelListener { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            val before = System.nanoTime(); control.pause()
            assertTrue("Pause blocked on provider cancellation", System.nanoTime() - before < 1_000_000_000L)
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(coordinator.needsRecovery())
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        } finally { release.countDown() }
    }
    @Test fun selectionExpiryErasesBytesAndPreventsNativeReturn() {
        val activity = launch().get(); bindSelected(activity)
        val selected = FactoryPhotoActivity::class.java.getDeclaredField("selected").apply { isAccessible = true }.get(activity) as FactoryPhotoCapture.Image
        val expire = FactoryPhotoActivity::class.java.getDeclaredField("expireSelection").apply { isAccessible = true }.get(activity) as Runnable
        expire.run()
        assertArrayEquals(ByteArray(selected.bytes.size), selected.bytes)
        assertNull(button(activity, "use")); advance(activity)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode); assertTrue(coordinator.needsRecovery())
    }

    @Test fun configuredAskHasOneReviewThenAutomaticResultWithoutUse() {
        policy.setDefaultMode(FactoryOperationPolicy.Mode.ASK)
        val app = android.content.pm.PackageInfo().apply {
            packageName = proof.appId
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = proof.appId; uid = 20002 }
        }
        val pm = shadowOf(context.packageManager)
        pm.installPackage(app); pm.setPackagesForUid(20002, proof.appId)
        val picker = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "example.system.photos"; name = "example.system.photos.Picker"; exported = true; enabled = true
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "example.system.photos"; flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM; enabled = true }
            }
        }
        pm.addResolveInfoForIntent(Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*"), picker)
        val control = launchRequest(registerPicker = false); val activity = control.get()
        val owner = FactoryPhotoActivity::class.java.getDeclaredField("token").apply { isAccessible = true }.get(activity) as String
        assertTrue(button(activity, "choose").isEnabled); assertNull(button(activity, "use"))
        assertNull(shadowOf(activity).nextStartedActivity)
        button(activity, "choose").performClick(); drain(activity)
        val external = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(external); assertEquals(android.provider.MediaStore.ACTION_PICK_IMAGES, external.intent.action)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, external.intent.flags)
        assertEquals("picker", coordinator.status()); assertNull(button(activity, "use"))
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        // Synthetic successful selection boundary; actual bytes are tested independently with native decoding.
        control.pause().resume(); set(activity, "externalOwned", false)
        coordinator.pickerTerminal(owner, true)
        set(activity, "selected", FactoryPhotoCapture.Image(byteArrayOf(4, 5, 6), "image/png", 1, 1)); render(activity)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode); assertNull(button(activity, "use"))
        advance(activity); drain(activity)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        (shadowOf(activity).resultIntent.extras!!.getBinder("transfer") as com.jarvys.factory.runtime.FileShareTransfer).close()
    }

    @Test fun cleanupRetainsAdmissionCaptureUntilCancelledCameraWorkerActuallyCompletes() {
        val activity = launch().get(); bindSelected(activity)
        val camera = FactoryPhotoCapture.reserve(context, "example.camera", android.os.Process.myUid()) { true }
        val writer = FactoryPhotoCapture.open(camera.uri, "w", camera.uid)
        val pipe = org.robolectric.shadow.api.Shadow.extract<FactoryPhotoCaptureTest.PipeDescriptor>(writer).state
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        pipe.pollEntered = entered; pipe.pollRelease = release
        val pending = FactoryPhotoActivity::class.java.getDeclaredField("admission").apply { isAccessible = true }.get(activity) as com.jarvys.factory.runtime.FileShareTransfer.Admission
        val closed = pending.javaClass.getDeclaredField("closed").apply { isAccessible = true }
        val cleanup = FactoryPhotoActivity::class.java.getDeclaredMethod("cleanup").apply { isAccessible = true }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            set(activity, "capture", camera); set(activity, "admissionCapture", camera); set(activity, "working", true)
            cleanup.invoke(activity)
            assertFalse(closed.getBoolean(pending)); assertFalse(camera.completed)
            set(activity, "working", false); cleanup.invoke(activity)
            assertFalse("Admission released before camera worker disposed provisional bytes", closed.getBoolean(pending))
            assertTrue(runCatching { com.jarvys.factory.runtime.FileShareTransfer.reserve() }.isFailure)
            release.countDown()
            val end = System.nanoTime() + 5_000_000_000L
            while (!closed.getBoolean(pending) && System.nanoTime() < end) Thread.sleep(5)
            assertTrue(camera.completed); assertTrue(closed.getBoolean(pending))
            com.jarvys.factory.runtime.FileShareTransfer.reserve().close()
        } finally { release.countDown(); writer.close(); FactoryPhotoCapture.cancel(camera); pending.close() }
    }
    @Test fun expiryWhilePreparedLaunchWaitsForMainThreadPreventsExternalStart() {
        policy.setDefaultMode(FactoryOperationPolicy.Mode.ASK)
        val picker = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "example.system.photos"; name = "example.system.photos.Picker"; exported = true; enabled = true
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "example.system.photos"; flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM; enabled = true }
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*"), picker)
        val activity = launchRequest(registerPicker = false).get()
        val owner = FactoryPhotoActivity::class.java.getDeclaredField("token").apply { isAccessible = true }.get(activity) as String
        button(activity, "choose").performClick(); awaitWorker()
        assertEquals("picker", coordinator.status())
        assertNull(shadowOf(activity).nextStartedActivity)
        set(activity, "selectionDeadline", android.os.SystemClock.elapsedRealtime())
        (FactoryPhotoActivity::class.java.getDeclaredField("expireSelection").apply { isAccessible = true }.get(activity) as Runnable).run()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode); assertTrue(coordinator.needsRecovery())
        com.jarvys.factory.runtime.FileShareTransfer.reserve().close()
    }

    private fun bindCaptureResult(activity: FactoryPhotoActivity, opened: Boolean): Pair<FactoryPhotoCapture.Capture, android.os.ParcelFileDescriptor?> {
        val value = FactoryPhotoCoordinator.Request("capture", request.nonce)
        val owner = coordinator.begin(proof.appId, value); coordinator.launch(owner) {}
        val capture = FactoryPhotoCapture.reserve(context, "example.camera", android.os.Process.myUid()) { true }
        val writer = if (opened) FactoryPhotoCapture.open(capture.uri, "w", capture.uid) else null
        set(activity, "token", owner); set(activity, "request", value); set(activity, "capture", capture)
        set(activity, "admissionCapture", capture); set(activity, "admission", com.jarvys.factory.runtime.FileShareTransfer.reserve())
        set(activity, "selectionDeadline", android.os.SystemClock.elapsedRealtime() + FactoryPhotoCapture.LIFETIME)
        set(activity, "externalOwned", true)
        shadowOf(activity).callOnActivityResult(6901, Activity.RESULT_OK, null)
        return capture to writer
    }
    private fun cancelWhileWaitingForCameraEof(back: Boolean) {
        val activity = launch().get(); val (capture, writer) = bindCaptureResult(activity, true)
        try {
            assertFalse(capture.completed); assertTrue(button(activity, "close").isEnabled)
            assertNull(button(activity, "use"))
            if (back) activity.onBackPressedDispatcher.onBackPressed() else button(activity, "close").performClick()
            assertFalse("Cancellation must not assert external completion", activity.isFinishing)
            drain(activity)
            // Camera cleanup has its own bounded worker; await its post-callback barrier too.
            val cameraWorker = FactoryPhotoCapture::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(null) as ExecutorService
            val deadline = System.nanoTime() + 5_000_000_000L
            var completed = false
            while (!completed && System.nanoTime() < deadline) {
                try { cameraWorker.submit {}.get(5, TimeUnit.SECONDS); completed = true }
                catch (_: java.util.concurrent.RejectedExecutionException) { Thread.sleep(5) }
            }
            assertTrue("Camera cleanup worker did not finish", completed)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
            assertTrue(coordinator.needsRecovery()); assertTrue(capture.cancelled)
            assertNull(button(activity, "use")); assertTrue(button(activity, "recover").isEnabled)
            com.jarvys.factory.runtime.FileShareTransfer.reserve().close()
        } finally { writer?.close(); FactoryPhotoCapture.cancel(capture) }
    }
    @Test fun nativeCloseCancelsPendingCameraEofWithoutReleasingResourcesOrReturningBytes() {
        cancelWhileWaitingForCameraEof(false)
    }
    @Test fun nativeBackCancelsPendingCameraEofWithoutClaimingExternalCompletion() {
        cancelWhileWaitingForCameraEof(true)
    }
    @Test fun cameraResultOkWithoutOpeningOutputFailsImmediatelyAndReturnsNothing() {
        val activity = launch().get(); val (capture, _) = bindCaptureResult(activity, false)
        try {
            drain(activity)
            assertFalse(capture.opened); assertTrue(capture.cancelled)
            assertEquals("cancelled", coordinator.status())
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
            assertNull(shadowOf(activity).resultIntent); assertNull(button(activity, "use"))
            com.jarvys.factory.runtime.FileShareTransfer.reserve().close()
        } finally { FactoryPhotoCapture.cancel(capture) }
    }

    @Test fun recoveryCannotReleaseCrossBrokerAdmissionWhileCancelledPhotoMaterializationStillOwnsBytes() {
        policy.setDefaultMode(FactoryOperationPolicy.Mode.ASK)
        val picker = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "example.system.photos"; name = "example.system.photos.Picker"; exported = true; enabled = true
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "example.system.photos"; flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM; enabled = true }
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*"), picker)
        val activity = launchRequest(registerPicker = false).get()
        val owner = FactoryPhotoActivity::class.java.getDeclaredField("token").apply { isAccessible = true }.get(activity) as String
        button(activity, "choose").performClick(); drain(activity)
        assertNotNull(shadowOf(activity).nextStartedActivityForResult)
        val pending = FactoryPhotoActivity::class.java.getDeclaredField("admission").apply { isAccessible = true }.get(activity)
        assertNotNull(pending)
        val owners = FactoryInteractionAdmission::class.java.getDeclaredField("owners").apply { isAccessible = true }
        fun currentOwners(): Set<*> = synchronized(FactoryInteractionAdmission) { (owners.get(null) as Set<*>).toSet() }
        assertEquals(setOf(coordinator, pending), currentOwners())
        // Model the provider's blocked materialization boundary after its picker has returned.
        set(activity, "externalOwned", false); set(activity, "working", true); render(activity)
        button(activity, "close").performClick()
        assertTrue(coordinator.needsRecovery()); assertFalse(activity.isFinishing)
        // A separate native recovery interaction can acknowledge uncertainty, never dispose worker bytes.
        coordinator.acknowledgeRecovery {}
        assertEquals(setOf(pending), currentOwners())
        assertFalse(FactoryInteractionAdmission.available()); assertTrue(FactoryDocumentCoordinator(context, policy) { proof }.canBegin())
        assertTrue(FactoryFileShareCoordinator(context, verify = { proof }).canBegin())
        set(activity, "working", false)
        FactoryPhotoActivity::class.java.getDeclaredMethod("cleanup").apply { isAccessible = true }.invoke(activity)
        assertEquals(emptySet<Any>(), currentOwners()); assertTrue(FactoryInteractionAdmission.available())
        com.jarvys.factory.runtime.FileShareTransfer.reserve().close()
    }

    private fun launchRequest(registerPicker: Boolean = true): ActivityController<GrantedPhotoActivity> {
        val picker = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "example.android.picker"; name = "example.android.picker.Picker"; exported = true; enabled = true
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "example.android.picker"; flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM; enabled = true }
            }
        }
        if (registerPicker) shadowOf(context.packageManager).addResolveInfoForIntent(Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*"), picker)
        val control = Robolectric.buildActivity(GrantedPhotoActivity::class.java, Intent().putExtra("operation", "pick").putExtra("nonce", request.nonce))
        controllers += control; shadowOf(control.get()).setCallingPackage(proof.appId)
        control.create().start().resume().visible(); repeat(3) { drain(control.get()) }
        return control
    }
    @Test fun defaultAllowAutomaticallyStartsPickerWithoutGuardDuringAutomation() {
        val action = MemoryUiAutomationGuard.beginAsyncAction(MemoryUiAutomationGuard.captureAutomationEpoch())
        try {
            val activity = launchRequest().get()
            assertEquals("picker", coordinator.status()); assertNotNull(shadowOf(activity).nextStartedActivityForResult)
            assertFalse(MemoryUiAutomationGuard.isProtected()); assertNull(button(activity, "use"))
            assertEquals(android.view.View.GONE, button(activity, "choose").visibility)
        } finally { action.close() }
    }
    @Test fun denyDoesNotLaunchPickerOrCreateJournalOrGuard() {
        policy.setDefaultMode(FactoryOperationPolicy.Mode.DENY)
        val activity = launchRequest().get()
        assertTrue(activity.isFinishing); assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertFalse(File(root, "interaction.json").exists()); assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }
    @Test fun configuredAskOwnsGuardOnlyUntilOneApproval() {
        policy.setDefaultMode(FactoryOperationPolicy.Mode.ASK)
        val activity = launchRequest().get()
        assertEquals("review", coordinator.status()); assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertTrue(button(activity, "choose").isEnabled)
        button(activity, "choose").performClick(); repeat(3) { drain(activity) }
        assertEquals("picker", coordinator.status()); assertNotNull(shadowOf(activity).nextStartedActivityForResult)
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertNull(button(activity, "use"))
    }
    @Test fun policyRevocationCancelsOutstandingPickerAuthorityWithoutReturn() {
        val activity = launchRequest().get(); assertEquals("picker", coordinator.status())
        policy.setDefaultMode(FactoryOperationPolicy.Mode.DENY); repeat(3) { drain(activity) }
        assertTrue(coordinator.needsRecovery()); assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).resultIntent); assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun deliveredPhotoRetainsRevocableGrantUntilBinderCloses() {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = proof.appId
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = proof.appId; uid = 20002 }
        }); pm.setPackagesForUid(20002, proof.appId)
        val activity = launch().get(); val owner = bindSelected(activity); val grant = coordinator.authority(owner)
        advance(activity); drain(activity)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode); grant.checkBeforeEffect()
        val transfer = shadowOf(activity).resultIntent.extras!!.getBinder("transfer") as com.jarvys.factory.runtime.FileShareTransfer
        policy.setDefaultMode(FactoryOperationPolicy.Mode.DENY)
        val snapshot = com.jarvys.factory.runtime.FileShareTransfer::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
        assertNull(snapshot.get(transfer)); assertTrue(runCatching { grant.checkBeforeEffect() }.isFailure)
        transfer.close()
    }

    @Test fun recoveryUnderAllowNeedsNoGlobalHumanGuardAndNeverReplays() = recoverWithoutGuard(FactoryOperationPolicy.Mode.ALLOW_ALWAYS)
    @Test fun recoveryUnderDenyNeedsNoGlobalHumanGuardAndNeverReplays() = recoverWithoutGuard(FactoryOperationPolicy.Mode.DENY)
    private fun recoverWithoutGuard(mode: FactoryOperationPolicy.Mode) {
        val owner = coordinator.begin(proof.appId, request); coordinator.launch(owner) {}; coordinator.revoke(owner)
        policy.setDefaultMode(mode)
        val automated = MemoryUiAutomationGuard.beginAsyncAction(MemoryUiAutomationGuard.captureAutomationEpoch())
        try {
            val activity = launch().get()
            assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(button(activity, "recover").isEnabled)
            button(activity, "recover").performClick(); drain(activity)
            assertEquals("closed_outcome_unknown", coordinator.status()); assertTrue(activity.isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode); assertNull(shadowOf(activity).resultIntent)
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertTrue(runCatching { coordinator.pickerTerminal(owner, true) }.isFailure)
        } finally { automated.close() }
        policy.setDefaultMode(FactoryOperationPolicy.Mode.ALLOW_ALWAYS)
        assertTrue(runCatching { coordinator.begin(proof.appId, request) }.isFailure)
    }
    @Test fun actualAskReviewRemainsTaintedWhenAutomationWasInFlight() {
        policy.setDefaultMode(FactoryOperationPolicy.Mode.ASK)
        val automated = MemoryUiAutomationGuard.beginAsyncAction(MemoryUiAutomationGuard.captureAutomationEpoch())
        val activity = try { launchRequest().get() } finally { automated.close() }
        assertFalse(button(activity, "choose").isEnabled); button(activity, "choose").performClick(); drain(activity)
        assertEquals("review", coordinator.status()); assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

}
