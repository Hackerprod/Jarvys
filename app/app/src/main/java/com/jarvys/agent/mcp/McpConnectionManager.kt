package com.jarvys.agent.mcp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.jarvys.agent.CancellationToken
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Owns one MCP transport/session per saved server and publishes connection state for Compose. */
class McpConnectionManager(
    private val repository: McpServerRepository,
    private val oauthManager: McpOAuthManager,
    val writeApproval: McpWriteApprovalCoordinator,
) : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, McpProtocolSession>()
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val refreshLocks = ConcurrentHashMap<String, Any>()
    private val _states = MutableStateFlow<Map<String, McpConnectionSnapshot>>(emptyMap())
    val states: StateFlow<Map<String, McpConnectionSnapshot>> = _states.asStateFlow()
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "JarvysMcpConnect").apply { isDaemon = true }
    }

    fun state(serverId: String): McpConnectionSnapshot = states.value[serverId] ?: McpConnectionSnapshot()

    /** Starts enabled saved servers without waiting for network work on the caller's thread. */
    fun connectEnabledServers() {
        for (server in repository.servers.value) {
            if (!server.enabled) continue
            val status = state(server.id).status
            if (status == McpConnectionStatus.DISCONNECTED || status == McpConnectionStatus.ERROR) {
                connect(server.id)
            }
        }
    }

    fun connect(serverId: String) {
        val generation = generation(serverId).incrementAndGet()
        val config = repository.get(serverId) ?: return publish(serverId, McpConnectionSnapshot(
            McpConnectionStatus.ERROR, "MCP server configuration no longer exists",
        ))
        if (!config.enabled) return publish(serverId, McpConnectionSnapshot(
            McpConnectionStatus.ERROR, "Enable this MCP server before connecting",
        ))
        publish(serverId, McpConnectionSnapshot(McpConnectionStatus.CONNECTING, "Connecting and discovering tools…"))
        executor.execute {
            disconnectNow(serverId)
            try {
                val latest = repository.get(serverId) ?: return@execute
                val session = connectWithSingleRefresh(latest)
                if (generation(serverId).get() != generation) {
                    session.close()
                    return@execute
                }
                sessions[serverId]?.close()
                sessions[serverId] = session
                if (generation(serverId).get() != generation) {
                    sessions.remove(serverId, session)
                    session.close()
                    return@execute
                }
                publish(serverId, McpConnectionSnapshot(
                    McpConnectionStatus.READY,
                    "Connected · ${session.tools.size} tools discovered",
                    session.serverInfo,
                ))
            } catch (error: Exception) {
                if (generation(serverId).get() == generation) {
                    val errorText = error.message.orEmpty()
                    val status = when (error) {
                        is McpReauthRequiredException -> McpConnectionStatus.REAUTH_REQUIRED
                        is McpPermissionRequiredException -> McpConnectionStatus.PERMISSION_REQUIRED
                        is McpTransportClient.McpAuthorizationException -> McpConnectionStatus.AUTH_REQUIRED
                        else -> if (latestConfigIsOAuth(serverId) && listOf("authoriz", "expired", "refresh", "oauth token")
                            .any { errorText.contains(it, true) }) McpConnectionStatus.AUTH_REQUIRED else McpConnectionStatus.ERROR
                    }
                    publish(serverId, McpConnectionSnapshot(status, safeMessage(error)))
                }
            }
        }
    }

    fun disconnect(serverId: String) {
        val generation = generation(serverId).incrementAndGet()
        executor.execute {
            disconnectNow(serverId)
            if (generation(serverId).get() == generation) {
                publish(serverId, McpConnectionSnapshot(McpConnectionStatus.DISCONNECTED, "Disconnected"))
            }
        }
    }

    fun callTool(serverId: String, wireName: String, arguments: JSONObject, token: CancellationToken): JSONObject =
        callTool(serverId, wireName, arguments, token, null)

    fun callTool(serverId: String, wireName: String, arguments: JSONObject, token: CancellationToken,
                 requester: String?): JSONObject = callTool(serverId, wireName, arguments, token, requester, null)

    fun callTool(serverId: String, wireName: String, arguments: JSONObject, token: CancellationToken,
                 requester: String?, requesterColorKey: String?): JSONObject {
        val session = sessions[serverId] ?: throw IllegalStateException("MCP server is not connected")
        val config = repository.get(serverId) ?: throw IllegalStateException("MCP server was removed")
        val tool = repository.exposedTools().firstOrNull { it.serverId == serverId && it.wireName == wireName }
            ?: throw IllegalArgumentException("MCP tool is disabled or unavailable")
        token.throwIfCancelled()
        return try {
            writeApproval.execute(tool, arguments, token, requester, requesterColorKey) {
                callToolWithSingleRefresh(config, session, tool.wireName, arguments, token)
            }
        } catch (cancelled: CancellationException) {
            sessions.remove(serverId, session)
            publish(serverId, McpConnectionSnapshot(McpConnectionStatus.DISCONNECTED, "STOP closed the active MCP request"))
            executor.execute { runCatching { session.close() } }
            throw cancelled
        } catch (unauthorized: McpTransportClient.McpAuthorizationException) {
            sessions.remove(serverId, session)
            publish(serverId, McpConnectionSnapshot(McpConnectionStatus.AUTH_REQUIRED,
                "The saved MCP token was rejected; update the token and reconnect"))
            executor.execute { runCatching { session.close() } }
            throw unauthorized
        } catch (permission: McpPermissionRequiredException) {
            publish(serverId, McpConnectionSnapshot(McpConnectionStatus.PERMISSION_REQUIRED, safeMessage(permission)))
            throw permission
        } catch (reauth: McpReauthRequiredException) {
            publish(serverId, McpConnectionSnapshot(McpConnectionStatus.REAUTH_REQUIRED, safeMessage(reauth)))
            throw reauth
        }
    }

    private fun connectWithSingleRefresh(config: McpServerConfig): McpProtocolSession {
        return McpOAuthRequestRetry.execute(
            oauthEnabled = config.authMode == McpAuthMode.OAUTH,
            refreshOnce = {
                oauthManager.invalidateAccessToken(config)
                oauthManager.refreshAccessToken(config)
            },
            request = {
                McpTransportClient.connect(config, authorizationHeader(config)) {
                    discovered -> persistDiscoveredTools(config.id, discovered)
                }
            },
        )
    }

    private fun callToolWithSingleRefresh(
        config: McpServerConfig,
        originalSession: McpProtocolSession,
        wireName: String,
        arguments: JSONObject,
        token: CancellationToken,
    ): JSONObject {
        try {
            return originalSession.callTool(wireName, arguments, token)
        } catch (unauthorized: McpTransportClient.McpAuthorizationException) {
            if (config.authMode != McpAuthMode.OAUTH) throw unauthorized
        } catch (forbidden: McpPermissionRequiredException) {
            publish(config.id, McpConnectionSnapshot(McpConnectionStatus.PERMISSION_REQUIRED, safeMessage(forbidden)))
            throw forbidden
        }
        return synchronized(refreshLocks.computeIfAbsent(config.id) { Any() }) {
            try {
                token.throwIfCancelled()
                val current = sessions[config.id]
                val retrySession = if (current !== originalSession && current != null) {
                    // A concurrent 401 already refreshed and replaced this server session.
                    current
                } else {
                    oauthManager.invalidateAccessToken(config)
                    oauthManager.refreshAccessToken(config)
                    val replacement = McpTransportClient.connect(config, authorizationHeader(config)) {
                        discovered -> persistDiscoveredTools(config.id, discovered)
                    }
                    sessions[config.id] = replacement
                    runCatching { originalSession.close() }
                    publish(config.id, McpConnectionSnapshot(McpConnectionStatus.READY,
                        "Connected · ${replacement.tools.size} tools discovered", replacement.serverInfo))
                    replacement
                }
                token.throwIfCancelled()
                try {
                    retrySession.callTool(wireName, arguments, token)
                } catch (unauthorized: McpTransportClient.McpAuthorizationException) {
                    sessions.remove(config.id, retrySession)
                    runCatching { retrySession.close() }
                    throw McpReauthRequiredException("MCP authorization failed after one refresh; reauthorize this service")
                }
            } catch (permission: McpPermissionRequiredException) {
                publish(config.id, McpConnectionSnapshot(McpConnectionStatus.PERMISSION_REQUIRED, safeMessage(permission)))
                throw permission
            } catch (unauthorized: McpTransportClient.McpAuthorizationException) {
                sessions.remove(config.id)?.let { runCatching { it.close() } }
                throw McpReauthRequiredException("MCP authorization failed after one refresh; reauthorize this service")
            } catch (reauth: McpReauthRequiredException) {
                sessions.remove(config.id)?.let { runCatching { it.close() } }
                publish(config.id, McpConnectionSnapshot(McpConnectionStatus.REAUTH_REQUIRED, safeMessage(reauth)))
                throw reauth
            }
        }
    }

    private fun persistDiscoveredTools(serverId: String, discovered: List<McpToolConfig>) {
        val current = repository.get(serverId) ?: return
        val application = McpInitialToolPolicy.apply(current, discovered)
        repository.updateTools(serverId, application.tools)
        application.newWritePolicies.forEach { (wireName, policy) ->
            val tool = application.tools.firstOrNull { it.wireName == wireName } ?: return@forEach
            val access = McpToolSecurity.classify(current.catalogServiceId, wireName, tool.annotations)
            val definition = McpToolDefinition(
                serverId = current.id,
                serverAlias = current.alias,
                wireName = wireName,
                modelName = tool.modelName,
                description = tool.description,
                inputSchema = runCatching { JSONObject(tool.inputSchemaJson) }.getOrDefault(JSONObject()),
                catalogServiceId = current.catalogServiceId,
                annotations = tool.annotations,
                access = access,
            )
            val saved = runCatching { writeApproval.setPolicy(definition, policy) }
            // ALLOW depends on the same notification prerequisite as the per-tool selector;
            // keep the just-discovered write on Ask if that prerequisite is currently unavailable.
            if (saved.isFailure && policy == com.jarvys.agent.connectors.AutonomyPolicy.ALLOW) {
                runCatching { writeApproval.setPolicy(definition, com.jarvys.agent.connectors.AutonomyPolicy.ASK) }
            }
        }
    }

    fun closeServer(serverId: String) = disconnect(serverId)

    override fun close() {
        executor.execute {
            sessions.values.toList().forEach { runCatching { it.close() } }
            sessions.clear()
        }
        executor.shutdown()
    }

    private fun authorizationHeader(config: McpServerConfig): String? {
        val value = when (config.authMode) {
            McpAuthMode.NONE -> null
            McpAuthMode.BEARER -> repository.bearerToken(config)
                ?.takeIf(String::isNotBlank)
                ?.let { "Bearer $it" }
                ?: throw IllegalStateException("Enter a bearer token for this MCP server")
            McpAuthMode.OAUTH -> oauthManager.authorizationHeader(config)
        }
        require(value?.contains('\r') != true && value?.contains('\n') != true) { "MCP authorization header is invalid" }
        require(value == null || value.length <= MAX_AUTH_HEADER_CHARS) { "MCP authorization header is too long" }
        return value
    }

    private fun disconnectNow(serverId: String) {
        sessions.remove(serverId)?.let { runCatching { it.close() } }
    }

    private fun generation(serverId: String): AtomicLong = generations.computeIfAbsent(serverId) { AtomicLong() }

    private fun latestConfigIsOAuth(serverId: String): Boolean = repository.get(serverId)?.authMode == McpAuthMode.OAUTH

    private fun publish(serverId: String, snapshot: McpConnectionSnapshot) {
        _states.value = _states.value.toMutableMap().apply { put(serverId, snapshot) }
    }

    private fun safeMessage(error: Exception): String {
        val message = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
        return message.take(MAX_STATUS_MESSAGE_CHARS)
    }

    companion object {
        private const val MAX_AUTH_HEADER_CHARS = 32 * 1024
        private const val MAX_STATUS_MESSAGE_CHARS = 240

        @Volatile private var instance: McpConnectionManager? = null

        fun get(context: Context): McpConnectionManager = instance ?: synchronized(this) {
            instance ?: McpServerRepository.get(context).let { repository ->
                McpConnectionManager(repository, McpOAuthManager.get(repository),
                    McpWriteApprovalCoordinator.get(context)).also { instance = it }
            }
        }
    }
}
