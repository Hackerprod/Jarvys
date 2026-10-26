package com.jarvys.agent.modules

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModulesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun moduleCardShowsManifestAndLetsUserToggleModuleAndToolPolicy() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val moduleRegistry = ModuleRegistry.fromManifests(listOf("""
            {"schemaVersion":1,"id":"fixture.module","version":"1.2.3","name":"Fixture module",
             "artifactTypes":[{"type":"application/x-fixture"}],
             "tools":[{"name":"fixture_tool","description":"Creates a fixture artifact","access":"write",
               "handler":"main.js#run","inputSchema":{"type":"object"}}]}
        """))
        val choices = mutableMapOf<String, Boolean>()
        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            override fun isConnected(id: String) = choices[id] == true
            override fun hasConnectionChoice(id: String) = id in choices
            override fun setConnected(id: String, connected: Boolean) { choices[id] = connected }
        }, { true })

        compose.setContent { MaterialTheme { ModulesScreen(moduleRegistry, connectors) } }
        compose.waitUntil(timeoutMillis = 10_000) {
            connectors.state("fixture.module") == com.jarvys.agent.connectors.ConnectorState.CONNECTED
        }
        compose.waitForIdle()

        compose.onNodeWithText("Fixture module").assertIsDisplayed()
        compose.onNodeWithText("Version 1.2.3").assertIsDisplayed()
        compose.onNodeWithText("fixture_tool").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("application/x-fixture").performScrollTo().assertIsDisplayed()

        val enabledSwitch = compose.onNodeWithTag("module-fixture.module-enabled").performScrollTo()
        enabledSwitch.assertIsOn().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            connectors.state("fixture.module") == com.jarvys.agent.connectors.ConnectorState.DISCONNECTED
        }
        enabledSwitch.assertIsOff().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            connectors.state("fixture.module") == com.jarvys.agent.connectors.ConnectorState.CONNECTED
        }
        enabledSwitch.assertIsOn()

        val policy = compose.onNodeWithTag("connector-policy-fixture_tool-control").performScrollTo()
        policy.performClick()
        compose.onNodeWithTag("connector-policy-fixture_tool-option-deny").performClick()
        val definition = connectors.get("fixture.module")!!
        val operation = definition.operations.single()
        assertEquals(AutonomyPolicy.DENY, connectors.configuredAutonomyPolicy(definition, operation))
    }
}
