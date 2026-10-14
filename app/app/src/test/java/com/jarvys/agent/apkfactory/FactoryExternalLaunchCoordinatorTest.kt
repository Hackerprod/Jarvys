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
        owner.session()?.let { io { owner.close(it) {} } }
        }
        browserOwners.asReversed().forEach { owner ->
        owner.session()?.let { io { owner.close(it) {} } }
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
    @Test fun browserControlDescriptorCannotAuthorizeMapsOrPhoneAndExternalCannotAuthorizeBrowser() {
        val external = ExternalLaunchControl("d".repeat(64)) { true }
        val browser = com.jarvys.factory.runtime.BrowserLaunchControl("d".repeat(64)) { true }
        try {
            denied { io { ExternalLaunchControl.register(browser, "d".repeat(64), { true }, Runnable {}) } }
            denied { io { com.jarvys.factory.runtime.BrowserLaunchControl.register(external, "d".repeat(64), { true }, Runnable {}) } }
        } finally { external.close(); browser.close() }
        assertTrue(coordinator.canBegin()); assertFalse(File(root, "interaction.json").exists())
    }
    companion object { const val QUERY = "Synthetic park & museum?fixture=external"; val ARGS = JSONObject().put("query", QUERY).toString() }
}
