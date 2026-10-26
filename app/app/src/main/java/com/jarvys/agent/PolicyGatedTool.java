package com.jarvys.agent;

import com.jarvys.agent.connectors.AutonomyPolicy;
import org.json.JSONObject;

import java.util.Map;
import java.util.function.Supplier;

/** Shared connector-style policy and scheduled-dispatch contract for dynamic tools. */
public interface PolicyGatedTool extends CoreTool {
  AutonomyPolicy effectiveAutonomyPolicy();

  /** Optional capability group used by the runtime's existing scoped-tool filter. */
  String policyGroupName();

  String policyDisplayName();

  String policyReflectionSource();

  PolicyGatedTool withRequester(String requester, String requesterColorKey);

  CoreToolResult executeScheduledTask(
      Map<String, Object> arguments,
      CancellationToken token,
      boolean approvedPendingAction,
      Supplier<JSONObject> deferAskWrite);
  default CoreToolResult executeScheduledTask(Map<String, Object> arguments, CancellationToken token,
      boolean approvedPendingAction, Supplier<JSONObject> deferAskWrite, EffectInvocation invocation) {
    if (invocation != null) throw new IllegalStateException("Scheduled gated effect context is unsupported");
    return executeScheduledTask(arguments, token, approvedPendingAction, deferAskWrite);
  }
}
