package com.jarvys.agent.providers

import com.jarvys.agent.CodexAuthDiagnostic
import com.jarvys.agent.CodexAuthConnectionRetry
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CodexOAuthManager
import com.jarvys.agent.R
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import org.json.JSONObject

sealed class CodexDeviceCodeState {
    object Idle : CodexDeviceCodeState()
    object RequestingCode : CodexDeviceCodeState()
    data class WaitingForCode(
        val userCode: String,
        val verificationUrl: String,
        val expiresAtMillis: Long,
    ) : CodexDeviceCodeState()
    data class ExchangingCode(
        val userCode: String,
        val verificationUrl: String,
        val expiresAtMillis: Long,
    ) : CodexDeviceCodeState()
    data class Failed(val messageResource: Int, val diagnostic: CodexAuthDiagnostic? = null) : CodexDeviceCodeState()
    data class Connected(val accountId: String) : CodexDeviceCodeState()

    val isRunning: Boolean
        get() = this is RequestingCode || this is WaitingForCode || this is ExchangingCode
}

data class CodexDeviceCodeHttpResponse(val statusCode: Int, val json: JSONObject, val diagnostic: CodexAuthDiagnostic? = null)

interface CodexDeviceCodeTransport {
    fun postJson(url: String, body: JSONObject, token: CancellationToken): CodexDeviceCodeHttpResponse
    fun postForm(url: String, body: Map<String, String>, token: CancellationToken): CodexDeviceCodeHttpResponse
}

fun interface CodexDeviceCodeClock {
    fun nowMillis(): Long
}

fun interface CodexDeviceCodeWaiter {
    @Throws(InterruptedException::class)
    fun waitFor(millis: Long, token: CancellationToken)
}

class CodexDeviceCodeFailure(val messageResource: Int, val diagnostic: CodexAuthDiagnostic? = null) : IllegalStateException("OpenAI device-code sign-in failed")

