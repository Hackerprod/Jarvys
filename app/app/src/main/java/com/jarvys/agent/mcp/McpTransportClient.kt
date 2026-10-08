package com.jarvys.agent.mcp

import android.util.Base64
import com.jarvys.agent.CancellationToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal interface McpProtocolSession : AutoCloseable {
    val serverInfo: McpServerInfo
    val tools: List<McpToolConfig>
    fun callTool(name: String, arguments: JSONObject, token: CancellationToken): JSONObject
}

internal object McpEndpointPolicy {
    fun validate(endpoint: String, trustedInsecure: Boolean): URI {
        require(endpoint.length in 1..4096) { "MCP endpoint URL is empty or too long" }
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: throw IllegalArgumentException("MCP endpoint URL is invalid")
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
        require(scheme == "https" || scheme == "http") { "MCP endpoint must use HTTP or HTTPS" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "MCP endpoint must have a host and cannot contain user info or a fragment"
        }
        val sensitiveQueryKeys = setOf("access_token", "token", "bearer", "authorization", "api_key", "apikey", "client_secret", "password", "secret", "key")
        val queryKeys = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            part.substringBefore('=').let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        }
        require(queryKeys.none { it.lowercase(java.util.Locale.ROOT) in sensitiveQueryKeys }) {
            "Credentials belong in encrypted MCP authentication settings, never in the URL query"
        }
        if (scheme == "http" && !isLoopbackOrLanLiteral(uri.host) && !trustedInsecure) {
            throw IllegalArgumentException("HTTP to a public/custom host requires ‘Confiar en servidor MCP inseguro’")
        }
        return uri
    }

    fun requireSameOrigin(base: URI, candidate: URI, trustedInsecure: Boolean): URI {
        validate(candidate.toString(), trustedInsecure)
        require(base.scheme.equals(candidate.scheme, true) && base.host.equals(candidate.host, true)
                && effectivePort(base) == effectivePort(candidate)) {
            "Legacy SSE server returned a message endpoint on a different origin"
        }
        return candidate
    }

    private fun isLoopbackOrLanLiteral(host: String): Boolean {
        val normalized = host.lowercase(java.util.Locale.ROOT).trim('[', ']')
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
        if (normalized.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
            val octets = normalized.split('.').map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return octets[0] == 10 || octets[0] == 127 || octets[0] == 169 && octets[1] == 254
                    || octets[0] == 192 && octets[1] == 168
                    || octets[0] == 172 && octets[1] in 16..31
        }
        if (normalized.contains(':') && normalized.matches(Regex("[0-9a-f:.%]+"))) {
            val address = runCatching { java.net.InetAddress.getByName(normalized) }.getOrNull() ?: return false
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
            val bytes = address.address
            return bytes.size == 16 && bytes[0].toInt() and 0xfe == 0xfc
        }
        return false
    }

    private fun effectivePort(uri: URI): Int = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
}

/** Android HttpURLConnection implementation of MCP's HTTP transports (no subprocess/stdio support). */
internal object McpTransportClient {
    private val nextId = AtomicLong(1)
    private const val MAX_BODY_BYTES = 1024 * 1024
    private const val MAX_TOOLS = 512
    private const val MAX_PAGES = 100
    private val supportedVersions = setOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

    fun connect(
        config: McpServerConfig,
        authorization: String?,
        onToolsChanged: (List<McpToolConfig>) -> Unit,
    ): McpProtocolSession {
        val endpoint = McpEndpointPolicy.validate(config.endpoint, config.trustInsecureServer)
        return when (config.transport) {
            McpTransport.AUTO -> try {
                connectStreamable(config, endpoint, authorization, onToolsChanged)
            } catch (failure: McpHttpStatusException) {
                if (failure.statusCode !in setOf(400, 404, 405)) throw failure
                connectLegacy(config, endpoint, authorization, onToolsChanged)
            }
            McpTransport.STREAMABLE_HTTP -> connectStreamable(config, endpoint, authorization, onToolsChanged)
            McpTransport.LEGACY_SSE -> connectLegacy(config, endpoint, authorization, onToolsChanged)
        }
    }

