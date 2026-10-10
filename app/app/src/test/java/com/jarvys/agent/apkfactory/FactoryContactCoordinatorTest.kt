package com.jarvys.agent.apkfactory

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.factory.runtime.ExternalLaunchControl
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic contact values and source/control metadata only. No real contact provider or picker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryContactCoordinatorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-contacts")
    private val journal get() = File(root, "interaction.json")
    private var proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryContactCoordinator
    private val owners = mutableListOf<FactoryContactCoordinator>()
    private val externalOwners = mutableListOf<FactoryExternalLaunchCoordinator>()
    private val executor = Executors.newSingleThreadExecutor()
    private fun <T> io(block: () -> T): T = executor.submit(Callable(block)).get(5, TimeUnit.SECONDS)
    private fun denied(block: () -> Unit) = assertTrue("Expected contact authority rejection", runCatching(block).isFailure)
    private fun make(verify: (String, String) -> FactoryDocumentCoordinator.Proof = { _, _ -> proof }) = FactoryContactCoordinator(context, verify).also { owners += it }
    private fun intent(kind: String = "phone") = Intent(context, FactoryContactActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("nonce", NONCE); putString("kind", kind); putBinder("control", Binder())
    })
    private fun request(kind: String = "phone") = FactoryContactCoordinator.Request.parse(intent(kind))
    private fun begin(kind: String = "phone") = io { coordinator.begin(proof.appId, request(kind)) {} }
    private fun register(value: FactoryContactCoordinator.Session) {
        val constructor = ExternalLaunchControl.Registration::class.java.getDeclaredConstructor(IBinder::class.java, Runnable::class.java).apply { isAccessible = true }
        value.registration = constructor.newInstance(Binder(), Runnable { value.revoke() })
    }
    private fun select(value: FactoryContactCoordinator.Session) {
        register(value); io { coordinator.prepareLaunch(value) {} }; io { coordinator.verifyReady(value) {} }
        value.pickerReturned = true; io { coordinator.prepareRead(value) {} }; io { coordinator.selected(value) }
    }
    private fun assertPrivateJournal(expectedState: String, expectedOpen: Boolean) {
        val raw = journal.readText(); val saved = JSONObject(raw)
        assertEquals(setOf("schemaVersion", "state", "open", "nonce"), saved.keys().asSequence().toSet())
        assertEquals(1, saved.getInt("schemaVersion")); assertEquals(expectedState, saved.getString("state")); assertEquals(expectedOpen, saved.getBoolean("open"))
        for (secret in listOf(PHONE, EMAIL, ROW_URI, proof.appId, proof.certificate, proof.apk, proof.record, "phone", "email", "control")) assertFalse("Journal contains $secret", raw.contains(secret))
    }
    @Before fun setup() {
        FactoryStartupTestIsolation.releaseCompletedSharingStartup(); root.deleteRecursively(); coordinator = make()
    }
    @After fun cleanup() {
        owners.asReversed().forEach { owner ->
            owner.session()?.preparing = false
            if (owner.needsRecovery()) io { owner.close(owner.session(), true, true) {} }
            else owner.session()?.let { io { owner.close(it, false, true) {} } }
        }
        externalOwners.asReversed().forEach { owner ->
            if (owner.needsRecovery()) io { owner.close(owner.session(), true, true) {} }
            else owner.session()?.let { io { owner.close(it, false, true) {} } }
        }
        executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        root.deleteRecursively(); File(context.noBackupFilesDir, "factory-external-launch").deleteRecursively()
    }
    @Test fun exactFourFieldRequestRejectsTransportExtrasTypeConfusionAndUnknownKinds() {
        val invalid = mutableListOf(intent().putExtra("extra", true), intent().putExtra("protocolVersion", 1L), intent().putExtra("protocolVersion", 2),
            intent().putExtra("nonce", "D".repeat(64)), intent().putExtra("nonce", "d".repeat(63)), intent().putExtra("nonce", 1),
            intent().putExtra("kind", 1), intent().putExtra("control", "binder"), intent().setData(Uri.parse(ROW_URI)),
            intent().apply { component = null }, intent().apply { action = Intent.ACTION_PICK }, intent().setType("text/plain"),
            intent().addCategory(Intent.CATEGORY_DEFAULT), intent().setPackage(proof.appId),
            intent().apply { clipData = ClipData.newPlainText("synthetic", "fixture") }, intent().apply { selector = Intent("synthetic") })
        for (kind in listOf("", "Phone", "EMAIL", "contact", "all", "phone ", "phone,email", "uri", "name")) invalid += intent(kind)
        for (flag in listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) invalid += intent().addFlags(flag)
        for (key in listOf("protocolVersion", "nonce", "kind", "control")) invalid += intent().apply { removeExtra(key) }
        invalid.forEach { denied { FactoryContactCoordinator.Request.parse(it) } }
        for (kind in listOf("phone", "email")) { assertEquals(kind, request(kind).kind); assertEquals(NONCE, request(kind).nonce) }
        assertFalse(journal.exists())
    }
    @Test fun absentWrongAndUnverifiedCallerProduceNoJournalOrAdmission() {
        denied { io { coordinator.begin(null, request()) {} } }; denied { io { coordinator.begin("", request()) {} } }
        denied { io { coordinator.begin("example.wrong.app", request()) {} } }
        coordinator = make { _, _ -> error("No latest signed contact capability") }; denied { begin() }
        assertFalse(journal.exists()); assertTrue(coordinator.canBegin()); assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun noJournalIoOnMainAndInactiveSourceCannotBegin() {
        denied { coordinator.begin(proof.appId, request()) {} }; denied { coordinator.restore() }
        denied { io { coordinator.begin(proof.appId, request()) { error("Source is inactive") } } }
        assertFalse(journal.exists()); assertTrue(coordinator.canBegin())
    }
    @Test fun automationAlreadyInFlightCannotAcquireContactAuthority() {
        val active = MemoryUiAutomationGuard.beginAsyncAction(MemoryUiAutomationGuard.captureAutomationEpoch())
        try { denied { begin() }; assertNull(coordinator.session()); assertFalse(journal.exists()) } finally { active.close() }
        assertTrue(coordinator.canBegin()); assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun reviewHoldsGlobalAdmissionAndJournalsNoContactOrSourceData() {
        val value = begin(); assertPrivateJournal("review", true)
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        denied { MemoryUiAutomationGuard.captureAutomationEpoch() }
        denied { io { make().begin(proof.appId, request().copy(nonce = "e".repeat(64))) {} } }
        assertEquals(Unit, io { coordinator.close(value, false, false) {} }); assertPrivateJournal("closed", false)
        assertFalse(MemoryUiAutomationGuard.isProtected()); denied { begin() }
    }
    @Test fun bothKindsRequireContactsCapabilityAtEverySourceVerification() {
        for ((index, kind) in listOf("phone", "email").withIndex()) {
            val seen = mutableListOf<Pair<String, String>>()
            coordinator = make { appId, capability -> seen += appId to capability; check(capability == "contacts"); proof }
            val value = io { coordinator.begin(proof.appId, request(kind).copy(nonce = (index + 1).toString().repeat(64))) {} }
            select(value)
            val result = io { coordinator.deliver(value, if (kind == "phone") PHONE else EMAIL) {} }
            assertEquals(List(5) { proof.appId to "contacts" }, seen)
            assertEquals(kind, result.getStringExtra("kind")); assertPrivateJournal("closed", false)
        }
    }
    @Test fun phoneAndEmailPermissionsCannotSubstituteForContactCapability() {
        for (permitted in listOf("phone", "email", "sms", "files")) {
            var requested: String? = null
            coordinator = make { _, capability -> requested = capability; check(capability == permitted); proof }
            denied { begin() }; assertEquals("contacts", requested); assertFalse(journal.exists()); assertTrue(coordinator.canBegin())
        }
    }
    @Test fun launchRequiresHumanRegistrationAndEveryLatestProofFieldUnchanged() {
        val value = begin(); denied { io { coordinator.prepareLaunch(value) { error("No human") } } }
        denied { io { coordinator.prepareLaunch(value) {} } }; register(value)
        val original = proof
        for (changed in listOf(proof.copy(appId = "example.other.app"), proof.copy(certificate = "f".repeat(64)), proof.copy(apk = "f".repeat(64)), proof.copy(version = 3), proof.copy(record = "f".repeat(64)))) {
            proof = changed; denied { io { coordinator.prepareLaunch(value) {} } }; assertFalse(value.attempted); assertEquals("review", coordinator.status())
        }
        proof = original; io { coordinator.prepareLaunch(value) {} }; assertTrue(value.attempted); assertPrivateJournal("launch_pending", true)
        denied { io { coordinator.prepareLaunch(value) {} } }; io { coordinator.verifyReady(value) {} }
        proof = proof.copy(version = 4); denied { io { coordinator.verifyReady(value) {} } }
    }
    @Test fun latestVerificationAndRegistrationRevocationPreventLaunch() {
        val value = begin(); register(value); io { coordinator.prepareLaunch(value) {} }
        denied { io { coordinator.verifyReady(value) { error("Lost human focus") } } }
        value.registration!!.close(); denied { io { coordinator.verifyReady(value) {} } }
        assertTrue(coordinator.needsRecovery()); assertFalse(value.pickerReturned)
    }
    @Test fun selectionRequiresExactlyOneOwnedAttemptAndReturnedPicker() {
        val value = begin(); register(value)
        denied { io { coordinator.selected(value) } }; denied { io { coordinator.deliver(value, PHONE) {} } }
        io { coordinator.prepareLaunch(value) {} }; denied { io { coordinator.selected(value) } }
        value.pickerReturned = true; io { coordinator.selected(value) }; assertPrivateJournal("selected", true)
        denied { io { coordinator.selected(value) } }; denied { io { coordinator.prepareLaunch(value) {} } }
    }
    @Test fun readPreparationRejectsUnownedCallbackAndRechecksHumanProofAndControl() {
        val value = begin(); register(value)
        denied { io { coordinator.prepareRead(value) {} } }
        io { coordinator.prepareLaunch(value) {} }; denied { io { coordinator.prepareRead(value) {} } }
        value.pickerReturned = true
        denied { io { coordinator.prepareRead(value) { error("No foreground human owner") } } }
        val original = proof; proof = proof.copy(record = "f".repeat(64))
        denied { io { coordinator.prepareRead(value) {} } }; proof = original
        io { coordinator.prepareRead(value) {} }; assertEquals("launch_pending", coordinator.status())
        value.registration!!.close(); denied { io { coordinator.prepareRead(value) {} } }
        assertTrue(coordinator.needsRecovery())
    }
    @Test fun successfulApprovalReturnsOnlyExactNonceKindValueAndDurablyConsumesBeforeReturn() {
        val value = begin("phone"); select(value)
        val result = io { coordinator.deliver(value, PHONE) {} }
        assertEquals(setOf("nonce", "kind", "value"), result.extras!!.keySet())
        assertEquals(NONCE, result.getStringExtra("nonce")); assertEquals("phone", result.getStringExtra("kind")); assertEquals(PHONE, result.getStringExtra("value"))
        assertNull(result.action); assertNull(result.data); assertNull(result.type); assertNull(result.clipData); assertNull(result.component); assertNull(result.selector); assertEquals(0, result.flags)
        assertPrivateJournal("closed", false); assertTrue(value.revoked.get()); assertNull(value.registration); assertNull(coordinator.session())
        assertTrue(FactoryInteractionAdmission.available()); assertFalse(MemoryUiAutomationGuard.isProtected())
        denied { io { coordinator.deliver(value, PHONE) {} } }; denied { begin() }
    }
    @Test fun secondApprovalRechecksHumanAndAllSourceProofFieldsWithoutLeakingData() {
        val value = begin("email"); select(value)
        denied { io { coordinator.deliver(value, EMAIL) { error("No second human approval") } } }
        val original = proof
        for (changed in listOf(proof.copy(appId = "example.other.app"), proof.copy(certificate = "f".repeat(64)), proof.copy(apk = "f".repeat(64)), proof.copy(version = 3), proof.copy(record = "f".repeat(64)))) {
            proof = changed; denied { io { coordinator.deliver(value, EMAIL) {} } }; assertEquals("selected", coordinator.status()); assertFalse(value.revoked.get())
        }
        proof = original; assertEquals(EMAIL, io { coordinator.deliver(value, EMAIL) {} }.getStringExtra("value")); assertPrivateJournal("closed", false)
    }
    @Test fun secondApprovalRejectsInvalidValuesAndStillRequiresLiveRegistration() {
        val value = begin(); select(value)
        for (datum in listOf("", " ", "x".repeat(257), "one\ntwo", "one\u202Etwo", "one\u0000two", "\uD800")) {
            denied { io { coordinator.deliver(value, datum) {} } }; assertEquals("selected", coordinator.status())
        }
        value.registration!!.close(); denied { io { coordinator.deliver(value, PHONE) {} } }; assertTrue(coordinator.needsRecovery())
    }
    @Test fun providerPreparationPreventsCloseDeliveryAndRecoveryUntilWorkerFinishes() {
        val value = begin(); select(value); value.preparing = true
        denied { io { coordinator.deliver(value, PHONE) {} } }; denied { io { coordinator.close(value, false, true) {} } }
        value.revoke(); denied { io { coordinator.close(value, true, true) {} } }
        assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available()); assertTrue(MemoryUiAutomationGuard.isProtected())
        value.preparing = false; assertEquals(Unit, io { coordinator.close(value, true, true) {} }); assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun initialPreparationCannotBeClosedEvenByExplicitRecovery() {
        val value = io { coordinator.begin(proof.appId, request(), preparing = true) {} }
        denied { io { coordinator.close(value, false, false) {} } }; value.revoke()
        denied { io { coordinator.close(value, true, true) {} } }; assertTrue(coordinator.needsRecovery())
        value.preparing = false; assertEquals(Unit, io { coordinator.close(value, true, true) {} })
    }
    @Test fun unreturnedPickerRequiresHumanExternalClosureAndCancellationReturnsNoData() {
        val value = begin(); register(value); io { coordinator.prepareLaunch(value) {} }
        denied { io { coordinator.close(value, false, false) {} } }; assertTrue(MemoryUiAutomationGuard.isProtected())
        assertEquals(Unit, io { coordinator.close(value, false, true) {} }); assertPrivateJournal("closed", false)
        assertTrue(value.revoked.get()); assertNull(value.registration)
    }
    @Test fun returnedCancelledPickerCanCloseWithoutClaimingAnySelectedDatum() {
        val value = begin(); register(value); io { coordinator.prepareLaunch(value) {} }; value.pickerReturned = true
        assertEquals(Unit, io { coordinator.close(value, false, false) {} }); assertPrivateJournal("closed", false)
        denied { io { coordinator.deliver(value, PHONE) {} } }
    }
    @Test fun exactFiveMinuteExpiryAndForeignSessionFailClosed() {
        val value = begin(); register(value)
        val foreign = FactoryContactCoordinator.Session("foreign", proof, request(), value.expiresAt)
        denied { coordinator.requireActive(foreign) }; denied { io { coordinator.prepareLaunch(foreign) {} } }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5)); denied { coordinator.requireActive(value) }; denied { io { coordinator.prepareLaunch(value) {} } }
        assertFalse(value.attempted); assertFalse(FactoryInteractionAdmission.available())
    }
    @Test fun selectedDatumCannotBeApprovedAfterExpiry() {
        val value = begin(); select(value); ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        denied { io { coordinator.deliver(value, PHONE) {} } }; assertPrivateJournal("selected", true)
    }
    @Test fun sourceVerifierPinsOneInstalledUidAndRejectsSharedUid() {
        val value = begin(); val pm = shadowOf(context.packageManager); val uid = 20002
        pm.installPackage(PackageInfo().apply { packageName = proof.appId; applicationInfo = ApplicationInfo().apply { packageName = proof.appId; this.uid = uid } })
        pm.setPackagesForUid(uid, proof.appId)
        val verifier = io { coordinator.sourceVerifier(value) }; assertTrue(verifier.allowed(uid)); assertFalse(verifier.allowed(uid + 1))
        pm.setPackagesForUid(uid, proof.appId, "example.shared.app"); denied { io { coordinator.sourceVerifier(value) } }
    }
    @Test fun actualControlAuthenticatesNonceAndCancellationRevokesAuthority() {
        val value = begin(); val source = ExternalLaunchControl(value.request.nonce) { uid -> uid == Binder.getCallingUid() }
        try {
            value.registration = io { ExternalLaunchControl.register(source, value.request.nonce, { true }, Runnable { value.revoke() }) }
            assertFalse(value.registration!!.isRevoked); value.registration!!.close(); assertTrue(value.revoked.get())
            denied { io { ExternalLaunchControl.register(source, "e".repeat(64), { true }, Runnable {}) } }
            denied { io { coordinator.prepareLaunch(value) {} } }; assertTrue(coordinator.needsRecovery())
        } finally { source.close() }
    }
    @Test fun failedDurableBeginRetainsProtectionUntilHumanRecoveryPersists() {
        root.writeText("Synthetic filesystem obstruction")
        denied { begin() }; assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
        assertTrue(MemoryUiAutomationGuard.isProtected()); denied { io { coordinator.close(coordinator.session(), true, true) {} } }
        assertTrue(coordinator.needsRecovery()); check(root.delete()); check(root.mkdirs())
        assertEquals(Unit, io { coordinator.close(coordinator.session(), true, true) {} }); assertEquals("closed_outcome_unknown", coordinator.status())
    }
    @Test fun failedDurableDeliveryNeverReturnsDataAndCannotReplay() {
        val value = begin(); select(value); check(root.deleteRecursively()); root.writeText("Synthetic delivery obstruction")
        denied { io { coordinator.deliver(value, PHONE) {} } }; assertTrue(coordinator.needsRecovery()); assertTrue(value.revoked.get())
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        check(root.delete()); check(root.mkdirs()); denied { io { coordinator.deliver(value, PHONE) {} } }
        io { coordinator.close(value, true, true) {} }; assertPrivateJournal("closed_outcome_unknown", false)
    }
    @Test fun interruptedReviewPendingAndSelectedRestoreOnlyUnknownWithoutDataOrBinderAuthority() {
        for (old in listOf("review", "launch_pending", "selected", "outcome_unknown")) {
            root.mkdirs(); journal.writeText(saved(old, true)); val restored = make(); io { restored.restore() }
            assertNull(restored.session()); assertEquals("outcome_unknown", restored.status()); assertTrue(restored.needsRecovery())
            assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
            denied { io { restored.close(null, true, false) {} } }; denied { io { restored.close(null, true, true) { error("No human") } } }
            assertEquals(Unit, io { restored.close(null, true, true) {} }); assertPrivateJournal("closed_outcome_unknown", false)
        }
    }
    @Test fun malformedOversizedWrongTypeExtraFieldAndImpossibleJournalsFailClosed() {
        for (body in listOf("{", "x".repeat(4097), saved("review", false), saved("closed", true),
            JSONObject(saved("selected", true)).put("value", PHONE).toString(), JSONObject(saved("selected", true)).put("schemaVersion", 2).toString(),
            JSONObject(saved("selected", true)).put("open", "true").toString(), JSONObject(saved("selected", true)).put("nonce", "D".repeat(64)).toString())) {
            root.mkdirs(); journal.writeText(body); val restored = make(); io { restored.restore() }
            assertTrue(restored.needsRecovery()); assertTrue(MemoryUiAutomationGuard.isProtected()); assertNull(restored.session())
            assertEquals(Unit, io { restored.close(null, true, true) {} }); assertEquals("closed_outcome_unknown", restored.status())
        }
    }
    @Test fun closedJournalPreventsNonceReplayAcrossRestore() {
        val value = begin(); io { coordinator.close(value, false, false) {} }
        val restored = make(); io { restored.restore() }; assertTrue(restored.canBegin()); assertFalse(restored.needsRecovery())
        denied { io { restored.begin(proof.appId, request()) {} } }
        assertNotNull(io { restored.begin(proof.appId, request().copy(nonce = "e".repeat(64))) {} })
    }
    @Test fun contactsAndExternalRecoveryKeepIndependentGuardsInEitherClosureOrder() {
        val externalRoot = File(context.noBackupFilesDir, "factory-external-launch")
        for (contactsFirst in listOf(true, false)) {
            root.mkdirs(); externalRoot.mkdirs(); journal.writeText(saved("selected", true)); File(externalRoot, "interaction.json").writeText(saved("launch_pending", true, "e".repeat(64)))
            val contacts = make(); val external = FactoryExternalLaunchCoordinator(context) { _, _ -> proof }.also { externalOwners += it }
            io { contacts.restore(); external.restore() }
            assertTrue(contacts.needsRecovery()); assertTrue(external.needsRecovery())
            if (contactsFirst) io { contacts.close(null, true, true) {} } else io { external.close(null, true, true) {} }
            assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
            assertTrue(if (contactsFirst) external.needsRecovery() else contacts.needsRecovery())
            denied { MemoryUiAutomationGuard.captureAutomationEpoch() }
            if (contactsFirst) io { external.close(null, true, true) {} } else io { contacts.close(null, true, true) {} }
            assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
        }
    }
    @Test fun recoveryTokenIsExactOneUseWithNoTransportOrExtraAuthority() {
        val token = FactoryContactCoordinator.recoveryIntent(context)
        val invalid = listOf(Intent(token).putExtra("kind", "phone"), Intent(token).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION), Intent(token).setData(Uri.parse(ROW_URI)),
            Intent(token).apply { clipData = ClipData.newPlainText("fixture", "fixture") }, Intent(token).apply { selector = Intent("fixture") })
        invalid.forEach { assertFalse(FactoryContactCoordinator.consumeRecoveryToken(it)) }
        assertTrue(FactoryContactCoordinator.consumeRecoveryToken(token)); assertFalse(FactoryContactCoordinator.consumeRecoveryToken(token))
        assertFalse(FactoryContactCoordinator.consumeRecoveryToken(Intent().putExtra("nativeRecoveryToken", "forged")))
    }
    private fun saved(state: String, open: Boolean, nonce: String = NONCE) = JSONObject().put("schemaVersion", 1).put("state", state).put("open", open).put("nonce", nonce).toString()
    companion object {
        const val PHONE = "  +1 (202) 555-0123 ext. 42  "
        const val EMAIL = "Case.Sensitive+Fixture@Example.invalid"
        const val ROW_URI = "content://com.android.contacts/data/42"
        val NONCE = "d".repeat(64)
    }
}
