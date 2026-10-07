package com.jarvys.agent.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class McpOAuthRequestRetryTest {
    @Test fun bearer401RefreshesOnceRotatesRefreshTokenAndRetriesRequest() {
        val requests = AtomicInteger()
        val refreshes = AtomicInteger()
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val serverThread = Thread {
            repeat(3) {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    val path = reader.readLine().orEmpty().split(' ').getOrElse(1) { "" }
                    var authorization: String? = null
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Authorization:", true)) authorization = header.substringAfter(':').trim()
                    }
                    val (status, body) = when {
                        path.startsWith("/token") -> {
                            refreshes.incrementAndGet()
                            200 to """{"access_token":"fresh-access","refresh_token":"rotated-refresh","expires_in":3600,"token_type":"Bearer"}"""
                        }
                        else -> {
                            requests.incrementAndGet()
                            if (authorization == "Bearer fresh-access") 200 to "ok" else 401 to "unauthorized"
                        }
                    }
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                    writer.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Unauthorized"}\r\n")
                    writer.write("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                    writer.flush()
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            var access = "stale-access"
            var refresh = "old-refresh"
            val response = McpOAuthRequestRetry.execute(
                oauthEnabled = true,
                refreshOnce = {
                    val connection = URL("$base/token").openConnection() as java.net.HttpURLConnection
                    val json = connection.inputStream.use { JSONObject(String(it.readBytes(), StandardCharsets.UTF_8)) }
                    access = json.getString("access_token")
                    refresh = McpOAuthRequestRetry.rotatedRefreshToken(json, refresh)
                },
                request = {
                    val connection = URL("$base/mcp").openConnection() as java.net.HttpURLConnection
                    connection.setRequestProperty("Authorization", "Bearer $access")
                    val code = connection.responseCode
                    if (code == 401) throw McpTransportClient.McpAuthorizationException("401", "Bearer")
                    code
                },
            )

            assertEquals(200, response)
            assertEquals(2, requests.get())
            assertEquals(1, refreshes.get())
            assertEquals("rotated-refresh", refresh)
        } finally {
            server.close()
            serverThread.join(2_000)
        }
    }

    @Test fun repeated401RequiresReauthorizationAfterExactlyOneRefresh() {
        var requests = 0
        var refreshes = 0
        val failure = assertThrows(McpReauthRequiredException::class.java) {
            McpOAuthRequestRetry.execute(true, { refreshes++ }) {
                requests++
                throw McpTransportClient.McpAuthorizationException("401", "Bearer")
            }
        }
        assertEquals(2, requests)
        assertEquals(1, refreshes)
        assertEquals("MCP authorization failed after one refresh; reauthorize this service", failure.message)
    }

    @Test fun forbiddenIsDistinctAndDoesNotAttemptTokenRefresh() {
        var refreshes = 0
        val failure = assertThrows(McpPermissionRequiredException::class.java) {
            McpOAuthRequestRetry.execute(true, { refreshes++ }) {
                throw McpPermissionRequiredException("scope missing")
            }
        }
        assertEquals("scope missing", failure.message)
        assertEquals(0, refreshes)
    }
}
