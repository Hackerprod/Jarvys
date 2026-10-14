package com.jarvys.agent.connectors

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.SecretStore
import com.jarvys.agent.mcp.*
import com.jarvys.agent.ui.JarvysOwnTheme
import com.jarvys.agent.ui.settings.DeveloperWorkspace
import com.jarvys.agent.ui.mcp.McpServerDetailPage
import com.jarvys.agent.ui.mcp.McpServerDetailScreen
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class ConnectedConnectorPermissionScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val secretStoreSingleton = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
    private val testSecretStoreConstructor = SecretStore::class.java
        .getDeclaredConstructor(android.content.SharedPreferences::class.java).apply { isAccessible = true }
    private var priorSecretStore: Any? = null
    private val managers = mutableListOf<McpConnectionManager>()
    private val store = RecordingPermissionStore()
    private val dispatches = AtomicInteger()
    private val sessions = AtomicInteger()
    private val presenter = object : ApprovalPresenter {
        override fun show(id: String, summary: ApprovalSummary) = error("Policy editing must not execute tools")
        override fun update(id: String, decision: ApprovalDecision) = Unit
    }
    private val gate = ApprovalGate(100, presenter)
    private data class ShownContent(val fontScale: Float, val dark: Boolean, val content: @Composable () -> Unit)
    private var shownContent: MutableState<ShownContent>? = null

    @Before fun installInMemorySecretStore() {
        priorSecretStore = secretStoreSingleton.get(null)
        val preferences = compose.activity.getSharedPreferences("ux-permission-screen-secrets", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        secretStoreSingleton.set(null, testSecretStoreConstructor.newInstance(preferences))
    }

    @After fun closeConnections() {
        try { managers.forEach { it.close() } }
        finally { secretStoreSingleton.set(null, priorSecretStore) }
    }

    private fun show(fontScale: Float = 1f, dark: Boolean = false, content: @Composable () -> Unit) {
        val next = ShownContent(fontScale, dark, content)
        val existing = shownContent
        if (existing == null) {
            val state = mutableStateOf(next)
            shownContent = state
            compose.setContent {
                val screen = state.value
                JarvysOwnTheme(if (screen.dark) JarvysThemeMode.DARK else JarvysThemeMode.LIGHT) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, screen.fontScale)) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                            contentColor = MaterialTheme.colorScheme.onBackground) {
                            Box(Modifier.fillMaxSize()) { screen.content() }
                        }
                    }
                }
            }
        } else {
            compose.runOnIdle { existing.value = next }
        }
    }
    private fun pick(tag: String, resource: Int) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.onNode(hasText(compose.activity.getString(resource)) and hasAnyAncestor(isPopup())).performClick()
        compose.waitForIdle()
    }
    private fun assertPolicy(tag: String, resource: Int) {
        compose.onNode(hasText(compose.activity.getString(resource)) and
            (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true).assertExists()
    }
    private fun geometry(prefix: String) {
        // Scroll the entire row; scrolling only its centered button can leave tall labels clipped.
        compose.onNodeWithTag("$prefix-row", useUnmergedTree = true).performScrollTo()
        val row = compose.onNodeWithTag("$prefix-row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val control = compose.onNodeWithTag("$prefix-control").fetchSemanticsNode().boundsInRoot
        assertEquals("policy is pinned to the row's right edge", row.right, control.right, 1f)
        assertEquals("policy stays vertically centered", row.center.y, control.center.y, 1f)
        assertTrue("policy touch target remains at least 48 dp tall", control.height >= 48f)
        if (prefix.startsWith("connector-policy-")) {
            val origin = compose.onNodeWithTag("$prefix-origin").performScrollTo().assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val visibleRow = compose.onNodeWithTag("$prefix-row", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("provenance remains below the centered policy row", origin.top >= visibleRow.bottom - 1f)
        }
    }
    private fun capture(name: String) = compose.runOnIdle { captureConnectedPermissionWindows(compose.activity, name) }

    @Test fun connectedCalendarPoliciesInDeveloperConfigureReadAndWriteOnceWithoutExecuting() = calendar(1f, false)

    @Test fun largeTextPolicyLayoutDoesNotDependOnConnectorOrOperationIdentity() {
        val registry = ConnectorRegistry.createForTestsWithAutonomy(PermissionConnectionPreferences(), { true }, gate,
            store, NoopAutonomyActionNotifier)
        val firstOperation = ConnectorOperation("operation_one", "", JSONObject(), displayLabel = "Readable action label")
        val secondOperation = ConnectorOperation("operation_two", "", JSONObject(), displayLabel = "Readable action label")
        val firstDefinition = ConnectorDefinition("connector_one", "Connector One", "1", "test",
            operations = listOf(firstOperation))
        val secondDefinition = ConnectorDefinition("connector_two", "Connector Two", "1", "test",
            operations = listOf(secondOperation))
        registry.register(firstDefinition)
        registry.register(secondDefinition)

        show(fontScale = 2f) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                AutonomyPolicyRow(registry, firstDefinition, firstOperation)
                AutonomyPolicyRow(registry, secondDefinition, secondOperation)
            }
        }

        val first = "connector-policy-${firstOperation.name}"
        val second = "connector-policy-${secondOperation.name}"
        geometry(first)
        geometry(second)
        val firstRow = compose.onNodeWithTag("${first}-row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val secondRow = compose.onNodeWithTag("${second}-row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val firstLabel = compose.onNodeWithTag("${first}-label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val secondLabel = compose.onNodeWithTag("${second}-label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val firstControl = compose.onNodeWithTag("${first}-control").fetchSemanticsNode().boundsInRoot
        val secondControl = compose.onNodeWithTag("${second}-control").fetchSemanticsNode().boundsInRoot
        assertEquals(firstRow.width, firstLabel.width, 1f)
        assertEquals(secondRow.width, secondLabel.width, 1f)
        assertEquals(firstLabel.width, secondLabel.width, 1f)
        assertEquals(firstControl.width, secondControl.width, 1f)
        assertTrue(firstControl.top >= firstLabel.bottom - 1f)
        assertTrue(secondControl.top >= secondLabel.bottom - 1f)
    }

    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun connectedCalendarSpanishLargeTextKeepsDeveloperPoliciesAtRightCenter() = calendar(2f, true)

    private fun calendar(scale: Float, dark: Boolean) {
        val registry = ConnectorRegistry.createForTestsWithAutonomy(PermissionConnectionPreferences(), { true }, gate,
            store, NoopAutonomyActionNotifier)
        val toolCalls = AtomicInteger()
        val definition = CalendarConnector.definition(compose.activity).copy(runtime = object : ConnectorRuntime {
            override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
            override fun disconnect() = Unit
            private fun unexpected(): Nothing { toolCalls.incrementAndGet(); error("Policy editing must not execute calendar tools") }
            override fun prepareRead(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation = unexpected()
            override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation = unexpected()
            override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken): JSONObject = unexpected()
        })
        registry.register(definition)
        registry.connect(definition.id)
        assertEquals(ConnectorState.CONNECTED, registry.state(definition))
        show(scale, dark) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { DeveloperWorkspace(registry) }
        }
        compose.onNodeWithTag("developer-connector-${definition.id}").assertIsDisplayed()
        val read = "connector-policy-${CalendarConnector.SEARCH}"
        val write = "connector-policy-${CalendarConnector.CREATE}"
        compose.onNodeWithTag("$read-control").assertHasClickAction()
        assertPolicy("$read-control", R.string.connector_policy_allow)
        assertPolicy("$write-control", R.string.connector_policy_allow)
        geometry(read); geometry(write)
        capture("calendar-connected-${if (dark) "es-dark-large" else "en-light"}")
        compose.onNodeWithTag("$write-control").performClick()
        capture("calendar-open-policy-${if (dark) "es-dark-large" else "en-light"}")
        assertTrue(store.changes.isEmpty())
        compose.onNode(hasText(compose.activity.getString(R.string.connector_policy_deny)) and hasAnyAncestor(isPopup())).performClick()
        assertEquals(listOf(Triple(definition.id, CalendarConnector.CREATE, AutonomyPolicy.DENY)), store.changes)
        assertPolicy("$write-control", R.string.connector_policy_deny)
        pick("$write-control", R.string.connector_policy_ask)
        pick("$write-control", R.string.connector_policy_allow)
        assertPolicy("$write-control", R.string.connector_policy_allow)
        assertEquals(listOf(AutonomyPolicy.DENY, AutonomyPolicy.ASK, AutonomyPolicy.ALLOW), store.changes.map { it.third })
        assertPolicy("$read-control", R.string.connector_policy_allow)
        pick("$read-control", R.string.connector_policy_deny)
        assertPolicy("$read-control", R.string.connector_policy_deny)
        assertPolicy("$write-control", R.string.connector_policy_allow)
        pick("$read-control", R.string.connector_policy_ask)
        assertPolicy("$read-control", R.string.connector_policy_ask)
        pick("$read-control", R.string.connector_policy_allow)
        assertPolicy("$read-control", R.string.connector_policy_allow)
        assertEquals(listOf(
            Triple(definition.id, CalendarConnector.CREATE, AutonomyPolicy.DENY),
            Triple(definition.id, CalendarConnector.CREATE, AutonomyPolicy.ASK),
            Triple(definition.id, CalendarConnector.CREATE, AutonomyPolicy.ALLOW),
            Triple(definition.id, CalendarConnector.SEARCH, AutonomyPolicy.DENY),
            Triple(definition.id, CalendarConnector.SEARCH, AutonomyPolicy.ASK),
            Triple(definition.id, CalendarConnector.SEARCH, AutonomyPolicy.ALLOW),
        ), store.changes)
        assertEquals(0, toolCalls.get())
        geometry(read); geometry(write)
    }

    private class Vault : McpCredentialVault {
        val values = mutableMapOf<Pair<String, String>, String>()
        override fun get(serverId: String, key: String) = values[serverId to key]
        override fun save(serverId: String, key: String, value: String) { values[serverId to key] = value }
        override fun clear(serverId: String) { values.keys.removeAll { it.first == serverId } }
    }
    private data class ConnectedMcp(val repository: McpServerRepository, val oauth: McpOAuthManager,
        val manager: McpConnectionManager, val serverId: String, val vault: Vault)
    private fun connectedMcp(catalog: String?, tools: List<McpToolConfig>): ConnectedMcp {
        compose.activity.getSharedPreferences("jarvys_mcp_servers", Context.MODE_PRIVATE).edit().clear().commit()
        val vault = Vault()
        val repository = McpServerRepository(compose.activity, vault)
        val oauth = McpOAuthManager(repository)
        val service = catalog?.let(RemoteServiceCatalog::find)
        val config = McpServerConfig(service?.mcpServerId ?: "permission_fixture", "Permission fixture",
            service?.endpoint ?: "https://example.test/mcp", tools = tools, catalogServiceId = catalog,
            authMode = if (catalog == "github") McpAuthMode.BEARER else McpAuthMode.NONE,
            githubToolsets = setOf("context", "issues"))
        repository.upsert(config)
        if (catalog == "github") repository.saveBearerToken(config.id, config.endpoint, "fixture-token-not-a-real-credential")
        val manager = McpConnectionManager(repository, oauth,
            McpWriteApprovalCoordinator(store, gate, NoopAutonomyActionNotifier), sessionFactory = { _, _, _ ->
                sessions.incrementAndGet()
                object : McpProtocolSession {
                    override val serverInfo = McpServerInfo("Permission fixture", "1", null)
                    override val tools = tools
                    override fun callTool(name: String, arguments: JSONObject, token: CancellationToken): JSONObject {
                        dispatches.incrementAndGet(); error("No remote invocation is permitted during a policy edit")
                    }
                    override fun close() = Unit
                }
            })
        managers += manager
        manager.connect(config.id)
        compose.waitUntil(5_000) { manager.state(config.id).status == McpConnectionStatus.READY }
        return ConnectedMcp(repository, oauth, manager, config.id, vault)
    }
    private fun tool(name: String, title: String, enabled: Boolean = true, read: Boolean = false,
        destructive: Boolean = false, description: String = "First sentence. Full details explain this remote operation independently of permission choices.") =
        McpToolConfig(name, "fixture_$name", description, "{}", enabled,
            McpToolAnnotations(readOnlyHint = read, destructiveHint = destructive, title = title))

    @Test fun connectedGitHubAllActionModesKeepExposureAndCredentialsSeparate() {
        val fixture = connectedMcp("github", listOf(tool("get_me", "Read GitHub profile", read = true),
            tool("issue_write", "Create or update repository issue")))
        val credentials = fixture.vault.values.toMap()
        show { RemoteServicesSection(fixture.repository, fixture.manager, fixture.oauth, selectedId = "github") }
        compose.onNodeWithTag("remote-policy-get_me-row").assertDoesNotExist()
        compose.onNodeWithTag("remote-policy-issue_write-row").assertDoesNotExist()
        compose.onNodeWithTag("remote-exposure-get_me-switch").assertExists()
        compose.onNodeWithTag("remote-exposure-issue_write-switch").assertExists()

        show {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("developer-settings")) {
                RemoteMcpPolicySettings(fixture.repository, fixture.manager)
            }
        }
        val read = "remote-policy-get_me"
        val write = "remote-policy-issue_write"
        compose.onNodeWithTag("developer-mcp-${fixture.serverId}").assertExists()
        compose.onNodeWithTag("remote-exposure-get_me-switch").assertDoesNotExist()
        compose.onNodeWithText("fixture-token-not-a-real-credential").assertDoesNotExist()
        geometry(read); geometry(write)
        capture("github-connected-read-write")
        compose.onNodeWithTag("$write-control").performScrollTo().performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_allow)) and hasAnyAncestor(isPopup())).assertExists()
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_ask)) and hasAnyAncestor(isPopup())).assertExists()
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_deny)) and hasAnyAncestor(isPopup())).assertExists()
        capture("github-write-menu-all-modes")
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_ask)) and hasAnyAncestor(isPopup())).performClick()
        assertEquals(1, store.changes.size)
        pick("$write-control", R.string.remote_service_policy_allow)
        assertPolicy("$write-control", R.string.remote_service_policy_allow)
        pick("$write-control", R.string.remote_service_policy_deny)
        assertPolicy("$write-control", R.string.remote_service_policy_deny)
        compose.onNodeWithTag("$read-control").performScrollTo().performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_allow)) and hasAnyAncestor(isPopup())).assertExists()
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_ask)) and hasAnyAncestor(isPopup())).assertExists()
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_deny)) and hasAnyAncestor(isPopup())).assertExists()
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_allow)) and hasAnyAncestor(isPopup())).performClick()
        assertPolicy("$read-control", R.string.remote_service_policy_allow)
        pick("$read-control", R.string.remote_service_policy_ask)
        assertPolicy("$read-control", R.string.remote_service_policy_ask)
        pick("$read-control", R.string.remote_service_policy_deny)
        assertTrue(fixture.repository.get(fixture.serverId)!!.tools.first { it.wireName == "get_me" }.enabled)
        assertPolicy("$read-control", R.string.remote_service_policy_deny)

        show { RemoteServicesSection(fixture.repository, fixture.manager, fixture.oauth, selectedId = "github") }
        compose.onNodeWithTag("remote-policy-get_me-row").assertDoesNotExist()
        compose.onNodeWithTag("remote-policy-issue_write-row").assertDoesNotExist()
        compose.onNodeWithTag("remote-exposure-get_me-switch").assertExists()
        compose.onNodeWithTag("remote-exposure-get_me-switch").performScrollTo().performClick()
        assertFalse(fixture.repository.get(fixture.serverId)!!.tools.first { it.wireName == "get_me" }.enabled)
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_enable_all_reads)).performScrollTo().performClick()
        assertTrue(fixture.repository.get(fixture.serverId)!!.tools.first { it.wireName == "get_me" }.enabled)
        compose.onNodeWithText(compose.activity.getString(R.string.mcp_disable_all_reads)).performScrollTo().performClick()
        assertFalse(fixture.repository.get(fixture.serverId)!!.tools.first { it.wireName == "get_me" }.enabled)
        assertTrue(fixture.repository.get(fixture.serverId)!!.tools.first { it.wireName == "issue_write" }.enabled)
        assertEquals(listOf(
            Triple("mcp:${fixture.serverId}", "issue_write", AutonomyPolicy.ASK),
            Triple("mcp:${fixture.serverId}", "issue_write", AutonomyPolicy.ALLOW),
            Triple("mcp:${fixture.serverId}", "issue_write", AutonomyPolicy.DENY),
            Triple("mcp:${fixture.serverId}", "get_me", AutonomyPolicy.ALLOW),
            Triple("mcp:${fixture.serverId}", "get_me", AutonomyPolicy.ASK),
            Triple("mcp:${fixture.serverId}", "get_me", AutonomyPolicy.DENY),
        ), store.changes)
        assertEquals(credentials, fixture.vault.values)
        assertEquals(1, sessions.get()); assertEquals(0, dispatches.get())
    }

    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun developerCatalogMcpLongLabelOffersEveryModeForDestructiveTools() {
        val fixture = connectedMcp("notion", listOf(tool("update_page", "Actualizar una página con un título suficientemente largo"),
            tool("delete_page", "Eliminar una página", destructive = true)))
        show(2f, true) {
            RemoteServicesSection(fixture.repository, fixture.manager, fixture.oauth, selectedId = "notion")
        }
        compose.onNodeWithTag("remote-policy-update_page-row").assertDoesNotExist()
        compose.onNodeWithTag("remote-policy-delete_page-row").assertDoesNotExist()
        show(2f, true) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                RemoteMcpPolicySettings(fixture.repository, fixture.manager)
            }
        }
        geometry("remote-policy-update_page"); geometry("remote-policy-delete_page")
        capture("catalog-mcp-es-dark-large")
        pick("remote-policy-update_page-control", R.string.remote_service_policy_allow)
        assertPolicy("remote-policy-update_page-control", R.string.remote_service_policy_allow)
        compose.onNodeWithTag("remote-policy-delete_page-control").performScrollTo().performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_allow)) and hasAnyAncestor(isPopup())).assertExists()
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_ask)) and hasAnyAncestor(isPopup())).assertExists()
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_deny)) and hasAnyAncestor(isPopup())).assertExists()
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_ask)) and hasAnyAncestor(isPopup())).performClick()
        assertEquals(listOf(AutonomyPolicy.ALLOW, AutonomyPolicy.ASK), store.changes.map { it.third })
        assertEquals(1, sessions.get()); assertEquals(0, dispatches.get()); assertTrue(fixture.vault.values.isEmpty())
    }

    @Test fun connectedCustomMcpDescriptionAndPolicyClicksStayIndependentAndDispatchOnce() {
        val fixture = connectedMcp(null, listOf(tool("update_record", "Update record"),
            tool("delete_record", "Delete record", destructive = true)))
        val enabledChanges = mutableListOf<Pair<String, Boolean>>()
        show {
            val servers by fixture.repository.servers.collectAsState()
            val states by fixture.manager.states.collectAsState()
            McpServerDetailPage(servers.single(), states.getValue(fixture.serverId), fixture.manager.writeApproval,
                fixture.repository, {}, {}, {}, {}, {}, {}, onToolEnabled = { name, enabled ->
                    enabledChanges += name to enabled
                    fixture.repository.updateToolEnabled(fixture.serverId, name, enabled)
                })
        }
        val full = McpToolPresentation.description(fixture.repository.get(fixture.serverId)!!.tools.first().description).fullText
        compose.onNodeWithText(full).assertDoesNotExist()
        compose.onNodeWithText("Update record").performScrollTo().performClick()
        compose.onNodeWithText(full).assertExists()
        assertTrue(store.changes.isEmpty()); assertTrue(enabledChanges.isEmpty())
        geometry("mcp-policy-update_record")
        capture("custom-mcp-expanded-description")
        compose.onNodeWithTag("mcp-policy-update_record-control").performScrollTo().performClick()
        compose.onNodeWithText(full).assertExists()
        capture("custom-mcp-expanded-policy-popup")
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_deny)) and hasAnyAncestor(isPopup())).performClick()
        assertTrue(enabledChanges.isEmpty())
        assertEquals(listOf(AutonomyPolicy.DENY), store.changes.map { it.third })
        compose.onNodeWithText(full).assertExists()
        pick("mcp-policy-update_record-control", R.string.remote_service_policy_ask)
        pick("mcp-policy-update_record-control", R.string.remote_service_policy_allow)
        assertTrue(enabledChanges.isEmpty())
        compose.onNodeWithTag("mcp-exposure-update_record-switch").performScrollTo().performClick()
        assertEquals(listOf("update_record" to false), enabledChanges)
        assertEquals(listOf(AutonomyPolicy.DENY, AutonomyPolicy.ASK, AutonomyPolicy.ALLOW), store.changes.map { it.third })
        compose.onNodeWithText("Update record").performScrollTo().performClick()
        compose.onNodeWithText(full).assertDoesNotExist()
        compose.onNodeWithTag("mcp-policy-delete_record-control").performScrollTo().performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.remote_service_policy_allow)) and hasAnyAncestor(isPopup())).assertExists()
        assertEquals(1, enabledChanges.size); assertEquals(0, dispatches.get())
    }

    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-port-mdpi")
    fun connectedCustomMcpDetailScreenUsesActualConnectedStateAtLargeText() {
        val fixture = connectedMcp(null, listOf(tool("update_record", "Actualizar registro de proyecto con descripción extensa")))
        show(2f, true) {
            McpServerDetailScreen(fixture.repository, fixture.manager, fixture.oauth, fixture.serverId,
                {}, { error("No deletion requested") }, { error("Fixture must remain present") })
        }
        compose.onNodeWithTag("mcp-server-detail").assertIsDisplayed()
        geometry("mcp-policy-update_record")
        capture("custom-mcp-es-dark-large")
        pick("mcp-policy-update_record-control", R.string.remote_service_policy_deny)
        assertPolicy("mcp-policy-update_record-control", R.string.remote_service_policy_deny)
        assertEquals(1, store.changes.size); assertEquals(0, dispatches.get())
    }
}
