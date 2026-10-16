package com.jarvys.agent.mcp

import com.jarvys.agent.connectors.AutonomyPolicy

data class McpInitialToolPolicyResult(
    val tools: List<McpToolConfig>,
    /** Kept source-compatible for older callers; policies are now inherited, never seeded. */
    val newWritePolicies: Map<String, AutonomyPolicy> = emptyMap(),
)

/** Discovery changes metadata only. Policy cannot select, expose, or grant a remote capability. */
internal object McpInitialToolPolicyApplication {
    fun apply(config: McpServerConfig, discovered: List<McpToolConfig>): McpInitialToolPolicyResult =
        McpInitialToolPolicyResult(McpToolSecurity.mergeDiscoveredTools(config, discovered))

    @Suppress("UNUSED_PARAMETER")
    fun apply(
        config: McpServerConfig,
        discovered: List<McpToolConfig>,
        classify: (McpToolConfig) -> McpToolAccess,
    ): McpInitialToolPolicyResult = apply(config, discovered)
}

/** New tools inherit the same Wizard-selected autonomy default as connectors and modules. */
fun McpServerConfig.inheritedActionPolicy(): AutonomyPolicy? =
    if (catalogServiceId != null) null else com.jarvys.agent.connectors.ActionPolicyDefaults.selectedDefaultPolicy()
