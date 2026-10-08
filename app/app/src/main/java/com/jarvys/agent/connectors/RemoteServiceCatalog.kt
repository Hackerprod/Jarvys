package com.jarvys.agent.connectors

import com.jarvys.agent.R
import com.jarvys.agent.mcp.McpTransport

enum class RemoteServiceAuthMode { OAUTH_DCR, OAUTH_PREREGISTERED, PAT }
enum class RemoteServiceRisk { LOW, MEDIUM, HIGH }

data class RemoteServiceDefinition(
    val id: String,
    val mcpServerId: String,
    val nameResourceId: Int,
    val endpoint: String,
    val transport: McpTransport,
    val authMode: RemoteServiceAuthMode,
    val brandIconId: String,
    val patInstructionsResourceId: Int = 0,
    val patCreationUrl: String = "",
    val scopesNoteResourceId: Int,
    val verifiedReadToolNames: Set<String>? = emptySet(),
    val risk: RemoteServiceRisk,
    val usageNoteResourceId: Int,
    val notConnectedNoteResourceId: Int,
    val documentationUrl: String,
    /** Exact least-privilege OAuth scopes to send; null preserves MCP server discovery behavior. */
    val requestedScopes: Set<String>? = null,
    /** Verified same-origin AS discovery fallback for providers without Protected Resource Metadata. */
    val authorizationServerMetadataUrl: String? = null,
)

/** Only integrations with an evidence-backed client auth path are listed here. */
object RemoteServiceCatalog {
    const val MAX_DEFAULT_READ_TOOLS_PER_SERVER = 40

