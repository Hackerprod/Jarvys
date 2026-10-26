package com.jarvys.agent

import com.jarvys.agent.connectors.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.function.Supplier

class ConnectorScheduledReadReceiptTest {
    private class Fixture {
        var reads = 0
        val policy = InMemoryConnectorAutonomyStore()
        val operation = ConnectorOperation("read", "Read", JSONObject(), write = false)
        val runtime = object : ConnectorRuntime {
            override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
            override fun disconnect() = Unit
            override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation = error("Read only")
            override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken): JSONObject {
                reads++; return JSONObject().put("items", "read data")
            }
        }
        val definition = ConnectorDefinition("read_fixture", "Read", "1", "Read", operations = listOf(operation), runtime = runtime)
        val registry = ConnectorRegistry.createForTestsWithAutonomy(object : ConnectorConnectionPreferences {
            var connected = false
            override fun isConnected(id: String) = connected
            override fun setConnected(id: String, connected: Boolean) { this.connected = connected }
        }, { true }, ApprovalGate.INSTANCE, policy, NoopAutonomyActionNotifier)
        val tool: CoreConnectorTool
        init { registry.register(definition); registry.connect(definition.id); tool = CoreConnectorTool(registry, definition, operation) }
    }

    @Test fun queuedAskReadDoesNotPretendReadCompleted() {
        val f = Fixture(); f.policy.setPolicy(f.definition.id, f.operation.name, AutonomyPolicy.ASK)
        val invocation = EffectInvocation { fail("Read must not admit WRITE") }
        var queued = 0
        val result = f.tool.executeScheduledTask(emptyMap(), CancellationToken.uncancellable(), false,
            Supplier { queued++; JSONObject().put("status", "pending_action") }, invocation)
        assertEquals(1, queued)
        assertEquals(0, f.reads)
        assertEquals(EffectInvocation.Outcome.NOT_EXECUTED, result.effectReceipt.outcome)
        assertEquals(EffectInvocation.Authorization.ASK_PENDING, result.effectReceipt.admission.authorization)
        assertFalse(invocation.ownsReadCompletion(result.administrativeReceipt))
        assertFalse(invocation.admitted())
    }

    @Test fun actualAllowedReadHasOnlyOwnedAdministrativeCompletion() {
        val f = Fixture(); f.policy.setPolicy(f.definition.id, f.operation.name, AutonomyPolicy.ALLOW)
        val invocation = EffectInvocation { fail("Read must not admit WRITE") }
        val result = f.tool.executeScheduledTask(emptyMap(), CancellationToken.uncancellable(), false, null, invocation)
        assertEquals(1, f.reads)
        assertNull(result.effectReceipt)
        assertTrue(invocation.ownsReadCompletion(result.administrativeReceipt))
        assertFalse(invocation.admitted())
    }
}
