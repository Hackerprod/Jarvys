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

/** Synthetic photo contract tests: isolated Application; no real camera, media, or URI grant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryPhotoCoordinatorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-photos")
    private val journal get() = File(root, "interaction.json")
    private var proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private val policy = FactoryOperationPolicy.inMemory()
    private lateinit var coordinator: FactoryPhotoCoordinator
    private fun fail(block: () -> Unit) { assertTrue("Expected fail-closed rejection", runCatching(block).isFailure) }
    private fun intent(op: String = "pick", nonce: String = "d".repeat(64)) = Intent().putExtra("operation", op).putExtra("nonce", nonce)
    private fun request(op: String = "pick", nonce: String = "d".repeat(64)) = FactoryPhotoCoordinator.Request.parse(intent(op, nonce))
    private fun begin(op: String = "pick") = coordinator.begin(proof.appId, request(op))
    @Before fun setup() { FactoryStartupTestIsolation.releaseCompletedSharingStartup(); root.deleteRecursively(); coordinator = FactoryPhotoCoordinator(context, policy) { proof } }
    @After fun cleanup() {
        // Process death in synthetic tests drops process-local leases; production has no such reset.
        for (name in listOf("protectedSurfaces", "taintedSurfaces", "activeActions")) MemoryUiAutomationGuard::class.java.getDeclaredField(name).apply { isAccessible = true }.setInt(null, 0)
        FactoryInteractionAdmission::class.java.getDeclaredField("owners").apply { isAccessible = true }.let { (it.get(null) as MutableSet<*>).clear() }
        root.deleteRecursively()
    }
    @Test fun exactRequestRejectsSpoofedFieldsAndUris() {
        fail { FactoryPhotoCoordinator.Request.parse(intent().putExtra("appId", proof.appId)) }
        fail { FactoryPhotoCoordinator.Request.parse(intent().setData(Uri.parse("content://a/b"))) }
        fail { FactoryPhotoCoordinator.Request.parse(intent().apply { clipData = ClipData.newRawUri("x", Uri.parse("content://a/b")) }) }
        fail { FactoryPhotoCoordinator.Request.parse(intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        fail { FactoryPhotoCoordinator.Request.parse(intent().putExtra("nonce", "predictable")) }
        fail { FactoryPhotoCoordinator.Request.parse(intent().putExtra("operation", 1)) }
    }
    @Test fun androidCallerIdentityIsRequiredAndCannotBeSpoofed() {
        fail { coordinator.begin(null, request()) }
        fail { coordinator.begin("evil.app", request()) }
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun singleFlightAndNoAutomaticGrantOrRelease() {
        val owner = begin()
        fail { coordinator.begin(proof.appId, request(nonce = "e".repeat(64))) }
        assertFalse(MemoryUiAutomationGuard.isProtected())
        coordinator.launch(owner) {}
        assertEquals("picker", coordinator.status())
        fail { coordinator.launch(owner) {} }
        fail { coordinator.close(owner, true) {} }
        coordinator.pickerTerminal(owner, true)
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertEquals(request(), coordinator.close(owner, true) {})
        assertFalse(MemoryUiAutomationGuard.isProtected())
        fail { coordinator.begin(proof.appId, request()) }
    }
    @Test fun backOrCancelNeverReturnsSelection() {
        val owner = begin(); coordinator.launch(owner) {}; coordinator.pickerTerminal(owner, true)
        assertNull(coordinator.close(owner, false) {})
        assertEquals("cancelled", coordinator.status())
    }
    @Test fun pickerCancelWaitsForTerminalAndSeparateNativeClose() {
        val owner = begin(); coordinator.launch(owner) {}; coordinator.cancelPicker(owner)
        fail { coordinator.close(owner, false) {} }
        assertFalse(MemoryUiAutomationGuard.isProtected())
        coordinator.pickerTerminal(owner, true)
        assertEquals("cancelled", coordinator.status())
        fail { coordinator.close(owner, true) {} }
        coordinator.close(owner, false) {}
    }
    @Test fun noHandlerCanRecordCancelledWithoutGrant() {
        val owner = begin(); coordinator.launch(owner) {}; coordinator.pickerTerminal(owner, false)
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertNull(coordinator.close(owner, false) {})
    }
    @Test fun staleCallbackAndDestroyedOwnerCannotDeliver() {
        val owner = begin(); coordinator.launch(owner) {}; coordinator.revoke(owner)
        fail { coordinator.pickerTerminal(owner, true) }; fail { coordinator.close(owner, true) {} }
        assertTrue(coordinator.needsRecovery()); assertFalse(MemoryUiAutomationGuard.isProtected())
        coordinator.acknowledgeRecovery {}
        assertEquals("closed_outcome_unknown", coordinator.status())
    }
    @Test fun foregroundLossDuringVerificationPreventsEffect() {
        var foreground = true
        coordinator = FactoryPhotoCoordinator(context, policy) { foreground = false; proof }
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
    @Test fun defaultAllowRunsDuringAutomationWithoutHumanLease() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
        val action = MemoryUiAutomationGuard.beginAsyncAction(epoch)
        try { val owner = begin(); coordinator.launch(owner) {}; assertTrue(journal.exists()); assertFalse(MemoryUiAutomationGuard.isProtected()) } finally { action.close() }
    }
    @Test fun restartRestoresLatchAndNeverRestoresOwnerOrSelection() {
        val owner = begin(); coordinator.launch(owner) {}
        val restored = FactoryPhotoCoordinator(context, policy) { proof }; restored.restore()
        assertTrue(restored.needsRecovery()); assertEquals("outcome_unknown", restored.status())
        fail { restored.pickerTerminal(owner, true) }; fail { restored.close(owner, true) {} }
        fail { restored.acknowledgeRecovery { error("background") } }
        restored.acknowledgeRecovery {}
        assertEquals("closed_outcome_unknown", restored.status())
    }
    @Test fun malformedAndSemanticallyInvalidJournalRetainLatch() {
        for (body in listOf("{", "x".repeat(4097), JSONObject().put("schemaVersion",1).put("state","picker").put("open",false).put("nonce","d".repeat(64)).toString())) {
            root.mkdirs(); journal.writeText(body)
            val restored = FactoryPhotoCoordinator(context, policy) { proof }; restored.restore()
            assertTrue(restored.needsRecovery()); assertFalse(MemoryUiAutomationGuard.isProtected())
            restored.acknowledgeRecovery {}
            val again = FactoryPhotoCoordinator(context, policy) { proof }; again.restore()
            assertFalse(again.needsRecovery())
        }
    }
    private fun evidence(capabilities: List<String> = listOf("documents", "photos")): FactorySigningIdentity.State {
        val scope = JSONObject(); for (field in listOf("capabilities","permissions","exportedComponents","features","queries","allowedHosts")) scope.put(field, JSONArray(if(field == "capabilities") capabilities else emptyList<String>()))
        val record = JSONObject().put("lastApkSha256",proof.apk).put("lastSignedScope", JSONObject().put("schemaVersion",1).put("appId",proof.appId).put("certificateSha256",proof.certificate).put("versionCode",2).put("apkSha256",proof.apk).put("scope",scope))
        return FactorySigningIdentity.State(true, proof.certificate, 2, FactorySigningScope.readBaseline(record, proof.appId, proof.certificate, 2), proof.apk, proof.record)
    }
    @Test fun installedEvidenceRequiresExactCurrentKnownPhotoScope() {
        val good = evidence()
        assertEquals(proof, FactoryDocumentCoordinator.verifyEvidence(proof.appId,proof.certificate,proof.apk,2,good,setOf("documents", "photos")))
        for (bad in listOf(good.copy(existing=false),good.copy(continuityKnown=false),good.copy(lastScope=null),good.copy(lastApkSha256=null),good.copy(lastVersion=1),good.copy(fingerprint="f".repeat(64)),good.copy(recordSha256=null))) {
            fail { FactoryDocumentCoordinator.verifyEvidence(proof.appId,proof.certificate,proof.apk,2,bad,setOf("documents", "photos")) }
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


    @Test fun bothSignedCapabilitiesAreMandatory() {
        for (caps in listOf(emptyList(), listOf("documents"), listOf("photos"))) {
            fail { FactoryDocumentCoordinator.verifyEvidence(proof.appId, proof.certificate, proof.apk, 2, evidence(caps), setOf("documents", "photos")) }
        }
    }
    @Test fun journalContainsNoOperationIdentityUriOrPhotoBytes() {
        val owner = begin("capture"); coordinator.launch(owner) {}; coordinator.pickerTerminal(owner, true)
        val saved = JSONObject(journal.readText())
        assertEquals(setOf("schemaVersion", "state", "open", "nonce"), saved.keys().asSequence().toSet())
        assertEquals(request().nonce, saved.getString("nonce"))
        coordinator.close(owner, false) {}
    }
    @Test fun lifecycleValidationStillAppliesAtLaunchAndDelivery() {
        val owner = begin(); fail { coordinator.launch(owner) { error("inactive launch owner") } }
        assertEquals("review", coordinator.status())
        coordinator.launch(owner) {}; coordinator.pickerTerminal(owner, true)
        fail { coordinator.close(owner, true) { error("inactive return owner") } }
        assertEquals("selected", coordinator.status()); assertFalse(MemoryUiAutomationGuard.isProtected())
        coordinator.close(owner, false) {}
    }
    @Test fun requestRejectsUnsupportedOperationsSelectorsAndEveryGrantMode() {
        for (op in listOf("open", "create", "video", "", "PICK")) fail { FactoryPhotoCoordinator.Request.parse(intent(op)) }
        for (flag in listOf(1, 2, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) {
            fail { FactoryPhotoCoordinator.Request.parse(intent().addFlags(flag)) }
        }
        fail { FactoryPhotoCoordinator.Request.parse(intent().apply { selector = Intent("nested") }) }
        assertEquals("capture", request("capture").operation)
    }

    @Test fun transferVerifierPinsUidExclusivePackageAndLatestSigningEvidence() {
        val uid = 20002
        val info = PackageInfo().apply { packageName = proof.appId; applicationInfo = ApplicationInfo().apply { packageName = proof.appId; this.uid = uid } }
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        pm.installPackage(info); pm.setPackagesForUid(uid, proof.appId)
        val owner = begin(); coordinator.launch(owner) {}; coordinator.pickerTerminal(owner, true)
        val verifier = coordinator.transferVerifier(owner) {}
        assertTrue(verifier(uid)); assertFalse(verifier(uid + 1))
        pm.setPackagesForUid(uid, proof.appId, "example.shared")
        assertFalse(verifier(uid)); pm.setPackagesForUid(uid, proof.appId)
        val original = proof; proof = proof.copy(record = "f".repeat(64))
        assertFalse(verifier(uid)); proof = original
        assertTrue(verifier(uid)); coordinator.close(owner, false) {}
    }
    @Test fun closedJournalRetainsReplayProtectionAcrossRestart() {
        val owner = begin(); coordinator.close(owner, false) {}
        val restored = FactoryPhotoCoordinator(context, policy) { proof }; restored.restore()
        assertFalse(restored.needsRecovery()); assertTrue(restored.canBegin())
        fail { restored.begin(proof.appId, request()) }
        val next = restored.begin(proof.appId, request(nonce = "e".repeat(64)))
        restored.close(next, false) {}
    }
    @Test fun journalWriteFailureRetainsFamilyStateWithoutHumanGuard() {
        root.writeText("not a directory")
        fail { begin() }
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(coordinator.needsRecovery())
        assertFalse(coordinator.canBegin())
        root.delete(); coordinator.acknowledgeRecovery {}
        assertEquals("closed_outcome_unknown", coordinator.status())
    }

    @Test fun configuredAskRequiresOneApprovalOnlyAndNeverCreatesCoordinatorLease() {
        policy.setDefaultMode(FactoryOperationPolicy.Mode.ASK)
        val owner = begin(); val grant = coordinator.authority(owner)
        assertTrue(grant.requiresApproval); assertFalse(MemoryUiAutomationGuard.isProtected())
        fail { coordinator.launch(owner) {} }
        var approvals = 0
        coordinator.approve(owner) { approvals++ }
        assertFalse(grant.requiresApproval)
        coordinator.approve(owner) { error("Approval must not repeat") }
        coordinator.launch(owner) {}; coordinator.pickerTerminal(owner, true)
        assertEquals(request(), coordinator.close(owner, true) {})
        assertEquals(1, approvals); grant.checkBeforeEffect(); grant.close()
    }
    @Test fun denyRejectsBeforeJournalPickerOrHostResourceOwnership() {
        policy.setMode(FactoryOperationPolicy.Subject.installed(proof), FactoryOperationPolicy.Operation.PHOTO_PICK, FactoryOperationPolicy.Mode.DENY)
        fail { begin() }
        assertFalse(journal.exists()); assertEquals("idle", coordinator.status()); assertTrue(coordinator.canBegin())
        assertTrue(FactoryInteractionAdmission.available(coordinator)); assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun policyRevisionInvalidatesBothPendingAndSelectedRequests() {
        val owner = begin(); coordinator.launch(owner) {}; coordinator.pickerTerminal(owner, true)
        val grant = coordinator.authority(owner)
        policy.setMode(FactoryOperationPolicy.Subject.installed(proof), FactoryOperationPolicy.Operation.PHOTO_PICK, FactoryOperationPolicy.Mode.DENY)
        fail { grant.checkBeforeEffect() }; fail { coordinator.close(owner, true) {} }
        assertTrue(coordinator.needsRecovery()); assertNull(coordinator.close(owner, false) {})
    }
    @Test fun successfulJournalConsumptionRetainsGrantUntilActualActivityDelivery() {
        val owner = begin(); val grant = coordinator.authority(owner)
        coordinator.launch(owner) {}; coordinator.pickerTerminal(owner, true); coordinator.close(owner, true) {}
        grant.checkBeforeEffect()
        policy.setDefaultMode(FactoryOperationPolicy.Mode.DENY)
        fail { grant.checkBeforeEffect() }; grant.close()
    }
    @Test fun validHistoricalUnknownAllowsOnlyNewVerifiedAllowRequestWithoutReplay() {
        root.mkdirs(); journal.writeText(JSONObject().put("schemaVersion", 1).put("state", "picker").put("open", true).put("nonce", "d".repeat(64)).toString())
        val restored = FactoryPhotoCoordinator(context, policy) { proof }; restored.restore()
        assertTrue(restored.canBegin()); assertTrue(restored.needsRecovery()); assertFalse(MemoryUiAutomationGuard.isProtected())
        fail { restored.begin(proof.appId, request()) }
        val fresh = request(nonce = "e".repeat(64))
        val owner = restored.begin(proof.appId, fresh)
        assertEquals("review", restored.status()); assertEquals(fresh.nonce, JSONObject(journal.readText()).getString("nonce"))
        assertNull(restored.close(owner, false) {})
    }
    @Test fun historicalUnknownDoesNotBypassDenyOrAsk() {
        root.mkdirs(); journal.writeText(JSONObject().put("schemaVersion", 1).put("state", "picker").put("open", true).put("nonce", "d".repeat(64)).toString())
        val restored = FactoryPhotoCoordinator(context, policy) { proof }; restored.restore()
        for (mode in listOf(FactoryOperationPolicy.Mode.DENY, FactoryOperationPolicy.Mode.ASK)) {
            policy.setDefaultMode(mode)
            fail { restored.begin(proof.appId, request(nonce = "e".repeat(64))) }
            assertEquals("outcome_unknown", restored.status()); assertTrue(restored.needsRecovery())
        }
        restored.acknowledgeRecovery {}; assertFalse(restored.needsRecovery())
    }

}
