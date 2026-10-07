package com.jarvys.agent.mcp

import org.json.JSONObject

enum class McpTransport { AUTO, STREAMABLE_HTTP, LEGACY_SSE }
enum class McpAuthMode { NONE, BEARER, OAUTH }
enum class McpToolSelectionMode { DEFAULT_ALL, ALL, CUSTOM, NONE }
enum class McpToolAccess { READ, WRITE }
enum class McpInitialToolPolicy {
    ASK, ALLOW, DENY;

    companion object {
        fun apply(config: McpServerConfig, discovered: List<McpToolConfig>): McpInitialToolPolicyResult =
            McpInitialToolPolicyApplication.apply(config, discovered)

        internal fun apply(
            config: McpServerConfig,
            discovered: List<McpToolConfig>,
            classify: (McpToolConfig) -> McpToolAccess,
        ): McpInitialToolPolicyResult = McpInitialToolPolicyApplication.apply(config, discovered, classify)
    }
}

/** Remote-supplied MCP tool annotations are preserved as hints, never as authorization. */
data class McpToolAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
    val title: String? = null,
)

data class McpServerConfig(
    val id: String,
    val alias: String,
    val endpoint: String,
    val transport: McpTransport = McpTransport.AUTO,
    val authMode: McpAuthMode = McpAuthMode.NONE,
    val trustInsecureServer: Boolean = false,
    val enabled: Boolean = true,
    val oauthClientId: String = "",
    val tools: List<McpToolConfig> = emptyList(),
    val toolSelectionMode: McpToolSelectionMode = McpToolSelectionMode.DEFAULT_ALL,
    val catalogServiceId: String? = null,
    val initialToolPolicy: McpInitialToolPolicy = McpInitialToolPolicy.ASK,
)

data class McpToolConfig(
    val wireName: String,
    val modelName: String,
    val description: String,
    val inputSchemaJson: String,
    val enabled: Boolean = false,
    val annotations: McpToolAnnotations? = null,
)

data class McpServerInfo(
    val name: String,
    val version: String,
    val instructions: String?,
)

data class McpConnectionSnapshot(
    val status: McpConnectionStatus = McpConnectionStatus.DISCONNECTED,
    val message: String = "",
    val serverInfo: McpServerInfo? = null,
)

enum class McpConnectionStatus { DISCONNECTED, CONNECTING, READY, ERROR, AUTH_REQUIRED, REAUTH_REQUIRED, PERMISSION_REQUIRED }

class McpReauthRequiredException(message: String) : IllegalStateException(message)
class McpPermissionRequiredException(message: String) : IllegalStateException(message)

data class McpToolDefinition(
    val serverId: String,
    val serverAlias: String,
    val wireName: String,
    val modelName: String,
    val description: String,
    val inputSchema: JSONObject,
    val catalogServiceId: String? = null,
    val annotations: McpToolAnnotations? = null,
    val access: McpToolAccess = McpToolAccess.WRITE,
)
