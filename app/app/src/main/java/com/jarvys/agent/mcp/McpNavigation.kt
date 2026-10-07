package com.jarvys.agent.mcp

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.jarvys.agent.AppNavigationBackPolicy

/** The MCP destinations used by MainActivity and navigation tests share the exact same route graph. */
fun NavGraphBuilder.mcpDestinations(
    navController: NavController,
    repository: McpServerRepository,
    connectionManager: McpConnectionManager,
    oauthManager: McpOAuthManager,
) {
    composable(AppNavigationBackPolicy.MCP_LIST) {
        McpServersScreen(repository, connectionManager) { serverId ->
            navController.navigate(AppNavigationBackPolicy.mcpServer(serverId))
        }
    }
    composable(AppNavigationBackPolicy.MCP_NEW) {
        McpServerEditorScreen(
            initial = null,
            repository = repository,
            onNavigateBack = { navController.popBackStack() },
            onSave = { config, bearer -> persistServer(repository, connectionManager, oauthManager, null, config, bearer) },
            onSaved = { navController.popBackStack() },
        )
    }
    composable(AppNavigationBackPolicy.MCP_SERVER,
        arguments = listOf(navArgument("serverId") { type = NavType.StringType })) { entry ->
        val id = entry.arguments?.getString("serverId").orEmpty()
        McpServerDetailScreen(
            repository = repository,
            connectionManager = connectionManager,
            oauthManager = oauthManager,
            serverId = id,
            onEdit = { navController.navigate(AppNavigationBackPolicy.editMcpServer(it)) },
            onDeleted = { navController.popBackStack() },
            onMissingServer = { returnToMcpList(navController) },
        )
    }
    composable(AppNavigationBackPolicy.MCP_SERVER_EDIT,
        arguments = listOf(navArgument("serverId") { type = NavType.StringType })) { entry ->
        val id = entry.arguments?.getString("serverId").orEmpty()
        val servers by repository.servers.collectAsState()
        val initial = servers.firstOrNull { it.id == id && it.catalogServiceId == null }
        if (initial == null) {
            LaunchedEffect(id, servers) { returnToMcpList(navController) }
        } else {
            McpServerEditorScreen(
                initial = initial,
                repository = repository,
                onNavigateBack = { navController.popBackStack() },
                onSave = { config, bearer -> persistServer(repository, connectionManager, oauthManager, initial, config, bearer) },
                onSaved = { navController.popBackStack() },
            )
        }
    }
}

private fun returnToMcpList(navController: NavController) {
    if (!navController.popBackStack(AppNavigationBackPolicy.MCP_LIST, false)) {
        navController.navigate(AppNavigationBackPolicy.MCP_LIST) { launchSingleTop = true }
    }
}

private fun persistServer(
    repository: McpServerRepository,
    connectionManager: McpConnectionManager,
    oauthManager: McpOAuthManager,
    old: McpServerConfig?,
    config: McpServerConfig,
    bearerToken: String,
) {
    repository.upsert(config)
    if (config.authMode == McpAuthMode.BEARER) {
        if (bearerToken.isNotBlank()) repository.saveBearerToken(config.id, config.endpoint, bearerToken)
        if (old?.authMode == McpAuthMode.OAUTH) oauthManager.clear(config.id)
    } else {
        repository.clearBearerToken(config.id)
        if (config.authMode != McpAuthMode.OAUTH) oauthManager.clear(config.id)
    }
    if (config.enabled) connectionManager.connect(config.id) else connectionManager.disconnect(config.id)
}
