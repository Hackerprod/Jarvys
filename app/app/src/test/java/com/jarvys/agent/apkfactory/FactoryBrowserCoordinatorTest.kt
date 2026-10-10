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
import com.jarvys.factory.runtime.BrowserLaunchControl
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

/** Synthetic URLs and native Binder fixtures only. No browser, DNS, or network is opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryBrowserCoordinatorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-browser")
    private var proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryBrowserCoordinator
    private val owners = mutableListOf<FactoryBrowserCoordinator>()
    private val executor = Executors.newSingleThreadExecutor()
    private fun <T> io(block: () -> T): T = executor.submit(Callable(block)).get(5, TimeUnit.SECONDS)
    private fun denied(block: () -> Unit) = assertTrue("Expected rejection", runCatching(block).isFailure)
    private fun make(verify: (String) -> FactoryDocumentCoordinator.Proof = { proof }) = FactoryBrowserCoordinator(context, verify).also { owners += it }
    private fun intent() = Intent(context, FactoryBrowserActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("nonce", "d".repeat(64)); putString("url", URL); putBinder("control", Binder())
    })
    private fun request() = FactoryBrowserCoordinator.Request.parse(intent())
    private fun begin() = io { coordinator.begin(proof.appId, request()) {} }
    private fun register(value: FactoryBrowserCoordinator.Session, callback: Runnable = Runnable { value.revoke() }) {
        val constructor = BrowserLaunchControl.Registration::class.java.getDeclaredConstructor(IBinder::class.java, Runnable::class.java).apply { isAccessible = true }
        value.registration = constructor.newInstance(Binder(), callback)
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
        executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available()); root.deleteRecursively()
    }
    @Test fun exactRequestShapeRejectsEveryTransportAndTypeConfusion() {
        val invalid = mutableListOf(intent().putExtra("extra", true), intent().putExtra("protocolVersion", 1L), intent().putExtra("protocolVersion", 2),
            intent().putExtra("nonce", "D".repeat(64)), intent().putExtra("nonce", "d".repeat(63)), intent().putExtra("url", 1), intent().putExtra("control", "binder"),
            intent().setData(Uri.parse("https://example.invalid/transport")), intent().apply { component = null },
            intent().apply { clipData = ClipData.newPlainText("synthetic", "fixture") }, intent().apply { selector = Intent("synthetic") })
        for (flag in listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) invalid += intent().addFlags(flag)
        for (key in listOf("protocolVersion", "nonce", "url", "control")) invalid += intent().apply { removeExtra(key) }
        invalid.forEach { denied { FactoryBrowserCoordinator.Request.parse(it) } }
        assertEquals(URL, request().url); assertEquals("d".repeat(64), request().nonce)
    }
    @Test fun dangerousUrlGrammarCannotEnterHostAuthority() {
        for (url in listOf("http://example.invalid/", "https://user@example.invalid/", "https://example.invalid/#fragment", "https://example.invalid:8443/", "https://127.0.0.1/", "https://localhost/", "https://example.invalid/%0a", "intent://example.invalid/", "https://example.invalid/" + "a".repeat(2048))) {
            denied { FactoryBrowserCoordinator.Request.parse(intent().putExtra("url", url)) }
            denied { io { coordinator.begin(proof.appId, request().copy(url = url)) {} } }
            assertFalse(File(root, "interaction.json").exists()); assertTrue(coordinator.canBegin())
        }
    }
    @Test fun absentWrongOrRejectedCallerProducesNoJournalOrAdmission() {
        denied { io { coordinator.begin(null, request()) {} } }; denied { io { coordinator.begin("example.wrong.app", request()) {} } }
        coordinator = make { error("No latest signed evidence") }
        denied { begin() }; assertFalse(File(root, "interaction.json").exists()); assertTrue(coordinator.canBegin())
    }
    @Test fun noJournalIoRunsOnMainAndInactiveCallerCannotBegin() {
        denied { coordinator.begin(proof.appId, request()) {} }; denied { coordinator.restore() }
        denied { io { coordinator.begin(proof.appId, request()) { error("Caller is no longer active") } } }
        assertTrue(coordinator.canBegin()); assertFalse(File(root, "interaction.json").exists())
    }
    @Test fun reviewJournalContainsOnlyMinimalNonceStateAndNeverUrlOrRecipient() {
        val value = begin(); val raw = File(root, "interaction.json").readText(); val journal = JSONObject(raw)
        assertEquals(setOf("schemaVersion", "state", "open", "nonce"), journal.keys().asSequence().toSet())
        assertEquals("review", journal.getString("state")); assertTrue(journal.getBoolean("open"))
        assertFalse(raw.contains(URL)); assertFalse(raw.contains("example.invalid")); assertFalse(raw.contains(proof.appId)); assertFalse(raw.contains(proof.apk))
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        denied { MemoryUiAutomationGuard.captureAutomationEpoch() }; denied { io { make().begin(proof.appId, request().copy(nonce = "e".repeat(64))) {} } }
        val receipt = io { coordinator.close(value, false, false) {} }!!
        assertEquals(setOf("nonce", "launchRequested", "pageLoadConfirmed"), receipt.extras!!.keySet())
        assertEquals(value.request.nonce, receipt.getStringExtra("nonce")); assertFalse(receipt.getBooleanExtra("launchRequested", true)); assertFalse(receipt.getBooleanExtra("pageLoadConfirmed", true))
        assertNull(receipt.data); assertNull(receipt.clipData); assertNull(receipt.component); assertEquals(0, receipt.flags)
        assertFalse(MemoryUiAutomationGuard.isProtected()); denied { begin() }
    }
    @Test fun launchRequiresHumanRegistrationAndEveryLatestProofFieldUnchanged() {
        val value = begin(); denied { io { coordinator.prepareLaunch(value) { error("No human") } } }
        denied { io { coordinator.prepareLaunch(value) {} } }; register(value)
        val original = proof
        for (changed in listOf(proof.copy(appId = "example.other.app"), proof.copy(certificate = "f".repeat(64)), proof.copy(apk = "f".repeat(64)), proof.copy(version = 3), proof.copy(record = "f".repeat(64)))) {
            proof = changed; denied { io { coordinator.prepareLaunch(value) {} } }; assertFalse(value.attempted); assertEquals("review", coordinator.status())
        }
        proof = original; io { coordinator.prepareLaunch(value) {} }
        assertTrue(value.attempted); assertFalse(value.launchRequested); assertEquals("launch_pending", JSONObject(File(root, "interaction.json").readText()).getString("state"))
        denied { io { coordinator.prepareLaunch(value) {} } }; io { coordinator.verifyReady(value) {} }
        proof = proof.copy(version = 4); denied { io { coordinator.verifyReady(value) {} } }
    }
    @Test fun finalVerificationRechecksHumanAndSourceCancellationCannotAuthorizeLaunch() {
        val value = begin(); register(value); io { coordinator.prepareLaunch(value) {} }
        denied { io { coordinator.verifyReady(value) { error("Lost focus") } } }
        value.registration!!.close(); denied { io { coordinator.verifyReady(value) {} } }
        assertTrue(coordinator.needsRecovery()); assertFalse(value.launchRequested)
    }
    @Test fun browserAttemptNeedsExplicitHumanClosureAndNeverConfirmsPageLoad() {
        val value = begin(); register(value); io { coordinator.prepareLaunch(value) {} }; value.launchRequested = true
        denied { io { coordinator.close(value, false, false) {} } }; assertTrue(MemoryUiAutomationGuard.isProtected())
        val receipt = io { coordinator.close(value, false, true) {} }!!
        assertTrue(receipt.getBooleanExtra("launchRequested", false)); assertFalse(receipt.getBooleanExtra("pageLoadConfirmed", true))
        assertTrue(value.registration == null); assertTrue(value.revoked.get()); assertEquals("closed", coordinator.status())
    }
    @Test fun exactFiveMinuteExpiryAndForeignSessionFailClosed() {
        val value = begin(); register(value)
        val foreign = FactoryBrowserCoordinator.Session("foreign", proof, request(), value.expiresAt)
        denied { coordinator.requireActive(foreign) }; denied { io { coordinator.prepareLaunch(foreign) {} } }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5)); denied { coordinator.requireActive(value) }; denied { io { coordinator.prepareLaunch(value) {} } }
        assertFalse(value.attempted); assertFalse(FactoryInteractionAdmission.available())
    }
    @Test fun sourceVerifierPinsOneInstalledUidAndRejectsSharedUid() {
        val value = begin(); val pm = shadowOf(context.packageManager); val uid = 20002
        pm.installPackage(PackageInfo().apply { packageName = proof.appId; applicationInfo = ApplicationInfo().apply { packageName = proof.appId; this.uid = uid } })
        pm.setPackagesForUid(uid, proof.appId)
        val verifier = io { coordinator.sourceVerifier(value) }; assertTrue(verifier.allowed(uid)); assertFalse(verifier.allowed(uid + 1))
        pm.setPackagesForUid(uid, proof.appId, "example.shared.app"); denied { io { coordinator.sourceVerifier(value) } }
    }
    @Test fun actualControlRegistrationAuthenticatesNonceAndCancellationRevokes() {
        val value = begin(); val source = BrowserLaunchControl(value.request.nonce) { uid -> uid == Binder.getCallingUid() }
        value.registration = io { BrowserLaunchControl.register(source, value.request.nonce, { true }, Runnable { value.revoke() }) }
        assertFalse(value.registration!!.isRevoked); value.registration!!.close(); assertTrue(value.revoked.get())
        denied { io { BrowserLaunchControl.register(source, "e".repeat(64), { true }, Runnable {}) } }
    }
    @Test fun restoreKeepsUnknownGuardButNeverUrlLaunchOrBinderAuthority() {
        val original = begin(); register(original); io { coordinator.prepareLaunch(original) {} }
        val restored = make(); io { restored.restore() }
        assertNull(restored.session()); assertEquals("outcome_unknown", restored.status()); assertTrue(restored.needsRecovery())
        denied { io { restored.close(null, true, false) {} } }; denied { io { restored.close(null, true, true) { error("No human") } } }
        assertNull(io { restored.close(null, true, true) {} }); assertFalse(FactoryInteractionAdmission.available())
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(File(root, "interaction.json").readText().contains(URL))
    }
    @Test fun malformedOversizedExtraFieldAndImpossibleJournalsFailClosed() {
        for (body in listOf("{", "x".repeat(4097), "{\"schemaVersion\":1,\"state\":\"review\",\"open\":false,\"nonce\":\"${"d".repeat(64)}\"}",
            JSONObject().put("schemaVersion", 1).put("state", "launch_pending").put("open", true).put("nonce", "d".repeat(64)).put("url", URL).toString(),
            JSONObject().put("schemaVersion", 2).put("state", "closed").put("open", false).put("nonce", "d".repeat(64)).toString())) {
            root.mkdirs(); File(root, "interaction.json").writeText(body); val restored = make(); io { restored.restore() }
            assertTrue(restored.needsRecovery()); assertTrue(MemoryUiAutomationGuard.isProtected()); assertNull(restored.session())
            assertNull(io { restored.close(null, true, true) {} }); assertEquals("closed_outcome_unknown", restored.status())
        }
    }
    @Test fun pendingPreparationRetainsAdmissionEvenAfterRevocationAndExplicitRecovery() {
        val value = begin(); value.preparing = true; value.revoke()
        denied { io { coordinator.close(value, true, true) {} } }; assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
        value.preparing = false; assertNull(io { coordinator.close(value, true, true) {} }); assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun closedJournalPreventsNonceReplayAcrossRestore() {
        val value = begin(); io { coordinator.close(value, false, false) {} }
        val restored = make(); io { restored.restore() }; assertTrue(restored.canBegin()); assertFalse(restored.needsRecovery())
        denied { io { restored.begin(proof.appId, request()) {} } }
        val fresh = io { restored.begin(proof.appId, request().copy(nonce = "e".repeat(64))) {} }; assertNotNull(fresh)
    }
    @Test fun recoveryTokenIsExactOneUseWithNoTransportOrExtraAuthority() {
        val token = FactoryBrowserCoordinator.recoveryIntent(context)
        val invalid = listOf(Intent(token).putExtra("open", true), Intent(token).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION), Intent(token).setData(Uri.parse(URL)), Intent(token).apply { clipData = ClipData.newPlainText("fixture", "fixture") }, Intent(token).apply { selector = Intent("fixture") })
        invalid.forEach { assertFalse(FactoryBrowserCoordinator.consumeRecoveryToken(it)) }
        assertTrue(FactoryBrowserCoordinator.consumeRecoveryToken(token)); assertFalse(FactoryBrowserCoordinator.consumeRecoveryToken(token))
        assertFalse(FactoryBrowserCoordinator.consumeRecoveryToken(Intent().putExtra("nativeRecoveryToken", "forged")))
    }
    companion object { const val URL = "https://example.invalid/review?fixture=browser" }
}
