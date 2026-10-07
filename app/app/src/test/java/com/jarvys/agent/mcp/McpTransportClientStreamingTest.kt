package com.jarvys.agent.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private data class McpTestSeenRequest(
    val method: String,
    val path: String,
    val request: JSONObject,
    val sessionId: String?,
    val lastEventId: String?,
)

private data class McpTestParsedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

private class McpTestLocalResponse(private val socket: Socket) {
    private val output = socket.getOutputStream()

    @Synchronized fun sendFixed(
        status: Int,
        contentType: String?,
        body: ByteArray = ByteArray(0),
        headers: Map<String, String> = emptyMap(),
    ) {
        writeHeaders(status, contentType, body.size.toLong(), false, headers)
        if (body.isNotEmpty()) output.write(body)
        output.flush()
        socket.close()
    }

    @Synchronized fun beginChunked(status: Int, contentType: String, headers: Map<String, String> = emptyMap()) {
        writeHeaders(status, contentType, 0L, true, headers)
        output.flush()
    }

    @Synchronized fun writeChunk(bytes: ByteArray) {
        output.write(bytes.size.toString(16).toByteArray(StandardCharsets.US_ASCII))
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    @Synchronized fun finishChunked() {
        runCatching {
            output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }
        runCatching { socket.close() }
    }

    private fun writeHeaders(
        status: Int,
        contentType: String?,
        length: Long,
        chunked: Boolean,
        headers: Map<String, String>,
    ) {
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Fixture"
        }
        output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.US_ASCII))
        if (contentType != null) output.write("Content-Type: $contentType\r\n".toByteArray(StandardCharsets.US_ASCII))
        headers.forEach { (name, value) -> output.write("$name: $value\r\n".toByteArray(StandardCharsets.ISO_8859_1)) }
        if (chunked) output.write("Transfer-Encoding: chunked\r\n".toByteArray(StandardCharsets.US_ASCII))
        else output.write("Content-Length: $length\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
    }
}

class McpTransportClientStreamingTest {
    @Test fun streamableHttpAcceptsATwoHundredKbSingleDataLine() {
        val tools = bigToolList(24, 8_500)
        val sampleBody = sseData(toolsResult("sample-id", tools).toString())
        assertTrue(sampleBody.toByteArray(StandardCharsets.UTF_8).size > 200 * 1024)
        assertTrue(sampleBody.toByteArray(StandardCharsets.UTF_8).size < 1024 * 1024)
        LocalMcpHttpServer().use { server ->
            server.toolsList = { request, exchange ->
                val body = sseData(toolsResult(request.optString("id"), tools).toString())
                server.sendSse(exchange, splitInsideUtf8(body))
            }
            val session = McpTransportClient.connect(server.config(McpTransport.STREAMABLE_HTTP), null, {})
            try {
                assertEquals(24, session.tools.size)
                assertEquals("large_23", session.tools.last().wireName)
            } finally { session.close() }
        }
    }

    @Test fun legacySseAcceptsATwoHundredKbSingleDataLine() {
        val tools = bigToolList(24, 8_500)
        LocalMcpHttpServer().use { server ->
            server.legacyMessage = { request -> when (request.optString("method")) {
                "initialize" -> server.initializeResult(request)
                "notifications/initialized" -> null
                "tools/list" -> toolsResult(request.optString("id"), tools)
                else -> rpcResult(request.optString("id"), JSONObject())
            } }
            val session = McpTransportClient.connect(server.config(McpTransport.LEGACY_SSE), null, {})
            try {
                assertEquals(24, session.tools.size)
                assertEquals("large_23", session.tools.last().wireName)
            } finally {
                // Close the test peer first to release LegacySseSession's blocking stream reader.
                server.close()
                session.close()
            }
        }
    }

