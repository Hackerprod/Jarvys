package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.factory.runtime.ExternalLaunchControl
import com.jarvys.factory.contract.ExternalLaunchSpec
import org.json.JSONObject
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Native host review and verified PM resolution; only external startActivity is recorded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, shadows = [FactoryExternalLaunchPackageManagerShadow::class, FactoryFileShareOsShadow::class])
class FactoryExternalLaunchActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-external-launch")
    private var proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryExternalLaunchCoordinator
    private lateinit var fixture: FactoryExternalLaunchTestPackages.Fixture
    private lateinit var source: ExternalLaunchControl
    private var spec: ExternalLaunchSpec = FactoryExternalLaunchTestPackages.MAPS_QUERY
    private val verifiedCapabilities = mutableListOf<String>()
    private val controllers = mutableListOf<ActivityController<FactoryExternalLaunchRecordingActivity>>()
    private val nonce = "d".repeat(64)
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup(); root.deleteRecursively()
        FactoryExternalLaunchPackageManagerShadow.beforeQuery = null; FactoryExternalLaunchPackageManagerShadow.useAndroidDefault = false
        coordinator = FactoryExternalLaunchCoordinator(context) { appId, capability ->
            check(appId == proof.appId); check(capability == spec.capability); verifiedCapabilities += capability; proof
        }
        FactoryExternalLaunchCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
        fixture = FactoryExternalLaunchTestPackages.install(context)
        val uid = Binder.getCallingUid(); val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply { packageName = proof.appId; applicationInfo = ApplicationInfo().apply { packageName = proof.appId; this.uid = uid; enabled = true } })
        pm.setPackagesForUid(uid, proof.appId)
        source = ExternalLaunchControl(nonce) { caller -> caller == uid }
    }
    @After fun cleanup() {
        FactoryExternalLaunchPackageManagerShadow.beforeQuery = null; drain()
        controllers.asReversed().forEach { runCatching { it.pause().stop().destroy() } }
        shadowOf(Looper.getMainLooper()).idle(); drain()
        coordinator.session()?.preparing = false
        coordinator.session()?.let { worker { coordinator.close(it) {} } }
        source.close(); drain()
        FactoryExternalLaunchCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available()); root.deleteRecursively()
    }
    private fun <T> worker(block: () -> T): T {
        val executor = FactoryExternalLaunchCoordinator.WORKER; executor.prestartCoreThread(); val task = FutureTask(Callable(block))
        check(executor.queue.offer(task, 5, TimeUnit.SECONDS)); return task.get(5, TimeUnit.SECONDS)
    }
    private fun drain() { repeat(3) { worker {}; shadowOf(Looper.getMainLooper()).idle() } }
    private fun request(control: IBinder = source) = Intent(context, FactoryExternalLaunchRecordingActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("nonce", nonce); putString("method", FactoryExternalLaunchTestPackages.method(spec))
        putString("args", FactoryExternalLaunchTestPackages.args(spec)); putBinder("control", control)
    })
    private fun launch(intent: Intent = request(), saved: Bundle? = null, caller: String? = proof.appId): ActivityController<FactoryExternalLaunchRecordingActivity> {
        val controller = Robolectric.buildActivity(FactoryExternalLaunchRecordingActivity::class.java, intent); controllers += controller
        caller?.let { shadowOf(controller.get()).setCallingPackage(it) }
        controller.create(saved).start().resume().visible(); controller.get().onWindowFocusChanged(true)
        drain(); return controller
    }
    private fun recovery() = launch(Intent(context), caller = null)
    private fun button(activity: FactoryExternalActionActivity, name: String) = activity.window.decorView.findViewWithTag<Button>("factory-external-launch-$name")
    private fun choose(activity: FactoryExternalActionActivity) {
        val choice = activity.window.decorView.findViewWithTag<RadioButton>(fixture.component.flattenToString())
        assertNotNull("Verified typed recipient must be offered as a native choice", choice); assertTrue(choice.isEnabled); choice.performClick()
    }
    private fun render(activity: FactoryExternalActionActivity) = FactoryExternalActionActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun wrapRevocation(value: FactoryExternalLaunchCoordinator.Session, around: (Runnable) -> Unit) {
        val registration = value.registration!!
        val action = registration.javaClass.getDeclaredField("action").apply { isAccessible = true }
        val original = action.get(registration) as Runnable; action.set(registration, Runnable { around(original) })
    }
    @Test fun unrelatedStartupOwnerDoesNotBlockThisOperation() {
        val other = Any(); FactoryInteractionAdmission.restore(other)
        try {
            val activity = launch().get()
            assertNotNull(coordinator.session()); assertFalse(activity.isFinishing)
            choose(activity); button(activity, "open").performClick(); drain()
            assertEquals(1, activity.launches.size); assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
            assertFalse(FactoryInteractionAdmission.available(other))
        } finally { FactoryInteractionAdmission.release(other) }
    }
    @Test fun fullyAndPartiallyObscuredNativeControlsRejectSyntheticTouch() {
        val activity = launch().get()
        val controls = listOf<View>(button(activity, "open"), button(activity, "close"), activity.window.decorView.findViewWithTag<RadioButton>(fixture.component.flattenToString()))
        fun event(flags: Int): android.view.MotionEvent = android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_DOWN, 1,
            arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER }),
            arrayOf(android.view.MotionEvent.PointerCoords().apply { x = 1f; y = 1f; pressure = 1f; size = 1f }),
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, flags)
        for (control in controls) {
            for (flags in listOf(android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED, android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) {
                val touch = event(flags); try { assertFalse(control.onFilterTouchEventForSecurity(touch)) } finally { touch.recycle() }
            }
            val clean = event(0); try { assertTrue(control.onFilterTouchEventForSecurity(clean)) } finally { clean.recycle() }
        }
        assertEquals(0, activity.launches.size); assertFalse(coordinator.session()!!.attempted)
    }
    @Test fun noEligibleRecipientLeavesReviewClosableWithoutAutomaticFallback() {
        shadowOf(context.packageManager).setResolveInfosForIntent(FactoryExternalLaunchTestPackages.view(spec, discovery = true), emptyList())
        val activity = launch().get(); assertFalse(button(activity, "open").isEnabled); assertEquals(0, activity.launches.size)
        button(activity, "close").performClick(); drain(); assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertFalse(shadowOf(activity).resultIntent.getBooleanExtra("launchRequested", true))
    }
    @Test fun fullTypedDetailsSecureNativeReviewRequiresSelectionAndNeverAutolaunches() {
        val activity = launch().get(); val value = coordinator.session()!!
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available()); assertFalse(value.attempted)
        val text = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(text.any { it.contains(spec.display) && it.contains(FactoryExternalLaunchTestPackages.method(spec)) && it.contains(proof.appId) })
        assertFalse(button(activity, "open").isEnabled); assertTrue(button(activity, "close").isEnabled)
        for (name in listOf("open", "close")) { assertTrue(button(activity, name).filterTouchesWhenObscured); assertFalse(button(activity, name).isSaveEnabled) }
        val choice = activity.window.decorView.findViewWithTag<RadioButton>(fixture.component.flattenToString())
        assertTrue(choice.filterTouchesWhenObscured); assertFalse(choice.isSaveEnabled); assertFalse(choice.isChecked)
        assertEquals(0, activity.launches.size); assertNull(shadowOf(activity).nextStartedActivity)
        choose(activity); assertTrue(button(activity, "open").isEnabled); assertEquals(0, activity.launches.size)
    }
    @Test fun mapsQueryPersistsPendingBeforeOneExactExternalLaunchAndNeverClaimsAction() = explicitTypedDispatch(FactoryExternalLaunchTestPackages.MAPS_QUERY)
    @Test fun mapsCoordinatesDispatchOnlyReviewedGeoViewWithNoNavigationClaim() = explicitTypedDispatch(FactoryExternalLaunchTestPackages.MAPS_COORDINATES)
    @Test fun phoneDialDispatchesOnlyReviewedTelDialWithNoActualCallOrCompletionClaim() = explicitTypedDispatch(FactoryExternalLaunchTestPackages.PHONE)
    private fun explicitTypedDispatch(value: ExternalLaunchSpec) {
        spec = value
        if (spec !== FactoryExternalLaunchTestPackages.MAPS_QUERY) fixture = FactoryExternalLaunchTestPackages.install(context, "example.alternative.external", 23003, spec)
        val activity = launch().get(); choose(activity)
        activity.beforeStart = {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            val journal = JSONObject(File(root, "interaction.json").readText())
            assertEquals("launch_pending", journal.getString("state")); assertTrue(journal.getBoolean("open")); assertFalse(journal.toString().contains(spec.uri))
            assertTrue(coordinator.session()!!.attempted); assertFalse(coordinator.session()!!.launchRequested)
        }
        button(activity, "open").performClick(); drain()
        val exact = activity.launches.single(); assertEquals(fixture.component, exact.component); assertEquals(spec.uri, exact.dataString)
        assertEquals(FactoryExternalLaunchTestPackages.action(spec), exact.action); assertNotEquals(Intent.ACTION_CALL, exact.action)
        assertEquals(setOf(Intent.CATEGORY_DEFAULT), exact.categories); assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, exact.flags); assertNull(exact.extras); assertNull(exact.selector); assertNull(exact.clipData)
        assertNull(coordinator.session()); assertTrue(activity.isFinishing); assertFalse(MemoryUiAutomationGuard.isProtected())
        button(activity, "open").performClick(); drain(); assertEquals(1, activity.launches.size)
        val receipt = shadowOf(activity).resultIntent; assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals(setOf("nonce", "launchRequested", "actionConfirmed"), receipt.extras!!.keySet()); assertEquals(nonce, receipt.getStringExtra("nonce"))
        assertTrue(receipt.getBooleanExtra("launchRequested", false)); assertFalse(receipt.getBooleanExtra("actionConfirmed", true))
        assertEquals(List(3) { spec.capability }, verifiedCapabilities)
        assertTrue(activity.launches.all { it.action != Intent.ACTION_CALL && it.component != null })
    }
    @Test fun registrationDisposalCallbackBeforeCloseCompletionCannotEraseValidReceipt() {
        val activity = launch().get(); choose(activity)
        val callbackPosted = CountDownLatch(1); val finishClose = CountDownLatch(1)
        wrapRevocation(coordinator.session()!!) { original -> original.run(); callbackPosted.countDown(); check(finishClose.await(10, TimeUnit.SECONDS)) }
        try {
            button(activity, "open").performClick(); worker {}; shadowOf(Looper.getMainLooper()).idle()
            assertTrue(callbackPosted.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode); assertFalse(activity.isFinishing)
        } finally { finishClose.countDown() }
        drain()
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode); assertTrue(shadowOf(activity).resultIntent.getBooleanExtra("launchRequested", false))
        assertFalse(shadowOf(activity).resultIntent.getBooleanExtra("actionConfirmed", true)); assertEquals("closed", coordinator.status())
    }
    @Test fun nativeCloseBeforeConsentReturnsFalseReceiptAndNoExternalLaunch() {
        val activity = launch().get(); button(activity, "close").performClick(); drain()
        assertEquals(0, activity.launches.size); assertEquals("closed", coordinator.status()); assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val receipt = shadowOf(activity).resultIntent; assertEquals(nonce, receipt.getStringExtra("nonce"))
        assertFalse(receipt.getBooleanExtra("launchRequested", true)); assertFalse(receipt.getBooleanExtra("actionConfirmed", true))
    }
    @Test fun pauseDuringBlockedFinalRecipientVerificationPreventsLaunchAndKeepsRecoveryBusy() = blockedFinalVerification("pause")
    @Test fun focusLossDuringBlockedFinalRecipientVerificationPreventsLaunch() = blockedFinalVerification("focus")
    @Test fun sourceCancellationDuringBlockedFinalRecipientVerificationPreventsLaunch() = blockedFinalVerification("cancel")
    private fun blockedFinalVerification(change: String) {
        val controller = launch(); val activity = controller.get(); choose(activity)
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val exactQueries = AtomicInteger(); val cancelled = CountDownLatch(1)
        wrapRevocation(coordinator.session()!!) { it.run(); cancelled.countDown() }
        FactoryExternalLaunchPackageManagerShadow.beforeQuery = { intent ->
            if (intent.`package` == fixture.packageName && intent.dataString == spec.uri && exactQueries.incrementAndGet() == 2) {
                assertTrue(Looper.myLooper() != Looper.getMainLooper()); entered.countDown(); check(finish.await(10, TimeUnit.SECONDS))
            }
        }
        var recoveryActivity: FactoryExternalLaunchRecordingActivity? = null
        try {
            button(activity, "open").performClick(); assertTrue("Final native package query was not reached", entered.await(5, TimeUnit.SECONDS))
            val value = coordinator.session()!!; assertTrue(value.preparing); assertTrue(value.attempted)
            when (change) { "pause" -> controller.pause(); "focus" -> activity.onWindowFocusChanged(false); "cancel" -> { source.cancel(); assertTrue(cancelled.await(5, TimeUnit.SECONDS)) } }
            assertTrue(value.revoked.get()); assertEquals(0, activity.launches.size); assertFalse(FactoryInteractionAdmission.available())
            // Do not drain the worker while it is intentionally blocked.
            val next = Robolectric.buildActivity(FactoryExternalLaunchRecordingActivity::class.java, Intent(context)); controllers += next
            next.create().start().resume().visible(); next.get().onWindowFocusChanged(true); recoveryActivity = next.get()
            assertFalse(button(next.get(), "close").isEnabled); button(next.get(), "close").performClick()
            assertTrue(value.preparing); assertFalse(FactoryInteractionAdmission.available())
        } finally { finish.countDown(); FactoryExternalLaunchPackageManagerShadow.beforeQuery = null }
        drain(); assertEquals(0, activity.launches.size); assertFalse(coordinator.session()!!.preparing)
        val recovered = recoveryActivity!!; render(recovered); assertTrue(button(recovered, "close").isEnabled)
        button(recovered, "close").performClick(); drain()
        assertEquals("closed_outcome_unknown", coordinator.status()); assertTrue(FactoryInteractionAdmission.available())
        assertEquals(Activity.RESULT_CANCELED, shadowOf(recovered).resultCode); assertNull(shadowOf(recovered).resultIntent)
    }
    @Test fun missingCallerMalformedRequestAndRecreationCannotRestoreOrLaunch() {
        for ((intent, saved, caller) in listOf(Triple(request(), null, null), Triple(request().putExtra("target", fixture.packageName), null, proof.appId), Triple(request(), Bundle(), proof.appId))) {
            val activity = launch(intent, saved, caller).get(); assertTrue(activity.isFinishing); assertEquals(0, activity.launches.size)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode); assertNull(button(activity, "open"))
        }
        assertEquals("idle", coordinator.status())
    }
}

/** No real Activity dispatch: preserve host logic up to the final platform effect. */
class FactoryExternalLaunchRecordingActivity : FactoryExternalActionActivity() {
    val launches = mutableListOf<Intent>()
    var beforeStart: (() -> Unit)? = null
    override fun startActivity(intent: Intent) { launches += Intent(intent); beforeStart?.invoke() }
}

/** Hooks may stall public queries; resolution itself always delegates to Robolectric PM. */
@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
class FactoryExternalLaunchPackageManagerShadow : ShadowApplicationPackageManager() {
    @Implementation override fun queryIntentActivities(intent: Intent, flags: Int): MutableList<ResolveInfo> {
        beforeQuery?.invoke(intent); return super.queryIntentActivities(intent, flags)
    }
    @Implementation override fun resolveActivity(intent: Intent, flags: Int): ResolveInfo? =
        if (useAndroidDefault) super.resolveActivity(intent, flags) else null
    companion object {
        @Volatile var beforeQuery: ((Intent) -> Unit)? = null
        @Volatile var useAndroidDefault = false
    }
}
