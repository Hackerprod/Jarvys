package com.jarvys.agent;

import com.jarvys.agent.mcp.McpConnectionManager;
import com.jarvys.agent.mcp.McpToolDefinition;
import com.jarvys.agent.mcp.McpToolSecurity;
import com.jarvys.agent.mcp.McpToolAccess;
import com.jarvys.agent.connectors.AutonomyPolicy;

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
    @Override public ExecutionMode executionMode() {
        return definition.getAccess() == McpToolAccess.READ ? ExecutionMode.PARALLEL : ExecutionMode.SEQUENTIAL;
    }
    @Override public UnattendedAccess unattendedAccess() {
        return definition.getAccess() == McpToolAccess.WRITE ? UnattendedAccess.WRITE : UnattendedAccess.READ;
    }
    @Override public boolean isMcpTool() { return true; }

    String groupName() { return "mcp_server:" + definition.getServerId(); }
    boolean isWriteOperation() { return definition.getAccess() == McpToolAccess.WRITE; }
    AutonomyPolicy autonomyPolicy() { return connections.getWriteApproval().policy(definition); }
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

    CoreToolResult executeScheduledTask(Map<String, Object> arguments, CancellationToken token,
                                        boolean approvedPendingAction,
                                        java.util.function.Supplier<JSONObject> deferAskWrite) {
        JSONObject response = connections.callToolForScheduledTask(definition.getServerId(),
                definition.getWireName(), new JSONObject(arguments == null ? Collections.emptyMap() : arguments),
                token, approvedPendingAction, deferAskWrite);
        if ("pending_user_approval".equals(response.optString("status")))
            return CoreToolResult.success(response.toString());
        JSONObject envelope = McpToolSecurity.INSTANCE.boundedUntrustedResult(
                definition.getServerAlias(), definition.getWireName(), response);
        return envelope.optBoolean("isError", false)
                ? CoreToolResult.failure(envelope.toString()) : CoreToolResult.success(envelope.toString());
    }
}
