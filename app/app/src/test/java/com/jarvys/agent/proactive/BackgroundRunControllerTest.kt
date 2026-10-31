package com.jarvys.agent.proactive

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRunControllerTest {
    @After fun reset() {
        BackgroundRunController.cancelAll()
        BackgroundRunController.interactiveFinished()
    }

    @Test fun taskPreemptsProactiveAndOnlyOneBackgroundLeaseExists() {
        val proactive = BackgroundRunController.tryStart(BackgroundRunKind.PROACTIVE)
        assertNotNull(proactive)
        assertNull(BackgroundRunController.tryStart(BackgroundRunKind.TASK))
        assertTrue(proactive!!.isCancelled)

        BackgroundRunController.finish(BackgroundRunKind.PROACTIVE, proactive)
        val task = BackgroundRunController.tryStart(BackgroundRunKind.TASK)
        assertNotNull(task)
        assertNull(BackgroundRunController.tryStart(BackgroundRunKind.PROACTIVE))
        BackgroundRunController.finish(BackgroundRunKind.TASK, task!!)
    }

    @Test fun interactiveRunCancelsBackgroundAndBlocksNewBackgroundUntilItFinishes() {
        val task = BackgroundRunController.tryStart(BackgroundRunKind.TASK)
        assertNotNull(task)
        BackgroundRunController.interactiveStarted()
        assertTrue(task!!.isCancelled)
        assertNull(BackgroundRunController.tryStart(BackgroundRunKind.PROACTIVE))
        assertNull(BackgroundRunController.tryStart(BackgroundRunKind.TASK))
        BackgroundRunController.finish(BackgroundRunKind.TASK, task)
        BackgroundRunController.interactiveFinished()
        assertFalse(BackgroundRunController.isInteractiveActive())
        val nextTask = BackgroundRunController.tryStart(BackgroundRunKind.TASK)
        assertNotNull(nextTask)
        BackgroundRunController.finish(BackgroundRunKind.TASK, requireNotNull(nextTask))
    }

    @Test fun proactiveOptOutCancellationDoesNotStopAScheduledTask() {
        val task = BackgroundRunController.tryStart(BackgroundRunKind.TASK)
        assertNotNull(task)
        ProactiveRunController.cancelAll()
        assertFalse(requireNotNull(task).isCancelled)
        assertNull(BackgroundRunController.tryStart(BackgroundRunKind.PROACTIVE))
        BackgroundRunController.cancelAll()
        assertTrue(requireNotNull(task).isCancelled)
        BackgroundRunController.finish(BackgroundRunKind.TASK, requireNotNull(task))
    }
}
