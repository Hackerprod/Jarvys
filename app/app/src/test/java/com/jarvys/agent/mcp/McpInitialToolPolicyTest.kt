package com.jarvys.agent.mcp

import com.jarvys.agent.connectors.AutonomyPolicy
import org.junit.Assert.*
import org.junit.Test

class McpInitialToolPolicyTest {
    private fun server(initial: McpInitialToolPolicy, tools: List<McpToolConfig> = emptyList()) =
        McpServerConfig("policy-server", "Policy server", "https://example.test/mcp", tools = tools, initialToolPolicy = initial)
    private fun discovered() = listOf(
        McpToolConfig("read_summary", "", "", "{}", annotations = McpToolAnnotations(readOnlyHint = true)),
        McpToolConfig("create_issue", "", "", "{}", annotations = McpToolAnnotations(readOnlyHint = false)),
        McpToolConfig("delete_file", "", "", "{}", annotations = McpToolAnnotations(destructiveHint = true)),
    )

    @Test fun everyInitialPolicyLeavesNewCapabilitiesUnselectedAndAvoidsPolicySeeds() {
        McpInitialToolPolicy.values().forEach { initial ->
            val config = server(initial)
            val result = McpInitialToolPolicy.apply(config, discovered())
            assertEquals(McpToolSecurity.mergeDiscoveredTools(config, discovered()), result.tools)
            assertTrue(result.tools.none { it.enabled })
            assertTrue(result.newWritePolicies.isEmpty())
            assertEquals(AutonomyPolicy.valueOf(initial.name), config.inheritedActionPolicy())
        }
    }

    @Test fun knownEnabledAndDisabledChoicesSurviveEveryInitialPolicy() {
        val existing = discovered().map { it.copy(enabled = it.wireName == "create_issue") }
        McpInitialToolPolicy.values().forEach { initial ->
            val result = McpInitialToolPolicy.apply(server(initial, existing), discovered())
            assertTrue(result.tools.single { it.wireName == "create_issue" }.enabled)
            assertFalse(result.tools.single { it.wireName == "delete_file" }.enabled)
            assertTrue(result.newWritePolicies.isEmpty())
        }
    }

    @Test fun catalogueInitialFieldIsNotACustomServerAncestor() {
        val config = server(McpInitialToolPolicy.DENY).copy(catalogServiceId = "github")
        assertNull(config.inheritedActionPolicy())
        assertTrue(McpInitialToolPolicy.apply(config, discovered()).newWritePolicies.isEmpty())
    }

    @Test fun freshCustomServerUsesTheCommonAllowDefault() {
        assertEquals(McpInitialToolPolicy.ALLOW, McpServerConfig("fresh", "Fresh", "https://example.test/mcp").initialToolPolicy)
    }
}
