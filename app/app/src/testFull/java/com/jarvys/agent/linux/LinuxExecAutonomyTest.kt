package com.jarvys.agent.linux

import com.jarvys.agent.connectors.*
import org.junit.Assert.*
import org.junit.Test

class LinuxExecAutonomyTest {
    @Test fun maintenanceDefaultsAndSavedPoliciesAreIndependentAndRejectStaleSnapshots() {
        val store = InMemoryConnectorAutonomyStore()
        val autonomy = LinuxExecAutonomy(store, { false })
        val token = com.jarvys.agent.CancellationToken.uncancellable()
        for (operation in LinuxExecAutonomy.MAINTENANCE_OPERATIONS) {
            val fresh = autonomy.maintenanceAuthorization(operation)
            assertEquals(AutonomyPolicy.ALLOW, fresh.policy)
            assertEquals(ActionPolicyOrigin.CATALOG_DEFAULT, fresh.snapshot.origin)
            autonomy.admitMaintenance(fresh, false, token)
            for (choice in AutonomyPolicy.entries) {
                autonomy.setMaintenancePolicyFromUi(operation, choice)
                val current = autonomy.maintenanceAuthorization(operation)
                assertEquals(choice, current.policy)
                assertEquals(ActionPolicyOrigin.USER, current.snapshot.origin)
                assertEquals(choice, LinuxExecAutonomy(store, { false }).maintenanceAuthorization(operation).policy)
                assertEquals(choice != AutonomyPolicy.DENY,
                    runCatching { autonomy.admitMaintenance(current, choice == AutonomyPolicy.ASK, token) }.isSuccess)
            }
            assertTrue(runCatching { autonomy.admitMaintenance(fresh, false, token) }.isFailure)
        }
        assertEquals(AutonomyPolicy.ALLOW, autonomy.policy())
        assertEquals(AutonomyPolicy.ALLOW, autonomy.projectPolicy())
    }

    @Test fun uninstallOwnerAloneCanAdmitItsGenerationAndNeverChangesSavedModes() {
        val store = InMemoryConnectorAutonomyStore()
        val autonomy = LinuxExecAutonomy(store, { true })
        val token = com.jarvys.agent.CancellationToken.uncancellable()
        autonomy.setPolicyFromUi(AutonomyPolicy.DENY)
        autonomy.setProjectPolicyFromUi(AutonomyPolicy.ASK)
        autonomy.setMaintenancePolicyFromUi(LinuxExecAutonomy.SETUP_OPERATION_NAME, AutonomyPolicy.DENY)
        autonomy.setMaintenancePolicyFromUi(LinuxExecAutonomy.UNINSTALL_OPERATION_NAME, AutonomyPolicy.ASK)
        val prior = autonomy.maintenanceAuthorization(LinuxExecAutonomy.UNINSTALL_OPERATION_NAME)
        var leaked: (() -> Unit)? = null
        var capturedDuringRemoval: LinuxExecAutonomy.Authorization? = null
        autonomy.withAdmittedUninstall(prior, true, token) { admitEffect ->
            leaked = admitEffect
            admitEffect()
            val concurrent = autonomy.maintenanceAuthorization(LinuxExecAutonomy.UNINSTALL_OPERATION_NAME)
            capturedDuringRemoval = concurrent
            assertTrue(runCatching { autonomy.admitMaintenance(concurrent, true, token) }.isFailure)
            assertTrue(runCatching { autonomy.withAdmittedUninstall(concurrent, true, token) { } }.isFailure)
            autonomy.setMaintenancePolicyFromUi(LinuxExecAutonomy.UNINSTALL_OPERATION_NAME, AutonomyPolicy.DENY)
            assertTrue(runCatching { admitEffect() }.isFailure)
            autonomy.setMaintenancePolicyFromUi(LinuxExecAutonomy.UNINSTALL_OPERATION_NAME, AutonomyPolicy.ASK)
            assertTrue(runCatching { admitEffect() }.isFailure)
            // This fresh policy snapshot can become stale only through closing the environment owner.
            capturedDuringRemoval = autonomy.maintenanceAuthorization(LinuxExecAutonomy.UNINSTALL_OPERATION_NAME)
        }
        assertTrue(runCatching { requireNotNull(leaked).invoke() }.isFailure)
        assertTrue(runCatching { autonomy.admitMaintenance(requireNotNull(capturedDuringRemoval), true, token) }.isFailure)
        assertTrue(runCatching { autonomy.admitMaintenance(prior, true, token) }.isFailure)
        assertEquals(AutonomyPolicy.DENY, autonomy.policy())
        assertEquals(AutonomyPolicy.ASK, autonomy.projectPolicy())
        assertEquals(AutonomyPolicy.DENY, autonomy.maintenanceAuthorization(LinuxExecAutonomy.SETUP_OPERATION_NAME).policy)
        assertEquals(AutonomyPolicy.ASK, autonomy.maintenanceAuthorization(LinuxExecAutonomy.UNINSTALL_OPERATION_NAME).policy)
        autonomy.admitMaintenance(autonomy.maintenanceAuthorization(LinuxExecAutonomy.UNINSTALL_OPERATION_NAME), true, token)
    }

