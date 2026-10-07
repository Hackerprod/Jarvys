package com.jarvys.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolSelectionPolicyTest {
    @Test
    fun discoveryDefaultsToolsOffAndPreservesExplicitSelections() {
        val reads = (0 until 42).map { index ->
            McpToolConfig("read_$index", "", "", "{}", annotations = McpToolAnnotations(readOnlyHint = true))
        }
        val write = McpToolConfig("create_issue", "", "", "{}", annotations = McpToolAnnotations(readOnlyHint = false))
        val defaults = McpToolSecurity.mergeDiscoveredTools(
            McpServerConfig("server", "Server", "https://example.test/mcp"), reads + write,
        )
        assertEquals(0, defaults.count { it.enabled })
        assertFalse(defaults.last().enabled)

        val selected = defaults.map { tool -> tool.copy(enabled = tool.wireName == "read_41" || tool.wireName == "create_issue") }
        val custom = McpToolSecurity.mergeDiscoveredTools(
            McpServerConfig("server", "Server", "https://example.test/mcp", tools = selected,
                toolSelectionMode = McpToolSelectionMode.CUSTOM),
            reads + write,
        )
        assertTrue(custom.first { it.wireName == "read_41" }.enabled)
        assertTrue(custom.last().enabled)
    }
}
