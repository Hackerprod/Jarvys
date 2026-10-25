package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.chatdb.ChatDatabaseBridge;
import org.json.JSONObject;
import java.util.Collections;
import java.util.Map;

/** Captures normalized effects without copying tool arguments or connector content into memory. */
final class EpisodeRecorder implements CoreToolRegistry.InvocationObserver {
  private final String conversationId;
  private final String ownerMessageId;
  private final ChatDatabaseBridge database;

  EpisodeRecorder(Context context, String conversationId, String ownerMessageId) {
    this.conversationId = conversationId;
    this.ownerMessageId = ownerMessageId;
    this.database = ChatDatabaseBridge.getInstance(context);
  }

  @Override public void beforeDispatch(CoreTool tool, String callId, Map<String, Object> arguments) {
    if (!records(tool) || blank(callId) || blank(ownerMessageId)) return;
    String connector = tool instanceof CoreConnectorTool ? ((CoreConnectorTool) tool).connectorId()
        : tool instanceof CoreMcpTool ? "mcp:" + ((CoreMcpTool) tool).policyGroupName() : null;
    String policy = policy(tool);
    JSONObject facts = new JSONObject();
    try {
      facts.put("classification", "WRITE");
      facts.put("policy", policy);
    } catch (Exception ignored) { }
    database.personalAgentRecordEpisodeToolStartedSync(conversationId, ownerMessageId, callId,
        tool.declaration().name, connector, authorization(policy), facts.toString(), "started");
  }

  @Override public void afterDispatch(CoreTool tool, String callId, CoreToolResult result, Throwable failure) {
    if (!records(tool) || blank(callId) || blank(ownerMessageId)) return;
    String outcome = failure != null ? (failure instanceof java.util.concurrent.CancellationException ? "uncertain" : "error")
        : result != null && result.success ? "ok" : "error";
    database.personalAgentFinishEpisodeToolEvidenceSync(conversationId, callId, outcome, "{}");
  }

  private boolean records(CoreTool tool) {
    return tool.effectClass() == CoreTool.EffectClass.WRITE
        && tool.persistenceEffect() != CoreTool.PersistenceEffect.MEMORY;
  }

  private static String policy(CoreTool tool) {
    if (tool instanceof CoreConnectorTool) return ((CoreConnectorTool) tool).autonomyPolicy().name();
    if (tool instanceof PolicyGatedTool) return ((PolicyGatedTool) tool).effectiveAutonomyPolicy().name();
    return "UNSPECIFIED";
  }

  private static String authorization(String policy) {
    if ("ALLOW".equals(policy)) return "allow";
    if ("DENY".equals(policy)) return "deny";
    return "ask_pending";
  }

  private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
