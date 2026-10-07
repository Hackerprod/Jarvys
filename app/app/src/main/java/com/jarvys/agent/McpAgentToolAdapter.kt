package com.jarvys.agent

import com.jarvys.agent.mcp.McpToolDefinition
import com.jarvys.agent.mcp.McpToolSecurity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.LinkedHashMap

/** Lossless JSON-Schema bridge from the dynamic MCP catalog into provider tool declarations. */
internal object McpAgentToolAdapter {
    fun toToolSpec(tool: McpToolDefinition): ToolSpec = ToolSpec(
        tool.modelName,
        "MCP server ${tool.serverAlias} (${tool.serverId})",
        McpToolSecurity.sanitizeDescription(tool.description.ifBlank { "Remote MCP tool ${tool.wireName}" }),
        "mcp",
        ToolSpec.Status.IMPLEMENTED,
        emptyMap(),
        emptyList(),
        jsonObjectToMap(tool.inputSchema),
    )

    fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = jsonValue(json.opt(key))
        }
        return Collections.unmodifiableMap(result)
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> (0 until value.length()).map { jsonValue(value.opt(it)) }
        else -> value
    }
}
