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
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.factory.runtime.BackupReviewControl
import com.jarvys.factory.runtime.DatabaseBackupFormat
import com.jarvys.factory.runtime.StorageBackupPlan
import com.jarvys.factory.runtime.StorageBackupFormat
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
class FactoryPreferencesBackupActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-data-backup")
    private val nonce = "d".repeat(64)
    private val proof = FactoryDataBackupPolicy.Verified(
        FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64)),
        FactoryDataBackupPolicy.Installed("synthetic.apk", "a".repeat(64), 2, Binder.getCallingUid(), 1),
        FactoryDataBackupPolicy.Installed("host.apk", "e".repeat(64), 84, 9000, 1), emptySet(), setOf("notes"), true)
    private var rejectEvidence = false
    private lateinit var coordinator: FactoryDataBackupCoordinator
    private lateinit var source: BackupReviewControl
    private lateinit var transfer: FileShareTransfer
    private lateinit var plan: StorageBackupPlan
    private val controllers = mutableListOf<ActivityController<FactoryDataBackupActivity>>()
    private val text = "Literal https://example.invalid/\u202Ehidden\u2066end"
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup(); root.deleteRecursively()
        coordinator = FactoryDataBackupCoordinator(context, currentIdentity = { check(it == proof && !rejectEvidence) }) { _, _ -> check(!rejectEvidence) { "Synthetic unknown signed receipt" }; proof }
        FactoryDataBackupCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, coordinator)
        val archive = StorageBackupFormat.create(proof.proof.appId, proof.proof.certificate, 1, JSONObject().put("notes", text))
        plan = StorageBackupPlan.create(nonce, SystemClock.elapsedRealtime() + 300000, "import", archive, "replaceSelectedKeys", "",
            "f".repeat(64), JSONObject().put("notes", true))
        source = BackupReviewControl(nonce) { true }; transfer = FileShareTransfer.reserve().complete(archive.bytes(), nonce) { true }
    }
    @After fun cleanup() {
        drain(); controllers.asReversed().forEach { runCatching { it.pause().stop().destroy() } }; drain()
        coordinator.session()?.let { it.preparing = false }
        coordinator.session()?.let { worker { coordinator.close(it) {} } }
        source.close(); transfer.close(); drain()
        FactoryDataBackupCoordinator::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available()); root.deleteRecursively()
    }
    private fun <T> worker(block: () -> T): T {
        val executor = FactoryDataBackupCoordinator.WORKER; executor.prestartCoreThread()
        val task = FutureTask(Callable(block)); check(executor.queue.offer(task, 5, TimeUnit.SECONDS)); return task.get(5, TimeUnit.SECONDS)
    }
    private fun drain() { worker {}; shadowOf(Looper.getMainLooper()).idle() }
    private fun request() = Intent(context, FactoryDataBackupActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("plan", plan.json()); putBinder("transfer", transfer); putBinder("control", source)
    })
    private fun launch(intent: Intent = request(), saved: Bundle? = null, caller: String? = proof.proof.appId): ActivityController<FactoryDataBackupActivity> {
        val controller = Robolectric.buildActivity(FactoryDataBackupActivity::class.java, intent); controllers += controller
        caller?.let { shadowOf(controller.get()).setCallingPackage(it) }
        controller.create(saved).start().resume().visible(); controller.get().onWindowFocusChanged(true); drain(); return controller
    }
    private fun <T : View> view(activity: Activity, name: String): T = activity.window.decorView.findViewWithTag("factory-data-backup-$name")
    @Test fun explicitCancelReturnsOnlyExactIdentifiers() {
        val activity = launch().get(); view<Button>(activity, "close").performClick(); drain()
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        val result = shadowOf(activity).resultIntent
        assertEquals(setOf("nonce", "planSha256"), result.extras!!.keySet()); assertEquals(nonce, result.getStringExtra("nonce"))
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
        val controller = launch(); val activity = controller.get()
        val details = view<TextView>(activity, "values")
        val consent = view<CheckBox>(activity, "acknowledgement")
        consent.performClick()
        assertEquals(View.GONE, consent.visibility); assertTrue(details.text.contains(plan.json()))
        assertFalse(details.isSaveEnabled); assertFalse(details.isSaveFromParentEnabled)
        assertFalse(consent.isSaveEnabled); assertFalse(consent.isSaveFromParentEnabled)
        val syntheticUri = Uri.parse("content://synthetic.backup/document/private-fixture")
        var providerCalls = 0
        activity.savedStateRegistry.registerSavedStateProvider("synthetic-backup-review") {
            providerCalls++
            Bundle().apply {
                putString("reviewValues", details.text.toString())
                putBoolean("approvedCheckbox", consent.isChecked)
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
        val owner = coordinator.session()
        val recreated = launch(saved = saved).get()
        assertTrue(recreated.isFinishing); assertEquals(Activity.RESULT_CANCELED, shadowOf(recreated).resultCode)
        assertSame(owner, coordinator.session()) // The non-null empty state cannot replay or adopt the review.
    }
    @Config(sdk = [26, 28, 34])
    @Test fun superclassSaveFailureStillClearsAllOutputStateAndPropagatesFailure() {
        val controller = launch(); val activity = controller.get()
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
    }
}