/** OpenAI's device-auth protocol with injectable HTTP/time seams; secrets stay in local variables only. */
class CodexDeviceCodeFlow(
    private val manager: CodexOAuthManager,
    private val transport: CodexDeviceCodeTransport = UrlConnectionCodexDeviceCodeTransport,
    private val clock: CodexDeviceCodeClock = CodexDeviceCodeClock(System::currentTimeMillis),
    private val waiter: CodexDeviceCodeWaiter = CodexDeviceCodeWaiter { millis, token ->
        token.throwIfCancelled()
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Device-code sign-in cancelled")
        }
        token.throwIfCancelled()
    },
    private val commitGate: CodexOAuthManager.CredentialCommitGate = CodexOAuthManager.CredentialCommitGate { it.run() },
) {
    fun authenticate(
        token: CancellationToken,
        onState: (CodexDeviceCodeState) -> Unit,
    ): String {
        onState(CodexDeviceCodeState.RequestingCode)
        var stage = CodexAuthDiagnostic.Stage.REQUEST_CODE
        try {
            token.throwIfCancelled()
            val startedAt = clock.nowMillis()
            val deadline = Math.addExact(startedAt, DEVICE_CODE_LIFETIME_MILLIS)
            val authorization = transport.postJson(
                USER_CODE_ENDPOINT,
                JSONObject().put("client_id", CodexOAuthManager.CLIENT_ID),
                token,
            )
            token.throwIfCancelled()
            if (authorization.statusCode !in 200..299) {
                throw CodexDeviceCodeFailure(R.string.provider_device_code_failed,
                    authorization.diagnostic ?: CodexAuthDiagnostic.http(stage, authorization.statusCode, authorization.json))
            }
            val deviceAuthId = authorization.json.optString("device_auth_id", "")
            val userCode = authorization.json.optString("user_code", "")
                .ifBlank { authorization.json.optString("usercode", "") }
            val intervalMillis = pollIntervalMillis(authorization.json.opt("interval"))
            if (deviceAuthId.isBlank() || userCode.isBlank() || intervalMillis == null) {
                throw CodexDeviceCodeFailure(R.string.provider_device_code_invalid, CodexAuthDiagnostic.validation(stage, "invalid_device_response"))
            }
            if (clock.nowMillis() >= deadline) throw CodexDeviceCodeFailure(R.string.provider_device_code_expired)
            onState(CodexDeviceCodeState.WaitingForCode(userCode, VERIFICATION_URL, deadline))

            var consecutivePollFailures = 0
            var nextPollDelayMillis: Long = intervalMillis
            while (true) {
                token.throwIfCancelled()
                val remaining = deadline - clock.nowMillis()
                if (remaining <= 0L) throw CodexDeviceCodeFailure(R.string.provider_device_code_expired)
                waiter.waitFor(minOf(nextPollDelayMillis, remaining), token)
                token.throwIfCancelled()
                if (clock.nowMillis() >= deadline) throw CodexDeviceCodeFailure(R.string.provider_device_code_expired)

                stage = CodexAuthDiagnostic.Stage.POLL_APPROVAL
                val poll = try {
                    transport.postJson(DEVICE_TOKEN_ENDPOINT,
                        JSONObject().put("device_auth_id", deviceAuthId).put("user_code", userCode), token)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    token.throwIfCancelled()
                    if (clock.nowMillis() >= deadline) throw CodexDeviceCodeFailure(R.string.provider_device_code_expired)
                    if (!isTransientPollingFailure(failure) || consecutivePollFailures >= 4) throw failure
                    consecutivePollFailures++
                    nextPollDelayMillis = maxOf(intervalMillis, minOf(30_000L, (1L shl (consecutivePollFailures - 1)) * 5_000L))
                    continue
                }
                consecutivePollFailures = 0
                nextPollDelayMillis = intervalMillis
                token.throwIfCancelled()
                if (clock.nowMillis() >= deadline) throw CodexDeviceCodeFailure(R.string.provider_device_code_expired)
                if (poll.statusCode == 403 || poll.statusCode == 404) continue
                if (poll.statusCode !in 200..299) {
                    val error = if (poll.statusCode == 400 || poll.statusCode == 410) {
                        R.string.provider_device_code_expired
                    } else R.string.provider_device_code_failed
                    throw CodexDeviceCodeFailure(error, poll.diagnostic ?: CodexAuthDiagnostic.http(stage, poll.statusCode, poll.json))
                }
                val authorizationCode = poll.json.optString("authorization_code", "")
                val codeVerifier = poll.json.optString("code_verifier", "")
                if (authorizationCode.isBlank() || codeVerifier.isBlank()) {
                    throw CodexDeviceCodeFailure(R.string.provider_device_code_invalid, CodexAuthDiagnostic.validation(stage, "invalid_device_response"))
                }
                stage = CodexAuthDiagnostic.Stage.TOKEN_EXCHANGE
                onState(CodexDeviceCodeState.ExchangingCode(userCode, VERIFICATION_URL, deadline))
                val accountId = manager.exchangeDeviceAuthorizationCode(
                    authorizationCode,
                    codeVerifier,
                    CodexOAuthManager.DEVICE_REDIRECT_URI,
                    token,
                    CodexOAuthManager.DeviceCodeTokenTransport { endpoint, form, exchangeToken ->
                        val response = transport.postForm(endpoint, form, exchangeToken)
                        if (clock.nowMillis() >= deadline) {
                            throw CodexDeviceCodeFailure(R.string.provider_device_code_expired)
                        }
                        CodexOAuthManager.DeviceCodeTokenResponse(response.statusCode, response.json, response.diagnostic)
                    },
                    CodexOAuthManager.CredentialCommitGate { persist ->
                        commitGate.commit {
                            token.throwIfCancelled()
                            if (clock.nowMillis() >= deadline) throw CodexDeviceCodeFailure(R.string.provider_device_code_expired)
                            persist.run()
                        }
                    },
                )
                token.throwIfCancelled()
                onState(CodexDeviceCodeState.Connected(accountId))
                return accountId
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CodexDeviceCodeFailure) {
            throw failure
        } catch (error: Exception) {
            token.throwIfCancelled()
            throw CodexDeviceCodeFailure(CodexOAuthManager.signInErrorResource(error), CodexAuthDiagnostic.failure(stage, error))
        }
    }

    private fun isTransientPollingFailure(failure: Exception): Boolean {
        val diagnostic = if (failure is CodexDeviceCodeFailure) failure.diagnostic
            else CodexAuthDiagnostic.failure(CodexAuthDiagnostic.Stage.POLL_APPROVAL, failure)
        return diagnostic?.stage == CodexAuthDiagnostic.Stage.POLL_APPROVAL &&
            diagnostic.category in setOf("dns", "connection", "timeout")
    }

    private fun pollIntervalMillis(raw: Any?): Long? {
        val seconds = when (raw) {
            is String -> raw.trim().toLongOrNull()
            is Number -> raw.toLong().takeIf { raw.toDouble() == it.toDouble() }
            null, JSONObject.NULL -> 0L
            else -> null
        } ?: return null
        if (seconds < 0L) return null
        return try {
            Math.addExact(Math.multiplyExact(seconds, 1_000L), POLL_MARGIN_MILLIS)
        } catch (_: ArithmeticException) {
            null
        }
    }

    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val USER_CODE_ENDPOINT = "$ISSUER/api/accounts/deviceauth/usercode"
        const val DEVICE_TOKEN_ENDPOINT = "$ISSUER/api/accounts/deviceauth/token"
        const val VERIFICATION_URL = "$ISSUER/codex/device"
        const val MAX_RESPONSE_BYTES = 128 * 1024
        const val DEVICE_CODE_LIFETIME_MILLIS = 15L * 60L * 1000L
        private const val POLL_MARGIN_MILLIS = 3_000L
    }
}

