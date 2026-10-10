package com.jarvys.agent.apkfactory

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
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

/** Synthetic host contracts only; no real chooser, recipient, signed caller or URI transmission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FactoryFileShareCoordinatorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-file-sharing")
    private val journal get() = File(root, "interaction.json")
    private val executor = Executors.newSingleThreadExecutor()
    private var proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private var cleanups = 0
    private var received = 0
    private var cleanupFailure = false
    private lateinit var coordinator: FactoryFileShareCoordinator
    private fun <T> io(block: () -> T): T = executor.submit(Callable { block() }).get(10, TimeUnit.SECONDS)
    private fun fail(block: () -> Unit) { assertTrue("Expected fail-closed rejection", runCatching(block).isFailure) }
    private fun wire() = Intent(context, FactoryFileShareActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("nonce", "d".repeat(64)); putString("filename", "file.bin")
        putString("mimeType", "application/octet-stream"); putInt("size", 3); putString("sha256", "e".repeat(64)); putBinder("transfer", Binder())
    })
    private fun request() = FactoryFileShareCoordinator.Request.parse(wire())
    private fun make(verify: (String) -> FactoryDocumentCoordinator.Proof = { check(it == proof.appId); proof }) = FactoryFileShareCoordinator(context, verify,
        receive = { value, active ->
            active(); received++
            FactoryFileShareStore.Snapshot(Uri.parse("content://${context.packageName}.factory.files/files/synthetic.bin"), value.filename, value.mimeType, value.size, SystemClock.elapsedRealtime() + 300000)
        }, clean = { cleanups++; check(!cleanupFailure) })
    @Before fun setup() { root.deleteRecursively(); coordinator = make() }
    @After fun cleanup() {
        executor.shutdownNow()
        for (name in listOf("protectedSurfaces", "taintedSurfaces", "activeActions")) MemoryUiAutomationGuard::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(null, 0)
        FactoryInteractionAdmission::class.java.getDeclaredField("owners").apply { isAccessible = true }.let { (it.get(null) as MutableSet<*>).clear() }
        root.deleteRecursively(); File(context.noBackupFilesDir,"factory-documents").deleteRecursively()
    }
    private fun begin() = io { coordinator.begin(proof.appId, request()) {} }
    private fun prepare(owner: String) = io { coordinator.prepareChooser(owner, "Synthetic") {} }
    @Test fun exactTypedProtocolRejectsEveryUnexpectedAuthoritySurface() {
        for (changed in listOf(
            wire().putExtra("protocolVersion", 1L), wire().putExtra("protocolVersion", 2), wire().putExtra("size", 3L),
            wire().putExtra("size", 0), wire().putExtra("size", 8388609), wire().putExtra("nonce", "A".repeat(64)),
            wire().putExtra("sha256", "short"), wire().putExtra("filename", "../file"), wire().putExtra("mimeType", "*/*"),
            wire().putExtra("mimeType", "text/plain; charset=utf-8"), wire().putExtra("appId", proof.appId),
            wire().setData(Uri.parse("content://evil/file")), wire().apply { clipData = ClipData.newRawUri("x", Uri.parse("content://evil/file")) },
            wire().apply { selector = Intent("evil") }, wire().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            wire().addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION), wire().addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
            wire().addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), wire().setComponent(null), wire().putExtra("transfer", "fake")
        )) fail { FactoryFileShareCoordinator.Request.parse(changed) }
        assertEquals(3, request().size)
        assertEquals(8388608, FactoryFileShareCoordinator.Request.parse(wire().putExtra("size", 8388608)).size)
    }
    @Test fun callerProofPrecedesPullAndUnknownCallerGetsNoLatch() {
        fail { io { coordinator.begin(null, request()) {} } }
        fail { io { coordinator.begin("evil.app", request()) {} } }
        assertEquals(0, received); assertFalse(journal.exists()); assertFalse(MemoryUiAutomationGuard.isProtected())
        assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun sharedAdmissionBlocksDocumentsAndSharingBothDirections() {
        val documents = FactoryDocumentCoordinator(context) { proof }
        val documentOwner = documents.begin(proof.appId, FactoryDocumentCoordinator.Request("open", "text/plain", null, "a".repeat(64)))
        fail { begin() }; assertEquals(0, received)
        documents.close(documentOwner, false) {}
        val owner = begin()
        assertFalse(documents.canBegin())
        fail { documents.begin(proof.appId, FactoryDocumentCoordinator.Request("open", "text/plain", null, "b".repeat(64))) }
        io { coordinator.close(owner, false) {} }
        assertTrue(documents.canBegin())
    }
    @Test fun durableLatchPrecedesChooserAndResultNeverAssertsDelivery() {
        val owner = begin(); assertEquals("review", JSONObject(journal.readText()).getString("state"))
        val chooser = prepare(owner)
        assertEquals("chooser_pending", JSONObject(journal.readText()).getString("state"))
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, chooser.flags)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action); assertNull(send.component); assertNull(send.`package`)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        assertEquals(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM), send.clipData!!.getItemAt(0).uri)
        coordinator.chooserLaunched(owner); coordinator.chooserCallback(owner)
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        fail { io { coordinator.close(owner, false) {} } }
        val result = io { coordinator.close(owner, true) {} }
        assertEquals(setOf("nonce", "chooserOpened", "deliveryConfirmed"), result.extras!!.keySet())
        assertTrue(result.getBooleanExtra("chooserOpened", false)); assertFalse(result.getBooleanExtra("deliveryConfirmed", true))
        assertNull(result.data); assertNull(result.clipData); assertNull(result.selector); assertEquals(0, result.flags)
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        fail { begin() }
    }
    @Test fun launchFailureAndPrelaunchCancelNeverClaimChooserOpened() {
        val owner = begin(); prepare(owner); coordinator.chooserLaunchFailed(owner)
        val result = io { coordinator.close(owner, true) {} }
        assertFalse(result.getBooleanExtra("chooserOpened", true)); assertFalse(result.getBooleanExtra("deliveryConfirmed", true))
    }
    @Test fun callbacksAndInterruptionCannotReleaseOrRestoreAuthority() {
        val owner = begin(); prepare(owner); coordinator.chooserLaunched(owner); coordinator.invalidate(owner)
        assertTrue(coordinator.needsRecovery()); assertTrue(MemoryUiAutomationGuard.isProtected())
        fail { coordinator.chooserCallback(owner) }; fail { io { coordinator.close(owner, true) {} } }
        fail { io { coordinator.acknowledgeRecovery { error("background") } } }
        assertTrue(coordinator.needsRecovery())
        io { coordinator.acknowledgeRecovery {} }
        assertEquals("closed_outcome_unknown", coordinator.status()); assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun cleanupFailureRetainsAdmissionAndGuardUntilHumanRetry() {
        val owner = begin(); prepare(owner); coordinator.chooserLaunched(owner)
        cleanupFailure = true
        fail { io { coordinator.close(owner, true) {} } }
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        cleanupFailure = false
        io { coordinator.close(owner, true) {} }
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun changedIdentityAfterCopyCannotBecomeReviewAndCleanupRuns() {
        var checks = 0
        coordinator = make { checks++; if (checks == 2) proof.copy(version = 3) else proof }
        fail { begin() }
        assertEquals(1, received); assertTrue(cleanups >= 2); assertTrue(coordinator.needsRecovery())
        io { coordinator.acknowledgeRecovery {} }
    }
    @Test fun changedScopeAtHumanApprovalCannotLaunch() {
        val owner = begin(); proof = proof.copy(record = "f".repeat(64))
        fail { prepare(owner) }; assertEquals("review", coordinator.status())
        io { coordinator.close(owner, false) {} }
    }
    @Test fun restoreDropsLaunchEvidenceAndEveryJournalKeepsItsOwnAdmission() {
        val owner = begin(); prepare(owner); coordinator.chooserLaunched(owner)
        val restored = make(); io { restored.restore() }
        assertTrue(restored.needsRecovery()); assertFalse(restored.chooserOpened())
        fail { io { restored.close(owner, true) {} } }
        io { restored.acknowledgeRecovery {} }
        // Simulated old process still owns its lease. Releasing the restored journal cannot release it.
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        coordinator.invalidate(owner); io { coordinator.acknowledgeRecovery {} }
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun malformedUnknownJournalRequiresProtectedNativeRecovery() {
        root.mkdirs(); journal.writeText("{")
        io { coordinator.restore() }
        assertTrue(coordinator.needsRecovery()); assertTrue(MemoryUiAutomationGuard.isProtected())
        io { coordinator.acknowledgeRecovery {} }
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertEquals("", JSONObject(journal.readText()).getString("nonce"))
    }
    @Test fun automatedActionCannotApproveOrStartTransfer() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch(); val action = MemoryUiAutomationGuard.beginAsyncAction(epoch)
        try { fail { begin() }; assertEquals(0, received); assertFalse(journal.exists()) } finally { action.close() }
    }
    @Test fun mainThreadIoIsRejectedBeforeWork() { fail { coordinator.begin(proof.appId, request()) {} }; assertEquals(0, received) }
    @Test fun currentExactIdentityIndependentlyRequiresBothCapabilities() {
        fun evidence(caps: List<String>): FactorySigningIdentity.State {
            val scope = JSONObject()
            for (field in listOf("capabilities", "permissions", "exportedComponents", "features", "queries", "allowedHosts")) scope.put(field, JSONArray(if (field == "capabilities") caps else emptyList<String>()))
            val record = JSONObject().put("lastApkSha256", proof.apk).put("lastSignedScope", JSONObject().put("schemaVersion", 1).put("appId", proof.appId).put("certificateSha256", proof.certificate).put("versionCode", 2).put("apkSha256", proof.apk).put("scope", scope))
            return FactorySigningIdentity.State(true, proof.certificate, 2, FactorySigningScope.readBaseline(record, proof.appId, proof.certificate, 2), proof.apk, proof.record)
        }
        for (caps in listOf(emptyList(), listOf("documents"), listOf("share"))) fail {
            FactoryDocumentCoordinator.verifyEvidence(proof.appId, proof.certificate, proof.apk, 2, evidence(caps), setOf("documents", "share"))
        }
        assertEquals(proof, FactoryDocumentCoordinator.verifyEvidence(proof.appId, proof.certificate, proof.apk, 2, evidence(listOf("documents", "share")), setOf("documents", "share")))
    }
    @Test fun malformedDocumentAndShareJournalsRestoreIndependentGuards() {
        val documentRoot = File(context.noBackupFilesDir, "factory-documents").apply { mkdirs() }
        File(documentRoot, "interaction.json").writeText("{")
        root.mkdirs(); journal.writeText("{")
        val documents = FactoryDocumentCoordinator(context) { proof }
        documents.restore(); io { coordinator.restore() }
        assertTrue(documents.needsRecovery()); assertTrue(coordinator.needsRecovery())
        io { coordinator.acknowledgeRecovery {} }
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        documents.acknowledgeRecovery {}
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun stalledTransferRetainsAdmissionUntilActualWorkerCompletion() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        coordinator = FactoryFileShareCoordinator(context, { proof }, receive = { value, active ->
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); active()
            FactoryFileShareStore.Snapshot(Uri.parse("content://${context.packageName}.factory.files/files/synthetic.bin"), value.filename, value.mimeType, value.size, SystemClock.elapsedRealtime() + 300000)
        }, clean = {})
        val future = executor.submit(Callable { coordinator.begin(proof.appId, request()) {} })
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(coordinator.canBegin()); assertFalse(FactoryInteractionAdmission.available())
            assertTrue(MemoryUiAutomationGuard.isProtected())
            val documents = FactoryDocumentCoordinator(context) { proof }
            fail { documents.begin(proof.appId, FactoryDocumentCoordinator.Request("open", "text/plain", null, "b".repeat(64))) }
            assertEquals(1, FactoryFileShareCoordinator.WORKER.maximumPoolSize)
            assertTrue(FactoryFileShareCoordinator.WORKER.queue is java.util.concurrent.ArrayBlockingQueue<*>)
            assertEquals(1, FactoryFileShareCoordinator.WORKER.queue.remainingCapacity())
        } finally { release.countDown() }
        val owner = future.get(10, TimeUnit.SECONDS)
        io { coordinator.close(owner, false) {} }
        assertTrue(FactoryInteractionAdmission.available())
    }

}
