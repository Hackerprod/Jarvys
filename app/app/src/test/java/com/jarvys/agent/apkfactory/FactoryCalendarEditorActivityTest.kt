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
class FactoryCalendarEditorActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-external-launch")
    private var proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryExternalLaunchCoordinator
    private lateinit var fixture: FactoryCalendarEditorTestPackages.Fixture
    private lateinit var source: ExternalLaunchControl
    private var spec: ExternalLaunchSpec = FactoryCalendarEditorTestPackages.TIMED
    private val verifiedCapabilities = mutableListOf<String>()
    private val controllers = mutableListOf<ActivityController<FactoryExternalLaunchRecordingActivity>>()
    private val nonce = "d".repeat(64)
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup(); root.deleteRecursively()
        FactoryExternalLaunchPackageManagerShadow.beforeQuery = null
        coordinator = FactoryExternalLaunchCoordinator(context) { appId, capability ->
            check(appId == proof.appId); check(capability == spec.capability); verifiedCapabilities += capability; proof
        }
        FactoryExternalLaunchCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
        fixture = FactoryCalendarEditorTestPackages.install(context)
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
        if (coordinator.needsRecovery()) worker { coordinator.close(coordinator.session(), true, true) {} }
        else coordinator.session()?.let { worker { coordinator.close(it, false, true) {} } }
        source.close(); drain()
        FactoryExternalLaunchCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available()); root.deleteRecursively()
    }
    private fun <T> worker(block: () -> T): T {
        val executor = FactoryExternalLaunchCoordinator.WORKER; executor.prestartCoreThread(); val task = FutureTask(Callable(block))
        check(executor.queue.offer(task, 5, TimeUnit.SECONDS)); return task.get(5, TimeUnit.SECONDS)
    }
    private fun drain() { worker {}; shadowOf(Looper.getMainLooper()).idle() }
    private fun request(control: IBinder = source) = Intent(context, FactoryExternalLaunchRecordingActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("nonce", nonce); putString("method", FactoryCalendarEditorTestPackages.method(spec))
        putString("args", FactoryCalendarEditorTestPackages.args(spec)); putBinder("control", control)
    })
    private fun launch(intent: Intent = request(), saved: Bundle? = null, caller: String? = proof.appId): ActivityController<FactoryExternalLaunchRecordingActivity> {
        val controller = Robolectric.buildActivity(FactoryExternalLaunchRecordingActivity::class.java, intent); controllers += controller
        caller?.let { shadowOf(controller.get()).setCallingPackage(it) }
        controller.create(saved).start().resume().visible(); controller.get().onWindowFocusChanged(true)
        drain(); return controller
    }
    private fun recovery() = launch(FactoryExternalLaunchCoordinator.recoveryIntent(context), caller = null)
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
    @Test fun timedReviewShowsExactCalendarDataAndDraftSyncDisclosure() = reviewPrivacy(FactoryCalendarEditorTestPackages.TIMED)
    @Test fun allDayReviewShowsUtcDatesAndExclusiveEndDisclosure() = reviewPrivacy(FactoryCalendarEditorTestPackages.ALL_DAY)
    private fun reviewPrivacy(value: ExternalLaunchSpec) {
        spec = value
        fixture = FactoryCalendarEditorTestPackages.install(context, "example.revieweditor", 25001, spec)
        val activity = launch().get()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val text = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        val disclosure = text.first { it.contains("cloud draft sync") }
        assertTrue(disclosure.contains("may transmit or save")); assertTrue(disclosure.contains("never presses Send"))
        val event = spec.calendar
        val fields = listOf("calendar-title" to event.title, "calendar-location" to event.location,
            "calendar-description" to event.description, "calendar-timezone" to event.timeZone,
            "calendar-all-day" to event.allDay.toString())
        assertTrue(text.any { it.contains("never presses Save") && it.contains("exclusive end date") && it.contains("may ignore or change") })
        val sourceText = activity.window.decorView.findViewWithTag<TextView>("factory-editor-calendar-source").text.toString()
        for (identity in listOf(proof.appId, proof.version.toString(), proof.apk, proof.certificate)) assertTrue(sourceText.contains(identity))
        for ((name, millis) in listOf("start" to event.startTimeMillis, "end" to event.endTimeMillis)) {
            val rendered = activity.window.decorView.findViewWithTag<TextView>("factory-editor-calendar-$name").text.toString()
            assertTrue(rendered.contains("$millis ms (Unix)"))
            if (event.allDay) assertTrue(rendered.contains("UTC")) else assertTrue(rendered.contains("-04:00"))
        }
        val column = views(activity.window.decorView).filterIsInstance<PrivateReviewColumn>().single()
        assertFalse(column.isSaveEnabled); assertFalse(column.isSaveFromParentEnabled)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, column.importantForAutofill)
        assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, column.importantForContentCapture)
        assertNotEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, column.importantForAccessibility)
        for ((name, value) in fields) {
            val field = column.findViewWithTag<TextView>("factory-editor-$name")
            val label = column.findViewWithTag<TextView>("factory-editor-$name-label")
            assertNotNull("Native $name value is present", field); assertNotNull("Native $name label is present", label)
            assertEquals(value, field.text.toString()); assertTrue(label.text.isNotBlank())
            assertEquals(label.parent, field.parent)
            assertFalse(field.isSaveEnabled); assertFalse(field.isSaveFromParentEnabled)
            assertEquals(0, field.autoLinkMask); assertNull(field.ellipsize)
            assertNotEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, field.importantForAccessibility)
        }
        for (flags in listOf(0, View.AUTOFILL_FLAG_INCLUDE_NOT_IMPORTANT_VIEWS)) {
            val structure = FactoryEditorRecordingViewStructure()
            column.dispatchProvideAutofillStructure(structure, flags)
            assertEquals(listOf("setAutofillId"), structure.calls)
            assertEquals(column.autofillId, structure.recordedId)
        }
        assertFalse(button(activity, "open").isEnabled); assertTrue(activity.launches.isEmpty())
        choose(activity); assertTrue(button(activity, "open").isEnabled); assertTrue(activity.launches.isEmpty())
    }
    @Test fun maximumMultilineDescriptionIsFullyRenderedLastWithoutBecomingNativeLabels() {
        val description = ("Title\nStart\nTimezone\n" + "x".repeat(4096)).take(4096)
        val base = FactoryCalendarEditorTestPackages.TIMED.calendar
        spec = ExternalLaunchSpec.calendar("t".repeat(256), "l".repeat(256), description, base.startTimeMillis, base.endTimeMillis, base.timeZone, false)
        fixture = FactoryCalendarEditorTestPackages.install(context, "example.longcalendar", 25004, spec)
        val activity = launch().get()
        val field = activity.window.decorView.findViewWithTag<TextView>("factory-editor-calendar-description")
        assertEquals(description, field.text.toString()); assertNull(field.ellipsize)
        val parent = field.parent as ViewGroup
        assertSame(field, parent.getChildAt(parent.childCount - 1)); assertEquals(16, parent.childCount)
        assertEquals("Title", parent.findViewWithTag<TextView>("factory-editor-calendar-title-label").text.toString())
        assertEquals(spec.calendar.title, parent.findViewWithTag<TextView>("factory-editor-calendar-title").text.toString())
        assertEquals(spec.calendar.location, parent.findViewWithTag<TextView>("factory-editor-calendar-location").text.toString())
        assertTrue(activity.launches.isEmpty())
    }
    @Test fun explicitlyEmptyLocationAndDescriptionHaveVisibleNativeEmptyMarkers() {
        spec = ExternalLaunchSpec.calendar("Synthetic title", "", "", 0L, 3600000L, "UTC", false)
        fixture = FactoryCalendarEditorTestPackages.install(context, "example.emptycalendar", 25005, spec)
        val activity = launch().get()
        for (name in listOf("location", "description")) {
            assertEquals("(empty)", activity.window.decorView.findViewWithTag<TextView>("factory-editor-calendar-$name").text.toString())
        }
        choose(activity); button(activity, "open").performClick(); drain()
        assertEquals("", activity.launches.single().getStringExtra("eventLocation"))
        assertEquals("", activity.launches.single().getStringExtra("description"))
    }
    @Test fun timedPersistsPendingBeforeExactDispatchAndReceiptNeverClaimsSaved() = dispatch(FactoryCalendarEditorTestPackages.TIMED)
    @Test fun allDayPersistsPendingBeforeExactDispatchAndReceiptNeverClaimsSaved() = dispatch(FactoryCalendarEditorTestPackages.ALL_DAY)
    private fun dispatch(value: ExternalLaunchSpec) {
        spec = value; fixture = FactoryCalendarEditorTestPackages.install(context, "example.dispatcheditor", 25002, spec)
        val activity = launch().get(); choose(activity)
        activity.beforeStart = {
            val raw = File(root, "interaction.json").readText(); val journal = JSONObject(raw)
            assertEquals("launch_pending", journal.getString("state")); assertTrue(journal.getBoolean("open"))
            assertEquals(setOf("schemaVersion", "state", "open", "nonce"), journal.keys().asSequence().toSet())
            for (secret in listOf(spec.calendar.title, spec.calendar.location, spec.calendar.description, spec.calendar.timeZone, fixture.packageName, proof.appId)) assertFalse(raw.contains(secret))
            assertTrue(coordinator.session()!!.attempted); assertFalse(coordinator.session()!!.launchRequested)
        }
        button(activity, "open").performClick(); drain()
        val exact = activity.launches.single()
        FactoryCalendarEditorTestPackages.assertExact(exact, spec)
        assertEquals(fixture.component, exact.component)
        button(activity, "open").performClick(); drain(); assertEquals(1, activity.launches.size)
        button(activity, "close").performClick(); drain()
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val receipt = shadowOf(activity).resultIntent
        assertEquals(setOf("nonce", "launchRequested", "actionConfirmed"), receipt.extras!!.keySet())
        assertTrue(receipt.getBooleanExtra("launchRequested", false)); assertFalse(receipt.getBooleanExtra("actionConfirmed", true))
        assertEquals(List(3) { spec.capability }, verifiedCapabilities)
    }
    @Test fun changedCalendarRecipientCertificatePreventsDispatch() {
        val activity = launch().get(); choose(activity)
        fixture.signatures(arrayOf(android.content.pm.Signature(byteArrayOf(9, 9, 9))))
        button(activity, "open").performClick(); drain()
        assertTrue(activity.launches.isEmpty()); assertTrue(coordinator.needsRecovery())
    }
    @Test fun changedCalendarLatestSourceProofPreventsDispatch() {
        spec = FactoryCalendarEditorTestPackages.ALL_DAY; fixture = FactoryCalendarEditorTestPackages.install(context, "example.sourceeditor", 25003, spec)
        val activity = launch().get(); choose(activity); proof = proof.copy(record = "f".repeat(64))
        button(activity, "open").performClick(); drain()
        assertTrue(activity.launches.isEmpty()); assertFalse(coordinator.session()!!.attempted); assertTrue(coordinator.needsRecovery())
    }
    @Test fun missingEditorOffersNoFallbackAndNativeCloseReturnsFalseReceipt() {
        shadowOf(context.packageManager).setResolveInfosForIntent(FactoryCalendarEditorTestPackages.view(spec, true), emptyList())
        val activity = launch().get(); assertFalse(button(activity, "open").isEnabled)
        button(activity, "close").performClick(); drain()
        assertTrue(activity.launches.isEmpty()); assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertFalse(shadowOf(activity).resultIntent.getBooleanExtra("launchRequested", true))
        assertFalse(shadowOf(activity).resultIntent.getBooleanExtra("actionConfirmed", true))
    }
    @Test fun lostEditorAfterAttemptCannotReplayOrClaimSaved() {
        val activity = launch().get(); choose(activity)
        activity.beforeStart = { throw android.content.ActivityNotFoundException("Synthetic editor disappeared") }
        button(activity, "open").performClick(); drain()
        assertTrue(coordinator.needsRecovery()); assertTrue(coordinator.session()!!.attempted); assertFalse(coordinator.session()!!.launchRequested)
        button(activity, "open").performClick(); drain(); assertEquals(1, activity.launches.size)
        button(activity, "close").performClick(); drain()
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode); assertNull(shadowOf(activity).resultIntent)
    }
    @Test fun backgroundBeforeApprovalRevokesCalendarWithoutDispatch() {
        val controller = launch(); val activity = controller.get(); choose(activity)
        controller.pause(); controller.resume(); activity.onWindowFocusChanged(true); drain()
        assertTrue(coordinator.needsRecovery()); assertTrue(activity.launches.isEmpty())
        assertFalse(button(activity, "open").isEnabled)
    }
    @Test fun sourceCancellationBeforeApprovalNeverDispatchesCalendar() {
        val activity = launch().get(); choose(activity); source.close(); drain()
        button(activity, "open").performClick(); drain()
        assertTrue(activity.launches.isEmpty()); assertTrue(coordinator.needsRecovery())
    }
    @Test fun calendarExpiryRevokesApprovalAndKeepsRecoveryGuard() {
        val activity = launch().get(); choose(activity)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(6)); drain()
        button(activity, "open").performClick(); drain()
        assertTrue(activity.launches.isEmpty()); assertTrue(coordinator.needsRecovery()); assertTrue(MemoryUiAutomationGuard.isProtected())
    }

}
