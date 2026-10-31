package com.jarvys.agent.ui.mascot

import org.junit.Assert.*
import org.junit.Test

class JarvysHeaderFailureTrackerRecoveryTest {
    @Test fun historicalTerminalsNeverStartPlayback() {
        for (outcome in listOf("FAILED", "STOPPED", "PARTIAL", "TIMED_OUT", "INTERRUPTED")) {
            val tracker = JarvysHeaderFailureTracker()
            assertNull(tracker.observe("old", false, outcome, true, "session:1"))
            assertNull(tracker.interruptedRunId)
        }
    }

    @Test fun onlyObservedCurrentGenerationMayProduceFailure() {
        val tracker = JarvysHeaderFailureTracker()
        tracker.observe("old-run-id", true, null, true, "session:1")
        assertNull(tracker.observe("new-run-id", false, "FAILED", true, "session:2"))
        tracker.observe("new-run-id", true, null, true, "session:3")
        assertEquals("session:3", tracker.observe("completed-run-id", false, "FAILED", true, "session:3"))
        assertEquals("session:3", tracker.observe("completed-run-id", false, "FAILED", true, "session:3"))
        tracker.observe("completed-run-id", true, null, true, "session:3")
        assertNull(tracker.observe("completed-run-id", false, "FAILED", true, "session:3"))
    }

    @Test fun leavingLiveSurfaceConsumesObservationAndReentryDoesNotReplay() {
        val tracker = JarvysHeaderFailureTracker()
        tracker.observe(null, true, null, true, "session:1")
        tracker.observe(null, true, null, false, "session:1")
        assertNull(tracker.observe("done", false, "FAILED", true, "session:1"))
        assertNull(tracker.interruptedRunId)
    }

    @Test fun terminalInterruptionRequiresObservationAndUsesGenerationWhenRunIdStillOld() {
        for (outcome in listOf("STOPPED", "PARTIAL", "TIMED_OUT", "INTERRUPTED")) {
            val tracker = JarvysHeaderFailureTracker()
            tracker.observe("previous", true, null, true, "session:5")
            assertNull(tracker.observe("previous", false, outcome, true, "session:5"))
            assertEquals("session:5", tracker.interruptedRunId)
            tracker.observe("previous", false, outcome, false, "session:5")
            tracker.observe("previous", false, outcome, true, "session:5")
            assertNull(tracker.interruptedRunId)
        }
    }

    @Test fun completedRunProducesNeitherTerminalAnimation() {
        val tracker = JarvysHeaderFailureTracker()
        tracker.observe(null, true, null, true, "session:1")
        assertNull(tracker.observe("done", false, "COMPLETED", true, "session:1"))
        assertNull(tracker.interruptedRunId)
    }
}
