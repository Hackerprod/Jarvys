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

/** Synthetic typed actions and Binder fixtures only. No maps, dialer, calls, navigation, or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FactoryExternalLaunchCoordinatorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.noBackupFilesDir, "factory-external-launch")
    private var proof = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "c".repeat(64))
    private lateinit var coordinator: FactoryExternalLaunchCoordinator
    private val owners = mutableListOf<FactoryExternalLaunchCoordinator>()
    private val browserOwners = mutableListOf<FactoryBrowserCoordinator>()
    private val executor = Executors.newSingleThreadExecutor()
    private fun <T> io(block: () -> T): T = executor.submit(Callable(block)).get(5, TimeUnit.SECONDS)
    private fun denied(block: () -> Unit) = assertTrue("Expected rejection", runCatching(block).isFailure)
    private fun make(verify: (String, String) -> FactoryDocumentCoordinator.Proof = { _, _ -> proof }) = FactoryExternalLaunchCoordinator(context, verify).also { owners += it }
    private fun intent(method: String = "maps.open", args: String = ARGS) = Intent(context, FactoryExternalActionActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("nonce", "d".repeat(64)); putString("method", method); putString("args", args); putBinder("control", Binder())
    })
    private fun request(method: String = "maps.open", args: String = ARGS) = FactoryExternalLaunchCoordinator.Request.parse(intent(method, args))
    private fun begin() = io { coordinator.begin(proof.appId, request()) {} }
    private fun register(value: FactoryExternalLaunchCoordinator.Session, callback: Runnable = Runnable { value.revoke() }) {
        val constructor = ExternalLaunchControl.Registration::class.java.getDeclaredConstructor(IBinder::class.java, Runnable::class.java).apply { isAccessible = true }
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
        browserOwners.asReversed().forEach { owner ->
            if (owner.needsRecovery()) io { owner.close(owner.session(), true, true) {} }
            else owner.session()?.let { io { owner.close(it, false, true) {} } }
        }
        executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available()); root.deleteRecursively(); File(context.noBackupFilesDir, "factory-browser").deleteRecursively()
    }
    @Test fun exactRequestShapeRejectsEveryTransportAndTypeConfusion() {
        val invalid = mutableListOf(intent().putExtra("extra", true), intent().putExtra("protocolVersion", 1L), intent().putExtra("protocolVersion", 2),
            intent().putExtra("nonce", "D".repeat(64)), intent().putExtra("nonce", "d".repeat(63)), intent().putExtra("method", 1),
            intent().putExtra("args", 1), intent().putExtra("args", Bundle()), intent().putExtra("control", "binder"),
            intent().setData(Uri.parse("geo:1,2")), intent().apply { component = null },
            intent().apply { action = Intent.ACTION_VIEW }, intent().setType("application/json"), intent().addCategory(Intent.CATEGORY_DEFAULT),
            intent().setPackage(proof.appId), intent().apply { clipData = ClipData.newPlainText("synthetic", "fixture") },
            intent().apply { selector = Intent("synthetic") })
        for (flag in listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) invalid += intent().addFlags(flag)
        for (key in listOf("protocolVersion", "nonce", "method", "args", "control")) invalid += intent().apply { removeExtra(key) }
        invalid.forEach { denied { FactoryExternalLaunchCoordinator.Request.parse(it) } }
        assertEquals(QUERY, request().spec.display); assertEquals("maps.open", request().method)
        assertEquals("maps", request().spec.capability); assertEquals("d".repeat(64), request().nonce)
    }
    @Test fun typedMapAndDialerGrammarCannotSmuggleRawIntentsOrUntypedArguments() {
        val invalidMaps = listOf("{}", "[]", "null", "{", "{query:'park'}", "{\"query\":\"park\",\"query\":\"other\"}",
            "{\"query\":null}", "{\"query\":42}", "{\"query\":\"\"}", "{\"query\":\"   \"}", "{\"query\":\"a\\nb\"}",
            "{\"query\":\"park\",\"latitude\":1,\"longitude\":2}", "{\"latitude\":\"1\",\"longitude\":2}",
            "{\"latitude\":true,\"longitude\":2}", "{\"latitude\":null,\"longitude\":2}", "{\"latitude\":91,\"longitude\":2}",
            "{\"latitude\":1,\"longitude\":181}", "{\"latitude\":1e999,\"longitude\":2}", "{\"latitude\":1}",
            "{\"uri\":\"geo:1,2\"}", "{\"query\":\"park\",\"component\":\"example.hidden.App\"}",
            JSONObject().put("query", "a".repeat(257)).toString(), JSONObject().put("query", "a\u202Eb").toString(),
            JSONObject().put("query", "a\u0000b").toString(), "x".repeat(8193))
        val invalidPhones = listOf("{}", "{\"number\":123}", "{\"number\":null}", "{\"number\":\"\"}",
            "{\"number\":\"+\"}", "{\"number\":\"tel:123\"}", "{\"number\":\"*123#\"}", "{\"number\":\"123,4\"}",
            "{\"number\":\"123;4\"}", "{\"number\":\"123p4\"}", "{\"number\":\"123w4\"}", "{\"number\":\"+1 234\"}",
            "{\"number\":\"１２３\"}", "{\"number\":\"1234567890123456\"}", "{\"number\":\"123\",\"action\":\"android.intent.action.CALL\"}",
            "{\"number\":\"123\",\"uri\":\"tel:456\"}")
        val malformed = invalidMaps.map { "maps.open" to it } + invalidPhones.map { "phone.dial" to it } +
            listOf("phone.call" to "{\"number\":\"123\"}", "maps.navigate" to ARGS, "browser.open" to ARGS)
        for ((method, args) in malformed) {
            denied { FactoryExternalLaunchCoordinator.Request.parse(intent(method, args)) }
            denied { io { coordinator.begin(proof.appId, request().copy(method = method, args = args)) {} } }
            assertFalse(File(root, "interaction.json").exists()); assertTrue(coordinator.canBegin())
        }
    }
    @Test fun validTypedCoordinatesQueriesAndDialerNumbersUseExactlyTheirOwnCapability() {
        val cases = listOf(Triple("maps.open", "{\"latitude\":-90,\"longitude\":180}", "maps"),
            Triple("maps.open", ARGS, "maps"), Triple("phone.dial", "{\"number\":\"+12025550123\"}", "phone"))
        for ((index, row) in cases.withIndex()) {
            val seen = mutableListOf<Pair<String, String>>()
            coordinator = make { appId, capability -> seen += appId to capability; check(capability == row.third); proof }
            val value = io { coordinator.begin(proof.appId, request(row.first, row.second).copy(nonce = (index + 1).toString().repeat(64))) {} }
            register(value); io { coordinator.prepareLaunch(value) {} }; io { coordinator.verifyReady(value) {} }
            assertEquals(List(3) { proof.appId to row.third }, seen)
            val raw = File(root, "interaction.json").readText()
            for (sensitive in listOf(value.request.method, value.request.args, value.request.spec.uri, value.request.spec.display, proof.appId)) assertFalse(raw.contains(sensitive))
            val receipt = io { coordinator.close(value, false, true) {} }!!
            assertFalse(receipt.getBooleanExtra("actionConfirmed", true))
        }
    }
    @Test fun approvingMapsDoesNotApprovePhoneAndApprovingPhoneDoesNotApproveMaps() {
        for ((method, args, permitted) in listOf(Triple("maps.open", ARGS, "phone"), Triple("phone.dial", "{\"number\":\"+12025550123\"}", "maps"))) {
            var requestedCapability: String? = null
            coordinator = make { _, capability -> requestedCapability = capability; check(capability == permitted) { "Capability is not in the latest signed evidence" }; proof }
            denied { io { coordinator.begin(proof.appId, request(method, args)) {} } }
            assertNotEquals(permitted, requestedCapability); assertNotNull(requestedCapability)
            assertTrue(coordinator.canBegin()); assertFalse(MemoryUiAutomationGuard.isProtected()); assertFalse(File(root, "interaction.json").exists())
        }
    }
    @Test fun uriLookingQueryTextIsEncodedAsOneLiteralQueryAndCannotAddRoutingAuthority() {
        val text = "geo:1,2?q=elsewhere&mode=drive#intent://component%2Fhidden"
        val value = request("maps.open", JSONObject().put("query", text).toString())
        assertEquals(text, value.spec.display)
        assertEquals("geo:0,0?q=geo%3A1%2C2%3Fq%3Delsewhere%26mode%3Ddrive%23intent%3A%2F%2Fcomponent%252Fhidden", value.spec.uri)
        assertEquals("geo:0,0", request("maps.open", "{\"latitude\":-0.0,\"longitude\":0}").spec.uri)
        assertFalse(File(root, "interaction.json").exists())
    }
    @Test fun absentWrongOrRejectedCallerProducesNoJournalOrAdmission() {
        denied { io { coordinator.begin(null, request()) {} } }; denied { io { coordinator.begin("example.wrong.app", request()) {} } }
        coordinator = make { _, _ -> error("No latest signed evidence") }
        denied { begin() }; assertFalse(File(root, "interaction.json").exists()); assertTrue(coordinator.canBegin())
    }
    @Test fun noJournalIoRunsOnMainAndInactiveCallerCannotBegin() {
        denied { coordinator.begin(proof.appId, request()) {} }; denied { coordinator.restore() }
        denied { io { coordinator.begin(proof.appId, request()) { error("Caller is no longer active") } } }
        assertTrue(coordinator.canBegin()); assertFalse(File(root, "interaction.json").exists())
    }
    @Test fun automationAlreadyInFlightTaintsReviewAndCannotAcquireAuthority() {
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch(); val active = MemoryUiAutomationGuard.beginAsyncAction(epoch)
        try { denied { begin() }; assertNull(coordinator.session()); assertFalse(File(root, "interaction.json").exists()) }
        finally { active.close() }
        assertTrue(coordinator.canBegin()); assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun failedDurableWriteRetainsGuardUntilActualHumanRecoveryCanPersist() {
        root.writeText("Synthetic filesystem obstruction")
        denied { begin() }; assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
        assertTrue(MemoryUiAutomationGuard.isProtected()); denied { io { coordinator.close(coordinator.session(), true, true) {} } }
        assertTrue(coordinator.needsRecovery()); check(root.delete()); check(root.mkdirs())
        assertNull(io { coordinator.close(coordinator.session(), true, true) {} }); assertEquals("closed_outcome_unknown", coordinator.status())
        assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun reviewJournalContainsOnlyMinimalNonceStateAndNeverTypedDataOrRecipient() {
        val value = begin(); val raw = File(root, "interaction.json").readText(); val journal = JSONObject(raw)
        assertEquals(setOf("schemaVersion", "state", "open", "nonce"), journal.keys().asSequence().toSet())
        assertEquals("review", journal.getString("state")); assertTrue(journal.getBoolean("open"))
        assertFalse(raw.contains(QUERY)); assertFalse(raw.contains(ARGS)); assertFalse(raw.contains("maps.open")); assertFalse(raw.contains(request().spec.uri)); assertFalse(raw.contains(proof.appId)); assertFalse(raw.contains(proof.apk))
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
        denied { MemoryUiAutomationGuard.captureAutomationEpoch() }; denied { io { make().begin(proof.appId, request().copy(nonce = "e".repeat(64))) {} } }
        val receipt = io { coordinator.close(value, false, false) {} }!!
        assertEquals(setOf("nonce", "launchRequested", "actionConfirmed"), receipt.extras!!.keySet())
        assertEquals(value.request.nonce, receipt.getStringExtra("nonce")); assertFalse(receipt.getBooleanExtra("launchRequested", true)); assertFalse(receipt.getBooleanExtra("actionConfirmed", true))
        assertNull(receipt.data); assertNull(receipt.clipData); assertNull(receipt.component); assertEquals(0, receipt.flags)
        assertFalse(MemoryUiAutomationGuard.isProtected()); denied { begin() }
    }
    @Test fun mapsLaunchRequiresHumanRegistrationAndEveryLatestProofFieldUnchanged() = latestProofFieldsUnchanged("maps.open", ARGS)
    @Test fun phoneLaunchRequiresHumanRegistrationAndEveryLatestProofFieldUnchanged() = latestProofFieldsUnchanged("phone.dial", "{\"number\":\"+12025550123\"}")
    private fun latestProofFieldsUnchanged(method: String, args: String) {
        val value = io { coordinator.begin(proof.appId, request(method, args)) {} }; denied { io { coordinator.prepareLaunch(value) { error("No human") } } }
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
    @Test fun externalAttemptNeedsExplicitHumanClosureAndNeverConfirmsAction() {
        val value = begin(); register(value); io { coordinator.prepareLaunch(value) {} }; value.launchRequested = true
        denied { io { coordinator.close(value, false, false) {} } }; assertTrue(MemoryUiAutomationGuard.isProtected())
        val receipt = io { coordinator.close(value, false, true) {} }!!
        assertTrue(receipt.getBooleanExtra("launchRequested", false)); assertFalse(receipt.getBooleanExtra("actionConfirmed", true))
        assertTrue(value.registration == null); assertTrue(value.revoked.get()); assertEquals("closed", coordinator.status())
    }
    @Test fun exactFiveMinuteExpiryAndForeignSessionFailClosed() {
        val value = begin(); register(value)
        val foreign = FactoryExternalLaunchCoordinator.Session("foreign", proof, request(), value.expiresAt)
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
        val value = begin(); val source = ExternalLaunchControl(value.request.nonce) { uid -> uid == Binder.getCallingUid() }
        value.registration = io { ExternalLaunchControl.register(source, value.request.nonce, { true }, Runnable { value.revoke() }) }
        assertFalse(value.registration!!.isRevoked); value.registration!!.close(); assertTrue(value.revoked.get())
        denied { io { ExternalLaunchControl.register(source, "e".repeat(64), { true }, Runnable {}) } }
    }
    @Test fun restoreKeepsUnknownGuardButNeverTypedDataLaunchOrBinderAuthority() {
        val original = begin(); register(original); io { coordinator.prepareLaunch(original) {} }
        val restored = make(); io { restored.restore() }
        assertNull(restored.session()); assertEquals("outcome_unknown", restored.status()); assertTrue(restored.needsRecovery())
        denied { io { restored.close(null, true, false) {} } }; denied { io { restored.close(null, true, true) { error("No human") } } }
        assertNull(io { restored.close(null, true, true) {} }); assertFalse(FactoryInteractionAdmission.available())
        assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(File(root, "interaction.json").readText().contains(QUERY))
    }
    @Test fun malformedOversizedExtraFieldAndImpossibleJournalsFailClosed() {
        for (body in listOf("{", "x".repeat(4097), "{\"schemaVersion\":1,\"state\":\"review\",\"open\":false,\"nonce\":\"${"d".repeat(64)}\"}",
            JSONObject().put("schemaVersion", 1).put("state", "launch_pending").put("open", true).put("nonce", "d".repeat(64)).put("args", ARGS).toString(),
            JSONObject().put("schemaVersion", 2).put("state", "closed").put("open", false).put("nonce", "d".repeat(64)).toString())) {
            root.mkdirs(); File(root, "interaction.json").writeText(body); val restored = make(); io { restored.restore() }
            assertTrue(restored.needsRecovery()); assertTrue(MemoryUiAutomationGuard.isProtected()); assertNull(restored.session())
            assertNull(io { restored.close(null, true, true) {} }); assertEquals("closed_outcome_unknown", restored.status())
        }
    }
    @Test fun pendingPreparationRetainsAdmissionEvenAfterRevocationAndExplicitRecovery() {
        val value = io { coordinator.begin(proof.appId, request(), preparing = true) {} }; assertTrue(value.preparing)
        denied { io { coordinator.close(value, false, false) {} } }; value.revoke()
        denied { io { coordinator.close(value, true, true) {} } }; assertTrue(coordinator.needsRecovery()); assertFalse(FactoryInteractionAdmission.available())
        value.preparing = false; assertNull(io { coordinator.close(value, true, true) {} }); assertTrue(FactoryInteractionAdmission.available())
    }
    @Test fun closedJournalPreventsNonceReplayAcrossRestore() {
        val value = begin(); io { coordinator.close(value, false, false) {} }
        val restored = make(); io { restored.restore() }; assertTrue(restored.canBegin()); assertFalse(restored.needsRecovery())
        denied { io { restored.begin(proof.appId, request()) {} } }
        val fresh = io { restored.begin(proof.appId, request().copy(nonce = "e".repeat(64))) {} }; assertNotNull(fresh)
    }
    @Test fun browserControlDescriptorCannotAuthorizeMapsOrPhoneAndExternalCannotAuthorizeBrowser() {
        val external = ExternalLaunchControl("d".repeat(64)) { true }
        val browser = com.jarvys.factory.runtime.BrowserLaunchControl("d".repeat(64)) { true }
        try {
            denied { io { ExternalLaunchControl.register(browser, "d".repeat(64), { true }, Runnable {}) } }
            denied { io { com.jarvys.factory.runtime.BrowserLaunchControl.register(external, "d".repeat(64), { true }, Runnable {}) } }
        } finally { external.close(); browser.close() }
        assertTrue(coordinator.canBegin()); assertFalse(File(root, "interaction.json").exists())
    }
    @Test fun browserAndExternalRecoveryJournalsCoexistAndClosingEitherPreservesTheOtherOwner() {
        val browserRoot = File(context.noBackupFilesDir, "factory-browser")
        for (externalFirst in listOf(true, false)) {
            val externalJournal = JSONObject().put("schemaVersion", 1).put("state", "launch_pending").put("open", true).put("nonce", "d".repeat(64)).toString()
            val browserJournal = JSONObject().put("schemaVersion", 1).put("state", "review").put("open", true).put("nonce", "e".repeat(64)).toString()
            root.mkdirs(); browserRoot.mkdirs(); File(root, "interaction.json").writeText(externalJournal); File(browserRoot, "interaction.json").writeText(browserJournal)
            val external = make(); val browser = FactoryBrowserCoordinator(context) { proof }.also { browserOwners += it }
            io { external.restore(); browser.restore() }
            assertTrue(external.needsRecovery()); assertTrue(browser.needsRecovery()); assertNull(external.session()); assertNull(browser.session())
            assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
            if (externalFirst) {
                assertNull(io { external.close(null, true, true) {} })
                assertTrue(browser.needsRecovery()); assertEquals("outcome_unknown", JSONObject(File(browserRoot, "interaction.json").readText()).getString("state"))
            } else {
                assertNull(io { browser.close(null, true, true) {} })
                assertTrue(external.needsRecovery()); assertEquals("outcome_unknown", JSONObject(File(root, "interaction.json").readText()).getString("state"))
            }
            assertTrue(MemoryUiAutomationGuard.isProtected()); assertFalse(FactoryInteractionAdmission.available())
            denied { MemoryUiAutomationGuard.captureAutomationEpoch() }
            if (externalFirst) assertNull(io { browser.close(null, true, true) {} }) else assertNull(io { external.close(null, true, true) {} })
            assertFalse(MemoryUiAutomationGuard.isProtected()); assertTrue(FactoryInteractionAdmission.available())
            assertEquals("closed_outcome_unknown", JSONObject(File(root, "interaction.json").readText()).getString("state"))
            assertEquals("closed_outcome_unknown", JSONObject(File(browserRoot, "interaction.json").readText()).getString("state"))
        }
    }
    @Test fun recoveryTokenIsExactOneUseWithNoTransportOrExtraAuthority() {
        val token = FactoryExternalLaunchCoordinator.recoveryIntent(context)
        val invalid = listOf(Intent(token).putExtra("open", true), Intent(token).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION), Intent(token).setData(Uri.parse("geo:1,2")), Intent(token).apply { clipData = ClipData.newPlainText("fixture", "fixture") }, Intent(token).apply { selector = Intent("fixture") })
        invalid.forEach { assertFalse(FactoryExternalLaunchCoordinator.consumeRecoveryToken(it)) }
        assertTrue(FactoryExternalLaunchCoordinator.consumeRecoveryToken(token)); assertFalse(FactoryExternalLaunchCoordinator.consumeRecoveryToken(token))
        assertFalse(FactoryExternalLaunchCoordinator.consumeRecoveryToken(Intent().putExtra("nativeRecoveryToken", "forged")))
    }
    companion object { const val QUERY = "Synthetic park & museum?fixture=external"; val ARGS = JSONObject().put("query", QUERY).toString() }
}
