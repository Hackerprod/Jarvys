package com.jarvys.agent.mcp

import androidx.activity.ComponentActivity
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.AppNavigationBackPolicy
import com.jarvys.agent.AppRouteAction
import com.jarvys.agent.AppRouteBackButton
import com.jarvys.agent.JarvysTopAppBar
import com.jarvys.agent.LucideIcons
import com.jarvys.agent.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpNavigationQ1Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var repository: McpServerRepository
    private lateinit var connections: McpConnectionManager
    private lateinit var oauth: McpOAuthManager
    private val nav = AtomicReference<NavHostController>()

    @Before fun clearServers() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        repository = McpServerRepository(context, TestCredentialVault())
        oauth = McpOAuthManager(repository)
        connections = McpConnectionManager(repository, oauth, McpWriteApprovalCoordinator.get(context))
        repository.servers.value.forEach { server ->
            connections.disconnect(server.id)
            repository.delete(server.id)
            repository.clearBearerToken(server.id)
            oauth.clear(server.id)
        }
    }

    private fun showGraph() {
        compose.setContent {
            val context = LocalContext.current
            MaterialTheme {
                val controller = rememberNavController()
                nav.set(controller)
                val entry by controller.currentBackStackEntryAsState()
                val route = entry?.destination?.route ?: AppNavigationBackPolicy.MCP_LIST
                val id = entry?.arguments?.getString("serverId")
                val servers by repository.servers.collectAsState()
                val alias = servers.firstOrNull { it.id == id }?.alias
                val meta = AppNavigationBackPolicy.metadata(context, route, "Chat", null, alias)
                Scaffold(topBar = {
                    JarvysTopAppBar(
                        title = { Text(meta.title, modifier = Modifier.testTag("route-title")) },
                        navigationIcon = { AppRouteBackButton(onFallback = { controller.popBackStack() }) },
                        actions = {
                            if (meta.action == AppRouteAction.MCP_ADD) IconButton(
                                onClick = { controller.navigate(AppNavigationBackPolicy.MCP_NEW) },
                                modifier = Modifier.testTag("mcp-add-server"),
                            ) { Icon(LucideIcons.Plus, contentDescription = null) }
                        },
                    )
                }) { padding ->
                    NavHost(controller, startDestination = AppNavigationBackPolicy.MCP_LIST,
                        modifier = Modifier.padding(padding)) {
                        mcpDestinations(controller, repository, connections, oauth)
                        composable(AppNavigationBackPolicy.SETTINGS) {
                            Text("Settings", modifier = Modifier.testTag("settings-route"))
                        }
                        composable(AppNavigationBackPolicy.CHAT_ROOT) {
                            Text("Chat", modifier = Modifier.testTag("chat-route"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun addRouteBackArrowAndSystemDispatcherReturnToMcpList() {
        showGraph()
        compose.onNodeWithTag("mcp-add-server").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("mcp-server-editor").assertIsDisplayed()
        compose.onNodeWithText("Add MCP server").assertIsDisplayed()
        compose.onNodeWithTag("jarvys-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("mcp-server-list").assertIsDisplayed()

        compose.onNodeWithTag("mcp-add-server").performClick()
        compose.waitForIdle()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithTag("mcp-server-list").assertIsDisplayed()
    }

    @Test fun dirtyEditorBackShowsDiscardDialogUntilConfirmedAndCleanEditorPopsDirectly() {
        showGraph()
        compose.onNodeWithTag("mcp-add-server").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("mcp-editor-alias").performTextInput("Draft server")
        compose.onNodeWithTag("jarvys-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_discard_changes_title)).assertIsDisplayed()
        assertEquals(AppNavigationBackPolicy.MCP_NEW, nav.get().currentDestination?.route)
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_discard_changes_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.MCP_LIST, nav.get().currentDestination?.route)

        compose.onNodeWithTag("mcp-add-server").performClick()
        compose.waitForIdle()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.MCP_LIST, nav.get().currentDestination?.route)
        compose.onAllNodesWithTag("mcp-server-editor").assertCountEquals(0)
    }

    @Test fun detailAndEditRoutesUseServerArgumentAndMissingServerReturnsToList() {
        val server = McpServerConfig("route-server", "Research endpoint", "https://example.test/mcp", enabled = false)
        repository.upsert(server)
        showGraph()
        compose.onNodeWithTag("mcp-server-row-${server.id}").performClick()
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.MCP_SERVER, nav.get().currentDestination?.route)
        compose.onAllNodesWithText("Research endpoint").assertCountEquals(2)
        compose.onNodeWithTag("jarvys-back").performClick()
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.MCP_LIST, nav.get().currentDestination?.route)

        nav.get().navigate(AppNavigationBackPolicy.editMcpServer(server.id))
        compose.waitForIdle()
        compose.onNodeWithText("Configure Research endpoint").assertIsDisplayed()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.MCP_LIST, nav.get().currentDestination?.route)

        nav.get().navigate(AppNavigationBackPolicy.mcpServer("missing-server"))
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.MCP_LIST, nav.get().currentDestination?.route)
        compose.onNodeWithTag("mcp-server-list").assertIsDisplayed()
    }

    @Test fun savedMcpRouteDoesNotReopenEditorAfterLeavingAndReturningToSettings() {
        showGraph()
        compose.onNodeWithTag("mcp-add-server").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("mcp-editor-alias").performTextInput("Saved server")
        compose.onNodeWithTag("mcp-editor-endpoint").performTextInput("https://example.test/mcp")
        compose.onNodeWithTag("mcp-editor-alias").assertTextContains("Saved server")
        compose.onNodeWithTag("mcp-editor-endpoint").assertTextContains("https://example.test/mcp")
        compose.onNodeWithTag("mcp-editor-enabled-switch").performScrollTo().performClick()
        compose.onNodeWithTag("mcp-editor-save").performScrollTo().performClick()
        compose.waitForIdle()
        val editorErrors = compose.onAllNodesWithTag("mcp-editor-error").fetchSemanticsNodes()
        assertTrue("MCP editor rejected the test input: $editorErrors", editorErrors.isEmpty())
        assertTrue("Saved server was not persisted: ${repository.servers.value}",
            repository.servers.value.any { it.alias == "Saved server" })
        assertEquals(AppNavigationBackPolicy.MCP_LIST, nav.get().currentDestination?.route)
        assertEquals(false, repository.servers.value.single { it.alias == "Saved server" }.enabled)

        nav.get().navigate(AppNavigationBackPolicy.SETTINGS)
        nav.get().navigate(AppNavigationBackPolicy.CHAT_ROOT)
        nav.get().navigate(AppNavigationBackPolicy.SETTINGS)
        nav.get().navigate(AppNavigationBackPolicy.MCP_LIST)
        compose.waitForIdle()
        compose.onNodeWithTag("mcp-server-list").assertIsDisplayed()
        compose.onAllNodesWithTag("mcp-server-editor").assertCountEquals(0)
    }

    @Test fun mcpServerListContainsOnlyUserServersAndNeverHarnessTools() {
        showGraph()
        compose.onNodeWithTag("mcp-server-list").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_no_servers)).assertIsDisplayed()
        compose.onAllNodesWithText("Jarvys local tools").assertCountEquals(0)
        compose.onAllNodesWithText("Local device tools").assertCountEquals(0)

        val server = McpServerConfig("user-server", "User MCP", "https://example.test/mcp", enabled = false)
        repository.upsert(server)
        compose.waitForIdle()
        compose.onNodeWithTag("mcp-server-row-${server.id}").assertIsDisplayed()
        compose.onAllNodesWithText("Jarvys local tools").assertCountEquals(0)
        compose.onAllNodesWithText("Local device tools").assertCountEquals(0)

        val source = File(sourceRoot(), "com/jarvys/agent/ui/mcp/McpWorkspaceScreens.kt").readText()
        assertFalse(source.contains("ToolRegistry"))
        assertFalse(source.contains("toolsForOperator"))
        assertFalse(source.contains("localListRow"))
    }

    @Test fun addRouteShowsInitialAskSelectorAndPersistsDenyWhileEditHidesIt() {
        showGraph()
        compose.onNodeWithTag("mcp-add-server").performClick()
        compose.onNodeWithTag("mcp-editor-tool-policy").performScrollTo().assertIsDisplayed()
        val ask = compose.activity.getString(R.string.remote_service_policy_ask)
        compose.onNodeWithTag("mcp-editor-tool-policy-selector").performScrollTo().assertTextContains(ask)
        compose.onNodeWithTag("mcp-editor-tool-policy-selector").performClick()
        compose.onNodeWithTag("mcp-editor-tool-policy-option-deny").assertIsDisplayed().performClick()
        compose.onNodeWithTag("mcp-editor-alias").performTextInput("Deny default server")
        compose.onNodeWithTag("mcp-editor-endpoint").performTextInput("https://deny.example/mcp")
        compose.onNodeWithTag("mcp-editor-enabled-switch").performClick()
        compose.onNodeWithTag("mcp-editor-save").performScrollTo().performClick()
        compose.waitForIdle()
        val saved = repository.servers.value.single { it.alias == "Deny default server" }
        assertEquals(McpInitialToolPolicy.DENY, saved.initialToolPolicy)
        assertEquals(AppNavigationBackPolicy.MCP_LIST, nav.get().currentDestination?.route)

        nav.get().navigate(AppNavigationBackPolicy.editMcpServer(saved.id))
        compose.waitForIdle()
        compose.onNodeWithTag("mcp-server-editor").assertIsDisplayed()
        compose.onAllNodesWithTag("mcp-editor-tool-policy").assertCountEquals(0)
    }

    @Test fun addRouteCanPersistInitialAllowPolicy() {
        showGraph()
        compose.onNodeWithTag("mcp-add-server").performClick()
        compose.onNodeWithTag("mcp-editor-tool-policy-selector").performScrollTo().assertTextContains(
            compose.activity.getString(R.string.remote_service_policy_ask))
        compose.onNodeWithTag("mcp-editor-tool-policy-selector").performClick()
        compose.onNodeWithTag("mcp-editor-tool-policy-option-allow").assertIsDisplayed().performClick()
        compose.onNodeWithTag("mcp-editor-alias").performTextInput("Allow default server")
        compose.onNodeWithTag("mcp-editor-endpoint").performTextInput("https://allow.example/mcp")
        compose.onNodeWithTag("mcp-editor-enabled-switch").performClick()
        compose.onNodeWithTag("mcp-editor-save").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(McpInitialToolPolicy.ALLOW,
            repository.servers.value.single { it.alias == "Allow default server" }.initialToolPolicy)
    }

    @Test fun metadataResolvesTheToolbarTitleForEveryMcpRoute() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(context.getString(R.string.mcp_screen_title),
            AppNavigationBackPolicy.metadata(context, AppNavigationBackPolicy.MCP_LIST, "", null, null).title)
        assertEquals(context.getString(R.string.mcp_server_add_title),
            AppNavigationBackPolicy.metadata(context, AppNavigationBackPolicy.MCP_NEW, "", null, null).title)
        assertEquals("Reviewer", AppNavigationBackPolicy.metadata(context, AppNavigationBackPolicy.MCP_SERVER,
            "", null, "Reviewer").title)
        assertEquals(context.getString(R.string.mcp_server_edit_title, "Reviewer"),
            AppNavigationBackPolicy.metadata(context, AppNavigationBackPolicy.MCP_SERVER_EDIT,
                "", null, "Reviewer").title)
    }

    private class TestCredentialVault : McpCredentialVault {
        private val values = ConcurrentHashMap<String, String>()
        override fun get(serverId: String, key: String): String? = values["$serverId/$key"]
        override fun save(serverId: String, key: String, value: String) { values["$serverId/$key"] = value }
        override fun clear(serverId: String) { values.keys.removeIf { it.startsWith("$serverId/") } }
    }

    private fun sourceRoot(): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return sequenceOf(File(working, "src/main/java"), File(working, "app/src/main/java"),
            File(working.parentFile, "app/src/main/java")).firstOrNull(File::isDirectory)
            ?: error("Could not find app/src/main/java from ${working.path}")
    }
}