    val services: List<RemoteServiceDefinition> = listOf(
        RemoteServiceDefinition(
            id = "github",
            mcpServerId = "catalog_github",
            nameResourceId = R.string.remote_service_github,
            endpoint = "https://api.githubcopilot.com/mcp/",
            transport = McpTransport.STREAMABLE_HTTP,
            authMode = RemoteServiceAuthMode.PAT,
            brandIconId = "github",
            patInstructionsResourceId = R.string.remote_service_pat_instructions,
            patCreationUrl = "https://github.com/settings/personal-access-tokens/new",
            scopesNoteResourceId = R.string.remote_service_pat_scopes,
            verifiedReadToolNames = GitHubOperationPolicy.readNames,
            risk = RemoteServiceRisk.HIGH,
            usageNoteResourceId = R.string.remote_service_github_usage_note,
            notConnectedNoteResourceId = R.string.remote_service_not_connected_note,
            documentationUrl = "https://docs.github.com/en/copilot/how-tos/provide-context/use-mcp-in-your-ide/set-up-the-github-mcp-server",
        ),
        RemoteServiceDefinition(
            id = "notion", mcpServerId = "catalog_notion", nameResourceId = R.string.remote_service_notion,
            endpoint = "https://mcp.notion.com/mcp", transport = McpTransport.AUTO,
            authMode = RemoteServiceAuthMode.OAUTH_DCR, brandIconId = "notion",
            scopesNoteResourceId = R.string.remote_service_notion_scopes,
            risk = RemoteServiceRisk.MEDIUM,
            usageNoteResourceId = R.string.remote_service_notion_usage_note,
            notConnectedNoteResourceId = R.string.remote_service_not_connected_note,
            documentationUrl = "https://developers.notion.com/docs/mcp", requestedScopes = setOf("default"),
        ),
        RemoteServiceDefinition(
            id = "linear", mcpServerId = "catalog_linear", nameResourceId = R.string.remote_service_linear,
            endpoint = "https://mcp.linear.app/mcp", transport = McpTransport.AUTO,
            authMode = RemoteServiceAuthMode.OAUTH_DCR, brandIconId = "linear",
            scopesNoteResourceId = R.string.remote_service_linear_scopes,
            risk = RemoteServiceRisk.MEDIUM,
            usageNoteResourceId = R.string.remote_service_linear_usage_note,
            notConnectedNoteResourceId = R.string.remote_service_not_connected_note,
            documentationUrl = "https://linear.app/developers/mcp", requestedScopes = setOf("read"),
        ),
        RemoteServiceDefinition(
            id = "atlassian", mcpServerId = "catalog_atlassian", nameResourceId = R.string.remote_service_atlassian,
            endpoint = "https://mcp.atlassian.com/v1/mcp", transport = McpTransport.AUTO,
            authMode = RemoteServiceAuthMode.OAUTH_DCR, brandIconId = "atlassian",
            scopesNoteResourceId = R.string.remote_service_atlassian_scopes,
            risk = RemoteServiceRisk.HIGH,
            usageNoteResourceId = R.string.remote_service_atlassian_usage_note,
            notConnectedNoteResourceId = R.string.remote_service_not_connected_note,
            documentationUrl = "https://developer.atlassian.com/platform/remote-mcp-server/",
            authorizationServerMetadataUrl = "https://mcp.atlassian.com/.well-known/oauth-authorization-server",
        ),
        RemoteServiceDefinition(
            id = "asana", mcpServerId = "catalog_asana", nameResourceId = R.string.remote_service_asana,
            endpoint = "https://mcp.asana.com/sse", transport = McpTransport.AUTO,
            authMode = RemoteServiceAuthMode.OAUTH_DCR, brandIconId = "asana",
            scopesNoteResourceId = R.string.remote_service_asana_scopes,
            risk = RemoteServiceRisk.MEDIUM,
            usageNoteResourceId = R.string.remote_service_asana_usage_note,
            notConnectedNoteResourceId = R.string.remote_service_not_connected_note,
            documentationUrl = "https://developers.asana.com/docs/using-asanas-mcp-server", requestedScopes = setOf("default"),
        ),
        RemoteServiceDefinition(
            id = "sentry", mcpServerId = "catalog_sentry", nameResourceId = R.string.remote_service_sentry,
            endpoint = "https://mcp.sentry.dev/mcp", transport = McpTransport.AUTO,
            authMode = RemoteServiceAuthMode.OAUTH_DCR, brandIconId = "sentry",
            scopesNoteResourceId = R.string.remote_service_sentry_scopes,
            risk = RemoteServiceRisk.HIGH,
            usageNoteResourceId = R.string.remote_service_sentry_usage_note,
            notConnectedNoteResourceId = R.string.remote_service_not_connected_note,
            documentationUrl = "https://docs.sentry.io/product/sentry-mcp/", requestedScopes = setOf("org:read"),
        ),
        RemoteServiceDefinition(
            id = "vercel", mcpServerId = "catalog_vercel", nameResourceId = R.string.remote_service_vercel,
            endpoint = "https://mcp.vercel.com", transport = McpTransport.AUTO,
            authMode = RemoteServiceAuthMode.OAUTH_DCR, brandIconId = "vercel",
            scopesNoteResourceId = R.string.remote_service_vercel_scopes,
            risk = RemoteServiceRisk.HIGH,
            usageNoteResourceId = R.string.remote_service_vercel_usage_note,
            notConnectedNoteResourceId = R.string.remote_service_not_connected_note,
            documentationUrl = "https://vercel.com/docs/mcp/vercel-mcp", requestedScopes = setOf("openid"),
        ),
        RemoteServiceDefinition(
            id = "canva", mcpServerId = "catalog_canva", nameResourceId = R.string.remote_service_canva,
            endpoint = "https://mcp.canva.com/mcp", transport = McpTransport.AUTO,
            authMode = RemoteServiceAuthMode.OAUTH_DCR, brandIconId = "canva",
            scopesNoteResourceId = R.string.remote_service_canva_scopes,
            risk = RemoteServiceRisk.MEDIUM,
            usageNoteResourceId = R.string.remote_service_canva_usage_note,
            notConnectedNoteResourceId = R.string.remote_service_not_connected_note,
            documentationUrl = "https://www.canva.dev/docs/connect/",
            requestedScopes = setOf("profile:read", "design:read", "folder:read", "brandtemplate:read",
                "comment:read", "asset:read", "brandkit:read", "help:read"),
        ),
    )

    fun find(id: String?): RemoteServiceDefinition? = services.firstOrNull { it.id == id }
}
