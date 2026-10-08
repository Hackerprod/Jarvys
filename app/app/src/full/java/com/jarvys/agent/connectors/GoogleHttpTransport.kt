package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/** Bodies may contain private data; never include them in diagnostics or generated toString(). */
data class GoogleHttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    override fun toString(): String = "GoogleHttpResponse(status=$status)"
}
data class GoogleBinaryResponse(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    override fun toString(): String = "GoogleBinaryResponse(status=$status, bytes=${body.size})"
}

enum class GoogleApiFailure { UNAUTHORIZED, INSUFFICIENT_SCOPE, ACCESS_DENIED, QUOTA, NOT_FOUND, CONFLICT, RATE_LIMITED,
    TRANSIENT, REDIRECT_REJECTED, RESPONSE_TOO_LARGE, NETWORK, OTHER }

class GoogleApiException(val status: Int?, val reason: GoogleApiFailure) : IllegalStateException(
    "Google API" + (status?.let { " HTTP $it" } ?: "") + ": " + when (reason) {
        GoogleApiFailure.UNAUTHORIZED -> "authorization expired; reconnect this feature"
        GoogleApiFailure.INSUFFICIENT_SCOPE -> "enable the required feature permission and authorize it again"
        GoogleApiFailure.ACCESS_DENIED -> "access denied; check the account, file sharing, and administrator restrictions"
        GoogleApiFailure.QUOTA -> "quota exceeded; retry later or check the Google project quota"
        GoogleApiFailure.NOT_FOUND -> "item was not found or is not accessible to this account"
        GoogleApiFailure.CONFLICT -> "item changed; read the latest version before trying again"
        GoogleApiFailure.RATE_LIMITED -> "rate limited; retry later"
        GoogleApiFailure.TRANSIENT -> "service temporarily unavailable; retry later"
        GoogleApiFailure.REDIRECT_REJECTED -> "redirect refused to protect Google authorization"
        GoogleApiFailure.RESPONSE_TOO_LARGE -> "transfer exceeded the allowed byte limit"
        GoogleApiFailure.NETWORK -> "connection failed; check connectivity (a write may have completed, so verify before retrying)"
        GoogleApiFailure.OTHER -> "request failed; check the selected operation and parameters"
    },
)

/** Existing request-only test doubles continue to work. Production overrides both cancellable seams. */
interface GoogleRestAuthorization {
    /** Opaque, process-local approval context; it is never an account identifier or credential. */
    fun currentAuthorizationEpoch(): Long = 0L
    fun isScopeGranted(scope: String): Boolean
    fun request(scope: String, method: String, url: String, body: String? = null,
                contentType: String = "application/json"): GoogleHttpResponse
    fun requestCancellable(scope: String, method: String, url: String, body: String? = null,
                           contentType: String = "application/json", token: CancellationToken,
                           requestHeaders: Map<String, String> = emptyMap(),
                           expectedAuthorizationEpoch: Long? = null): GoogleHttpResponse {
        token.throwIfCancelled()
        check(expectedAuthorizationEpoch == null || expectedAuthorizationEpoch == currentAuthorizationEpoch()) {
            "Google connection changed; prepare and approve this operation again"
        }
        GoogleHttpPolicy.validateAdditionalHeaders(requestHeaders)
        body?.let { GoogleHttpPolicy.requireSize(it.toByteArray(Charsets.UTF_8).size, GoogleApiLimits.MAX_TRANSFER_BYTES) }
        val response = request(scope, method, url, body, contentType)
        token.throwIfCancelled()
        GoogleHttpPolicy.requireSize(response.body.toByteArray(Charsets.UTF_8).size, GoogleApiLimits.MAX_RESPONSE_BYTES)
        return response
    }
    fun requestBytes(scope: String, method: String, url: String, body: ByteArray? = null,
                     contentType: String = "application/octet-stream", token: CancellationToken,
                     maxResponseBytes: Int = GoogleApiLimits.MAX_TRANSFER_BYTES,
                     requestHeaders: Map<String, String> = emptyMap(),
                     expectedAuthorizationEpoch: Long? = null): GoogleBinaryResponse {
        token.throwIfCancelled()
        check(expectedAuthorizationEpoch == null || expectedAuthorizationEpoch == currentAuthorizationEpoch()) {
            "Google connection changed; prepare and approve this operation again"
        }
        GoogleHttpPolicy.validateLimit(maxResponseBytes)
        GoogleHttpPolicy.validateAdditionalHeaders(requestHeaders)
        body?.let { GoogleHttpPolicy.requireSize(it.size, GoogleApiLimits.MAX_TRANSFER_BYTES) }
        // Compatibility seam for text-only fake implementations; real binary uploads must override it.
        val textBody = body?.toString(Charsets.UTF_8)
        require(body == null || body.contentEquals(textBody!!.toByteArray(Charsets.UTF_8))) { "Binary request transport is unavailable" }
        val response = request(scope, method, url, textBody, contentType)
        token.throwIfCancelled()
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        GoogleHttpPolicy.requireSize(bytes.size, maxResponseBytes)
        return GoogleBinaryResponse(response.status, bytes, response.headers)
    }
}