    @Test fun sseLineAndEventOverOneMiBFailWithTheExistingEventLimitMessage() {
        val oversized = bigToolList(125, 9_000)
        val body = sseData(toolsResult("request-tools", oversized).toString())
        assertTrue(body.toByteArray(StandardCharsets.UTF_8).size > 1024 * 1024)
        LocalMcpHttpServer().use { server ->
            server.toolsList = { _, exchange -> server.sendSse(exchange, listOf(body.toByteArray(StandardCharsets.UTF_8))) }
            val error = runCatching {
                McpTransportClient.connect(server.config(McpTransport.STREAMABLE_HTTP), null, {})
            }.exceptionOrNull()
            assertTrue("Expected IllegalStateException but got $error", error is IllegalStateException)
            assertEquals("MCP SSE event exceeded the 1 MiB limit", error?.message)
        }
    }

    @Test fun streamableSseParsesCrlfCommentsAndMessageEvent() {
        val tool = smallTool("crlf_tool", "Café reader")
        LocalMcpHttpServer().use { server ->
            server.toolsList = { request, exchange ->
                val data = toolsResult(request.optString("id"), listOf(tool)).toString()
                server.sendSse(exchange, listOf((": ping\r\n\r\nevent: message\r\ndata:$data\r\n\r\n")
                    .toByteArray(StandardCharsets.UTF_8)))
            }
            val session = McpTransportClient.connect(server.config(McpTransport.STREAMABLE_HTTP), null, {})
            try { assertEquals(listOf("crlf_tool"), session.tools.map { it.wireName }) }
            finally { session.close() }
        }
    }

    @Test fun streamableSseJoinsMultipleDataFieldsWithNewline() {
        val tool = smallTool("multiline_tool")
        LocalMcpHttpServer().use { server ->
            server.toolsList = { request, exchange ->
                val serialized = toolsResult(request.optString("id"), listOf(tool)).toString()
        val split = serialized.indexOf(',') + 1
                assertTrue(split > 0)
                val body = "data:${serialized.substring(0, split)}\r\ndata:${serialized.substring(split)}\r\n\r\n"
                server.sendSse(exchange, listOf(body.toByteArray(StandardCharsets.UTF_8)))
            }
            val session = McpTransportClient.connect(server.config(McpTransport.STREAMABLE_HTTP), null, {})
            try { assertEquals(listOf("multiline_tool"), session.tools.map { it.wireName }) }
            finally { session.close() }
        }
    }

    @Test fun streamableSseKeepsEventIdAndRetryWhenResumingAnIncompleteResponse() {
        val resumed = AtomicInteger()
        val toolListRequestId = AtomicReference<String>()
        LocalMcpHttpServer().use { server ->
            server.toolsList = { request, exchange ->
                toolListRequestId.set(request.optString("id"))
                if (resumed.getAndIncrement() == 0) {
                    val ping = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/ping\"}"
                    val split = ping.indexOf(",\"method\":") + 1
                    val body = ": keepalive\r\n\r\nid: resume-token\r\nretry: 0\r\nevent: message\r\n" +
                        "data:${ping.substring(0, split)}\r\ndata:${ping.substring(split)}\r\n\r\n"
                    server.sendSse(exchange, listOf(body.toByteArray(StandardCharsets.UTF_8)))
                } else {
                    val response = toolsResult(request.optString("id"), listOf(smallTool("resumed_tool"))).toString()
                    server.sendSse(exchange, listOf(sseData(response).toByteArray(StandardCharsets.UTF_8)))
                }
            }
            server.streamableGet = { _, exchange ->
                val response = toolsResult(toolListRequestId.get(), listOf(smallTool("resumed_tool"))).toString()
                server.sendSse(exchange, listOf(sseData(response).toByteArray(StandardCharsets.UTF_8)))
            }
            val session = McpTransportClient.connect(server.config(McpTransport.STREAMABLE_HTTP), null, {})
            try {
                assertEquals("resumed_tool", session.tools.single().wireName)
                assertTrue(server.requests.any { it.method == "GET" && it.lastEventId == "resume-token" })
                assertEquals(1, resumed.get())
            } finally { session.close() }
        }
    }

    @Test fun streamableSseDecodesUtf8CharacterSplitBetweenTcpChunks() {
        LocalMcpHttpServer().use { server ->
            server.toolsList = { request, exchange ->
                val response = toolsResult(request.optString("id"), listOf(smallTool("utf8_tool", "Café"))).toString()
                val bytes = sseData(response).toByteArray(StandardCharsets.UTF_8)
                server.sendSse(exchange, splitInsideUtf8(String(bytes, StandardCharsets.UTF_8)))
            }
            val session = McpTransportClient.connect(server.config(McpTransport.STREAMABLE_HTTP), null, {})
            try {
                assertEquals("Café", session.tools.single().description)
            } finally { session.close() }
        }
    }

