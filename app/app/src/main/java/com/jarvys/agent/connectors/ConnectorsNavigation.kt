package com.jarvys.agent.connectors

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.jarvys.agent.AppNavigationBackPolicy

/** Shared real Connector routes used by MainActivity and the Robolectric route graph tests. */
fun NavGraphBuilder.connectorsDestinations(
    navController: NavController,
    registry: ConnectorRegistry,
    remoteServicesOverride: (@androidx.compose.runtime.Composable (String?, (String, String) -> Unit) -> Unit)? = null,
    googleServicesOverride: (@androidx.compose.runtime.Composable (String?, (String, String) -> Unit) -> Unit)? = null,
) {
    composable(AppNavigationBackPolicy.CONNECTORS) {
        ConnectorsScreen(
            registry = registry,
            remoteServicesOverride = remoteServicesOverride,
            googleServicesOverride = googleServicesOverride,
            onOpenDevice = { id -> navController.navigate(AppNavigationBackPolicy.connectorDevice(id)) },
            onOpenRemote = { id, title -> navController.navigate(AppNavigationBackPolicy.connectorRemote(id, title)) },
            onOpenGoogle = { id, title -> navController.navigate(AppNavigationBackPolicy.connectorGoogle(id, title)) },
        )
    }
    composable(AppNavigationBackPolicy.CONNECTOR_DEVICE,
        arguments = listOf(navArgument("connectorId") { type = NavType.StringType })) { entry ->
        ConnectorsDeviceDetailScreen(
            registry = registry,
            connectorId = entry.arguments?.getString("connectorId").orEmpty(),
            onDisconnected = { navController.popBackStack() },
            onMissingConnector = { returnToConnectorsList(navController) },
        )
    }
    composable(AppNavigationBackPolicy.CONNECTOR_REMOTE,
        arguments = listOf(navArgument("serviceId") { type = NavType.StringType },
            navArgument("title") { type = NavType.StringType; defaultValue = "" })) { entry ->
        ConnectorsRemoteServiceScreen(
            serviceId = entry.arguments?.getString("serviceId").orEmpty(),
            override = remoteServicesOverride,
            onSelect = { id, title -> navController.navigate(AppNavigationBackPolicy.connectorRemote(id, title)) },
            onMissing = { returnToConnectorsList(navController) },
            onDetailExit = { navController.popBackStack() },
        )
    }
    composable(AppNavigationBackPolicy.CONNECTOR_GOOGLE,
        arguments = listOf(navArgument("serviceId") { type = NavType.StringType },
            navArgument("title") { type = NavType.StringType; defaultValue = "" })) { entry ->
        ConnectorsGoogleServiceScreen(
            serviceId = entry.arguments?.getString("serviceId").orEmpty(),
            override = googleServicesOverride,
            onSelect = { id, title -> navController.navigate(AppNavigationBackPolicy.connectorGoogle(id, title)) },
            onMissing = { returnToConnectorsList(navController) },
            onDetailExit = { navController.popBackStack() },
        )
    }
}

private fun returnToConnectorsList(navController: NavController) {
    if (!navController.popBackStack(AppNavigationBackPolicy.CONNECTORS, false)) {
        navController.navigate(AppNavigationBackPolicy.CONNECTORS) { launchSingleTop = true }
    }
}
