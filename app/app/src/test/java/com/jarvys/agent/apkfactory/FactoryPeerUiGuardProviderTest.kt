package com.jarvys.agent.apkfactory

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** In-process protocol/guard race evidence; no device gesture or peer APK is launched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class FactoryPeerUiGuardProviderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val caller: String get() = if (context.packageName == "com.jarvys.agent") "com.jarvys.agent.recoverytest" else "com.jarvys.agent"
    private val uid = Binder.getCallingUid()
    private val sessions = mutableListOf<FactoryOperationAuthorizationProvider.PeerGuardSession>()
    private val surfaces = mutableListOf<MemoryUiAutomationGuard.Lease>()

    @Suppress("DEPRECATION") @Before fun setup() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = caller; versionCode = 1; lastUpdateTime = 2
            signatures = arrayOf(Signature(byteArrayOf(1, 4, 9, 16)))
            applicationInfo = ApplicationInfo().apply {
                packageName = caller; this.uid = this@FactoryPeerUiGuardProviderTest.uid
                sourceDir = "/synthetic/peer.apk"; enabled = true
            }
        })
        shadowOf(context.packageManager).setPackagesForUid(uid, caller)
    }
    @After fun cleanup() { sessions.forEach { it.close() }; surfaces.forEach { it.close() } }
    private fun input() = Bundle().apply { putInt("protocolVersion", 1) }
    private fun snapshot(): Bundle = FactoryOperationAuthorizationProvider.peerGuardStatus(context, caller, uid, null, input()).also {
        sessions += it.getBinder("guard") as FactoryOperationAuthorizationProvider.PeerGuardSession
    }
    private fun surface() = MemoryUiAutomationGuard.enterProtectedSurface().also { surfaces += it }

    private class Client : Binder() {
        val deaths = mutableListOf<IBinder.DeathRecipient>()
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { deaths += recipient }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = deaths.remove(recipient)
        fun die() { deaths.toList().forEach { it.binderDied() } }
    }
    private fun transact(snapshot: Bundle, code: Int, value: Long = snapshot.getLong("epoch"), client: IBinder? = null,
                         instance: String? = snapshot.getString("instance"), trailing: Boolean = false): Long? {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(FactoryOperationAuthorizationProvider.GUARD_INTERFACE)
            data.writeString(instance); data.writeLong(value)
            if (code == FactoryOperationAuthorizationProvider.GUARD_BEGIN) data.writeStrongBinder(client)
            if (trailing) data.writeInt(7)
            if (!snapshot.getBinder("guard")!!.transact(code, data, reply, 0)) null
            else {
                reply.readException()
                val result = if (code == FactoryOperationAuthorizationProvider.GUARD_BEGIN) reply.readLong() else reply.readInt().toLong()
                assertEquals(0, reply.dataAvail()); result
            }
        } finally { data.recycle(); reply.recycle() }
    }
    private fun begin(snapshot: Bundle, client: IBinder = Client()) = transact(snapshot, FactoryOperationAuthorizationProvider.GUARD_BEGIN, client = client)
    private fun check(snapshot: Bundle) = transact(snapshot, FactoryOperationAuthorizationProvider.GUARD_CHECK)
    private fun end(snapshot: Bundle, action: Long) = transact(snapshot, FactoryOperationAuthorizationProvider.GUARD_END, value = action)

    @Test fun normalSurfacesAllowAndResponseContainsOnlyGuardState() {
        val value = snapshot()
        assertEquals(setOf("protocolVersion", "instance", "epoch", "protected", "guard"), value.keySet())
        assertEquals(1, value.getInt("protocolVersion")); assertFalse(value.getBoolean("protected"))
        assertTrue(value.getString("instance")!!.matches(Regex("[a-f0-9]{64}")))
        assertEquals(1L, check(value))
        val action = begin(value)!!
        assertEquals(1L, end(value, action)); assertEquals(1L, end(value, action))
        assertEquals(1L, check(value)); assertNotNull(begin(value))
    }
    @Test fun protectedEntryAfterCaptureRejectsQueuedActionEvenAfterSurfaceCloses() {
        val before = snapshot(); val protected = surface()
        assertTrue(snapshot().getBoolean("protected"))
        assertEquals(0L, check(before)); assertNull(begin(before))
        protected.close()
        assertEquals(0L, check(before)); assertNull(begin(before))
        assertNotNull(begin(snapshot()))
    }
    @Test fun inFlightPeerGesturePermanentlyTaintsNewProtectedSurface() {
        val before = snapshot(); val action = begin(before)!!
        val protected = surface()
        assertFalse(protected.isReadyForUser)
        assertEquals(1L, end(before, action))
        assertFalse(protected.isReadyForUser)
        protected.close()
        val clean = surface(); assertTrue(clean.isReadyForUser)
    }
    @Test fun actualClientDeathReleasesActionsWithoutAClockBasedExpiry() {
        val before = snapshot(); val client = Client(); begin(before, client)
        assertEquals(1, client.deaths.size)
        client.die(); assertTrue(client.deaths.isEmpty())
        val clean = surface(); assertTrue(clean.isReadyForUser)
    }
    @Test fun clientDyingDuringRegistrationDoesNotLeaveAnActionOrDeathLink() {
        val value = snapshot()
        var linked = false
        val client = object : Binder() {
            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
                linked = true; recipient.binderDied()
            }
            override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
                linked = false; return true
            }
        }
        assertNull(begin(value, client)); assertFalse(linked)
        val clean = surface(); assertTrue(clean.isReadyForUser)
    }
    @Test fun sessionClosingBeforeDeathRegistrationCompletesUndoesTheLateLink() {
        val value = snapshot()
        var linked = false
        val client = object : Binder() {
            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
                (value.getBinder("guard") as FactoryOperationAuthorizationProvider.PeerGuardSession).close()
                linked = true
            }
            override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
                linked = false; return true
            }
        }
        assertNull(begin(value, client)); assertFalse(linked)
        val clean = surface(); assertTrue(clean.isReadyForUser)
    }
    @Test fun malformedOrCrossInstanceRequestsCannotBeginOrEndActions() {
        val value = snapshot(); val client = Client()
        assertNull(transact(value, FactoryOperationAuthorizationProvider.GUARD_BEGIN, client = client, instance = "0".repeat(64)))
        assertNull(transact(value, FactoryOperationAuthorizationProvider.GUARD_BEGIN, client = client, trailing = true))
        assertNull(transact(value, FactoryOperationAuthorizationProvider.GUARD_BEGIN, client = null))
        assertTrue(client.deaths.isEmpty())
        val clean = surface(); assertTrue(clean.isReadyForUser)
    }
    @Test fun generatedAppsSelfCallsSharedUidsAndUnexpectedEnvelopesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FactoryOperationAuthorizationProvider.peerGuardStatus(context, "example.generated", uid, null, input())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FactoryOperationAuthorizationProvider.peerGuardStatus(context, context.packageName, uid, null, input())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FactoryOperationAuthorizationProvider.peerGuardStatus(context, caller, uid, null, input().apply { putBoolean("allow", true) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            FactoryOperationAuthorizationProvider.peerGuardStatus(context, caller, uid, null, Bundle().apply { putLong("protocolVersion", 1) })
        }
        shadowOf(context.packageManager).setPackagesForUid(uid, caller, "example.shared")
        assertThrows(IllegalArgumentException::class.java) {
            FactoryOperationAuthorizationProvider.peerGuardStatus(context, caller, uid, null, input())
        }
    }
    @Suppress("DEPRECATION") @Test fun packageReplacementInvalidatesAnExistingConnection() {
        val value = snapshot()
        val info = context.packageManager.getPackageInfo(caller, 0)
        info.lastUpdateTime++; shadowOf(context.packageManager).installPackage(info)
        assertNull(check(value)); assertNull(begin(value))
    }
    @Suppress("DEPRECATION") @Test fun actualTerminalEndStillReleasesAfterPackageMetadataChanges() {
        val value = snapshot(); val action = begin(value)!!
        val info = context.packageManager.getPackageInfo(caller, 0)
        info.lastUpdateTime++; shadowOf(context.packageManager).installPackage(info)
        assertNull(check(value)); assertNull(begin(value))
        assertEquals(1L, end(value, action))
        val clean = surface(); assertTrue(clean.isReadyForUser)
    }
    @Test fun peerActionsHaveABoundedProcessWideAdmission() {
        val first = snapshot(); val second = snapshot(); val ids = mutableListOf<Long>()
        repeat(64) { ids += begin(first)!! }
        assertNull(begin(second))
        end(first, ids.first())
        assertNotNull(begin(second))
    }
}
