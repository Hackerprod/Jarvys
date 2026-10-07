package com.jarvys.agent.connectors

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

data class GitHubOAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long,
    val refreshTokenExpiresAtMillis: Long,
    val scopes: Set<String>,
)

data class GitHubOAuthResponse(val status: Int, val body: String)

fun interface GitHubOAuthTransport {
    fun post(url: String, form: Map<String, String>): GitHubOAuthResponse
}

/** GitHub OAuth App Device Flow; protocol functions use injectable network/time seams for JVM tests. */
object GitHubDeviceFlowProtocol {
    const val CLIENT_ID = "Ov23li0EEKL4QP7tNbnA"
    const val DEVICE_CODE_ENDPOINT = "https://github.com/login/device/code"
    const val TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token"
    const val VERIFICATION_URI = "https://github.com/login/device"
    const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    const val USER_SCOPE = "read:user"
    const val REPOSITORY_SCOPE = "repo"
    const val OFFLINE_SCOPE = "offline_access"
    const val MAX_RESPONSE_CHARS = 64 * 1024

    fun requestForm(clientId: String, includePrivateRepositories: Boolean): Map<String, String> {
        require(validClientId(clientId))
        return mapOf("client_id" to clientId,
            "scope" to buildList {
                add(USER_SCOPE)
                if (includePrivateRepositories) add(REPOSITORY_SCOPE)
                add(OFFLINE_SCOPE)
            }.joinToString(" "))
    }

    fun parseDeviceCode(status: Int, body: String): GitHubDeviceCode {
        require(status in 200..299) { "GitHub device authorization request failed (HTTP $status)" }
        require(body.length <= MAX_RESPONSE_CHARS) { "GitHub device authorization response was too large" }
        val json = JSONObject(body)
        json.optString("error").takeIf(String::isNotBlank)?.let { throw GitHubDeviceFlowException(it) }
        val deviceCode = json.optString("device_code")
        val userCode = json.optString("user_code")
        val verification = json.optString("verification_uri").ifBlank { json.optString("verification_url") }
        val uri = runCatching { java.net.URI(verification) }.getOrNull()
        require(deviceCode.length in 1..512 && userCode.length in 1..64) { "GitHub returned invalid device authorization codes" }
        require(uri?.scheme == "https" && uri.host == "github.com" && uri.rawUserInfo == null
            && uri.path == "/login/device" && uri.rawQuery == null && uri.fragment == null) {
            "GitHub returned an unexpected device verification URL"
        }
        return GitHubDeviceCode(deviceCode, userCode, VERIFICATION_URI,
            json.optLong("expires_in", 0).coerceIn(1, 3600), json.optLong("interval", 5).coerceIn(1, 120))
    }

    fun deviceCode(transport: GitHubOAuthTransport, clientId: String = CLIENT_ID,
                   includePrivateRepositories: Boolean = false): GitHubDeviceCode {
        val response = transport.post(DEVICE_CODE_ENDPOINT, requestForm(clientId, includePrivateRepositories))
        return parseDeviceCode(response.status, response.body)
    }

    fun pollForm(clientId: String, deviceCode: String): Map<String, String> {
        require(validClientId(clientId) && deviceCode.length in 1..512)
        return linkedMapOf("client_id" to clientId, "device_code" to deviceCode, "grant_type" to DEVICE_GRANT)
    }

    fun refreshForm(clientId: String, refreshToken: String): Map<String, String> {
        require(validClientId(clientId) && refreshToken.length in 1..4096)
        return linkedMapOf("client_id" to clientId, "grant_type" to "refresh_token", "refresh_token" to refreshToken)
    }

    fun pollForToken(
        transport: GitHubOAuthTransport,
        device: GitHubDeviceCode,
        clientId: String = CLIENT_ID,
        nowMillis: () -> Long = System::currentTimeMillis,
        waitMillis: (Long) -> Unit = Thread::sleep,
        isCancelled: () -> Boolean = { false },
    ): GitHubOAuthTokens {
        val deadline = nowMillis() + device.expiresInSeconds * 1_000
        var interval = device.intervalSeconds
        while (nowMillis() < deadline) {
            if (isCancelled()) throw GitHubDeviceFlowException("cancelled")
            val remaining = deadline - nowMillis()
            waitMillis(minOf(interval * 1_000, remaining).coerceAtLeast(1))
            if (isCancelled()) throw GitHubDeviceFlowException("cancelled")
            if (nowMillis() >= deadline) throw GitHubDeviceFlowException("expired_token")
            val response = transport.post(TOKEN_ENDPOINT, pollForm(clientId, device.deviceCode))
            require(response.body.length <= MAX_RESPONSE_CHARS) { "GitHub token response was too large" }
            val json = runCatching { JSONObject(response.body) }.getOrElse {
                if (response.status !in 200..299) throw GitHubDeviceFlowException("http_${response.status}")
                throw GitHubDeviceFlowException("invalid_response")
            }
            val error = json.optString("error")
            if (error.isNotBlank()) {
                when (error) {
                    "authorization_pending" -> Unit
                    "slow_down" -> interval = max(interval + SLOW_DOWN_INCREMENT_SECONDS,
                        json.optLong("interval", interval + SLOW_DOWN_INCREMENT_SECONDS).coerceAtMost(MAX_INTERVAL_SECONDS))
                    "expired_token", "token_expired" -> throw GitHubDeviceFlowException("expired_token")
                    "access_denied" -> throw GitHubDeviceFlowException("access_denied")
                    "device_flow_disabled" -> throw GitHubDeviceFlowException("device_flow_disabled")
                    else -> throw GitHubDeviceFlowException(error.take(80))
                }
                continue
            }
            if (response.status !in 200..299) throw GitHubDeviceFlowException("http_${response.status}")
            return parseTokens(json, nowMillis())
        }
        throw GitHubDeviceFlowException("expired_token")
    }

