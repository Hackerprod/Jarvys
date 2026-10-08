package com.jarvys.agent.mcp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.jarvys.agent.connectors.GitHubOperationPolicy
import com.jarvys.agent.connectors.GitHubNativeBridge
import com.jarvys.agent.connectors.GitHubNativeException
import com.jarvys.agent.connectors.GitHubNativeFailure
import com.jarvys.agent.connectors.GitHubWriteJournal
import com.jarvys.agent.connectors.GitHubWriteJournals
import com.jarvys.agent.CancellationToken
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Owns one MCP transport/session per saved server and publishes connection state for Compose. */
class McpConnectionManager internal constructor(
    private val repository: McpServerRepository,
    private val oauthManager: McpOAuthManager,
    val writeApproval: McpWriteApprovalCoordinator,
    private val githubBridge: GitHubNativeBridge = GitHubNativeBridge(),
    private val githubJournal: GitHubWriteJournal = GitHubWriteJournals.inMemory(),
    private val sessionFactory: (McpServerConfig, String?, (List<McpToolConfig>) -> Unit) -> McpProtocolSession = McpTransportClient::connect,
) : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, McpProtocolSession>()
    private val sessionEpochs = ConcurrentHashMap<String, String>()
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
        val owner = generation(serverId)
        val config = repository.get(serverId) ?: return publish(serverId, McpConnectionSnapshot(
            McpConnectionStatus.ERROR, "MCP server configuration no longer exists"))
        if (!config.enabled) return publish(serverId, McpConnectionSnapshot(McpConnectionStatus.ERROR, "Enable this MCP server before connecting"))
        var previous: McpProtocolSession? = null
        val expectedGeneration = synchronized(owner) {
            val next = owner.incrementAndGet()
            previous = sessions.remove(serverId)
            sessionEpochs.remove(serverId)
            publish(serverId, McpConnectionSnapshot(McpConnectionStatus.CONNECTING, "Connecting and discovering tools…"))
            next
        }
        executor.execute {
            runCatching { previous?.close() }
            if (owner.get() != expectedGeneration) return@execute
            try {
                val epoch = githubEpoch(config)
                val discoveryOwner = java.util.concurrent.atomic.AtomicReference<McpProtocolSession?>()
                val session = connectWithSingleRefresh(config) {
                    owner.get() == expectedGeneration &&
                        (discoveryOwner.get() == null && sessions[serverId] == null || sessions[serverId] === discoveryOwner.get()) &&
                        repository.get(serverId)?.let { sameConnection(config, it) } == true &&
                        runCatching { githubEpoch(config) }.getOrNull() == epoch
                }
                discoveryOwner.set(session)
                val admitted = synchronized(owner) {
                    val latest = repository.get(serverId)
                    if (owner.get() != expectedGeneration || latest == null || !sameConnection(config, latest) || epoch != runCatching { githubEpoch(config) }.getOrNull()) false
                    else {
                        sessions[serverId] = session
                        if (epoch != null) sessionEpochs[serverId] = epoch
                        persistDiscoveredTools(serverId, session.tools, config)
                        publish(serverId, McpConnectionSnapshot(McpConnectionStatus.READY,
                            "Connected · ${repository.get(serverId)?.tools?.size ?: session.tools.size} tools discovered", session.serverInfo))
                        true
                    }
                }
                if (!admitted) session.close()
            } catch (error: Exception) {
                synchronized(owner) {
                    if (owner.get() == expectedGeneration) {
                        val errorText = error.message.orEmpty()
                        val status = when(error) {
                            is McpReauthRequiredException -> McpConnectionStatus.REAUTH_REQUIRED
                            is McpPermissionRequiredException -> McpConnectionStatus.PERMISSION_REQUIRED
                            is McpTransportClient.McpAuthorizationException -> McpConnectionStatus.AUTH_REQUIRED
                            else -> if (latestConfigIsOAuth(serverId) && listOf("authoriz", "expired", "refresh", "oauth token")
                                .any { errorText.contains(it, true) }) McpConnectionStatus.AUTH_REQUIRED else McpConnectionStatus.ERROR
                        }
                        publish(serverId, McpConnectionSnapshot(status, safeMessage(error), requiredScopes = (error as? McpPermissionRequiredException)?.requiredScopes.orEmpty()))
                    }
                }
            }
        }
    }

    fun disconnect(serverId: String) {
        val previous = synchronized(generation(serverId)) {
            generation(serverId).incrementAndGet()
            sessionEpochs.remove(serverId)
            val old = sessions.remove(serverId)
            publish(serverId, McpConnectionSnapshot(McpConnectionStatus.DISCONNECTED, "Disconnected"))
            old
        }
        executor.execute { runCatching { previous?.close() } }
    }

    private fun sameConnection(a: McpServerConfig, b: McpServerConfig): Boolean = b.enabled &&
        a.endpoint == b.endpoint && a.authMode == b.authMode && a.oauthClientId == b.oauthClientId &&
        a.catalogServiceId == b.catalogServiceId && a.githubToolsets == b.githubToolsets &&
        a.transport == b.transport && a.trustInsecureServer == b.trustInsecureServer

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
        val prepared = if (config.catalogServiceId == "github") GitHubOperationPolicy.prepare(config, tool, arguments)
            else JSONObject(arguments.toString())
        val expectedEpoch = githubEpoch(config)
        val expectedGeneration = generation(serverId).get()
        if (expectedEpoch != null && sessionEpochs[serverId] != expectedEpoch) {
            throw McpReauthRequiredException("GitHub account changed; reconnect before running this operation")
        }
        if (config.catalogServiceId == "github" && tool.access == McpToolAccess.WRITE &&
            githubJournal.isUncertain(GitHubOperationPolicy.intentHash(config, tool.wireName, prepared))) {
            throw IllegalStateException("GitHub write outcome is unresolved; verify the existing action before requesting another approval")
        }
        return try {
            writeApproval.execute(tool, prepared, token, requester, requesterColorKey) {
                if (config.catalogServiceId == "github") executeGitHub(config, session, tool, prepared, expectedEpoch!!, expectedGeneration, token)
                else callToolWithSingleRefresh(config, session, tool.wireName, prepared, token)
            }
        } catch (cancelled: CancellationException) {
            sessions.remove(serverId, session)
            publishIfCurrent(serverId, expectedGeneration, McpConnectionSnapshot(McpConnectionStatus.DISCONNECTED, "STOP closed the active MCP request"))
            executor.execute { runCatching { session.close() } }
            throw cancelled
        } catch (unauthorized: McpTransportClient.McpAuthorizationException) {
            sessions.remove(serverId, session)
            publishIfCurrent(serverId, expectedGeneration, McpConnectionSnapshot(McpConnectionStatus.AUTH_REQUIRED,
                "The saved MCP token was rejected; update the token and reconnect"))
            executor.execute { runCatching { session.close() } }
            throw unauthorized
        } catch (permission: McpPermissionRequiredException) {
            publishIfCurrent(serverId, expectedGeneration, McpConnectionSnapshot(McpConnectionStatus.PERMISSION_REQUIRED, safeMessage(permission), requiredScopes = permission.requiredScopes))
            throw permission
        } catch (reauth: McpReauthRequiredException) {
            publishIfCurrent(serverId, expectedGeneration, McpConnectionSnapshot(McpConnectionStatus.REAUTH_REQUIRED, safeMessage(reauth)))
            throw reauth
        }
    }

    private fun connectWithSingleRefresh(config: McpServerConfig, canPublish: () -> Boolean = { true }): McpProtocolSession {
        return McpOAuthRequestRetry.execute(
            oauthEnabled = config.authMode == McpAuthMode.OAUTH,
            refreshOnce = {
                oauthManager.invalidateAccessToken(config)
                oauthManager.refreshAccessToken(config)
            },
            request = {
                sessionFactory(config, authorizationHeader(config)) {
                    discovered -> synchronized(generation(config.id)) {
                        if (canPublish()) persistDiscoveredTools(config.id, discovered, config)
                    }
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
        onDefinitiveRejection: () -> Unit = {},
        beforeDispatch: () -> Unit = {},
        beforeRequest: () -> Unit = {},
    ): JSONObject {
        val requestGeneration = generation(config.id).get()
        var ownedSession = originalSession
        try {
            beforeRequest()
            beforeDispatch()
            return originalSession.callTool(wireName, arguments, token)
        } catch (unauthorized: McpTransportClient.McpAuthorizationException) {
            if (config.authMode != McpAuthMode.OAUTH) throw unauthorized
            onDefinitiveRejection()
        } catch (forbidden: McpPermissionRequiredException) {
            publishIfCurrent(config.id, requestGeneration, McpConnectionSnapshot(McpConnectionStatus.PERMISSION_REQUIRED, safeMessage(forbidden), requiredScopes = forbidden.requiredScopes))
            throw forbidden
        }
        return synchronized(refreshLocks.computeIfAbsent(config.id) { Any() }) {
            try {
                token.throwIfCancelled()
                beforeRequest()
                val current = sessions[config.id]
                val retrySession = if (current !== originalSession && current != null) {
                    // A concurrent 401 already refreshed and replaced this server session.
                    current
                } else {
                    beforeRequest()
                    oauthManager.invalidateAccessToken(config)
                    oauthManager.refreshAccessToken(config)
                    beforeRequest()
                    val discoveryOwner = java.util.concurrent.atomic.AtomicReference<McpProtocolSession?>(originalSession)
                    val discoveryEpoch = githubEpoch(config)
                    val replacement = sessionFactory(config, authorizationHeader(config)) {
                        discovered -> synchronized(generation(config.id)) {
                            if (generation(config.id).get() == requestGeneration && sessions[config.id] === discoveryOwner.get() &&
                                repository.get(config.id)?.let { sameConnection(config, it) } == true &&
                                runCatching { githubEpoch(config) }.getOrNull() == discoveryEpoch) {
                                persistDiscoveredTools(config.id, discovered, config)
                            }
                        }
                    }
                    discoveryOwner.set(replacement)
                    try {
                        synchronized(generation(config.id)) {
                            require(generation(config.id).get() == requestGeneration && sessions[config.id] === originalSession) { "MCP connection changed during refresh" }
                            beforeRequest()
                            sessions[config.id] = replacement
                            ownedSession = replacement
                            githubEpoch(config)?.let { sessionEpochs[config.id] = it }
                            persistDiscoveredTools(config.id, replacement.tools, config)
                        }
                    } catch (failure: Exception) { replacement.close(); throw failure }
                    runCatching { originalSession.close() }
                    publishIfCurrent(config.id, requestGeneration, McpConnectionSnapshot(McpConnectionStatus.READY,
                        "Connected · ${replacement.tools.size} tools discovered", replacement.serverInfo))
                    replacement
                }
                token.throwIfCancelled()
                try {
                    beforeRequest()
                    beforeDispatch()
                    retrySession.callTool(wireName, arguments, token)
                } catch (unauthorized: McpTransportClient.McpAuthorizationException) {
                    onDefinitiveRejection()
                    sessions.remove(config.id, retrySession)
                    runCatching { retrySession.close() }
                    throw McpReauthRequiredException("MCP authorization failed after one refresh; reauthorize this service")
                }
            } catch (permission: McpPermissionRequiredException) {
                publishIfCurrent(config.id, requestGeneration, McpConnectionSnapshot(McpConnectionStatus.PERMISSION_REQUIRED, safeMessage(permission), requiredScopes = permission.requiredScopes))
                throw permission
            } catch (unauthorized: McpTransportClient.McpAuthorizationException) {
                sessions.remove(config.id, ownedSession)
                runCatching { ownedSession.close() }
                throw McpReauthRequiredException("MCP authorization failed after one refresh; reauthorize this service")
            } catch (reauth: McpReauthRequiredException) {
                sessions.remove(config.id, ownedSession)
                runCatching { ownedSession.close() }
                publishIfCurrent(config.id, requestGeneration, McpConnectionSnapshot(McpConnectionStatus.REAUTH_REQUIRED, safeMessage(reauth)))
                throw reauth
            }
        }
    }

    private fun githubEpoch(config: McpServerConfig): String? = when {
        config.catalogServiceId != "github" -> null
        config.authMode == McpAuthMode.OAUTH -> oauthManager.githubAuthorizationEpoch(config)
        config.authMode == McpAuthMode.BEARER -> repository.bearerAuthorizationEpoch(config)
        else -> throw McpReauthRequiredException("GitHub requires an authenticated account")
    }

    private fun requireGitHubLease(config: McpServerConfig, tool: McpToolDefinition, epoch: String, expectedGeneration: Long, token: CancellationToken) {
        token.throwIfCancelled()
        require(generation(config.id).get() == expectedGeneration && sessionEpochs[config.id] == epoch) { "GitHub connection changed during review" }
        val current = repository.get(config.id) ?: throw McpReauthRequiredException("GitHub was disconnected")
        require(current.enabled && current.endpoint == config.endpoint && current.authMode == config.authMode &&
            current.oauthClientId == config.oauthClientId && current.githubToolsets == config.githubToolsets &&
            current.catalogServiceId == "github" && githubEpoch(current) == epoch) {
            "GitHub configuration or account changed during review; prepare a new operation"
        }
        val currentTool = repository.exposedTools().firstOrNull { it.serverId == tool.serverId && it.wireName == tool.wireName && it.access == tool.access }
            ?: throw IllegalArgumentException("GitHub tool permission changed during review; no operation was sent")
        if (tool.wireName == "merge_pull_request") require(currentTool.inputSchema.optJSONObject("properties")?.has("expectedHeadSha") == true) {
            "GitHub merge precondition changed during review; no operation was sent"
        }
        if (tool.access == McpToolAccess.WRITE) require(writeApproval.policy(tool) != com.jarvys.agent.connectors.AutonomyPolicy.DENY) {
            "GitHub write permission was disabled during review"
        }
    }

    private fun executeGitHub(config: McpServerConfig, session: McpProtocolSession, tool: McpToolDefinition,
                              args: JSONObject, epoch: String, expectedGeneration: Long, token: CancellationToken): JSONObject {
        requireGitHubLease(config, tool, epoch, expectedGeneration, token)
        val write = tool.access == McpToolAccess.WRITE
        val intent = GitHubOperationPolicy.intentHash(config, tool.wireName, args)
        var reserved = false
        fun beforeMutation() {
            requireGitHubLease(config, tool, epoch, expectedGeneration, token)
            if (!reserved) { githubJournal.reserve(intent); reserved = true }
        }
        fun authorization(): String {
            requireGitHubLease(config, tool, epoch, expectedGeneration, token)
            val header = if (config.authMode == McpAuthMode.BEARER) repository.bearerAuthorizationHeader(config, epoch)
                else requireNotNull(authorizationHeader(config))
            requireGitHubLease(config, tool, epoch, expectedGeneration, token)
            return header
        }
        try {
            val response = if (githubBridge.handles(tool.wireName)) {
                var refreshed = false
                while (true) {
                    try {
                        val native = githubBridge.prepare(tool.wireName, args)
                        val result = githubBridge.execute(native, { authorization() }, token, ::beforeMutation)
                        if (reserved && !githubJournal.resolve(intent)) addSafetyNotice(result, "Completed, but local safety state could not be cleared; do not repeat this action.")
                        return result
                    } catch (error: GitHubNativeException) {
                        if (error.reason != GitHubNativeFailure.UNAUTHORIZED || refreshed || config.authMode != McpAuthMode.OAUTH) throw error
                        // 401 is a definitive rejection. Never retry an ambiguous native mutation.
                        if (reserved) { check(githubJournal.resolve(intent)); reserved = false }
                        requireGitHubLease(config, tool, epoch, expectedGeneration, token)
                        oauthManager.invalidateAccessToken(config); oauthManager.refreshAccessToken(config); refreshed = true
                        requireGitHubLease(config, tool, epoch, expectedGeneration, token)
                    }
                }
                @Suppress("UNREACHABLE_CODE") JSONObject()
            } else {
                if (write) {
                    githubBridge.validateMcpTarget(tool.wireName, args, { authorization() }, token)
                    beforeMutation()
                }
                val result = callToolWithSingleRefresh(config, session, tool.wireName, args, token, onDefinitiveRejection = {
                    if (reserved) { check(githubJournal.resolve(intent)) { "GitHub rejected the write, but local safety state could not be released" }; reserved = false }
                }, beforeDispatch = {
                    requireGitHubLease(config, tool, epoch, expectedGeneration, token)
                    if (write && !reserved) beforeMutation()
                }) { requireGitHubLease(config, tool, epoch, expectedGeneration, token) }
                val callResult = result.optJSONObject("result")
                if (write && (callResult == null || callResult.optJSONArray("content") == null ||
                    callResult.has("isError") && callResult.opt("isError") !is Boolean)) {
                    throw McpTransportClient.McpAmbiguousWriteException()
                }
                if (write && callResult?.optBoolean("isError") == true) {
                    addSafetyNotice(result, "Write outcome unconfirmed; inspect GitHub before repeating this operation.")
                } else if (reserved && !githubJournal.resolve(intent)) {
                    addSafetyNotice(result, "Completed, but local safety state could not be cleared; do not repeat this action.")
                }
                result
            }
            return response
        } catch (failure: Exception) {
            val definitive = failure is McpTransportClient.McpAuthorizationException || failure is McpPermissionRequiredException ||
                failure is McpTransportClient.McpHttpStatusException && failure.statusCode in setOf(400, 401, 403, 404, 409, 422, 429) ||
                failure is GitHubNativeException && !failure.ambiguousOutcome
            if (reserved && definitive) githubJournal.resolve(intent)
            if (reserved && !definitive) throw IllegalStateException("GitHub write outcome is unknown. Verify the existing action on GitHub before retrying; automatic replay is blocked.")
            throw failure
        }
    }

    private fun addSafetyNotice(response: JSONObject, notice: String) {
        val result = response.optJSONObject("result") ?: JSONObject().also { response.put("result", it) }
        val content = result.optJSONArray("content") ?: org.json.JSONArray().also { result.put("content", it) }
        content.put(JSONObject().put("type", "text").put("text", notice))
    }

    private fun persistDiscoveredTools(serverId: String, discovered: List<McpToolConfig>, expected: McpServerConfig) {
        val current = repository.get(serverId) ?: return
        if (current.endpoint != expected.endpoint || current.authMode != expected.authMode || current.catalogServiceId != expected.catalogServiceId ||
            current.oauthClientId != expected.oauthClientId || current.githubToolsets != expected.githubToolsets) return
        val remote = if (current.catalogServiceId == "github") discovered.filterNot { it.wireName in GitHubOperationPolicy.replacedRemoteWrites } else discovered
        val native = if (current.catalogServiceId == "github") githubBridge.tools().filter {
            GitHubOperationPolicy.group(it.wireName) in current.githubToolsets && discovered.none { remote -> remote.wireName == it.wireName }
        } else emptyList()
        val application = McpInitialToolPolicy.apply(current, remote + native)
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
        sessionEpochs.remove(serverId)
        sessions.remove(serverId)?.let { runCatching { it.close() } }
    }

    private fun generation(serverId: String): AtomicLong = generations.computeIfAbsent(serverId) { AtomicLong() }

    private fun latestConfigIsOAuth(serverId: String): Boolean = repository.get(serverId)?.authMode == McpAuthMode.OAUTH

    private fun publishIfCurrent(serverId: String, expected: Long, snapshot: McpConnectionSnapshot) = synchronized(generation(serverId)) {
        if (generation(serverId).get() == expected) publish(serverId, snapshot)
    }

    private fun publish(serverId: String, snapshot: McpConnectionSnapshot) = synchronized(_states) {
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
                    McpWriteApprovalCoordinator.get(context), githubJournal = GitHubWriteJournals.persistent(context)).also { instance = it }
            }
        }
    }
}