    private fun connectStreamable(
        config: McpServerConfig,
        endpoint: URI,
        authorization: String?,
        onToolsChanged: (List<McpToolConfig>) -> Unit,
    ): McpProtocolSession {
        val initId = nextId.getAndIncrement().toString()
        val initial = request(endpoint, config, authorization, null, null, rpcRequest("initialize", initId, initializeParams()))
        val result = initial.getJSONObject("result")
        val version = result.optString("protocolVersion")
        require(version in supportedVersions) { "MCP server negotiated unsupported protocol version: $version" }
        val sessionId = initial.optString("__mcpSessionId").takeIf(String::isNotBlank)
        val info = parseServerInfo(result.optJSONObject("serverInfo"))
        val listChanged = result.optJSONObject("capabilities")?.optJSONObject("tools")?.optBoolean("listChanged") == true

        postNotification(endpoint, config, authorization, sessionId, version,
            rpcNotification("notifications/initialized", JSONObject()))
        val session = StreamableSession(config, endpoint, authorization, sessionId, version, info, listChanged, onToolsChanged)
        session.refreshTools()
        session.startNotificationsIfSupported()
        return session
    }

    private fun connectLegacy(
        config: McpServerConfig,
        endpoint: URI,
        authorization: String?,
        onToolsChanged: (List<McpToolConfig>) -> Unit,
    ): McpProtocolSession {
        val connection = open(endpoint.toURL(), config, authorization, "GET", null, legacyAccept())
        val code = responseCode(connection)
        if (code != 200) {
            connection.disconnect()
            throw httpError(code, connection.getHeaderField("WWW-Authenticate"), connection.getHeaderField("Retry-After"))
        }
        val contentType = connection.contentType.orEmpty()
        require(contentType.startsWith("text/event-stream", true)) { "Legacy MCP SSE endpoint did not return text/event-stream" }
        val reader = BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8))
        val endpointEvent = readSseEvent(reader)
        require(endpointEvent?.event == "endpoint" && !endpointEvent.data.isNullOrBlank()) {
            "Legacy MCP server did not begin with an endpoint event"
        }
        val postUri = McpEndpointPolicy.requireSameOrigin(endpoint, endpoint.resolve(endpointEvent.data), config.trustInsecureServer)
        val session = LegacySseSession(config, endpoint, postUri, authorization, connection, reader, onToolsChanged)
        try {
            session.startReader()
            val initId = nextId.getAndIncrement().toString()
            val init = session.request("initialize", initId, initializeParams())
            val result = init.getJSONObject("result")
            val version = result.optString("protocolVersion")
            require(version in supportedVersions) { "MCP server negotiated unsupported protocol version: $version" }
            session.setNegotiated(version, parseServerInfo(result.optJSONObject("serverInfo")))
            session.notification("notifications/initialized", JSONObject())
            session.refreshTools()
            return session
        } catch (failure: Exception) {
            session.close()
            throw failure
        }
    }

    private fun initializeParams(): JSONObject = JSONObject()
        .put("protocolVersion", "2025-11-25")
        .put("capabilities", JSONObject())
        .put("clientInfo", JSONObject().put("name", "Jarvys").put("version", "1.2.0"))

    private fun parseServerInfo(json: JSONObject?): McpServerInfo = McpServerInfo(
        name = json?.optString("name").orEmpty().ifBlank { "MCP Server" }.take(256),
        version = json?.optString("version").orEmpty().take(128),
        instructions = json?.optString("instructions")?.takeIf(String::isNotBlank)?.take(4096),
    )

    private fun request(
        endpoint: URI,
        config: McpServerConfig,
        authorization: String?,
        sessionId: String?,
        version: String?,
        body: JSONObject,
        onMessage: (JSONObject) -> Unit = {},
        token: CancellationToken? = null,
        safeToReplay: Boolean = body.optString("method") != "tools/call",
    ): JSONObject {
        var accepted = false
        val id = body.optString("id").takeIf(String::isNotBlank)
        val connection = open(endpoint.toURL(), config, authorization, "POST", sessionId, requestAccept())
        var unregisterCancellation: Runnable = Runnable { }
        try {
            token?.throwIfCancelled()
            if (token != null) unregisterCancellation = token.registerCancelAction { connection.disconnect() }
            if (version != null && version != "2024-11-05") connection.setRequestProperty("MCP-Protocol-Version", version)
            writeJson(connection, body)
            val code = responseCode(connection)
            if (code == 404 && sessionId != null) throw SessionExpiredException()
            if (code !in 200..299) throw httpError(code, connection.getHeaderField("WWW-Authenticate"), connection.getHeaderField("Retry-After"))
            accepted = true
            val type = connection.contentType.orEmpty()
            if (code == 202 || code == 204) {
                if (id != null) throw IllegalStateException("MCP server accepted a request without returning its response")
                return JSONObject()
            }
            return when {
                type.startsWith("application/json", true) -> {
                    val json = JSONObject(readBounded(connection.inputStream))
                    if (id != null && json.optString("id") != id) throw IllegalStateException("MCP response id did not match request")
                    json
                }
                type.startsWith("text/event-stream", true) -> {
                    if (id == null) return JSONObject()
                    try {
                        readSseResponse(connection.inputStream, id, onMessage)
                    } catch (closed: IncompleteSseResponse) {
                        resumeStream(endpoint, config, authorization, sessionId, version, id, closed, onMessage, token)
                    }
                }
                else -> throw IllegalStateException("MCP server returned unsupported content type: ${type.take(100)}")
            }.also { response ->
                connection.getHeaderField("MCP-Session-Id")?.let { response.put("__mcpSessionId", it) }
                requireNoRpcError(response)
            }
        } catch (error: Exception) {
            if (accepted && !safeToReplay) throw McpAmbiguousWriteException()
            if (token?.isCancelled == true) throw CancellationException("MCP request stopped")
            throw error
        } finally {
            unregisterCancellation.run()
            connection.disconnect()
        }
    }

    private fun postNotification(endpoint: URI, config: McpServerConfig, authorization: String?, sessionId: String?, version: String?, body: JSONObject) {
        val connection = open(endpoint.toURL(), config, authorization, "POST", sessionId, requestAccept())
        try {
            if (version != null && version != "2024-11-05") connection.setRequestProperty("MCP-Protocol-Version", version)
            writeJson(connection, body)
            val code = responseCode(connection)
            if (code !in 200..299) throw httpError(code, connection.getHeaderField("WWW-Authenticate"), connection.getHeaderField("Retry-After"))
        } finally {
            connection.disconnect()
        }
    }

    private fun open(
        url: URL,
        config: McpServerConfig,
        authorization: String?,
        method: String,
        sessionId: String?,
        accept: String,
        lastEventId: String? = null,
    ): HttpURLConnection {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("MCP-Client-Name", "Jarvys")
            authorization?.let { setRequestProperty("Authorization", it) }
            com.jarvys.agent.connectors.GitHubOperationPolicy.headers(config).forEach { (name, value) -> setRequestProperty(name, value) }
            sessionId?.let { setRequestProperty("MCP-Session-Id", it) }
            lastEventId?.let { setRequestProperty("Last-Event-ID", it) }
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        McpEndpointPolicy.validate(url.toString(), config.trustInsecureServer)
        return connection
    }

    private fun writeJson(connection: HttpURLConnection, json: JSONObject) {
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { output -> output.write(bytes) }
    }

    private fun responseCode(connection: HttpURLConnection): Int = try {
        connection.responseCode
    } catch (error: Exception) {
        throw IllegalStateException("Could not reach MCP server", error)
    }

    private fun httpError(code: Int, challenge: String?, retryAfter: String? = null): RuntimeException = when {
        code == 401 && challenge?.contains("insufficient_scope", true) == true -> McpPermissionRequiredException(
            "The service requires additional account permissions. Review the requested scopes and reconnect; the pending action was not replayed.",
            Regex("scope=\"([^\"]{1,512})\"").find(challenge)?.groupValues?.get(1)?.split(' ')?.filter { it.matches(Regex("[A-Za-z0-9:_-]{1,64}")) }?.toSet().orEmpty())
        code == 401 -> McpAuthorizationException("MCP server requires valid authorization", challenge)
        code == 403 -> McpPermissionRequiredException("MCP server denied access: the configured account or token lacks required permissions")
        code in setOf(301, 302, 303, 307, 308) -> IllegalStateException("MCP redirects are not followed; update the configured endpoint URL")
        else -> McpHttpStatusException(code, retryAfter?.toLongOrNull()?.coerceIn(0, 60))
    }

    private fun readBounded(input: InputStream): String {
        input.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_BODY_BYTES) throw IllegalStateException("MCP response exceeded the 1 MiB limit")
                output.write(buffer, 0, read)
            }
            return output.toString("UTF-8")
        }
    }

    private fun readSseResponse(
        input: InputStream,
        requestId: String,
        onMessage: (JSONObject) -> Unit,
        initialEventId: String? = null,
        initialRetryMillis: Long = DEFAULT_SSE_RETRY_MS,
    ): JSONObject {
        input.use { stream ->
            val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
            var bytes = 0
            var lastEventId: String? = initialEventId
            var retryMillis = initialRetryMillis
            while (true) {
                val event = readSseEvent(reader) ?: throw IncompleteSseResponse(lastEventId, retryMillis)
                event.id?.let { lastEventId = it }
                event.retryMillis?.let { retryMillis = it }
                bytes += event.data?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0
                if (bytes > MAX_BODY_BYTES) throw IllegalStateException("MCP SSE response exceeded the 1 MiB limit")
                val message = event.data?.takeIf(String::isNotBlank)?.let(::JSONObject) ?: continue
                if (message.optString("id") == requestId && (message.has("result") || message.has("error"))) return message
                onMessage(message)
            }
        }
    }

    private fun resumeStream(
        endpoint: URI,
        config: McpServerConfig,
        authorization: String?,
        sessionId: String?,
        version: String?,
        requestId: String,
        initialClose: IncompleteSseResponse,
        onMessage: (JSONObject) -> Unit,
        token: CancellationToken?,
    ): JSONObject {
        var closed = initialClose
        repeat(MAX_SSE_RESUMES) {
            token?.throwIfCancelled()
            val eventId = closed.lastEventId
                ?: throw IllegalStateException("MCP SSE response closed before returning request $requestId")
            try {
                Thread.sleep(closed.retryMillis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("MCP SSE response wait was interrupted", interrupted)
            }
            val connection = open(endpoint.toURL(), config, authorization, "GET", sessionId, "text/event-stream", eventId)
            var unregisterCancellation: Runnable = Runnable { }
            try {
                if (token != null) unregisterCancellation = token.registerCancelAction { connection.disconnect() }
                if (version != null && version != "2024-11-05") connection.setRequestProperty("MCP-Protocol-Version", version)
                val code = responseCode(connection)
                if (code != 200) throw httpError(code, connection.getHeaderField("WWW-Authenticate"), connection.getHeaderField("Retry-After"))
                try {
                    return readSseResponse(connection.inputStream, requestId, onMessage, closed.lastEventId, closed.retryMillis)
                } catch (nextClose: IncompleteSseResponse) {
                    closed = nextClose
                }
            } finally {
                unregisterCancellation.run()
                connection.disconnect()
            }
        }
        throw IllegalStateException("MCP SSE response could not be resumed after $MAX_SSE_RESUMES reconnects")
    }

    private data class SseEvent(val event: String?, val id: String?, val retryMillis: Long?, val data: String?)

    private fun readSseEvent(reader: BufferedReader): SseEvent? {
        var event: String? = null
        var id: String? = null
        var retry: Long? = null
        val data = StringBuilder()
        var dataBytes = 0
        var sawLine = false
        while (true) {
            val line = readSseLine(reader) ?: return if (sawLine || data.isNotEmpty()) {
                SseEvent(event, id, retry, data.toString().takeIf(String::isNotEmpty))
            } else null
            sawLine = true
            if (line.isEmpty()) {
                if (event != null || id != null || retry != null || data.isNotEmpty()) {
                    return SseEvent(event, id, retry, data.toString().takeIf(String::isNotEmpty))
                }
                sawLine = false
                continue
            }
            if (line.startsWith(':')) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
            when (field) {
                "event" -> event = value
                "id" -> if ('\u0000' !in value) id = value
                "retry" -> retry = value.toLongOrNull()?.coerceIn(0, MAX_RETRY_MS)
                "data" -> {
                    if (data.isNotEmpty()) {
                        data.append('\n')
                        dataBytes++
                    }
                    data.append(value)
                    dataBytes += value.toByteArray(StandardCharsets.UTF_8).size
                    if (dataBytes > MAX_BODY_BYTES) throw IllegalStateException("MCP SSE event exceeded the 1 MiB limit")
                }
            }
        }
    }

    private fun readSseLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        var utf8Bytes = 0
        while (true) {
            val next = reader.read()
            if (next < 0) return if (line.isEmpty()) null else line.toString().removeSuffix("\r")
            if (next == '\n'.code) return line.toString().removeSuffix("\r")
            val character = next.toChar()
            line.append(character)
            // InputStreamReader decodes UTF-8 incrementally; count decoded code points back to UTF-8 bytes.
            utf8Bytes += when {
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                Character.isHighSurrogate(character) -> 4
                Character.isLowSurrogate(character) -> 0
                else -> 3
            }
            if (utf8Bytes > MAX_BODY_BYTES) throw IllegalStateException("MCP SSE event exceeded the 1 MiB limit")
        }
    }

    private fun requestAccept() = "application/json, text/event-stream"
    private fun legacyAccept() = "text/event-stream"

    private fun requireNoRpcError(message: JSONObject) {
        val error = message.optJSONObject("error") ?: return
        throw McpProtocolException(error.optInt("code"), error.optString("message", "MCP JSON-RPC error"))
    }

    private class StreamableSession(
        private val config: McpServerConfig,
        private val endpoint: URI,
        private val authorization: String?,
        private var sessionId: String?,
        private var version: String,
        override val serverInfo: McpServerInfo,
        private val supportsListChanged: Boolean,
        private val onToolsChanged: (List<McpToolConfig>) -> Unit,
    ) : McpProtocolSession {
        @Volatile private var closed = false
        @Volatile private var listenerConnection: HttpURLConnection? = null
        @Volatile override var tools: List<McpToolConfig> = emptyList()
            private set

        override fun callTool(name: String, arguments: JSONObject, token: CancellationToken): JSONObject = synchronized(this) {
            ensureOpen()
            token.throwIfCancelled()
            val params = JSONObject().put("name", name).put("arguments", arguments)
            callWithRecovery("tools/call", params, token)
        }

        fun refreshTools() = synchronized(this) {
            val merged = discoverTools { method, params -> callWithRecovery(method, params) }
            tools = mergeTools(config, merged)
            onToolsChanged(tools)
        }

        private fun callWithRecovery(method: String, params: JSONObject, token: CancellationToken? = null): JSONObject {
            val id = nextId.getAndIncrement().toString()
            val safeRead = method == "tools/list" || method == "tools/call" && tools.firstOrNull {
                it.wireName == params.optString("name")
            }?.let { McpToolSecurity.classify(config.catalogServiceId, it.wireName, it.annotations) == McpToolAccess.READ } == true
            var recovered = false
            var retries = 0
            while (true) {
                token?.throwIfCancelled()
                try {
                    return request(endpoint, config, authorization, sessionId, version, rpcRequest(method, id, params), ::handleServerMessage, token, safeRead)
                } catch (expired: SessionExpiredException) {
                    if (!safeRead || recovered) throw McpHttpStatusException(404)
                    sessionId = initializeAgain(); recovered = true
                } catch (status: McpHttpStatusException) {
                    if (!safeRead || status.statusCode !in setOf(429, 500, 502, 503, 504) || retries++ >= 2 || (status.retryAfterSeconds ?: 0) > 5) throw status
                    var remaining = status.retryAfterSeconds?.times(1000) ?: (250L * retries + (0..100).random())
                    while (remaining > 0) {
                        token?.throwIfCancelled()
                        val wait = minOf(remaining, 100L)
                        Thread.sleep(wait); remaining -= wait
                    }
                }
            }
        }

        private fun initializeAgain(): String? {
            val id = nextId.getAndIncrement().toString()
            val response = request(endpoint, config, authorization, null, null, rpcRequest("initialize", id, initializeParams()), ::handleServerMessage)
            val result = response.getJSONObject("result")
            version = result.optString("protocolVersion")
            require(version in supportedVersions) { "MCP server negotiated unsupported protocol version: $version" }
            postNotification(endpoint, config, authorization, response.optString("__mcpSessionId").takeIf(String::isNotBlank), version,
                rpcNotification("notifications/initialized", JSONObject()))
            return response.optString("__mcpSessionId").takeIf(String::isNotBlank)
        }

        private fun handleServerMessage(message: JSONObject) {
            if (message.optString("method") == "notifications/tools/list_changed") {
                Thread({ runCatching { refreshTools() } }, "JarvysMcpToolRefresh").apply { isDaemon = true; start() }
            }
        }

        fun startNotificationsIfSupported() {
            if (!supportsListChanged || closed) return
            Thread({
                var failures = 0
                var lastEventId: String? = null
                var retryMillis = DEFAULT_SSE_RETRY_MS
                while (!closed && failures < 5) {
                    var connection: HttpURLConnection? = null
                    val startedAt = System.currentTimeMillis()
                    try {
                        connection = open(endpoint.toURL(), config, authorization, "GET", sessionId, "text/event-stream", lastEventId)
                        if (version != "2024-11-05") connection.setRequestProperty("MCP-Protocol-Version", version)
                        listenerConnection = connection
                        val code = responseCode(connection)
                        if (code == 405) return@Thread
                        if (code != 200 || !connection.contentType.orEmpty().startsWith("text/event-stream", true)) {
                            failures++
                        } else {
                            failures = 0
                            val reader = BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8))
                            while (!closed) {
                                val event = readSseEvent(reader) ?: break
                                event.id?.let { lastEventId = it }
                                event.retryMillis?.let { retryMillis = it }
                                val json = event.data?.takeIf(String::isNotBlank)?.let(::JSONObject) ?: continue
                                if (json.optString("method") == "notifications/tools/list_changed") {
                                    Thread({ runCatching { refreshTools() } }, "JarvysMcpToolRefresh").apply { isDaemon = true; start() }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        failures++
                    } finally {
                        if (listenerConnection === connection) listenerConnection = null
                        connection?.disconnect()
                    }
                    if (System.currentTimeMillis() - startedAt > LONG_LIVED_SSE_RESET_MS) failures = 0 else failures++
                    if (!closed && failures < 5) try { Thread.sleep(retryMillis) } catch (_: InterruptedException) { return@Thread }
                }
            }, "JarvysMcpNotify-${config.id.take(8)}").apply { isDaemon = true; start() }
        }

        override fun close() {
            closed = true
            listenerConnection?.disconnect()
            sessionId?.let { id ->
                runCatching {
                    val connection = open(endpoint.toURL(), config, authorization, "DELETE", id, "application/json")
                    if (version != "2024-11-05") connection.setRequestProperty("MCP-Protocol-Version", version)
                    responseCode(connection)
                    connection.disconnect()
                }
            }
        }

        private fun ensureOpen() { check(!closed) { "MCP session is closed" } }
    }

    private class LegacySseSession(
        private val config: McpServerConfig,
        private val endpoint: URI,
        private val postEndpoint: URI,
        private val authorization: String?,
        private val sseConnection: HttpURLConnection,
        private val reader: BufferedReader,
        private val onToolsChanged: (List<McpToolConfig>) -> Unit,
    ) : McpProtocolSession {
        private val pending = ConcurrentHashMap<String, BlockingQueue<JSONObject>>()
        @Volatile private var closed = false
        @Volatile private var version = ""
        @Volatile override var serverInfo: McpServerInfo = McpServerInfo("MCP Server", "", null)
            private set
        @Volatile override var tools: List<McpToolConfig> = emptyList()
            private set

        fun setNegotiated(version: String, info: McpServerInfo) {
            this.version = version
            this.serverInfo = info
        }

        fun startReader() {
            Thread({
                try {
                    while (!closed) {
                        val event = readSseEvent(reader) ?: break
                        val json = event.data?.takeIf(String::isNotBlank)?.let(::JSONObject) ?: continue
                        val id = json.optString("id")
                        if (id.isNotBlank()) pending[id]?.offer(json)
                        else if (json.optString("method") == "notifications/tools/list_changed") {
                            Thread({ runCatching { refreshTools() } }, "JarvysMcpLegacySync").apply { isDaemon = true; start() }
                        }
                    }
                } catch (error: Exception) {
                    if (!closed) pending.values.forEach { it.offer(JSONObject().put("__transportError", error.javaClass.simpleName)) }
                } finally {
                    if (!closed) pending.values.forEach { it.offer(JSONObject().put("__transportError", "SSE stream closed")) }
                }
            }, "JarvysMcpSse-${config.id.take(8)}").apply { isDaemon = true; start() }
        }

        fun request(method: String, id: String, params: JSONObject, token: CancellationToken? = null): JSONObject {
            check(!closed) { "MCP session is closed" }
            token?.throwIfCancelled()
            val queue = LinkedBlockingQueue<JSONObject>(1)
            pending[id] = queue
            try {
                post(rpcRequest(method, id, params), expectingResponse = true, token = token)
                val deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MS
                var response: JSONObject? = null
                while (response == null && System.currentTimeMillis() < deadline) {
                    token?.throwIfCancelled()
                    response = queue.poll(200L, TimeUnit.MILLISECONDS)
                }
                val received = response ?: throw IllegalStateException("Timed out waiting for the legacy SSE response to $method")
                if (received.has("__transportError")) throw IllegalStateException("Legacy MCP SSE stream closed")
                requireNoRpcError(received)
                return received
            } finally {
                pending.remove(id)
            }
        }

        fun notification(method: String, params: JSONObject) = post(rpcNotification(method, params), expectingResponse = false)

        fun refreshTools() {
            val discovered = discoverTools { method, params ->
                request(method, nextId.getAndIncrement().toString(), params)
            }
            tools = mergeTools(config, discovered)
            onToolsChanged(tools)
        }

        override fun callTool(name: String, arguments: JSONObject, token: CancellationToken): JSONObject =
            request("tools/call", nextId.getAndIncrement().toString(), JSONObject().put("name", name).put("arguments", arguments), token)

        private fun post(body: JSONObject, expectingResponse: Boolean, token: CancellationToken? = null) {
            val connection = open(postEndpoint.toURL(), config, authorization, "POST", null, requestAccept())
            var unregisterCancellation: Runnable = Runnable { }
            try {
                token?.throwIfCancelled()
                if (token != null) unregisterCancellation = token.registerCancelAction {
                    connection.disconnect()
                    sseConnection.disconnect()
                }
                if (version.isNotBlank() && version != "2024-11-05") connection.setRequestProperty("MCP-Protocol-Version", version)
                writeJson(connection, body)
                val code = responseCode(connection)
                if (code !in 200..299) throw httpError(code, connection.getHeaderField("WWW-Authenticate"), connection.getHeaderField("Retry-After"))
                val responseBody = runCatching { readBounded(connection.inputStream) }.getOrDefault("")
                if (expectingResponse && responseBody.isNotBlank()) {
                    val json = JSONObject(responseBody)
                    val responseId = json.optString("id")
                    if (responseId.isNotBlank()) pending[responseId]?.offer(json)
                }
            } catch (error: Exception) {
                if (token?.isCancelled == true) throw CancellationException("MCP legacy SSE request stopped")
                throw error
            } finally {
                unregisterCancellation.run()
                connection.disconnect()
            }
        }

        override fun close() {
            closed = true
            runCatching { reader.close() }
            sseConnection.disconnect()
            pending.values.forEach { it.offer(JSONObject().put("__transportError", "MCP session closed")) }
        }
    }

    private fun discoverTools(request: (String, JSONObject) -> JSONObject): List<McpToolConfig> {
        val result = mutableListOf<McpToolConfig>()
        val seen = mutableSetOf<String>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var page = 0
        do {
            check(++page <= MAX_PAGES) { "MCP server returned too many tools/list pages" }
            val params = JSONObject().also { if (cursor != null) it.put("cursor", cursor) }
            val response = request("tools/list", params).getJSONObject("result")
            val tools = response.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                val name = tool.optString("name")
                if (name.isBlank() || name.length > 512 || name.any(Char::isISOControl)) continue
                val schema = McpToolSecurity.sanitizeInputSchema(
                    tool.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                )
                require(schema.toString().toByteArray(StandardCharsets.UTF_8).size <= 64 * 1024) { "MCP tool schema exceeded 64 KiB" }
                if (seen.add(name)) {
                    result += McpToolConfig(
                        wireName = name,
                        modelName = "",
                        description = McpToolSecurity.sanitizeRemoteText(tool.optString("description", ""), 1200),
                        inputSchemaJson = schema.toString(),
                        annotations = McpToolSecurity.parseAnnotations(tool.optJSONObject("annotations")),
                    )
                }
                check(result.size <= MAX_TOOLS) { "MCP server returned more than $MAX_TOOLS tools" }
            }
            cursor = response.optString("nextCursor").takeIf(String::isNotBlank)
            check(cursor == null || seenCursors.add(cursor)) { "MCP server repeated a tools/list cursor" }
        } while (cursor != null)
        return result
    }

    private fun mergeTools(config: McpServerConfig, discovered: List<McpToolConfig>): List<McpToolConfig> {
        return McpToolSecurity.mergeDiscoveredTools(config, discovered)
    }

    private fun rpcRequest(method: String, id: String, params: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)

    private fun rpcNotification(method: String, params: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)

    private class SessionExpiredException : RuntimeException("MCP session expired")
    private class IncompleteSseResponse(val lastEventId: String?, val retryMillis: Long) : RuntimeException()
    internal class McpAmbiguousWriteException : IllegalStateException("MCP write may have completed; response recovery failed, so automatic replay is blocked")
    internal class McpHttpStatusException(val statusCode: Int, val retryAfterSeconds: Long? = null) : RuntimeException(when(statusCode) {
        404 -> "GitHub target or MCP session was not found; verify the repository/account and reconnect before retrying"
        409 -> "GitHub target changed; read the latest branch or content before preparing a new operation"
        422 -> "GitHub rejected the arguments or repository rules; check branch protection and required fields"
        429 -> "GitHub rate limit reached; wait before retrying"
        else -> "MCP server returned HTTP $statusCode"
    })
    private class McpProtocolException(code: Int, message: String) : RuntimeException("MCP JSON-RPC error $code: $message")
    internal class McpAuthorizationException(message: String, val challenge: String?) : RuntimeException(message)

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 90_000
    private const val RESPONSE_TIMEOUT_MS = 90_000L
    private const val MAX_RETRY_MS = 30_000L
    private const val DEFAULT_SSE_RETRY_MS = 1_000L
    private const val MAX_SSE_RESUMES = 3
    private const val LONG_LIVED_SSE_RESET_MS = 60_000L
}
