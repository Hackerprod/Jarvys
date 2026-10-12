package com.jarvys.agent.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test fun discoveryCannotOverwriteRestoredAskOrDenyFromStaleAllowCache() {
        for (mode in listOf(McpInitialToolPolicy.ASK, McpInitialToolPolicy.DENY)) {
            preferences.edit().clear().commit()
            val repository = McpServerRepository(context, TestVault())
            repository.upsert(server("restored", McpInitialToolPolicy.ALLOW))
            val restored = JSONArray(preferences.getString("servers_json", "[]"))
            restored.getJSONObject(0).put("initialToolPolicy", mode.name)
            val raw = restored.toString()
            preferences.edit().putString("servers_json", raw).commit()
            assertThrows(IllegalStateException::class.java) { repository.updateTools("restored", emptyList()) }
            assertEquals(raw, preferences.getString("servers_json", null))
            assertThrows(IllegalStateException::class.java) { repository.upsert(server("restored", McpInitialToolPolicy.ALLOW)) }
            assertThrows(IllegalStateException::class.java) { repository.authorityRevision("restored") }
            assertEquals(raw, preferences.getString("servers_json", null))
            assertEquals(mode, McpServerRepository(context, TestVault()).get("restored")!!.initialToolPolicy)
        }
    }

    @Test fun unchangedCustomRediscoveryPreservesRevisionAndDoesNotCreateGitHubChoices() {
        val repository = McpServerRepository(context, TestVault())
        val config = server("custom-refresh", McpInitialToolPolicy.ALLOW)
        val remote = McpToolConfig("update_record", "", "", "{}",
            annotations = McpToolAnnotations(readOnlyHint = false))
        repository.upsert(config.copy(tools = McpInitialToolPolicy.apply(config, listOf(remote)).tools))
        repository.updateToolEnabled(config.id, remote.wireName, true)
        val selected = repository.get(config.id)!!
        val revision = repository.authorityRevision(config.id)
        assertTrue(selected.githubToolPreferences.isEmpty())

        repository.updateTools(config.id, McpInitialToolPolicy.apply(selected, listOf(remote)).tools)

        assertEquals(selected, repository.get(config.id))
        assertEquals(revision, repository.authorityRevision(config.id))
        repository.updateToolEnabled(config.id, remote.wireName, false)
        assertTrue(repository.authorityRevision(config.id) > revision)
        assertFalse(repository.get(config.id)!!.tools.single().enabled)
    }

    @Test fun customLegacyIncidentalGitHubMetadataSurvivesRediscoveryWithoutAuthorityChurn() {
        val repository = McpServerRepository(context, TestVault())
        val config = server("legacy-refresh", McpInitialToolPolicy.ALLOW)
        val remote = McpToolConfig("update_record", "", "", "{}",
            annotations = McpToolAnnotations(readOnlyHint = false))
        val selected = config.copy(
            tools = McpInitialToolPolicy.apply(config, listOf(remote)).tools.map { it.copy(enabled = true) },
            toolSelectionMode = McpToolSelectionMode.CUSTOM,
            githubToolPreferences = mapOf(remote.wireName to "WRITE:true"),
        )
        repository.upsert(selected)
        val revision = repository.authorityRevision(config.id)

        repository.updateTools(config.id, McpInitialToolPolicy.apply(selected, listOf(remote)).tools)

        assertEquals(selected, repository.get(config.id))
        assertEquals(revision, repository.authorityRevision(config.id))
        assertEquals(selected.githubToolPreferences,
            McpServerRepository(context, TestVault()).get(config.id)!!.githubToolPreferences)
    }

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

    @Test fun legacyJsonWithoutInitialPolicyUsesCommonAllowDefault() {
        preferences.edit().putString("servers_json", JSONArray().put(JSONObject()
            .put("id", "legacy-server")
            .put("alias", "Legacy")
            .put("endpoint", "https://legacy.example/mcp")
            .put("tools", JSONArray())
        ).toString()).commit()

        val repository = McpServerRepository(context, TestVault())

        assertEquals(McpInitialToolPolicy.ALLOW, repository.get("legacy-server")?.initialToolPolicy)
        assertEquals(McpInitialPolicyOrigin.COMMON_DEFAULT, repository.get("legacy-server")?.initialToolPolicyOrigin)
    }

    @Test fun materializedLegacyChoicesStayUnchangedAcrossUnrelatedSavesAndReload() {
        for (policy in McpInitialToolPolicy.values()) {
            val json = JSONObject().put("id", "legacy").put("alias", "Legacy")
                .put("endpoint", "https://example.test/mcp").put("initialToolPolicy", policy.name)
                .put("tools", JSONArray().put(JSONObject().put("wireName", "tool").put("modelName", "tool")
                    .put("inputSchema", "{}").put("enabled", false)))
            preferences.edit().putString("servers_json", JSONArray().put(json).toString()).commit()
            val repository = McpServerRepository(context, TestVault())
            val config = repository.get("legacy")!!
            assertEquals(policy, config.initialToolPolicy)
            assertEquals(McpInitialPolicyOrigin.LEGACY_UNKNOWN, config.initialToolPolicyOrigin)
            repository.upsert(config.copy(alias = "Edited alias"))
            val reloaded = McpServerRepository(context, TestVault()).get("legacy")!!
            assertEquals(policy, reloaded.initialToolPolicy)
            assertEquals(McpInitialPolicyOrigin.LEGACY_UNKNOWN, reloaded.initialToolPolicyOrigin)
            assertFalse(reloaded.tools.single().enabled)
        }
    }

    @Test fun invalidValueIsDeniedPreservedAndRepairableWithoutChangingExposure() {
        for (raw in listOf<Any>("UNKNOWN", 7, true, JSONObject.NULL, JSONObject().put("bad", true))) {
            val json = JSONObject().put("id", "invalid").put("alias", "Invalid")
                .put("endpoint", "https://example.test/mcp").put("initialToolPolicy", raw)
            preferences.edit().putString("servers_json", JSONArray().put(json).toString()).commit()
            val repository = McpServerRepository(context, TestVault())
            val config = repository.get("invalid")!!
            assertEquals(McpInitialToolPolicy.DENY, config.initialToolPolicy)
            assertEquals(McpInitialPolicyOrigin.INVALID, config.initialToolPolicyOrigin)
            repository.upsert(config.copy(alias = "Edited"))
            val persisted = JSONArray(preferences.getString("servers_json", "[]")).getJSONObject(0)
            assertEquals(raw.toString(), persisted.get("initialToolPolicy").toString())
            repository.upsert(config.copy(initialToolPolicy = McpInitialToolPolicy.ASK,
                initialToolPolicyOrigin = McpInitialPolicyOrigin.EXPLICIT_CURRENT, invalidInitialToolPolicyJson = null))
            assertEquals(McpInitialToolPolicy.ASK, McpServerRepository(context, TestVault()).get("invalid")!!.initialToolPolicy)
        }
    }

    @Test fun configAbaAndRestoredSettingsInvalidateInFlightAuthority() {
        val repository = McpServerRepository(context, TestVault())
        val config = server("revision", McpInitialToolPolicy.ALLOW)
        repository.upsert(config)
        val first = repository.authorityRevision(config.id)
        repository.upsert(config.copy(endpoint = "https://other.example/mcp"))
        repository.upsert(config)
        assertTrue(repository.authorityRevision(config.id) > first)
        preferences.edit().putString("servers_json", "[]").commit()
        assertThrows(IllegalStateException::class.java) { repository.authorityRevision(config.id) }
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
