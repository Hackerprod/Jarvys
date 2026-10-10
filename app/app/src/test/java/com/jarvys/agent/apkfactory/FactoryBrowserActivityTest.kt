package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.factory.runtime.BrowserLaunchControl
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
@Config(sdk = [34], application = android.app.Application::class, shadows = [FactoryBrowserPackageManagerShadow::class])
class FactoryBrowserActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-browser")
    private var proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryBrowserCoordinator
    private lateinit var fixture: FactoryBrowserTestPackages.Fixture
    private lateinit var source: BrowserLaunchControl
    private val controllers = mutableListOf<ActivityController<FactoryBrowserRecordingActivity>>()
    private val nonce = "d".repeat(64)
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup(); root.deleteRecursively()
        FactoryBrowserPackageManagerShadow.beforeQuery = null
        coordinator = FactoryBrowserCoordinator(context, { proof })
        FactoryBrowserCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
        fixture = FactoryBrowserTestPackages.install(context)
        val uid = Binder.getCallingUid(); val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply { packageName = proof.appId; applicationInfo = ApplicationInfo().apply { packageName = proof.appId; this.uid = uid; enabled = true } })
        pm.setPackagesForUid(uid, proof.appId)
        source = BrowserLaunchControl(nonce) { caller -> caller == uid }
    }
    @After fun cleanup() {
        FactoryBrowserPackageManagerShadow.beforeQuery = null; drain()
        controllers.asReversed().forEach { runCatching { it.pause().stop().destroy() } }
        shadowOf(Looper.getMainLooper()).idle(); drain()
        coordinator.session()?.preparing = false
        if (coordinator.needsRecovery()) worker { coordinator.close(coordinator.session(), true, true) {} }
        else coordinator.session()?.let { worker { coordinator.close(it, false, true) {} } }
        source.close(); drain()
        FactoryBrowserCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available()); root.deleteRecursively()
    }
    private fun <T> worker(block: () -> T): T {
        val executor = FactoryBrowserCoordinator.WORKER; executor.prestartCoreThread(); val task = FutureTask(Callable(block))
        check(executor.queue.offer(task, 5, TimeUnit.SECONDS)); return task.get(5, TimeUnit.SECONDS)
    }
    private fun drain() { worker {}; shadowOf(Looper.getMainLooper()).idle() }
    private fun request() = Intent(context, FactoryBrowserRecordingActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("nonce", nonce); putString("url", FactoryBrowserTestPackages.URL); putBinder("control", source)
    })
    private fun launch(intent: Intent = request(), saved: Bundle? = null, caller: String? = proof.appId): ActivityController<FactoryBrowserRecordingActivity> {
        val controller = Robolectric.buildActivity(FactoryBrowserRecordingActivity::class.java, intent); controllers += controller
        caller?.let { shadowOf(controller.get()).setCallingPackage(it) }
        controller.create(saved).start().resume().visible(); controller.get().onWindowFocusChanged(true)
        drain(); return controller
    }
    private fun recovery() = launch(FactoryBrowserCoordinator.recoveryIntent(context), caller = null)
    private fun button(activity: FactoryBrowserActivity, name: String) = activity.window.decorView.findViewWithTag<Button>("factory-browser-$name")
    private fun choose(activity: FactoryBrowserActivity) {
        val choice = activity.window.decorView.findViewWithTag<RadioButton>(fixture.component.flattenToString())
        assertNotNull("Verified browser must be offered as a native choice", choice); assertTrue(choice.isEnabled); choice.performClick()
    }
    private fun render(activity: FactoryBrowserActivity) = FactoryBrowserActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun wrapRevocation(value: FactoryBrowserCoordinator.Session, around: (Runnable) -> Unit) {
        val registration = value.registration!!
        val action = registration.javaClass.getDeclaredField("action").apply { isAccessible = true }
        val original = action.get(registration) as Runnable; action.set(registration, Runnable { around(original) })
    }
    @Test fun fullUrlSecureNativeReviewRequiresSelectionAndNeverAutolaunches() {
        val activity = launch().get(); val value = coordinator.session()!!
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available()); assertFalse(value.attempted)
        val text = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(text.any { it.contains(FactoryBrowserTestPackages.URL) && it.contains("example.invalid") && it.contains(proof.appId) })
        assertFalse(button(activity, "open").isEnabled); assertTrue(button(activity, "close").isEnabled)
        for (name in listOf("open", "close")) { assertTrue(button(activity, name).filterTouchesWhenObscured); assertFalse(button(activity, name).isSaveEnabled) }
        val choice = activity.window.decorView.findViewWithTag<RadioButton>(fixture.component.flattenToString())
        assertTrue(choice.filterTouchesWhenObscured); assertFalse(choice.isSaveEnabled); assertFalse(choice.isChecked)
        assertEquals(0, activity.launches.size); assertNull(shadowOf(activity).nextStartedActivity)
        choose(activity); assertTrue(button(activity, "open").isEnabled); assertEquals(0, activity.launches.size)
    }
    @Test fun explicitOpenPersistsPendingBeforeOneExactExternalLaunchAndNeverClaimsLoad() {
        val activity = launch().get(); choose(activity)
        activity.beforeStart = {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            val journal = JSONObject(File(root, "interaction.json").readText())
            assertEquals("launch_pending", journal.getString("state")); assertTrue(journal.getBoolean("open")); assertFalse(journal.toString().contains(FactoryBrowserTestPackages.URL))
            assertTrue(coordinator.session()!!.attempted); assertFalse(coordinator.session()!!.launchRequested)
        }
        button(activity, "open").performClick(); drain()
        val exact = activity.launches.single(); assertEquals(fixture.component, exact.component); assertEquals(FactoryBrowserTestPackages.URL, exact.dataString)
        assertEquals(Intent.ACTION_VIEW, exact.action); assertEquals(setOf(Intent.CATEGORY_BROWSABLE), exact.categories)
        assertEquals(0, exact.flags); assertNull(exact.extras); assertNull(exact.selector); assertNull(exact.clipData)
        assertTrue(coordinator.session()!!.launchRequested); assertFalse(button(activity, "open").isEnabled)
        button(activity, "open").performClick(); drain(); assertEquals(1, activity.launches.size)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode); assertNull(shadowOf(activity).resultIntent)
        button(activity, "close").performClick(); drain()
        val receipt = shadowOf(activity).resultIntent; assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals(setOf("nonce", "launchRequested", "pageLoadConfirmed"), receipt.extras!!.keySet()); assertEquals(nonce, receipt.getStringExtra("nonce"))
        assertTrue(receipt.getBooleanExtra("launchRequested", false)); assertFalse(receipt.getBooleanExtra("pageLoadConfirmed", true))
    }
    @Test fun registrationDisposalCallbackBeforeCloseCompletionCannotEraseValidReceipt() {
        val activity = launch().get(); choose(activity); button(activity, "open").performClick(); drain()
        val callbackPosted = CountDownLatch(1); val finishClose = CountDownLatch(1)
        wrapRevocation(coordinator.session()!!) { original -> original.run(); callbackPosted.countDown(); check(finishClose.await(10, TimeUnit.SECONDS)) }
        try {
            button(activity, "close").performClick(); assertTrue(callbackPosted.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode); assertFalse(activity.isFinishing)
        } finally { finishClose.countDown() }
        drain()
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode); assertTrue(shadowOf(activity).resultIntent.getBooleanExtra("launchRequested", false))
        assertFalse(shadowOf(activity).resultIntent.getBooleanExtra("pageLoadConfirmed", true)); assertEquals("closed", coordinator.status())
    }
    @Test fun nativeCloseBeforeConsentReturnsFalseReceiptAndNoExternalLaunch() {
        val activity = launch().get(); button(activity, "close").performClick(); drain()
        assertEquals(0, activity.launches.size); assertEquals("closed", coordinator.status()); assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val receipt = shadowOf(activity).resultIntent; assertEquals(nonce, receipt.getStringExtra("nonce"))
        assertFalse(receipt.getBooleanExtra("launchRequested", true)); assertFalse(receipt.getBooleanExtra("pageLoadConfirmed", true))
    }
    @Test fun throwingNativeLaunchConsumesAttemptAndCannotRetryOrClaimSuccess() {
        val activity = launch().get(); choose(activity); activity.beforeStart = { throw android.content.ActivityNotFoundException("Synthetic recipient vanished") }
        button(activity, "open").performClick(); drain()
        assertEquals(1, activity.launches.size); assertTrue(coordinator.session()!!.attempted); assertFalse(coordinator.session()!!.launchRequested); assertTrue(coordinator.needsRecovery())
        button(activity, "open").performClick(); drain(); assertEquals(1, activity.launches.size)
        button(activity, "close").performClick(); drain(); assertEquals("closed_outcome_unknown", coordinator.status())
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode); assertNull(shadowOf(activity).resultIntent)
    }
    @Test fun sourceCancellationBeforeConsentRevokesWithoutAnyNativeLaunch() {
        val activity = launch().get(); choose(activity); val revoked = CountDownLatch(1)
        wrapRevocation(coordinator.session()!!) { it.run(); revoked.countDown() }
        source.cancel(); assertTrue(revoked.await(5, TimeUnit.SECONDS)); shadowOf(Looper.getMainLooper()).idle()
        button(activity, "open").performClick(); drain()
        assertTrue(coordinator.needsRecovery()); assertFalse(button(activity, "open").isEnabled); assertEquals(0, activity.launches.size)
    }
    @Test fun changedLatestSourceProofPreventsDispatchAfterHumanConsent() {
        val activity = launch().get(); choose(activity); proof = proof.copy(record = "f".repeat(64))
        button(activity, "open").performClick(); drain(); assertEquals(0, activity.launches.size)
        assertFalse(coordinator.session()!!.attempted); assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
    }
    @Test fun changedRecipientCertificatePreventsDispatchWithoutReplacingNativeChoice() {
        val activity = launch().get(); choose(activity); fixture.signatures(arrayOf(android.content.pm.Signature(byteArrayOf(9, 9, 9))))
        button(activity, "open").performClick(); drain(); assertEquals(0, activity.launches.size); assertTrue(coordinator.needsRecovery())
    }
    @Test fun focusLossCannotBeUndoneByRegainingFocus() {
        val activity = launch().get(); choose(activity); activity.onWindowFocusChanged(false)
        assertFalse(button(activity, "open").isEnabled); assertFalse(button(activity, "close").isEnabled)
        activity.onWindowFocusChanged(true); button(activity, "open").performClick(); drain()
        assertEquals(0, activity.launches.size); assertTrue(coordinator.needsRecovery()); assertFalse(button(activity, "open").isEnabled)
    }
    @Test fun pauseDuringBlockedFinalRecipientVerificationPreventsLaunchAndKeepsRecoveryBusy() = blockedFinalVerification("pause")
    @Test fun focusLossDuringBlockedFinalRecipientVerificationPreventsLaunch() = blockedFinalVerification("focus")
    @Test fun sourceCancellationDuringBlockedFinalRecipientVerificationPreventsLaunch() = blockedFinalVerification("cancel")
    private fun blockedFinalVerification(change: String) {
        val controller = launch(); val activity = controller.get(); choose(activity)
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val exactQueries = AtomicInteger(); val cancelled = CountDownLatch(1)
        wrapRevocation(coordinator.session()!!) { it.run(); cancelled.countDown() }
        FactoryBrowserPackageManagerShadow.beforeQuery = { intent ->
            if (intent.`package` == fixture.packageName && intent.dataString == FactoryBrowserTestPackages.URL && exactQueries.incrementAndGet() == 2) {
                assertTrue(Looper.myLooper() != Looper.getMainLooper()); entered.countDown(); check(finish.await(10, TimeUnit.SECONDS))
            }
        }
        var recoveryActivity: FactoryBrowserRecordingActivity? = null
        try {
            button(activity, "open").performClick(); assertTrue("Final native package query was not reached", entered.await(5, TimeUnit.SECONDS))
            val value = coordinator.session()!!; assertTrue(value.preparing); assertTrue(value.attempted)
            when (change) { "pause" -> controller.pause(); "focus" -> activity.onWindowFocusChanged(false); "cancel" -> { source.cancel(); assertTrue(cancelled.await(5, TimeUnit.SECONDS)) } }
            assertTrue(value.revoked.get()); assertEquals(0, activity.launches.size); assertFalse(FactoryInteractionAdmission.available())
            // Do not drain the worker while it is intentionally blocked.
            val next = Robolectric.buildActivity(FactoryBrowserRecordingActivity::class.java, FactoryBrowserCoordinator.recoveryIntent(context)); controllers += next
            next.create().start().resume().visible(); next.get().onWindowFocusChanged(true); recoveryActivity = next.get()
            assertFalse(button(next.get(), "close").isEnabled); button(next.get(), "close").performClick()
            assertTrue(value.preparing); assertFalse(FactoryInteractionAdmission.available())
        } finally { finish.countDown(); FactoryBrowserPackageManagerShadow.beforeQuery = null }
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
    @Test fun destroyedReviewAndRecreatedIntentNeverRehydrateOrReplayAuthority() {
        val first = launch(); val activity = first.get(); choose(activity); first.pause().stop().destroy(); controllers.remove(first)
        val next = launch(saved = Bundle()).get(); assertTrue(next.isFinishing); assertEquals(0, next.launches.size)
        assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
        val recovered = recovery().get(); assertFalse(button(recovered, "open").isEnabled); button(recovered, "close").performClick(); drain()
        assertEquals(Activity.RESULT_CANCELED, shadowOf(recovered).resultCode); assertNull(shadowOf(recovered).resultIntent)
    }
    @Test fun backAfterDispatchRetainsUnknownOutcomeAndNeverClaimsBrowserClosure() {
        val activity = launch().get(); choose(activity); button(activity, "open").performClick(); drain()
        activity.onBackPressedDispatcher.onBackPressed(); drain()
        assertTrue(activity.isFinishing); assertTrue(coordinator.needsRecovery()); assertTrue(MemoryUiAutomationGuard.isProtected())
        assertFalse(FactoryInteractionAdmission.available()); assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode); assertNull(shadowOf(activity).resultIntent)
        assertEquals(1, activity.launches.size)
    }
    @Test fun externalBrowserLifecycleDoesNotAutoCloseOrTurnDispatchIntoPageLoad() {
        val controller = launch(); val activity = controller.get(); choose(activity); button(activity, "open").performClick(); drain()
        activity.onWindowFocusChanged(false); controller.pause().resume(); activity.onWindowFocusChanged(true); drain()
        assertFalse(coordinator.session()!!.revoked.get()); assertFalse(button(activity, "open").isEnabled); assertTrue(button(activity, "close").isEnabled)
        assertFalse(FactoryInteractionAdmission.available()); assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        button(activity, "close").performClick(); drain(); assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertFalse(shadowOf(activity).resultIntent.getBooleanExtra("pageLoadConfirmed", true)); assertEquals(1, activity.launches.size)
    }
    @Test fun expiredReviewNeverLaunchesAndOnlyExplicitRecoveryReleasesAdmission() {
        val activity = launch().get(); choose(activity)
        val expire = FactoryBrowserActivity::class.java.getDeclaredField("expire").apply { isAccessible = true }.get(activity) as Runnable
        expire.run(); button(activity, "open").performClick(); drain()
        assertEquals(0, activity.launches.size); assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
        button(activity, "close").performClick(); drain(); assertTrue(FactoryInteractionAdmission.available()); assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }
    @Test fun saturatedWorkerRejectsUnstartedPreparationWithoutClearingAdmission() {
        val activity = launch().get(); choose(activity); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = FactoryBrowserCoordinator.WORKER; executor.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); executor.execute { }
            button(activity, "open").performClick(); assertFalse(coordinator.session()!!.preparing); assertFalse(coordinator.session()!!.attempted)
            assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available()); assertEquals(0, activity.launches.size)
        } finally { release.countDown() }
        drain(); button(activity, "close").performClick(); drain(); assertEquals("closed_outcome_unknown", coordinator.status())
    }
}

/** No real Activity dispatch: preserve host logic up to the final platform effect. */
class FactoryBrowserRecordingActivity : FactoryBrowserActivity() {
    val launches = mutableListOf<Intent>()
    var beforeStart: (() -> Unit)? = null
    override fun startActivity(intent: Intent) { launches += Intent(intent); beforeStart?.invoke() }
}

/** Hooks may stall public queries; resolution itself always delegates to Robolectric PM. */
@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
class FactoryBrowserPackageManagerShadow : ShadowApplicationPackageManager() {
    @Implementation override fun queryIntentActivities(intent: Intent, flags: Int): MutableList<ResolveInfo> {
        beforeQuery?.invoke(intent); return super.queryIntentActivities(intent, flags)
    }
    companion object { @Volatile var beforeQuery: ((Intent) -> Unit)? = null }
}
