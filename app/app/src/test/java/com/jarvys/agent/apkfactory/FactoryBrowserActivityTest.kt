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

/** Authenticated browser handoff; Android's system resolver selects a browser when needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, shadows = [FactoryBrowserPackageManagerShadow::class, FactoryFileShareOsShadow::class])
class FactoryBrowserActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-browser")
    private val proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryBrowserCoordinator
    private lateinit var fixture: FactoryBrowserTestPackages.Fixture
    private lateinit var source: BrowserLaunchControl
    private val controllers = mutableListOf<ActivityController<FactoryBrowserRecordingActivity>>()
    private val nonce = "d".repeat(64)

    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        root.deleteRecursively()
        FactoryBrowserPackageManagerShadow.beforeQuery = null
        FactoryBrowserPackageManagerShadow.useAndroidDefault = false
        FactoryBrowserPackageManagerShadow.defaultResolve = null
        coordinator = FactoryBrowserCoordinator(context, { proof })
        FactoryBrowserCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
        fixture = FactoryBrowserTestPackages.install(context)
        val uid = Binder.getCallingUid()
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = proof.appId
            applicationInfo = ApplicationInfo().apply { packageName = proof.appId; this.uid = uid; enabled = true }
        })
        pm.setPackagesForUid(uid, proof.appId)
        source = BrowserLaunchControl(nonce) { caller -> caller == uid }
    }

    @After fun cleanup() {
        FactoryBrowserPackageManagerShadow.beforeQuery = null
        FactoryBrowserPackageManagerShadow.defaultResolve = null
        drain()
        controllers.asReversed().forEach { runCatching { it.pause().stop().destroy() } }
        shadowOf(Looper.getMainLooper()).idle()
        drain()
        coordinator.session()?.preparing = false
        coordinator.session()?.let { worker { coordinator.close(it) {} } }
        source.close()
        drain()
        FactoryBrowserCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertTrue(FactoryInteractionAdmission.available())
        root.deleteRecursively()
    }

    private fun <T> worker(block: () -> T): T {
        val executor = FactoryBrowserCoordinator.WORKER
        executor.prestartCoreThread()
        val task = FutureTask(Callable(block))
        check(executor.queue.offer(task, 5, TimeUnit.SECONDS))
        return task.get(5, TimeUnit.SECONDS)
    }

    private fun drain() { repeat(3) { worker {}; shadowOf(Looper.getMainLooper()).idle() } }

    private fun request() = Intent(context, FactoryBrowserRecordingActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1)
        putString("nonce", nonce)
        putString("url", FactoryBrowserTestPackages.URL)
        putBinder("control", source)
    })

    private fun launch(
        intent: Intent = request(),
        saved: Bundle? = null,
        caller: String? = proof.appId,
        focused: Boolean = true,
        beforeStart: (() -> Unit)? = null,
    ): ActivityController<FactoryBrowserRecordingActivity> {
        val controller = Robolectric.buildActivity(FactoryBrowserRecordingActivity::class.java, intent)
        controllers += controller
        caller?.let { shadowOf(controller.get()).setCallingPackage(it) }
        controller.get().beforeStart = beforeStart
        controller.create(saved).start().resume().visible()
        if (focused) controller.get().onWindowFocusChanged(true)
        drain()
        return controller
    }

    private fun button(activity: FactoryBrowserActivity, name: String) =
        activity.window.decorView.findViewWithTag<Button>("factory-browser-$name")

    private fun views(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun chooserPayload(activity: FactoryBrowserRecordingActivity): Intent {
        val chooser = activity.launches.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val payload = chooser.extras?.get(Intent.EXTRA_INTENT) as? Intent
        assertNotNull("A missing browser default is delegated to Android's chooser", payload)
        assertNull(payload!!.component)
        assertNull(payload.`package`)
        return payload
    }

    private fun assertReceipt(activity: FactoryBrowserRecordingActivity) {
        assertTrue(activity.isFinishing)
        assertNull(coordinator.session())
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val receipt = requireNotNull(shadowOf(activity).resultIntent)
        assertEquals(setOf("nonce", "launchRequested", "pageLoadConfirmed"), receipt.extras!!.keySet())
        assertEquals(nonce, receipt.getStringExtra("nonce"))
        assertTrue(receipt.getBooleanExtra("launchRequested", false))
        assertFalse(receipt.getBooleanExtra("pageLoadConfirmed", true))
    }

    @Test fun fullUrlIsShownSecurelyAndAutoDispatchesTheExactSystemChooserRequest() {
        val controller = launch(focused = false)
        val activity = controller.get()
        val value = requireNotNull(coordinator.session())
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertTrue(MemoryUiAutomationGuard.isProtected())
        assertFalse(value.attempted)
        val rendered = views(activity.window.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        assertTrue(rendered.contains(FactoryBrowserTestPackages.URL))
        assertTrue(rendered.contains("example.invalid"))
        assertTrue(rendered.contains(proof.appId))
        assertTrue(activity.launches.isEmpty())
        assertFalse(button(activity, "close").isEnabled)

        activity.beforeStart = {
            val raw = File(root, "interaction.json").readText()
            val journal = JSONObject(raw)
            assertEquals("launch_pending", journal.getString("state"))
            assertTrue(journal.getBoolean("open"))
            assertFalse(raw.contains(FactoryBrowserTestPackages.URL))
            assertTrue(coordinator.session()!!.attempted)
            assertFalse(coordinator.session()!!.launchRequested)
        }
        activity.onWindowFocusChanged(true)
        drain()
        val exact = chooserPayload(activity)
        assertEquals(Intent.ACTION_VIEW, exact.action)
        assertEquals(FactoryBrowserTestPackages.URL, exact.dataString)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), exact.categories)
        assertEquals(0, exact.flags)
        assertNull(exact.extras)
        assertNull(exact.selector)
        assertNull(exact.clipData)
        assertReceipt(activity)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun verifiedAndroidDefaultReceivesTheExactUrlDirectly() {
        FactoryBrowserPackageManagerShadow.useAndroidDefault = true
        FactoryBrowserPackageManagerShadow.defaultResolve = fixture.resolve
        shadowOf(context.packageManager).setResolveInfosForIntent(
            FactoryBrowserTestPackages.view(FactoryBrowserTestPackages.URL), listOf(fixture.resolve),
        )
        val activity = launch().get()
        val exact = activity.launches.single()
        assertEquals(Intent.ACTION_VIEW, exact.action)
        assertEquals(FactoryBrowserTestPackages.URL, exact.dataString)
        assertEquals(fixture.component, exact.component)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), exact.categories)
        assertEquals(0, exact.flags)
        assertNull(exact.extras)
        assertNull(exact.selector)
        assertNull(exact.clipData)
        assertReceipt(activity)
    }

    @Test fun noEligibleBrowserStillUsesTheAndroidSystemChooser() {
        shadowOf(context.packageManager).setResolveInfosForIntent(FactoryBrowserTestPackages.view(FactoryBrowserTestPackages.DISCOVERY), emptyList())
        val activity = launch().get()
        val exact = chooserPayload(activity)
        assertEquals(Intent.ACTION_VIEW, exact.action)
        assertEquals(FactoryBrowserTestPackages.URL, exact.dataString)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), exact.categories)
        assertReceipt(activity)
    }

    @Test fun unrelatedStartupOwnerDoesNotPreventAuthenticatedBrowserHandoff() {
        val other = Any()
        FactoryInteractionAdmission.restore(other)
        try {
            val activity = launch().get()
            assertTrue(activity.isFinishing)
            assertNull(coordinator.session())
            assertEquals(1, activity.launches.size)
            assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
            assertFalse(FactoryInteractionAdmission.available(other))
        } finally { FactoryInteractionAdmission.release(other) }
    }

    @Test fun obscuredCloseControlRejectsSyntheticTouchWhileActivityWaitsForFocus() {
        val activity = launch(focused = false).get()
        val close = button(activity, "close")
        assertTrue(close.filterTouchesWhenObscured)
        assertFalse(close.isSaveEnabled)
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

    @Test fun pauseDuringBlockedFinalRecipientVerificationPreventsExternalDispatch() = blockedFinalVerification("pause")
    @Test fun focusLossDuringBlockedFinalRecipientVerificationPreventsExternalDispatch() = blockedFinalVerification("focus")
    @Test fun sourceCancellationDuringBlockedFinalRecipientVerificationPreventsExternalDispatch() = blockedFinalVerification("cancel")

    private fun blockedFinalVerification(change: String) {
        FactoryBrowserPackageManagerShadow.useAndroidDefault = true
        FactoryBrowserPackageManagerShadow.defaultResolve = fixture.resolve
        shadowOf(context.packageManager).setResolveInfosForIntent(
            FactoryBrowserTestPackages.view(FactoryBrowserTestPackages.URL), listOf(fixture.resolve),
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
        FactoryBrowserPackageManagerShadow.beforeQuery = { intent ->
            if (intent.`package` == fixture.packageName && intent.dataString == FactoryBrowserTestPackages.URL && targetQueries.incrementAndGet() == 2) {
                assertTrue(Looper.myLooper() != Looper.getMainLooper())
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            activity.onWindowFocusChanged(true)
            assertTrue("Final browser verification query was not reached", entered.await(5, TimeUnit.SECONDS))
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
            FactoryBrowserPackageManagerShadow.beforeQuery = null
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

/** Records only the external dispatch; no browser app is opened. */
class FactoryBrowserRecordingActivity : FactoryBrowserActivity() {
    val launches = mutableListOf<Intent>()
    var beforeStart: (() -> Unit)? = null
    override fun startActivity(intent: Intent) { launches += Intent(intent); beforeStart?.invoke() }
}

/** Hooks can stall package queries; resolution otherwise delegates to Robolectric's package manager. */
@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
class FactoryBrowserPackageManagerShadow : ShadowApplicationPackageManager() {
    @Implementation override fun queryIntentActivities(intent: Intent, flags: Int): MutableList<ResolveInfo> {
        beforeQuery?.invoke(intent)
        return super.queryIntentActivities(intent, flags)
    }

    @Implementation override fun resolveActivity(intent: Intent, flags: Int): ResolveInfo? =
        if (useAndroidDefault) defaultResolve ?: super.resolveActivity(intent, flags) else null

    companion object {
        @Volatile var beforeQuery: ((Intent) -> Unit)? = null
        @Volatile var useAndroidDefault = false
        @Volatile var defaultResolve: ResolveInfo? = null
    }
}
