package com.jarvys.agent

import android.content.Context
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner

enum class AppRouteAction { NONE, CHAT, MCP_ADD, SKILL_IMPORT, HOME_FALLBACK }

data class AppRouteMeta(
    val title: String,
    val isRoot: Boolean,
    val showTopBar: Boolean = true,
    val action: AppRouteAction = AppRouteAction.NONE,
)

/** Route names are the single source used by MainActivity's graph and back/title/action metadata. */
object AppNavigationBackPolicy {
    const val CHAT_ROOT = "chat"
    const val SETTINGS = "settings"
    const val SETTINGS_PREFERENCES = "settings/preferences"
    const val ARCHIVED_CHATS = "settings/archived-chats"
    const val SCHEDULED_TASKS = "scheduled-tasks"
    const val PROVIDERS = "providers"
    const val PROVIDERS_OPENAI = "providers/openai"
    const val PROVIDERS_OPENROUTER = "providers/openrouter"
    const val PROVIDERS_CUSTOM = "providers/custom"
    const val PROVIDERS_SERVICE = "providers/service/{serviceId}"
    const val MCP_LIST = "mcp"
    const val MCP_NEW = "mcp/new"
    const val MCP_SERVER = "mcp/server/{serverId}"
    const val MCP_SERVER_EDIT = "mcp/server/{serverId}/edit"
    const val SKILLS = "skills"
    const val CONNECTORS = "connectors"
    const val CONNECTOR_DEVICE = "connectors/device/{connectorId}"
    const val CONNECTOR_REMOTE = "connectors/remote/{serviceId}?title={title}"
    const val CONNECTOR_GOOGLE = "connectors/google/{serviceId}?title={title}"
    const val MEMORY = "memory"
    const val TASKS = "tasks"
    const val TASK_DETAIL = "tasks/detail/{taskId}"
    const val MEMORY_HISTORY = "memory/history"
    const val MEMORY_FILE = "memory/file?path={path}&new={new}"
    const val BOTS = "bots"
    const val CREW_EMPTY = "crew"
    const val CREW = "crew/{missionId}"
    const val CREW_BOT = "crew/{missionId}/bot/{botId}"
    const val WORKSPACE_PREVIEW = "workspace-preview/{projectId}"
    fun mcpServer(serverId: String) = "mcp/server/${android.net.Uri.encode(serverId)}"
    fun editMcpServer(serverId: String) = "${mcpServer(serverId)}/edit"
    fun connectorDevice(id: String) = "connectors/device/${android.net.Uri.encode(id)}"
    fun connectorRemote(id: String, title: String) =
        "connectors/remote/${android.net.Uri.encode(id)}?title=${android.net.Uri.encode(title)}"
    fun connectorGoogle(id: String, title: String) =
        "connectors/google/${android.net.Uri.encode(id)}?title=${android.net.Uri.encode(title)}"
    fun memoryFile(path: String, isNew: Boolean) =
        "memory/file?path=${android.net.Uri.encode(path)}&new=$isNew"
    fun providerService(serviceId: String) = "providers/service/${android.net.Uri.encode(serviceId)}"

    val registeredRoutePatterns = listOf(
        CHAT_ROOT, SETTINGS, SETTINGS_PREFERENCES, ARCHIVED_CHATS, SCHEDULED_TASKS,
        PROVIDERS, PROVIDERS_OPENAI, PROVIDERS_OPENROUTER, PROVIDERS_CUSTOM, PROVIDERS_SERVICE,
        MCP_LIST, MCP_NEW, MCP_SERVER, MCP_SERVER_EDIT, SKILLS, CONNECTORS,
        CONNECTOR_DEVICE, CONNECTOR_REMOTE, CONNECTOR_GOOGLE, MEMORY, MEMORY_HISTORY, MEMORY_FILE,
        TASKS, TASK_DETAIL,
        BOTS, CREW_EMPTY, CREW, CREW_BOT, WORKSPACE_PREVIEW,
    )
    val rootRoutes = setOf(CHAT_ROOT)