    fun parseTokens(json: JSONObject, nowMillis: Long): GitHubOAuthTokens {
        val access = json.optString("access_token")
        require(access.length in 1..4096) { "GitHub did not return a usable access token" }
        val expires = json.optLong("expires_in", 0).coerceIn(0, 31_536_000)
        val refreshExpiry = json.optLong("refresh_token_expires_in", 0).coerceIn(0, 31_536_000)
        val scopes = json.optString("scope").split(',', ' ').filter(String::isNotBlank).toSet()
        return GitHubOAuthTokens(access, json.optString("refresh_token").takeIf(String::isNotBlank),
            if (expires == 0L) 0L else nowMillis + expires * 1_000,
            if (refreshExpiry == 0L) 0L else nowMillis + refreshExpiry * 1_000, scopes)
    }

    fun mapError(error: Throwable?): GitHubDeviceFlowError = when ((error as? GitHubDeviceFlowException)?.code) {
        "authorization_pending" -> GitHubDeviceFlowError.PENDING
        "slow_down" -> GitHubDeviceFlowError.SLOW_DOWN
        "expired_token", "token_expired" -> GitHubDeviceFlowError.EXPIRED
        "access_denied" -> GitHubDeviceFlowError.DENIED
        "device_flow_disabled" -> GitHubDeviceFlowError.DISABLED
        "cancelled" -> GitHubDeviceFlowError.CANCELLED
        else -> GitHubDeviceFlowError.NETWORK
    }

    private fun validClientId(value: String) = value.length in 8..256 && value.none(Char::isISOControl)
    private const val SLOW_DOWN_INCREMENT_SECONDS = 5L
    private const val MAX_INTERVAL_SECONDS = 600L
}

enum class GitHubDeviceFlowError { PENDING, SLOW_DOWN, EXPIRED, DENIED, DISABLED, CANCELLED, NETWORK }

class GitHubDeviceFlowException(val code: String) : IllegalStateException("GitHub Device Flow failed: ${code.take(80)}")

/** Android browser/poll orchestration; the protocol itself remains testable without framework stubs. */
class GitHubDeviceFlowAttempt private constructor(
    private val cancelled: AtomicBoolean,
    private val worker: AtomicReference<Thread?>,
) : AutoCloseable {
    override fun close() { cancelled.set(true); worker.get()?.interrupt() }

    companion object {
        private val executor = Executors.newCachedThreadPool { Thread(it, "JarvysGitHubDeviceOAuth").apply { isDaemon = true } }
        private val main = Handler(Looper.getMainLooper())

        fun start(
            includePrivateRepositories: Boolean,
            onDeviceCode: (GitHubDeviceCode) -> Unit,
            onComplete: (Result<GitHubOAuthTokens>) -> Unit,
        ): GitHubDeviceFlowAttempt {
            val cancelled = AtomicBoolean(false)
            val worker = AtomicReference<Thread?>()
            executor.execute {
                worker.set(Thread.currentThread())
                val result = runCatching {
                    val code = GitHubDeviceFlowProtocol.deviceCode(GitHubUrlConnectionTransport,
                        includePrivateRepositories = includePrivateRepositories)
                    if (cancelled.get()) throw GitHubDeviceFlowException("cancelled")
                    main.post { if (!cancelled.get()) onDeviceCode(code) }
                    GitHubDeviceFlowProtocol.pollForToken(GitHubUrlConnectionTransport, code,
                        waitMillis = { Thread.sleep(it) }, isCancelled = cancelled::get)
                }
                val finalResult = if (cancelled.get()) Result.failure(GitHubDeviceFlowException("cancelled")) else result
                main.post { onComplete(finalResult) }
            }
            return GitHubDeviceFlowAttempt(cancelled, worker)
        }
    }
}

private object GitHubUrlConnectionTransport : GitHubOAuthTransport {
    override fun post(url: String, form: Map<String, String>): GitHubOAuthResponse {
        require(url == GitHubDeviceFlowProtocol.DEVICE_CODE_ENDPOINT || url == GitHubDeviceFlowProtocol.TOKEN_ENDPOINT)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = form.entries.joinToString("&") { (key, value) ->
                URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
            }
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(response.length <= GitHubDeviceFlowProtocol.MAX_RESPONSE_CHARS)
            return GitHubOAuthResponse(status, response)
        } finally { connection.disconnect() }
    }
}
