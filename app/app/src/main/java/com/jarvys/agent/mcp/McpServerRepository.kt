package com.jarvys.agent.mcp

import android.content.Context
import com.jarvys.agent.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Non-secret MCP connection metadata; credentials are delegated to encrypted SecretStore. */
internal interface McpCredentialVault {
    fun get(serverId: String, key: String): String?
    fun save(serverId: String, key: String, value: String)
    fun clear(serverId: String)
}

private class EncryptedMcpCredentialVault(private val store: SecretStore) : McpCredentialVault {
    override fun get(serverId: String, key: String) = store.getMcpSecret(serverId, key)
    override fun save(serverId: String, key: String, value: String) = store.saveMcpSecret(serverId, key, value)
    override fun clear(serverId: String) = store.clearMcpSecrets(serverId)
}

class McpServerRepository internal constructor(context: Context, private val secrets: McpCredentialVault) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _servers = MutableStateFlow(readServers())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    fun get(serverId: String): McpServerConfig? = synchronized(lock) {
        _servers.value.firstOrNull { it.id == serverId }
    }

    /** Serializes explicit grant adoption with edits/removal of the configuration it belongs to. */
    internal fun <T> withCurrentOAuthConfig(expected: McpServerConfig, block: () -> T): T = synchronized(lock) {
        val current = get(expected.id) ?: error("MCP server was removed during authorization")
        require(current.endpoint == expected.endpoint && current.authMode == McpAuthMode.OAUTH && current.enabled &&
            current.oauthClientId == expected.oauthClientId && current.catalogServiceId == expected.catalogServiceId) {
            "MCP authorization configuration changed; start a new authorization"
        }
        block()
    }

    fun hasBearerToken(serverId: String): Boolean = !secrets.get(serverId, SECRET_BEARER).isNullOrBlank()

    fun bearerToken(config: McpServerConfig): String? = synchronized(lock) {
        val token = secrets.get(config.id, SECRET_BEARER) ?: return@synchronized null
        val boundEndpoint = secrets.get(config.id, SECRET_BEARER_ENDPOINT)
        require(boundEndpoint == config.endpoint) {
            "MCP endpoint changed; re-enter its bearer token before connecting"
        }
        token
    }

    internal fun bearerAuthorizationEpoch(config: McpServerConfig): String = synchronized(lock) {
        val token = bearerToken(config)?.takeIf(String::isNotBlank) ?: error("GitHub bearer grant is unavailable")
        secrets.get(config.id, "github_bearer_epoch").takeUnless { it.isNullOrBlank() }
            ?: com.jarvys.agent.connectors.GitHubOperationPolicy.digest("legacy-bearer:" + token)
    }

    internal fun bearerAuthorizationHeader(config: McpServerConfig, expectedEpoch: String): String = synchronized(lock) {
        val current = get(config.id) ?: error("GitHub was disconnected")
        require(current.enabled && current.authMode == McpAuthMode.BEARER && current.endpoint == config.endpoint && current.catalogServiceId == config.catalogServiceId) { "GitHub configuration changed" }
        require(bearerAuthorizationEpoch(config) == expectedEpoch) { "GitHub account changed during review" }
        "Bearer ${bearerToken(config)}"
    }

    fun oauthValue(serverId: String, key: String): String? = secrets.get(serverId, key)

    fun saveBearerToken(serverId: String, endpoint: String, value: String) = synchronized(lock) {
        val token = value.trim()
        require(token.length <= MAX_TOKEN_CHARS && token.none { it == '\r' || it == '\n' }) {
            "Bearer token is invalid or too long"
        }
        secrets.save(serverId, SECRET_BEARER_ENDPOINT, "")
        secrets.save(serverId, "github_bearer_epoch", "")
        secrets.save(serverId, SECRET_BEARER, token)
        secrets.save(serverId, SECRET_BEARER_ENDPOINT, endpoint)
        secrets.save(serverId, "github_bearer_epoch", UUID.randomUUID().toString())
    }

    fun clearBearerToken(serverId: String) = synchronized(lock) {
        secrets.save(serverId, SECRET_BEARER_ENDPOINT, "")
        secrets.save(serverId, SECRET_BEARER, "")
        secrets.save(serverId, "github_bearer_epoch", "")
    }

    fun saveOAuthValue(serverId: String, key: String, value: String?) {
        secrets.save(serverId, key, value.orEmpty())
    }

    fun createId(): String = UUID.randomUUID().toString()

    fun upsert(config: McpServerConfig) = synchronized(lock) {
        validateConfig(config)
        val next = _servers.value.filterNot { it.id == config.id } + config
        persist(next)
        _servers.value = next
    }

    fun updateTools(serverId: String, tools: List<McpToolConfig>) = synchronized(lock) {
        val current = get(serverId) ?: return@synchronized
        upsert(current.copy(tools = tools, githubToolPreferences = rememberGitHubChoices(current)))
    }

    fun updateToolEnabled(serverId: String, wireName: String, enabled: Boolean) = synchronized(lock) {
        val current = get(serverId) ?: return@synchronized
        if (enabled && current.tools.firstOrNull { it.wireName == wireName }?.enabled != true && current.tools.count { it.enabled } >= McpToolSecurity.MAX_EXPOSED_TOOLS_PER_SERVER) {
            throw IllegalStateException("Only ${McpToolSecurity.MAX_EXPOSED_TOOLS_PER_SERVER} tools per server can be enabled for model context")
        }
        upsert(current.copy(
            tools = current.tools.map { if (it.wireName == wireName) it.copy(enabled = enabled) else it },
            toolSelectionMode = McpToolSelectionMode.CUSTOM,
            githubToolPreferences = rememberGitHubChoices(current) + current.tools.filter { it.wireName == wireName }.associate {
                it.wireName to "${McpToolSecurity.classify(current.catalogServiceId, it.wireName, it.annotations).name}:$enabled"
            },
        ))
    }

    fun setAllToolsEnabled(serverId: String, enabled: Boolean) = synchronized(lock) {
        val current = get(serverId) ?: return@synchronized
        val selectedWrites = current.tools.count { it.enabled && McpToolSecurity.classify(current.catalogServiceId, it.wireName, it.annotations) == McpToolAccess.WRITE }
        var available = (McpToolSecurity.MAX_EXPOSED_TOOLS_PER_SERVER - selectedWrites).coerceAtLeast(0)
        val updated = current.tools.map { tool ->
            val access = McpToolSecurity.classify(current.catalogServiceId, tool.wireName, tool.annotations)
            if (access == McpToolAccess.WRITE) tool else tool.copy(enabled = enabled && available-- > 0)
        }
        upsert(current.copy(tools = updated, toolSelectionMode = McpToolSelectionMode.CUSTOM,
            githubToolPreferences = rememberGitHubChoices(current.copy(tools = updated))))
    }

    private fun rememberGitHubChoices(config: McpServerConfig): Map<String, String> =
        if (config.catalogServiceId != "github") emptyMap() else (config.githubToolPreferences + config.tools.associate {
            it.wireName to "${McpToolSecurity.classify(config.catalogServiceId, it.wireName, it.annotations).name}:${it.enabled}"
        }).entries.toList().takeLast(512).associate { it.toPair() }

    fun delete(serverId: String) = synchronized(lock) {
        val next = _servers.value.filterNot { it.id == serverId }
        persist(next)
        _servers.value = next
        secrets.clear(serverId)
    }

    fun exposedTools(): List<McpToolDefinition> = _servers.value
        .filter { it.enabled }
        .flatMap { server ->
            McpToolSecurity.exposedEnabledTools(server.tools).mapNotNull { tool ->
                runCatching {
                    McpToolDefinition(
                        serverId = server.id,
                        serverAlias = server.alias,
                        wireName = tool.wireName,
                        modelName = tool.modelName,
                        description = McpToolSecurity.sanitizeDescription(tool.description),
                        inputSchema = JSONObject(tool.inputSchemaJson),
                        catalogServiceId = server.catalogServiceId,
                        annotations = tool.annotations,
                        access = McpToolSecurity.classify(server.catalogServiceId, tool.wireName, tool.annotations),
                    )
                }.getOrNull()
            }
            .toList()
        }

    private fun readServers(): List<McpServerConfig> = runCatching {
        val root = JSONArray(preferences.getString(KEY_SERVERS, "[]"))
        (0 until root.length()).mapNotNull { index ->
            runCatching { decode(root.getJSONObject(index)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun decode(item: JSONObject): McpServerConfig {
        val tools = item.optJSONArray("tools") ?: JSONArray()
        val decodedTools = (0 until tools.length()).mapNotNull { toolIndex ->
            runCatching {
                tools.getJSONObject(toolIndex).let { tool ->
                    McpToolConfig(
                        wireName = tool.getString("wireName"),
                        modelName = tool.getString("modelName"),
                        description = tool.optString("description", ""),
                        inputSchemaJson = tool.getString("inputSchema"),
                        enabled = tool.optBoolean("enabled", false),
                        annotations = McpToolSecurity.parseAnnotations(tool.optJSONObject("annotations")),
                    )
                }
            }.getOrNull()
        }
        val storedSelectionMode = item.optString("toolSelectionMode", "")
        val selectionMode = if (storedSelectionMode.isNotBlank()) {
            runCatching { McpToolSelectionMode.valueOf(storedSelectionMode) }
                .getOrDefault(McpToolSelectionMode.DEFAULT_ALL)
        } else if (decodedTools.any { it.enabled }) {
            // Legacy rows with at least one enabled tool reflect an existing manual selection.
            McpToolSelectionMode.CUSTOM
        } else {
            // Prior versions initialized every discovered tool as disabled; absent selection metadata
            // means the user never chose an opt-in list.
            McpToolSelectionMode.DEFAULT_ALL
        }
        return McpServerConfig(
            id = item.getString("id"),
            alias = item.getString("alias"),
            endpoint = item.getString("endpoint"),
            transport = runCatching { McpTransport.valueOf(item.optString("transport")) }
                .getOrDefault(McpTransport.AUTO),
            authMode = runCatching { McpAuthMode.valueOf(item.optString("authMode")) }
                .getOrDefault(McpAuthMode.NONE),
            trustInsecureServer = item.optBoolean("trustInsecureServer", false),
            enabled = item.optBoolean("enabled", true),
            oauthClientId = item.optString("oauthClientId", ""),
            tools = decodedTools,
            toolSelectionMode = selectionMode,
            catalogServiceId = item.optString("catalogServiceId", "").takeIf(String::isNotBlank),
            githubToolPreferences = item.optJSONObject("githubToolPreferences")?.let { choices ->
                choices.keys().asSequence().take(512).filter { it.length <= 512 && it.none(Char::isISOControl) }
                    .associateWith { choices.optString(it) }.filterValues { it in setOf("READ:true", "READ:false", "WRITE:true", "WRITE:false") }
            }.orEmpty(),
            githubToolsets = item.optJSONArray("githubToolsets")?.let { groups ->
                (0 until groups.length()).map { groups.getString(it) }.toSet().also(com.jarvys.agent.connectors.GitHubOperationPolicy::validateToolsets)
            } ?: com.jarvys.agent.connectors.GitHubOperationPolicy.defaultToolsets,
            initialToolPolicy = runCatching { McpInitialToolPolicy.valueOf(item.optString("initialToolPolicy", "ASK")) }
                .getOrDefault(McpInitialToolPolicy.ASK),
        )
    }

    private fun persist(configs: List<McpServerConfig>) {
        val array = JSONArray()
        configs.forEach { config ->
            val tools = JSONArray()
            config.tools.forEach { tool ->
                val annotations = McpToolSecurity.encodeAnnotations(tool.annotations)
                tools.put(
                    JSONObject()
                        .put("wireName", tool.wireName)
                        .put("modelName", tool.modelName)
                        .put("description", tool.description)
                        .put("inputSchema", tool.inputSchemaJson)
                        .put("enabled", tool.enabled)
                        .put("annotations", annotations ?: JSONObject.NULL),
                )
            }
            array.put(
                JSONObject()
                    .put("id", config.id)
                    .put("alias", config.alias)
                    .put("endpoint", config.endpoint)
                    .put("transport", config.transport.name)
                    .put("authMode", config.authMode.name)
                    .put("trustInsecureServer", config.trustInsecureServer)
                    .put("enabled", config.enabled)
                    .put("oauthClientId", config.oauthClientId)
                    .put("catalogServiceId", config.catalogServiceId ?: "")
                    .put("toolSelectionMode", config.toolSelectionMode.name)
                    .put("initialToolPolicy", config.initialToolPolicy.name)
                    .put("githubToolsets", JSONArray(config.githubToolsets.sorted()))
                    .put("githubToolPreferences", JSONObject(config.githubToolPreferences))
                    .put("tools", tools),
            )
        }
        check(preferences.edit().putString(KEY_SERVERS, array.toString()).commit()) {
            "Could not persist MCP server settings"
        }
    }

    private fun validateConfig(config: McpServerConfig) {
        require(config.id.matches(Regex("[A-Za-z0-9_-]{1,96}"))) { "MCP server id is invalid" }
        require(config.alias.isNotBlank() && config.alias.length <= 64) { "MCP server alias is required (max 64 chars)" }
        require(config.endpoint.length <= 4096) { "MCP endpoint is too long" }
        require(config.oauthClientId.length <= 1024 && config.oauthClientId.none(Char::isISOControl)) {
            "OAuth client ID is invalid"
        }
        if (config.catalogServiceId == "github") com.jarvys.agent.connectors.GitHubOperationPolicy.validateToolsets(config.githubToolsets)
        require(config.tools.size <= MAX_TOOLS) { "MCP server returned too many tools" }
    }

    companion object {
        private const val PREFS = "jarvys_mcp_servers"
        private const val KEY_SERVERS = "servers_json"
        private const val SECRET_BEARER = "bearer_token"
        private const val SECRET_BEARER_ENDPOINT = "bearer_endpoint"
        private const val MAX_TOOLS = 512
        private const val MAX_TOKEN_CHARS = 32 * 1024

        @Volatile private var instance: McpServerRepository? = null

        fun get(context: Context): McpServerRepository = instance ?: synchronized(this) {
            instance ?: McpServerRepository(context,
                EncryptedMcpCredentialVault(SecretStore.get(context))).also { instance = it }
        }
    }
}
