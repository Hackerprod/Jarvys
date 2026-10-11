package com.jarvys.agent.apkfactory

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.jarvys.factory.runtime.NativeOperationAuthorization
import com.jarvys.factory.runtime.NativeOperationRequest
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDialog

/** Synthetic identity/registry/lease tests; no installed APK, Android permission, or native effect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, shadows = [FactoryPolicyQueryIdentityShadow::class])
class FactoryOperationAuthorizationProviderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val caller = "example.nativeapp"
    private val uid = Binder.getCallingUid()
    private val sessions = mutableListOf<FactoryOperationAuthorizationProvider.Session>()
    private lateinit var proof: FactoryDocumentCoordinator.Proof
    @Suppress("DEPRECATION") @Before fun setup() {
        val signature = Signature(byteArrayOf(1, 3, 5, 7))
        val certificate = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        val pm = shadowOf(context.packageManager)
        pm.installPackage(PackageInfo().apply {
            packageName = caller; versionCode = 1; signatures = arrayOf(signature); lastUpdateTime = 2
            applicationInfo = ApplicationInfo().apply { packageName = caller; this.uid = this@FactoryOperationAuthorizationProviderTest.uid; sourceDir = "/synthetic/native.apk" }
        })
        pm.setPackagesForUid(uid, caller)
        proof = FactoryDocumentCoordinator.Proof(caller, certificate, "b".repeat(64), 1, "c".repeat(64))
    }
    @After fun cleanup() {
        sessions.forEach { it.close() }
        FactoryArtifactAuthority.invalidate(caller)
        FactoryPolicyQueryIdentityShadow.saved = null
    }
    private fun <T> io(action: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try { return executor.submit(Callable(action)).get(5, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
    }
    private fun session(policy: FactoryOperationPolicy, current: AtomicBoolean = AtomicBoolean(true)): FactoryOperationAuthorizationProvider.Session {
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "").padEnd(64, '0')
        val request = NativeOperationRequest.parse("notifications.cancel", "{\"id\":1}")
        val grant = policy.acquire(FactoryOperationPolicy.Subject.installed(proof), FactoryOperationPolicy.Operation.NOTIFICATIONS_CANCEL, request.binding(nonce))
        return FactoryOperationAuthorizationProvider.Session(context, uid, caller, nonce, request, proof, grant, request.binding(nonce),
            FactoryOperationAuthorizationProvider.sourceStamp(context, caller, uid), { check(current.get()) }).also {
            sessions += it; FactoryOperationAuthorizationProvider.register(it); it.attachRevocation()
        }
    }
    @Test fun pendingLeaseSurvivesCheckAndUsesSameGrantAfterOneApproval() {
        val policy = FactoryOperationPolicy.inMemory(FactoryOperationPolicy.Mode.ASK)
        val value = session(policy)
        val client = io { NativeOperationAuthorization.connect(value.lease, value.nonce, value.binding, { true }, {}) }
        assertEquals(NativeOperationAuthorization.PENDING, io { client.status() })
        assertThrows(SecurityException::class.java) { client.check() }
        assertFalse(value.closed.get())
        var human = 0
        value.approve { human++ }; value.approve { human++ }
        client.check(); assertEquals(1, human)
        policy.setDefaultMode(FactoryOperationPolicy.Mode.DENY)
        assertTrue(value.closed.get()); assertThrows(SecurityException::class.java) { client.check() }
    }
    @Test fun wrongCallerDoesNotConsumeTheReviewAndSuccessfulClaimIsSingleUse() {
        val value = session(FactoryOperationPolicy.inMemory(FactoryOperationPolicy.Mode.ASK))
        assertThrows(IllegalArgumentException::class.java) { FactoryOperationAuthorizationProvider.claim(context, "example.wrong", value.token, value.nonce) }
        assertThrows(IllegalArgumentException::class.java) { FactoryOperationAuthorizationProvider.claim(context, caller, value.token, "f".repeat(64)) }
        assertFalse(value.claimed.get())
        assertSame(value, FactoryOperationAuthorizationProvider.claim(context, caller, value.token, value.nonce))
        assertThrows(IllegalStateException::class.java) { FactoryOperationAuthorizationProvider.claim(context, caller, value.token, value.nonce) }
    }
    @Test fun sharedUidPackageChangeAndTrustInvalidationRevokeAuthority() {
        val value = session(FactoryOperationPolicy.inMemory())
        shadowOf(context.packageManager).setPackagesForUid(uid, caller, "example.shared")
        assertEquals(NativeOperationAuthorization.TERMINAL, value.lease.currentStatus()); assertTrue(value.closed.get())
        shadowOf(context.packageManager).setPackagesForUid(uid, caller)
        val current = AtomicBoolean(true); val next = session(FactoryOperationPolicy.inMemory(), current)
        current.set(false)
        assertEquals(NativeOperationAuthorization.TERMINAL, next.lease.currentStatus())
    }
    @Test fun providerRejectsAllCrudAndFileOperations() {
        val provider = FactoryOperationAuthorizationProvider(); val uri = android.net.Uri.parse("content://invalid/anything")
        assertThrows(SecurityException::class.java) { provider.query(uri, null, null, null, null) }
        assertThrows(SecurityException::class.java) { provider.insert(uri, null) }
        assertThrows(SecurityException::class.java) { provider.update(uri, null, null, null) }
        assertThrows(SecurityException::class.java) { provider.delete(uri, null, null) }
        assertThrows(SecurityException::class.java) { provider.getType(uri) }
        assertThrows(SecurityException::class.java) { provider.openFile(uri, "r") }
    }
    @Test fun approvalIntentHasNoCallerControlledDisclosureOrAlternateRouting() {
        fun request() = Intent(context, FactoryOperationApprovalActivity::class.java).putExtra("reviewToken", "a".repeat(64)).putExtra("nonce", "b".repeat(64))
        assertEquals("a".repeat(64) to "b".repeat(64), FactoryOperationApprovalActivity.parse(request()))
        assertThrows(IllegalArgumentException::class.java) { FactoryOperationApprovalActivity.parse(request().putExtra("summary", "Harmless")) }
        assertThrows(IllegalArgumentException::class.java) { FactoryOperationApprovalActivity.parse(request().setAction("anything")) }
        assertThrows(IllegalArgumentException::class.java) { FactoryOperationApprovalActivity.parse(request().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    @Test fun delayedMainApprovalCannotResurrectAnExpiredPendingReview() {
        val value = session(FactoryOperationPolicy.inMemory(FactoryOperationPolicy.Mode.ASK))
        io { value.requireCurrent() }
        org.robolectric.shadows.ShadowSystemClock.advanceBy(301, TimeUnit.SECONDS)
        var human = false
        assertThrows(IllegalStateException::class.java) { value.approve { human = true } }
        assertFalse(human); assertTrue(value.closed.get())
    }
    private class CountingStore(initial: FactoryOperationPolicy.Mode) : FactoryOperationPolicy.Store {
        var state = FactoryOperationPolicy.State(defaultMode = initial)
        var writes = 0
        var failWrites = false
        override fun read() = state
        override fun write(state: FactoryOperationPolicy.State) {
            writes++
            check(!failWrites) { "Synthetic persistence failure" }
            this.state = state
        }
    }
    private inner class DescriptorFixture(
        mode: FactoryOperationPolicy.Mode = FactoryOperationPolicy.Mode.ALLOW_ALWAYS,
        capabilities: Set<String> = setOf("notifications", "documents", "audio", "share"),
    ) {
        val store = CountingStore(mode)
        val policy = FactoryOperationPolicy(store)
        var verifications = 0
        var policyReads = 0
        var requiredCapabilities = emptySet<String>()
        var onPolicyRead: () -> Unit = {}
        val hostContext = object : ContextWrapper(context) {
            override fun getPackageName() = "com.jarvys.agent"
            override fun getApplicationContext(): Context = this
        }
        val provider = object : FactoryOperationAuthorizationProvider() {
            override fun verifiedCaller(context: Context, caller: String, capabilities: Set<String>): FactoryDocumentCoordinator.Proof {
                verifications++; requiredCapabilities = capabilities
                return proof
            }
            override fun operationPolicy(context: Context): FactoryOperationPolicy {
                policyReads++; onPolicyRead(); return policy
            }
        }
        init {
            // Substitute only keystore state. Receipt/capability checks, caller UID/source checks,
            // temporary trust token issuance and its final recheck remain production code.
            val scope = JSONObject().put("capabilities", JSONArray(capabilities.toList()))
                .put("permissions", JSONArray()).put("exportedComponents", JSONArray())
                .put("features", JSONArray()).put("queries", JSONArray()).put("allowedHosts", JSONArray())
            val anchor = JSONObject().put("schemaVersion", 1).put("appId", caller)
                .put("certificateSha256", proof.certificate).put("versionCode", 1).put("apkSha256", proof.apk).put("scope", scope)
            val baseline = requireNotNull(FactorySigningScope.readBaseline(JSONObject().put("lastApkSha256", proof.apk)
                .put("lastSignedScope", anchor), caller, proof.certificate, 1))
            FactoryPolicyQueryIdentityShadow.saved = FactorySigningIdentity.State(true, proof.certificate, 1, baseline, proof.apk, proof.record)
            provider.attachInfo(hostContext, null)
            shadowOf(provider).setCallingPackage(caller)
        }
        fun request(operation: String? = "notifications.cancel", capability: String = "notifications") = Bundle().apply {
            putInt("protocolVersion", 1); putString("nonce", "e".repeat(64)); putString("capability", capability)
            putLong("deadlineElapsedMs", SystemClock.elapsedRealtime() + 2000)
            if (operation != null) putString("operation", operation)
        }
        fun describe(extras: Bundle = request()): Bundle = provider.call("describe_installed", null, extras)
        fun assertNoAuthorityOrUi(expectedWrites: Int = 0) {
            assertEquals(expectedWrites, store.writes)
            val active = FactoryOperationPolicy::class.java.getDeclaredField("active").apply { isAccessible = true }.get(policy) as Set<*>
            assertTrue("A descriptor must not acquire a grant", active.isEmpty())
            val trust = FactoryArtifactAuthority::class.java.getDeclaredField("active").apply { isAccessible = true }.get(null) as Set<*>
            assertTrue("A descriptor must close its temporary trust token", trust.isEmpty())
            assertNull(ShadowDialog.getLatestDialog())
            assertNull(shadowOf(context as Application).nextStartedActivity)
        }
    }
    @Test fun descriptorReturnsAllAuthenticatedPolicyModesWithoutMutationGrantOrHumanDialog() {
        for (mode in FactoryOperationPolicy.Mode.entries) {
            val fixture = DescriptorFixture(mode)
            val request = fixture.request()
            val reply = fixture.describe(request)
            assertEquals(setOf("protocolVersion", "nonce", "capability", "deadlineElapsedMs", "appId", "certificateSha256", "apkSha256", "recordSha256", "versionCode", "resourceBudgets", "operation", "policyMode"), reply.keySet())
            assertEquals(mode.name, reply.getString("policyMode")); assertEquals("notifications.cancel", reply.getString("operation"))
            assertEquals(request.getString("nonce"), reply.getString("nonce")); assertEquals(request.getLong("deadlineElapsedMs"), reply.getLong("deadlineElapsedMs"))
            assertEquals(caller, reply.getString("appId")); assertEquals(proof.certificate, reply.getString("certificateSha256"))
            assertEquals(proof.apk, reply.getString("apkSha256")); assertEquals(proof.record, reply.getString("recordSha256"))
            assertEquals(proof.version, reply.getLong("versionCode")); assertEquals(1, fixture.verifications); assertEquals(1, fixture.policyReads)
            assertTrue(fixture.policy.subjects().isEmpty()); fixture.assertNoAuthorityOrUi()
        }
    }
    @Test fun legacyDescriptorRetainsExactShapeAndNeverReadsPolicy() {
        val fixture = DescriptorFixture(FactoryOperationPolicy.Mode.DENY)
        fixture.onPolicyRead = { error("Legacy source description must not read policy") }
        val reply = fixture.describe(fixture.request(operation = null))
        assertEquals(setOf("protocolVersion", "nonce", "capability", "deadlineElapsedMs", "appId", "certificateSha256", "apkSha256", "recordSha256", "versionCode", "resourceBudgets"), reply.keySet())
        assertEquals(1, fixture.verifications); assertEquals(0, fixture.policyReads); fixture.assertNoAuthorityOrUi()
    }
    @Test fun malformedAndMismatchedOperationQueriesRejectBeforeReceiptOrPolicyAccess() {
        val fixture = DescriptorFixture()
        val edits: List<(Bundle) -> Unit> = listOf(
            { it.putString("operation", "unknown.operation") }, { it.putString("operation", "audio.play") },
            { it.putString("operation", "") }, { it.putString("operation", null) }, { it.putInt("operation", 1) },
            { it.putString("policyMode", "ALLOW_ALWAYS") }, { it.putString("details", "{}") },
            { it.putString("capability", "unknown") }, { it.putLong("protocolVersion", 1) },
            { it.putString("nonce", "bad") }, { it.putInt("deadlineElapsedMs", 2000) },
            { it.putLong("deadlineElapsedMs", SystemClock.elapsedRealtime()) }, { it.remove("capability") },
        )
        edits.forEach { edit -> assertThrows(IllegalArgumentException::class.java) { fixture.describe(fixture.request().also(edit)) } }
        assertThrows(SecurityException::class.java) { fixture.describe(fixture.request("notifications.status")) }
        assertEquals(0, fixture.verifications); assertEquals(0, fixture.policyReads); fixture.assertNoAuthorityOrUi()
    }
    @Test fun undeclaredReceiptCapabilityAndMissingDocumentDependencyNeverDisclosePolicy() {
        val fixture = DescriptorFixture(capabilities = setOf("audio", "share"))
        assertThrows(SecurityException::class.java) { fixture.describe() }
        for ((operation, capability) in listOf("audio.play" to "audio", "share.file" to "share")) {
            assertThrows(SecurityException::class.java) { fixture.describe(fixture.request(operation, capability)) }
            assertEquals(setOf(capability, "documents"), fixture.requiredCapabilities)
        }
        assertEquals(3, fixture.verifications); assertEquals(0, fixture.policyReads); fixture.assertNoAuthorityOrUi()
    }
    @Test fun shareAndAudioQueriesAuthenticateBothCapabilitiesWithoutReadingDocumentContent() {
        val fixture = DescriptorFixture(FactoryOperationPolicy.Mode.DENY)
        for ((operation, capability) in listOf("audio.play" to "audio", "share.file" to "share")) {
            val reply = fixture.describe(fixture.request(operation, capability))
            assertEquals("DENY", reply.getString("policyMode")); assertEquals(operation, reply.getString("operation"))
            assertEquals(setOf(capability, "documents"), fixture.requiredCapabilities)
        }
        fixture.assertNoAuthorityOrUi()
    }
    @Test fun savedOperationOverrideIsExactToAuthenticatedAppCertificateAndOperation() {
        val fixture = DescriptorFixture()
        val subject = FactoryOperationPolicy.Subject.installed(proof)
        fixture.policy.setMode(subject, FactoryOperationPolicy.Operation.NOTIFICATIONS_CANCEL, FactoryOperationPolicy.Mode.DENY)
        fixture.policy.setMode(FactoryOperationPolicy.Subject.signedArtifact(caller, "f".repeat(64)), FactoryOperationPolicy.Operation.NOTIFICATIONS_SHOW, FactoryOperationPolicy.Mode.DENY)
        val writes = fixture.store.writes
        assertEquals("DENY", fixture.describe().getString("policyMode"))
        assertEquals("ALLOW_ALWAYS", fixture.describe(fixture.request("notifications.show")).getString("policyMode"))
        fixture.assertNoAuthorityOrUi(writes)
    }
    @Test fun pointInTimeAllowNeverReplacesFreshExactGrantOrApprovesAsk() {
        val fixture = DescriptorFixture()
        val previous = fixture.describe()
        fixture.policy.setDefaultMode(FactoryOperationPolicy.Mode.ASK)
        assertEquals("ALLOW_ALWAYS", previous.getString("policyMode"))
        assertEquals("ASK", fixture.describe().getString("policyMode"))
        fixture.assertNoAuthorityOrUi(1)
        val request = NativeOperationRequest.parse("notifications.cancel", "{\"id\":2}")
        val binding = request.binding("f".repeat(64))
        val grant = fixture.policy.acquire(FactoryOperationPolicy.Subject.installed(proof), FactoryOperationPolicy.Operation.NOTIFICATIONS_CANCEL, binding)
        try {
            assertEquals(binding, grant.requestBinding); assertTrue(grant.requiresApproval)
            assertThrows(IllegalStateException::class.java) { grant.checkBeforeEffect() }
            fixture.policy.setDefaultMode(FactoryOperationPolicy.Mode.DENY)
            assertEquals("DENY", fixture.describe().getString("policyMode"))
            assertThrows(IllegalStateException::class.java) { grant.checkBeforeEffect() }
        } finally { grant.close() }
        fixture.assertNoAuthorityOrUi(fixture.store.writes)
    }
    @Test fun changedUidSourceOrSigningEvidenceNeverYieldsAPolicyDescriptor() {
        val fixture = DescriptorFixture()
        shadowOf(context.packageManager).setPackagesForUid(uid, caller, "example.shared")
        assertThrows(IllegalArgumentException::class.java) { fixture.describe() }
        assertEquals(0, fixture.verifications)
        shadowOf(context.packageManager).setPackagesForUid(uid, caller)
        FactoryPolicyQueryIdentityShadow.saved = FactoryPolicyQueryIdentityShadow.saved!!.copy(fingerprint = "f".repeat(64))
        assertThrows(SecurityException::class.java) { fixture.describe() }
        assertEquals(0, fixture.policyReads); fixture.assertNoAuthorityOrUi()
    }
    @Test fun sourceReplacementDuringPolicyReadRejectsReplyAndClosesTemporaryAuthority() {
        val fixture = DescriptorFixture()
        fixture.onPolicyRead = { shadowOf(context.packageManager).setPackagesForUid(uid, caller, "example.shared") }
        assertThrows(SecurityException::class.java) { fixture.describe() }
        assertEquals(1, fixture.policyReads); fixture.assertNoAuthorityOrUi()
        val active = FactoryArtifactAuthority::class.java.getDeclaredField("active").apply { isAccessible = true }.get(null) as Set<*>
        assertTrue("Descriptor trust token must be closed on failure", active.isEmpty())
    }
    @Test fun expiredReplyCannotPublishModeEvenAfterAuthenticatedPolicyRead() {
        val fixture = DescriptorFixture()
        fixture.onPolicyRead = { org.robolectric.shadows.ShadowSystemClock.advanceBy(2001, TimeUnit.MILLISECONDS) }
        assertThrows(SecurityException::class.java) { fixture.describe() }
        assertEquals(1, fixture.policyReads); fixture.assertNoAuthorityOrUi()
    }
    @Test fun unhealthyPolicyCannotPublishAnAllowDescriptor() {
        val fixture = DescriptorFixture()
        fixture.store.failWrites = true
        assertThrows(IllegalStateException::class.java) { fixture.policy.setDefaultMode(FactoryOperationPolicy.Mode.ALLOW_ALWAYS) }
        assertThrows(SecurityException::class.java) { fixture.describe() }
        fixture.assertNoAuthorityOrUi(1)
    }

}


/** Test-only keystore boundary. No key creation, signing, installed APK read, or native effect. */
@Implements(value = FactorySigningIdentity::class, isInAndroidSdk = false)
internal class FactoryPolicyQueryIdentityShadow {
    @Implementation fun state(appId: String): FactorySigningIdentity.State {
        require(appId == "example.nativeapp")
        return checkNotNull(saved)
    }
    companion object { @JvmField var saved: FactorySigningIdentity.State? = null }
}
