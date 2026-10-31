package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreConnectorTool
import com.jarvys.agent.EffectInvocation
import org.junit.Assert.*
import org.junit.Test

/** Real phone adapter + registry; gateways are the only fakes, no Android effects. */
class ConnectorEffectAdmissionTest {
    private class Fixture {
        val policies = InMemoryConnectorAutonomyStore()
        var effects = 0
        var admitted = false
        var failCall = false
        var approveAlways = false
        var deny = false
        val gate: ApprovalGate = ApprovalGate(1000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                if (deny) gate.resolve(id, ApprovalDecision.DENIED)
                else if (approveAlways) { summary.enableAlways?.invoke(); gate.resolve(id, ApprovalDecision.APPROVED_ALLOW_ALWAYS) }
                else gate.resolve(id, ApprovalDecision.APPROVED)
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        val definition = DialerConnector.definition(object : PhoneCallGateway {
            override fun placeCall(phoneNumber: String) { assertTrue(admitted); effects++; if (failCall) throw SecurityException("gateway failure after dispatch") }
            override fun openDialer(phoneNumber: String) { assertTrue(admitted); effects++ }
        }, { true }, { false })
        val operation = definition.operations.single { it.name == DialerConnector.PLACE_CALL }
        val registry = ConnectorRegistry.createForTestsWithAutonomy(object : ConnectorConnectionPreferences {
            var connected = false
            override fun isConnected(id: String) = connected
            override fun setConnected(id: String, connected: Boolean) { this.connected = connected }
        }, { true }, gate, policies, NoopAutonomyActionNotifier)
        val tool: CoreConnectorTool
        init { registry.register(definition); registry.connect(definition.id); tool = CoreConnectorTool(registry, definition, operation) }
        fun policy(value: AutonomyPolicy) = policies.setPolicy(definition.id, operation.name, value)
        fun invoke(call: EffectInvocation) = tool.execute(mapOf("phoneNumber" to "+15551234567"), CancellationToken.uncancellable(), null, call)
    }

    @Test fun allowPersistsBeforeRealGatewayAndReturnsLocallyTypedReceipt() {
        val f = Fixture()
        val call = EffectInvocation { assertEquals(EffectInvocation.Authorization.ALLOW, it.authorization); f.admitted = true }
        val result = f.invoke(call)
        assertEquals(1, f.effects)
        assertEquals(EffectInvocation.Outcome.OK, call.resolve(result, true).outcome)
    }

    @Test fun approvedAskUsesExactApprovedSnapshotNotPending() {
        val f = Fixture(); f.policy(AutonomyPolicy.ASK)
        val call = EffectInvocation { assertEquals(EffectInvocation.Authorization.ASK_APPROVED, it.authorization); f.admitted = true }
        assertEquals(EffectInvocation.Outcome.OK, f.invoke(call).effectReceipt.outcome)
        assertEquals(1, f.effects)
    }

    @Test fun allowAlwaysUsesSavedAllowReceipt() {
        val f = Fixture(); f.policy(AutonomyPolicy.ASK); f.approveAlways = true
        val call = EffectInvocation { assertEquals(EffectInvocation.Authorization.ALLOW, it.authorization); f.admitted = true }
        f.invoke(call)
        assertEquals(1, f.effects)
    }

    @Test fun failingDurableCallbackCannotDispatchOrFallback() {
        val f = Fixture()
        val call = EffectInvocation { throw IllegalArgumentException("durable admission failed") }
        assertTrue(runCatching { f.invoke(call) }.isFailure)
        assertEquals(0, f.effects)
        assertFalse(call.admitted())
    }

    @Test fun revocationDuringDurableCallbackIsRevalidatedBeforeDispatch() {
        val f = Fixture()
        val call = EffectInvocation { f.admitted = true; f.policy(AutonomyPolicy.DENY) }
        assertTrue(runCatching { f.invoke(call) }.isFailure)
        assertEquals(0, f.effects)
        assertEquals(EffectInvocation.Outcome.UNCERTAIN, call.resolveFailure(true).outcome)
    }

