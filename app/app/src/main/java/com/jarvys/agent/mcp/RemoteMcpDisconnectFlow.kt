package com.jarvys.agent.mcp

/** Enforces local disconnect/secret cleanup even when provider-side revocation is unavailable. */
internal object RemoteMcpDisconnectFlow {
    fun disconnect(
        revokeBestEffort: () -> Unit,
        stopConnection: () -> Unit,
        clearConfigurationAndSecrets: () -> Unit,
    ) {
        try { runCatching(revokeBestEffort) }
        finally {
            try { stopConnection() }
            finally { clearConfigurationAndSecrets() }
        }
    }
}
