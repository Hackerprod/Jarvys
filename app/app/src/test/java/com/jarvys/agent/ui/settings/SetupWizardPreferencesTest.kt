package com.jarvys.agent.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.ActionPolicyDefaults
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.SharedPreferencesConnectorAutonomyStore
import com.jarvys.agent.mcp.McpInitialToolPolicy
import com.jarvys.agent.mcp.McpServerConfig
import com.jarvys.agent.mcp.inheritedActionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupWizardPreferencesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun oneWizardChoiceFeedsEveryMissingPolicyAndSavedToolChoicesStillWin() {
        val preferences = isolatedPreferences()
        try {
            val policies = SharedPreferencesConnectorAutonomyStore(preferences, "wizard-${UUID.randomUUID()}")
            val wizard = SetupWizardPreferences(preferences)

            assertEquals(AutonomyPolicy.ALLOW, policies.policy("connector-fixture", "new_action"))
            assertEquals(AutonomyPolicy.ALLOW, ActionPolicyDefaults.selectedDefaultPolicy())
            assertEquals(McpInitialToolPolicy.ALLOW, McpInitialToolPolicy.defaultPolicy())

            policies.setPolicy("connector-fixture", "chosen_action", AutonomyPolicy.ALLOW)
            wizard.complete(AutonomyPolicy.ASK)

            assertEquals(AutonomyPolicy.ASK, policies.policy("connector-fixture", "new_action"))
            assertEquals(AutonomyPolicy.ALLOW, policies.policy("connector-fixture", "chosen_action"))
            assertEquals(McpInitialToolPolicy.ASK, McpInitialToolPolicy.defaultPolicy())
            val mcp = McpServerConfig("fixture", "Fixture", "https://example.test/mcp")
            assertEquals(AutonomyPolicy.ASK, mcp.inheritedActionPolicy())

            wizard.complete(AutonomyPolicy.DENY)
            assertEquals(AutonomyPolicy.DENY, policies.policy("connector-fixture", "another_new_action"))
        } finally {
            preferences.edit().clear().commit()
            ActionPolicyDefaults.bindDefaultPolicyPreferences(null)
        }
    }

    @Test
    fun launchShowsWizardOnceAndSkippingRecordsAllowAsTheChoice() {
        val preferences = isolatedPreferences()
        try {
            val wizard = SetupWizardPreferences(preferences)
            assertTrue(wizard.shouldShowOnLaunch())
            assertFalse(wizard.hasSelectedPolicy())

            wizard.skip()

            assertFalse(wizard.shouldShowOnLaunch())
            assertTrue(wizard.hasSelectedPolicy())
            assertEquals(AutonomyPolicy.ALLOW, wizard.selectedPolicy())
            assertEquals(AutonomyPolicy.ALLOW, ActionPolicyDefaults.selectedDefaultPolicy())
        } finally {
            preferences.edit().clear().commit()
            ActionPolicyDefaults.bindDefaultPolicyPreferences(null)
        }
    }

    private fun isolatedPreferences() = context.getSharedPreferences(
        "setup-wizard-${UUID.randomUUID()}", Context.MODE_PRIVATE,
    ).apply { edit().clear().commit() }
}
