package com.jarvys.agent.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpInitialToolPolicyRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = context.getSharedPreferences("jarvys_mcp_servers", Context.MODE_PRIVATE)

    @Before fun clearMcpSettings() { preferences.edit().clear().commit() }
    @After fun restoreMcpSettings() { preferences.edit().clear().commit() }

    @Test fun initialPolicyRoundTripsInServerJson() {
        val repository = McpServerRepository(context, TestVault())
        repository.upsert(server("policy-deny", McpInitialToolPolicy.DENY))
        repository.upsert(server("policy-allow", McpInitialToolPolicy.ALLOW))

        val savedJson = preferences.getString("servers_json", "[]").orEmpty()
        assertTrue(savedJson.contains("\"initialToolPolicy\":\"DENY\""))
        assertTrue(savedJson.contains("\"initialToolPolicy\":\"ALLOW\""))
        val reloaded = McpServerRepository(context, TestVault())
        assertEquals(McpInitialToolPolicy.DENY, reloaded.get("policy-deny")?.initialToolPolicy)
        assertEquals(McpInitialToolPolicy.ALLOW, reloaded.get("policy-allow")?.initialToolPolicy)
    }

    @Test fun legacyJsonWithoutInitialPolicyDefaultsToAsk() {
        preferences.edit().putString("servers_json", JSONArray().put(JSONObject()
            .put("id", "legacy-server")
            .put("alias", "Legacy")
            .put("endpoint", "https://legacy.example/mcp")
            .put("tools", JSONArray())
        ).toString()).commit()

        val repository = McpServerRepository(context, TestVault())

        assertEquals(McpInitialToolPolicy.ASK, repository.get("legacy-server")?.initialToolPolicy)
    }

    private fun server(id: String, initial: McpInitialToolPolicy) = McpServerConfig(
        id = id, alias = id, endpoint = "https://example.test/mcp", initialToolPolicy = initial,
    )

    private class TestVault : McpCredentialVault {
        private val secrets = mutableMapOf<Pair<String, String>, String>()
        override fun get(serverId: String, key: String): String? = secrets[serverId to key]
        override fun save(serverId: String, key: String, value: String) { secrets[serverId to key] = value }
        override fun clear(serverId: String) { secrets.keys.removeAll { it.first == serverId } }
    }
}
