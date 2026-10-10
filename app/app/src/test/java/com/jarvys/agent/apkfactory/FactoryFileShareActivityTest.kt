package com.jarvys.agent.apkfactory

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
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
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FactoryFileShareActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private val request = FactoryFileShareCoordinator.Request("d".repeat(64), "file.bin", "application/octet-stream", 3, "e".repeat(64), Binder())
    private val executor = Executors.newSingleThreadExecutor()
    private val controllers = mutableListOf<ActivityController<FactoryFileShareActivity>>()
    private lateinit var coordinator: FactoryFileShareCoordinator
    private var cleanups = 0
    private fun <T> io(block: () -> T): T = executor.submit(Callable { block() }).get(10, TimeUnit.SECONDS)
    @Before fun setup() {
        File(context.noBackupFilesDir, "factory-file-sharing").deleteRecursively()
        coordinator = FactoryFileShareCoordinator(context, { check(it == proof.appId); proof }, receive = { value, active ->
            active(); FactoryFileShareStore.Snapshot(Uri.parse("content://${context.packageName}.factory.files/files/synthetic.bin"), value.filename, value.mimeType, value.size, SystemClock.elapsedRealtime() + 300000)
        }, clean = { cleanups++ })
        FactoryFileShareCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
    }
    @After fun cleanup() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        executor.shutdownNow()
        FactoryFileShareCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        for (name in listOf("protectedSurfaces", "taintedSurfaces", "activeActions")) MemoryUiAutomationGuard::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(null, 0)
        FactoryInteractionAdmission::class.java.getDeclaredField("owners").apply { isAccessible = true }.let { (it.get(null) as MutableSet<*>).clear() }
        File(context.noBackupFilesDir, "factory-file-sharing").deleteRecursively()
    }
    private fun launch(intent: Intent = FactoryFileShareCoordinator.recoveryIntent(context), saved: Bundle? = null): ActivityController<FactoryFileShareActivity> {
        val control = Robolectric.buildActivity(FactoryFileShareActivity::class.java, intent)
        controllers += control
        control.create(saved).start().resume().visible()
        return control
    }
    private fun button(activity: FactoryFileShareActivity, name: String) = activity.window.decorView.findViewWithTag<Button>("factory-file-sharing-$name")
    private fun set(activity: FactoryFileShareActivity, name: String, value: Any?) = FactoryFileShareActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun render(activity: FactoryFileShareActivity) = FactoryFileShareActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
    private fun bind(activity: FactoryFileShareActivity, opened: Boolean = false): String {
        val owner = io { coordinator.begin(proof.appId, request) {} }
        if (opened) { io { coordinator.prepareChooser(owner, "Test") {} }; coordinator.chooserLaunched(owner) }
        set(activity, "token", owner); set(activity, "request", request); set(activity, "external", opened); render(activity)
        return owner
    }
    private fun drain() {
        var accepted = false
        repeat(1000) {
            if (!accepted) try { FactoryFileShareCoordinator.WORKER.submit {}.get(5, TimeUnit.SECONDS); accepted = true }
            catch (_: java.util.concurrent.RejectedExecutionException) { Thread.sleep(5) }
        }
        assertTrue(accepted); shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun secureControlsRequireExplicitForegroundNativeHuman() {
        val activity = launch().get()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        for (name in listOf("choose", "close", "acknowledge")) assertTrue(button(activity, name).filterTouchesWhenObscured)
        assertTrue(activity.packageManager.getActivityInfo(ComponentName(activity, FactoryFileShareActivity::class.java), 0).exported)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }
    @Test fun chooserCallbackCannotReleaseLatchOrConfirmDelivery() {
        val activity = launch().get(); bind(activity, opened = true)
        shadowOf(activity).callOnActivityResult(6801, Activity.RESULT_OK, Intent().putExtra("deliveryConfirmed", true))
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertEquals(View.GONE, button(activity, "close").visibility)
        assertTrue(button(activity, "acknowledge").isEnabled)
        button(activity, "acknowledge").performClick(); drain()
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val result = shadowOf(activity).resultIntent
        assertEquals(setOf("nonce", "chooserOpened", "deliveryConfirmed"), result.extras!!.keySet())
        assertTrue(result.getBooleanExtra("chooserOpened", false)); assertFalse(result.getBooleanExtra("deliveryConfirmed", true))
        assertNull(result.data); assertNull(result.clipData); assertEquals(0, result.flags)
        assertEquals(2, cleanups)
    }
    @Test fun nativeCancelBeforeChooserReturnsFalseWithoutExternalEffect() {
        val activity = launch().get(); bind(activity)
        button(activity, "close").performClick(); drain()
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertFalse(shadowOf(activity).resultIntent.getBooleanExtra("chooserOpened", true))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun backAfterChooserKeepsRecoveryAndNeverAcknowledgesForHuman() {
        val activity = launch().get(); bind(activity, opened = true)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(coordinator.needsRecovery()); assertTrue(MemoryUiAutomationGuard.isProtected())
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }
    @Test fun backgroundAndRecreationNeverRestoreBytesOrReturnLaunchClaim() {
        val control = launch(); val activity = control.get(); bind(activity)
        control.pause().stop().destroy(); controllers.remove(control)
        assertTrue(coordinator.needsRecovery())
        val next = launch(saved = Bundle()).get()
        assertTrue(next.isFinishing); assertNull(shadowOf(next).nextStartedActivity)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(next).resultCode); assertNull(shadowOf(next).resultIntent)
    }
    @Test fun recoveryRequiresSeparateForegroundHumanAndNeverReturnsReceipt() {
        val owner = io { coordinator.begin(proof.appId, request) {} }; coordinator.invalidate(owner)
        val control = launch(); val activity = control.get()
        control.pause(); button(activity, "acknowledge").performClick(); assertTrue(coordinator.needsRecovery())
        control.resume(); button(activity, "acknowledge").performClick(); drain()
        assertEquals("closed_outcome_unknown", coordinator.status())
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode); assertNull(shadowOf(activity).resultIntent)
    }
    @Test fun forgedOrReplayedRecoveryTokenCannotRenderNativeAuthority() {
        val intent = FactoryFileShareCoordinator.recoveryIntent(context)
        assertTrue(FactoryFileShareCoordinator.consumeRecoveryToken(intent))
        assertFalse(FactoryFileShareCoordinator.consumeRecoveryToken(intent))
        assertTrue(launch(Intent(context, FactoryFileShareActivity::class.java).putExtra("nativeRecoveryToken", "forged")).get().isFinishing)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun chooserRequiresUniqueEnabledExportedSystemComponent() {
        fun candidate(pkg: String = "android", flags: Int = ApplicationInfo.FLAG_SYSTEM, enabled: Boolean = true) = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg; name = "$pkg.Chooser"; exported = true; this.enabled = enabled
                applicationInfo = ApplicationInfo().apply { packageName = pkg; this.flags = flags; this.enabled = true }
            }
        }
        fun rejects(values: List<ResolveInfo>) = assertTrue(runCatching { FactoryFileShareActivity.selectTrustedChooser(values) }.isFailure)
        rejects(emptyList()); rejects(listOf(candidate(flags = 0))); rejects(listOf(candidate(enabled = false)))
        rejects(listOf(candidate(), candidate("example.system")))
        assertEquals("android", FactoryFileShareActivity.selectTrustedChooser(listOf(candidate(), candidate("evil.app", 0))).packageName)
    }
    private fun incoming() = Intent(context, FactoryFileShareActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("nonce", request.nonce); putString("filename", request.filename)
        putString("mimeType", request.mimeType); putInt("size", request.size); putString("sha256", request.sha256); putBinder("transfer", request.transfer)
    })
    private fun pendingStartup(): Pair<java.util.concurrent.CountDownLatch, java.util.concurrent.Future<*>> {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var first = true
        coordinator = FactoryFileShareCoordinator(context, { check(it == proof.appId); proof }, receive = { value, active ->
            active(); FactoryFileShareStore.Snapshot(Uri.parse("content://${context.packageName}.factory.files/files/synthetic.bin"), value.filename, value.mimeType, value.size, SystemClock.elapsedRealtime() + 300000)
        }, clean = { if (first) { first = false; entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) } })
        FactoryFileShareCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
        FactoryFileShareCoordinator::class.java.getDeclaredMethod("prepareRestore").apply { isAccessible = true }.invoke(coordinator)
        val future = FactoryFileShareCoordinator.WORKER.submit { coordinator.restore() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        return release to future
    }
    @Test fun validColdStartFileRequestWaitsForStartupThenBeginsExactlyOnce() {
        val (release, startup) = pendingStartup()
        val control = Robolectric.buildActivity(FactoryFileShareActivity::class.java, incoming()); controllers += control
        shadowOf(control.get()).setCallingPackage(proof.appId)
        control.create().start().resume().visible()
        assertFalse(control.get().isFinishing); assertTrue(FactoryFileShareCoordinator.startupPending())
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(button(control.get(), "choose").isEnabled)
        release.countDown(); startup.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle(); drain()
        assertEquals("review", coordinator.status()); assertTrue(button(control.get(), "choose").isEnabled)
        button(control.get(), "close").performClick(); drain()
    }
    @Test fun closingWhileStartupPendingNeverReplaysRequestAfterRestore() {
        val (release, startup) = pendingStartup()
        val control = Robolectric.buildActivity(FactoryFileShareActivity::class.java, incoming()); controllers += control
        shadowOf(control.get()).setCallingPackage(proof.appId)
        control.create().start().resume().visible()
        control.get().onBackPressedDispatcher.onBackPressed()
        assertTrue(control.get().isFinishing)
        release.countDown(); startup.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle(); drain()
        assertEquals("idle", coordinator.status()); assertTrue(FactoryInteractionAdmission.available())
        assertEquals(Activity.RESULT_CANCELED, shadowOf(control.get()).resultCode)
    }
    @Test fun validColdStartDocumentRequestWaitsWithoutRegressingSaf() {
        val (release, startup) = pendingStartup()
        val documents = FactoryDocumentCoordinator(context) { check(it == proof.appId); proof }
        FactoryDocumentCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, documents)
        val intent = Intent(context, FactoryDocumentActivity::class.java).putExtra("operation", "open").putExtra("mimeType", "text/plain").putExtra("nonce", request.nonce)
        val control = Robolectric.buildActivity(FactoryDocumentActivity::class.java, intent)
        shadowOf(control.get()).setCallingPackage(proof.appId)
        try {
            control.create().start().resume().visible()
            assertFalse(control.get().isFinishing); assertEquals("idle", documents.status())
            release.countDown(); startup.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val worker = FactoryDocumentActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(control.get()) as java.util.concurrent.ExecutorService
            var accepted = false
            repeat(1000) { if (!accepted) try { worker.submit {}.get(5, TimeUnit.SECONDS); accepted = true } catch (_: java.util.concurrent.RejectedExecutionException) { Thread.yield() } }
            assertTrue(accepted); shadowOf(Looper.getMainLooper()).idle()
            assertEquals("review", documents.status())
            assertTrue(control.get().window.decorView.findViewWithTag<Button>("factory-documents-choose").isEnabled)
        } finally {
            release.countDown(); control.pause().stop().destroy()
            FactoryDocumentCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
            File(context.noBackupFilesDir, "factory-documents").deleteRecursively()
        }
    }

}
