package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.net.Uri
import android.os.Parcel
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.factory.runtime.BackupReviewControl
import com.jarvys.factory.runtime.DatabaseBackupFormat
import com.jarvys.factory.runtime.DatabaseBackupPlan
import com.jarvys.factory.runtime.FileShareTransfer
import org.json.JSONArray
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

/** Native synthetic UI review only; no device automation, document-provider I/O, or real app database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryDataBackupActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-data-backup")
    private val nonce = "d".repeat(64)
    private val proof = FactoryDataBackupPolicy.Verified(
        FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64)),
        FactoryDataBackupPolicy.Installed("synthetic.apk", "a".repeat(64), 2, Binder.getCallingUid(), 1),
        FactoryDataBackupPolicy.Installed("host.apk", "e".repeat(64), 83, 9000, 1), setOf("notes"))
    private var rejectEvidence = false
    private lateinit var coordinator: FactoryDataBackupCoordinator
    private lateinit var source: BackupReviewControl
    private lateinit var transfer: FileShareTransfer
    private lateinit var plan: DatabaseBackupPlan
    private val controllers = mutableListOf<ActivityController<FactoryDataBackupActivity>>()
    private val text = "Literal https://example.invalid/\u202Ehidden\u2066end"
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup(); root.deleteRecursively()
        coordinator = FactoryDataBackupCoordinator(context, currentIdentity = { check(it == proof && !rejectEvidence) }) { _, _ -> check(!rejectEvidence) { "Synthetic unknown signed receipt" }; proof }
        FactoryDataBackupCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
        val archive = DatabaseBackupFormat.create(proof.proof.appId, proof.proof.certificate, 1,
            JSONObject().put("notes", JSONObject().put("text", JSONObject().put("type", "text").put("nullable", true))),
            JSONObject().put("notes", JSONArray().put(JSONObject().put("id", "one").put("text", text))))
        plan = DatabaseBackupPlan.create(nonce, SystemClock.elapsedRealtime() + 300000, "import", archive, "replaceSelectedTables", "",
            DatabaseBackupFormat.sha256(DatabaseBackupFormat.canonical(archive.schema())), "f".repeat(64), JSONObject().put("notes", 2))
        source = BackupReviewControl(nonce) { true }; transfer = FileShareTransfer.reserve().complete(archive.bytes(), nonce) { true }
    }
    @After fun cleanup() {
        var closeFailure: Throwable? = null
        val otherFailures = mutableListOf<Throwable>()
        fun record(failure: Throwable) { if (failure !== closeFailure) otherFailures += failure }
        try {
            drain()
            controllers.asReversed().forEach { controller ->
                try { controller.pause().stop().destroy() } catch (failure: Throwable) { record(failure) }
            }
            drain()
            if (::coordinator.isInitialized) {
                coordinator.session()?.let { it.preparing = false }
                coordinator.session()?.let { owner ->
                    try { worker { coordinator.close(owner) {} } }
                    catch (failure: Throwable) { closeFailure = failure.cause ?: failure }
                }
            }
        } catch (failure: Throwable) { record(failure) }
        finally {
            if (::source.isInitialized) try { source.close() } catch (failure: Throwable) { record(failure) }
            if (::transfer.isInitialized) try { transfer.close() } catch (failure: Throwable) { record(failure) }
            try { drain() } catch (failure: Throwable) { record(failure) }

            var coordinatorClean = false
            if (::coordinator.isInitialized) {
                val needsRecovery = closeFailure != null || coordinator.session() != null ||
                    !coordinator.canBegin() || !FactoryInteractionAdmission.available(coordinator)
                if (needsRecovery) try { worker { coordinator.restore() } }
                catch (failure: Throwable) { record(failure) }
                try { assertNull(coordinator.session()) } catch (failure: Throwable) { record(failure) }
                try { assertTrue(coordinator.canBegin()) } catch (failure: Throwable) { record(failure) }
                try { assertTrue(FactoryInteractionAdmission.available(coordinator)) }
                catch (failure: Throwable) { record(failure) }
                coordinatorClean = coordinator.session() == null && coordinator.canBegin() &&
                    FactoryInteractionAdmission.available(coordinator)

                if (coordinatorClean) {
                    try {
                        FactoryDataBackupCoordinator::class.java.getDeclaredField("instance")
                            .apply { isAccessible = true }.set(null, null)
                    } catch (failure: Throwable) { record(failure) }
                }
            }
            if (coordinatorClean) {
                var globalStateClean = true
                try { assertFalse(MemoryUiAutomationGuard.isProtected()) }
                catch (failure: Throwable) { record(failure); globalStateClean = false }
                try { assertTrue(FactoryInteractionAdmission.available()) }
                catch (failure: Throwable) { record(failure); globalStateClean = false }
                if (globalStateClean) try { root.deleteRecursively() } catch (failure: Throwable) { record(failure) }
            }
        }
        val primary = closeFailure ?: otherFailures.firstOrNull()
        if (primary != null) {
            otherFailures.filter { it !== primary }.forEach(primary::addSuppressed)
            throw primary
        }
    }
    private fun <T> worker(block: () -> T): T {
        val executor = FactoryDataBackupCoordinator.WORKER; executor.prestartCoreThread()
        val task = FutureTask(Callable(block)); check(executor.queue.offer(task, 5, TimeUnit.SECONDS)); return task.get(5, TimeUnit.SECONDS)
    }
    private fun drain() { worker {}; shadowOf(Looper.getMainLooper()).idle() }
    private fun request() = Intent(context, FactoryDataBackupActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("plan", plan.json()); putBinder("transfer", transfer); putBinder("control", source)
    })
    private fun launch(
        intent: Intent = request(),
        saved: Bundle? = null,
        caller: String? = proof.proof.appId,
        focused: Boolean = true,
    ): ActivityController<FactoryDataBackupActivity> {
        val controller = Robolectric.buildActivity(FactoryDataBackupActivity::class.java, intent); controllers += controller
        caller?.let { shadowOf(controller.get()).setCallingPackage(it) }
        controller.create(saved).start().resume().visible(); controller.get().onWindowFocusChanged(focused); drain(); return controller
    }
    private fun <T : View> view(activity: Activity, name: String): T = activity.window.decorView.findViewWithTag("factory-data-backup-$name")
    private fun status(activity: Activity): TextView {
        val matches = ArrayList<View>()
        (activity.window.decorView as ViewGroup).findViewsWithText(
            matches, activity.getString(com.jarvys.agent.R.string.factory_data_backup_disclosure), View.FIND_VIEWS_WITH_TEXT)
        return checkNotNull(matches.filterIsInstance<TextView>().firstOrNull())
    }
    private fun assertCurrentBackupSurface(activity: FactoryDataBackupActivity) {
        val status = status(activity)
        val column = status.parent as ViewGroup
        assertEquals(3, column.childCount)
        assertEquals(activity.getString(com.jarvys.agent.R.string.factory_data_backup_title), (column.getChildAt(0) as TextView).text)
        assertSame(status, column.getChildAt(1))
        assertSame(view<Button>(activity, "close"), column.getChildAt(2))
        assertEquals(activity.getString(com.jarvys.agent.R.string.factory_data_backup_close), view<Button>(activity, "close").text)
        assertTrue(status.text.contains(activity.getString(com.jarvys.agent.R.string.factory_data_backup_disclosure)))
        assertFalse(status.isSaveEnabled); assertFalse(status.isSaveFromParentEnabled)
    }
    @Test fun permissionFreeImportReturnsExactReceiptWithoutUriOrGrant() {
        val activity = launch().get(); drain()
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val result = shadowOf(activity).resultIntent
        assertEquals(setOf("nonce", "planSha256", "approved"), result.extras!!.keySet())
        assertEquals(nonce, result.getStringExtra("nonce")); assertEquals(plan.sha256(), result.getStringExtra("planSha256"))
        assertTrue(result.getBooleanExtra("approved", false))
        assertNull(result.data); assertNull(result.clipData); assertEquals(0, result.flags)
    }
    @Test fun invalidTransportAndMissingCallingActivityCannotStartReview() {
        val activity = launch(request().putExtra("uri", "content://hidden/path")).get(); assertTrue(activity.isFinishing); assertNull(coordinator.session())
        val noCaller = launch(caller = null).get(); assertTrue(noCaller.isFinishing); assertNull(coordinator.session())
    }
    @Test fun inertDisplayEscapesEveryControlAndDirectionalCharacterWithoutTruncation() {
        val input = "prefix\u0000\n\u200b\u202e\u2069\u2028suffix" + "x".repeat(4096)
        val result = FactoryDataBackupActivity.inert(input)
        assertTrue(result.startsWith("prefix\\u0000\\u000a\\u200b\\u202e\\u2069\\u2028suffix"))
        assertTrue(result.endsWith("x".repeat(4096)))
    }
    @Config(sdk = [26, 28, 34])
    @Test fun superclassSaveRunsButNoPrepopulatedProviderOrViewStateSurvives() {
        val controller = launch(focused = false); val activity = controller.get()
        assertCurrentBackupSurface(activity)
        val status = status(activity)
        val owner = checkNotNull(coordinator.session())
        assertFalse(owner.preparing)
        val archive = checkNotNull(owner.archive)
        assertEquals(text, archive.data().getJSONArray("notes").getJSONObject(0).getString("text"))
        assertEquals(plan.sha256(), owner.plan.sha256())
        val syntheticUri = Uri.parse("content://synthetic.backup/document/private-fixture")
        var providerCalls = 0
        activity.savedStateRegistry.registerSavedStateProvider("synthetic-backup-review") {
            providerCalls++
            Bundle().apply {
                putString("reviewStatus", status.text.toString())
                putString("preparedPlan", owner.plan.json())
                putParcelable("selectedDocument", syntheticUri)
            }
        }
        val saved = Bundle().apply {
            putString("preexistingArchive", plan.json())
            putBoolean("preexistingConsent", true)
            putParcelable("preexistingUri", syntheticUri)
            putBundle("preexistingNested", Bundle().apply { putString("privateValue", text) })
        }
        controller.saveInstanceState(saved)
        assertEquals(1, providerCalls) // Proves ComponentActivity's real saved-state path ran.
        assertEquals(androidx.lifecycle.Lifecycle.State.CREATED, activity.lifecycle.currentState)
        assertTrue(saved.isEmpty)
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved); parcel.setDataPosition(0)
            assertTrue(checkNotNull(parcel.readBundle(activity.javaClass.classLoader)).isEmpty)
        } finally { parcel.recycle() }
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertSame(owner, coordinator.session())
        if (owner.revoked.get()) assertNull(owner.archive)
        val recreated = launch(saved = saved, focused = false).get()
        assertTrue(recreated.isFinishing); assertEquals(Activity.RESULT_CANCELED, shadowOf(recreated).resultCode)
        assertSame(owner, coordinator.session()) // Saved state cannot replay or adopt the generated-app backup.
        if (owner.revoked.get()) assertNull(owner.archive)
    }
    @Config(sdk = [26, 28, 34])
    @Test fun superclassSaveFailureStillClearsAllOutputStateAndPropagatesFailure() {
        val controller = launch(focused = false); val activity = controller.get()
        assertCurrentBackupSurface(activity)
        val owner = checkNotNull(coordinator.session())
        val archive = checkNotNull(owner.archive)
        assertEquals(text, archive.data().getJSONArray("notes").getJSONObject(0).getString("text"))
        val saved = Bundle().apply {
            putString("preexistingArchive", plan.json())
            putBoolean("preexistingConsent", true)
            putParcelable("preexistingUri", Uri.parse("content://synthetic.backup/document/private-fixture"))
        }
        var providerCalls = 0
        activity.savedStateRegistry.registerSavedStateProvider("synthetic-failing-backup-review") {
            providerCalls++
            error("Synthetic saved-state provider failure")
        }
        assertTrue(runCatching { controller.saveInstanceState(saved) }.isFailure)
        assertEquals(1, providerCalls); assertTrue(saved.isEmpty)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertSame(owner, coordinator.session())
        if (owner.revoked.get()) assertNull(owner.archive)
    }
}
