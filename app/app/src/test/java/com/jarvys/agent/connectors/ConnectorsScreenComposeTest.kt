package com.jarvys.agent.connectors

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.AppNavigationBackPolicy
import com.jarvys.agent.AppRouteBackButton
import com.jarvys.agent.JarvysTopAppBar
import com.jarvys.agent.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectorsScreenComposeTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val navigation = AtomicReference<NavHostController>()

    private class MemoryConnectionPreferences : ConnectorConnectionPreferences {
        private val values = mutableMapOf<String, Boolean>()
        override fun isConnected(id: String) = values[id] == true
        override fun setConnected(id: String, connected: Boolean) { values[id] = connected }
    }

    private fun registry(context: Context, connectedId: String? = null): ConnectorRegistry {
        val stateStore = MemoryConnectionPreferences()
        connectedId?.let { stateStore.setConnected(it, true) }
        return ConnectorRegistry.createForTests(stateStore, { true }).also {
            it.registerBuiltInDefinitions(ConnectorRegistry.deviceDefinitions(context))
        }
    }

    private fun showNavigation(
        registry: ConnectorRegistry,
        remote: (@Composable (String?, (String, String) -> Unit) -> Unit)? = null,
        google: (@Composable (String?, (String, String) -> Unit) -> Unit)? = null,
    ) {
        compose.setContent {
            val context = LocalContext.current
            MaterialTheme {
                val nav = rememberNavController()
                navigation.set(nav)
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route ?: AppNavigationBackPolicy.CONNECTORS
                val definitionId = entry?.arguments?.getString("connectorId")
                val serviceTitle = entry?.arguments?.getString("title")
                val title = definitionId?.let { id -> registry.definitions.value.firstOrNull { it.id == id } }
                    ?.let { if (it.displayNameResourceId != 0) context.getString(it.displayNameResourceId) else it.name }
                    ?: serviceTitle
                val meta = AppNavigationBackPolicy.metadata(context, route, "Chat", null, title)
                Scaffold(topBar = {
                    JarvysTopAppBar(
                        title = { Text(meta.title, modifier = Modifier.testTag("connectors-route-title")) },
                        navigationIcon = { AppRouteBackButton(onFallback = { nav.popBackStack() }) },
                    )
                }) { padding ->
                    NavHost(nav, startDestination = AppNavigationBackPolicy.CONNECTORS, modifier = Modifier.padding(padding)) {
                        connectorsDestinations(nav, registry, remote, google)
                        composable(AppNavigationBackPolicy.CHAT_ROOT) {
                            Text("Chat", modifier = Modifier.testTag("connectors-test-chat"))
                        }
                        composable(AppNavigationBackPolicy.SETTINGS) {
                            Text("Settings", modifier = Modifier.testTag("connectors-test-settings"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun everyDeviceConnectorRouteReturnsToListWithArrowAndSystemBack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = registry(context)
        showNavigation(registry, remote = { _, _ -> }, google = { _, _ -> })
        val definitions = registry.definitions.value.filter { it.presentationGroup == ConnectorPresentationGroup.ON_DEVICE }
        definitions.forEachIndexed { index, definition ->
            val title = if (definition.displayNameResourceId != 0) context.getString(definition.displayNameResourceId) else definition.name
            compose.onNodeWithText(title, useUnmergedTree = true).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("connectors-detail-screen").assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.drawer_back)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("connectors-list-screen").assertIsDisplayed()
            if (index == 0) {
                compose.onNodeWithText(title, useUnmergedTree = true).performScrollTo().performClick()
                compose.waitForIdle()
                compose.activity.onBackPressedDispatcher.onBackPressed()
                compose.waitForIdle()
                compose.onNodeWithTag("connectors-list-screen").assertIsDisplayed()
            }
        }
    }

    @Test fun everyRemoteAndGoogleDetailRouteUsesNavBackToReturnToTheConnectorList() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = registry(context)
        val remote: @Composable (String?, (String, String) -> Unit) -> Unit = { selected, select ->
            FakeRemoteServiceContent(selected, select)
        }
        showNavigation(registry, remote = remote, google = { selected, select ->
            FakeGoogleServicesContent(selected, select)
        })
        RemoteServiceCatalog.services.forEach { service ->
            val title = context.getString(service.nameResourceId)
            compose.onNodeWithText(title, useUnmergedTree = true).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("connectors-remote-detail").assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.drawer_back)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("connectors-list-screen").assertIsDisplayed()
        }
        listOf("gmail" to "Gmail test", "drive" to "Drive test").forEach { (id, title) ->
            compose.onNodeWithText(title, useUnmergedTree = true).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("connectors-google-detail").assertIsDisplayed()
            assertEquals(AppNavigationBackPolicy.CONNECTOR_GOOGLE, navigation.get().currentDestination?.route)
            assertEquals(id, navigation.get().currentBackStackEntry?.arguments?.getString("serviceId"))
            assertEquals(title, navigation.get().currentBackStackEntry?.arguments?.getString("title"))
            compose.onNodeWithContentDescription(context.getString(R.string.drawer_back)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("connectors-list-screen").assertIsDisplayed()
        }
    }

    @Test fun aMissingDeviceConnectorReturnsToListAndReenteringAfterChatStartsAtList() {
        val registry = registry(ApplicationProvider.getApplicationContext())
        showNavigation(registry, remote = { _, _ -> }, google = { _, _ -> })
        navigation.get().navigate(AppNavigationBackPolicy.connectorDevice("missing-connector"))
        compose.waitForIdle()
        assertEquals(AppNavigationBackPolicy.CONNECTORS, navigation.get().currentDestination?.route)
        compose.onNodeWithTag("connectors-list-screen").assertIsDisplayed()

        val definition = registry.definitions.value.first()
        val title = if (definition.displayNameResourceId != 0)
            ApplicationProvider.getApplicationContext<Context>().getString(definition.displayNameResourceId) else definition.name
        compose.onNodeWithText(title, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        navigation.get().navigate(AppNavigationBackPolicy.CHAT_ROOT)
        navigation.get().navigate(AppNavigationBackPolicy.SETTINGS)
        navigation.get().navigate(AppNavigationBackPolicy.CHAT_ROOT)
        navigation.get().navigate(AppNavigationBackPolicy.SETTINGS)
        navigation.get().navigate(AppNavigationBackPolicy.CONNECTORS)
        compose.waitForIdle()
        compose.onNodeWithTag("connectors-list-screen").assertIsDisplayed()
        compose.onAllNodesWithTag("connectors-detail-screen").assertCountEquals(0)
    }

    @Test fun disconnectingAConnectedDeviceDetailReturnsToList() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = registry(context, EmailIntentConnector.ID)
        showNavigation(registry, remote = { _, _ -> }, google = { _, _ -> })
        val definition = registry.definitions.value.first { it.id == EmailIntentConnector.ID }
        val title = context.getString(definition.displayNameResourceId)
        compose.onNodeWithText(title, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        val disconnect = context.getString(R.string.connector_disconnect)
        compose.onNodeWithText(disconnect).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.connector_disconnect_confirm_title, title)).assertIsDisplayed()
        compose.onAllNodesWithText(disconnect).get(1).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("connectors-list-screen").assertIsDisplayed()
        assertEquals(ConnectorState.DISCONNECTED, registry.state(definition))
    }

    @Test fun permissionAwareConnectRemainsAvailableFromDeviceDetail() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val definitions = ConnectorRegistry.deviceDefinitions(context)
        val definition = definitions.first { it.connectionPermissions.isNotEmpty() }
        shadowOf(context.applicationContext as android.app.Application).grantPermissions(*definition.connectionPermissions.toTypedArray())
        val registry = ConnectorRegistry.createForTests(MemoryConnectionPreferences(), { true }).also {
            it.registerBuiltInDefinitions(definitions)
        }
        showNavigation(registry, remote = { _, _ -> }, google = { _, _ -> })
        val title = context.getString(definition.displayNameResourceId)
        compose.onNodeWithText(title, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        val connect = context.getString(if (definition.connectionFlow == ConnectorConnectionFlow.NOTIFICATION_LISTENER_SETTINGS)
            R.string.connector_reconnect else R.string.connector_connect)
        compose.onNodeWithText(connect).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("connectors-detail-screen").assertIsDisplayed()
    }

    @Test fun remoteDetailsRemainBoundedAtFontScaleTwo() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = registry(context)
        val remote: @Composable (String?, (String, String) -> Unit) -> Unit = { selected, select ->
            FakeRemoteServiceContent(selected, select)
        }
        showNavigation(registry, remote = remote, google = { _, _ -> })
        val title = context.getString(RemoteServiceCatalog.services.first().nameResourceId)
        compose.onNodeWithText(title, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("connectors-remote-detail").assertIsDisplayed()
    }

    @Test fun routeMetadataSetsTheSharedTopBarTitleForListDeviceAndRemoteDetails() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = registry(context)
        showNavigation(registry, remote = { selected, select -> FakeRemoteServiceContent(selected, select) },
            google = { _, _ -> })
        compose.onNodeWithTag("connectors-route-title").assertTextEquals(context.getString(R.string.connectors_title))
        val definition = registry.definitions.value.first()
        val deviceTitle = if (definition.displayNameResourceId != 0) context.getString(definition.displayNameResourceId) else definition.name
        compose.onNodeWithText(deviceTitle, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("connectors-route-title").assertTextEquals(deviceTitle)
        compose.onNodeWithTag("jarvys-back").performClick()
        compose.waitForIdle()
        val service = RemoteServiceCatalog.services.first()
        val serviceTitle = context.getString(service.nameResourceId)
        compose.onNodeWithText(serviceTitle, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("connectors-route-title").assertTextEquals(serviceTitle)
    }

    @Composable
    private fun FakeRemoteServiceContent(selectedId: String?, onSelect: (String, String) -> Unit) {
        val context = LocalContext.current
        if (selectedId == null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RemoteServiceCatalog.services.forEach { service ->
                    val title = stringResource(service.nameResourceId)
                    Text(title, Modifier.clickable { onSelect(service.id, title) })
                }
            }
        } else {
            val title = RemoteServiceCatalog.find(selectedId)?.let { context.getString(it.nameResourceId) } ?: selectedId
            ConnectorDetailScaffold(title = title, subtitle = "Disconnected", icon = {}, connected = false,
                summary = "Test detail", primaryActionLabel = "Connect", onPrimaryAction = {},
                disconnectLabel = "Disconnect", disconnectExplanation = "Confirm", onDisconnect = {},
                showIdentityHeader = false, content = { Text("Test tools") })
        }
    }

    @Composable
    private fun FakeGoogleServicesContent(selectedId: String?, onSelect: (String, String) -> Unit) {
        if (selectedId == null) Column {
            listOf("gmail" to "Gmail test", "drive" to "Drive test").forEach { (id, title) ->
                Text(title, Modifier.clickable { onSelect(id, title) })
            }
        } else ConnectorDetailScaffold(title = if (selectedId == "gmail") "Gmail test" else "Drive test",
            subtitle = "Disconnected", icon = {}, connected = false, summary = "Test detail",
            primaryActionLabel = "Connect", onPrimaryAction = {}, disconnectLabel = "Disconnect",
            disconnectExplanation = "Confirm", onDisconnect = {}, showIdentityHeader = false)
    }
}
