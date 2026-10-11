package com.jarvys.agent.apkfactory

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import com.jarvys.factory.runtime.NativeOperationAuthorization
import com.jarvys.factory.runtime.NativeOperationRequest
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Synthetic identity/registry/lease tests; no installed APK, Android permission, or native effect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
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
    @After fun cleanup() { sessions.forEach { it.close() } }
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
}
