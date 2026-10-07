package com.jarvys.agent.connectors

import com.jarvys.agent.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalCardPresentationTest {
    @Test fun resolvedSmsCardUsesOneLineDestinationSummary() {
        assertEquals(
            "Send SMS · 9714842828",
            compactApprovalSummary("Send SMS", listOf("To: 9714842828", "Message: Are you feeling better?")),
        )
        assertEquals("Create contact · Ana", compactApprovalSummary("Create contact", listOf("Ana", "Phone: 5550100")))
    }

    @Test fun primaryButtonChangesToExplicitPermissionGrantActionOnlyWhenNeeded() {
        assertEquals(R.string.approval_approve, approvalPrimaryActionResourceId(permissionRequired = false))
        assertEquals(R.string.approval_approve_grant_permission, approvalPrimaryActionResourceId(permissionRequired = true))
    }

    @Test fun resolvedStatusesHaveDifferentSemanticTones() {
        assertEquals(ApprovalOutcomeTone.SUCCESS, approvalStatusPresentation("APPROVED").tone)
        assertEquals(ApprovalOutcomeTone.FAILURE, approvalStatusPresentation("DENIED").tone)
        assertEquals(ApprovalOutcomeTone.WARNING, approvalStatusPresentation("PERMISSION_FALLBACK_LAUNCHED").tone)
        assertTrue(approvalStatusPresentation("EXPIRED").labelResourceId != approvalStatusPresentation("CANCELLED").labelResourceId)
    }
}
