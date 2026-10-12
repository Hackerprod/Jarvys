package com.jarvys.agent.mcp

import org.json.JSONObject

enum class McpTransport { AUTO, STREAMABLE_HTTP, LEGACY_SSE }
enum class McpAuthMode { NONE, BEARER, OAUTH }
enum class McpToolSelectionMode { DEFAULT_ALL, ALL, CUSTOM, NONE }
enum class McpToolAccess { READ, WRITE }

/** Persisted policy provenance is distinct from tool selection and provider grants. */
enum class McpInitialPolicyOrigin { COMMON_DEFAULT, EXPLICIT_CURRENT, LEGACY_UNKNOWN, INVALID }
enum class McpInitialToolPolicy {
    ASK, ALLOW, DENY;

    companion object {
        fun defaultPolicy(): McpInitialToolPolicy = valueOf(com.jarvys.agent.connectors.ActionPolicyDefaults
            .descriptor("mcp-server", "initial_tool_policy").defaultPolicy.name)

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
    val initialToolPolicy: McpInitialToolPolicy = McpInitialToolPolicy.defaultPolicy(),
    val githubToolsets: Set<String> = com.jarvys.agent.connectors.GitHubOperationPolicy.defaultToolsets,
    val githubToolPreferences: Map<String, String> = emptyMap(),
    val initialToolPolicyOrigin: McpInitialPolicyOrigin = McpInitialPolicyOrigin.COMMON_DEFAULT,
    /** Preserve a corrupt raw value on unrelated saves until the policy is explicitly repaired. */
    val invalidInitialToolPolicyJson: String? = null,
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
    val requiredScopes: Set<String> = emptySet(),
)

enum class McpConnectionStatus { DISCONNECTED, CONNECTING, READY, ERROR, AUTH_REQUIRED, REAUTH_REQUIRED, PERMISSION_REQUIRED }

class McpReauthRequiredException(message: String) : IllegalStateException(message)
class McpPermissionRequiredException(message: String, val requiredScopes: Set<String> = emptySet()) : IllegalStateException(message)

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
    /** Custom server fallback; a saved exact action policy always takes precedence. */
    val inheritedPolicy: com.jarvys.agent.connectors.AutonomyPolicy? = null,
)
