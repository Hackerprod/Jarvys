package com.jarvys.agent.connectors

internal class FakeConnectorPreferences : ConnectorConnectionPreferences {
    private val connected = mutableMapOf<String, Boolean>()
    override fun isConnected(id: String): Boolean = connected[id] == true
    override fun setConnected(id: String, connected: Boolean) { this.connected[id] = connected }
}

internal fun rejectingApprovalGate(onSummary: (ApprovalSummary) -> Unit = {}): ApprovalGate {
    lateinit var gate: ApprovalGate
    gate = ApprovalGate(1_000, object : ApprovalPresenter {
        override fun show(id: String, summary: ApprovalSummary) {
            onSummary(summary)
            gate.resolve(id, ApprovalDecision.DENIED)
        }

        override fun update(id: String, decision: ApprovalDecision) = Unit
    })
    return gate
}