    @Test fun defaultsAllowAndExplicitChoicesRemainIndependentOfAvailability() {
        val store = InMemoryConnectorAutonomyStore(); val autonomy = LinuxExecAutonomy(store, { false })
        assertEquals(AutonomyPolicy.ALLOW, autonomy.policy())
        assertEquals(AutonomyPolicy.ALLOW, autonomy.projectPolicy())
        for (choice in AutonomyPolicy.entries) {
            assertNull(autonomy.setPolicyFromUi(choice))
            assertEquals(choice, autonomy.policy())
            assertEquals(choice, autonomy.projectPolicy())
            assertEquals(choice, LinuxExecAutonomy(store, { false }).policy())
        }
    }

    @Test fun explicitCodingOverrideIgnoresPrincipalEditsAndPreservesExactAdmission() {
        val store = InMemoryConnectorAutonomyStore(); val autonomy = LinuxExecAutonomy(store, { true })
        assertNull(autonomy.setProjectPolicyFromUi(AutonomyPolicy.ALLOW))
        val original = autonomy.projectAuthorization()
        assertNull(autonomy.setPolicyFromUi(AutonomyPolicy.DENY))
        assertEquals(original, autonomy.projectAuthorization())
        autonomy.admitProject(original, false, com.jarvys.agent.CancellationToken.uncancellable())
        assertEquals(AutonomyPolicy.ALLOW, autonomy.projectPolicy())
        assertEquals(AutonomyPolicy.DENY, autonomy.policy())
    }

    @Test fun crossInstancePolicyAbaRevokesPrincipalAndInheritedCoding() {
        val store = InMemoryConnectorAutonomyStore(); val first = LinuxExecAutonomy(store, { true })
        val second = LinuxExecAutonomy(store, { true })
        val principal = first.authorization(); val project = first.projectAuthorization()
        second.setPolicyFromUi(AutonomyPolicy.DENY); second.setPolicyFromUi(AutonomyPolicy.ALLOW)
        assertTrue(runCatching { first.admit(principal, false, com.jarvys.agent.CancellationToken.uncancellable()) }.isFailure)
        assertNotEquals(project, first.projectAuthorization())
        assertTrue(runCatching { first.admitProject(project, false, com.jarvys.agent.CancellationToken.uncancellable()) }.isFailure)
    }

    @Test fun explicitCodingPolicyAbaRevokesItsOpaqueAuthorizationAcrossInstances() {
        val store = InMemoryConnectorAutonomyStore(); val first = LinuxExecAutonomy(store, { true })
        first.setProjectPolicyFromUi(AutonomyPolicy.ALLOW)
        val prior = first.projectAuthorization()
        val second = LinuxExecAutonomy(store, { true })
        second.setProjectPolicyFromUi(AutonomyPolicy.DENY); second.setProjectPolicyFromUi(AutonomyPolicy.ALLOW)
        assertNotEquals(prior, first.projectAuthorization())
        assertTrue(runCatching { first.admitProject(prior, false, com.jarvys.agent.CancellationToken.uncancellable()) }.isFailure)
    }

