package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreConnectorTool
import com.jarvys.agent.CoreToolRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorRegistryTest {
    private class Preferences : ConnectorConnectionPreferences {
        private val values = mutableMapOf<String, Boolean>()
        override fun isConnected(id: String) = values[id] == true
        override fun setConnected(id: String, connected: Boolean) { values[id] = connected }
    }
    private class Runtime : ConnectorRuntime {
        override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
        override fun disconnect() = Unit
        override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken) =
            ConnectorWritePreparation(ApprovalSummary("Write", emptyList()), arguments)
        override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken) = JSONObject().put("ok", true)
    }

    @Test fun connectionStateTransitionsAndToolsAreHiddenUntilConnected() {
        var readGranted = false
        val registry = ConnectorRegistry.createForTests(Preferences(), permissionGranted = { it == "READ_CALENDAR" && readGranted })
        val definition = ConnectorDefinition(
            id = "calendar", name = "Calendar", version = "1", description = "Calendar",
            readPermissions = listOf("READ_CALENDAR"), runtime = Runtime(),
            operations = listOf(ConnectorOperation("search", "Search", JSONObject())),
        )
        registry.register(definition)
        assertEquals(ConnectorState.DISCONNECTED, registry.state("calendar"))
        assertTrue(registry.connectedDefinitions().isEmpty())

        readGranted = true
        registry.connect("calendar")
        assertEquals(ConnectorState.CONNECTED, registry.state("calendar"))
        assertEquals(1, registry.connectedDefinitions().single().operations.size)

        readGranted = false
        registry.refreshStates()
        assertEquals(ConnectorState.PERMISSION_REVOKED, registry.state("calendar"))
        assertTrue(registry.connectedDefinitions().isEmpty())
        val tool = CoreConnectorTool(registry, definition, definition.operations.single())
        val result = CoreToolRegistry(listOf(tool)).invoke(
            "calendar_search", emptyMap(), CancellationToken.uncancellable(),
        )
        assertFalse(result.success)
        assertEquals("El permiso de Calendar fue revocado; volvé a conectarlo en Conectores", result.content)
    }

    @Test fun bootstrapReinstallsConnectorDefinitionsAroundPersistedConnectionState() {
        val preferences = Preferences().apply { setConnected("calendar", true) }
        val registry = ConnectorRegistry.createForTests(preferences, permissionGranted = { it == "READ_CALENDAR" })
        val definition = ConnectorDefinition(
            id = "calendar", name = "Calendar", version = "1", description = "Calendar",
            readPermissions = listOf("READ_CALENDAR"), runtime = Runtime(),
            operations = listOf(ConnectorOperation("search", "Search", JSONObject())),
        )

        registry.registerBuiltInDefinitions(listOf(definition))
        registry.registerBuiltInDefinitions(listOf(definition))

        assertEquals(1, registry.definitions.value.count { it.id == "calendar" })
        assertEquals(ConnectorState.CONNECTED, registry.state("calendar"))
        assertEquals("calendar", registry.connectedDefinitions().single().id)
    }
}
