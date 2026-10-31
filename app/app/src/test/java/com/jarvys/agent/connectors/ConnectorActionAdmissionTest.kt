package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** UX44 exact dispatch contracts, without Android/provider effects. */
class ConnectorActionAdmissionTest {
    private class Fixture(write: Boolean = true, intent: Boolean = false) {
        val store = InMemoryConnectorAutonomyStore()
        var cards = 0
        var preparations = 0
        var executions = 0
        var launches = 0
        var onPrepare: () -> Unit = {}
        var onExecute: (ConnectorWritePreparation) -> Unit = {}
        var onCard: (String, ApprovalSummary) -> Unit = { id, _ -> gate.resolve(id, ApprovalDecision.APPROVED); Unit }
        val gate = ApprovalGate(1000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) { cards++; onCard(id, summary) }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        val operation = ConnectorOperation("action", "Exact action", JSONObject(), write = write)
        val runtime = object : ConnectorRuntime {
            override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
            override fun disconnect() = Unit
            override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation {
                preparations++; onPrepare()
                return ConnectorWritePreparation(ApprovalSummary("Action", listOf(arguments.toString()),
                    activityIntent = if (intent) ApprovalIntentSpec(ApprovalIntentKind.DIAL, "tel:12345") else null), arguments)
            }
            override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken): JSONObject {
                executions++; return JSONObject().put("ok", true)
            }
            override fun invokePrepared(operation: String, arguments: JSONObject, preparation: ConnectorWritePreparation, token: CancellationToken): JSONObject {
                onExecute(preparation)
                preparation.authorizeEffect?.invoke()
                return invoke(operation, arguments, token)
            }
        }
        val definition = ConnectorDefinition("test", "Test", "1", "Test", operations = listOf(operation), runtime = runtime)
        val registry = ConnectorRegistry.createForTestsWithAutonomy(object : ConnectorConnectionPreferences {
            var connected = false
            override fun isConnected(id: String) = connected
            override fun setConnected(id: String, connected: Boolean) { this.connected = connected }
        }, { true }, gate, store, object : AutonomyActionNotifier {
            override fun canPost() = false
            override fun missingRuntimePermission(): String? = "POST_NOTIFICATIONS"
            override fun post(record: AutonomyAuditRecord) { error("Notifications disabled") }
        }, launchIntent = { launches++ })
        init { registry.register(definition); registry.connect(definition.id) }
        fun policy(value: AutonomyPolicy) = store.setPolicy("test", "action", value)
        fun invoke(token: CancellationToken = CancellationToken.uncancellable()) = registry.invoke(definition, operation, JSONObject().put("target", "exact"), token)
    }

    @Test fun absentPolicyAllowsWithoutCardOrNotificationPrerequisite() {
        val f = Fixture(); val result = f.invoke()
        assertEquals(0, f.cards); assertEquals(1, f.executions)
        assertTrue(result.has("notificationWarning"))
        assertEquals(1, f.store.recentAudit("test", 10).size)
    }
    @Test fun denyBlocksPreparationAndDispatch() {
        val f = Fixture(); f.policy(AutonomyPolicy.DENY)
        assertTrue(runCatching { f.invoke() }.isFailure)
        assertEquals(0, f.preparations); assertEquals(0, f.executions); assertEquals(0, f.cards)
    }
    @Test fun readActionsHaveSameAskAndDenyChoices() {
        val f = Fixture(write = false); f.policy(AutonomyPolicy.ASK); f.invoke()
        assertEquals(1, f.cards); assertEquals(1, f.executions)
        f.policy(AutonomyPolicy.DENY); assertTrue(runCatching { f.invoke() }.isFailure)
        assertEquals(1, f.executions)
    }
    @Test fun revokeDuringAskPreventsDispatch() {
        val f = Fixture(); f.policy(AutonomyPolicy.ASK)
        f.onCard = { id, _ -> f.policy(AutonomyPolicy.DENY); f.gate.resolve(id, ApprovalDecision.APPROVED); Unit }
        assertTrue(runCatching { f.invoke() }.isFailure); assertEquals(0, f.executions)
    }
    @Test fun revokeDuringPreparationPreventsDispatch() {
        val f = Fixture(); f.onPrepare = { f.policy(AutonomyPolicy.DENY) }
        assertTrue(runCatching { f.invoke() }.isFailure); assertEquals(0, f.executions)
    }
    @Test fun repeatedEffectAdmissionRejectsPolicyAba() {
        val f = Fixture(); f.onExecute = { f.policy(AutonomyPolicy.DENY); f.policy(AutonomyPolicy.ALLOW) }
        assertTrue(runCatching { f.invoke() }.isFailure); assertEquals(0, f.executions)
    }
    @Test fun alwaysButtonBindsItsExactDurableReceipt() {
        val f = Fixture(); f.policy(AutonomyPolicy.ASK)
        f.onCard = { id, _ -> assertTrue(f.gate.enableAlwaysFromUi(id)); f.gate.resolve(id, ApprovalDecision.APPROVED_ALLOW_ALWAYS); Unit }
        f.invoke(); f.invoke(); assertEquals(1, f.cards); assertEquals(2, f.executions)
    }
    @Test fun unrelatedAllowAfterAlwaysDoesNotReuseApproval() {
        val f = Fixture(); f.policy(AutonomyPolicy.ASK)
        f.onCard = { id, _ -> f.gate.enableAlwaysFromUi(id); f.policy(AutonomyPolicy.DENY); f.policy(AutonomyPolicy.ALLOW)
            f.gate.resolve(id, ApprovalDecision.APPROVED_ALLOW_ALWAYS); Unit }
        assertTrue(runCatching { f.invoke() }.isFailure); assertEquals(0, f.executions)
    }
    @Test fun nativeIntentHasExactlyOneOwnerForAskAndAllow() {
        val f = Fixture(intent = true); f.invoke(); f.policy(AutonomyPolicy.ASK); f.invoke()
        assertEquals(2, f.launches); assertEquals(2, f.executions); assertEquals(1, f.cards)
    }
    @Test fun stopDuringPreparationPreventsAdmission() {
        val f = Fixture(); val token = CancellationToken.cancellable(); f.onPrepare = { token.cancel(); Unit }
        assertTrue(runCatching { f.invoke(token) }.isFailure); assertEquals(0, f.executions)
    }
}
