package com.jarvys.agent.connectors

import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.CommonStatusCodes
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class GoogleAuthorizationSessionTest {
    @Test fun disconnectAndAccountChangeInvalidateStaleGrantAndRequestsMonotonically() {
        val state = GoogleAuthorizationSession()
        val authorization = state.acquire(interactive = true)
        val read = state.acquire()
        state.invalidate()
        assertTrue(authorization.token.isCancellationRequested)
        assertTrue(read.token.isCancellationRequested)
        var committed = false
        assertTrue(runCatching { state.withCurrent(authorization) { committed = true } }.exceptionOrNull() is CancellationException)
        assertFalse(committed)
        val replacement = state.acquire(interactive = true)
        assertTrue(replacement.generation > authorization.generation)
        state.release(authorization)
        assertTrue(state.hasInteractive())
        state.withCurrent(replacement) { committed = true }
        assertTrue(committed)
    }

    @Test fun doubleTapDoesNotCreateSecondInteractiveAttemptAndCancelledLeaseCannotCommit() {
        val state = GoogleAuthorizationSession()
        val first = state.acquire(interactive = true)
        assertTrue(runCatching { state.acquire(interactive = true) }.isFailure)
        assertTrue(state.isCurrent(first))
        first.token.cancel()
        assertFalse(state.isCurrent(first))
        state.release(first)
        assertFalse(state.hasInteractive())
        val next = state.acquire(interactive = true)
        assertTrue(next.generation > first.generation)
    }

    @Test fun commonStatusCancellation16IsNotConnectionResultUnavailable16() {
        assertEquals(ConnectionResult.API_UNAVAILABLE, CommonStatusCodes.CANCELED)
        assertEquals(GoogleIdentityFailure.USER_CANCELLED, GoogleIdentityPolicy.apiStatusFailure(CommonStatusCodes.CANCELED))
        assertEquals(GoogleIdentityFailure.NETWORK, GoogleIdentityPolicy.apiStatusFailure(CommonStatusCodes.NETWORK_ERROR))
        assertEquals(GoogleIdentityFailure.ACCESS_BLOCKED, GoogleIdentityPolicy.apiStatusFailure(CommonStatusCodes.DEVELOPER_ERROR))
        assertEquals(GoogleIdentityFailure.PLAY_SERVICES_UNAVAILABLE, GoogleIdentityPolicy.playServicesFailure(false))
    }

    @Test fun expectedEpochCheckAndLeaseAcquisitionAreAtomicAndNewManagersUseDistinctEpochs() {
        val session = GoogleAuthorizationSession()
        val epoch = session.currentEpoch()
        assertEquals(epoch, session.acquire(expectedEpoch = epoch).generation)
        session.invalidate()
        assertTrue(runCatching { session.acquire(expectedEpoch = epoch) }.isFailure)
        assertNotEquals(epoch, session.currentEpoch())
        assertNotEquals(session.currentEpoch(), GoogleAuthorizationSession().currentEpoch())
    }
    @Test fun externalUiEpochAndDenyInvalidateLeaseWithoutAcquiringSessionOrRunningCallbacks() {
        val epoch = java.util.concurrent.atomic.AtomicLong()
        val deny = java.util.concurrent.atomic.AtomicBoolean()
        val session = GoogleAuthorizationSession(epoch::get, deny::get)
        val lease = session.acquire()
        epoch.incrementAndGet()
        deny.set(true)
        assertFalse(session.isCurrent(lease))
        assertTrue(runCatching { session.acquire() }.isFailure)
        assertTrue(runCatching { session.withCurrent(lease) { fail("No persistence") } }.isFailure)
        deny.set(false)
        assertFalse(session.isCurrent(lease))
        assertTrue(session.isCurrent(session.acquire()))
    }

}
