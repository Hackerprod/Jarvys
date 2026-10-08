package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorDefinition
import com.jarvys.agent.connectors.ConnectorOperation
import com.jarvys.agent.connectors.ConnectorPresentationGroup
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.connectors.ConnectorRuntime
import com.jarvys.agent.connectors.ConnectorWritePreparation
import com.jarvys.agent.crew.CrewRoleTemplates
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotAndroidConnectorScopeTest {
    @Test fun androidRoleExcludesServiceConnectorsWithoutChangingGenericOperator() {
        val preferences = object : ConnectorConnectionPreferences {
            val connected = mutableSetOf<String>()
            override fun isConnected(id: String) = id in connected
            override fun setConnected(id: String, value: Boolean) { if (value) connected.add(id) else connected.remove(id) }
        }
        val runtime = object : ConnectorRuntime {
            override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
            override fun disconnect() = Unit
            override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken) = JSONObject()
            override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken) =
                ConnectorWritePreparation(ApprovalSummary("Write", emptyList()), arguments)
        }
        val native = ConnectorDefinition("phone_calendar", "Phone Calendar", "1", "Native calendar",
            operations = listOf(ConnectorOperation("read", "Read native calendar", JSONObject())),
            runtime = runtime, presentationGroup = ConnectorPresentationGroup.ON_DEVICE)
        val cloud = ConnectorDefinition("google_mail", "Google Mail", "1", "Remote email",
            operations = listOf(ConnectorOperation("read", "Read remote email", JSONObject())),
            runtime = runtime, presentationGroup = ConnectorPresentationGroup.SERVICES)
        val connectors = ConnectorRegistry.createForTests(preferences, { true }).apply {
            register(native); register(cloud); connect(native.id); connect(cloud.id)
        }
        val tools = CoreToolRegistry(connectors.connectedDefinitions().flatMap { definition ->
            definition.operations.map { CoreConnectorTool(connectors, definition, it) }
        })
        val nativeName = CoreConnectorTool.toolName(native.id, "read")
        val cloudName = CoreConnectorTool.toolName(cloud.id, "read")
        assertEquals(setOf(nativeName, cloudName), tools.connectorToolNames().toSet())
        assertEquals(listOf(nativeName), tools.onDeviceConnectorToolNames())
        assertTrue(CrewRoleTemplates.all(tools).single { it.id == CrewRoleTemplates.OPERATOR }.tools.contains(cloudName))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val agent = CoreAgentRuntime(context, "android-scope-test", emptyList())
        val role = agent.resolveCrewProfile(tools.names(), tools.onDeviceConnectorToolNames(), CrewRoleTemplates.ANDROID_USE)
        assertTrue(role.tools.contains(nativeName))
        assertFalse(role.tools.contains(cloudName))
    }
}
