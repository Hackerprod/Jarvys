package com.jarvys.agent

import com.jarvys.agent.connectors.AutonomyPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeBotImageDispatchTest {
    @Test fun revocationWhileObtainingInitialCredentialsPreventsAnySend() {
        val policy = NativeActionPolicy.inMemory()
        val request = policy.prepare(NativeActionPolicy.Operation.BOT_CREATE, NativeActionPolicy.binding("exact bot icon"))
        val token = CancellationToken.uncancellable()
        var sent = 0
        assertThrows(IllegalStateException::class.java) {
            CodexImageGenerationClient.nativeRequest(JSONObject(), "chat", token, "model", { request.admit(token) },
                { _, _ -> policy.set(NativeActionPolicy.Operation.BOT_CREATE, AutonomyPolicy.DENY); Any() },
                { _, _, _, _ -> sent++; ProviderHttp.Response(200, "{}") })
        }
        assertEquals(0, sent)
    }
    @Test fun revocationDuring401RefreshPreventsFreshRetry() {
        val policy = NativeActionPolicy.inMemory()
        val request = policy.prepare(NativeActionPolicy.Operation.BOT_CREATE, NativeActionPolicy.binding("exact bot icon"))
        val token = CancellationToken.uncancellable()
        var sent = 0
        assertThrows(IllegalStateException::class.java) {
            CodexImageGenerationClient.nativeRequest(JSONObject(), "chat", token, "model", { request.admit(token) },
                { refresh, _ -> if (refresh) policy.set(NativeActionPolicy.Operation.BOT_CREATE, AutonomyPolicy.DENY); Any() },
                { _, _, _, _ -> sent++; ProviderHttp.Response(401, "{}") })
        }
        assertEquals(1, sent)
    }
}
