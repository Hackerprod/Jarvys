package com.jarvys.agent.ui.mascot

import com.jarvys.agent.crew.MascotPilotPreparation
import com.jarvys.agent.crew.MascotPilotSession
import com.jarvys.agent.crew.prepareMascotPilot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Pure policy coverage; no Android native loader, worker or renderer is used. */
class JarvysMascotPolicyTest {
    @Test fun onlyExplicitSupportedWireValuesCanPrepareLivePlayback() {
        for (mode in listOf(null, -1, 0, 1, 3, 4, 5, 9, Int.MAX_VALUE)) {
            assertFalse(jarvysMascotKnownLiveMode(mode))
            assertFalse(jarvysMascotMayPrepare(mode, true, true, true))
        }
        for (mode in listOf(2, 6, 7, 8)) assertTrue(jarvysMascotMayPrepare(mode, true, true, true))
    }

    @Test fun explicitConsentResumedAndVisibleAreAllRequired() {
        for (mode in listOf(2, 6, 7, 8)) {
            assertFalse(jarvysMascotMayPrepare(mode, false, true, true))
            assertFalse(jarvysMascotMayPrepare(mode, true, false, true))
            assertFalse(jarvysMascotMayPrepare(mode, true, true, false))
        }
    }

    @Test fun unknownOrClosedGateCannotLoadOrInitialize() = runBlocking {
        var loads = 0
        var inits = 0
        for (mode in listOf(null, 0, 1, 3, 4, 5, 9)) {
            val result = prepareMascotPilot(
                isCurrent = { jarvysMascotMayPrepare(mode, true, true, true) },
                verifiedLoad = { loads++; byteArrayOf(1) },
                initialize = { inits++; true },
            )
            assertSame(MascotPilotPreparation.Stale, result)
        }
        assertEquals(0, loads)
        assertEquals(0, inits)
    }

    @Test fun visibilityPauseDoesNotConsumeProcessConsentButStillBlocksPreparation() {
        val consent = MascotPilotSession().start(true, true)
        assertFalse(consent.accepts(consent.generation, false, true))
        assertFalse(consent.accepts(consent.generation, true, false))
        assertTrue(consent.optedIn)
        assertTrue(consent.accepts(consent.generation, true, true))
        assertFalse(jarvysMascotMayPrepare(2, consent.optedIn, false, true))
        assertFalse(jarvysMascotMayPrepare(2, consent.optedIn, true, false))
        assertFalse(jarvysMascotMayPrepare(null, consent.optedIn, true, true))
        assertTrue(jarvysMascotMayPrepare(7, consent.optedIn, true, true))
    }

    @Test fun temporaryConsentCannotSurviveRevocationOrNewProcess() {
        val allowed = MascotPilotSession().start(true, true)
        val revoked = allowed.stop()
        assertFalse(revoked.accepts(allowed.generation, true, true))
        assertFalse(MascotPilotSession().optedIn)
        assertFalse(allowed.accepts(allowed.generation, false, true))
        assertFalse(allowed.accepts(allowed.generation, true, false))
    }
}
