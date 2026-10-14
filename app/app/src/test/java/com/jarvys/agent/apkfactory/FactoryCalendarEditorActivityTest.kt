package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Authenticated calendar handoff; Android's system resolver is the only recipient selector. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, shadows = [FactoryExternalLaunchPackageManagerShadow::class, FactoryFileShareOsShadow::class])
class FactoryCalendarEditorActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-external-launch")
    private val proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryExternalLaunchCoordinator
    private lateinit var fixture: FactoryCalendarEditorTestPackages.Fixture
    private lateinit var source: ExternalLaunchControl
    private var spec: ExternalLaunchSpec = FactoryCalendarEditorTestPackages.TIMED
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
        fixture = FactoryCalendarEditorTestPackages.install(context)
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
            putString("method", FactoryCalendarEditorTestPackages.method(spec))
            putString("args", FactoryCalendarEditorTestPackages.args(spec))
            putBinder("control", control)
        })

    private fun launch(
        intent: Intent = request(),
        caller: String? = proof.appId,
        beforeStart: (() -> Unit)? = null,
    ): ActivityController<FactoryExternalLaunchRecordingActivity> {
        val controller = Robolectric.buildActivity(FactoryExternalLaunchRecordingActivity::class.java, intent)
        controllers += controller
        caller?.let { shadowOf(controller.get()).setCallingPackage(it) }
        controller.get().beforeStart = beforeStart
        controller.create().start().resume().visible()
        controller.get().onWindowFocusChanged(true)
        drain()
        return controller
    }

    private fun views(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun chooserPayload(activity: FactoryExternalLaunchRecordingActivity): Intent {
        val chooser = activity.launches.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val payload = chooser.extras?.get(Intent.EXTRA_INTENT) as? Intent
        assertNotNull("Calendar data is handed to Android's system chooser", payload)
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

    @Test fun timedAndAllDayEventsReachTheSystemChooserWithExactTypedFields() {
        listOf(FactoryCalendarEditorTestPackages.TIMED, FactoryCalendarEditorTestPackages.ALL_DAY)
            .forEachIndexed { index, value -> dispatch(value, "example.calendarhandoff$index") }
    }

    private fun dispatch(value: ExternalLaunchSpec, packageName: String) {
        spec = value
        verifiedCapabilities.clear()
        fixture = FactoryCalendarEditorTestPackages.install(context, packageName, 25000 + packageName.last().code, spec)
        val activity = launch(beforeStart = {
            val raw = File(root, "interaction.json").readText()
            val journal = JSONObject(raw)
            assertEquals("launch_pending", journal.getString("state"))
            assertTrue(journal.getBoolean("open"))
            assertEquals(setOf("schemaVersion", "state", "open", "nonce"), journal.keys().asSequence().toSet())
            for (secret in listOf(spec.calendar.title, spec.calendar.location, spec.calendar.description, spec.calendar.timeZone, fixture.packageName, proof.appId)) {
                assertFalse(raw.contains(secret))
            }
            assertTrue(coordinator.session()!!.attempted)
            assertFalse(coordinator.session()!!.launchRequested)
        }).get()

        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val rendered = views(activity.window.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        for (secret in listOf(spec.calendar.title, spec.calendar.location, spec.calendar.description, spec.calendar.timeZone)) {
            assertFalse(rendered.contains(secret))
        }

        val exact = chooserPayload(activity)
        FactoryCalendarEditorTestPackages.assertExact(exact, spec)
        assertEquals(1, activity.launches.size)
        assertReceipt(activity)
    }

    @Test fun emptyLocationAndDescriptionStayExplicitInTheSystemRequest() {
        spec = ExternalLaunchSpec.calendar("Synthetic title", "", "", 0L, 3600000L, "UTC", false)
        fixture = FactoryCalendarEditorTestPackages.install(context, "example.emptycalendar", 25005, spec)
        val activity = launch().get()
        val exact = chooserPayload(activity)
        assertEquals("", exact.getStringExtra("eventLocation"))
        assertEquals("", exact.getStringExtra("description"))
        assertReceipt(activity)
    }

    @Test fun absentCalendarHandlerStillUsesAndroidSystemChooser() {
        shadowOf(context.packageManager).setResolveInfosForIntent(FactoryCalendarEditorTestPackages.view(spec, true), emptyList())
        val activity = launch().get()
        val exact = chooserPayload(activity)
        FactoryCalendarEditorTestPackages.assertExact(exact, spec)
        assertReceipt(activity)
    }

    @Test fun unauthenticatedCallerCannotDispatchCalendarData() {
        val activity = launch(Intent(context, FactoryExternalLaunchRecordingActivity::class.java), caller = null).get()
        assertTrue(activity.isFinishing)
        assertTrue(activity.launches.isEmpty())
        assertEquals("idle", coordinator.status())
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).resultIntent)
    }
}