internal fun interface GoogleHttpTransport {
    fun execute(method: String, url: String, headers: Map<String, String>, body: String?): GoogleHttpResponse
    fun executeBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray?,
                     token: CancellationToken, maxResponseBytes: Int): GoogleBinaryResponse {
        token.throwIfCancelled()
        val textBody = body?.toString(Charsets.UTF_8)
        require(body == null || body.contentEquals(textBody!!.toByteArray(Charsets.UTF_8))) { "Binary request transport is unavailable" }
        val response = execute(method, url, headers, textBody)
        token.throwIfCancelled()
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        GoogleHttpPolicy.requireSize(bytes.size, maxResponseBytes)
        return GoogleBinaryResponse(response.status, bytes, response.headers)
    }
}

internal object GoogleHttpPolicy {
    private val methods = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
    fun validateLimit(limit: Int) { require(limit in 1..GoogleApiLimits.MAX_TRANSFER_BYTES) { "Invalid Google transfer limit" } }
    fun requireSize(size: Int, limit: Int) {
        if (size > limit) throw GoogleApiException(null, GoogleApiFailure.RESPONSE_TOO_LARGE)
    }
    fun validateAdditionalHeaders(headers: Map<String, String>) {
        require(headers.all { (key, value) -> key.equals("If-Match", true) && value.length in 1..1024 && value.none(Char::isISOControl) }) {
            "Unsupported Google request header"
        }
    }
    fun validateEndpoint(method: String, url: String, scope: String? = null) {
        val uri = runCatching { URI(url) }.getOrNull()
        require(method in methods && uri != null && uri.scheme == "https" && uri.port in setOf(-1, 443) &&
            uri.userInfo == null && uri.fragment == null && uri.normalize().rawPath == uri.rawPath &&
            !Regex("(?i)%2f|%5c|%2e").containsMatchIn(uri.rawPath.orEmpty()) && !uri.path.orEmpty().contains('\\')) {
            "Google API endpoint is not allowed"
        }
        val gmail = uri.host == "gmail.googleapis.com" && uri.path.startsWith("/gmail/v1/users/me/")
        val drive = uri.host == "www.googleapis.com" &&
            listOf("/drive/v3/files", "/upload/drive/v3/files").any { uri.path == it || uri.path.startsWith("$it/") }
        val oauth = uri.host == "oauth2.googleapis.com" && uri.path in setOf("/token", "/revoke") &&
            method == "POST" && uri.rawQuery == null
        val valid = when {
            scope == null -> gmail || drive || oauth
            scope.startsWith("https://www.googleapis.com/auth/gmail.") -> gmail
            scope in setOf(GoogleOAuthProtocol.DRIVE_READ, GoogleOAuthProtocol.DRIVE_FILE) -> drive
            else -> false
        }
        require(valid) { "Google API endpoint did not match the requested service" }
        require(!Regex("(?i)(^|&)(access_token|token|authorization)=").containsMatchIn(uri.rawQuery.orEmpty())) {
            "Credentials must not be sent in Google API URLs"
        }
    }
    fun failure(status: Int, body: String): GoogleApiException {
        // Only allowlisted machine reasons are inspected. Never reflect provider message text or unknown values.
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val reasons = buildSet {
            error?.optString("status")?.let(::add)
            val details = error?.optJSONArray("errors")
            for (i in 0 until (details?.length() ?: 0).coerceAtMost(10)) details?.optJSONObject(i)?.optString("reason")?.let(::add)
        }
        val reason = when {
            status == 401 -> GoogleApiFailure.UNAUTHORIZED
            status == 403 && reasons.any { it in setOf("insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT") } -> GoogleApiFailure.INSUFFICIENT_SCOPE
            status == 403 && reasons.any { it in setOf("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded", "dailyLimitExceeded", "storageQuotaExceeded") } -> GoogleApiFailure.QUOTA
            status == 403 -> GoogleApiFailure.ACCESS_DENIED
            status == 404 -> GoogleApiFailure.NOT_FOUND
            status == 409 || status == 412 -> GoogleApiFailure.CONFLICT
            status == 429 -> GoogleApiFailure.RATE_LIMITED
            status in 500..599 -> GoogleApiFailure.TRANSIENT
            status in 300..399 -> GoogleApiFailure.REDIRECT_REJECTED
            else -> GoogleApiFailure.OTHER
        }
        return GoogleApiException(status, reason)
    }
}

