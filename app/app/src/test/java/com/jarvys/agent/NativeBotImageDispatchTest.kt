package com.jarvys.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class NativeBotImageDispatchTest {
    @Test fun cancellationAfterCredentialLookupPreventsImageRequestSend() {
        val token = CancellationToken.cancellable()
        var sent = 0
        assertThrows(CancellationException::class.java) {
            CodexImageGenerationClient.nativeRequest(JSONObject(), "chat", token, "model",
                { token.throwIfCancelled() },
                { _, _ -> token.cancel(); Any() },
                { _, _, _, _ -> sent++; ProviderHttp.Response(200, "{}") })
        }
        assertEquals(0, sent)
    }

    @Test fun cancellationDuring401RefreshPreventsFreshRetry() {
        val token = CancellationToken.cancellable()
        var sent = 0
        assertThrows(CancellationException::class.java) {
            CodexImageGenerationClient.nativeRequest(JSONObject(), "chat", token, "model",
                { token.throwIfCancelled() },
                { refresh, _ -> if (refresh) token.cancel(); Any() },
                { _, _, _, _ -> sent++; ProviderHttp.Response(401, "{}") })
        }
        assertEquals(1, sent)
    }
}
