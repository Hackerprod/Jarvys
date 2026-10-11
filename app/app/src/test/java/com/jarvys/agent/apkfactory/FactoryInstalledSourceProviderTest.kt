package com.jarvys.agent.apkfactory

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE, application = Application::class)
class FactoryInstalledSourceProviderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val caller = "example.installedsource"
    private val uid = Binder.getCallingUid()
    private lateinit var proof: FactoryDocumentCoordinator.Proof
    private val verified = AtomicInteger()
    private val exited = CountDownLatch(1)
    private var block: CountDownLatch? = null
    private val provider = object : FactoryOperationAuthorizationProvider() {
        override fun operationPolicy(context: Context): FactoryOperationPolicy = error("Descriptor accessed operation policy")
        override fun verifiedCaller(context: Context, caller: String, capabilities: Set<String>): FactoryDocumentCoordinator.Proof {
            assertEquals(setOf("notifications"), capabilities)
            verified.incrementAndGet()
            // Deliberately emulate an uninterruptible platform read that completes after timeout.
            block?.let { latch -> while (latch.count > 0) { try { latch.await() } catch (_: InterruptedException) { } } }
            exited.countDown()
            return proof
        }
    }
    @Suppress("DEPRECATION") @Before fun setup() {
        val signature = Signature(byteArrayOf(1, 3, 5, 7))
        val certificate = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = caller; versionCode = 1; signatures = arrayOf(signature); lastUpdateTime = 2
            applicationInfo = ApplicationInfo().apply { packageName = caller; this.uid = this@FactoryInstalledSourceProviderTest.uid; sourceDir = "/synthetic/native.apk" }
        })
        shadowOf(context.packageManager).setPackagesForUid(uid, caller)
        proof = FactoryDocumentCoordinator.Proof(caller, certificate, "b".repeat(64), 1, "c".repeat(64))
    }
    private fun request() = Bundle().apply {
        putInt("protocolVersion", 1); putString("nonce", "a".repeat(64)); putString("capability", "notifications")
        putLong("deadlineElapsedMs", SystemClock.elapsedRealtime() + 2000L)
    }
    private fun invoke(extras: Bundle, arg: String? = null): Bundle {
        val method = FactoryOperationAuthorizationProvider::class.java.getDeclaredMethod("describeInstalled", Context::class.java, String::class.java, Int::class.javaPrimitiveType, String::class.java, Bundle::class.java)
        method.isAccessible = true
        try { return method.invoke(provider, context, caller, uid, arg, extras) as Bundle }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    @Test fun closedRequestSchemaRejectsBeforeVerification() {
        val edits: List<(Bundle) -> Unit> = listOf(
            { it.putString("unexpected", "x") }, { it.remove("nonce") }, { it.putLong("protocolVersion", 1L) },
            { it.putString("nonce", "bad") }, { it.putString("capability", "unknown") },
            { it.putInt("deadlineElapsedMs", 2000) }, { it.putLong("deadlineElapsedMs", SystemClock.elapsedRealtime()) }
        )
        edits.forEach { edit -> assertThrows(IllegalArgumentException::class.java) { invoke(request().also(edit)) } }
        assertThrows(IllegalArgumentException::class.java) { invoke(request(), "unexpected") }
        assertEquals(0, verified.get())
    }
    @Test fun sharedUidRejectsBeforeReceiptVerification() {
        shadowOf(context.packageManager).setPackagesForUid(uid, caller, "example.shared")
        assertThrows(IllegalArgumentException::class.java) { invoke(request()) }
        assertEquals(0, verified.get())
    }
    @Test fun unsignedSyntheticProofNeverProducesDescriptorOrPolicyGrant() {
        assertThrows(SecurityException::class.java) { invoke(request()) }
        assertEquals(1, verified.get())
    }
    @Test fun timeoutCannotPublishAfterUninterruptibleReadReturns() {
        val release = CountDownLatch(1); block = release
        val started = System.nanoTime()
        try {
            assertThrows(SecurityException::class.java) { invoke(request().apply { putLong("deadlineElapsedMs", SystemClock.elapsedRealtime() + 100L) }) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
            assertEquals(1, verified.get())
        } finally { release.countDown() }
        assertTrue(exited.await(2, TimeUnit.SECONDS))
        val drained = FactoryOperationAuthorizationProvider.WORKER.submit { }
        drained.get(2, TimeUnit.SECONDS)
        assertTrue(FactoryOperationAuthorizationProvider.WORKER.activeCount <= 1)
    }
}
