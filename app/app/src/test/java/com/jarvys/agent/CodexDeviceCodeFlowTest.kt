package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.providers.CodexDeviceCodeClock
import com.jarvys.agent.providers.CodexDeviceCodeFailure
import com.jarvys.agent.providers.CodexDeviceCodeFlow
import com.jarvys.agent.providers.CodexDeviceCodeHttpResponse
import com.jarvys.agent.providers.CodexDeviceCodeState
import com.jarvys.agent.providers.CodexDeviceCodeTransport
import com.jarvys.agent.providers.CodexDeviceCodeWaiter
import java.io.File
import java.util.Base64
import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexDeviceCodeFlowTest {
    private class FakeTransport : CodexDeviceCodeTransport {
        val jsonUrls = mutableListOf<String>()
        val jsonRequests = mutableListOf<JSONObject>()
        val formUrls = mutableListOf<String>()
        val forms = mutableListOf<Map<String, String>>()
        val jsonReplies = ArrayDeque<CodexDeviceCodeHttpResponse>()
        var formReply = CodexDeviceCodeHttpResponse(200, JSONObject())

        override fun postJson(url: String, body: JSONObject, token: CancellationToken): CodexDeviceCodeHttpResponse {
            jsonUrls += url
            jsonRequests += JSONObject(body.toString())
            return jsonReplies.removeFirst()
        }

        override fun postForm(url: String, body: Map<String, String>, token: CancellationToken): CodexDeviceCodeHttpResponse {
            formUrls += url
            forms += body.toMap()
            return formReply
        }
    }

    private class FakeClock(var now: Long = 1_000L) : CodexDeviceCodeClock {
        override fun nowMillis() = now
    }

    @Test fun deviceRequestPollsEveryServerIntervalPlusThreeAndStoresTokensAfterServerVerifierExchange() {
        val context = RuntimeEnvironment.getApplication()
        val secretPrefs = context.getSharedPreferences("r3a-device-success", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val secrets = SecretStore(secretPrefs)
        val transport = FakeTransport().apply {
            jsonReplies += CodexDeviceCodeHttpResponse(200,
                JSONObject().put("device_auth_id", "device-auth-secret").put("usercode", "ABCD-EFGH").put("interval", "1"))
            jsonReplies += CodexDeviceCodeHttpResponse(403, JSONObject().put("error", "authorization_pending"))
            jsonReplies += CodexDeviceCodeHttpResponse(404, JSONObject().put("error", "authorization_pending"))
            jsonReplies += CodexDeviceCodeHttpResponse(200,
                JSONObject().put("authorization_code", "authorization-code-secret").put("code_challenge", "challenge-from-server")
                    .put("code_verifier", "verifier-from-server"))
            formReply = CodexDeviceCodeHttpResponse(200, tokenResponse("account-r3a"))
        }
        val clock = FakeClock()
        val waits = mutableListOf<Long>()
        val states = mutableListOf<CodexDeviceCodeState>()
        val flow = CodexDeviceCodeFlow(CodexOAuthManager(secrets), transport, clock,
            CodexDeviceCodeWaiter { millis, token -> token.throwIfCancelled(); waits += millis; clock.now += millis })

        val account = flow.authenticate(CancellationToken.cancellable(), states::add)

        assertEquals("account-r3a", account)
        assertEquals(listOf(4_000L, 4_000L, 4_000L), waits)
        assertEquals(CodexDeviceCodeFlow.USER_CODE_ENDPOINT, transport.jsonUrls[0])
        assertEquals(JSONObject().put("client_id", CodexOAuthManager.CLIENT_ID).toString(), transport.jsonRequests[0].toString())
        assertEquals(listOf(CodexDeviceCodeFlow.DEVICE_TOKEN_ENDPOINT, CodexDeviceCodeFlow.DEVICE_TOKEN_ENDPOINT,
            CodexDeviceCodeFlow.DEVICE_TOKEN_ENDPOINT), transport.jsonUrls.drop(1))
        assertEquals(JSONObject().put("device_auth_id", "device-auth-secret").put("user_code", "ABCD-EFGH").toString(),
            transport.jsonRequests[1].toString())
        assertEquals(CodexOAuthManager.TOKEN_URL, transport.formUrls.single())
        assertEquals(linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to CodexOAuthManager.CLIENT_ID,
            "code" to "authorization-code-secret",
            "code_verifier" to "verifier-from-server",
            "redirect_uri" to "https://auth.openai.com/deviceauth/callback",
        ), transport.forms.single())
        assertEquals(jwt("account-r3a"), secrets.codexCredentials?.accessToken)
        assertEquals("refresh-r3a", secrets.codexCredentials?.refreshToken)
        assertEquals("account-r3a", secrets.codexCredentials?.accountId)
        assertEquals("ABCD-EFGH", (states.filterIsInstance<CodexDeviceCodeState.WaitingForCode>().single()).userCode)
        assertTrue(states.contains(CodexDeviceCodeState.RequestingCode))
        assertTrue(states.any { it is CodexDeviceCodeState.ExchangingCode
            && it.userCode == "ABCD-EFGH" && it.verificationUrl == CodexDeviceCodeFlow.VERIFICATION_URL })
        assertEquals(CodexDeviceCodeState.Connected("account-r3a"), states.last())
        assertFalse(transport.jsonRequests.any { it.toString().contains("authorization-code-secret") })
    }

    @Test fun nonPendingErrorsAreSafeAndNoCredentialsAreSavedBeforeSuccessfulExchange() {
        val context = RuntimeEnvironment.getApplication()
        val secrets = SecretStore(context.getSharedPreferences("r3a-device-error", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        val transport = FakeTransport().apply {
            jsonReplies += CodexDeviceCodeHttpResponse(200,
                JSONObject().put("device_auth_id", "device-secret-1").put("user_code", "USER-SECRET-2").put("interval", "0"))
            jsonReplies += CodexDeviceCodeHttpResponse(400, JSONObject().put("error", "secret response body"))
        }
        val failure = runCatching {
            CodexDeviceCodeFlow(CodexOAuthManager(secrets), transport, FakeClock(),
                CodexDeviceCodeWaiter { millis, _ -> }).authenticate(CancellationToken.cancellable()) {}
        }.exceptionOrNull()
        assertTrue(failure is CodexDeviceCodeFailure)
        assertFalse(failure.toString().contains("device-secret-1"))
        assertFalse(failure.toString().contains("USER-SECRET-2"))
        assertFalse(failure.toString().contains("secret response body"))
        assertNull(secrets.codexCredentials)
        assertEquals(0, transport.formUrls.size)
    }

    @Test fun tokenExchangeErrorsDoNotExposeAuthorizationCodeVerifierOrResponseBody() {
        val context = RuntimeEnvironment.getApplication()
        val secrets = SecretStore(context.getSharedPreferences("r3a-device-token-error", Context.MODE_PRIVATE).also {
            it.edit().clear().commit()
        })
        val authorizationCode = "authorization-code-secret-r3a"
        val verifier = "code-verifier-secret-r3a"
        val transport = FakeTransport().apply {
            jsonReplies += CodexDeviceCodeHttpResponse(200,
                JSONObject().put("device_auth_id", "device-auth-secret-r3a").put("user_code", "user-code-secret-r3a")
                    .put("interval", "0"))
            jsonReplies += CodexDeviceCodeHttpResponse(200,
                JSONObject().put("authorization_code", authorizationCode).put("code_verifier", verifier))
            formReply = CodexDeviceCodeHttpResponse(500,
                JSONObject().put("message", "$authorizationCode $verifier device-auth-secret-r3a user-code-secret-r3a"))
        }
        val failure = runCatching {
            CodexDeviceCodeFlow(CodexOAuthManager(secrets), transport, FakeClock(),
                CodexDeviceCodeWaiter { _, token -> token.throwIfCancelled() })
                .authenticate(CancellationToken.cancellable()) {}
        }.exceptionOrNull()
        assertTrue(failure is CodexDeviceCodeFailure)
        listOf(authorizationCode, verifier, "device-auth-secret-r3a", "user-code-secret-r3a")
            .forEach { assertFalse(failure.toString().contains(it)) }
        assertNull(secrets.codexCredentials)
        assertEquals(CodexOAuthManager.TOKEN_URL, transport.formUrls.single())
        assertEquals(authorizationCode, transport.forms.single()["code"])
        assertEquals(verifier, transport.forms.single()["code_verifier"])

        val flowSource = File(sourceRoot(), "com/jarvys/agent/providers/CodexDeviceCodeFlow.kt").readText()
        assertFalse(flowSource.contains("Log."))
        assertFalse(flowSource.contains("System.out"))
    }

    @Test fun cancellationStopsPollingAndFifteenMinuteExpiryDoesNotExchangeOrSaveTokens() {
        val context = RuntimeEnvironment.getApplication()
        val cancelledSecrets = SecretStore(context.getSharedPreferences("r3a-device-cancel", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        val cancelledTransport = FakeTransport().apply {
            jsonReplies += CodexDeviceCodeHttpResponse(200,
                JSONObject().put("device_auth_id", "device").put("user_code", "CODE").put("interval", "1"))
        }
        val cancelToken = CancellationToken.cancellable()
        val cancelResult = runCatching {
            CodexDeviceCodeFlow(CodexOAuthManager(cancelledSecrets), cancelledTransport, FakeClock(),
                CodexDeviceCodeWaiter { _, token -> token.cancel(); token.throwIfCancelled() })
                .authenticate(cancelToken) {}
        }.exceptionOrNull()
        assertTrue(cancelResult is CancellationException)
        assertEquals(1, cancelledTransport.jsonUrls.size)
        assertTrue(cancelledTransport.formUrls.isEmpty())
        assertNull(cancelledSecrets.codexCredentials)

        val expirySecrets = SecretStore(context.getSharedPreferences("r3a-device-expiry", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        val expiryTransport = FakeTransport().apply {
            jsonReplies += CodexDeviceCodeHttpResponse(200,
                JSONObject().put("device_auth_id", "device").put("user_code", "CODE").put("interval", "2000"))
        }
        val clock = FakeClock()
        val expiryResult = runCatching {
            CodexDeviceCodeFlow(CodexOAuthManager(expirySecrets), expiryTransport, clock,
                CodexDeviceCodeWaiter { millis, _ -> clock.now += millis })
                .authenticate(CancellationToken.cancellable()) {}
        }.exceptionOrNull()
        assertEquals(R.string.provider_device_code_expired, (expiryResult as CodexDeviceCodeFailure).messageResource)
        assertEquals(CodexDeviceCodeFlow.DEVICE_CODE_LIFETIME_MILLIS, clock.now - 1_000L)
        assertEquals(1, expiryTransport.jsonUrls.size)
        assertTrue(expiryTransport.formUrls.isEmpty())
        assertNull(expirySecrets.codexCredentials)
    }

    @Test fun browserPkceExchangeStillUsesTheLoopbackRedirectUri() {
        assertEquals("http://localhost:1455/auth/callback", CodexOAuthManager.REDIRECT_URI)
        val root = sourceRoot()
        val source = File(root, "com/jarvys/agent/CodexOAuthManager.java").readText()
        val browserExchange = source.substringAfter("private TokenReply exchangeCode(String code, String verifier)")
            .substringBefore("private TokenReply exchangeCode(String code, String verifier, String redirectUri")
        assertTrue(browserExchange.contains("exchangeCode(code, verifier, REDIRECT_URI"))
        val redirectParameterizedExchange = source.substringAfter(
            "private TokenReply exchangeCode(String code, String verifier, String redirectUri")
            .substringBefore("private TokenReply refreshToken")
        assertTrue(redirectParameterizedExchange.contains("body.put(\"redirect_uri\", redirectUri)"))
        assertFalse(browserExchange.contains("DEVICE_REDIRECT_URI"))
    }

    private fun tokenResponse(accountId: String) = JSONObject()
        .put("access_token", jwt(accountId))
        .put("refresh_token", "refresh-r3a")
        .put("expires_in", 3600)

    private fun jwt(accountId: String): String {
        val claims = JSONObject().put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", accountId))
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().toByteArray(Charsets.UTF_8))
        return "header.$payload.signature"
    }

    private fun sourceRoot(): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return sequenceOf(File(working, "src/main/java"), File(working, "app/src/main/java"),
            File(working.parentFile, "app/src/main/java")).firstOrNull(File::isDirectory)
            ?: error("Could not locate source root from ${working.path}")
    }
}
