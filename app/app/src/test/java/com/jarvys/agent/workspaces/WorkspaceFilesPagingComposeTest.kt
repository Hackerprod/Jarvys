package com.jarvys.agent.workspaces

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.R
import com.jarvys.agent.WorkspaceStore
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS-w360dp-h900dp-port-mdpi")
class WorkspaceFilesPagingComposeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Test fun sharedTextPagesHaveBackAndDetectContentChangesBeforeShowingAnotherPage() {
        val context = compose.activity.applicationContext
        val scope = WorkspaceRegistry(context).use { it.createWorkspace("files-pages", "Files") }
        val store = WorkspaceStore.forCrewBoard(context, scope.executionSessionId)
        val original = "PAGE_ONE\n" + "a".repeat(16000) + "\nPAGE_TWO"
        store.write("board/demo.txt", original)
        compose.setContent { MaterialTheme { WorkspaceFilesRoute(scope.workspaceId) } }
        openSharedFile("demo.txt")
        awaitText("PAGE_ONE", substring = true)
        compose.onNodeWithTag("workspace-next-page").performClick()
        awaitText("PAGE_TWO", substring = true)
        compose.onNodeWithTag("workspace-previous-page").performClick()
        awaitText("PAGE_ONE", substring = true)
        store.write("board/demo.txt", "CHANGED_SOURCE")
        compose.onNodeWithTag("workspace-next-page").performClick()
        awaitText(compose.activity.getString(R.string.workspace_file_unavailable))
        compose.onNodeWithText("PAGE_TWO", substring = true).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_retry_from_start)).performClick()
        awaitText("CHANGED_SOURCE")
        assertEquals("CHANGED_SOURCE", store.read("board/demo.txt"))
        compose.onNodeWithTag("workspace-next-page").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun switchingWorkspaceNeverUsesTheVisibleChatsFileOwner() {
        val context = compose.activity.applicationContext
        val first = WorkspaceRegistry(context).use { it.createWorkspace("files-owner-first", "First") }
        val second = WorkspaceRegistry(context).use { it.createWorkspace("files-owner-second", "Second") }
        WorkspaceStore.forCrewBoard(context, first.executionSessionId).write("board/same.txt", "FIRST_OWNER")
        WorkspaceStore.forCrewBoard(context, second.executionSessionId).write("board/same.txt", "SECOND_OWNER")
        context.getSharedPreferences("jarvys_chat", 0).edit().putString("active_session_id", "unrelated-legacy").commit()
        val selected = mutableStateOf(first.workspaceId)
        compose.setContent { MaterialTheme { WorkspaceFilesRoute(selected.value) } }
        openSharedFile("same.txt")
        awaitText("FIRST_OWNER")
        compose.runOnIdle { selected.value = second.workspaceId }
        openSharedFile("same.txt")
        awaitText("SECOND_OWNER")
        compose.onNodeWithText("FIRST_OWNER").assertDoesNotExist()
        assertEquals("unrelated-legacy", context.getSharedPreferences("jarvys_chat", 0).getString("active_session_id", null))
    }

    private fun openSharedFile(name: String) {
        val shared = compose.activity.getString(R.string.workspace_shared_files)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(shared) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(shared).performClick()
        awaitText("DIR  board")
        compose.onNodeWithText("DIR  board").performClick()
        awaitText("FILE $name (", substring = true)
        compose.onNodeWithText("FILE $name (", substring = true).performClick()
    }

    private fun awaitText(value: String, substring: Boolean = false) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }
}
