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
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.factory.contract.ExternalLaunchSpec
import com.jarvys.factory.runtime.ExternalLaunchControl
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

/** Authenticated external-action handoff; Android selects the recipient when no default exists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, shadows = [FactoryExternalLaunchPackageManagerShadow::class, FactoryFileShareOsShadow::class])
class FactoryExternalLaunchActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-external-launch")
    private val proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryExternalLaunchCoordinator
    private lateinit var fixture: FactoryExternalLaunchTestPackages.Fixture
    private lateinit var source: ExternalLaunchControl
    private var spec: ExternalLaunchSpec = FactoryExternalLaunchTestPackages.MAPS_QUERY
    private val verifiedCapabilities = mutableListOf<String>()
    private val controllers = mutableListOf<ActivityController<FactoryExternalLaunchRecordingActivity>>()
    private val nonce = "d".repeat(64)

    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        root.deleteRecursively()
        FactoryExternalLaunchPackageManagerShadow.beforeQuery = null
        FactoryExternalLaunchPackageManagerShadow.useAndroidDefault = false
        FactoryExternalLaunchPackageManagerShadow.defaultResolve = null
        coordinator = FactoryExternalLaunchCoordinator(context) { appId, capability ->
            check(appId == proof.appId)
            check(capability == spec.capability)
            verifiedCapabilities += capability
            proof
        }
        FactoryExternalLaunchCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
        fixture = FactoryExternalLaunchTestPackages.install(context)
        val uid = Binder.getCallingUid()
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = proof.appId
            applicationInfo = ApplicationInfo().apply { packageName = proof.appId; this.uid = uid; enabled = true }
        })
        pm.setPackagesForUid(uid, proof.appId)
        source = ExternalLaunchControl(nonce) { caller -> caller == uid }
    }

    @After fun cleanup() {
        FactoryExternalLaunchPackageManagerShadow.beforeQuery = null
        FactoryExternalLaunchPackageManagerShadow.defaultResolve = null
        drain()
        controllers.asReversed().forEach { runCatching { it.pause().stop().destroy() } }
        shadowOf(Looper.getMainLooper()).idle()
        drain()
        coordinator.session()?.preparing = false
        coordinator.session()?.let { worker { coordinator.close(it) {} } }
        source.close()
        drain()
        FactoryExternalLaunchCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertTrue(FactoryInteractionAdmission.available())
        root.deleteRecursively()
    }

    private fun <T> worker(block: () -> T): T {
        val executor = FactoryExternalLaunchCoordinator.WORKER
        executor.prestartCoreThread()
        val task = FutureTask(Callable(block))
        check(executor.queue.offer(task, 5, TimeUnit.SECONDS))
        return task.get(5, TimeUnit.SECONDS)
    }

    private fun drain() { repeat(3) { worker {}; shadowOf(Looper.getMainLooper()).idle() } }

    private fun request(control: IBinder = source) =
        Intent(context, FactoryExternalLaunchRecordingActivity::class.java).putExtras(Bundle().apply {
            putInt("protocolVersion", 1)
            putString("nonce", nonce)
            putString("method", FactoryExternalLaunchTestPackages.method(spec))
            putString("args", FactoryExternalLaunchTestPackages.args(spec))
            putBinder("control", control)
        })

    private fun launch(
        intent: Intent = request(),
        saved: Bundle? = null,
        caller: String? = proof.appId,
        focused: Boolean = true,
        beforeStart: (() -> Unit)? = null,
    ): ActivityController<FactoryExternalLaunchRecordingActivity> {
        val controller = Robolectric.buildActivity(FactoryExternalLaunchRecordingActivity::class.java, intent)
        controllers += controller
        caller?.let { shadowOf(controller.get()).setCallingPackage(it) }
        controller.get().beforeStart = beforeStart
        controller.create(saved).start().resume().visible()
        if (focused) controller.get().onWindowFocusChanged(true)
        drain()
        return controller
    }

    private fun button(activity: FactoryExternalActionActivity, name: String) =
        activity.window.decorView.findViewWithTag<Button>("factory-external-launch-$name")

    private fun views(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun dispatchedPayload(activity: FactoryExternalLaunchRecordingActivity, expectChooser: Boolean): Intent {
        val dispatched = activity.launches.single()
        assertEquals(expectChooser, dispatched.action == Intent.ACTION_CHOOSER)
        if (!expectChooser) return dispatched
        assertNull(dispatched.component)
        assertNull(dispatched.`package`)
        val payload = dispatched.extras?.get(Intent.EXTRA_INTENT) as? Intent
        assertNotNull("Without an Android default, the typed request goes to the system chooser", payload)
        assertNull(payload!!.component)
        assertNull(payload.`package`)
        return payload
    }

    private fun assertReceipt(activity: FactoryExternalLaunchRecordingActivity) {
        assertTrue(activity.isFinishing)
        assertNull(coordinator.session())
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val receipt = requireNotNull(shadowOf(activity).resultIntent)
        assertEquals(setOf("nonce", "launchRequested", "actionConfirmed"), receipt.extras!!.keySet())
        assertEquals(nonce, receipt.getStringExtra("nonce"))
        assertTrue(receipt.getBooleanExtra("launchRequested", false))
        assertFalse(receipt.getBooleanExtra("actionConfirmed", true))
        assertEquals(List(6) { spec.capability }, verifiedCapabilities)
    }

    @Test fun mapQueryAutoDispatchesExactTypedRequestToSystemChooser() =
        autoDispatchToChooser(FactoryExternalLaunchTestPackages.MAPS_QUERY, "example.mapquery")

    @Test fun mapCoordinatesAutoDispatchExactGeoUriWithoutNavigationClaim() =
        autoDispatchToChooser(FactoryExternalLaunchTestPackages.MAPS_COORDINATES, "example.mapcoordinates")

    @Test fun phoneAutoDispatchUsesDialActionAndNeverStartsACall() =
        autoDispatchToChooser(FactoryExternalLaunchTestPackages.PHONE, "example.dialer")

    private fun autoDispatchToChooser(value: ExternalLaunchSpec, packageName: String) {
        spec = value
        fixture = FactoryExternalLaunchTestPackages.install(context, packageName, 23000 + packageName.last().code, spec)
        val activity = launch(beforeStart = {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            val raw = File(root, "interaction.json").readText()
            val journal = JSONObject(raw)
            assertEquals("launch_pending", journal.getString("state"))
            assertTrue(journal.getBoolean("open"))
            assertFalse(raw.contains(spec.uri))
            assertTrue(coordinator.session()!!.attempted)
            assertFalse(coordinator.session()!!.launchRequested)
        }).get()

        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val rendered = views(activity.window.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        assertFalse(rendered.contains(spec.display))
        assertFalse(rendered.contains(spec.uri))
        val exact = dispatchedPayload(activity, expectChooser = true)
        assertEquals(FactoryExternalLaunchTestPackages.action(spec), exact.action)
        assertNotEquals(Intent.ACTION_CALL, exact.action)
        assertEquals(spec.uri, exact.dataString)
        assertEquals(setOf(Intent.CATEGORY_DEFAULT), exact.categories)
        assertEquals(0, exact.flags)
        assertNull(exact.extras)
        assertNull(exact.selector)
        assertNull(exact.clipData)
        assertReceipt(activity)
    }

    @Test fun verifiedAndroidDefaultReceivesExactTypedIntentDirectly() {
        FactoryExternalLaunchPackageManagerShadow.useAndroidDefault = true
        FactoryExternalLaunchPackageManagerShadow.defaultResolve = fixture.resolve
        shadowOf(context.packageManager).setResolveInfosForIntent(
            FactoryExternalLaunchTestPackages.view(spec), listOf(fixture.resolve),
        )
        val activity = launch().get()
        val exact = dispatchedPayload(activity, expectChooser = false)
        assertEquals(FactoryExternalLaunchTestPackages.action(spec), exact.action)
        assertNotEquals(Intent.ACTION_CALL, exact.action)
        assertEquals(spec.uri, exact.dataString)
        assertEquals(fixture.component, exact.component)
        assertEquals(setOf(Intent.CATEGORY_DEFAULT), exact.categories)
        assertEquals(0, exact.flags)
        assertNull(exact.extras)
        assertNull(exact.selector)
        assertNull(exact.clipData)
        assertReceipt(activity)
    }

    @Test fun missingEligibleRecipientStillDelegatesToSystemChooser() {
        shadowOf(context.packageManager).setResolveInfosForIntent(
            FactoryExternalLaunchTestPackages.view(spec, discovery = true), emptyList(),
        )
        val activity = launch().get()
        val exact = dispatchedPayload(activity, expectChooser = true)
        assertEquals(FactoryExternalLaunchTestPackages.action(spec), exact.action)
        assertEquals(spec.uri, exact.dataString)
        assertReceipt(activity)
    }

    @Test fun unrelatedStartupOwnerDoesNotBlockThisAuthenticatedRequest() {
        val other = Any()
        FactoryInteractionAdmission.restore(other)
        try {
            val activity = launch().get()
            assertTrue(activity.isFinishing)
            assertEquals(1, activity.launches.size)
            assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
            assertFalse(FactoryInteractionAdmission.available(other))
        } finally { FactoryInteractionAdmission.release(other) }
    }

    @Test fun obscuredCloseControlRejectsSyntheticTouchWhileWaitingForFocus() {
        val activity = launch(focused = false).get()
        val close = button(activity, "close")
        assertTrue(close.filterTouchesWhenObscured)
        fun event(flags: Int): android.view.MotionEvent = android.view.MotionEvent.obtain(
            0L, 0L, android.view.MotionEvent.ACTION_DOWN, 1,
            arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER }),
            arrayOf(android.view.MotionEvent.PointerCoords().apply { x = 1f; y = 1f; pressure = 1f; size = 1f }),
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, flags,
        )
        for (flags in listOf(android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED, android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) {
            val touch = event(flags)
            try { assertFalse(close.onFilterTouchEventForSecurity(touch)) } finally { touch.recycle() }
        }
        val clean = event(0)
        try { assertTrue(close.onFilterTouchEventForSecurity(clean)) } finally { clean.recycle() }
        assertTrue(activity.launches.isEmpty())
        assertFalse(coordinator.session()!!.attempted)
    }

    @Test fun pauseDuringBlockedFinalRecipientVerificationPreventsDispatchAndKeepsAttemptConsumed() = blockedFinalVerification("pause")
    @Test fun focusLossDuringBlockedFinalRecipientVerificationPreventsDispatch() = blockedFinalVerification("focus")
    @Test fun sourceCancellationDuringBlockedFinalRecipientVerificationPreventsDispatch() = blockedFinalVerification("cancel")

    private fun blockedFinalVerification(change: String) {
        FactoryExternalLaunchPackageManagerShadow.useAndroidDefault = true
        FactoryExternalLaunchPackageManagerShadow.defaultResolve = fixture.resolve
        shadowOf(context.packageManager).setResolveInfosForIntent(
            FactoryExternalLaunchTestPackages.view(spec), listOf(fixture.resolve),
        )
        val controller = launch(focused = false)
        val activity = controller.get()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val targetQueries = AtomicInteger()
        val cancelled = CountDownLatch(1)
        val value = requireNotNull(coordinator.session())
        val registration = value.registration!!
        val actionField = registration.javaClass.getDeclaredField("action").apply { isAccessible = true }
        val original = actionField.get(registration) as Runnable
        actionField.set(registration, Runnable { original.run(); cancelled.countDown() })
        FactoryExternalLaunchPackageManagerShadow.beforeQuery = { intent ->
            if (intent.`package` == fixture.packageName && intent.dataString == spec.uri && targetQueries.incrementAndGet() == 2) {
                assertTrue(Looper.myLooper() != Looper.getMainLooper())
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            activity.onWindowFocusChanged(true)
            assertTrue("Final recipient verification query was not reached", entered.await(5, TimeUnit.SECONDS))
            assertTrue(value.preparing)
            assertTrue(value.attempted)
            when (change) {
                "pause" -> controller.pause()
                "focus" -> activity.onWindowFocusChanged(false)
                "cancel" -> { source.cancel(); assertTrue(cancelled.await(5, TimeUnit.SECONDS)) }
            }
            assertTrue(value.revoked.get())
            assertTrue(activity.launches.isEmpty())
            assertFalse(FactoryInteractionAdmission.available())
        } finally {
            release.countDown()
            FactoryExternalLaunchPackageManagerShadow.beforeQuery = null
        }
        drain()
        assertTrue(activity.launches.isEmpty())
        assertFalse(value.preparing)
        assertEquals("launch_pending", coordinator.status())
        assertFalse(FactoryInteractionAdmission.available())
        worker { coordinator.close(coordinator.session()) {} }
        assertEquals("closed", coordinator.status())
        assertTrue(FactoryInteractionAdmission.available())
    }

    @Test fun unauthenticatedMalformedAndRecreatedRequestsCannotDispatch() {
        val cases = listOf(
            Triple(request(), null, null),
            Triple(request().putExtra("target", fixture.packageName), null, proof.appId),
            Triple(request(), Bundle(), proof.appId),
        )
        for ((intent, saved, caller) in cases) {
            val activity = launch(intent, saved, caller).get()
            assertTrue(activity.isFinishing)
            assertTrue(activity.launches.isEmpty())
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
            assertNull(shadowOf(activity).resultIntent)
        }
        assertEquals("idle", coordinator.status())
    }
}

/** Records the host dispatch without opening a real external application. */
class FactoryExternalLaunchRecordingActivity : FactoryExternalActionActivity() {
    val launches = mutableListOf<Intent>()
    var beforeStart: (() -> Unit)? = null
    override fun startActivity(intent: Intent) { launches += Intent(intent); beforeStart?.invoke() }
}

/** Package queries can be stalled for cancellation cases; resolution is controlled explicitly. */
@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
class FactoryExternalLaunchPackageManagerShadow : ShadowApplicationPackageManager() {
    @Implementation override fun queryIntentActivities(intent: Intent, flags: Int): MutableList<ResolveInfo> {
        beforeQuery?.invoke(intent)
        return super.queryIntentActivities(intent, flags)
    }

    @Implementation override fun resolveActivity(intent: Intent, flags: Int): ResolveInfo? =
        if (!useAndroidDefault) null else defaultResolve ?: super.resolveActivity(intent, flags)

    companion object {
        @Volatile var beforeQuery: ((Intent) -> Unit)? = null
        @Volatile var useAndroidDefault = false
        @Volatile var defaultResolve: ResolveInfo? = null
    }
}
