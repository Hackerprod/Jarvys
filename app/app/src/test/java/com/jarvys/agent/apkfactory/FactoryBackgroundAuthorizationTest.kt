package com.jarvys.agent.apkfactory

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.jarvys.factory.runtime.NativeBackgroundAuthorization
import com.jarvys.factory.runtime.NativeOperationAuthorization
import com.jarvys.factory.runtime.NativeOperationRequest
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

/** Synthetic source/lease tests. No signed APK, native effect, or physical-device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE, application = Application::class)
class FactoryBackgroundAuthorizationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val caller = "example.background"
    private val uid = Binder.getCallingUid()
    private lateinit var proof: FactoryDocumentCoordinator.Proof
    private val sessions = mutableListOf<FactoryOperationAuthorizationProvider.Session>()
    private val verifications = AtomicInteger()
    private val provider = object : FactoryOperationAuthorizationProvider() {
        override fun verifiedCaller(context: Context, caller: String, capabilities: Set<String>): FactoryDocumentCoordinator.Proof {
            verifications.incrementAndGet(); return proof
        }
    }
    @Suppress("DEPRECATION") @Before fun setup() {
        val signature = Signature(byteArrayOf(1, 3, 5, 7))
        val certificate = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = caller; versionCode = 1; signatures = arrayOf(signature); lastUpdateTime = 2
            applicationInfo = ApplicationInfo().apply { packageName = caller; this.uid = this@FactoryBackgroundAuthorizationTest.uid; sourceDir = "/synthetic/background.apk" }
        })
        shadowOf(context.packageManager).setPackagesForUid(uid, caller)
        proof = FactoryDocumentCoordinator.Proof(caller, certificate, "b".repeat(64), 1, "c".repeat(64))
        provider.attachInfo(context, null); shadowOf(provider).setCallingPackage(caller)
    }
    @After fun cleanup() { sessions.forEach { it.close() } }
    private fun <T> worker(action: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try { return executor.submit(Callable(action)).get(5, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
    }
    private fun identity() = NativeBackgroundAuthorization.SourceIdentity(proof.appId, proof.certificate, proof.apk, proof.record, proof.version)
    private fun extras(source: String = identity().canonicalJson()) = Bundle().apply {
        putInt("protocolVersion", 1); putString("operation", "notifications.cancel"); putString("nonce", "f".repeat(64)); putString("details", "{\"id\":1}")
        putLong("deadlineElapsedMs", SystemClock.elapsedRealtime() + 8000); putString("expectedSource", source)
    }
    private fun session(hardDeadline: Long?): FactoryOperationAuthorizationProvider.Session {
        val request = NativeOperationRequest.parse("notifications.cancel", "{\"id\":1}")
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "").padEnd(64, '0')
        val binding = if (hardDeadline == null) request.binding(nonce) else request.binding(nonce, identity().bindingEvidence())
        return FactoryOperationAuthorizationProvider.Session(context, uid, caller, nonce, request, proof, binding,
            FactoryOperationAuthorizationProvider.sourceStamp(context, caller, uid), {}, {}, hardDeadline).also {
            sessions += it; FactoryOperationAuthorizationProvider.register(it); it.attachDeadline()
        }
    }
    @Test fun everyChangedExpectedSourceFieldRejectsBeforeAnySession() {
        val changes = mapOf("certificateSha256" to "d".repeat(64), "apkSha256" to "d".repeat(64), "recordSha256" to "d".repeat(64), "versionCode" to 2)
        for ((field, value) in changes) {
            val changed = JSONObject(identity().canonicalJson()).put(field, value).toString()
            val failure = assertThrows(SecurityException::class.java) { provider.call("acquire_background", null, extras(changed)) }
            val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
            assertTrue(field, causes.any { it.message == "Installed source changed after description" })
        }
        val wrongApp = JSONObject(identity().canonicalJson()).put("appId", "example.other").toString()
        assertThrows(IllegalArgumentException::class.java) { provider.call("acquire_background", null, extras(wrongApp)) }
        assertEquals(4, verifications.get())
    }
    @Test fun missingAndMalformedBackgroundIdentityRejectBeforeVerification() {
        assertThrows(IllegalArgumentException::class.java) { provider.call("acquire_background", null, extras().apply { remove("expectedSource") }) }
        assertThrows(IllegalArgumentException::class.java) { provider.call("acquire_background", null, extras().apply { putInt("expectedSource", 1) }) }
        assertThrows(com.jarvys.factory.runtime.FactoryException::class.java) { provider.call("acquire_background", null, extras("{}")) }
        assertEquals(0, verifications.get())
    }
    @Test fun connectedAllowedBackgroundLeaseCannotPassHardDeadlineEvenBeforeTimerRuns() {
        val value = session(SystemClock.elapsedRealtime() + 8000)
        val client = worker { NativeOperationAuthorization.connect(value.lease, value.nonce, value.binding, { true }, {}) }
        client.check(); assertTrue(value.lease.isConnected)
        ShadowSystemClock.advanceBy(8000, TimeUnit.MILLISECONDS)
        assertThrows(SecurityException::class.java) { client.check() }
        assertTrue(value.closed.get()); assertThrows(IllegalStateException::class.java) { value.requireCurrent() }
    }
    @Test fun backgroundTimerClosesConnectedAllowedLeaseWithoutAnotherClientCheck() {
        val value = session(SystemClock.elapsedRealtime() + 8000)
        worker { NativeOperationAuthorization.connect(value.lease, value.nonce, value.binding, { true }, {}) }
        shadowOf(Looper.getMainLooper()).idleFor(8000, TimeUnit.MILLISECONDS)
        assertTrue(value.closed.get()); assertTrue(value.lease.isClosed)
        assertThrows(IllegalStateException::class.java) { value.requireCurrent() }
    }
    @Test fun foregroundConnectedAllowRetainsHistoricalUnboundedLiveLeaseBehavior() {
        val value = session(null)
        val client = worker { NativeOperationAuthorization.connect(value.lease, value.nonce, value.binding, { true }, {}) }
        shadowOf(Looper.getMainLooper()).idleFor(301, TimeUnit.SECONDS)
        client.check(); assertFalse(value.closed.get()); assertEquals(value.request.binding(value.nonce), value.binding)
        worker { client.close() }
    }
}
