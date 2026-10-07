package com.jarvys.agent.linux

import com.jarvys.agent.connectors.*
import org.junit.Assert.*
import org.junit.Test

class LinuxExecAutonomyTest {
    @Test fun defaultAskAndExplicitChangesInvalidateApprovals() {
        val store = InMemoryConnectorAutonomyStore(); val autonomy = LinuxExecAutonomy(store, { true })
        assertEquals(AutonomyPolicy.ASK, autonomy.policy())
        assertNull(autonomy.setPolicyFromUi(AutonomyPolicy.ALLOW)); assertEquals(1L, autonomy.revision.value)
        assertNull(autonomy.setPolicyFromUi(AutonomyPolicy.DENY)); assertEquals(2L, autonomy.revision.value)
    }
    @Test fun allowNeedsInstallationAndUninstallResetsBeforeRemoval() {
        val store = InMemoryConnectorAutonomyStore(); var installed = false
        val autonomy = LinuxExecAutonomy(store, { installed })
        assertNotNull(autonomy.setPolicyFromUi(AutonomyPolicy.ALLOW)); assertEquals(AutonomyPolicy.ASK, autonomy.policy())
        installed = true; autonomy.setPolicyFromUi(AutonomyPolicy.ALLOW)
        autonomy.withUninstallReset {
            assertEquals(AutonomyPolicy.ASK, autonomy.policy())
            assertNotNull(autonomy.setPolicyFromUi(AutonomyPolicy.ALLOW))
            installed = false
        }
        assertEquals(AutonomyPolicy.ASK, autonomy.policy())
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
