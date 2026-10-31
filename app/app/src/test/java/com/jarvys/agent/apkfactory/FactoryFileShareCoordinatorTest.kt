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
    private fun wire(nonce: String = "d".repeat(64)) = Intent(context, FactoryFileShareActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("nonce", nonce); putString("filename", "file.bin")
        putString("mimeType", "application/octet-stream"); putInt("size", 3); putString("sha256", "e".repeat(64)); putBinder("transfer", Binder())
    })
    private fun request(nonce: String = "d".repeat(64)) = FactoryFileShareCoordinator.Request.parse(wire(nonce))
    private fun make(verify: (String) -> FactoryDocumentCoordinator.Proof = { check(it == proof.appId); proof }) = FactoryFileShareCoordinator(context, verify,
        receive = { value, active ->
            active(); received++
            FactoryFileShareStore.Snapshot(Uri.parse("content://${context.packageName}.factory.files/files/synthetic.bin"), value.filename, value.mimeType, value.size, SystemClock.elapsedRealtime() + 300000)
        }, clean = { cleanups++; check(!cleanupFailure) })
    @Before fun setup() { root.deleteRecursively(); coordinator = make() }
    @After fun cleanup() {
        FactoryFileShareCoordinator.WORKER.prestartCoreThread()
        val barrier = java.util.concurrent.FutureTask(Callable { Unit })
        check(FactoryFileShareCoordinator.WORKER.queue.offer(barrier, 5, TimeUnit.SECONDS)); barrier.get(5, TimeUnit.SECONDS)
        executor.shutdownNow()
        for (name in listOf("protectedSurfaces", "taintedSurfaces", "activeActions")) MemoryUiAutomationGuard::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(null, 0)
        FactoryInteractionAdmission::class.java.getDeclaredField("owners").apply { isAccessible = true }.let { (it.get(null) as MutableSet<*>).clear() }
        root.deleteRecursively(); File(context.noBackupFilesDir,"factory-documents").deleteRecursively()
    }
    private fun begin() = io { coordinator.begin(proof.appId, request()) {} }
    private fun prepare(owner: String) = io { coordinator.prepareChooser(owner, "Synthetic") }
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
        assertEquals(3L, request().size)
        assertEquals(8388608L, FactoryFileShareCoordinator.Request.parse(wire().putExtra("size", 8388608)).size)
    }
    @Test fun v2LongEnvelopeAcceptsFourGiBBoundButNeverCoercesOrRaisesV1Limit() {
        val maximum = 4L * 1024 * 1024 * 1024
        assertEquals(maximum, FactoryFileShareCoordinator.Request.parse(wire().putExtra("protocolVersion", 2).putExtra("size", maximum)).size)
        for (intent in listOf(wire().putExtra("protocolVersion", 2).putExtra("size", 3),
            wire().putExtra("protocolVersion", 2).putExtra("size", maximum + 1),
            wire().putExtra("protocolVersion", 2).putExtra("size", -1L),
            wire().putExtra("protocolVersion", 3).putExtra("size", maximum))) fail { FactoryFileShareCoordinator.Request.parse(intent) }
    }
    @Test fun legacySignedBudgetRejectsLargeV2BeforeReceiveOrJournal() {
        val request = FactoryFileShareCoordinator.Request.parse(wire().putExtra("protocolVersion", 2).putExtra("size", 8388609L))
        fail { io { coordinator.begin(proof.appId, request) {} } }
        assertEquals(0, received); assertFalse(journal.exists()); assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun callerProofPrecedesPullAndUnknownCallerGetsNoLatch() {
        fail { io { coordinator.begin(null, request()) {} } }
        fail { io { coordinator.begin("evil.app", request()) {} } }
        assertEquals(0, received); assertFalse(journal.exists()); assertFalse(MemoryUiAutomationGuard.isProtected())
        assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun mainThreadIoIsRejectedBeforeWork() { fail { coordinator.begin(proof.appId, request()) {} }; assertEquals(0, received) }
    @Test fun newInteractionRetriesAutomaticCleanupAfterTransientStartupFailure() {
        root.mkdirs()
        journal.writeText(JSONObject().put("schemaVersion", 1).put("state", "chooser_opened")
            .put("open", true).put("nonce", "c".repeat(64)).toString())
        cleanupFailure = true
        io { coordinator.restore() }
        assertTrue(coordinator.canBegin())
        cleanupFailure = false

        io { coordinator.begin(proof.appId, request("f".repeat(64))) {} }
        assertEquals(1, received)
        assertEquals("review", coordinator.status())
        assertEquals(3, cleanups)
    }
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

}