    @Test fun deniedPolicyRecordsDecisionWithoutDispatch() {
        val f = Fixture(); f.policy(AutonomyPolicy.DENY)
        val call = EffectInvocation { fail("DENY cannot persist started") }
        assertTrue(runCatching { f.invoke(call) }.isFailure)
        assertEquals(EffectInvocation.Authorization.DENY, call.decision().authorization)
        assertEquals(0, f.effects)
    }
    @Test fun explicitUserDenialIsDistinctFromPendingAsk() {
        val f = Fixture(); f.policy(AutonomyPolicy.ASK); f.deny = true
        val call = EffectInvocation { fail("User denial cannot admit") }
        assertTrue(runCatching { f.invoke(call) }.isFailure)
        assertEquals(EffectInvocation.Authorization.DENY, call.decision().authorization)
        assertTrue(call.decision().origin.endsWith(":user_denied"))
        assertEquals(0, f.effects)
    }
    @Test fun partialCallThenFallbackCannotOverwriteUncertaintyAndAdmissionIsIdempotent() {
        val f = Fixture(); f.failCall = true
        var persisted = 0
        val call = EffectInvocation { persisted++; f.admitted = true }
        val result = f.invoke(call)
        assertEquals(2, f.effects)
        assertEquals(1, persisted)
        assertEquals(EffectInvocation.Outcome.UNCERTAIN, call.resolve(result, true).outcome)
    }
    @Test fun connectionAbaDuringDurableAdmissionCannotUseNewReadyAsOldAuthority() {
        val f = Fixture(); var observed: ConnectionStateCoordinator.Observation? = null
        val call = EffectInvocation {
            observed = it.connectionObservation
            f.registry.disconnect(f.definition.id)
            f.registry.connect(f.definition.id)
            f.admitted = true
        }
        assertTrue(runCatching { f.invoke(call) }.isFailure)
        assertEquals(ConnectorState.CONNECTED, f.registry.state(f.definition.id))
        assertEquals(0, f.effects); assertEquals(f.definition.id, observed!!.connectorId); assertTrue(observed!!.selected)
        assertEquals(observed, call.decision().connectionObservation)
    }

    @Test fun unrelatedConnectorChangeDoesNotInvalidatePinnedConnectionObservation() {
        val f = Fixture(); var observed: ConnectionStateCoordinator.Observation? = null
        val other = ConnectorDefinition("unrelated", "Unrelated", "1", "Unrelated local choice")
        f.registry.register(other)
        val call = EffectInvocation {
            observed = it.connectionObservation
            f.registry.connect(other.id)
            f.admitted = true
        }
        assertEquals(EffectInvocation.Outcome.OK, f.invoke(call).effectReceipt.outcome)
        assertEquals(1, f.effects); assertEquals(f.definition.id, observed!!.connectorId)
        assertEquals(observed, call.decision().connectionObservation)
    }

    @Test fun oldToolCannotAdoptReplacementDefinitionWithSameIdAndOperationName() {
        val f = Fixture()
        var replacementEffects = 0
        var serviceChecks = 0
        val replacement = DialerConnector.definition(object : PhoneCallGateway {
            override fun placeCall(phoneNumber: String) { replacementEffects++ }
            override fun openDialer(phoneNumber: String) { replacementEffects++ }
        }, { true }, { false }).copy(operationAccessGranted = { serviceChecks++; true })
        f.registry.unregister(f.definition.id)
        f.registry.register(replacement)
        f.registry.connect(replacement.id)
        val checksBeforeInvoke = serviceChecks
        val rejected = EffectInvocation { fail("Stale tool cannot admit an effect") }
        assertTrue(runCatching { f.invoke(rejected) }.isFailure)
        assertFalse(rejected.admitted())
        assertEquals(checksBeforeInvoke, serviceChecks)
        assertEquals(0, f.effects)
        assertEquals(0, replacementEffects)

        val fresh = CoreConnectorTool(f.registry, replacement,
            replacement.operations.single { it.name == DialerConnector.PLACE_CALL })
        val accepted = EffectInvocation { }
        fresh.execute(mapOf("phoneNumber" to "+15551234567"), CancellationToken.uncancellable(), null, accepted)
        assertTrue(accepted.admitted())
        assertEquals(1, replacementEffects)
    }

    @Test fun removedOrReplacedOperationCannotBeAdoptedByOldToolOnSameDefinition() {
        val f = Fixture()
        val operations = f.definition.operations.toMutableList()
        val definition = f.definition.copy(operations = operations)
        f.registry.unregister(f.definition.id)
        f.registry.register(definition)
        f.registry.connect(definition.id)
        val oldOperation = operations.single { it.name == DialerConnector.PLACE_CALL }
        val oldTool = CoreConnectorTool(f.registry, definition, oldOperation)
        operations.remove(oldOperation)
        fun rejectOldTool() {
            val call = EffectInvocation { fail("Removed or replaced operation cannot admit") }
            assertTrue(runCatching {
                oldTool.execute(mapOf("phoneNumber" to "+15551234567"), CancellationToken.uncancellable(), null, call)
            }.isFailure)
            assertFalse(call.admitted())
            assertEquals(0, f.effects)
        }
        rejectOldTool()
        val replacement = oldOperation.copy()
        operations.add(replacement)
        rejectOldTool()
        val fresh = CoreConnectorTool(f.registry, definition, replacement)
        val accepted = EffectInvocation { f.admitted = true }
        fresh.execute(mapOf("phoneNumber" to "+15551234567"), CancellationToken.uncancellable(), null, accepted)
        assertTrue(accepted.admitted())
        assertEquals(1, f.effects)
    }

}