/** One refresh for an explicit 401. Only GET may retry transient failures; writes are never retried ambiguously. */
internal class GoogleRequestExecutor(
    private val transport: GoogleHttpTransport,
    private val sleep: (Long, CancellationToken) -> Unit = { millis, token ->
        val stopped = CountDownLatch(1)
        val unregister = token.registerCancelAction { stopped.countDown() }
        try { token.throwIfCancelled(); stopped.await(millis, TimeUnit.MILLISECONDS); token.throwIfCancelled() }
        finally { unregister.run() }
    },
    private val jitter: () -> Long = { ThreadLocalRandom.current().nextLong(251) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun execute(scope: String, method: String, url: String, body: ByteArray?, contentType: String,
                token: CancellationToken, maxResponseBytes: Int, requestHeaders: Map<String, String>,
                accessToken: () -> String, refresh: () -> Unit, repeatedUnauthorized: () -> Unit = {}): GoogleBinaryResponse {
        GoogleHttpPolicy.validateEndpoint(method, url, scope)
        GoogleHttpPolicy.validateLimit(maxResponseBytes)
        GoogleHttpPolicy.validateAdditionalHeaders(requestHeaders)
        require(contentType.length in 1..200 && contentType.none(Char::isISOControl)) { "Invalid Google content type" }
        body?.let { GoogleHttpPolicy.requireSize(it.size, GoogleApiLimits.MAX_TRANSFER_BYTES) }
        var refreshed = false
        var retries = 0
        while (true) {
            token.throwIfCancelled()
            val access = accessToken()
            token.throwIfCancelled()
            val headers = mapOf("Authorization" to "Bearer $access", "Content-Type" to contentType,
                "Accept" to "*/*") + requestHeaders
            val response = try {
                transport.executeBytes(method, url, headers, body, token, maxResponseBytes)
            } catch (error: GoogleApiException) {
                token.throwIfCancelled()
                if (method != "GET" || error.reason != GoogleApiFailure.NETWORK || retries >= 2) throw error
                sleep(backoff(retries++), token)
                continue
            }
            token.throwIfCancelled()
            GoogleHttpPolicy.requireSize(response.body.size, maxResponseBytes)
            if (response.status == 401) {
                if (refreshed) { repeatedUnauthorized(); throw GoogleApiException(401, GoogleApiFailure.UNAUTHORIZED) }
                refreshed = true
                refresh()
                continue
            }
            if (method == "GET" && response.status in setOf(429, 500, 502, 503, 504) && retries < 2) {
                val delay = retryDelay(response.headers, retries) ?: return response
                retries++
                sleep(delay, token)
                continue
            }
            return response
        }
    }
    private fun backoff(attempt: Int): Long = (250L shl attempt) + jitter().coerceIn(0, 250)
    internal fun retryDelay(headers: Map<String, String>, attempt: Int): Long? {
        val raw = headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value
        val required = raw?.trim()?.let { value ->
            value.toLongOrNull()?.let { if (it < 0 || it > 8) return null else it * 1000 }
                ?: runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now()).coerceAtLeast(0) }.getOrNull()
        }
        if (required != null && required > 8_000) return null
        return maxOf(required ?: 0, backoff(attempt)).coerceAtMost(8_000)
    }
}

