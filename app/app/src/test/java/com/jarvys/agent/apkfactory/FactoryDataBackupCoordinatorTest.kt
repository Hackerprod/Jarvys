package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic archive, proof and Binder fixtures. No user data, provider writes, or signing keys. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryDataBackupCoordinatorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-data-backup")
    private val nonce = "d".repeat(64)
    private val executor = Executors.newSingleThreadExecutor()
    private val owners = mutableListOf<FactoryDataBackupCoordinator>()
    private val controls = mutableListOf<BackupReviewControl>()
    private val transfers = mutableListOf<FileShareTransfer>()
    private var proof = FactoryDataBackupPolicy.Verified(
        FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64)),
        FactoryDataBackupPolicy.Installed("synthetic.apk", "a".repeat(64), 2, Binder.getCallingUid(), 1),
        FactoryDataBackupPolicy.Installed("host.apk", "e".repeat(64), 83, 9000, 1), setOf("notes"))
    private var policyChecks = 0
    private var rejectPolicyAt = Int.MAX_VALUE
    private lateinit var coordinator: FactoryDataBackupCoordinator
    private fun <T> io(action: () -> T): T = executor.submit(Callable(action)).get(5, TimeUnit.SECONDS)
    private fun denied(action: () -> Unit) = assertTrue("Expected rejection", runCatching(action).isFailure)
    private fun make() = FactoryDataBackupCoordinator(context, currentIdentity = { policyChecks++; check(policyChecks < rejectPolicyAt && it == proof) }) { _, _ -> proof }.also { owners += it }
    private fun archive(): DatabaseBackupFormat.Archive = DatabaseBackupFormat.create(proof.proof.appId, proof.proof.certificate, 1,
        JSONObject().put("notes", JSONObject().put("text", JSONObject().put("type", "text").put("nullable", true))),
        JSONObject().put("notes", JSONArray().put(JSONObject().put("id", "one").put("text", "Synthetic only"))))
    private fun plan(operation: String = "import"): DatabaseBackupPlan {
        val a = archive()
        return DatabaseBackupPlan.create(nonce, SystemClock.elapsedRealtime() + 300000, operation, a, if (operation == "import") "insertOnly" else "export",
            if (operation == "export") "notes.json" else "", DatabaseBackupFormat.sha256(DatabaseBackupFormat.canonical(a.schema())),
            if (operation == "import") "f".repeat(64) else "", if (operation == "import") JSONObject().put("notes", 1) else JSONObject())
    }
    private fun request(p: DatabaseBackupPlan = plan(), real: Boolean = false): FactoryDataBackupCoordinator.Request {
        val control = if (real) BackupReviewControl(nonce) { true }.also { controls += it } else Binder()
        val transfer = if (real) FileShareTransfer.reserve().complete(archive().bytes(), nonce) { true }.also { transfers += it } else Binder()
        return FactoryDataBackupCoordinator.Request(p, transfer, control).copy(protocolVersion = 1)
    }
    private fun prepared(operation: String = "import"): FactoryDataBackupCoordinator.Session {
        val request = request(plan(operation), true)
        return io { coordinator.begin(proof.proof.appId, request) {}.also { coordinator.prepare(it, {}, Runnable {}) } }
    }
    private fun intent(request: FactoryDataBackupCoordinator.Request = request()) = Intent(context, FactoryDataBackupActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("plan", request.plan.json()); putBinder("transfer", request.transfer); putBinder("control", request.control)
    })
    @Before fun setup() { FactoryStartupTestIsolation.releaseCompletedSharingStartup(); root.deleteRecursively(); coordinator = make() }
    @After fun cleanup() {
        owners.asReversed().forEach { owner ->
            owner.session()?.let { it.preparing = false; it.abandoned = true }
        owner.session()?.let { io { owner.close(it) {} } }
        }
        controls.forEach { it.close() }; transfers.forEach { it.close() }
        executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available()); root.deleteRecursively()
    }
    @Test fun strictTransportRejectsGrantsNamesAndUnknownFields() {
        val variants = mutableListOf(intent().putExtra("extra", true), intent().putExtra("protocolVersion", 1L), intent().putExtra("plan", 1), intent().putExtra("transfer", "not binder"),
            intent().setData(Uri.parse("content://synthetic/1")), intent().setType("application/json"), intent().setAction(Intent.ACTION_VIEW), intent().setPackage("example.forwarder"),
            intent().addCategory(Intent.CATEGORY_DEFAULT), intent().apply { component = null }, intent().apply { selector = Intent("synthetic") })
        for (key in listOf("protocolVersion", "plan", "transfer", "control")) variants += intent().apply { removeExtra(key) }
        for (flag in listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) variants += intent().addFlags(flag)
        variants.forEach { denied { FactoryDataBackupCoordinator.Request.parse(it) } }
        assertEquals(nonce, FactoryDataBackupCoordinator.Request.parse(intent()).plan.nonce)
    }
    @Test fun completeArchiveIsCheckedThenExactApprovalHasNoImportUriOrGrant() {
        val value = prepared(); assertNotNull(value.archive); assertFalse(value.preparing)
        val result = io { coordinator.complete(value) {} }
        assertEquals(setOf("nonce", "planSha256", "approved"), result.extras!!.keySet())
        assertEquals(nonce, result.getStringExtra("nonce")); assertEquals(value.plan.sha256(), result.getStringExtra("planSha256")); assertTrue(result.getBooleanExtra("approved", false))
        assertNull(result.data); assertNull(result.clipData); assertEquals(0, result.flags)
        assertFalse(MemoryUiAutomationGuard.isProtected()); denied { io { coordinator.begin(proof.proof.appId, request()) {} } }
        val journal = File(root, "interaction.json").readText()
        assertFalse(journal.contains("notes")); assertFalse(journal.contains("Synthetic")); assertFalse(journal.contains("planSha256"))
    }
    @Test fun onlyExactCallerAndSignedApkAllowlistAdmit() {
        denied { io { coordinator.begin("example.forwarded.app", request()) {} } }
        proof = proof.copy(tables = emptySet()); denied { io { coordinator.begin(proof.proof.appId, request()) {} } }
        assertTrue(FactoryInteractionAdmission.available())
    }

}
