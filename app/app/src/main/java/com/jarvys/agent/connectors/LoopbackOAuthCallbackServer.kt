package com.jarvys.agent.connectors

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Shared loopback OAuth callback listener for existing MCP and Full BYO desktop-client OAuth. */
class LoopbackOAuthCallbackServer private constructor(
    private val server: ServerSocket,
    private val path: String,
    private val maxLineChars: Int,
) : AutoCloseable {
    val redirectUri: String = "http://127.0.0.1:${server.localPort}$path"

    fun awaitCode(expectedState: String, timeoutMillis: Int): String {
        server.soTimeout = timeoutMillis
        server.accept().use { socket ->
            val request = readRequestLine(socket, maxLineChars)
            val callback = parseQuery(request, path)
            val state = callback["state"]
            val code = callback["code"]
            val authError = callback["error"]
            if (authError != null) {
                respond(socket, 400, "Google authorization was not completed. Return to Jarvys and retry.")
                error("OAuth authorization failed: ${authError.take(100)}")
            }
            if (state == null || !MessageDigest.isEqual(
                    state.toByteArray(StandardCharsets.UTF_8), expectedState.toByteArray(StandardCharsets.UTF_8),
                ) || code.isNullOrBlank() || code.length > MAX_CODE_CHARS) {
                respond(socket, 400, "Authorization response was invalid. Return to Jarvys and retry.")
                error("OAuth callback state or redirect did not match")
            }
            respond(socket, 200, "Authorization received. Return to Jarvys.")
            return code
        }
    }

    override fun close() = server.close()

    companion object {
        private const val MAX_CODE_CHARS = 8 * 1024
        private const val MAX_REQUEST_LINE_CHARS = 16 * 1024

        fun fixed(port: Int, path: String): LoopbackOAuthCallbackServer = bind(port, path)
        fun random(path: String): LoopbackOAuthCallbackServer = bind(0, path)

        private fun bind(port: Int, path: String): LoopbackOAuthCallbackServer {
            require(path.startsWith('/') && path.length <= 100)
            val socket = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
            return LoopbackOAuthCallbackServer(socket, path, MAX_REQUEST_LINE_CHARS)
        }

        private fun readRequestLine(socket: Socket, maxChars: Int): String {
            val input = socket.getInputStream()
            val line = StringBuilder()
            while (line.length <= maxChars) {
                val byte = input.read()
                if (byte < 0 || byte == '\n'.code) break
                if (byte != '\r'.code) line.append(byte.toChar())
            }
            require(line.length <= maxChars && line.startsWith("GET ")) { "OAuth callback request was invalid" }
            return line.toString()
        }

        private fun parseQuery(request: String, callbackPath: String): Map<String, String> {
            val target = request.substringAfter("GET ").substringBefore(' ')
            val uri = runCatching { URI("http://127.0.0.1$target") }.getOrNull()
                ?: error("OAuth callback URL was invalid")
            require(uri.path == callbackPath) { "OAuth callback path did not match" }
            val pairs = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).map { item ->
                val parts = item.split('=', limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
            require(pairs.groupingBy { it.first }.eachCount().values.none { it > 1 }) { "OAuth callback repeated a parameter" }
            return pairs.toMap()
        }

        private fun respond(socket: Socket, status: Int, message: String) {
            val body = "<html><body><h3>$message</h3></body></html>".toByteArray(StandardCharsets.UTF_8)
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Bad Request"}\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.write("Content-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(StandardCharsets.US_ASCII))
            out.write(body)
            out.flush()
        }
    }
}
