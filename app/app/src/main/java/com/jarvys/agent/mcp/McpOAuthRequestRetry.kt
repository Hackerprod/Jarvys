package com.jarvys.agent.mcp

import org.json.JSONObject

/** Exactly one OAuth refresh and one retry; permission errors and non-OAuth auth never refresh. */
internal object McpOAuthRequestRetry {
    fun <T> execute(
        oauthEnabled: Boolean,
        refreshOnce: () -> Unit,
        request: () -> T,
    ): T {
        try { return request() } catch (unauthorized: McpTransportClient.McpAuthorizationException) {
            if (!oauthEnabled) throw unauthorized
        }
        try {
            refreshOnce()
        } catch (failure: Exception) {
            throw if (failure is McpReauthRequiredException) failure
            else McpReauthRequiredException("MCP OAuth refresh failed; reauthorize this service")
        }
        return try {
            request()
        } catch (unauthorized: McpTransportClient.McpAuthorizationException) {
            throw McpReauthRequiredException("MCP authorization failed after one refresh; reauthorize this service")
        }
    }

    fun rotatedRefreshToken(response: JSONObject, previous: String): String =
        response.optString("refresh_token").takeIf(String::isNotBlank) ?: previous
}
