package com.jarvys.agent.connectors

import com.jarvys.agent.R

enum class ApprovalOutcomeTone { SUCCESS, FAILURE, WARNING, NEUTRAL }

data class ApprovalStatusPresentation(val labelResourceId: Int, val tone: ApprovalOutcomeTone)

fun compactApprovalSummary(title: String, lines: List<String>): String {
    val recipient = lines.firstNotNullOfOrNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) null else {
            val label = line.substring(0, separator).trim()
            if (label.equals("to", ignoreCase = true) || label.equals("para", ignoreCase = true)) {
                line.substring(separator + 1).trim().takeIf(String::isNotEmpty)
            } else null
        }
    }
    val detail = recipient ?: lines.firstOrNull { it.isNotBlank() }?.take(56)
    return if (detail.isNullOrBlank()) title else "$title · $detail"
}

fun approvalPrimaryActionResourceId(permissionRequired: Boolean): Int =
    if (permissionRequired) R.string.approval_approve_grant_permission else R.string.approval_approve

fun approvalStatusPresentation(status: String): ApprovalStatusPresentation = when (status) {
    "APPROVED" -> ApprovalStatusPresentation(R.string.approval_approved, ApprovalOutcomeTone.SUCCESS)
    "APPROVED_ALLOW_ALWAYS" -> ApprovalStatusPresentation(R.string.approval_allow_enabled, ApprovalOutcomeTone.SUCCESS)
    "APPROVED_ALLOW_FAILED" -> ApprovalStatusPresentation(R.string.approval_allow_not_enabled, ApprovalOutcomeTone.WARNING)
    "DENIED" -> ApprovalStatusPresentation(R.string.approval_rejected, ApprovalOutcomeTone.FAILURE)
    "PERMISSION_DENIED" -> ApprovalStatusPresentation(R.string.approval_permission_denied, ApprovalOutcomeTone.FAILURE)
    "PERMISSION_FALLBACK_LAUNCHED" -> ApprovalStatusPresentation(R.string.approval_dialer_opened, ApprovalOutcomeTone.WARNING)
    "ACTION_FAILED" -> ApprovalStatusPresentation(R.string.approval_system_app_failed, ApprovalOutcomeTone.FAILURE)
    "EXPIRED" -> ApprovalStatusPresentation(R.string.approval_expired, ApprovalOutcomeTone.NEUTRAL)
    else -> ApprovalStatusPresentation(R.string.approval_cancelled, ApprovalOutcomeTone.NEUTRAL)
}