    fun requiresBack(route: String): Boolean = route !in rootRoutes

    fun metadata(context: Context, route: String, chatTitle: String, crewBotTitle: String?,
                 detailTitle: String?): AppRouteMeta = when (route) {
        CHAT_ROOT -> AppRouteMeta(chatTitle, isRoot = true, action = AppRouteAction.CHAT)
        ARCHIVED_CHATS -> AppRouteMeta(context.getString(R.string.drawer_archived_chats), false)
        SCHEDULED_TASKS -> AppRouteMeta(context.getString(R.string.drawer_scheduled_tasks), false)
        SETTINGS -> AppRouteMeta(context.getString(R.string.drawer_settings), false)
        SETTINGS_PREFERENCES -> AppRouteMeta(context.getString(R.string.settings_preferences), false)
        PROVIDERS -> AppRouteMeta(context.getString(R.string.settings_providers), false)
        PROVIDERS_OPENAI -> AppRouteMeta(context.getString(R.string.provider_openai_name), false)
        PROVIDERS_OPENROUTER -> AppRouteMeta(context.getString(R.string.provider_openrouter_name), false)
        PROVIDERS_CUSTOM -> AppRouteMeta(detailTitle ?: context.getString(R.string.provider_custom_endpoint_name), false)
        PROVIDERS_SERVICE -> AppRouteMeta(detailTitle ?: context.getString(R.string.providers_services_heading), false)
        MCP_LIST -> AppRouteMeta(context.getString(R.string.mcp_screen_title), false, action = AppRouteAction.MCP_ADD)
        MCP_NEW -> AppRouteMeta(context.getString(R.string.mcp_server_add_title), false)
        MCP_SERVER -> AppRouteMeta(detailTitle ?: context.getString(R.string.mcp_screen_title), false)
        MCP_SERVER_EDIT -> AppRouteMeta(context.getString(R.string.mcp_server_edit_title,
            detailTitle ?: context.getString(R.string.mcp_screen_title)), false)
        SKILLS -> AppRouteMeta(context.getString(R.string.settings_skills), false, action = AppRouteAction.SKILL_IMPORT)
        CONNECTORS -> AppRouteMeta(context.getString(R.string.connectors_title), false)
        CONNECTOR_DEVICE, CONNECTOR_REMOTE, CONNECTOR_GOOGLE ->
            AppRouteMeta(detailTitle ?: context.getString(R.string.connectors_title), false)
        MEMORY -> AppRouteMeta(context.getString(R.string.memory_title), false)
        MEMORY_HISTORY -> AppRouteMeta(context.getString(R.string.memory_history), false)
        MEMORY_FILE -> AppRouteMeta(context.getString(R.string.memory_title), false)
        TASKS -> AppRouteMeta(context.getString(R.string.tasks_title), false)
        TASK_DETAIL -> AppRouteMeta(detailTitle ?: context.getString(R.string.task_detail_title), false)
        BOTS -> AppRouteMeta(context.getString(R.string.drawer_bots), false, showTopBar = false)
        CREW_EMPTY, CREW -> AppRouteMeta(context.getString(R.string.crew_title), false)
        CREW_BOT -> AppRouteMeta(crewBotTitle ?: context.getString(R.string.crew_bot_title), false)
        WORKSPACE_PREVIEW -> AppRouteMeta(context.getString(R.string.chat_open_preview), false)
        else -> AppRouteMeta(context.getString(R.string.drawer_chat_fallback), false,
            action = AppRouteAction.HOME_FALLBACK)
    }
}

@Composable
fun JarvysBackNavigationButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.drawer_back)
    IconButton(
        onClick = onBack,
        modifier = modifier.size(48.dp).testTag("jarvys-back").semantics { contentDescription = description },
    ) {
        Icon(LucideIcons.ArrowLeft, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun AppRouteBackButton(onFallback: () -> Unit, modifier: Modifier = Modifier) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    JarvysBackNavigationButton(
        onBack = { if (dispatcher != null) dispatcher.onBackPressed() else onFallback() },
        modifier = modifier,
    )
}
