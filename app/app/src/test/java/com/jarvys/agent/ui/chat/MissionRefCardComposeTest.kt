package com.jarvys.agent.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.jarvys.agent.crew.CrewBotSnapshot
import com.jarvys.agent.crew.CrewMissionSnapshot
import com.jarvys.agent.workspaces.MissionRef
import com.jarvys.agent.workspaces.WorkspaceIdentity
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** B12: the thread draws a mission from its reference and opens it with the reference's own session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h900dp-port-mdpi")
class MissionRefCardComposeTest {
    @OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = UnconfinedTestDispatcher())

    private val workspaceId = "11111111-1111-1111-1111-111111111111"
    private val ref = MissionRef(workspaceId, WorkspaceIdentity.executionSessionId(workspaceId), "mission-1", "assignment-1")

    private fun mission() = CrewMissionSnapshot("mission-1", ref.executionSessionId, "process", "Compare sources",
        "RUNNING", "", 1L, 0L, listOf(CrewBotSnapshot("bot-1", "analyst", "Analyst", "Ada", "amber", "Task",
            "RUNNING", "", "", "", emptyList(), 1L, 0L)), emptyList())

    @Test fun aResolvedReferenceShowsItsMissionCardAndOpensWithTheSessionOfTheReference() {
        val opened = mutableListOf<MissionRef>()
        compose.setContent { MaterialTheme { MissionRefCard(ref, { opened += it }, { _, _ -> mission() }) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("crew-mission-card").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("crew-mission-card").assertIsDisplayed().performClick()

        assertEquals(listOf(ref), opened)
        assertEquals(ref.executionSessionId, opened.single().executionSessionId)
        compose.onAllNodesWithTag("mission-ref-unavailable").assertCountEquals(0)
        compose.onAllNodesWithTag("mission-ref-result").assertCountEquals(0)
    }

    @Test fun aReferenceThatNoLongerResolvesSaysSoInsteadOfDisappearing() {
        val opened = mutableListOf<MissionRef>()
        compose.setContent { MaterialTheme { MissionRefCard(ref, { opened += it }, { _, _ -> null }) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("mission-ref-unavailable").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("mission-ref-unavailable").assertIsDisplayed()
        compose.onAllNodesWithTag("crew-mission-card").assertCountEquals(0)
        compose.onNodeWithTag("mission-ref-unavailable").performClick()
        assertEquals("the mission screen, not Central, explains the missing mission", listOf(ref), opened)
    }

    @Test fun aResultReferenceAnnouncesTheDeliveredResultAboveTheCard() {
        val withResult = ref.copy(resultId = "result-1", resultSha256 = "a".repeat(64))
        compose.setContent { MaterialTheme { MissionRefCard(withResult, {}, { _, _ -> mission() }) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("crew-mission-card").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("mission-ref-result").assertIsDisplayed()
    }
}
