package com.jarvys.agent

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PeerUiAutomationGuardTest {
    @Before fun isolateFactoryStartup() { com.jarvys.agent.apkfactory.FactoryStartupTestIsolation.releaseCompletedSharingStartup() }

    private class Peer : Binder() {
        val instance = "a".repeat(64)
        var epoch = 0L
        var protected = false
        var tainted = false
        var available = true
        var rejectBegin = false
        var identityCurrent = true
        var ended = 0
        private var next = 0L
        private val actions = mutableSetOf<Long>()
        val active get() = actions.size

        fun status() = Bundle().apply {
            putInt("protocolVersion", 1); putString("instance", instance); putLong("epoch", epoch)
            putBoolean("protected", protected); putBinder("guard", this@Peer)
        }
        fun proof() = PeerUiAutomationClient.decode(status()) { identityCurrent }
        fun openProtected() { tainted = active != 0; protected = true; epoch++ }
        fun closeProtected() { protected = false; tainted = false; epoch++ }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (!available) throw RemoteException("peer process died")
            data.enforceInterface(PeerUiAutomationClient.DESCRIPTOR)
            require(data.readString() == instance)
            val output = requireNotNull(reply)
            when (code) {
                IBinder.FIRST_CALL_TRANSACTION -> {
                    val expected = data.readLong()
                    require(data.dataAvail() == 0)
                    output.writeNoException(); output.writeInt(if (!protected && expected == epoch) 1 else 0)
                }
                IBinder.FIRST_CALL_TRANSACTION + 1 -> {
                    val expected = data.readLong(); requireNotNull(data.readStrongBinder())
                    require(data.dataAvail() == 0)
                    check(!rejectBegin && !protected && expected == epoch)
                    val id = ++next; actions += id
                    output.writeNoException(); output.writeLong(id)
                }
                IBinder.FIRST_CALL_TRANSACTION + 2 -> {
                    val id = data.readLong(); require(data.dataAvail() == 0)
                    if (actions.remove(id)) ended++
                    output.writeNoException(); output.writeInt(1)
                }
                else -> return false
            }
            return true
        }
    }

    @Test fun ordinaryPeerAllowsAutomationAndEndsSynchronousActionExactlyOnce() {
        val peer = Peer()
        val epoch = MemoryUiAutomationGuard.captureWithProof(peer.proof())
        var performed = 0
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) {
            assertEquals(1, peer.active)
            performed++
        })
        assertEquals(1, performed); assertEquals(0, peer.active); assertEquals(1, peer.ended)
        assertTrue(MemoryUiAutomationGuard.isAutomationEpochValid(epoch))
        MemoryUiAutomationGuard.enterProtectedSurface().use { assertTrue(it.isReadyForUser) }
    }

    @Test fun asyncPeerLeaseSurvivesWaiterTimeoutAndTaintsAskUntilSurfaceReopens() {
        val peer = Peer()
        val epoch = MemoryUiAutomationGuard.captureWithProof(peer.proof())
        lateinit var callbackLease: MemoryUiAutomationGuard.Action
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) {
            callbackLease = MemoryUiAutomationGuard.beginAsyncAction(epoch)
            assertEquals(2, peer.active)
        })
        assertEquals(1, peer.active) // Ending a synchronous waiter does not close the dispatched gesture.
        peer.openProtected()
        assertTrue(peer.tainted)
        callbackLease.close(); callbackLease.close()
        assertEquals(0, peer.active); assertEquals(2, peer.ended)
        assertTrue(peer.tainted) // Callback completion cannot turn synthetic interaction into consent.
        assertFalse(MemoryUiAutomationGuard.isAutomationEpochValid(epoch))
        assertFalse(MemoryUiAutomationGuard.runAutomated(epoch) { error("Queued tap ran") })
        peer.closeProtected()
        assertFalse(MemoryUiAutomationGuard.isAutomationEpochValid(epoch))
        val fresh = MemoryUiAutomationGuard.captureWithProof(peer.proof())
        assertTrue(MemoryUiAutomationGuard.runAutomated(fresh) { })
    }

    @Test fun peerProtectedBeforeCaptureAndOpenCloseDuringObservationBothFailClosed() {
        val peer = Peer()
        val before = MemoryUiAutomationGuard.captureWithProof(peer.proof())
        peer.openProtected()
        assertThrows(IllegalStateException::class.java) { peer.proof() }
        assertThrows(IllegalStateException::class.java) { MemoryUiAutomationGuard.requireAutomationEpoch(before) }
        peer.closeProtected()
        assertThrows(IllegalStateException::class.java) { MemoryUiAutomationGuard.requireAutomationEpoch(before) }
    }

    @Test fun beginFailurePeerDeathOrIdentityReplacementNeverDispatchAndReleaseLocalAction() {
        for (scenario in 0..2) {
            val peer = Peer()
            val captured = MemoryUiAutomationGuard.captureWithProof(peer.proof())
            when (scenario) {
                0 -> peer.rejectBegin = true
                1 -> peer.available = false
                else -> peer.identityCurrent = false
            }
            assertFalse(MemoryUiAutomationGuard.runAutomated(captured) { error("Unproven peer dispatch") })
            assertEquals(0, peer.active)
            MemoryUiAutomationGuard.enterProtectedSurface().use { assertTrue(it.isReadyForUser) }
        }
    }

    @Test fun localProtectionRemainsEffectiveWithAnUnprotectedPeer() {
        val peer = Peer()
        val captured = MemoryUiAutomationGuard.captureWithProof(peer.proof())
        MemoryUiAutomationGuard.enterProtectedSurface().use {
            assertFalse(MemoryUiAutomationGuard.runAutomated(captured) { error("Local consent bypass") })
            assertEquals(0, peer.active)
        }
        assertFalse(MemoryUiAutomationGuard.isAutomationEpochValid(captured))
    }

    @Test fun peerReplyRequiresExactTypedProtocolAndCannotFallBackToAllow() {
        val peer = Peer()
        val malformed = listOf(
            Bundle(),
            peer.status().apply { putInt("protocolVersion", 2) },
            peer.status().apply { putInt("epoch", 0) },
            peer.status().apply { putString("instance", "unexpected") },
            peer.status().apply { putString("protected", "false") },
            peer.status().apply { putString("extra", "ignored?") },
            peer.status().apply { remove("guard") },
        )
        for (reply in malformed) assertThrows(IllegalStateException::class.java) {
            PeerUiAutomationClient.decode(reply) { true }
        }
    }

    @Test fun localProviderSnapshotIsAtomicAndDoesNotContactAnyPeer() {
        val initial = MemoryUiAutomationGuard.localSnapshot()
        assertEquals(0L, initial[1])
        val action = MemoryUiAutomationGuard.localBeginAsyncAction(initial[0])
        MemoryUiAutomationGuard.enterProtectedSurface().use {
            assertFalse(it.isReadyForUser)
            val guarded = MemoryUiAutomationGuard.localSnapshot()
            assertEquals(1L, guarded[1]); assertTrue(guarded[0] > initial[0])
            assertFalse(MemoryUiAutomationGuard.localIsAutomationEpochValid(initial[0]))
            action.close()
            assertFalse(it.isReadyForUser)
        }
        action.close()
    }

    @Test @Config(sdk = [27]) fun missingPeerAllowsDeviceAutomationButInstallingPeerInvalidatesOldObservation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val epoch = MemoryUiAutomationGuard.captureAutomationEpoch(context)
        var ran = false
        assertTrue(MemoryUiAutomationGuard.runAutomated(epoch) { ran = true })
        assertTrue(ran)
        installUncooperativePeer(context)
        assertFalse(MemoryUiAutomationGuard.isAutomationEpochValid(epoch))
        assertFalse(MemoryUiAutomationGuard.runAutomated(epoch) { error("New host was ignored") })
    }

    @Test @Config(sdk = [27]) fun oldPeerBlocksOnlyUnprovenDeviceAutomationAndNeverLocalNativeGuard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installUncooperativePeer(context)
        assertThrows(IllegalStateException::class.java) { MemoryUiAutomationGuard.captureAutomationEpoch(context) }
        assertFalse(MemoryUiAutomationGuard.isProtected())
        val local = MemoryUiAutomationGuard.captureAutomationEpoch()
        assertTrue(MemoryUiAutomationGuard.localIsAutomationEpochValid(local))
        MemoryUiAutomationGuard.enterProtectedSurface().use { assertTrue(it.isReadyForUser) }
    }

    @Suppress("DEPRECATION") private fun installUncooperativePeer(context: Context) {
        val peer = if (context.packageName == "com.jarvys.agent") "com.jarvys.agent.recoverytest" else "com.jarvys.agent"
        val uid = 14567
        val packages = shadowOf(context.packageManager)
        packages.installPackage(PackageInfo().apply {
            packageName = peer; versionCode = 1; lastUpdateTime = 2
            signatures = arrayOf(Signature(byteArrayOf(1, 4, 9)))
            applicationInfo = ApplicationInfo().apply { packageName = peer; this.uid = uid; sourceDir = "/synthetic/old-host.apk" }
        })
        packages.setPackagesForUid(uid, peer)
    }
}
