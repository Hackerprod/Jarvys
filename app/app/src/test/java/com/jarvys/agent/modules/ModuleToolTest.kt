package com.jarvys.agent.modules

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.ModuleTool
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleToolTest {
    @Test fun validatesArgumentsUsesAccessDefaultsAndDefersScheduledModuleExecution() {
        var executions = 0
        var receivedArguments: Map<String, Any?>? = null
        val runtime = ModuleRuntime { request, _, _ ->
            executions++
            receivedArguments = request.arguments
            CoreToolResult.success("ran")
        }
        val moduleRegistry = ModuleRegistry.fromManifests(listOf(manifest()))
        val connectorRegistry = ConnectorRegistry.createForTests(
            Preferences(), { true },
        )
        val definition = moduleRegistry.installConnectorDefinitions(connectorRegistry, runtime).single()
        connectorRegistry.connect(definition.id)
        val tool = moduleRegistry.tools(connectorRegistry, runtime).single { it.declaration().name == "read_items" }

        val invalid = tool.execute(mapOf("extra" to "unexpected"), CancellationToken.uncancellable())
        assertFalse(invalid.success)
        assertEquals(0, executions)

        val valid = tool.execute(mapOf("count" to "3"), CancellationToken.uncancellable())
        assertTrue(valid.success)
        assertEquals("ran", valid.content)
        assertEquals(3, receivedArguments?.get("count"))
        assertEquals(com.jarvys.agent.connectors.AutonomyPolicy.ALLOW, tool.effectiveAutonomyPolicy())

        val scheduled = tool.executeScheduledTask(
            mapOf("count" to 3), CancellationToken.uncancellable(),
            approvedPendingAction = false, deferAskWrite = null,
        )
        assertFalse(scheduled.success)
        assertTrue(scheduled.content.contains("no resumable pending action"))
        assertEquals(1, executions)

        val approvedInBackground = tool.executeScheduledTask(
            mapOf("count" to 3), CancellationToken.uncancellable(),
            approvedPendingAction = true, deferAskWrite = null,
        )
        assertEquals(ModuleTool.VISIBLE_APP_REQUIRED_STATUS, JSONObject(approvedInBackground.content).optString("status"))
        assertEquals(1, executions)
    }

    @Test fun writeAccessIsSequentialAndDefaultsToAsk() {
        val runtime = ModuleRuntime { _, _, _ -> CoreToolResult.success("ran") }
        val modules = ModuleRegistry.fromManifests(listOf(manifest()))
        val registry = ConnectorRegistry.createForTests(Preferences(), { true })
        val definition = modules.installConnectorDefinitions(registry, runtime).single()
        registry.connect(definition.id)
        val tool = modules.tools(registry, runtime).single { it.declaration().name == "write_items" }

        assertEquals(com.jarvys.agent.CoreTool.ExecutionMode.SEQUENTIAL, tool.executionMode())
        assertEquals(com.jarvys.agent.CoreTool.UnattendedAccess.WRITE, tool.unattendedAccess())
        assertEquals(com.jarvys.agent.connectors.AutonomyPolicy.ASK, tool.effectiveAutonomyPolicy())
    }

    private class Preferences : ConnectorConnectionPreferences {
        private val values = mutableMapOf<String, Boolean>()
        override fun isConnected(id: String): Boolean = values[id] == true
        override fun setConnected(id: String, connected: Boolean) { values[id] = connected }
    }

    private fun manifest() = """{"schemaVersion":1,"id":"com.test.module.tool-test","version":"1.0.0","name":"Tool test",
        "artifactTypes":[],"skills":[],"capabilities":[],"tools":[
          {"name":"read_items","description":"Read items","inputSchema":{"type":"object",
            "properties":{"count":{"type":"integer"}},"required":["count"],"additionalProperties":false},
            "access":"read","handler":"logic/tools.js#readItems"},
          {"name":"write_items","description":"Write items","inputSchema":{"type":"object",
            "properties":{"name":{"type":"string"}},"required":["name"],"additionalProperties":false},
            "access":"write","handler":"logic/tools.js#writeItems"}
        ]}"""
}
