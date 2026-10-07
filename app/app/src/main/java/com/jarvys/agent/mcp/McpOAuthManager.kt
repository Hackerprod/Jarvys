package com.jarvys.agent.mcp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.jarvys.agent.connectors.LoopbackOAuthCallbackServer
import com.jarvys.agent.connectors.GitHubDeviceFlowProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** OAuth 2.1 public-client PKCE coordinator for a single explicitly configured MCP server. */
class McpOAuthManager(private val repository: McpServerRepository) {
    fun authorize(activity: Activity, serverId: String, onComplete: (Result<Unit>) -> Unit) {
        AUTH_EXECUTOR.execute {
            val result = runCatching {
                val config = repository.get(serverId) ?: error("MCP server was removed")
                require(config.authMode == McpAuthMode.OAUTH) { "Select OAuth authentication for this MCP server first" }
                authorizeBlocking(activity, config)
            }
            activity.runOnUiThread { onComplete(result) }
        }
    }

    @Synchronized
    fun authorizationHeader(config: McpServerConfig): String {
        val boundEndpoint = repository.oauthValue(config.id, ENDPOINT_BINDING)
        require(boundEndpoint == config.endpoint) {
            "MCP endpoint changed; complete OAuth authorization again for this server URL"
        }
        val access = repository.oauthValue(config.id, ACCESS_TOKEN)
        val expiresAt = repository.oauthValue(config.id, EXPIRES_AT)?.toLongOrNull() ?: 0L
        if (access.isNullOrBlank() && repository.oauthValue(config.id, REFRESH_TOKEN).isNullOrBlank()) {
            throw IllegalStateException("Authorize this MCP server with OAuth first")
        }
        return if (access.isNullOrBlank() || expiresAt > 0 && expiresAt <= System.currentTimeMillis() + TOKEN_REFRESH_LEEWAY_MS) {
            refreshAccessToken(config)
        } else "Bearer $access"
    }

    /** Forces exactly one refresh attempt after an authenticated MCP request receives HTTP 401. */
    @Synchronized
    fun refreshAccessToken(config: McpServerConfig): String {
        require(repository.oauthValue(config.id, ENDPOINT_BINDING) == config.endpoint) {
            "MCP endpoint changed; complete OAuth authorization again for this server URL"
        }
        val refresh = repository.oauthValue(config.id, REFRESH_TOKEN)
            ?: throw McpReauthRequiredException("MCP OAuth refresh token is unavailable; authorize this server again")
        val tokenEndpoint = repository.oauthValue(config.id, TOKEN_ENDPOINT)
            ?: throw McpReauthRequiredException("MCP OAuth token endpoint is unavailable; authorize this server again")
        val resource = repository.oauthValue(config.id, RESOURCE) ?: config.endpoint.substringBefore('#')
        val refreshFields = linkedMapOf(
                "grant_type" to "refresh_token",
                "client_id" to config.oauthClientId,
                "refresh_token" to refresh,
            ).apply {
                // GitHub's OAuth App refresh endpoint does not implement RFC 8707 resource parameters.
                if (config.catalogServiceId != "github") put("resource", resource)
            }
        val response = try {
            tokenRequest(tokenEndpoint, refreshFields)
        } catch (failure: RuntimeException) {
            throw McpReauthRequiredException("MCP OAuth refresh failed; authorize this server again")
        }
        val access = response.optString("access_token")
        if (access.isBlank()) throw McpReauthRequiredException("MCP OAuth refresh returned no access token; authorize again")
        repository.saveOAuthValue(config.id, ACCESS_TOKEN, access)
        // OAuth refresh token rotation is optional; retain the previous refresh token when omitted.
        repository.saveOAuthValue(config.id, REFRESH_TOKEN, McpOAuthRequestRetry.rotatedRefreshToken(response, refresh))
        repository.saveOAuthValue(config.id, EXPIRES_AT, expiry(response).toString())
        return "Bearer $access"
    }

