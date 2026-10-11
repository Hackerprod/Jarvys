package com.jarvys.agent.connectors

/** Applies the explicit card action and reports failure without blocking approval of this one write. */
internal fun approveAndAllowAlways(
    registry: ConnectorRegistry,
    connectorId: String?,
    operationName: String?,
    onStatusDetail: (ConnectorUiText) -> Unit,
    onResolve: (ApprovalDecision) -> Unit,
    approvalId: String? = null,
) {
    approveAndAllowAlways({ if (approvalId != null) {
        val result = runCatching { ApprovalGate.INSTANCE.enableAlwaysFromUi(approvalId) }
        if (result.getOrNull() == true) null
        else ConnectorUiText(fallback = result.exceptionOrNull()?.message ?: "This approval cannot change its policy")
    } else if (connectorId != null && operationName != null) {
        registry.tryEnableAutonomyFromApprovalUi(connectorId, operationName)
    } else {
        ConnectorUiText(com.jarvys.agent.R.string.approval_autonomy_operation_unavailable,
            fallback = "The operation could not be identified.")
    } }, onStatusDetail, onResolve)
}

internal fun approveAndAllowAlways(
    enable: () -> ConnectorUiText?,
    onStatusDetail: (ConnectorUiText) -> Unit,
    onResolve: (ApprovalDecision) -> Unit,
) {
    val reason = enable()
    if (reason == null) {
        onStatusDetail(ConnectorUiText(com.jarvys.agent.R.string.approval_allow_enabled_detail,
            fallback = "Allow mode is enabled for this operation."))
        onResolve(ApprovalDecision.APPROVED_ALLOW_ALWAYS)
    } else {
        onStatusDetail(ConnectorUiText(com.jarvys.agent.R.string.approval_allow_failed_detail,
            listOf(reason), "This action was approved, but Allow mode was not enabled: ${reason.fallback}"))
        onResolve(ApprovalDecision.APPROVED_ALLOW_FAILED)
    }
}
