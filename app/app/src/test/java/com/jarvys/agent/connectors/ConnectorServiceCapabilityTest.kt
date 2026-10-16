package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectorServiceCapabilityTest {
    private class Runtime : ConnectorRuntime {
        var effects = 0
        override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
        override fun disconnect() = Unit
        override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken) =
            ConnectorWritePreparation(ApprovalSummary("Permanent action", listOf("Exact account and targets"), allowAlwaysAvailable = true), arguments)
        override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken): JSONObject { effects++; return JSONObject() }
    }
    @Test fun unavailableServiceCapabilityBlocksBeforeApprovalAndBeforeEffect() {
        var scope = false; var shown = 0
        val runtime = Runtime(); val op = ConnectorOperation("manage", "Manage", JSONObject(), write = true)
        val definition = ConnectorDefinition("test", "Test", "1", "Test", operations = listOf(op), runtime = runtime, operationAccessGranted = { scope })
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { true }, rejectingApprovalGate { shown++ })
        registry.register(definition); registry.connect("test")
        registry.setAutonomyPolicy(definition, op, AutonomyPolicy.ASK)
        assertTrue(runCatching { registry.invoke(definition, op, JSONObject(), CancellationToken.uncancellable()) }.isFailure)
        assertEquals(0, shown); assertEquals(0, runtime.effects)
        // Choosing a policy never grants the provider capability it still needs.
        registry.setAutonomyPolicy(definition, op, AutonomyPolicy.ALLOW)
        assertEquals(AutonomyPolicy.ALLOW, registry.configuredAutonomyPolicy(definition, op))
        assertTrue(runCatching { registry.invoke(definition, op, JSONObject(), CancellationToken.uncancellable()) }.isFailure)
        assertEquals(0, shown); assertEquals(0, runtime.effects)
        registry.setAutonomyPolicy(definition, op, AutonomyPolicy.ASK)
        scope = true
        assertTrue(runCatching { registry.invoke(definition, op, JSONObject(), CancellationToken.uncancellable()) }.isFailure)
        assertEquals(1, shown); assertEquals(0, runtime.effects)
    }
    @Test fun staleOperationCannotDowngradeACatalogWriteOrBypassSavedAsk() {
        val runtime = Runtime(); val op = ConnectorOperation("delete", "Delete", JSONObject(), write = true, autonomyAllowed = false)
        val definition = ConnectorDefinition("test", "Test", "1", "Test", operations = listOf(op), runtime = runtime)
        var summary: ApprovalSummary? = null
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { true }, rejectingApprovalGate { summary = it })
        registry.register(definition); registry.connect("test")
        registry.setAutonomyPolicy(definition, op, AutonomyPolicy.ASK)
        val stale = op.copy(write = false, autonomyAllowed = true)
        assertTrue(runCatching { registry.invoke(definition, stale, JSONObject(), CancellationToken.uncancellable()) }.isFailure)
        assertNotNull(summary); assertTrue(summary!!.allowAlwaysAvailable); assertEquals(0, runtime.effects)
        assertEquals("Permanent action", summary!!.title)
        assertEquals(definition.id, summary!!.autonomyConnectorId)
        assertEquals(op.name, summary!!.autonomyOperationName)
        assertEquals(AutonomyPolicy.ASK, registry.configuredAutonomyPolicy(definition, op))
    }
    @Test fun removedOperationCannotInvokeItsOldRuntimeHandle() {
        val runtime = Runtime(); val definition = ConnectorDefinition("test", "Test", "1", "Test", runtime = runtime)
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { true })
        registry.register(definition); registry.connect("test")
        assertTrue(runCatching { registry.invoke(definition, ConnectorOperation("old", "Old", JSONObject()), JSONObject(), CancellationToken.uncancellable()) }.isFailure)
        assertEquals(0, runtime.effects)
    }

    @Test fun failedPolicyStoreKeepsTheDisplayedChoiceRestricted() {
        val store = object : ConnectorAutonomyStore by InMemoryConnectorAutonomyStore() {
            override fun policy(connectorId: String, operationName: String): AutonomyPolicy = error("fixture store failure")
            override fun unavailablePolicy(): AutonomyPolicy = AutonomyPolicy.ASK
        }
        val operation = ConnectorOperation("read", "Read", JSONObject())
        val definition = ConnectorDefinition("test", "Test", "1", "Test", operations = listOf(operation))
        val registry = ConnectorRegistry.createForTestsWithAutonomy(
            FakeConnectorPreferences(), { true }, ApprovalGate.INSTANCE, store, NoopAutonomyActionNotifier,
        )
        registry.register(definition)
        assertEquals(AutonomyPolicy.ASK, registry.configuredAutonomyPolicy(definition, operation))
    }
}
