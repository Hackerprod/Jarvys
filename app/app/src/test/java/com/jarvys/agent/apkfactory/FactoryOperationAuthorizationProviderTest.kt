package com.jarvys.agent.apkfactory

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
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

/** Caller provenance and authenticated source transport tests; no approval state or native effect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, shadows = [FactorySourceIdentityShadow::class])
class FactoryOperationAuthorizationProviderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val caller = "example.nativeapp"
    private val uid = Binder.getCallingUid()
    private val sessions = mutableListOf<FactoryOperationAuthorizationProvider.Session>()
    private lateinit var proof: FactoryDocumentCoordinator.Proof
    @Suppress("DEPRECATION") @Before fun setup() {
        val signature = Signature(byteArrayOf(1, 3, 5, 7))
        val certificate = digest(signature.toByteArray())
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
        FactoryArtifactAuthority.invalidate(caller); FactorySourceIdentityShadow.saved = null
    }
    private fun <T> io(action: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try { return executor.submit(Callable(action)).get(5, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
    }
    private fun session(current: AtomicBoolean = AtomicBoolean(true), nonce: String = randomNonce()): FactoryOperationAuthorizationProvider.Session {
        val request = NativeOperationRequest.parse("notifications.cancel", "{\"id\":1}")
        return FactoryOperationAuthorizationProvider.Session(context, uid, caller, nonce, request, proof, request.binding(nonce),
            FactoryOperationAuthorizationProvider.sourceStamp(context, caller, uid), { check(current.get()) }).also {
            sessions += it; FactoryOperationAuthorizationProvider.register(it); it.attachDeadline()
        }
    }
    @Test fun operationSessionAuthenticatesCallerAndInstalledSourceAtEveryEffect() {
        val value = session()
        val client = io { NativeOperationAuthorization.connect(value.lease, value.nonce, value.binding, { true }, {}) }
        assertEquals(NativeOperationAuthorization.ACTIVE, io { client.status() }); client.check()
        shadowOf(context.packageManager).setPackagesForUid(uid, caller, "example.shared")
        assertThrows(SecurityException::class.java) { client.check() }
        assertTrue(value.closed.get()); assertEquals(NativeOperationAuthorization.TERMINAL, value.lease.currentStatus())
    }
    @Test fun aRequestNonceCannotBeRegisteredTwice() {
        val nonce = randomNonce(); session(nonce = nonce)
        val request = NativeOperationRequest.parse("notifications.cancel", "{\"id\":1}")
        val duplicate = FactoryOperationAuthorizationProvider.Session(context, uid, caller, nonce, request, proof,
            request.binding(nonce), FactoryOperationAuthorizationProvider.sourceStamp(context, caller, uid), {})
        sessions += duplicate
        assertThrows(IllegalStateException::class.java) { FactoryOperationAuthorizationProvider.register(duplicate) }
        duplicate.close()
    }
    @Test fun changedSourceAndSharedUidEndTheSession() {
        val current = AtomicBoolean(true); val value = session(current)
        shadowOf(context.packageManager).setPackagesForUid(uid, caller, "example.shared")
        assertEquals(NativeOperationAuthorization.TERMINAL, value.lease.currentStatus()); assertTrue(value.closed.get())
        shadowOf(context.packageManager).setPackagesForUid(uid, caller)
        val next = session(current); current.set(false)
        assertEquals(NativeOperationAuthorization.TERMINAL, next.lease.currentStatus())
    }
    @Test fun providerRejectsAllCrudAndFileOperations() {
        val provider = FactoryOperationAuthorizationProvider(); val uri = android.net.Uri.parse("content://invalid/anything")
        assertThrows(SecurityException::class.java) { provider.query(uri, null, null, null, null) }
        assertThrows(SecurityException::class.java) { provider.insert(uri, null) }
        assertThrows(SecurityException::class.java) { provider.update(uri, null, null, null) }
        assertThrows(SecurityException::class.java) { provider.delete(uri, null, null) }
        assertThrows(SecurityException::class.java) { provider.getType(uri) }; assertThrows(SecurityException::class.java) { provider.openFile(uri, "r") }
    }

    private inner class SourceFixture(private val capabilities: Set<String> = setOf("notifications", "documents", "audio", "share")) {
        var verifications = 0; var requiredCapabilities = emptySet<String>(); var duringVerification: () -> Unit = {}
        val hostContext = object : ContextWrapper(context) {
            override fun getPackageName() = "com.jarvys.agent"
            override fun getApplicationContext(): Context = this
        }
        val provider = object : FactoryOperationAuthorizationProvider() {
            override fun verifiedCaller(context: Context, caller: String, capabilities: Set<String>): FactoryDocumentCoordinator.Proof {
                verifications++; requiredCapabilities = capabilities; duringVerification(); return proof
            }
        }
        init {
            val scope = JSONObject().put("capabilities", JSONArray(capabilities.toList())).put("permissions", JSONArray())
                .put("exportedComponents", JSONArray()).put("features", JSONArray()).put("queries", JSONArray()).put("allowedHosts", JSONArray())
            val anchor = JSONObject().put("schemaVersion", 1).put("appId", caller).put("certificateSha256", proof.certificate)
                .put("versionCode", 1).put("apkSha256", proof.apk).put("scope", scope)
            val baseline = requireNotNull(FactorySigningScope.readBaseline(JSONObject().put("lastApkSha256", proof.apk)
                .put("lastSignedScope", anchor), caller, proof.certificate, 1))
            FactorySourceIdentityShadow.saved = FactorySigningIdentity.State(true, proof.certificate, 1, baseline, proof.apk, proof.record)
            provider.attachInfo(hostContext, null); shadowOf(provider).setCallingPackage(caller)
        }
        fun request(capability: String = "notifications") = Bundle().apply {
            putInt("protocolVersion", 1); putString("nonce", randomNonce()); putString("capability", capability)
            putLong("deadlineElapsedMs", SystemClock.elapsedRealtime() + 2000)
        }
        fun describe(extras: Bundle = request()): Bundle = provider.call("describe_installed", null, extras)
        fun assertNoTrustOrUi() {
            val trust = FactoryArtifactAuthority::class.java.getDeclaredField("active").apply { isAccessible = true }.get(null) as Set<*>
            assertTrue("Source description closes its temporary trust token", trust.isEmpty())
            assertNull(ShadowDialog.getLatestDialog()); assertNull(shadowOf(context as Application).nextStartedActivity)
        }
    }
    @Test fun sourceDescriptionReturnsAuthenticatedIdentityAndResourceBudgetsOnly() {
        val fixture = SourceFixture(); val request = fixture.request(); val reply = fixture.describe(request)
        assertEquals(setOf("protocolVersion", "nonce", "capability", "deadlineElapsedMs", "appId", "certificateSha256", "apkSha256", "recordSha256", "versionCode", "resourceBudgets"), reply.keySet())
        assertEquals(request.getString("nonce"), reply.getString("nonce")); assertEquals(caller, reply.getString("appId"))
        assertEquals(proof.certificate, reply.getString("certificateSha256")); assertEquals(proof.apk, reply.getString("apkSha256"))
        assertEquals(proof.record, reply.getString("recordSha256")); assertEquals(1L, reply.getLong("versionCode"))
        assertEquals(setOf("notifications"), fixture.requiredCapabilities); assertEquals(1, fixture.verifications); fixture.assertNoTrustOrUi()
    }
    @Test fun descriptionRejectsExtraFieldsAndUndeclaredCapabilitiesBeforeTrustCapture() {
        val fixture = SourceFixture()
        assertThrows(IllegalArgumentException::class.java) { fixture.describe(fixture.request().apply { putString("operation", "notifications.cancel") }) }
        assertThrows(IllegalArgumentException::class.java) { fixture.describe(fixture.request().apply { putString("policyMode", "ASK") }) }
        assertThrows(SecurityException::class.java) { SourceFixture(setOf("audio")).describe() }
        assertEquals(0, fixture.verifications); fixture.assertNoTrustOrUi()
    }
    @Test fun sourceReplacementDuringDescriptionRejectsReplyAndClosesTrustToken() {
        val fixture = SourceFixture()
        fixture.duringVerification = { shadowOf(context.packageManager).setPackagesForUid(uid, caller, "example.shared") }
        assertThrows(SecurityException::class.java) { fixture.describe() }
        fixture.assertNoTrustOrUi()
    }

    companion object {
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun randomNonce() = java.util.UUID.randomUUID().toString().replace("-", "").padEnd(64, '0')
    }
}

/** Test-only keystore boundary. No key creation, signing, installed APK read, or native effect. */
@Implements(value = FactorySigningIdentity::class, isInAndroidSdk = false)
internal class FactorySourceIdentityShadow {
    @Implementation fun state(appId: String): FactorySigningIdentity.State { require(appId == "example.nativeapp"); return checkNotNull(saved) }
    companion object { @JvmField var saved: FactorySigningIdentity.State? = null }
}
