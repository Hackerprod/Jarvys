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
import android.os.Parcel
import android.provider.ContactsContract
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Production native owner and callback handlers. Only OS dispatch, permissions, and contacts are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FactoryContactFixtureApplication::class, shadows = [FactoryFileShareOsShadow::class])
class FactoryContactActivityTest {
    private val context: FactoryContactFixtureApplication get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-contacts")
    private var proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryContactCoordinator
    private lateinit var fixture: FactoryContactTestPackages.Fixture
    private lateinit var source: ExternalLaunchControl
    private val verifiedCapabilities = mutableListOf<String>()
    private val controllers = mutableListOf<ActivityController<FactoryContactRecordingActivity>>()
    private val nonce = "d".repeat(64)
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup(); root.deleteRecursively(); context.resetContactPermissions()
        coordinator = FactoryContactCoordinator(context) { appId, capability ->
            check(appId == proof.appId); check(capability == "contacts"); verifiedCapabilities += capability; proof
        }
        FactoryContactCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
        fixture = FactoryContactTestPackages.install(context)
        val uid = Binder.getCallingUid(); val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply { packageName = proof.appId; applicationInfo = ApplicationInfo().apply { packageName = proof.appId; this.uid = uid; enabled = true } })
        pm.setPackagesForUid(uid, proof.appId); source = ExternalLaunchControl(nonce) { caller -> caller == uid }
    }
    @After fun cleanup() {
        fixture.provider.onQuery = null; drain()
        controllers.asReversed().forEach { runCatching { it.pause().stop().destroy() } }
        shadowOf(Looper.getMainLooper()).idle(); drain()
        coordinator.session()?.preparing = false
        if (coordinator.needsRecovery()) worker { coordinator.close(coordinator.session(), true, true) {} }
        else coordinator.session()?.let { worker { coordinator.close(it, false, true) {} } }
        source.close(); drain()
        FactoryContactCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        root.deleteRecursively(); context.resetContactPermissions()
    }
    private fun <T> worker(block: () -> T): T {
        val executor = FactoryContactCoordinator.WORKER; executor.prestartCoreThread(); val task = FutureTask(Callable(block))
        check(executor.queue.offer(task, 5, TimeUnit.SECONDS)); return task.get(5, TimeUnit.SECONDS)
    }
    private fun drain() { worker {}; shadowOf(Looper.getMainLooper()).idle() }
    private fun request(kind: String = "phone", control: IBinder = source) = Intent(context, FactoryContactRecordingActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("nonce", nonce); putString("kind", kind); putBinder("control", control)
    })
    private fun launch(intent: Intent = request(), saved: Bundle? = null, caller: String? = proof.appId): ActivityController<FactoryContactRecordingActivity> {
        val controller = Robolectric.buildActivity(FactoryContactRecordingActivity::class.java, intent); controllers += controller
        caller?.let { shadowOf(controller.get()).setCallingPackage(it) }
        controller.create(saved).start().resume().visible(); controller.get().onWindowFocusChanged(true); drain(); return controller
    }
    private fun button(activity: FactoryContactActivity, name: String) = activity.window.decorView.findViewWithTag<Button>("factory-contacts-$name")
    private fun value(activity: FactoryContactActivity) = activity.window.decorView.findViewWithTag<TextView>("factory-contacts-value")
    private fun choose(activity: FactoryContactRecordingActivity) { assertTrue(button(activity, "choose").isEnabled); button(activity, "choose").performClick(); drain(); assertEquals(1, activity.launches.size) }
    private fun callback(activity: FactoryContactActivity, data: Intent? = FactoryContactTestPackages.result(), code: Int = Activity.RESULT_OK, requestCode: Int = FactoryContactActivity.EXTERNAL) {
        shadowOf(activity).callOnActivityResult(requestCode, code, data)
    }
    private fun selected(kind: String = "phone", datum: String = FactoryContactCoordinatorTest.PHONE): ActivityController<FactoryContactRecordingActivity> {
        fixture.provider.rows = listOf(arrayOf(FactoryContactSelection.itemType(kind), datum))
        val controller = launch(request(kind)); choose(controller.get()); callback(controller.get()); drain(); return controller
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun assertNoReturnedData(activity: FactoryContactActivity) {
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode); assertNull(shadowOf(activity).resultIntent)
    }
    @Test fun nativeReviewProtectsCaptureSavingAutofillAndExposesOnlyHumanControls() {
        val activity = launch().get(); assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val column = views(activity.window.decorView).filterIsInstance<PrivateReviewColumn>().single()
        assertFalse(column.isSaveEnabled); assertFalse(column.isSaveFromParentEnabled)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, column.importantForAutofill)
        assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, column.importantForContentCapture)
        assertNotEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, column.importantForAccessibility)
        for (name in listOf("choose", "use", "close")) {
            val control = button(activity, name); assertTrue(control.filterTouchesWhenObscured); assertFalse(control.isSaveEnabled); assertFalse(control.isSaveFromParentEnabled)
        }
        assertTrue(button(activity, "choose").isEnabled); assertFalse(button(activity, "use").isEnabled)
        assertTrue(activity.launches.isEmpty()); assertTrue(fixture.provider.queries.isEmpty()); assertNoReturnedData(activity)
    }
    @Test fun phoneSelectionShowsExactFullValueAndNeedsSeparateNativeApproval() = approval("phone", FactoryContactCoordinatorTest.PHONE)
    @Test fun emailSelectionShowsExactFullValueAndNeedsSeparateNativeApproval() = approval("email", FactoryContactCoordinatorTest.EMAIL)
    private fun approval(kind: String, datum: String) {
        context.uriReadGranted = true; val activity = selected(kind, datum).get()
        assertEquals(datum, value(activity).text.toString()); assertNull(value(activity).ellipsize); assertEquals(0, value(activity).autoLinkMask)
        assertFalse(value(activity).isSaveEnabled); assertFalse(value(activity).isSaveFromParentEnabled)
        assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains(proof.appId) && it.text.contains(proof.apk) })
        assertFalse(button(activity, "choose").isEnabled); assertTrue(button(activity, "use").isEnabled); assertNoReturnedData(activity)
        assertEquals("selected", coordinator.status()); assertEquals(1, fixture.provider.queries.size)
        val raw = File(root, "interaction.json").readText(); assertFalse(raw.contains(datum)); assertFalse(raw.contains(FactoryContactCoordinatorTest.ROW_URI)); assertFalse(raw.contains(proof.appId))
        button(activity, "use").performClick(); drain()
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode); val result = shadowOf(activity).resultIntent
        assertEquals(setOf("nonce", "kind", "value"), result.extras!!.keySet()); assertEquals(nonce, result.getStringExtra("nonce")); assertEquals(kind, result.getStringExtra("kind")); assertEquals(datum, result.getStringExtra("value"))
        assertNull(result.data); assertNull(result.clipData); assertEquals(0, result.flags)
        assertEquals(List(5) { "contacts" }, verifiedCapabilities); assertEquals("closed", coordinator.status())
    }
    @Test fun maximumLengthValueIsCompleteAndCannotLeakThroughAutofillOrSavedState() {
        context.uriReadGranted = true; val datum = "Fixture:" + "x".repeat(248); val controller = selected(datum = datum); val activity = controller.get()
        assertEquals(256, datum.length); assertEquals(datum, value(activity).text.toString()); assertNull(value(activity).ellipsize)
        val column = views(activity.window.decorView).filterIsInstance<PrivateReviewColumn>().single()
        for (flags in listOf(0, View.AUTOFILL_FLAG_INCLUDE_NOT_IMPORTANT_VIEWS)) {
            val structure = FactoryEditorRecordingViewStructure(); column.dispatchProvideAutofillStructure(structure, flags)
            assertEquals(listOf("setAutofillId"), structure.calls); assertEquals(column.autofillId, structure.recordedId)
        }
        val saved = Bundle(); controller.saveInstanceState(saved); val parcel = Parcel.obtain()
        try { parcel.writeBundle(saved); val bytes = parcel.marshall(); assertFalse(String(bytes, Charsets.UTF_16LE).contains(datum)); assertFalse(String(bytes, Charsets.UTF_8).contains(datum)) } finally { parcel.recycle() }
        assertNoReturnedData(activity)
    }
    @Test fun durableLaunchPendingPrecedesOneExactPinnedSystemPickerDispatch() {
        val activity = launch().get()
        activity.beforeStart = {
            val saved = JSONObject(File(root, "interaction.json").readText())
            assertEquals("launch_pending", saved.getString("state")); assertTrue(saved.getBoolean("open"))
            assertEquals(setOf("schemaVersion", "state", "open", "nonce"), saved.keys().asSequence().toSet())
            assertTrue(coordinator.session()!!.attempted); assertFalse(coordinator.session()!!.pickerReturned)
        }
        choose(activity); val (intent, requestCode) = activity.launches.single()
        assertEquals(Intent.ACTION_PICK, intent.action); assertEquals(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE, intent.type)
        assertEquals(fixture.picker.component, intent.component); assertEquals(FactoryContactActivity.EXTERNAL, requestCode)
        assertNull(intent.data); assertNull(intent.extras); assertNull(intent.clipData); assertNull(intent.selector); assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertTrue(fixture.provider.queries.isEmpty()); assertNoReturnedData(activity)
    }
    @Test fun unsolicitedCallbackWithAmbientContactsPermissionMakesZeroQueries() {
        context.contactsReadGranted = true; val activity = launch().get()
        callback(activity, FactoryContactTestPackages.result(flags = 0)); drain()
        assertTrue(fixture.provider.queries.isEmpty()); assertTrue(activity.launches.isEmpty()); assertEquals("review", coordinator.status()); assertNoReturnedData(activity)
        assertFalse(button(activity, "use").isEnabled)
    }
    @Test fun wrongRequestCodeWithAmbientPermissionCannotConsumeOwnedPicker() {
        context.contactsReadGranted = true; val activity = launch().get(); choose(activity)
        callback(activity, FactoryContactTestPackages.result(flags = 0), requestCode = FactoryContactActivity.EXTERNAL + 1); drain()
        assertTrue(fixture.provider.queries.isEmpty()); assertFalse(coordinator.session()!!.pickerReturned)
        callback(activity, FactoryContactTestPackages.result(flags = 0)); drain()
        assertEquals(1, fixture.provider.queries.size); assertTrue(button(activity, "use").isEnabled); assertNoReturnedData(activity)
    }
    @Test fun ownedResultWithExistingReadContactsAndNoUriGrantCanReachExactReview() {
        context.contactsReadGranted = true; val activity = launch().get(); choose(activity)
        callback(activity, FactoryContactTestPackages.result(flags = 0)); drain()
        assertEquals(FactoryContactCoordinatorTest.PHONE, value(activity).text.toString()); assertEquals(1, fixture.provider.queries.size)
        assertEquals(FactoryContactCoordinatorTest.ROW_URI, fixture.provider.queries.single().uri.toString()); assertNoReturnedData(activity)
    }
    @Test fun ownedResultWithoutActualReadAccessMakesZeroQueriesAndNeedsRecovery() {
        val activity = launch().get(); choose(activity); callback(activity); drain()
        assertTrue(fixture.provider.queries.isEmpty()); assertTrue(coordinator.needsRecovery()); assertFalse(button(activity, "use").isEnabled); assertNoReturnedData(activity)
    }
    @Test fun cancelledCallbackConsumesOwnershipAndLaterSuccessCannotQueryEvenWithAmbientPermission() {
        context.contactsReadGranted = true; val activity = launch().get(); choose(activity)
        callback(activity, data = null, code = Activity.RESULT_CANCELED); drain()
        callback(activity, FactoryContactTestPackages.result(flags = 0)); drain()
        assertTrue(fixture.provider.queries.isEmpty()); assertTrue(coordinator.session()!!.pickerReturned); assertFalse(button(activity, "use").isEnabled)
        button(activity, "close").performClick(); drain(); assertNoReturnedData(activity); assertEquals("closed", coordinator.status())
    }
    @Test fun nullSuccessfulCallbackConsumesOwnershipWithoutEverQuerying() {
        context.contactsReadGranted = true; val activity = launch().get(); choose(activity)
        callback(activity, data = null); drain(); callback(activity, FactoryContactTestPackages.result(flags = 0)); drain()
        assertTrue(fixture.provider.queries.isEmpty()); assertFalse(button(activity, "use").isEnabled); assertNoReturnedData(activity)
    }
    @Test fun replayedCallbackCannotReplaceAlreadyReviewedDatumOrQueryAgain() {
        context.contactsReadGranted = true; val activity = selected().get()
        fixture.provider.rows = listOf(arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "Attacker replacement"))
        callback(activity, FactoryContactTestPackages.result("content://com.android.contacts/data/43", flags = 0)); drain()
        assertEquals(1, fixture.provider.queries.size); assertEquals(FactoryContactCoordinatorTest.PHONE, value(activity).text.toString()); assertNoReturnedData(activity)
    }
    @Test fun sourceRevocationBeforeOwnedCallbackPreventsQueryWithAmbientPermission() {
        context.contactsReadGranted = true; val activity = launch().get(); choose(activity); coordinator.session()!!.registration!!.close(); drain()
        callback(activity, FactoryContactTestPackages.result(flags = 0)); drain()
        assertTrue(fixture.provider.queries.isEmpty()); assertTrue(coordinator.needsRecovery()); assertFalse(button(activity, "use").isEnabled); assertNoReturnedData(activity)
    }
    @Test fun latestSourceProofChangedBeforeOwnedCallbackPreventsProviderQuery() {
        context.contactsReadGranted = true; val activity = launch().get(); choose(activity); proof = proof.copy(record = "f".repeat(64))
        callback(activity, FactoryContactTestPackages.result(flags = 0)); drain()
        assertTrue(fixture.provider.queries.isEmpty()); assertTrue(coordinator.needsRecovery()); assertNoReturnedData(activity)
    }
    @Test fun pendingResultWaitsForFocusedForegroundBeforeExactQuery() {
        context.contactsReadGranted = true; val controller = launch(); val activity = controller.get(); choose(activity)
        activity.onWindowFocusChanged(false); controller.pause(); callback(activity, FactoryContactTestPackages.result(flags = 0)); drain()
        assertTrue(fixture.provider.queries.isEmpty()); controller.resume(); assertTrue(fixture.provider.queries.isEmpty())
        activity.onWindowFocusChanged(true); drain(); assertEquals(1, fixture.provider.queries.size); assertTrue(button(activity, "use").isEnabled); assertNoReturnedData(activity)
    }
    @Test fun secondPauseAfterPendingCallbackDiscardsOwnershipInsteadOfReadingOnResume() {
        context.contactsReadGranted = true; val controller = launch(); val activity = controller.get(); choose(activity)
        activity.onWindowFocusChanged(false); controller.pause(); callback(activity, FactoryContactTestPackages.result(flags = 0)); drain()
        controller.resume(); controller.pause(); controller.resume(); activity.onWindowFocusChanged(true); drain()
        assertTrue(fixture.provider.queries.isEmpty()); assertFalse(button(activity, "use").isEnabled); assertNoReturnedData(activity)
    }
    @Test fun selectedDatumIsClearedOnLostFocusAndCannotReturnAfterRefocus() {
        context.uriReadGranted = true; val activity = selected().get(); activity.onWindowFocusChanged(false); drain()
        assertFalse(value(activity).text.toString().contains(FactoryContactCoordinatorTest.PHONE)); assertTrue(coordinator.needsRecovery())
        activity.onWindowFocusChanged(true); button(activity, "use").performClick(); drain()
        assertEquals(1, fixture.provider.queries.size); assertFalse(button(activity, "use").isEnabled); assertNoReturnedData(activity)
    }
    @Test fun closeAfterSelectionReturnsCancellationAndNoContactData() {
        context.uriReadGranted = true; val activity = selected().get(); button(activity, "close").performClick(); drain()
        assertEquals("closed", coordinator.status()); assertNoReturnedData(activity); assertTrue(activity.isFinishing)
    }
    @Test fun backAfterSelectionReturnsCancellationAndNoContactData() {
        context.uriReadGranted = true; val activity = selected().get(); activity.onBackPressedDispatcher.onBackPressed(); drain()
        assertEquals("closed", coordinator.status()); assertNoReturnedData(activity); assertTrue(activity.isFinishing)
    }
    @Test fun changedSourceAtSecondApprovalCannotReturnAlreadyReviewedContactData() {
        context.uriReadGranted = true; val activity = selected().get(); proof = proof.copy(certificate = "f".repeat(64))
        button(activity, "use").performClick(); drain(); assertNoReturnedData(activity); assertTrue(coordinator.needsRecovery())
    }
    @Test fun closeWhileProviderIsBlockedRetainsAdmissionUntilWorkEndsAndDiscardsData() {
        context.contactsReadGranted = true; val activity = launch().get(); choose(activity)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.provider.onQuery = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            callback(activity, FactoryContactTestPackages.result(flags = 0)); assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(coordinator.session()!!.preparing); button(activity, "close").performClick()
            assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available()); assertNoReturnedData(activity)
        } finally { release.countDown() }
        drain(); assertTrue(coordinator.needsRecovery()); assertFalse(button(activity, "use").isEnabled); assertNoReturnedData(activity)
        button(activity, "close").performClick(); drain(); assertEquals("closed_outcome_unknown", coordinator.status()); assertNoReturnedData(activity)
    }
    @Test fun savedInstanceRecreationNeverRestoresDatumOrPickerAuthority() {
        context.contactsReadGranted = true; val controller = selected(); val first = controller.get(); val saved = Bundle(); controller.saveInstanceState(saved)
        controller.pause().stop().destroy(); controllers.remove(controller); drain()
        val next = launch(saved = saved).get(); callback(next, FactoryContactTestPackages.result(flags = 0)); drain()
        assertTrue(next.isFinishing); assertTrue(next.launches.isEmpty()); assertEquals(1, fixture.provider.queries.size); assertNoReturnedData(first); assertNoReturnedData(next)
    }
    @Test fun forgedControlAndNullCallerCannotAcquirePickerOrQueryAuthority() {
        val activity = launch(request(control = Binder())).get(); assertTrue(activity.launches.isEmpty()); assertTrue(fixture.provider.queries.isEmpty()); assertNoReturnedData(activity)
        val other = launch(caller = null).get(); assertTrue(other.isFinishing); assertTrue(other.launches.isEmpty()); assertNoReturnedData(other)
    }
    @Test fun idleRecoveryCloseIsSafeAndReturnsOnlyCancellation() {
        val activity = launch(FactoryContactCoordinator.recoveryIntent(context), caller = null).get()
        button(activity, "close").performClick(); drain()
        assertTrue(activity.isFinishing); assertNoReturnedData(activity); assertTrue(fixture.provider.queries.isEmpty()); assertNull(coordinator.session())
    }
    @Test fun idleRecoveryBackIsSafeAndReturnsOnlyCancellation() {
        val activity = launch(FactoryContactCoordinator.recoveryIntent(context), caller = null).get()
        activity.onBackPressedDispatcher.onBackPressed(); drain()
        assertTrue(activity.isFinishing); assertNoReturnedData(activity); assertTrue(fixture.provider.queries.isEmpty()); assertNull(coordinator.session())
    }
}

class FactoryContactRecordingActivity : FactoryContactActivity() {
    val launches = mutableListOf<Pair<Intent, Int>>()
    var beforeStart: (() -> Unit)? = null
    @Deprecated("Synthetic for-result dispatch only")
    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        beforeStart?.invoke(); launches += Intent(intent) to requestCode
    }
}