/** Injectable URL connection factory keeps HTTP contract tests hermetic, including cancellation during IO. */
internal class GoogleConnectionTransport(private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) : GoogleHttpTransport {
    override fun execute(method: String, url: String, headers: Map<String, String>, body: String?): GoogleHttpResponse {
        val response = executeBytes(method, url, headers, body?.toByteArray(Charsets.UTF_8),
            CancellationToken.uncancellable(), GoogleApiLimits.MAX_RESPONSE_BYTES)
        return GoogleHttpResponse(response.status, response.body.toString(Charsets.UTF_8), response.headers)
    }
    override fun executeBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray?,
                              token: CancellationToken, maxResponseBytes: Int): GoogleBinaryResponse {
        GoogleHttpPolicy.validateEndpoint(method, url)
        GoogleHttpPolicy.validateLimit(maxResponseBytes)
        body?.let { GoogleHttpPolicy.requireSize(it.size, GoogleApiLimits.MAX_TRANSFER_BYTES) }
        token.throwIfCancelled()
        val connection = try { openConnection(URL(url)) } catch (error: IOException) {
            token.throwIfCancelled()
            throw GoogleApiException(null, GoogleApiFailure.NETWORK)
        }
        val unregister = token.registerCancelAction(connection::disconnect)
        try {
            token.throwIfCancelled()
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { output ->
                    var offset = 0
                    while (offset < body.size) {
                        token.throwIfCancelled()
                        val length = minOf(8192, body.size - offset)
                        output.write(body, offset, length)
                        offset += length
                    }
                }
            }
            token.throwIfCancelled()
            val status = connection.responseCode
            // Do not follow Location, even to another Google host. Bearer credentials stay on the original request.
            if (status in 300..399) throw GoogleApiException(status, GoogleApiFailure.REDIRECT_REJECTED)
            val length = connection.contentLengthLong
            if (length > maxResponseBytes) throw GoogleApiException(status, GoogleApiFailure.RESPONSE_TOO_LARGE)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    token.throwIfCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    GoogleHttpPolicy.requireSize(output.size() + count, maxResponseBytes)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            token.throwIfCancelled()
            val responseHeaders = connection.headerFields.orEmpty().entries.filter { (name, _) ->
                name != null && name.lowercase(Locale.ROOT) in setOf("etag", "retry-after", "content-type", "content-length")
            }.associate { (name, values) -> requireNotNull(name) to values.joinToString(", ") }
            return GoogleBinaryResponse(status, bytes, responseHeaders)
        } catch (error: IOException) {
            token.throwIfCancelled()
            throw GoogleApiException(null, GoogleApiFailure.NETWORK)
        } finally { unregister.run(); connection.disconnect() }
    }
}

internal object GoogleUrlConnectionTransport : GoogleHttpTransport by GoogleConnectionTransport()
