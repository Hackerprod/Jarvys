package com.jarvys.agent.workspaces

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.R
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h900dp-port-mdpi")
class WorkspaceDetailComposeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    private val first = WorkspaceAssignmentUi("first", 7, "Research", "Review sources", WorkspaceUiStatus.ACTIVE,
        canCorrect = true, canPause = true)
    private val second = WorkspaceAssignmentUi("second", 23, "Review", "Check result", WorkspaceUiStatus.PAUSED,
        recoveryNote = "Cleanup recorded", canCorrect = true, canResume = true)

    @Test fun actionsCarryOnlyTheirExactDisplayedTargetAndRevisionIncludingAfterReorder() {
        val rows = mutableStateOf(listOf(first, second))
        val calls = mutableListOf<Triple<String, String, Long>>()
        var files = 0
        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(WorkspaceDetailUiState("workspace", "Project", WorkspaceUiStatus.ACTIVE,
                rows.value, canOpenFiles = true),
                { id, revision -> calls += Triple("correct", id, revision) },
                { id, revision -> calls += Triple("pause", id, revision) },
                { id, revision -> calls += Triple("resume", id, revision) }, { files++ })
        } }
        compose.runOnIdle { assertTrue(calls.isEmpty()); assertEquals(0, files) }
        compose.onNodeWithTag("workspace-correct-first").revealInWorkspace("assignment-first").performTouchInput { click() }
        compose.onNodeWithTag("workspace-pause-first").revealInWorkspace("assignment-first").performTouchInput { click() }
        compose.onNodeWithTag("workspace-resume-second").revealInWorkspace("assignment-second").performTouchInput { click() }
        compose.runOnIdle { rows.value = listOf(second, first.copy(revision = 8)) }
        compose.onNodeWithTag("workspace-correct-first").revealInWorkspace("assignment-first").performTouchInput { click() }
        assertEquals(listOf(Triple("correct", "first", 7L), Triple("pause", "first", 7L),
            Triple("resume", "second", 23L), Triple("correct", "first", 8L)), calls)
        compose.onNodeWithTag("workspace-open-files").revealInWorkspace("workspace-summary").performTouchInput { click() }
        assertEquals(1, files)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    /**
     * Materialize the keyed item, then use bounded real gestures instead of ui-test 1.9.0's
     * open-ended performScrollTo loop. A clipped/unreachable production target fails with bounds;
     * this never invokes a callback directly, enlarges the viewport, or relaxes target assertions.
     */
    private fun SemanticsNodeInteraction.revealInWorkspace(itemKey: String): SemanticsNodeInteraction {
        val list = compose.onNodeWithTag("workspace-detail")
        // State writes and lazy-list scroll actions use the controlled effect dispatcher.
        // Commit the current item provider before key lookup, then its new layout before bounds.
        settleWorkspaceFrames()
        list.performScrollToKey(itemKey)
        settleWorkspaceFrames()
        var lastBounds = "not observed"
        repeat(6) { attempt ->
            val viewport = list.fetchSemanticsNode().boundsInRoot
            val node = fetchSemanticsNode()
            val top = node.positionInRoot.y
            val bottom = top + node.size.height
            val left = node.positionInRoot.x
            val right = left + node.size.width
            lastBounds = "attempt=$attempt item=$itemKey viewport=$viewport target=[$left,$top,$right,$bottom] size=${node.size}"
            // Physical-pixel rounding is harmless; substantive clipping must still fail.
            if (node.size.height > 0 && node.size.width > 0 && left >= viewport.left - 1f &&
                right <= viewport.right + 1f && top >= viewport.top - 1f && bottom <= viewport.bottom + 1f) {
                assertIsDisplayed()
                return this
            }
            val forward = bottom > viewport.bottom
            list.performTouchInput {
                val start = Offset(centerX, height * (if (forward) 0.75f else 0.25f))
                val end = Offset(centerX, height * (if (forward) 0.25f else 0.75f))
                swipe(start, end, durationMillis = 350)
            }
            compose.mainClock.advanceTimeBy(400)
            settleWorkspaceFrames()
        }
        throw AssertionError("Workspace target is not fully reachable after bounded real scrolls: $lastBounds")
    }

    /** Finite recomposition/layout barriers; do not retry missing keys or unreachable targets. */
    private fun settleWorkspaceFrames() {
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    @Test fun readOnlySurfaceHasOnlyCentralCommandsAndFilesWithRuntimeAvailability() {
        var calls = 0
        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(WorkspaceDetailUiState("workspace", "Project", WorkspaceUiStatus.UNKNOWN,
                listOf(first.copy(canCorrect = false, canPause = false))),
                { _, _ -> calls++ }, { _, _ -> calls++ }, { _, _ -> calls++ }, { calls++ })
        } }
        compose.onNodeWithTag("workspace-open-files").assertIsNotEnabled()
        compose.onAllNodes(hasClickAction()).assertCountEquals(4)
        listOf("correct", "pause", "resume").forEach {
            compose.onNodeWithTag("workspace-$it-first").revealInWorkspace("assignment-first").assertIsNotEnabled()
                .performTouchInput { click() }
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("crew-mission-composer").assertDoesNotExist()
        compose.onNodeWithTag("drawer-open-settings").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_result_unknown)).revealInWorkspace("assignment-first").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_recovery_unknown)).revealInWorkspace("assignment-first").assertIsDisplayed()
        assertEquals(0, calls)
    }

    @Test fun loadingAndErrorDisableStaleCommandsAndDoNotPretendTheWorkspaceIsEmpty() {
        val loading = mutableStateOf(true)
        val error = mutableStateOf<String?>(null)
        var calls = 0
        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(WorkspaceDetailUiState("workspace", "Project", WorkspaceUiStatus.INTERRUPTED,
                listOf(first), loading.value, error.value, true),
                { _, _ -> calls++ }, { _, _ -> calls++ }, { _, _ -> calls++ }, { calls++ })
        } }
        compose.onNodeWithTag("workspace-loading").assertIsDisplayed()
        compose.onNodeWithTag("workspace-correct-first").revealInWorkspace("assignment-first").assertIsNotEnabled()
        compose.runOnIdle { loading.value = false; error.value = "Recorded data is unavailable" }
        compose.onNodeWithTag("workspace-error").revealInWorkspace("workspace-error").assertIsDisplayed()
        compose.onNodeWithTag("workspace-loading").assertDoesNotExist()
        compose.onNodeWithTag("workspace-empty").assertDoesNotExist()
        compose.onNodeWithTag("workspace-pause-first").revealInWorkspace("assignment-first").assertIsNotEnabled()
        compose.onNodeWithTag("workspace-open-files").revealInWorkspace("workspace-summary").assertIsNotEnabled()
        assertEquals(0, calls)
    }

    @Test fun emptyWorkspaceShowsHonestStateWithoutWorkerControls() {
        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(WorkspaceDetailUiState("workspace", "Project", WorkspaceUiStatus.UNKNOWN),
                { _, _ -> error("No assignment") }, { _, _ -> error("No assignment") },
                { _, _ -> error("No assignment") }, {})
        } }
        compose.onNodeWithTag("workspace-empty").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_status_unknown)).assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test fun completedStatusAndAWorkerReportDoNotFabricateVerifiedEvidence() {
        compose.setContent { MaterialTheme {
            WorkspaceDetailScreen(WorkspaceDetailUiState("workspace", "Project", WorkspaceUiStatus.COMPLETED,
                listOf(first.copy(status = WorkspaceUiStatus.COMPLETED, result = "Worker reported completion"))),
                { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_result_unverified)).revealInWorkspace("assignment-first").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_result_verified)).assertDoesNotExist()
    }
}
