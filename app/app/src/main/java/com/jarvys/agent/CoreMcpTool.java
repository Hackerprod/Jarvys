package com.jarvys.agent;

import com.jarvys.agent.mcp.McpConnectionManager;
import com.jarvys.agent.mcp.McpToolDefinition;
import com.jarvys.agent.mcp.McpToolSecurity;

import org.json.JSONObject;

import java.util.Collections;
import java.util.Map;

/** MCP capability adapter for the new core registry; deliberately independent of Android-use dispatch. */
final class CoreMcpTool implements CoreTool {
    private final McpConnectionManager connections;
    private final McpToolDefinition definition;
    private final String requester;
    private final String requesterColorKey;
    private final ToolSpec declaration;

    CoreMcpTool(McpConnectionManager connections, McpToolDefinition definition) {
        this(connections, definition, null, null);
    }

    private CoreMcpTool(McpConnectionManager connections, McpToolDefinition definition, String requester,
                        String requesterColorKey) {
        this.connections = connections;
        this.definition = definition;
        this.requester = requester;
        this.requesterColorKey = requesterColorKey;
        this.declaration = McpAgentToolAdapter.INSTANCE.toToolSpec(definition);
    }

    @Override public ToolSpec declaration() { return declaration; }

    String groupName() { return "mcp_server:" + definition.getServerId(); }
    CoreMcpTool withRequester(String name, String colorKey) { return new CoreMcpTool(connections, definition, name, colorKey); }

    String displayName() { return CoreToolRegistry.humanizeToolName(definition.getWireName()); }

    @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
        JSONObject response = connections.callTool(definition.getServerId(), definition.getWireName(),
                new JSONObject(arguments == null ? Collections.emptyMap() : arguments), token, requester, requesterColorKey);
        JSONObject envelope = McpToolSecurity.INSTANCE.boundedUntrustedResult(
                definition.getServerAlias(), definition.getWireName(), response);
        return envelope.optBoolean("isError", false)
                ? CoreToolResult.failure(envelope.toString()) : CoreToolResult.success(envelope.toString());
    }
}
