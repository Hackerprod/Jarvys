package com.jarvys.agent.mcp

import androidx.compose.runtime.Composable
import com.jarvys.agent.ui.mcp.McpServerDetailScreen as RenderServerDetail
import com.jarvys.agent.ui.mcp.McpServerEditorScreen as RenderServerEditor
import com.jarvys.agent.ui.mcp.McpServersScreen as RenderServerIndex

@Composable
fun McpServersScreen(repository: McpServerRepository, connectionManager: McpConnectionManager,
                     onOpenServer: (String) -> Unit) = RenderServerIndex(repository, connectionManager, onOpenServer)

@Composable
fun McpServerDetailScreen(
    repository: McpServerRepository,
    connectionManager: McpConnectionManager,
    oauthManager: McpOAuthManager,
    serverId: String,
    onEdit: (String) -> Unit,
    onDeleted: () -> Unit,
    onMissingServer: () -> Unit,
) = RenderServerDetail(repository, connectionManager, oauthManager, serverId, onEdit, onDeleted, onMissingServer)

@Composable
fun McpServerEditorScreen(
    initial: McpServerConfig?,
    repository: McpServerRepository,
    onNavigateBack: () -> Unit,
    onSave: (McpServerConfig, String) -> Unit,
    onSaved: () -> Unit,
) = RenderServerEditor(initial, repository, onNavigateBack, onSave, onSaved)