    @Test fun streamableHttpStillAcceptsApplicationJsonResponses() {
        LocalMcpHttpServer().use { server ->
            server.toolsList = { request, exchange ->
                server.sendJson(exchange, toolsResult(request.optString("id"), listOf(smallTool("json_tool"))))
            }
            val session = McpTransportClient.connect(server.config(McpTransport.STREAMABLE_HTTP), null, {})
            try { assertEquals("json_tool", session.tools.single().wireName) }
            finally { session.close() }
        }
    }

    @Test fun toolsListNextCursorDiscoversBothPages() {
        val pages = AtomicInteger()
        LocalMcpHttpServer().use { server ->
            server.toolsList = { request, exchange ->
                val page = pages.getAndIncrement()
                val item = if (page == 0) smallTool("page_one") else smallTool("page_two")
                val result = toolsResult(request.optString("id"), listOf(item))
                if (page == 0) result.getJSONObject("result").put("nextCursor", "cursor-two")
                server.sendJson(exchange, result)
            }
            val session = McpTransportClient.connect(server.config(McpTransport.STREAMABLE_HTTP), null, {})
            try {
                assertEquals(listOf("page_one", "page_two"), session.tools.map { it.wireName })
                assertEquals("cursor-two", server.requests.last { it.method == "POST" && it.request.optString("method") == "tools/list" }
                    .request.getJSONObject("params").optString("cursor"))
            } finally { session.close() }
        }
    }

    @Test fun http401ChallengeMapsToMcpAuthorizationException() {
        LocalMcpHttpServer().use { server ->
            server.initialize = { _, exchange ->
                server.sendEmpty(exchange, 401, mapOf("WWW-Authenticate" to "Bearer realm=\"local-mcp\""))
            }
            val error = runCatching {
                McpTransportClient.connect(server.config(McpTransport.STREAMABLE_HTTP), null, {})
            }.exceptionOrNull()
            assertTrue("Expected MCP authorization error but got $error", error is McpTransportClient.McpAuthorizationException)
            assertEquals("Bearer realm=\"local-mcp\"", (error as McpTransportClient.McpAuthorizationException).challenge)
        }
    }

    @Test fun http404WithSessionReinitializesExactlyOnce() {
        val initializations = AtomicInteger()
        val lists = AtomicInteger()
        LocalMcpHttpServer().use { server ->
            server.initialize = { request, exchange ->
                val sessionNumber = initializations.incrementAndGet()
                server.sendJson(exchange, server.initializeResult(request),
                    headers = mapOf("MCP-Session-Id" to "session-$sessionNumber"))
            }
            server.toolsList = { request, exchange ->
                if (lists.incrementAndGet() == 1) server.sendEmpty(exchange, 404)
                else server.sendJson(exchange, toolsResult(request.optString("id"), listOf(smallTool("after_404"))))
            }
            val session = McpTransportClient.connect(server.config(McpTransport.STREAMABLE_HTTP), null, {})
            try {
                assertEquals("after_404", session.tools.single().wireName)
                assertEquals(2, initializations.get())
                assertEquals(2, lists.get())
                val listRequests = server.requests.filter { it.method == "POST" && it.request.optString("method") == "tools/list" }
                assertEquals("session-1", listRequests[0].sessionId)
                assertEquals("session-2", listRequests[1].sessionId)
            } finally { session.close() }
        }
    }

    private fun smallTool(name: String, description: String = "test tool") = McpToolConfig(
        wireName = name,
        modelName = "",
        description = description,
        inputSchemaJson = JSONObject().put("type", "object").put("properties", JSONObject()).toString(),
    )

    private fun bigToolList(count: Int, descriptionChars: Int): List<McpToolConfig> = (0 until count).map { index ->
        smallTool("large_$index", "Café " + "x".repeat(descriptionChars))
    }

