package com.jarvys.agent.flavor

import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.InMemoryConnectorAutonomyStore
import com.jarvys.agent.linux.LinuxExecAutonomy
import org.junit.Assert.assertTrue
import org.junit.Test

/** Policy-only resume checks; no jobs, command process, account or network is started. */
class FlavorLinuxResumePolicyTest {
    @Test fun explicitCodingAllowMayResumeEvenWhenPrincipalIsDenied() {
        val autonomy = LinuxExecAutonomy(InMemoryConnectorAutonomyStore(), { true })
        autonomy.setPolicyFromUi(AutonomyPolicy.DENY)
        autonomy.setProjectPolicyFromUi(AutonomyPolicy.ALLOW)
        FlavorLinuxTools.validateProfileResume(listOf(CodingExecutionTools.EXEC), autonomy)
    }

    @Test fun inheritedPrincipalDenyBlocksExecutionResumeButNotRetainedJobInspection() {
        val autonomy = LinuxExecAutonomy(InMemoryConnectorAutonomyStore(), { true })
        autonomy.setPolicyFromUi(AutonomyPolicy.DENY)
        assertTrue(runCatching {
            FlavorLinuxTools.validateProfileResume(listOf(CodingExecutionTools.EXEC), autonomy)
        }.isFailure)
        FlavorLinuxTools.validateProfileResume(listOf(CodingExecutionTools.JOBS, CodingExecutionTools.STATUS), autonomy)
    }

    @Test fun explicitCodingDenyBlocksPrincipalAllowWhileAskDefersToActionApproval() {
        val autonomy = LinuxExecAutonomy(InMemoryConnectorAutonomyStore(), { true })
        autonomy.setPolicyFromUi(AutonomyPolicy.ALLOW)
        autonomy.setProjectPolicyFromUi(AutonomyPolicy.DENY)
        assertTrue(runCatching {
            FlavorLinuxTools.validateProfileResume(listOf(CodingExecutionTools.EXEC), autonomy)
        }.isFailure)
        autonomy.setProjectPolicyFromUi(AutonomyPolicy.ASK)
        FlavorLinuxTools.validateProfileResume(listOf(CodingExecutionTools.EXEC), autonomy)
    }
}
