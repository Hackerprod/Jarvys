package com.jarvys.agent.workspaces

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.R
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h900dp-port-mdpi")
class WorkspacePagingComposeTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Test fun catalogErrorAndLoadingNeverPretendTheCatalogIsEmpty() {
        val failed = mutableStateOf(true)
        val loading = mutableStateOf(false)
        var retried = 0
        compose.setContent { MaterialTheme {
            WorkspaceListScreen(emptyList(), onOpenWorkspace = {}, workspaceError = if (failed.value) "Catalog unavailable" else null,
                workspacesLoading = loading.value, onRetryWorkspaces = { retried++ })
        } }
        compose.onNodeWithTag("workspace-list-error").assertExists()
        compose.onNodeWithTag("workspace-list-empty").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.workspace_retry)).performClick()
        assertEquals(1, retried)
        compose.runOnIdle { failed.value = false; loading.value = true }
        compose.onNodeWithTag("workspace-list-loading").assertExists()
        compose.onNodeWithTag("workspace-list-empty").assertDoesNotExist()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithTag("workspace-list-empty").assertExists()
    }
}
