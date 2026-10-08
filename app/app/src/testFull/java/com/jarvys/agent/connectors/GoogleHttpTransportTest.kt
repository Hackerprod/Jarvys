package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GoogleHttpTransportTest {
    private val scope = GoogleOAuthProtocol.DRIVE_FILE
    private val url = "https://www.googleapis.com/drive/v3/files/file-1"
    private data class Recorded(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray?)
    private class FakeTransport : GoogleHttpTransport {
        val calls = mutableListOf<Recorded>()
        val replies = ArrayDeque<GoogleBinaryResponse>()
        override fun execute(method: String, url: String, headers: Map<String, String>, body: String?): GoogleHttpResponse = error("binary expected")
        override fun executeBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray?,
                                  token: CancellationToken, maxResponseBytes: Int): GoogleBinaryResponse {
            calls += Recorded(method, url, headers, body)
            return replies.removeFirst()
        }
    }
    private class Connection(url: URL, private val bytes: ByteArray = byteArrayOf(), private val status: Int = 200,
                             private val stream: InputStream? = null) : HttpURLConnection(url) {
        val output = ByteArrayOutputStream()
        var disconnects = 0
        // Android's platform URLConnection supports PATCH; the desktop JDK base class does not.
        override fun setRequestMethod(value: String) { method = value }
        override fun disconnect() { disconnects++; stream?.close() }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream = stream ?: ByteArrayInputStream(bytes)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getOutputStream() = output
        override fun getContentLengthLong() = -1L
        override fun getHeaderFields(): Map<String, List<String>> = mapOf("ETag" to listOf("\"v1\""), "Set-Cookie" to listOf("private"))
    }

    @Test fun realConnectionContractKeepsBinaryMethodsHeadersBodyAndBounds() {
        val payload = byteArrayOf(0, 1, 127, -1, -32)
        val connection = Connection(URL(url), payload)
        val transport = GoogleConnectionTransport { connection }
        val response = transport.executeBytes("PATCH", url,
            mapOf("Authorization" to "Bearer fake-test-token", "Content-Type" to "application/octet-stream", "If-Match" to "\"old\""),
            payload, CancellationToken.cancellable(), 32)
        assertEquals("PATCH", connection.requestMethod)
        assertEquals("Bearer fake-test-token", connection.getRequestProperty("Authorization"))
        assertEquals("\"old\"", connection.getRequestProperty("If-Match"))
        assertArrayEquals(payload, connection.output.toByteArray())
        assertArrayEquals(payload, response.body)
        assertEquals("\"v1\"", response.headers["ETag"])
        assertFalse(response.headers.containsKey("Set-Cookie"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnects > 0)
        assertFalse(response.toString().contains("fake-test-token"))
        val oversized = runCatching { GoogleConnectionTransport { Connection(URL(url), ByteArray(33)) }
            .executeBytes("GET", url, emptyMap(), null, CancellationToken.cancellable(), 32) }.exceptionOrNull()
        assertEquals(GoogleApiFailure.RESPONSE_TOO_LARGE, (oversized as GoogleApiException).reason)
    }

    @Test fun exactEightMiBBinaryAllowedButLargerRequestOrResponseRejected() {
        val payload = ByteArray(GoogleApiLimits.MAX_TRANSFER_BYTES)
        val connection = Connection(URL(url), payload)
        val transport = GoogleConnectionTransport { connection }
        assertEquals(payload.size, transport.executeBytes("POST", url, emptyMap(), payload,
            CancellationToken.cancellable(), payload.size).body.size)
        val error = runCatching { transport.executeBytes("POST", url, emptyMap(), ByteArray(payload.size + 1),
            CancellationToken.cancellable(), payload.size) }.exceptionOrNull()
        assertEquals(GoogleApiFailure.RESPONSE_TOO_LARGE, (error as GoogleApiException).reason)
    }

    @Test fun cancellationClosesActiveBlockedIoAndDoesNotLeakProviderMessage() {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stream = object : InputStream() {
            override fun read(): Int { started.countDown(); closed.await(2, TimeUnit.SECONDS); throw IOException("secret provider text") }
            override fun close() { closed.countDown() }
        }
        val connection = Connection(URL(url), stream = stream)
        val token = CancellationToken.cancellable()
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            runCatching { GoogleConnectionTransport { connection }.executeBytes("GET", url, emptyMap(), null, token, 32) }
                .onFailure(failure::set)
        }.apply { start() }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        token.cancel()
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertTrue(connection.disconnects > 0)
        assertTrue(failure.get() is CancellationException)
        assertFalse(failure.get().message.orEmpty().contains("secret"))
    }

    @Test fun redirectHostPathPortAndCredentialQueryCannotEscapeService() {
        listOf("http://www.googleapis.com/drive/v3/files", "https://www.googleapis.com.evil.test/drive/v3/files",
            "https://evil.test/drive/v3/files", "https://www.googleapis.com:444/drive/v3/files",
            "https://www.googleapis.com/drive/v3/../secrets", "https://www.googleapis.com/drive/v3/files/%2e%2e/x",
            "https://www.googleapis.com/other/v3/files", "https://gmail.googleapis.com/gmail/v1/users/me/messages",
            "$url?access_token=do-not-send", "$url#fragment").forEach { invalid ->
            assertTrue(invalid, runCatching { GoogleHttpPolicy.validateEndpoint("GET", invalid, scope) }.isFailure)
        }
        GoogleHttpPolicy.validateEndpoint("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", scope)
        val error = runCatching { GoogleConnectionTransport { Connection(URL(url), status = 302) }
            .executeBytes("GET", url, emptyMap(), null, CancellationToken.cancellable(), 32) }.exceptionOrNull()
        assertEquals(GoogleApiFailure.REDIRECT_REJECTED, (error as GoogleApiException).reason)
        assertTrue(runCatching { GoogleHttpPolicy.validateAdditionalHeaders(mapOf("Authorization" to "override")) }.isFailure)
    }

    @Test fun safeGetHonorsRetryAfterWhileWritesNeverReplayOn429Or503() {
        val transport = FakeTransport().apply {
            replies += GoogleBinaryResponse(429, byteArrayOf(), mapOf("Retry-After" to "2"))
            replies += GoogleBinaryResponse(503, byteArrayOf())
            replies += GoogleBinaryResponse(200, "ok".toByteArray())
        }
        val sleeps = mutableListOf<Long>()
        val executor = GoogleRequestExecutor(transport, { delay, _ -> sleeps += delay }, { 100 })
        val response = executor.execute(scope, "GET", url, null, "application/json", CancellationToken.cancellable(), 32,
            emptyMap(), { "test-token" }, { error("no refresh") })
        assertEquals(200, response.status)
        assertEquals(listOf(2000L, 600L), sleeps)
        assertEquals(3, transport.calls.size)
        for (method in listOf("POST", "PATCH", "DELETE")) {
            val fake = FakeTransport().apply { replies += GoogleBinaryResponse(503, byteArrayOf()) }
            assertEquals(503, GoogleRequestExecutor(fake, { _, _ -> error("no retry") }).execute(scope, method,
                url, "write".toByteArray(), "application/json", CancellationToken.cancellable(), 32, emptyMap(),
                { "test-token" }, { error("no refresh") }).status)
            assertEquals(1, fake.calls.size)
        }
        assertNull(executor.retryDelay(mapOf("Retry-After" to "120"), 0))
        assertNull(executor.retryDelay(mapOf("Retry-After" to "-1"), 0))
    }

    @Test fun one401RefreshChangesBearerAnd403IsActionableWithoutRefresh() {
        val fake = FakeTransport().apply {
            replies += GoogleBinaryResponse(401, byteArrayOf())
            replies += GoogleBinaryResponse(403, """{"error":{"message":"access_token=secret", "errors":[{"reason":"insufficientPermissions"}]}}""".toByteArray())
        }
        var access = "old-test-token"
        var refreshes = 0
        val response = GoogleRequestExecutor(fake).execute(scope, "PATCH", url, "{}".toByteArray(), "application/json",
            CancellationToken.cancellable(), 1024, mapOf("If-Match" to "\"v1\""), { access }, { refreshes++; access = "new-test-token" })
        assertEquals(1, refreshes)
        assertEquals(listOf("Bearer old-test-token", "Bearer new-test-token"), fake.calls.map { it.headers["Authorization"] })
        val error = runCatching { GoogleRestEndpoints.requireSuccess(response) }.exceptionOrNull() as GoogleApiException
        assertEquals(403, error.status)
        assertEquals(GoogleApiFailure.INSUFFICIENT_SCOPE, error.reason)
        assertFalse(error.message.orEmpty().contains("secret"))
        val repeated = FakeTransport().apply { repeat(2) { replies += GoogleBinaryResponse(401, byteArrayOf()) } }
        var cleared = 0
        assertTrue(runCatching { GoogleRequestExecutor(repeated).execute(scope, "GET", url, null, "application/json",
            CancellationToken.cancellable(), 32, emptyMap(), { "test-token" }, { }, { cleared++ }) }.isFailure)
        assertEquals(2, repeated.calls.size)
        assertEquals(1, cleared)
    }

    @Test fun cancellationDuringBackoffPreventsAnotherRequestAndCompatibilityDoubleStillWorks() {
        val fake = FakeTransport().apply { replies += GoogleBinaryResponse(429, byteArrayOf()) }
        val token = CancellationToken.cancellable()
        assertTrue(runCatching { GoogleRequestExecutor(fake, { _, active -> active.cancel() }).execute(scope, "GET", url,
            null, "application/json", token, 32, emptyMap(), { "test-token" }, { }) }.exceptionOrNull() is CancellationException)
        assertEquals(1, fake.calls.size)
        val old = object : GoogleRestAuthorization {
            override fun isScopeGranted(scope: String) = true
            override fun request(scope: String, method: String, url: String, body: String?, contentType: String) = GoogleHttpResponse(200, "fixture")
        }
        assertEquals("fixture", old.requestCancellable(scope, "GET", url, token = CancellationToken.cancellable()).body)
        assertArrayEquals("fixture".toByteArray(), old.requestBytes(scope, "GET", url, token = CancellationToken.cancellable()).body)
        val cancelled = CancellationToken.cancellable().apply { cancel() }
        assertTrue(runCatching { old.requestCancellable(scope, "GET", url, token = cancelled) }.exceptionOrNull() is CancellationException)
    }

    @Test fun errorsAndGrantDiagnosticsNeverEchoUnknownProviderContentOrTokens() {
        val text = """{"error":{"message":"Bearer real-secret-value", "errors":[{"reason":"real-secret-value"}]}}"""
        val error = GoogleHttpPolicy.failure(403, text)
        assertEquals(GoogleApiFailure.ACCESS_DENIED, error.reason)
        assertFalse(error.toString().contains("real-secret"))
        assertFalse(GoogleHttpResponse(403, text).toString().contains("real-secret"))
        assertEquals("other", GoogleOAuthException("real-secret-value").providerError)
        assertFalse(GoogleIdentityGrant("real-secret-value", setOf(scope), "private@example.test").toString().contains("real-secret"))
    }

    @Test fun networkRetryIsBoundedForGetAndNeverReplaysAmbiguousWrites() {
        var calls = 0
        val transport = GoogleConnectionTransport {
            calls++
            throw IOException("Bearer must-not-be-disclosed")
        }
        val executor = GoogleRequestExecutor(transport, { _, _ -> }, { 0 })
        val getError = runCatching { executor.execute(scope, "GET", url, null, "application/json", CancellationToken.cancellable(),
            32, emptyMap(), { "test-token" }, { error("no refresh") }) }.exceptionOrNull() as GoogleApiException
        assertEquals(3, calls)
        assertEquals(GoogleApiFailure.NETWORK, getError.reason)
        assertNull(getError.cause)
        assertFalse(getError.message.orEmpty().contains("must-not"))
        calls = 0
        assertTrue(runCatching { executor.execute(scope, "POST", url, "{}".toByteArray(), "application/json", CancellationToken.cancellable(),
            32, emptyMap(), { "test-token" }, { error("no refresh") }) }.isFailure)
        assertEquals(1, calls)
    }
}
