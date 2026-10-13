package com.jarvys.agent.ui.mascot

import com.jarvys.agent.JarvysVerifiedAnimation
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Fresh tests for the reconstructed source; previous test receipts do not apply to this build. */
class JarvysHeaderAnimationPolicyTest {
    @Test fun everyMotionGateMustPermitAnimation() {
        for (allowed in listOf(false, true)) for (resumed in listOf(false, true))
            for (visible in listOf(false, true)) for (reduced in listOf(false, true))
                assertEquals(allowed && resumed && visible && !reduced, jarvysHeaderMayAnimate(allowed, resumed, visible, reduced))
    }
    @Test fun knownOriginalBindingsKeepExactCaseAndPlaybackMode() {
        val names = listOf("IdleMascot" to "Idle", "SadMascot" to "Sadness", "Thinking" to "thinking", "WorkingMascot" to "Working")
        for ((index, timeline) in listOf(JarvysHeaderTimeline.IDLE, JarvysHeaderTimeline.SAD, JarvysHeaderTimeline.THINKING, JarvysHeaderTimeline.WORKING).withIndex()) {
            assertEquals(names[index].first, timeline.artboard)
            assertEquals(names[index].second, timeline.animation)
            assertEquals(timeline == JarvysHeaderTimeline.SAD, timeline.oneShot)
        }
    }
    @Test fun terminalGateNeverEvictsOrReplaysConsumedIdsAtCapacity() {
        val gate = JarvysFailurePlaybackGate(2)
        assertFalse(gate.consume(null)); assertFalse(gate.consume(" ")); assertFalse(gate.consume("x".repeat(257)))
        assertTrue(gate.consume("a")); assertFalse(gate.consume("a")); assertTrue(gate.consume("b"))
        assertFalse(gate.consume("c")); assertFalse(gate.consume("a"))
    }
    @Test fun sadnessCacheMissAndHiddenObservationAreConsumed() {
        val sad = JarvysVerifiedAnimation(File("sad.riv"), "a".repeat(64), 600, "default", "sad")
        for (initial in listOf(true to null, false to sad)) {
            val gate = JarvysFailurePlaybackGate()
            assertNull(jarvysHeaderClaimSad("run", initial.first, initial.second, gate))
            assertNull(jarvysHeaderClaimSad("run", true, sad, gate))
        }
    }
    @Test fun exactTenModeTableDoesNotAdmitUnknownValues() {
        assertEquals(listOf("idle", "thinking", "working", "idle", "waiting", "waiting", "idle", "sad", "interrupted", "taunt"),
            (0..9).map(::jarvysHeaderAnimationIdForMode))
        for (unknown in listOf(-1, 10, Int.MIN_VALUE, Int.MAX_VALUE)) assertNull(jarvysHeaderAnimationIdForMode(unknown))
    }

    @Test fun explicitAnimationRequestsPlayEveryHeaderIdentityOnce() {
        for (id in jarvysHeaderAnimationIds) {
            val requested = checkNotNull(jarvysHeaderRequestedTimeline(id))
            assertEquals(id, requested.remoteId ?: if (id == "idle") "idle" else null)
            assertTrue(requested.oneShot)
        }
        assertNull(jarvysHeaderRequestedTimeline(null))
        assertNull(jarvysHeaderRequestedTimeline("unknown"))
    }
}
