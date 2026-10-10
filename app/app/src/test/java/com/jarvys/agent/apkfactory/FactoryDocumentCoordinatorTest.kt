package com.jarvys.agent.apkfactory

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.ApplicationInfo
import android.net.Uri
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

/** Synthetic host contract tests: no device picker, private key, document I/O or real URI grant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryDocumentCoordinatorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-documents")
    private val journal get() = File(root, "interaction.json")
    private var proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryDocumentCoordinator
    private fun fail(block: () -> Unit) { assertTrue("Expected fail-closed rejection", runCatching(block).isFailure) }
    private fun intent(op: String = "open", nonce: String = "d".repeat(64)) = Intent().putExtra("operation", op).putExtra("mimeType", "text/plain").putExtra("nonce", nonce)
    private fun request(op: String = "open", nonce: String = "d".repeat(64)) = FactoryDocumentCoordinator.Request.parse(intent(op, nonce).apply { if (op == "create") putExtra("filename", "document.txt") })
    private fun begin(op: String = "open") = coordinator.begin(proof.appId, request(op))
    @Before fun setup() { root.deleteRecursively(); coordinator = FactoryDocumentCoordinator(context) { proof } }
    @After fun cleanup() {
        // Process death in synthetic tests drops process-local leases; production has no such reset.
        for (name in listOf("protectedSurfaces", "taintedSurfaces", "activeActions")) MemoryUiAutomationGuard::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(null, 0)
        FactoryInteractionAdmission::class.java.getDeclaredField("owners").apply { isAccessible = true }.let { (it.get(null) as MutableSet<*>).clear() }
        root.deleteRecursively()
    }
    @Test fun exactRequestRejectsSpoofedFieldsAndUris() {
        fail { FactoryDocumentCoordinator.Request.parse(intent().putExtra("appId", proof.appId)) }
        fail { FactoryDocumentCoordinator.Request.parse(intent().setData(Uri.parse("content://a/b"))) }
        fail { FactoryDocumentCoordinator.Request.parse(intent().apply { clipData = ClipData.newRawUri("x", Uri.parse("content://a/b")) }) }
        fail { FactoryDocumentCoordinator.Request.parse(intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        fail { FactoryDocumentCoordinator.Request.parse(intent().putExtra("nonce", "predictable")) }
        fail { FactoryDocumentCoordinator.Request.parse(intent().putExtra("operation", 1)) }
    }
    @Test fun filenameAndMimeAreBounded() {
        for (name in listOf("../x", "x/y", "x\\y", "..", "a\nb", "x".repeat(121))) fail { FactoryDocumentCoordinator.Request.parse(intent("create").putExtra("filename", name)) }
        fail { FactoryDocumentCoordinator.Request.parse(intent().putExtra("filename", "x")) }
        fail { FactoryDocumentCoordinator.Request.parse(intent().putExtra("mimeType", "text/plain\n")) }
        assertEquals("*/*", FactoryDocumentCoordinator.Request.parse(intent().putExtra("mimeType", "*/*")).mimeType)
    }
    @Test fun androidCallerIdentityIsRequiredAndCannotBeSpoofed() {
        fail { coordinator.begin(null, request()) }
        fail { coordinator.begin("evil.app", request()) }
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun singleFlightAndNoAutomaticGrantOrRelease() {
        val owner = begin()
        fail { coordinator.begin(proof.appId, request(nonce = "e".repeat(64))) }
        assertTrue(MemoryUiAutomationGuard.isProtected())
        val picker = coordinator.launch(owner) {}
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, picker.flags)
        fail { coordinator.launch(owner) {} }
        fail { coordinator.close(owner, true) {} }
        coordinator.pickerTerminal(owner, true)
        assertTrue(MemoryUiAutomationGuard.isProtected())
        assertEquals(request(), coordinator.close(owner, true) {})
        assertFalse(MemoryUiAutomationGuard.isProtected())
        fail { coordinator.begin(proof.appId, request()) }
    }
    @Test fun createRequestsOnlyWriteAndNoPersistence() {
        val owner = begin("create")
        assertEquals(Intent.FLAG_GRANT_WRITE_URI_PERMISSION, coordinator.launch(owner) {}.flags)
        coordinator.pickerTerminal(owner, false)
        assertNull(coordinator.close(owner, false) {})
    }
    @Test fun backOrCancelNeverReturnsSelection() {
        val owner = begin(); coordinator.launch(owner) {}; coordinator.pickerTerminal(owner, true)
        assertNull(coordinator.close(owner, false) {})
        assertEquals("cancelled", coordinator.status())
    }
    @Test fun pickerCancelWaitsForTerminalAndSeparateNativeClose() {
        val owner = begin(); coordinator.launch(owner) {}; coordinator.cancelPicker(owner)
        fail { coordinator.close(owner, false) {} }
        assertTrue(MemoryUiAutomationGuard.isProtected())
        coordinator.pickerTerminal(owner, true)
        assertEquals("cancelled", coordinator.status())
        fail { coordinator.close(owner, true) {} }
        coordinator.close(owner, false) {}
    }
    @Test fun noHandlerCanRecordCancelledWithoutGrant() {
        val owner = begin(); coordinator.launch(owner) {}; coordinator.pickerTerminal(owner, false)
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertNull(coordinator.close(owner, false) {})
    }
    @Test fun staleCallbackAndDestroyedOwnerCannotDeliver() {
        val owner = begin(); coordinator.launch(owner) {}; coordinator.revoke(owner)
        fail { coordinator.pickerTerminal(owner, true) }; fail { coordinator.close(owner, true) {} }
        assertTrue(coordinator.needsRecovery()); assertTrue(MemoryUiAutomationGuard.isProtected())
        coordinator.acknowledgeRecovery {}
        assertEquals("closed_outcome_unknown", coordinator.status())
    }
    @Test fun foregroundLossDuringVerificationRevokesApproval() {
        var foreground = true
        coordinator = FactoryDocumentCoordinator(context) { foreground = false; proof }
        val owner = begin(); foreground = true
        fail { coordinator.launch(owner) { check(foreground) } }
        assertEquals("review", coordinator.status())
    }
    @Test fun changedCertificateHashVersionOrEvidenceRevokesBeforeReturn() {
        val owner = begin(); coordinator.launch(owner) {}; coordinator.pickerTerminal(owner, true)
        for (changed in listOf(proof.copy(certificate="e".repeat(64)), proof.copy(apk="e".repeat(64)), proof.copy(version=3), proof.copy(record="e".repeat(64)))) {
            val original = proof; proof = changed; fail { coordinator.close(owner, true) {} }; proof = original
        }
        coordinator.close(owner, false) {}
    }
    @Test fun automationInFlightCannotStartReview() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        val action = MemoryUiAutomationGuard.beginAsyncAction(epoch)
        try { fail { begin() }; assertFalse(journal.exists()) } finally { action.close() }
    }
    @Test fun restartRestoresLatchAndNeverRestoresOwnerOrSelection() {
        val owner = begin(); coordinator.launch(owner) {}
        val restored = FactoryDocumentCoordinator(context) { proof }; restored.restore()
        assertTrue(restored.needsRecovery()); assertEquals("outcome_unknown", restored.status())
        fail { restored.pickerTerminal(owner, true) }; fail { restored.close(owner, true) {} }
        fail { restored.acknowledgeRecovery { error("background") } }
        restored.acknowledgeRecovery {}
        assertEquals("closed_outcome_unknown", restored.status())
    }
    @Test fun malformedAndSemanticallyInvalidJournalRetainLatch() {
        for (body in listOf("{", "x".repeat(4097), JSONObject().put("schemaVersion",1).put("state","picker").put("open",false).put("nonce","d".repeat(64)).toString())) {
            root.mkdirs(); journal.writeText(body)
            val restored = FactoryDocumentCoordinator(context) { proof }; restored.restore()
            assertTrue(restored.needsRecovery()); assertTrue(MemoryUiAutomationGuard.isProtected())
            restored.acknowledgeRecovery {}
            val again = FactoryDocumentCoordinator(context) { proof }; again.restore()
            assertFalse(again.needsRecovery())
        }
    }
    @Test fun resultsRejectFileMultiSelectionNestedIntentsAndMissingModes() {
        val valid = Intent().setData(Uri.parse("content://provider/document/1")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(FactoryDocumentActivity.validResult(valid, Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertFalse(FactoryDocumentActivity.validResult(valid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertFalse(FactoryDocumentActivity.validResult(Intent(valid).setData(Uri.parse("file:///tmp/x")), 1))
        val clip = ClipData.newRawUri("x", valid.data!!); clip.addItem(ClipData.Item(Uri.parse("content://provider/document/2")))
        assertFalse(FactoryDocumentActivity.validResult(Intent(valid).apply { clipData = clip }, 1))
        assertEquals(1, FactoryDocumentActivity.flagsFor("open")); assertEquals(2, FactoryDocumentActivity.flagsFor("create"))
    }
    private fun evidence(): FactorySigningIdentity.State {
        val scope = JSONObject(); for (field in listOf("capabilities","permissions","exportedComponents","features","queries","allowedHosts")) scope.put(field, JSONArray(if(field == "capabilities") listOf("documents") else emptyList<String>()))
        val record = JSONObject().put("lastApkSha256",proof.apk).put("lastSignedScope", JSONObject().put("schemaVersion",1).put("appId",proof.appId).put("certificateSha256",proof.certificate).put("versionCode",2).put("apkSha256",proof.apk).put("scope",scope))
        return FactorySigningIdentity.State(true, proof.certificate, 2, FactorySigningScope.readBaseline(record, proof.appId, proof.certificate, 2), proof.apk, proof.record)
    }
    @Test fun installedEvidenceRequiresExactCurrentKnownDocumentScope() {
        val good = evidence()
        assertEquals(proof, FactoryDocumentCoordinator.verifyEvidence(proof.appId,proof.certificate,proof.apk,2,good))
        for (bad in listOf(good.copy(existing=false),good.copy(continuityKnown=false),good.copy(lastScope=null),good.copy(lastApkSha256=null),good.copy(lastVersion=1),good.copy(fingerprint="f".repeat(64)),good.copy(recordSha256=null))) {
            fail { FactoryDocumentCoordinator.verifyEvidence(proof.appId,proof.certificate,proof.apk,2,bad) }
        }
    }
    @Test fun splitPackagesSharedUidUnknownUidAndSpoofedPackageAreRejected() {
        val info = PackageInfo().apply { packageName = proof.appId; applicationInfo = ApplicationInfo().apply { packageName = proof.appId } }
        FactoryDocumentCoordinator.verifyPackageShape(proof.appId, info, setOf(proof.appId))
        fail { FactoryDocumentCoordinator.verifyPackageShape(proof.appId, info, null) }
        fail { FactoryDocumentCoordinator.verifyPackageShape(proof.appId, info, setOf(proof.appId, "evil.app")) }
        info.sharedUserId = "shared"
        fail { FactoryDocumentCoordinator.verifyPackageShape(proof.appId, info, setOf(proof.appId)) }
        info.sharedUserId = null
        info.splitNames = arrayOf("feature")
        fail { FactoryDocumentCoordinator.verifyPackageShape(proof.appId, info, setOf(proof.appId)) }
        info.splitNames = emptyArray(); info.applicationInfo!!.splitSourceDirs = arrayOf("split.apk")
        fail { FactoryDocumentCoordinator.verifyPackageShape(proof.appId, info, setOf(proof.appId)) }
        info.applicationInfo!!.splitSourceDirs = null; info.packageName = "evil.app"
        fail { FactoryDocumentCoordinator.verifyPackageShape(proof.appId, info, setOf(proof.appId)) }
    }

    @Test fun createRequiresNameAndOpenNeverAcceptsOne() {
        fail { FactoryDocumentCoordinator.Request.parse(intent("create")) }
        fail { FactoryDocumentCoordinator.Request.parse(intent("open").putExtra("filename", "document.txt")) }
        fail { FactoryDocumentCoordinator.Request.parse(intent("create").putExtra("filename", "document.txt").putExtra("mimeType", "*/*")) }
        assertEquals("document.txt", request("create").filename)
    }
    @Test fun apkHashBoundsActualStreamBytesIncludingGrowthAndEmptyStreams() {
        val bytes = "apk".toByteArray()
        assertEquals(com.jarvys.agent.coding.ProjectScope.sha256(bytes), FactoryDocumentCoordinator.hashApk(bytes.inputStream(), 3))
        fail { FactoryDocumentCoordinator.hashApk("apkgrown".byteInputStream(), 3) }
        fail { FactoryDocumentCoordinator.hashApk(byteArrayOf().inputStream(), 3) }
        fail { FactoryDocumentCoordinator.hashApk(object : java.io.InputStream() {
            override fun read() = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        }, 3) }
    }

}
