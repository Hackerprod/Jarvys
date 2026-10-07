package com.jarvys.agent

import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorDefinition
import com.jarvys.agent.connectors.ConnectorOperation
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.connectors.ConnectorRuntime
import com.jarvys.agent.connectors.ConnectorWritePreparation
import com.jarvys.agent.connectors.CalendarConnector
import com.jarvys.agent.connectors.DeviceLocationFix
import com.jarvys.agent.connectors.DeviceLocationGateway
import com.jarvys.agent.connectors.GeocoderAttempt
import com.jarvys.agent.connectors.LocationAttempt
import com.jarvys.agent.connectors.LocationConnector
import com.jarvys.agent.skills.SkillEntry
import com.jarvys.agent.skills.SkillMetadata
import com.jarvys.agent.skills.SkillSource
import com.jarvys.agent.mcp.McpToolDefinition
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreAgentRuntimeConnectorToolsTest {
    private class Preferences : ConnectorConnectionPreferences {
        private val values = mutableMapOf<String, Boolean>()
        override fun isConnected(id: String) = values[id] == true
        override fun setConnected(id: String, connected: Boolean) { values[id] = connected }
    }

    private class FakeLocationGateway : DeviceLocationGateway {
        override fun isLocationEnabled() = true
        override fun isAppActivityVisible() = true
        override fun lastKnownLocations() = emptyList<DeviceLocationFix>()
        override fun currentLocation(timeoutMillis: Long, token: CancellationToken) =
            LocationAttempt.Unavailable(LocationConnector.REASON_NO_FIX)
        override fun isGeocoderPresent() = false
        override fun reverseGeocode(latitude: Double, longitude: Double, precision: String,
                                    timeoutMillis: Long, token: CancellationToken) =
            GeocoderAttempt.Unavailable(LocationConnector.REASON_GEOCODER_UNAVAILABLE)
    }

    @Test fun enabledBundledSkillsDoNotHideConnectedConnectorOrMcpTools() {
        val registry = connectedCalendarRegistry()
        val connectorDefinition = registry.connectedDefinitions().single()
        val connectorTool = CoreConnectorTool(registry, connectorDefinition, connectorDefinition.operations.single())
        val mcpTool = CoreMcpTool(null, McpToolDefinition(
            "example-server", "Example", "lookup", "mcp_lookup", "Find an item", JSONObject(),
        ))
        val runtime = CoreAgentRuntime(
            bundledSkills(), listOf(mcpTool), listOf(connectorTool), emptyList(),
        )

        val names = runtime.createTools().names()
        assertTrue("calendar tool omitted: $names", CalendarConnector.SEARCH_TOOL in names)
        assertTrue("MCP tool omitted: $names", "mcp_lookup" in names)
    }

    @Test fun connectedUsageNoteIsAddedToInstructionsAndOmittedWhenDisconnected() {
        val registry = connectedCalendarRegistry()
        val skills = bundledSkills()
        val definition = registry.connectedDefinitions().single()
        val connected = CoreAgentRuntime(
            skills,
            emptyList(),
            definition.operations.map { CoreConnectorTool(registry, definition, it) },
            emptyList(),
            registry,
        )
        assertTrue(connected.instructions().contains("calendar-usage-note"))
        assertFalse(connected.instructions().contains("Device connectors available but not connected"))
        assertTrue(CalendarConnector.SEARCH_TOOL in connected.createTools().names())

        registry.disconnect(definition.id)
        val disconnected = CoreAgentRuntime(skills, emptyList(), emptyList(), emptyList(), registry)
        assertFalse(disconnected.instructions().contains("calendar-usage-note"))
        assertTrue(disconnected.instructions().contains("Device connectors available but not connected"))
        assertTrue(disconnected.instructions().contains("- Calendar: Calendar"))
        assertFalse(CalendarConnector.SEARCH_TOOL in disconnected.createTools().names())
    }

    @Test fun permissionRevokedDeviceConnectorIsListedAsNeedingReconnect() {
        var permissionGranted = true
        val runtime = object : ConnectorRuntime {
            override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
            override fun disconnect() = Unit
            override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken) =
                ConnectorWritePreparation(ApprovalSummary("Write", emptyList()), arguments)
            override fun invokePrepared(operation: String, arguments: JSONObject,
                                        preparation: ConnectorWritePreparation, token: CancellationToken) = JSONObject()
            override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken) = JSONObject()
        }
        val definition = ConnectorDefinition(
            id = "device_data", name = "Device Data", version = "1", description = "Read device data",
            operations = listOf(ConnectorOperation("read", "Read data", JSONObject())),
            runtime = runtime, readPermissions = listOf("READ_DEVICE_DATA"),
        )
        val registry = ConnectorRegistry.createForTests(
            Preferences(), { permissionGranted }, ApprovalGate.INSTANCE,
        ).apply {
            register(definition)
            connect(definition.id)
        }
        permissionGranted = false

        val runtimePrompt = CoreAgentRuntime(emptyList(), emptyList(), emptyList(), emptyList(), registry).instructions()

        assertTrue(runtimePrompt.contains("Device Data: Read device data (permission revoked; reconnect in Connectors)"))
    }

    @Test fun smsRecipientHistoryIncludesOnlyUserMessageTurns() {
        val history = listOf(
            ConversationTurn("user", "Send a message to +15551234567"),
            ConversationTurn("assistant", "I can do that for +15557654321"),
            ConversationTurn.toolResult("tool-1", "sms_list_sms", "Message body contains +15559876543"),
            ConversationTurn.toolCalls("", listOf(ModelReply.Call("call-1", "sms_list_sms", emptyMap()))),
            ConversationTurn("user", "Try again with +15550001111"),
        )

        assertEquals(
            listOf("Send a message to +15551234567", "Try again with +15550001111"),
            CoreAgentRuntime.userMessagesFromHistory(history),
        )
    }

    @Test fun locationToolsAndUsageNoteAppearOnlyAfterMinimumPermissionConnectorConnects() {
        var coarseGranted = false
        val definition = LocationConnector.definition(FakeLocationGateway(), { coarseGranted }, { false })
        val registry = ConnectorRegistry.createForTests(
            Preferences(), { permission -> permission == android.Manifest.permission.ACCESS_COARSE_LOCATION && coarseGranted },
        ).apply { register(definition) }
        val disconnected = CoreAgentRuntime(emptyList(), emptyList(), emptyList(), emptyList(), registry)
        val locationTool = CoreConnectorTool.toolName(LocationConnector.ID, LocationConnector.GET_CURRENT_LOCATION)
        assertFalse(locationTool in disconnected.createTools().names())
        assertFalse(disconnected.instructions().contains(definition.usageNote()))
        assertTrue(disconnected.instructions().contains("Location"))

        coarseGranted = true
        registry.connect(LocationConnector.ID)
        val tools = registry.connectedDefinitions().flatMap { current ->
            current.operations.map { CoreConnectorTool(registry, current, it) }
        }
        val connected = CoreAgentRuntime(emptyList(), emptyList(), tools, emptyList(), registry)
        assertTrue(locationTool in connected.createTools().names())
        assertTrue(connected.instructions().contains(definition.usageNote()))
    }

    private fun bundledSkills() = listOf(
        skill("com.jarvys.skill-creator", listOf("ls", "read", "write", "edit", "read_skill")),
        skill("com.jarvys.android-navigation", listOf("click", "swipe", "wait_for_delay")),
    )

    private fun skill(id: String, allowedTools: List<String>) = SkillEntry(
        metadata = SkillMetadata(id, id, "Test bundled skill", 1, allowedTools, emptyList()),
        body = "instructions",
        source = SkillSource.BUNDLED,
        enabled = true,
        usageCount = 0,
    )

    private fun connectedCalendarRegistry(): ConnectorRegistry {
        val runtime = object : ConnectorRuntime {
            override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
            override fun disconnect() = Unit
            override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken) =
                ConnectorWritePreparation(ApprovalSummary("Write", emptyList()), arguments)
            override fun invokePrepared(operation: String, arguments: JSONObject,
                                        preparation: ConnectorWritePreparation, token: CancellationToken) = JSONObject()
            override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken) = JSONObject()
        }
        val operation = ConnectorOperation(
            name = CalendarConnector.SEARCH,
            description = "Search calendar",
            inputSchema = JSONObject(),
            displayLabel = "Search Calendar Events",
            requiredPermissions = listOf("READ_CALENDAR"),
        )
        val definition = ConnectorDefinition(
            id = CalendarConnector.ID,
            name = "Calendar",
            version = "1",
            description = "Calendar",
            operations = listOf(operation),
            runtime = runtime,
            readPermissions = listOf("READ_CALENDAR"),
            usageNoteProvider = { "calendar-usage-note" },
        )
        return ConnectorRegistry.createForTests(
            Preferences(),
            permissionGranted = { true },
            approvalGate = ApprovalGate.INSTANCE,
        ).apply {
            register(definition)
            connect(definition.id)
        }
    }
}
