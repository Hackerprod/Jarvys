package com.jarvys.agent.mcp

import com.jarvys.agent.connectors.AutonomyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpInitialToolPolicyTest {
    private val classify: (McpToolConfig) -> McpToolAccess = { tool ->
        if (tool.wireName == "read_summary") McpToolAccess.READ else McpToolAccess.WRITE
    }

    private fun server(
        initial: McpInitialToolPolicy,
        tools: List<McpToolConfig> = emptyList(),
        catalogServiceId: String? = null,
    ) = McpServerConfig(
        id = "policy-server", alias = "Policy server", endpoint = "https://example.test/mcp",
        tools = tools, initialToolPolicy = initial, catalogServiceId = catalogServiceId,
    )

    private fun discovered() = listOf(
        McpToolConfig("read_summary", "", "", "{}", annotations = McpToolAnnotations(readOnlyHint = true)),
        McpToolConfig("create_issue", "", "", "{}", annotations = McpToolAnnotations(readOnlyHint = false)),
        McpToolConfig("delete_file", "", "", "{}", annotations = McpToolAnnotations(readOnlyHint = false)),
    )

    @Test fun askPreservesCurrentDiscoveryDefaultsAndUsesAskForNewWrites() {
        val config = server(McpInitialToolPolicy.ASK)
        val tools = discovered()

        val result = McpInitialToolPolicy.apply(config, tools, classify)

        assertEquals(McpToolSecurity.mergeDiscoveredTools(config, tools), result.tools)
        assertEquals(AutonomyPolicy.ASK, result.newWritePolicies["create_issue"])
        assertEquals(AutonomyPolicy.ASK, result.newWritePolicies["delete_file"])
        assertFalse(result.tools.any { it.enabled })
    }

    @Test fun denyDisablesAllNewToolsAndDeniesNewWriteTools() {
        val result = McpInitialToolPolicy.apply(server(McpInitialToolPolicy.DENY), discovered(), classify)

        assertTrue(result.tools.all { !it.enabled })
        assertEquals(mapOf("create_issue" to AutonomyPolicy.DENY, "delete_file" to AutonomyPolicy.DENY),
            result.newWritePolicies)
        assertFalse(result.newWritePolicies.containsKey("read_summary"))
    }

    @Test fun allowEnablesReadsAndWritesButLeavesHighImpactWriteOnAsk() {
        val result = McpInitialToolPolicy.apply(server(McpInitialToolPolicy.ALLOW), discovered(), classify)

        assertTrue(result.tools.first { it.wireName == "read_summary" }.enabled)
        assertTrue(result.tools.first { it.wireName == "create_issue" }.enabled)
        assertTrue(result.tools.first { it.wireName == "delete_file" }.enabled)
        assertEquals(AutonomyPolicy.ALLOW, result.newWritePolicies["create_issue"])
        assertEquals(AutonomyPolicy.ASK, result.newWritePolicies["delete_file"])
    }

    @Test fun knownToolsKeepSelectionAndOnlyLaterNewToolsReceiveInitialPolicy() {
        val known = McpToolConfig("create_issue", "old-model-name", "old", "{}", enabled = true,
            annotations = McpToolAnnotations(readOnlyHint = false))
        val config = server(McpInitialToolPolicy.DENY, listOf(known))
        val firstRefresh = McpInitialToolPolicy.apply(config, discovered(), classify)

        assertTrue(firstRefresh.tools.first { it.wireName == "create_issue" }.enabled)
        assertFalse(firstRefresh.newWritePolicies.containsKey("create_issue"))
        assertEquals(AutonomyPolicy.DENY, firstRefresh.newWritePolicies["delete_file"])

        val laterTool = McpToolConfig("update_issue", "", "", "{}", annotations = McpToolAnnotations(readOnlyHint = false))
        val laterRefresh = McpInitialToolPolicy.apply(config.copy(tools = firstRefresh.tools), discovered() + laterTool, classify)
        assertTrue(laterRefresh.tools.first { it.wireName == "create_issue" }.enabled)
        assertFalse(laterRefresh.tools.first { it.wireName == "delete_file" }.enabled)
        assertFalse(laterRefresh.newWritePolicies.containsKey("delete_file"))
        assertEquals(AutonomyPolicy.DENY, laterRefresh.newWritePolicies["update_issue"])
    }

    @Test fun catalogServicesKeepTheirExistingDiscoveryPolicy() {
        val config = server(McpInitialToolPolicy.DENY, catalogServiceId = "github")
        val tools = discovered()

        val result = McpInitialToolPolicy.apply(config, tools, classify)

        assertEquals(McpToolSecurity.mergeDiscoveredTools(config, tools), result.tools)
        assertTrue(result.newWritePolicies.isEmpty())
    }
}