    private fun toolsResult(id: String, tools: List<McpToolConfig>): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("result", JSONObject().put("tools", JSONArray().apply {
            tools.forEach { tool ->
                put(JSONObject()
                    .put("name", tool.wireName)
                    .put("description", tool.description)
                    .put("inputSchema", JSONObject(tool.inputSchemaJson)))
            }
        }))

    private fun rpcResult(id: String, result: JSONObject): JSONObject = JSONObject()
        .put("jsonrpc", "2.0").put("id", id).put("result", result)

    private fun sseData(json: String) = "data:$json\r\n\r\n"

    /** Split the wire bytes between the two octets of the first UTF-8 é in the SSE data. */
    private fun splitInsideUtf8(value: String): List<ByteArray> {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val marker = "é".toByteArray(StandardCharsets.UTF_8)
        val index = (0..bytes.size - marker.size).firstOrNull { offset ->
            bytes[offset] == marker[0] && bytes[offset + 1] == marker[1]
        } ?: error("UTF-8 split marker missing")
        return listOf(bytes.copyOfRange(0, index + 1), bytes.copyOfRange(index + 1, bytes.size))
    }

    private inner class LocalMcpHttpServer : AutoCloseable {
        private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "McpTransportClientTestServer").apply { isDaemon = true }
        }
        private val listener = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        private val legacyDone = CountDownLatch(1)
        private val legacyStream = AtomicReference<McpTestLocalResponse?>()
        private val activeSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        private val acceptThread = Thread({ acceptConnections() }, "McpTransportClientAccept").apply { isDaemon = true; start() }
        val requests = CopyOnWriteArrayList<McpTestSeenRequest>()
        val baseUrl = "http://127.0.0.1:${listener.localPort}"
        @Volatile var initialize: (JSONObject, McpTestLocalResponse) -> Unit = { request, response ->
            sendJson(response, initializeResult(request), headers = mapOf("MCP-Session-Id" to "session-1"))
        }
        @Volatile var toolsList: (JSONObject, McpTestLocalResponse) -> Unit = { request, response ->
            sendJson(response, toolsResult(request.optString("id"), emptyList()))
        }
        @Volatile var streamableGet: (McpTestSeenRequest, McpTestLocalResponse) -> Unit = { _, response -> sendEmpty(response, 405) }
        @Volatile var legacyMessage: (JSONObject) -> JSONObject? = { request -> when (request.optString("method")) {
            "initialize" -> initializeResult(request)
            "notifications/initialized" -> null
            "tools/list" -> toolsResult(request.optString("id"), emptyList())
            else -> rpcResult(request.optString("id"), JSONObject())
        } }

        fun config(transport: McpTransport) = McpServerConfig(
            id = "local-mcp", alias = "Local MCP", endpoint = "$baseUrl${if (transport == McpTransport.LEGACY_SSE) "/sse" else "/mcp"}",
            transport = transport,
        )

        fun initializeResult(request: JSONObject): JSONObject = rpcResult(
            request.optString("id"),
            JSONObject().put("protocolVersion", "2025-11-25")
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", "Local fixture").put("version", "1")),
        )

        fun sendJson(
            response: McpTestLocalResponse,
            body: JSONObject,
            headers: Map<String, String> = emptyMap(),
            status: Int = 200,
        ) = response.sendFixed(status, "application/json; charset=utf-8", body.toString().toByteArray(StandardCharsets.UTF_8), headers)

        fun sendSse(response: McpTestLocalResponse, chunks: List<ByteArray>) {
            response.beginChunked(200, "text/event-stream; charset=utf-8")
            chunks.forEach(response::writeChunk)
            response.finishChunked()
        }

        fun sendEmpty(response: McpTestLocalResponse, status: Int, headers: Map<String, String> = emptyMap()) =
            response.sendFixed(status, null, headers = headers)

        private fun acceptConnections() {
            while (!stopped.get()) {
                try {
                    val socket = listener.accept()
                    activeSockets += socket
                    executor.execute { handle(socket) }
                } catch (_: Exception) {
                    if (!stopped.get()) continue else break
                }
            }
        }

        private fun handle(socket: Socket) {
            try {
                socket.soTimeout = 30_000
                val input = socket.getInputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2) return
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val header = readLine(input) ?: return
                    if (header.isEmpty()) break
                    val colon = header.indexOf(':')
                    if (colon > 0) headers[header.substring(0, colon).trim().lowercase()] = header.substring(colon + 1).trim()
                }
                val body = if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
                    readChunkedBody(input)
                } else {
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    readFully(input, length)
                }
                val request = McpTestParsedRequest(parts[0], parts[1].substringBefore('?'), headers, body)
                val response = McpTestLocalResponse(socket)
                when {
                    request.path == "/sse" && request.method == "GET" -> handleLegacySse(response, request)
                    request.path == "/messages" && request.method == "POST" -> handleLegacyMessage(response, request)
                    request.path == "/mcp" -> handleStreamable(response, request)
                    else -> sendEmpty(response, 404)
                }
            } catch (_: Exception) {
                runCatching { socket.close() }
            } finally {
                activeSockets.remove(socket)
            }
        }

        private fun handleStreamable(response: McpTestLocalResponse, parsed: McpTestParsedRequest) {
            val request = if (parsed.body.isEmpty()) JSONObject() else JSONObject(String(parsed.body, StandardCharsets.UTF_8))
            val seen = record(parsed, request)
            when {
                parsed.method == "GET" -> streamableGet(seen, response)
                request.optString("method") == "initialize" -> initialize(request, response)
                request.optString("method") == "notifications/initialized" -> sendEmpty(response, 202)
                request.optString("method") == "tools/list" -> toolsList(request, response)
                parsed.method == "DELETE" -> sendEmpty(response, 200)
                else -> sendJson(response, rpcResult(request.optString("id"), JSONObject()))
            }
        }

        private fun handleLegacySse(response: McpTestLocalResponse, parsed: McpTestParsedRequest) {
            record(parsed, JSONObject())
            response.beginChunked(200, "text/event-stream; charset=utf-8")
            legacyStream.set(response)
            writeLegacyEvent("event: endpoint\r\ndata: /messages\r\n\r\n")
            try { legacyDone.await() } finally {
                legacyStream.compareAndSet(response, null)
                response.finishChunked()
            }
        }

        private fun handleLegacyMessage(response: McpTestLocalResponse, parsed: McpTestParsedRequest) {
            val request = JSONObject(String(parsed.body, StandardCharsets.UTF_8))
            record(parsed, request)
            val result = legacyMessage(request)
            sendEmpty(response, 202)
            if (result != null) writeLegacyEvent(sseData(result.toString()))
        }

        private fun record(parsed: McpTestParsedRequest, request: JSONObject): McpTestSeenRequest = McpTestSeenRequest(
            method = parsed.method,
            path = parsed.path,
            request = request,
            sessionId = parsed.headers["mcp-session-id"],
            lastEventId = parsed.headers["last-event-id"],
        ).also(requests::add)

        private fun writeLegacyEvent(event: String) {
            val stream = requireNotNull(legacyStream.get()) { "Legacy SSE stream is not open" }
            stream.writeChunk(event.toByteArray(StandardCharsets.UTF_8))
        }

        private fun readLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) return if (bytes.size() == 0) null else String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
                if (next == '\n'.code) return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1).removeSuffix("\r")
                bytes.write(next)
            }
        }

        private fun readFully(input: InputStream, length: Int): ByteArray {
            if (length <= 0) return ByteArray(0)
            val output = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(output, offset, length - offset)
                if (read < 0) throw java.io.EOFException("Incomplete local MCP request")
                offset += read
            }
            return output
        }

        private fun readChunkedBody(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(input) ?: throw java.io.EOFException("Incomplete chunked local MCP request")
                val size = sizeLine.substringBefore(';').trim().toInt(16)
                if (size == 0) {
                    while (readLine(input)?.isNotEmpty() == true) Unit
                    break
                }
                output.write(readFully(input, size))
                readLine(input)
            }
            return output.toByteArray()
        }

        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            legacyDone.countDown()
            runCatching { listener.close() }
            activeSockets.toList().forEach { runCatching { it.close() } }
            executor.shutdownNow()
            runCatching { acceptThread.join(1_000) }
        }
    }
}
