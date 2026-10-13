package com.jarvys.agent

import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.URI
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Authenticator
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class JarvysAnimationIsolatedHttpTransportTest {
    companion object {
        @org.junit.BeforeClass @JvmStatic fun verifyRequiredHostGuard() {
            if (System.getProperty("jarvys.offlineGuard.okhttp") == "required") {
                Class.forName("guard.bootstrap.OfflineGuard", true, null)
                    .getMethod("verifyOkHttp", ClassLoader::class.java)
                    .invoke(null, okhttp3.OkHttpClient::class.java.classLoader)
            }
        }
    }

    private class TestBody : ResponseBody() {
        var closed = false
        private val buffer = object : ForwardingSource(Buffer().write(byteArrayOf(1, 2, 3))) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = 3L
        override fun source(): BufferedSource = buffer
    }
    private class FakeCall(private val value: Request, private val status: Int, private val headers: Map<String, String>) : Call {
        val body = TestBody()
        var cancelled = false
        private var executed = false
        override fun request(): Request = value
        override fun execute(): Response {
            executed = true
            return Response.Builder().request(value).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body)
                .apply { headers.forEach { (key, value) -> header(key, value) } }.build()
        }
        override fun enqueue(responseCallback: Callback) { throw UnsupportedOperationException("Synchronous fixture") }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall(value, status, headers)
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected safe rejection") } catch (_: IOException) { }
    }
    @Test fun dedicatedClientIsIndependentAndKeepsDefaultTlsVerification() {
        val client = JarvysAnimationIsolatedHttpTransport.newClient()
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertNull(client.cache)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertNotNull(client.x509TrustManager)
        assertNotNull(client.sslSocketFactory)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
    }
    @Test fun nonNullGlobalCookiesDoNotBlockAndRequestHasNoCredentials() {
        val prior = CookieHandler.getDefault()
        val handler = CookieManager()
        try {
            CookieHandler.setDefault(handler)
            var call: FakeCall? = null
            val transport = JarvysAnimationIsolatedHttpTransport(Call.Factory { request ->
                assertNull(request.header("Cookie")); assertNull(request.header("Authorization")); assertNull(request.header("Proxy-Authorization"))
                assertEquals("identity", request.header("Accept-Encoding"))
                FakeCall(request, 200, mapOf("Content-Length" to "3")).also { call = it }
            })
            transport.get(URI(JarvysAnimationSite.INDEX), CancellationToken.cancellable()).use { assertEquals(3L, it.contentLength) }
            assertSame(handler, CookieHandler.getDefault())
            assertTrue(call!!.body.closed)
        } finally { CookieHandler.setDefault(prior) }
    }
    @Test fun allowedRedirectThenProhibitedRedirectNeverOpensProhibitedHost() {
        val calls = mutableListOf<FakeCall>()
        val transport = JarvysAnimationIsolatedHttpTransport(Call.Factory { request ->
            assertTrue(request.url.host in setOf("www.jarvys-agent.com", "jarvys-agent.com"))
            FakeCall(request, 302, mapOf("Location" to if (calls.isEmpty()) "https://jarvys-agent.com/next" else "https://outside.example/no"))
                .also { calls.add(it) }
        })
        rejected { transport.get(URI(JarvysAnimationSite.INDEX), CancellationToken.cancellable()) }
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.body.closed })
    }
    @Test fun responses401And407DoNotRetryAndCloseTheirBodies() {
        for (status in listOf(401, 407)) {
            val calls = mutableListOf<FakeCall>()
            val transport = JarvysAnimationIsolatedHttpTransport(Call.Factory { request ->
                FakeCall(request, status, emptyMap()).also { calls.add(it) }
            })
            rejected { transport.get(URI(JarvysAnimationSite.INDEX), CancellationToken.cancellable()) }
            assertEquals(1, calls.size)
            assertTrue(calls.single().body.closed)
        }
    }
    @Test fun cancelCancelsActiveCallAndCloseUnregistersIt() {
        for (closeFirst in listOf(false, true)) {
            lateinit var call: FakeCall
            val token = CancellationToken.cancellable()
            val transport = JarvysAnimationIsolatedHttpTransport(Call.Factory { request -> FakeCall(request, 200, emptyMap()).also { call = it } })
            val response = transport.get(URI(JarvysAnimationSite.INDEX), token)
            if (closeFirst) response.close()
            token.cancel()
            assertEquals(!closeFirst, call.cancelled)
            response.close()
            assertTrue(call.body.closed)
        }
    }
    @Test fun invalidEncodingAndLengthAreRejected() {
        for (headers in listOf(mapOf("Content-Encoding" to "gzip"), mapOf("Content-Length" to "-1"), mapOf("Content-Length" to "bad"))) {
            lateinit var call: FakeCall
            val transport = JarvysAnimationIsolatedHttpTransport(Call.Factory { request -> FakeCall(request, 200, headers).also { call = it } })
            rejected { transport.get(URI(JarvysAnimationSite.INDEX), CancellationToken.cancellable()) }
            assertTrue(call.body.closed)
        }
    }
}
