package com.jarvys.agent.mcp

import com.jarvys.agent.connectors.AutonomyPolicy

data class McpInitialToolPolicyResult(
    val tools: List<McpToolConfig>,
    val newWritePolicies: Map<String, AutonomyPolicy>,
)

/** Applies a server's add-time default only to wire names not previously known by the user. */
internal object McpInitialToolPolicyApplication {
    fun apply(config: McpServerConfig, discovered: List<McpToolConfig>): McpInitialToolPolicyResult =
        apply(config, discovered) { tool ->
            McpToolSecurity.classify(config.catalogServiceId, tool.wireName, tool.annotations)
        }

    fun apply(
        config: McpServerConfig,
        discovered: List<McpToolConfig>,
        classify: (McpToolConfig) -> McpToolAccess,
    ): McpInitialToolPolicyResult {
        val merged = McpToolSecurity.mergeDiscoveredTools(config, discovered)
        if (config.catalogServiceId != null) return McpInitialToolPolicyResult(merged, emptyMap())

        val knownNames = config.tools.mapTo(mutableSetOf()) { it.wireName }
        val writePolicies = linkedMapOf<String, AutonomyPolicy>()
        var selectedReads = merged.count { tool ->
            tool.wireName in knownNames && tool.enabled
                    && classify(tool) == McpToolAccess.READ
        }
        val tools = merged.map { tool ->
            if (tool.wireName in knownNames) return@map tool
            when (config.initialToolPolicy) {
                McpInitialToolPolicy.ASK -> {
                    if (classify(tool) == McpToolAccess.WRITE) {
                        writePolicies[tool.wireName] = AutonomyPolicy.ASK
                    }
                    tool
                }
                McpInitialToolPolicy.DENY -> {
                    if (classify(tool) == McpToolAccess.WRITE) {
                        writePolicies[tool.wireName] = AutonomyPolicy.DENY
                    }
                    tool.copy(enabled = false)
                }
                McpInitialToolPolicy.ALLOW -> when (classify(tool)) {
                    McpToolAccess.READ -> {
                        val enable = selectedReads < McpToolSecurity.MAX_EXPOSED_TOOLS_PER_SERVER
                        if (enable) selectedReads++
                        tool.copy(enabled = enable)
                    }
                    McpToolAccess.WRITE -> {
                        writePolicies[tool.wireName] = if (McpToolSecurity.isDestructive(tool.wireName, tool.annotations)) {
                            AutonomyPolicy.ASK
                        } else {
                            AutonomyPolicy.ALLOW
                        }
                        tool.copy(enabled = true)
                    }
                }
            }
        }
        return McpInitialToolPolicyResult(tools, writePolicies)
    }
}
