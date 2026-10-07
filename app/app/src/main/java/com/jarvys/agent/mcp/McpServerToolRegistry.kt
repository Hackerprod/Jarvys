package com.jarvys.agent.mcp

import java.security.MessageDigest

/** Dynamic MCP inventory kept separate from ToolRegistry.EXPECTED_INVENTORY. */
class McpServerToolRegistry(private val repository: McpServerRepository) {
    fun all(): List<McpToolDefinition> = repository.exposedTools()

    fun resolve(modelName: String): McpToolDefinition? = all().firstOrNull { it.modelName == modelName }

    companion object {
        fun namespace(server: McpServerConfig, wireName: String): String {
            require(wireName.isNotBlank() && wireName.length <= 512 && wireName.none(Char::isISOControl)) {
                "MCP tool name is invalid"
            }
            val serverTag = sha256(server.id).take(8)
            val safeAlias = server.alias.lowercase(java.util.Locale.ROOT)
                .map { if (it in 'a'..'z' || it in '0'..'9' || it == '_') it else '_' }
                .joinToString("").trim('_').ifBlank { "server" }.take(12)
            val safeName = wireName.lowercase(java.util.Locale.ROOT)
                .map { if (it in 'a'..'z' || it in '0'..'9' || it == '_') it else '_' }
                .joinToString("").trim('_').ifBlank { "tool" }
            val prefix = "mcp_${safeAlias}_${serverTag}__"
            val room = (64 - prefix.length).coerceAtLeast(1)
            val shortName = if (safeName.length <= room && safeName == wireName) safeName else {
                val hash = sha256(wireName).take(8)
                safeName.take((room - hash.length - 2).coerceAtLeast(1)).trimEnd('_') + "__" + hash
            }
            return (prefix + shortName).take(64)
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
