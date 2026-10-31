package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NativeReflectionDispatchTest {
    private class FakeConnection(private val sends: AtomicInteger, private val status: Int) : HttpURLConnection(URL("https://synthetic.invalid")) {
        override fun getOutputStream() = ByteArrayOutputStream().also { sends.incrementAndGet() }
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream("{}".toByteArray())
        override fun getErrorStream() = ByteArrayInputStream("{}".toByteArray())
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
    private fun send(codex: Boolean, token: CancellationToken, factory: ProviderHttp.ConnectionFactory): ProviderHttp.Response {
        if (!codex) return ProviderHttp.post("https://synthetic.invalid", emptyMap(), "{}".toByteArray(), token, false, factory)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secrets = SecretStore(context.getSharedPreferences("synthetic-reflection-credentials", Context.MODE_PRIVATE))
        secrets.saveCodexTokens("SYNTHETIC_ACCESS", "SYNTHETIC_REFRESH", Long.MAX_VALUE, "synthetic-account")
        return OpenAICodexResponsesClient.sendImageRequest(JSONObject(), secrets.codexCredentials, "session", token, factory)
    }
    @Test fun bothActualTransportsStopWhenCancelledBeforeDispatch() {
        for (codex in listOf(false, true)) {
            val token = CancellationToken.cancellable()
            val sends = AtomicInteger()
            NativeEffectDispatchScope.enter { token.throwIfCancelled() }.use {
                assertThrows(java.util.concurrent.CancellationException::class.java) {
                    send(codex, token) { token.cancel(); FakeConnection(sends, 200) }
                }
                assertEquals(0, sends.get())
            }
        }
    }
    @Test fun codexInitialCredentialAnd401RefreshCancellationStopsDispatch() {
        for (revokeOnRefresh in listOf(false, true)) {
            val token = CancellationToken.cancellable()
            val sends = AtomicInteger()
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                CodexAuthenticatedRequestExecutor.execute(JSONObject(), "session", token, "synthetic-model",
                    { refresh, _ -> if (refresh == revokeOnRefresh) token.cancel(); Any() },
                    { _, _, _, _ -> send(true, token) { FakeConnection(sends, 401) } })
            }
            assertEquals(if (revokeOnRefresh) 1 else 0, sends.get())
        }
    }
    @Test fun nestedScopeRestoresExactPreviousAdmission() {
        var outer = 0; var inner = 0
        NativeEffectDispatchScope.enter { outer++ }.use {
            NativeEffectDispatchScope.beforeDispatch()
            NativeEffectDispatchScope.enter { inner++ }.use { NativeEffectDispatchScope.beforeDispatch() }
            NativeEffectDispatchScope.beforeDispatch()
        }
        NativeEffectDispatchScope.beforeDispatch()
        assertEquals(2, outer); assertEquals(1, inner)
    }
}
