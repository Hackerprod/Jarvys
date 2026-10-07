package com.jarvys.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteMcpDisconnectFlowTest {
    @Test fun localConfigAndSecretRemovalRunsEvenWhenRemoteRevocationFails() {
        val calls = mutableListOf<String>()
        val encryptedServerSecrets = mutableMapOf("catalog_github" to "encrypted-token")
        RemoteMcpDisconnectFlow.disconnect(
            revokeBestEffort = { calls += "revoke"; error("provider revocation unavailable") },
            stopConnection = { calls += "stop" },
            clearConfigurationAndSecrets = { calls += "clear"; encryptedServerSecrets.remove("catalog_github") },
        )
        assertEquals(listOf("revoke", "stop", "clear"), calls)
        assertFalse(encryptedServerSecrets.containsKey("catalog_github"))
    }
}
