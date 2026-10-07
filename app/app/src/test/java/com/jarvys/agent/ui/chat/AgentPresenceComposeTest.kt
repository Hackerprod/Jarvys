package com.jarvys.agent.ui.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.AgentRunUiEvent
import com.jarvys.agent.AgentRunUiSnapshot
import com.jarvys.agent.R
import com.jarvys.agent.ui.motion.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentPresenceComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyPresenceStateHasVisibleAndSemanticTextAtReducedMotion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshot = mutableStateOf(AgentRunUiSnapshot())
        val labels = listOf(
            AgentRunUiSnapshot() to R.string.agent_presence_idle,
            AgentRunUiSnapshot(running = true) to R.string.agent_presence_thinking,
            AgentRunUiSnapshot(running = true, events = listOf(
                AgentRunUiEvent.toolEvent(1, "tool_call", "Calendar Search", null, "c1", null, 1)))
                to R.string.agent_presence_using_tool,
            AgentRunUiSnapshot(events = listOf(
                AgentRunUiEvent(1, "approval", "PENDING", "Confirm", approvalStatus = "PENDING")))
                to R.string.agent_presence_waiting_user,
            AgentRunUiSnapshot(outcome = "FAILED") to R.string.agent_presence_error,
            AgentRunUiSnapshot(outcome = "COMPLETED") to R.string.agent_presence_done,
        )
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MaterialTheme {
                    Box(Modifier.fillMaxWidth()) {
                        AgentPresenceIndicator(snapshot.value, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        for ((state, label) in labels) {
            snapshot.value = state
            compose.waitForIdle()
            val expected = if (label == R.string.agent_presence_using_tool)
                context.getString(label, "Calendar Search") else context.getString(label)
            compose.onNodeWithContentDescription(expected).assertIsDisplayed()
            compose.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
            val node = compose.onNodeWithTag("agent-presence-${AgentPresence.from(state).state.name.lowercase()}")
                .fetchSemanticsNode()
            assertEquals(expected, node.config[SemanticsProperties.StateDescription])
            assertEquals(1, compose.onAllNodesWithTag("agent-presence-motion-static", useUnmergedTree = true)
                .fetchSemanticsNodes().size)
        }
    }

    @Test fun waitingCopyWrapsFullyAtDoubleFontScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshot = AgentRunUiSnapshot(events = listOf(
            AgentRunUiEvent(1L, "user_decision", "PENDING", "Choose", decisionStatus = "PENDING"),
        ))
        compose.setContent {
            val original = LocalDensity.current
            CompositionLocalProvider(LocalReducedMotion provides true,
                LocalDensity provides Density(original.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.fillMaxWidth().heightIn(min = 100.dp)) {
                        AgentPresenceIndicator(snapshot, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        val waiting = context.getString(R.string.agent_presence_waiting_user)
        compose.onNodeWithText(waiting, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(waiting).assertIsDisplayed()
    }

    @Test fun activePresenceLoopIsRemovedWhenTheElementLeavesTheViewport() {
        compose.mainClock.autoAdvance = false
        val snapshot = mutableStateOf(AgentRunUiSnapshot(running = true))
        val reduced = mutableStateOf(false)
        val visible = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides reduced.value) {
                MaterialTheme { AgentPresenceIndicator(snapshot.value, visible = visible.value) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithTag("agent-presence-motion-active", useUnmergedTree = true)
            .fetchSemanticsNodes().size)
        compose.mainClock.advanceTimeBy(1_800)
        compose.waitForIdle()

        visible.value = false
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("agent-presence-motion-active", useUnmergedTree = true)
            .fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag("agent-presence-motion-static", useUnmergedTree = true)
            .fetchSemanticsNodes().size)
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()

        compose.runOnIdle {
            snapshot.value = AgentRunUiSnapshot(outcome = "COMPLETED")
            visible.value = true
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals(AgentPresence.State.DONE,
            AgentPresence.from(snapshot.value).state)
        assertEquals(1, compose.onAllNodesWithTag("agent-presence-motion-static", useUnmergedTree = true)
            .fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("agent-presence-motion-active", useUnmergedTree = true)
            .fetchSemanticsNodes().size)
    }
}