    @Synchronized
    fun invalidateAccessToken(config: McpServerConfig) {
        require(repository.oauthValue(config.id, ENDPOINT_BINDING) == config.endpoint) {
            "MCP endpoint changed; complete OAuth authorization again for this server URL"
        }
        repository.saveOAuthValue(config.id, ACCESS_TOKEN, null)
    }

    /** Best-effort RFC 7009 cleanup before the local MCP configuration and SecretStore entries are deleted. */
    @Synchronized
    fun revokeAndClear(config: McpServerConfig) {
        val revokeEndpoint = repository.oauthValue(config.id, REVOCATION_ENDPOINT)
        val access = repository.oauthValue(config.id, ACCESS_TOKEN)
        val refresh = repository.oauthValue(config.id, REFRESH_TOKEN)
        if (!revokeEndpoint.isNullOrBlank()) {
            for (token in listOfNotNull(refresh, access).distinct()) {
                runCatching { revokeToken(revokeEndpoint, config.oauthClientId, token) }
            }
        }
        clear(config.id)
    }

    private fun revokeToken(endpoint: String, clientId: String, token: String) {
        val connection = open(endpoint, "POST", "application/json")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val fields = linkedMapOf("token" to token, "client_id" to clientId)
            val body = fields.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299 && status != 400) throw IllegalStateException("OAuth token revocation failed")
        } finally { connection.disconnect() }
    }

    fun clear(serverId: String) {
        listOf(ACCESS_TOKEN, REFRESH_TOKEN, EXPIRES_AT, TOKEN_ENDPOINT, RESOURCE, ENDPOINT_BINDING,
            REVOCATION_ENDPOINT).forEach {
            repository.saveOAuthValue(serverId, it, null)
        }
    }

    /** Adopts a GitHub OAuth App Device Flow token into the same encrypted MCP session store. */
    fun saveGitHubDeviceGrant(config: McpServerConfig, accessToken: String, refreshToken: String?, expiresAt: Long) {
        require(config.catalogServiceId == "github" && config.endpoint == GITHUB_MCP_ENDPOINT
            && config.authMode == McpAuthMode.OAUTH
            && config.oauthClientId == GitHubDeviceFlowProtocol.CLIENT_ID) {
            "GitHub device grant did not match the verified remote service configuration"
        }
        requireCurrentServer(config)
        require(accessToken.isNotBlank() && accessToken.length <= 4096)
        repository.saveOAuthValue(config.id, ACCESS_TOKEN, accessToken)
        repository.saveOAuthValue(config.id, REFRESH_TOKEN, refreshToken)
        repository.saveOAuthValue(config.id, EXPIRES_AT, expiresAt.takeIf { it > 0 }?.toString())
        repository.saveOAuthValue(config.id, TOKEN_ENDPOINT, GitHubDeviceFlowProtocol.TOKEN_ENDPOINT)
        repository.saveOAuthValue(config.id, RESOURCE, config.endpoint)
        repository.saveOAuthValue(config.id, ENDPOINT_BINDING, config.endpoint)
        repository.saveOAuthValue(config.id, REVOCATION_ENDPOINT, null)
    }

    private fun authorizeBlocking(activity: Activity, config: McpServerConfig) {
        val resourceUri = McpEndpointPolicy.validate(config.endpoint, config.trustInsecureServer)
        val challengeConnection = open(resourceUri.toURL(), "GET", "application/json, text/event-stream")
        val challengeCode = challengeConnection.responseCode
        val challengeHeader = challengeConnection.getHeaderField("WWW-Authenticate").orEmpty()
        challengeConnection.disconnect()
        val advertisedUrl = Regex("resource_metadata\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(challengeHeader)?.groupValues?.getOrNull(1)
        val metadataUrl = advertisedUrl ?: protectedResourceCandidates(resourceUri).firstNotNullOfOrNull { candidate ->
            runCatching { fetchJson(candidate) }.getOrNull()?.takeIf { it.has("authorization_servers") }?.let { candidate }
        }
        val directAuthorizationMetadata = if (metadataUrl == null) {
            McpCatalogOAuthDiscovery.directAuthorizationServerMetadata(config.catalogServiceId, resourceUri, ::fetchJson)
        } else null
        require(metadataUrl != null || directAuthorizationMetadata != null) {
            if (challengeCode == 401) "MCP server did not publish OAuth protected-resource metadata" else "MCP server does not advertise OAuth"
        }
        val protected = if (metadataUrl != null) {
            val metadataUri = McpEndpointPolicy.validate(metadataUrl, config.trustInsecureServer)
            if (metadataUri.scheme.equals("http", true)) {
                require(sameOrigin(resourceUri, metadataUri)) { "HTTP OAuth resource metadata must share the explicitly trusted MCP server origin" }
            }
            fetchJson(metadataUri.toString())
        } else {
            JSONObject().put("resource", canonical(resourceUri))
                .put("authorization_servers", JSONArray().put(requireNotNull(directAuthorizationMetadata).optString("issuer")))
        }
        val resource = protected.optString("resource").ifBlank { canonical(resourceUri) }
        val resourceUriCanonical = validateHttpsOrTrustedHttpResource(resource, config)
        require(sameOrigin(resourceUri, resourceUriCanonical)) { "OAuth resource does not match the configured MCP server origin" }
        val issuer = protected.optJSONArray("authorization_servers")?.optString(0).orEmpty()
        require(issuer.isNotBlank()) { "OAuth metadata omitted authorization_servers" }
        val issuerUri = validateHttpsMetadata(issuer)
        val authorizationMetadata = directAuthorizationMetadata ?: authorizationMetadataCandidates(issuerUri).firstNotNullOfOrNull { candidate ->
            runCatching {
                fetchJson(candidate).takeIf { it.optString("issuer") == issuer }
            }.getOrNull()
        } ?: error("OAuth authorization-server metadata could not be discovered")
        val pkceMethods = authorizationMetadata.optJSONArray("code_challenge_methods_supported") ?: JSONArray()
        require((0 until pkceMethods.length()).any { pkceMethods.optString(it) == "S256" }) {
            "Authorization server does not advertise PKCE S256"
        }
        authorizationMetadata.optJSONArray("token_endpoint_auth_methods_supported")?.let { methods ->
            require((0 until methods.length()).any { methods.optString(it) == "none" }) {
                "Authorization server does not support a public OAuth client"
            }
        }
        val authEndpoint = validateHttpsMetadata(authorizationMetadata.getString("authorization_endpoint"))
        val tokenEndpoint = validateHttpsMetadata(authorizationMetadata.getString("token_endpoint"))
        val registrationEndpoint = authorizationMetadata.optString("registration_endpoint").takeIf(String::isNotBlank)
            ?.let(::validateHttpsMetadata)
        val clientId = config.oauthClientId.ifBlank {
            requireNotNull(registrationEndpoint) { "Enter a pre-registered OAuth client ID; this server has no DCR endpoint" }
            dynamicRegister(registrationEndpoint, REDIRECT_URI, config.alias)
        }
        require(clientId.length <= 1024 && clientId.none(Char::isISOControl)) { "OAuth client ID is invalid" }
        val latestConfig = requireCurrentServer(config)
        if (config.oauthClientId.isBlank()) repository.upsert(latestConfig.copy(oauthClientId = clientId))

        val scope = McpCatalogOAuthDiscovery.requestedScope(config.catalogServiceId, challengeHeader, protected).orEmpty()
        val random = SecureRandom()
        val verifierBytes = ByteArray(48).also(random::nextBytes)
        val verifier = Base64.encodeToString(verifierBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        verifierBytes.fill(0)
        val stateBytes = ByteArray(24).also(random::nextBytes)
        val state = Base64.encodeToString(stateBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        stateBytes.fill(0)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        LoopbackOAuthCallbackServer.fixed(REDIRECT_PORT, CALLBACK_PATH).use { callbackServer ->
            val authorizationUrl = Uri.parse(authEndpoint.toString()).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", callbackServer.redirectUri)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state)
                .appendQueryParameter("resource", canonical(resourceUriCanonical))
                .apply { if (scope.isNotBlank()) appendQueryParameter("scope", scope) }
                .build()
            activity.runOnUiThread {
                runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, authorizationUrl) }
                    .onFailure { activity.startActivity(Intent(Intent.ACTION_VIEW, authorizationUrl)) }
            }
            val code = callbackServer.awaitCode(state, OAUTH_CALLBACK_TIMEOUT_MS)
            requireCurrentServer(config)
            val token = tokenRequest(
                tokenEndpoint.toString(),
                linkedMapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "code_verifier" to verifier,
                    "resource" to canonical(resourceUriCanonical),
                ),
            )
            requireCurrentServer(config)
            repository.saveOAuthValue(config.id, ACCESS_TOKEN, token.getString("access_token"))
            repository.saveOAuthValue(config.id, REFRESH_TOKEN, token.optString("refresh_token").takeIf(String::isNotBlank))
            repository.saveOAuthValue(config.id, EXPIRES_AT, expiry(token).toString())
            repository.saveOAuthValue(config.id, TOKEN_ENDPOINT, tokenEndpoint.toString())
            repository.saveOAuthValue(config.id, RESOURCE, canonical(resourceUriCanonical))
            repository.saveOAuthValue(config.id, ENDPOINT_BINDING, config.endpoint)
            repository.saveOAuthValue(config.id, REVOCATION_ENDPOINT,
                authorizationMetadata.optString("revocation_endpoint").takeIf(String::isNotBlank)
                    ?.let { validateHttpsMetadata(it).toString() })
        }
    }

    private fun dynamicRegister(endpoint: URI, redirectUri: String, alias: String): String {
        val registration = JSONObject()
            .put("client_name", "Jarvys")
            .put("redirect_uris", JSONArray().put(redirectUri))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
        return postJson(endpoint.toString(), registration).getString("client_id")
    }

    private fun tokenRequest(endpoint: String, fields: Map<String, String>): JSONObject {
        val connection = open(endpoint, "POST", "application/json")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = fields.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("OAuth token request failed with HTTP $status")
            return JSONObject(readBody(connection.inputStream))
                .also { require(it.optString("token_type").equals("Bearer", true)) { "OAuth token_type must be Bearer" } }
                .also { require(it.optString("access_token").isNotBlank()) { "OAuth response omitted access_token" } }
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(endpoint: String, body: JSONObject): JSONObject {
        val connection = open(endpoint, "POST", "application/json")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("OAuth client registration failed with HTTP $status")
            return JSONObject(readBody(connection.inputStream))
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String, accept: String): HttpURLConnection = open(URL(url), method, accept)

    private fun open(url: URL, method: String, accept: String): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = false
        setRequestProperty("Accept", accept)
    }

    private fun fetchJson(url: String): JSONObject {
        val connection = open(url, "GET", "application/json")
        try {
            if (connection.responseCode !in 200..299) return JSONObject()
            return JSONObject(readBody(connection.inputStream))
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(input: java.io.InputStream): String {
        input.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= MAX_OAUTH_RESPONSE_BYTES) { "OAuth metadata response is too large" }
                output.write(buffer, 0, n)
            }
            return output.toString("UTF-8")
        }
    }

    private fun protectedResourceCandidates(resource: URI): List<String> {
        val origin = "${resource.scheme}://${resource.rawAuthority}"
        val path = resource.rawPath.orEmpty().trimEnd('/').takeUnless { it == "/" }.orEmpty()
        return listOfNotNull(
            if (path.isNotEmpty()) "$origin/.well-known/oauth-protected-resource$path" else null,
            "$origin/.well-known/oauth-protected-resource",
        )
    }

    private fun authorizationMetadataCandidates(issuer: URI): List<String> {
        val origin = "${issuer.scheme}://${issuer.rawAuthority}"
        val path = issuer.rawPath.orEmpty().trimEnd('/').takeUnless { it == "/" }.orEmpty()
        val result = mutableListOf<String>()
        if (path.isNotEmpty()) {
            result += "$origin/.well-known/oauth-authorization-server$path"
            result += "$origin/.well-known/openid-configuration$path"
            result += "$origin$path/.well-known/openid-configuration"
        }
        result += "$origin/.well-known/oauth-authorization-server"
        result += "$origin/.well-known/openid-configuration"
        return result
    }

    private fun validateHttpsMetadata(value: String): URI {
        val uri = runCatching { URI(value) }.getOrNull() ?: error("OAuth metadata URL is invalid")
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "OAuth authorization and metadata endpoints must use HTTPS"
        }
        return uri
    }

    private fun validateHttpsOrTrustedHttpResource(value: String, config: McpServerConfig): URI =
        McpEndpointPolicy.validate(value, config.trustInsecureServer)

    private fun sameOrigin(a: URI, b: URI): Boolean = a.scheme.equals(b.scheme, true)
            && a.host.equals(b.host, true) && port(a) == port(b)

    private fun port(uri: URI): Int = uri.port.takeIf { it >= 0 } ?: if (uri.scheme.equals("https", true)) 443 else 80

    private fun canonical(uri: URI): String {
        val scheme = uri.scheme.lowercase(java.util.Locale.ROOT)
        val host = uri.host.lowercase(java.util.Locale.ROOT)
        val authorityHost = if (host.contains(':')) "[$host]" else host
        val defaultPort = if (scheme == "https") 443 else 80
        val port = if (uri.port >= 0 && uri.port != defaultPort) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().takeUnless { it == "/" }.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$authorityHost$port$path$query"
    }

    private fun expiry(token: JSONObject): Long {
        val seconds = token.optLong("expires_in", 0L).coerceIn(0, MAX_TOKEN_LIFETIME_SECONDS)
        return if (seconds == 0L) 0L else System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds)
    }

    private fun requireCurrentServer(expected: McpServerConfig): McpServerConfig {
        val current = repository.get(expected.id) ?: error("MCP server was removed during OAuth authorization")
        require(current.endpoint == expected.endpoint && current.authMode == McpAuthMode.OAUTH) {
            "MCP server URL or authentication changed during OAuth authorization"
        }
        return current
    }

    companion object {
        private const val REDIRECT_PORT = 37167
        private const val CALLBACK_PATH = "/mcp-oauth/callback"
        const val REDIRECT_URI = "http://127.0.0.1:$REDIRECT_PORT$CALLBACK_PATH"
        private const val OAUTH_CALLBACK_TIMEOUT_MS = 180_000
        private const val TOKEN_REFRESH_LEEWAY_MS = 60_000L
        private const val MAX_OAUTH_RESPONSE_BYTES = 128 * 1024
        private const val MAX_AUTH_CODE_CHARS = 8 * 1024
        private const val MAX_TOKEN_LIFETIME_SECONDS = 315_360_000L
        private const val GITHUB_MCP_ENDPOINT = "https://api.githubcopilot.com/mcp/"
        private const val ACCESS_TOKEN = "oauth_access_token"
        private const val REFRESH_TOKEN = "oauth_refresh_token"
        private const val EXPIRES_AT = "oauth_expires_at"
        private const val TOKEN_ENDPOINT = "oauth_token_endpoint"
        private const val RESOURCE = "oauth_resource"
        private const val ENDPOINT_BINDING = "oauth_endpoint_binding"
        private const val REVOCATION_ENDPOINT = "oauth_revocation_endpoint"
        private val AUTH_EXECUTOR: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "JarvysMcpOAuth").apply { isDaemon = true }
        }

        @Volatile private var instance: McpOAuthManager? = null

        fun get(repository: McpServerRepository): McpOAuthManager = instance ?: synchronized(this) {
            instance ?: McpOAuthManager(repository).also { instance = it }
        }
    }
}