    @Test fun uninstallPreservesEveryChoiceButRevokesPreparedEnvironmentAcrossReinstall() {
        for (choice in AutonomyPolicy.entries) {
            val store = InMemoryConnectorAutonomyStore(); var installed = true
            val autonomy = LinuxExecAutonomy(store, { installed })
            autonomy.setPolicyFromUi(choice); autonomy.setProjectPolicyFromUi(choice)
            val principal = autonomy.authorization(); val project = autonomy.projectAuthorization()
            autonomy.withUninstallReset {
                assertEquals(choice, autonomy.policy()); assertEquals(choice, autonomy.projectPolicy())
                assertTrue(runCatching { autonomy.admit(principal, choice == AutonomyPolicy.ASK,
                    com.jarvys.agent.CancellationToken.uncancellable()) }.isFailure)
                installed = false
            }
            installed = true
            assertEquals(choice, LinuxExecAutonomy(store, { installed }).policy())
            assertEquals(choice, LinuxExecAutonomy(store, { installed }).projectPolicy())
            assertTrue(runCatching { autonomy.admitProject(project, choice == AutonomyPolicy.ASK,
                com.jarvys.agent.CancellationToken.uncancellable()) }.isFailure)
        }
    }

    @Test fun installationAndStopRemainIndependentDispatchRequirements() {
        var installed = false
        val autonomy = LinuxExecAutonomy(InMemoryConnectorAutonomyStore(), { installed })
        assertNull(autonomy.setPolicyFromUi(AutonomyPolicy.ALLOW))
        val authorization = autonomy.authorization()
        assertTrue(runCatching { autonomy.admit(authorization, false, com.jarvys.agent.CancellationToken.uncancellable()) }.isFailure)
        installed = true
        val token = com.jarvys.agent.CancellationToken.cancellable(); token.cancel()
        var validated = false
        assertTrue(runCatching { autonomy.admit(authorization, false, token) { validated = true } }.isFailure)
        assertFalse(validated)
    }

    @Test fun exactAllowReceiptCannotReviveAnUninstalledEnvironment() {
        val autonomy = LinuxExecAutonomy(InMemoryConnectorAutonomyStore(), { true })
        autonomy.setPolicyFromUi(AutonomyPolicy.ASK)
        val principal = autonomy.authorization()
        autonomy.withUninstallReset { }
        assertTrue(runCatching { autonomy.allowFromApproval(principal) }.isFailure)
        assertEquals(AutonomyPolicy.ASK, autonomy.policy())
    }

    @Test fun failedSavePreservesExplicitAskWithoutPretendingSuccess() {
        val backing = InMemoryConnectorAutonomyStore()
        backing.setPolicy(LinuxExecAutonomy.PROJECT_CONNECTOR_ID, LinuxExecAutonomy.PROJECT_OPERATION_NAME, AutonomyPolicy.ASK)
        val broken = object : ConnectorAutonomyStore by backing {
            override fun setPolicyChecked(connectorId: String, operationName: String, policy: AutonomyPolicy) { error("disk full") }
        }
        val autonomy = LinuxExecAutonomy(broken, { true }); val before = autonomy.projectAuthorization()
        assertNotNull(autonomy.setProjectPolicyFromUi(AutonomyPolicy.ALLOW))
        assertEquals(before, autonomy.projectAuthorization())
    }

    @Test fun failedAuditPreventsAutomaticExecution() {
        val backing = InMemoryConnectorAutonomyStore()
        val store = object : ConnectorAutonomyStore by backing { override fun appendAudit(record: AutonomyAuditRecord) { error("disk full") } }
        val autonomy = LinuxExecAutonomy(store, { true })
        assertNotNull(autonomy.recordAutomaticExecution("test", "/workspace")); assertEquals(0L, autonomy.revision.value)
    }
    @Test fun streamRedactionSurvivesChunkBoundariesAndLongLines() {
        val chunks = mutableListOf<String>(); val log = CodingJobLog(CodingJobRedactor::redact, 40, chunks::add)
        log.accept(LinuxOutputStream.STDOUT, "password=super")
        log.accept(LinuxOutputStream.STDOUT, "secret\nBearer\ncredential\n")
        log.accept(LinuxOutputStream.STDERR, "-----BEGIN PRIVATE KEY-----\nprivate-data\n-----END PRIVATE KEY-----\n")
        log.accept(LinuxOutputStream.STDOUT, "x".repeat(100)); log.finish()
        val output = chunks.joinToString("")
        assertFalse(output.contains("supersecret")); assertFalse(output.contains("credential")); assertFalse(output.contains("private-data"))
        assertTrue(output.contains("REDACTED LONG LINE")); assertTrue(output.contains("REDACTED PEM BLOCK"))
    }
}