private object UrlConnectionCodexDeviceCodeTransport : CodexDeviceCodeTransport {
    override fun postJson(url: String, body: JSONObject, token: CancellationToken): CodexDeviceCodeHttpResponse {
        require(url == CodexDeviceCodeFlow.USER_CODE_ENDPOINT || url == CodexDeviceCodeFlow.DEVICE_TOKEN_ENDPOINT) {
            "Untrusted OpenAI device-code endpoint"
        }
        return post(url, body.toString().toByteArray(StandardCharsets.UTF_8), "application/json", token)
    }

    override fun postForm(
        url: String,
        body: Map<String, String>,
        token: CancellationToken,
    ): CodexDeviceCodeHttpResponse {
        require(url == CodexOAuthManager.TOKEN_URL) { "Untrusted OpenAI token endpoint" }
        val encoded = body.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        return post(url, encoded.toByteArray(StandardCharsets.UTF_8), "application/x-www-form-urlencoded", token)
    }

    private fun post(url: String, body: ByteArray, contentType: String, token: CancellationToken): CodexDeviceCodeHttpResponse {
        return try {
            if (url == CodexOAuthManager.TOKEN_URL) {
                CodexAuthConnectionRetry.execute(token) { postOnce(url, body, contentType, token) }
            } else postOnce(url, body, contentType, token)
        } catch (failure: CodexAuthConnectionRetry.BeforeBodyDnsFailure) {
            throw CodexDeviceCodeFailure(R.string.provider_device_code_failed, failure.diagnostic)
        }
    }

    private fun postOnce(url: String, body: ByteArray, contentType: String, token: CancellationToken): CodexDeviceCodeHttpResponse {
        val stage = when (url) {
            CodexDeviceCodeFlow.USER_CODE_ENDPOINT -> CodexAuthDiagnostic.Stage.REQUEST_CODE
            CodexDeviceCodeFlow.DEVICE_TOKEN_ENDPOINT -> CodexAuthDiagnostic.Stage.POLL_APPROVAL
            else -> CodexAuthDiagnostic.Stage.TOKEN_EXCHANGE
        }
        token.throwIfCancelled()
        val connection = URL(url).openConnection() as HttpURLConnection
        val unregister = token.registerCancelAction(connection::disconnect)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", contentType)
            CodexAuthConnectionRetry.connectBeforeBody(connection, stage, token)
            connection.outputStream.use { it.write(body) }
            token.throwIfCancelled()
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorBody = try {
                    connection.errorStream?.use { JSONObject(String(readBounded(it, stage), StandardCharsets.UTF_8)) }
                } catch (_: Exception) {
                    token.throwIfCancelled()
                    null
                }
                // Only sanitized metadata crosses into UI state, never the raw OAuth error body.
                return CodexDeviceCodeHttpResponse(status, JSONObject(), CodexAuthDiagnostic.http(stage, status, errorBody))
            }
            val bytes = connection.inputStream.use { readBounded(it, stage) }
            token.throwIfCancelled()
            val json = try { JSONObject(String(bytes, StandardCharsets.UTF_8)) }
                catch (_: org.json.JSONException) {
                    throw CodexDeviceCodeFailure(R.string.provider_device_code_invalid, CodexAuthDiagnostic.invalidJson(stage, status))
                }
            return CodexDeviceCodeHttpResponse(status, json)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CodexAuthConnectionRetry.BeforeBodyDnsFailure) {
            throw failure
        } catch (failure: CodexDeviceCodeFailure) {
            throw failure
        } catch (failure: Exception) {
            token.throwIfCancelled()
            throw CodexDeviceCodeFailure(R.string.provider_device_code_failed, CodexAuthDiagnostic.failure(stage, failure))
        } finally {
            unregister.run()
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream, stage: CodexAuthDiagnostic.Stage): ByteArray {
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > CodexDeviceCodeFlow.MAX_RESPONSE_BYTES) {
                    throw CodexDeviceCodeFailure(R.string.provider_device_code_invalid,
                        CodexAuthDiagnostic.validation(stage, "oversized_response"))
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
