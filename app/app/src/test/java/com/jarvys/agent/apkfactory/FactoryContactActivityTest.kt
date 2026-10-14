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

/** Production contact selection and callback handling; only Android dispatch and contacts are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = FactoryContactFixtureApplication::class, shadows = [FactoryFileShareOsShadow::class])
class FactoryContactActivityTest {
    private val context: FactoryContactFixtureApplication get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-contacts")
    private val proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryContactCoordinator
    private lateinit var fixture: FactoryContactTestPackages.Fixture
    private lateinit var source: ExternalLaunchControl
    private val verifiedCapabilities = mutableListOf<String>()
    private val controllers = mutableListOf<ActivityController<FactoryContactRecordingActivity>>()
    private val nonce = "d".repeat(64)

    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup()
        root.deleteRecursively()
        context.resetContactPermissions()
        coordinator = FactoryContactCoordinator(context) { appId, capability ->
            check(appId == proof.appId)
            check(capability == "contacts")
            verifiedCapabilities += capability
            proof
        }
        FactoryContactCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
        fixture = FactoryContactTestPackages.install(context)
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
        fixture.provider.onQuery = null
        drain()
        controllers.asReversed().forEach { runCatching { it.pause().stop().destroy() } }
        shadowOf(Looper.getMainLooper()).idle()
        drain()
        coordinator.session()?.preparing = false
        coordinator.session()?.let { worker { coordinator.close(it) {} } }
        source.close()
        drain()
        FactoryContactCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertTrue(FactoryInteractionAdmission.available())
        root.deleteRecursively()
        context.resetContactPermissions()
    }

    private fun <T> worker(block: () -> T): T {
        val executor = FactoryContactCoordinator.WORKER
        executor.prestartCoreThread()
        val task = FutureTask(Callable(block))
        check(executor.queue.offer(task, 5, TimeUnit.SECONDS))
        return task.get(5, TimeUnit.SECONDS)
    }

    private fun drain() { repeat(4) { worker {}; shadowOf(Looper.getMainLooper()).idle() } }

    private fun request(kind: String = "phone", control: IBinder = source) =
        Intent(context, FactoryContactRecordingActivity::class.java).putExtras(Bundle().apply {
            putInt("protocolVersion", 1)
            putString("nonce", nonce)
            putString("kind", kind)
            putBinder("control", control)
        })

    private fun launch(
        intent: Intent = request(),
        saved: Bundle? = null,
        caller: String? = proof.appId,
        beforePicker: (() -> Unit)? = null,
        focused: Boolean = true,
    ): ActivityController<FactoryContactRecordingActivity> {
        val controller = Robolectric.buildActivity(FactoryContactRecordingActivity::class.java, intent)
        controllers += controller
        caller?.let { shadowOf(controller.get()).setCallingPackage(it) }
        controller.get().beforePicker = beforePicker
        controller.create(saved).start().resume().visible()
        if (focused) controller.get().onWindowFocusChanged(true)
        drain()
        return controller
    }

    private fun button(activity: FactoryContactActivity, name: String) =
        activity.window.decorView.findViewWithTag<Button>("factory-contacts-$name")

    private fun value(activity: FactoryContactActivity) =
        activity.window.decorView.findViewWithTag<TextView>("factory-contacts-value")

    private fun choose(activity: FactoryContactRecordingActivity) {
        assertEquals("The authenticated system picker starts automatically", 1, activity.launches.size)
    }

    private fun callback(
        activity: FactoryContactActivity,
        data: Intent? = FactoryContactTestPackages.result(),
        code: Int = Activity.RESULT_OK,
        requestCode: Int = FactoryContactActivity.EXTERNAL,
    ) {
        shadowOf(activity).callOnActivityResult(requestCode, code, data)
    }

    private fun selected(kind: String = "phone", datum: String = FactoryContactCoordinatorTest.PHONE): ActivityController<FactoryContactRecordingActivity> {
        fixture.provider.rows = listOf(arrayOf(FactoryContactSelection.itemType(kind), datum))
        val controller = launch(request(kind))
        choose(controller.get())
        callback(controller.get())
        drain()
        return controller
    }

    private fun views(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun assertNoReturnedData(activity: FactoryContactActivity) {
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).resultIntent)
    }

    @Test fun authenticatedEntryAutomaticallyDispatchesTheExactSecureSystemPicker() {
        val activity = launch(beforePicker = {
            val saved = JSONObject(File(root, "interaction.json").readText())
            assertEquals("launch_pending", saved.getString("state"))
            assertTrue(saved.getBoolean("open"))
            assertEquals(setOf("schemaVersion", "state", "open", "nonce"), saved.keys().asSequence().toSet())
            assertTrue(coordinator.session()!!.attempted)
            assertFalse(coordinator.session()!!.pickerReturned)
        }).get()

        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        val (intent, requestCode) = activity.launches.single()
        assertEquals(Intent.ACTION_PICK, intent.action)
        assertEquals(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE, intent.type)
        assertEquals(fixture.picker.component, intent.component)
        assertEquals(FactoryContactActivity.EXTERNAL, requestCode)
        assertNull(intent.data)
        assertNull(intent.extras)
        assertNull(intent.clipData)
        assertNull(intent.selector)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertTrue(MemoryUiAutomationGuard.isProtected())
        assertTrue(fixture.provider.queries.isEmpty())
        assertFalse(context.contactsReadGranted)
    }

    @Test fun phoneSelectionReturnsOnlyTheValidatedSystemPickerValue() =
        selectedValue("phone", FactoryContactCoordinatorTest.PHONE)

    @Test fun emailSelectionReturnsOnlyTheValidatedSystemPickerValue() =
        selectedValue("email", FactoryContactCoordinatorTest.EMAIL)

    private fun selectedValue(kind: String, datum: String) {
        context.uriReadGranted = true
        val activity = selected(kind, datum).get()
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(1, fixture.provider.queries.size)
        assertEquals(FactoryContactCoordinatorTest.ROW_URI, fixture.provider.queries.single().uri.toString())
        assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any {
            it.text.contains(proof.appId) && it.text.contains(proof.apk)
        })
        val journal = File(root, "interaction.json").readText()
        assertFalse(journal.contains(datum))
        assertFalse(journal.contains(FactoryContactCoordinatorTest.ROW_URI))
        assertFalse(journal.contains(proof.appId))

        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val result = requireNotNull(shadowOf(activity).resultIntent)
        assertEquals(setOf("nonce", "kind", "value"), result.extras!!.keySet())
        assertEquals(nonce, result.getStringExtra("nonce"))
        assertEquals(kind, result.getStringExtra("kind"))
        assertEquals(datum, result.getStringExtra("value"))
        assertNull(result.data)
        assertNull(result.clipData)
        assertEquals(0, result.flags)
        assertTrue(verifiedCapabilities.isNotEmpty())
        assertTrue(verifiedCapabilities.all { it == "contacts" })
        assertEquals("closed", coordinator.status())
        assertTrue(activity.isFinishing)
    }

    @Test fun maximumLengthValueReturnsExactlyAndIsAbsentFromSavedState() {
        context.uriReadGranted = true
        val datum = "Fixture:" + "x".repeat(248)
        val controller = selected(datum = datum)
        val activity = controller.get()
        assertEquals(256, datum.length)
        assertEquals(datum, shadowOf(activity).resultIntent.getStringExtra("value"))
        assertFalse(value(activity).isSaveEnabled)
        assertFalse(value(activity).isSaveFromParentEnabled)
        val saved = Bundle()
        controller.saveInstanceState(saved)
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            val bytes = parcel.marshall()
            assertFalse(String(bytes, Charsets.UTF_16LE).contains(datum))
            assertFalse(String(bytes, Charsets.UTF_8).contains(datum))
        } finally { parcel.recycle() }
    }

    @Test fun durableLaunchPendingPrecedesOneExactPinnedSystemPickerDispatch() {
        val activity = launch(beforePicker = {
            val saved = JSONObject(File(root, "interaction.json").readText())
            assertEquals("launch_pending", saved.getString("state"))
            assertTrue(saved.getBoolean("open"))
            assertEquals(setOf("schemaVersion", "state", "open", "nonce"), saved.keys().asSequence().toSet())
            assertTrue(coordinator.session()!!.attempted)
            assertFalse(coordinator.session()!!.pickerReturned)
        }).get()
        val (intent, requestCode) = activity.launches.single()
        assertEquals(Intent.ACTION_PICK, intent.action)
        assertEquals(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE, intent.type)
        assertEquals(fixture.picker.component, intent.component)
        assertEquals(FactoryContactActivity.EXTERNAL, requestCode)
        assertNull(intent.data)
        assertNull(intent.extras)
        assertNull(intent.clipData)
        assertNull(intent.selector)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertTrue(fixture.provider.queries.isEmpty())
        assertNoReturnedData(activity)
    }

    @Test fun callbackWithoutAnOwnedForegroundPickerCannotQueryContacts() {
        context.contactsReadGranted = true
        val activity = launch(focused = false).get()
        callback(activity, FactoryContactTestPackages.result(flags = 0))
        drain()
        assertTrue(fixture.provider.queries.isEmpty())
        assertTrue(activity.launches.isEmpty())
        assertEquals("review", coordinator.status())
        assertNoReturnedData(activity)
    }

    @Test fun wrongRequestCodeCannotConsumePickerAndCorrectResultReadsOnce() {
        context.uriReadGranted = true
        val activity = launch().get()
        choose(activity)
        callback(activity, FactoryContactTestPackages.result(flags = 0), requestCode = FactoryContactActivity.EXTERNAL + 1)
        drain()
        assertTrue(fixture.provider.queries.isEmpty())
        assertFalse(coordinator.session()!!.pickerReturned)
        callback(activity, FactoryContactTestPackages.result(flags = 0))
        drain()
        assertEquals(1, fixture.provider.queries.size)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
    }

    @Test fun ownedUriGrantReadsOnlyTheSelectedRowWithoutBroadContactsPermission() {
        context.uriReadGranted = true
        context.contactsReadGranted = false
        val activity = launch().get()
        choose(activity)
        callback(activity, FactoryContactTestPackages.result(flags = 0))
        drain()
        assertEquals(FactoryContactCoordinatorTest.PHONE, shadowOf(activity).resultIntent.getStringExtra("value"))
        assertEquals(1, fixture.provider.queries.size)
        assertEquals(FactoryContactCoordinatorTest.ROW_URI, fixture.provider.queries.single().uri.toString())
        assertFalse(context.contactsReadGranted)
    }

    @Test fun cancelledPickerAutomaticallyClosesAndLateSuccessCannotQuery() {
        val activity = launch().get()
        choose(activity)
        callback(activity, data = null, code = Activity.RESULT_CANCELED)
        drain()
        callback(activity, FactoryContactTestPackages.result(flags = 0))
        drain()
        assertTrue(fixture.provider.queries.isEmpty())
        assertNull(coordinator.session())
        assertEquals("closed", coordinator.status())
        assertTrue(activity.isFinishing)
        assertNoReturnedData(activity)
    }

    @Test fun nullSuccessfulCallbackConsumesPickerOwnershipWithoutReading() {
        val activity = launch().get()
        choose(activity)
        callback(activity, data = null)
        drain()
        callback(activity, FactoryContactTestPackages.result(flags = 0))
        drain()
        assertTrue(fixture.provider.queries.isEmpty())
        assertNoReturnedData(activity)
    }

    @Test fun repeatedCallbackCannotReplaceDeliveredDatumOrReadAgain() {
        context.uriReadGranted = true
        val activity = selected().get()
        fixture.provider.rows = listOf(arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "Attacker replacement"))
        callback(activity, FactoryContactTestPackages.result("content://com.android.contacts/data/43", flags = 0))
        drain()
        assertEquals(1, fixture.provider.queries.size)
        assertEquals(FactoryContactCoordinatorTest.PHONE, shadowOf(activity).resultIntent.getStringExtra("value"))
    }

    @Test fun pendingResultWaitsForForegroundBeforeReadingAndReturning() {
        context.uriReadGranted = true
        val controller = launch()
        val activity = controller.get()
        choose(activity)
        activity.onWindowFocusChanged(false)
        controller.pause()
        callback(activity, FactoryContactTestPackages.result(flags = 0))
        drain()
        assertTrue(fixture.provider.queries.isEmpty())
        controller.resume()
        assertTrue(fixture.provider.queries.isEmpty())
        activity.onWindowFocusChanged(true)
        drain()
        assertEquals(1, fixture.provider.queries.size)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
    }

    @Test fun secondPauseAfterPendingCallbackDiscardsPickerOwnership() {
        context.uriReadGranted = true
        val controller = launch()
        val activity = controller.get()
        choose(activity)
        activity.onWindowFocusChanged(false)
        controller.pause()
        callback(activity, FactoryContactTestPackages.result(flags = 0))
        drain()
        controller.resume()
        controller.pause()
        controller.resume()
        activity.onWindowFocusChanged(true)
        drain()
        assertTrue(fixture.provider.queries.isEmpty())
        assertNoReturnedData(activity)
    }

    private fun interruptBlockedRead(change: (FactoryContactRecordingActivity) -> Unit) {
        context.uriReadGranted = true
        val activity = launch().get()
        choose(activity)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.provider.onQuery = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            callback(activity, FactoryContactTestPackages.result(flags = 0))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(coordinator.session()!!.preparing)
            change(activity)
            assertFalse(MemoryUiAutomationGuard.isProtected())
            assertFalse(FactoryInteractionAdmission.available(coordinator))
            assertNoReturnedData(activity)
        } finally { release.countDown() }
        drain()
        assertEquals("closed_outcome_unknown", coordinator.status())
        assertNoReturnedData(activity)
    }

    @Test fun closeDuringBlockedProviderRetainsResourcesUntilWorkActuallyEnds() {
        interruptBlockedRead { button(it, "close").performClick() }
        val activity = controllers.last().get()
        button(activity, "close").performClick()
        drain()
        assertEquals("closed_outcome_unknown", coordinator.status())
        assertNoReturnedData(activity)
    }

    @Test fun lostFocusDuringBlockedProviderDiscardsSelectedData() = interruptBlockedRead { it.onWindowFocusChanged(false) }

    @Test fun backDuringBlockedProviderDiscardsSelectedData() = interruptBlockedRead { it.onBackPressedDispatcher.onBackPressed() }

    @Test fun savedInstanceRecreationDoesNotRestorePickerAuthorityOrDatum() {
        val controller = launch()
        val first = controller.get()
        val saved = Bundle()
        controller.saveInstanceState(saved)
        controller.pause().stop().destroy()
        controllers.remove(controller)
        drain()
        val next = launch(saved = saved).get()
        callback(next, FactoryContactTestPackages.result(flags = 0))
        drain()
        assertTrue(next.isFinishing)
        assertTrue(next.launches.isEmpty())
        assertTrue(fixture.provider.queries.isEmpty())
        assertNoReturnedData(first)
        assertNoReturnedData(next)
    }

    @Test fun automationTaintedActionCannotAcquirePickerOrReadSelectedData() {
        val action = MemoryUiAutomationGuard.beginAsyncAction(MemoryUiAutomationGuard.captureAutomationEpoch())
        val activity = try { launch().get() } finally { action.close() }
        assertTrue(activity.launches.isEmpty())
        assertTrue(fixture.provider.queries.isEmpty())
        assertNoReturnedData(activity)
    }

    @Test fun forgedControlAndUnauthenticatedCallerCannotAcquirePickerOrRead() {
        val forged = launch(request(control = Binder())).get()
        assertTrue(forged.launches.isEmpty())
        assertTrue(fixture.provider.queries.isEmpty())
        assertNoReturnedData(forged)
        val unauthenticated = launch(caller = null).get()
        assertTrue(unauthenticated.isFinishing)
        assertTrue(unauthenticated.launches.isEmpty())
        assertNoReturnedData(unauthenticated)
    }
}

/** Records Android's system picker dispatch without launching a real contacts application. */
class FactoryContactRecordingActivity : FactoryContactActivity() {
    val launches = mutableListOf<Pair<Intent, Int>>()
    var beforePicker: (() -> Unit)? = null

    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        launches += Intent(intent) to requestCode
        beforePicker?.invoke()
    }
}
