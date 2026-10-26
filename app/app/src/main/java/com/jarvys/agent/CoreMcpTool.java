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
final class CoreMcpTool implements PolicyGatedTool {
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
    @Override public EffectClass effectClass() { return definition.getAccess() == McpToolAccess.WRITE ? EffectClass.WRITE : EffectClass.READ; }
    @Override public boolean isMcpTool() { return true; }

    @Override public String policyGroupName() { return "mcp_server:" + definition.getServerId(); }
    boolean isWriteOperation() { return definition.getAccess() == McpToolAccess.WRITE; }
    @Override public AutonomyPolicy effectiveAutonomyPolicy() { return connections.getWriteApproval().policy(definition); }
    @Override public CoreMcpTool withRequester(String name, String colorKey) {
        return new CoreMcpTool(connections, definition, name, colorKey);
    }

    @Override public String policyDisplayName() { return CoreToolRegistry.humanizeToolName(definition.getWireName()); }
    @Override public String policyReflectionSource() { return "mcp"; }

    @Override public boolean hasCompleteEffectAdmissionCoverage() { return true; }

    @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
        return execute(arguments, token, null, null);
    }

    @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token,
        ProgressListener progress, EffectInvocation invocation) {
        JSONObject response = connections.callTool(definition.getServerId(), definition.getWireName(),
                new JSONObject(arguments == null ? Collections.emptyMap() : arguments), token, requester,
                requesterColorKey, invocation);
        return result(response, invocation);
    }

    @Override public CoreToolResult executeScheduledTask(Map<String, Object> arguments, CancellationToken token,
        boolean approvedPendingAction, java.util.function.Supplier<JSONObject> deferAskWrite) {
        return executeScheduledTask(arguments, token, approvedPendingAction, deferAskWrite, null);
    }

    @Override public CoreToolResult executeScheduledTask(Map<String, Object> arguments, CancellationToken token,
        boolean approvedPendingAction, java.util.function.Supplier<JSONObject> deferAskWrite,
        EffectInvocation invocation) {
        java.util.concurrent.atomic.AtomicBoolean queued = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.function.Supplier<JSONObject> deferred = deferAskWrite == null ? null : () -> {
            JSONObject receipt = deferAskWrite.get();
            queued.set(true);
            return receipt;
        };
        JSONObject response = connections.callToolForScheduledTask(definition.getServerId(),
                definition.getWireName(), new JSONObject(arguments == null ? Collections.emptyMap() : arguments),
                token, approvedPendingAction, deferred, invocation);
        if (queued.get()) {
            CoreToolResult result = CoreToolResult.success(response.toString());
            return invocation == null ? result : result.withEffectReceipt(
                invocation.receipt(EffectInvocation.Outcome.NOT_EXECUTED));
        }
        return result(response, invocation);
    }

    private CoreToolResult result(JSONObject response, EffectInvocation invocation) {
        JSONObject envelope = McpToolSecurity.INSTANCE.boundedUntrustedResult(
                definition.getServerAlias(), definition.getWireName(), response);
        CoreToolResult result = envelope.optBoolean("isError", false)
                ? CoreToolResult.failure(envelope.toString()) : CoreToolResult.success(envelope.toString());
        if (invocation == null) return result;
        JSONObject protocolResult = response.optJSONObject("result");
        boolean valid = protocolResult != null && protocolResult.optJSONArray("content") != null
                && (!protocolResult.has("isError") || protocolResult.opt("isError") instanceof Boolean);
        if (!valid) result = CoreToolResult.failure(envelope.toString());
        if (!isWriteOperation()) return valid && result.success
                ? result.withAdministrativeReceipt(invocation.readCompleted()) : result;
        EffectInvocation.Outcome outcome = !invocation.admitted() ? EffectInvocation.Outcome.NOT_EXECUTED
                : !valid || !result.success ? EffectInvocation.Outcome.UNCERTAIN : EffectInvocation.Outcome.OK;
        return result.withEffectReceipt(invocation.receipt(outcome));
    }
}
