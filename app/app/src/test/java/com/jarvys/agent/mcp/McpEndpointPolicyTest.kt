package com.jarvys.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI

class McpEndpointPolicyTest {
    @Test
    fun httpsAndPrivateLanHttpAreAllowedWithoutUnsafeTrust() {
        assertEquals("https", McpEndpointPolicy.validate("https://mcp.example/mcp", false).scheme)
        assertEquals("http", McpEndpointPolicy.validate("http://127.0.0.1:8000/mcp", false).scheme)
        assertEquals("http", McpEndpointPolicy.validate("http://192.168.1.40:8000/mcp", false).scheme)
    }

    @Test
    fun publicHttpRequiresExplicitTrustAndTrustIsPerConfig() {
        assertThrows(IllegalArgumentException::class.java) {
            McpEndpointPolicy.validate("http://203.0.113.20:8000/mcp", false)
        }
        assertEquals("203.0.113.20", McpEndpointPolicy.validate("http://203.0.113.20:8000/mcp", true).host)
    }

    @Test
    fun credentialsInUrlAndCrossOriginLegacyEndpointAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            McpEndpointPolicy.validate("https://mcp.example/mcp?access_token=secret", true)
        }
        val original = URI("https://mcp.example/sse")
        assertThrows(IllegalArgumentException::class.java) {
            McpEndpointPolicy.requireSameOrigin(original, URI("https://attacker.example/messages"), true)
        }
    }

    @Test
    fun toolNamesAreBoundedStableAndDistinctAcrossServersAndUnsafeNames() {
        val first = McpServerConfig("server-one", "Files Server", "https://files.example/mcp")
        val second = first.copy(id = "server-two")
        val normalizedCollisionA = McpServerToolRegistry.namespace(first, "search-files")
        val normalizedCollisionB = McpServerToolRegistry.namespace(first, "search files")

        assertEquals(normalizedCollisionA, McpServerToolRegistry.namespace(first, "search-files"))
        assertNotEquals(normalizedCollisionA, normalizedCollisionB)
        assertNotEquals(normalizedCollisionA, McpServerToolRegistry.namespace(second, "search-files"))
        assert(normalizedCollisionA.matches(Regex("[a-z0-9_]{1,64}")))
    }
}
