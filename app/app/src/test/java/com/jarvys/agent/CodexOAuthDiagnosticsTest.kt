package com.jarvys.agent

import android.content.Context
import android.content.SharedPreferences
import com.jarvys.agent.providers.*
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodexOAuthDiagnosticsTest {
    private fun preferences(name: String): SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("v28-auth-$name", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    private fun jwt(account: String, exp: Long? = null): String {
        val payload = JSONObject().put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", account))
        exp?.let { payload.put("exp", it) }
        return "header." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray()) + ".signature"
    }

    private fun response(account: String = "test-account") = JSONObject()
        .put("access_token", jwt(account)).put("refresh_token", "secret-refresh").put("expires_in", 3600)

    private fun exchange(manager: CodexOAuthManager, body: JSONObject, token: CancellationToken = CancellationToken.cancellable(),
                         gate: CodexOAuthManager.CredentialCommitGate = CodexOAuthManager.CredentialCommitGate { it.run() }): String =
        manager.exchangeDeviceAuthorizationCode("secret-code", "secret-verifier", CodexOAuthManager.DEVICE_REDIRECT_URI,
            token, { endpoint, form, _ ->
                assertEquals(CodexOAuthManager.TOKEN_URL, endpoint)
                assertEquals("authorization_code", form["grant_type"])
                CodexOAuthManager.DeviceCodeTokenResponse(200, body)
            }, gate)

    @Test fun incompleteTokensAndInvalidExpiryHaveSpecificSanitizedReasons() {
        val cases = listOf(JSONObject() to "missing_access_token",
            JSONObject().put("access_token", jwt("account")) to "missing_refresh_token",
            JSONObject().put("access_token", "opaque-secret").put("refresh_token", "secret") to "missing_account",
            response().put("expires_in", -1) to "invalid_expiry",
            response().put("expires_in", Long.MAX_VALUE) to "invalid_expiry")
        cases.forEachIndexed { index, (body, reason) ->
            val secrets = SecretStore(preferences("invalid-$index"))
            val error = runCatching { exchange(CodexOAuthManager(secrets), body) }.exceptionOrNull()
            assertTrue(error is CodexAuthDiagnostic.Failure)
            assertEquals(reason, (error as CodexAuthDiagnostic.Failure).diagnostic.reason)
            assertEquals(CodexAuthDiagnostic.Stage.TOKEN_RESPONSE, error.diagnostic.stage)
            assertEquals(R.string.oauth_exchange_invalid_response, CodexOAuthManager.signInErrorResource(error))
            assertFalse(error.toString().contains("secret"))
            assertNull(secrets.codexCredentials)
        }
    }

    @Test fun idTokenAccountAndJwtExpiryAreRecovered() {
        val secrets = SecretStore(preferences("jwt"))
        val expiry = System.currentTimeMillis() / 1000L + 7200L
        val body = response("access-account").put("access_token", jwt("access-account", expiry))
            .put("id_token", jwt("id-account"))
        assertEquals("id-account", exchange(CodexOAuthManager(secrets), body))
        assertEquals(expiry * 1000L, secrets.codexCredentials?.expiresAtMillis)
        assertEquals("id-account", secrets.codexCredentials?.accountId)
    }

    @Test fun cancellationAtCommitGateCannotPersistCredentials() {
        val secrets = SecretStore(preferences("cancel-gate"))
        val token = CancellationToken.cancellable()
        val error = runCatching {
            exchange(CodexOAuthManager(secrets), response(), token, CodexOAuthManager.CredentialCommitGate { persist ->
                token.cancel()
                persist.run()
            })
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertNull(secrets.codexCredentials)
    }

    @Test fun failedStorageReadbackIsNeverReportedAsSuccess() {
        val prefs = preferences("readback")
        val unreadable = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "getString" && args?.firstOrNull() == "codex_access_token") null
            else method.invoke(prefs, *(args ?: emptyArray()))
        } as SharedPreferences
        val error = runCatching { exchange(CodexOAuthManager(SecretStore(unreadable)), response()) }.exceptionOrNull()
        assertTrue(error is CodexAuthDiagnostic.Failure)
        assertEquals(CodexAuthDiagnostic.Stage.SAVE_SESSION, (error as CodexAuthDiagnostic.Failure).diagnostic.stage)
        assertEquals("readback_failed", error.diagnostic.reason)
        assertEquals(R.string.oauth_storage_failed, CodexOAuthManager.signInErrorResource(error))
    }

    @Test fun refreshKeepsOmittedFieldsButCannotResurrectDisconnectedSession() {
        val secrets = SecretStore(preferences("refresh-disconnect"))
        secrets.saveCodexTokens(jwt("original"), "secret-old-refresh", 1L, "original")
        val manager = CodexOAuthManager(secrets) { _, form, _ ->
            assertEquals("refresh_token", form["grant_type"])
            secrets.clearCodexTokens()
            CodexOAuthManager.DeviceCodeTokenResponse(200, response("new-account"))
        }
        val failure = runCatching { manager.getValidCredentials(true) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertNull(secrets.codexCredentials)

        secrets.saveCodexTokens(jwt("original"), "secret-old-refresh", 1L, "original")
        val preserving = CodexOAuthManager(secrets) { _, _, _ ->
            CodexOAuthManager.DeviceCodeTokenResponse(200, JSONObject().put("expires_in", 3600))
        }
        assertEquals("secret-old-refresh", preserving.getValidCredentials(true).refreshToken)
        assertEquals("original", secrets.codexCredentials?.accountId)
    }

    @Test fun transientPollingBackoffIsBoundedAndNeverReplaysAuthorizationExchange() {
        val secrets = SecretStore(preferences("poll-backoff"))
        var time = 0L
        val waits = mutableListOf<Long>()
        var polls = 0
        var forms = 0
        val transport = object : CodexDeviceCodeTransport {
            override fun postJson(url: String, body: JSONObject, token: CancellationToken): CodexDeviceCodeHttpResponse {
                if (url == CodexDeviceCodeFlow.USER_CODE_ENDPOINT) return CodexDeviceCodeHttpResponse(200,
                    JSONObject().put("device_auth_id", "secret-device").put("user_code", "CODE"))
                polls++
                throw SocketTimeoutException("secret-response")
            }
            override fun postForm(url: String, body: Map<String, String>, token: CancellationToken): CodexDeviceCodeHttpResponse {
                forms++
                error("An unapproved code must never be exchanged")
            }
        }
        val error = runCatching {
            CodexDeviceCodeFlow(CodexOAuthManager(secrets), transport, CodexDeviceCodeClock { time },
                CodexDeviceCodeWaiter { millis, _ -> waits += millis; time += millis })
                .authenticate(CancellationToken.cancellable()) {}
        }.exceptionOrNull()
        assertEquals(listOf(3_000L, 5_000L, 10_000L, 20_000L, 30_000L), waits)
        assertEquals(5, polls)
        assertEquals(0, forms)
        assertEquals("timeout", (error as CodexDeviceCodeFailure).diagnostic?.category)
        assertFalse(error.diagnostic!!.toDisplayText("device_code").contains("secret"))
        assertNull(secrets.codexCredentials)
    }

    @Test fun tokenExchangeHttpErrorKeepsSafeReasonAndNeverSaves() {
        val secrets = SecretStore(preferences("http"))
        val error = runCatching {
            CodexOAuthManager(secrets).exchangeDeviceAuthorizationCode("secret-code", "secret-verifier",
                CodexOAuthManager.DEVICE_REDIRECT_URI, CancellationToken.cancellable()) { _, _, _ ->
                CodexOAuthManager.DeviceCodeTokenResponse(400, JSONObject().put("error", "invalid_grant")
                    .put("error_description", "secret-code secret-verifier secret-refresh"))
            }
        }.exceptionOrNull()
        assertEquals("invalid_grant", (error as CodexAuthDiagnostic.Failure).diagnostic.providerCode)
        assertEquals(400, error.diagnostic.httpStatus)
        assertFalse(error.diagnostic.toDisplayText("device_code").contains("secret"))
        assertNull(secrets.codexCredentials)
    }
}
