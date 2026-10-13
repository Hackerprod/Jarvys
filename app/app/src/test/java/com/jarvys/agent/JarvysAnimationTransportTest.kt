package com.jarvys.agent

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class JarvysAnimationTransportTest {
    private class Connection(uri: URI, private val response: Int = 200,
        private val headers: Map<String, String> = emptyMap()
    ) : HttpURLConnection(uri.toURL()) {
        var disconnections = 0
        var streamClosed = false
        override fun disconnect() { disconnections++ }
        override fun usingProxy() = false
        override fun connect() { }
        override fun getResponseCode() = response
        override fun getHeaderField(name: String): String? = headers[name]
        override fun getInputStream(): InputStream = object : ByteArrayInputStream(byteArrayOf(1, 2)) {
            override fun close() { streamClosed = true; super.close() }
        }
    }
    private fun withoutCookieHandler(action: () -> Unit) {
        val prior = CookieHandler.getDefault()
        try { CookieHandler.setDefault(null); action() } finally { CookieHandler.setDefault(prior) }
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected safe rejection") } catch (_: IOException) { }
    }
    @Test fun validatesAllowedRedirectAndReturnsIdentityEncodedStream() = withoutCookieHandler {
        val requested = mutableListOf<URI>()
        val connections = mutableListOf<Connection>()
        val transport = JarvysAnimationHttpTransport { uri ->
            requested.add(uri)
            Connection(uri, if (requested.size == 1) 302 else 200,
                if (requested.size == 1) mapOf("Location" to "https://jarvys-agent.com/asset.riv")
                else mapOf("Content-Length" to "2", "Content-Encoding" to "identity")
            ).also { connections.add(it) }
        }
        transport.get(URI(JarvysAnimationSite.INDEX), CancellationToken.cancellable()).use { response ->
            assertEquals(200, response.status)
            assertEquals(2L, response.contentLength)
            assertArrayEquals(byteArrayOf(1, 2), response.body.readBytes())
        }
        assertEquals(2, requested.size)
        assertEquals("jarvys-agent.com", requested.last().host)
        connections.forEach {
            assertEquals("GET", it.requestMethod)
            assertFalse(it.instanceFollowRedirects)
            assertFalse(it.useCaches)
            assertEquals("identity", it.getRequestProperty("Accept-Encoding"))
            assertNull(it.getRequestProperty("Authorization"))
            assertNull(it.getRequestProperty("Cookie"))
            assertEquals(1, it.disconnections)
        }
        assertTrue(connections.last().streamClosed)
    }
    @Test fun refusesOffSiteRedirectWithoutOpeningItsConnection() = withoutCookieHandler {
        var count = 0
        lateinit var connection: Connection
        val transport = JarvysAnimationHttpTransport { uri ->
            count++
            Connection(uri, 302, mapOf("Location" to "https://outside.example/asset.riv")).also { connection = it }
        }
        rejected { transport.get(URI(JarvysAnimationSite.INDEX), CancellationToken.cancellable()) }
        assertEquals(1, count)
        assertEquals(1, connection.disconnections)
    }
    @Test fun rejectsRedirectLoopAndMoreThanEightHops() = withoutCookieHandler {
        for (loop in listOf(true, false)) {
            var count = 0
            val transport = JarvysAnimationHttpTransport { uri ->
                count++
                Connection(uri, 307, mapOf("Location" to if (loop) JarvysAnimationSite.INDEX else "/next-$count"))
            }
            rejected { transport.get(URI(JarvysAnimationSite.INDEX), CancellationToken.cancellable()) }
            assertEquals(if (loop) 1 else 8, count)
        }
    }
    @Test fun rejectsUnexpectedEncodingLengthAndStatusAndClosesConnection() = withoutCookieHandler {
        val cases = listOf(200 to mapOf("Content-Encoding" to "gzip"), 200 to mapOf("Content-Length" to "-1"),
            200 to mapOf("Content-Length" to "bad"), 404 to emptyMap(), 302 to emptyMap())
        for ((status, headers) in cases) {
            lateinit var connection: Connection
            val transport = JarvysAnimationHttpTransport { uri -> Connection(uri, status, headers).also { connection = it } }
            rejected { transport.get(URI(JarvysAnimationSite.INDEX), CancellationToken.cancellable()) }
            assertEquals(1, connection.disconnections)
        }
    }
    @Test fun missingLengthIsAllowedAndCloseUnregistersCancellation() = withoutCookieHandler {
        val token = CancellationToken.cancellable()
        lateinit var connection: Connection
        val transport = JarvysAnimationHttpTransport { uri -> Connection(uri).also { connection = it } }
        transport.get(URI(JarvysAnimationSite.INDEX), token).use { assertEquals(-1L, it.contentLength) }
        token.cancel()
        assertEquals(1, connection.disconnections)
    }
    @Test fun cancelledTokenNeverOpensConnection() = withoutCookieHandler {
        val token = CancellationToken.cancellable().apply { cancel() }
        var opened = false
        val transport = JarvysAnimationHttpTransport { opened = true; throw AssertionError("Must not open") }
        try { transport.get(URI(JarvysAnimationSite.INDEX), token); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertFalse(opened)
    }
}
