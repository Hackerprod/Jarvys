package com.jarvys.agent.mcp

import java.net.URI

enum class McpServerEditorValidationError {
    ALIAS_REQUIRED, URL_REQUIRED, URL_INVALID, CONFIGURATION_INVALID, BEARER_REQUIRED, TOKEN_REENTER,
}

object McpServerEditorValidation {
    val transports = listOf(McpTransport.AUTO, McpTransport.STREAMABLE_HTTP, McpTransport.LEGACY_SSE)
    val authModes = listOf(McpAuthMode.NONE, McpAuthMode.BEARER, McpAuthMode.OAUTH)

    fun validate(
        alias: String,
        endpoint: String,
        transport: McpTransport,
        authMode: McpAuthMode,
        bearerProvided: Boolean,
        savedBearerAvailable: Boolean,
        endpointChanged: Boolean,
    ): McpServerEditorValidationError? {
        if (alias.trim().isBlank()) return McpServerEditorValidationError.ALIAS_REQUIRED
        if (endpoint.trim().isBlank()) return McpServerEditorValidationError.URL_REQUIRED
        if (transport !in transports || authMode !in authModes) return McpServerEditorValidationError.CONFIGURATION_INVALID
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull()
        if (uri == null || !(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) ||
            uri.host.isNullOrBlank() || uri.userInfo != null || uri.rawUserInfo != null) {
            return McpServerEditorValidationError.URL_INVALID
        }
        if (authMode == McpAuthMode.BEARER && !bearerProvided && !savedBearerAvailable) {
            return McpServerEditorValidationError.BEARER_REQUIRED
        }
        if (authMode == McpAuthMode.BEARER && !bearerProvided && savedBearerAvailable && endpointChanged) {
            return McpServerEditorValidationError.TOKEN_REENTER
        }
        return null
    }
}
