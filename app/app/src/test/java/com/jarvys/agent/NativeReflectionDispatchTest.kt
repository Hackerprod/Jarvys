package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.AutonomyPolicy
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
    private val token = CancellationToken.uncancellable()
    private val operation = NativeActionPolicy.Operation.MEMORY_REFLECTION
    private class FakeConnection(private val sends: AtomicInteger, private val status: Int) : HttpURLConnection(URL("https://synthetic.invalid")) {
        override fun getOutputStream() = ByteArrayOutputStream().also { sends.incrementAndGet() }
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream("{}".toByteArray())
        override fun getErrorStream() = ByteArrayInputStream("{}".toByteArray())
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
    private fun send(codex: Boolean, factory: ProviderHttp.ConnectionFactory): ProviderHttp.Response {
        if (!codex) return ProviderHttp.post("https://synthetic.invalid", emptyMap(), "{}".toByteArray(), token, false, factory)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secrets = SecretStore(context.getSharedPreferences("synthetic-reflection-credentials", Context.MODE_PRIVATE))
        secrets.saveCodexTokens("SYNTHETIC_ACCESS", "SYNTHETIC_REFRESH", Long.MAX_VALUE, "synthetic-account")
        return OpenAICodexResponsesClient.sendImageRequest(JSONObject(), secrets.codexCredentials, "session", token, factory)
    }
    @Test fun bothActualTransportsRecheckAfterPreparationAndNeverLeakLexicalAuthority() {
        for (codex in listOf(false, true)) {
            val policy = NativeActionPolicy.inMemory()
            val request = policy.prepare(operation, NativeActionPolicy.binding("selected reflection"))
            val sends = AtomicInteger()
            NativeEffectDispatchScope.enter { request.admit(token) }.use {
                assertThrows(IllegalStateException::class.java) {
                    send(codex) { policy.set(operation, AutonomyPolicy.DENY); FakeConnection(sends, 200) }
                }
                assertEquals(0, sends.get())
                val concurrent = Thread { send(codex) { FakeConnection(sends, 200) } }
                concurrent.start(); concurrent.join(5_000)
                assertFalse(concurrent.isAlive); assertEquals(1, sends.get())
            }
            // No ambient policy is installed for unrelated provider traffic after lexical exit.
            send(codex) { FakeConnection(sends, 200) }
            assertEquals(2, sends.get())
            request.close()
        }
    }
    @Test fun codexInitialCredentialWaitAnd401RefreshCannotDispatchAfterRevocation() {
        for (revokeOnRefresh in listOf(false, true)) {
            val policy = NativeActionPolicy.inMemory()
            val request = policy.prepare(operation, NativeActionPolicy.binding("selected reflection"))
            val sends = AtomicInteger()
            NativeEffectDispatchScope.enter { request.admit(token) }.use {
                assertThrows(IllegalStateException::class.java) {
                    CodexAuthenticatedRequestExecutor.execute(JSONObject(), "session", token, "synthetic-model",
                        { refresh, _ -> if (refresh == revokeOnRefresh) policy.set(operation, AutonomyPolicy.DENY); Any() },
                        { _, _, _, _ -> send(true) { FakeConnection(sends, 401) } })
                }
            }
            assertEquals(if (revokeOnRefresh) 1 else 0, sends.get())
            request.close()
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
