package com.jarvys.agent.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.ActionPolicyDefaults
import com.jarvys.agent.connectors.ConnectorStateStore
import com.jarvys.agent.ui.settings.SetupWizardPreferences
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpInitialToolPolicyTest {
    private val policyPreferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(ConnectorStateStore.PREFERENCES, Context.MODE_PRIVATE)
    private var previousDefaultPolicy: String? = null
    private var hadPreviousDefaultPolicy = false
    private var previousWizardCompleted = false
    private var hadPreviousWizardCompleted = false

    @Before fun clearWizardDefault() {
        hadPreviousDefaultPolicy = policyPreferences.contains(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY)
        previousDefaultPolicy = policyPreferences.getString(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY, null)
        hadPreviousWizardCompleted = policyPreferences.contains(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY)
        previousWizardCompleted = policyPreferences.getBoolean(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY, false)
        policyPreferences.edit().remove(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY)
            .remove(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY).commit()
        ActionPolicyDefaults.bindDefaultPolicyPreferences(policyPreferences)
    }

    @After fun restoreWizardDefault() {
        policyPreferences.edit().apply {
            if (hadPreviousDefaultPolicy) putString(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY, previousDefaultPolicy)
            else remove(ActionPolicyDefaults.DEFAULT_POLICY_PREFERENCE_KEY)
            if (hadPreviousWizardCompleted) putBoolean(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY, previousWizardCompleted)
            else remove(ActionPolicyDefaults.WIZARD_COMPLETED_PREFERENCE_KEY)
        }.commit()
        ActionPolicyDefaults.bindDefaultPolicyPreferences(null)
    }

    private fun server(initial: McpInitialToolPolicy, tools: List<McpToolConfig> = emptyList()) =
        McpServerConfig("policy-server", "Policy server", "https://example.test/mcp", tools = tools, initialToolPolicy = initial)
    private fun discovered() = listOf(
        McpToolConfig("read_summary", "", "", "{}", annotations = McpToolAnnotations(readOnlyHint = true)),
        McpToolConfig("create_issue", "", "", "{}", annotations = McpToolAnnotations(readOnlyHint = false)),
        McpToolConfig("delete_file", "", "", "{}", annotations = McpToolAnnotations(destructiveHint = true)),
    )

    @Test fun everyInitialPolicyLeavesNewCapabilitiesUnselectedAndAvoidsPolicySeeds() {
        McpInitialToolPolicy.values().forEach { initial ->
            val config = server(initial).copy(initialToolPolicyOrigin = McpInitialPolicyOrigin.EXPLICIT_CURRENT)
            val result = McpInitialToolPolicy.apply(config, discovered())
            assertEquals(McpToolSecurity.mergeDiscoveredTools(config, discovered()), result.tools)
            assertTrue(result.tools.none { it.enabled })
            assertTrue(result.newWritePolicies.isEmpty())
            assertEquals(AutonomyPolicy.valueOf(initial.name), config.inheritedActionPolicy())
        }
    }

    @Test fun explicitAndLegacyServerPoliciesAreInheritedWhileCommonDefaultUsesTheWizardChoice() {
        McpInitialPolicyOrigin.values().filter { it in setOf(
            McpInitialPolicyOrigin.EXPLICIT_CURRENT, McpInitialPolicyOrigin.LEGACY_UNKNOWN,
        ) }.forEach { origin ->
            McpInitialToolPolicy.values().forEach { initial ->
                val saved = server(initial).copy(initialToolPolicyOrigin = origin)
                assertEquals("$origin must retain saved $initial", AutonomyPolicy.valueOf(initial.name),
                    saved.inheritedActionPolicy())
            }
        }
        SetupWizardPreferences(policyPreferences).complete(AutonomyPolicy.DENY)
        val common = server(McpInitialToolPolicy.ALLOW).copy(initialToolPolicyOrigin = McpInitialPolicyOrigin.COMMON_DEFAULT)
        assertEquals(AutonomyPolicy.DENY, common.inheritedActionPolicy())
    }

    @Test fun invalidServerPolicyFailsClosedToDeny() {
        val invalid = server(McpInitialToolPolicy.ALLOW).copy(
            initialToolPolicyOrigin = McpInitialPolicyOrigin.INVALID,
            invalidInitialToolPolicyJson = "\"not-a-policy\"",
        )
        assertEquals(AutonomyPolicy.DENY, invalid.inheritedActionPolicy())
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
        assertEquals(McpInitialToolPolicy.ALLOW,
            McpServerConfig("fresh", "Fresh", "https://example.test/mcp").initialToolPolicy)
    }
}
