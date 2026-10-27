package com.jarvys.agent;

import java.util.Map;

/**
 * Tool contract owned by the new conversation core; it has no Android/legacy dispatcher dependency.
 */
public interface CoreTool {
  enum ExecutionMode { PARALLEL, SEQUENTIAL }
  /** Admission policy for calls returned together in one model response. */
  enum ResponseCallPolicy { MULTIPLE_PER_RESPONSE, ONE_PER_RESPONSE }
  enum UnattendedAccess { EXCLUDED, READ, WRITE }
  enum AccessClassification { READ, WRITE }
  enum EffectClass { READ, WRITE }
  enum PersistenceEffect { NONE, MEMORY }
  /** A durable job submission is administrative acceptance, never synchronous command completion. */
  enum CompletionMode { SYNCHRONOUS, ASYNC_JOB }
  default CompletionMode completionMode(Map<String, Object> arguments) { return CompletionMode.SYNCHRONOUS; }
  /** Opt-in requires the complete durable producer and exact invocation-bound acceptance protocol. */
  default boolean hasCompleteAsyncSubmissionCoverage() { return false; }

  interface ProgressListener {
    void onProgress(String message);
  }

  default boolean canDelegate() {
    return true;
  }

  /** Tools opt in explicitly before a silent scheduled run can call them. */
  default UnattendedAccess unattendedAccess() {
    return UnattendedAccess.EXCLUDED;
  }

  /** Identifies native connector handlers through task-scoped wrappers without name heuristics. */
  default boolean isConnectorTool() {
    return false;
  }

  /** True for MCP backed calls whose returned data is untrusted external content. */
  default boolean isMcpTool() {
    return false;
  }

  /** Task file tools use a memory-only view and require the creator's saved memory capability. */
  default boolean requiresMemoryScopeForScheduledTask() {
    return false;
  }

  /** Generic access and persistence-effect metadata; it does not depend on tool names. */
  default AccessClassification accessClassification() { return AccessClassification.READ; }
  /** Normalized external effect classification used by durable episode capture. */
  default EffectClass effectClass() {
    return accessClassification() == AccessClassification.WRITE || unattendedAccess() == UnattendedAccess.WRITE
        ? EffectClass.WRITE : EffectClass.READ;
  }
  /** Argument-sensitive effects are used by tools whose one declaration supports reads and writes. */
  default EffectClass effectClass(Map<String, Object> arguments) { return effectClass(); }
  default PersistenceEffect persistenceEffect() { return PersistenceEffect.NONE; }
  /** Mixed workspace tools retain WRITE classification while excluding internal memory maintenance. */
  default PersistenceEffect persistenceEffect(Map<String, Object> arguments) { return persistenceEffect(); }

  /**
   * Tools keep ReAct's original call order unless their implementation explicitly opts into
   * parallel execution after establishing that calls are independent.
   */
  default ExecutionMode executionMode() {
    return ExecutionMode.SEQUENTIAL;
  }

  /**
   * Whether a model response may request multiple calls to this tool. This is independent of
   * executionMode(): SEQUENTIAL preserves execution order but still executes every admitted call.
   */
  default ResponseCallPolicy responseCallPolicy() {
    return ResponseCallPolicy.MULTIPLE_PER_RESPONSE;
  }

  /** Factual tool-owned result for a call rejected by responseCallPolicy(). */
  default String additionalCallNotStartedMessage() {
    return "This call was not started because the tool permits one invocation per model response.";
  }

  /** Tool-owned clarification for schema failures that need domain catalog context. */
  default String preflightErrorMessage(Map<String, Object> arguments, String reason) {
    return reason;
  }

  /**
   * Optional stable identity for a complete result. The loop may reuse it only when a matching,
   * complete result is present in the exact transcript sent to the current model response.
   */
  default String completeResultIdentity(Map<String, Object> arguments) {
    return null;
  }

  /** The tool decides whether transcript content is its complete result for the given arguments. */
  default boolean isCompleteResult(Map<String, Object> arguments, String content) {
    return false;
  }

  /** Factual tool-owned result used when the complete result is already visible to the model. */
  default String completeResultAlreadyVisibleMessage() {
    return "The complete result for this call is already present in the transcript sent with this response.";
  }

  default String auditDetail(Map<String, Object> arguments) {
    return null;
  }

  /** Hide routine activity for tools whose successful effect is already visible elsewhere. */
  default boolean hideFromProgress() {
    return false;
  }

  /** Optional activity label derived from this tool's arguments; null uses the registry label. */
  default String activityDisplayName(Map<String, Object> arguments) {
    return null;
  }

  /** Optional skill metadata used by activity presentation without tool-specific loop logic. */
  default String activitySkillId(Map<String, Object> arguments) {
    return "";
  }

  default String activitySkillName(Map<String, Object> arguments) {
    return "";
  }

  ToolSpec declaration();

  CoreToolResult execute(Map<String, Object> arguments, CancellationToken token);

  /** Optional live row updates for long operations; existing tools need not implement it. */
  default CoreToolResult execute(
      Map<String, Object> arguments, CancellationToken token, ProgressListener progress) {
    return execute(arguments, token);
  }
  /** True only when this adapter covers every actual effect dispatch with the invocation context. */
  default boolean hasCompleteEffectAdmissionCoverage() {
    return !isConnectorTool() && !isMcpTool() && !(this instanceof PolicyGatedTool);
  }

  /** Explicit invocation transport. Legacy gated adapters fail closed instead of inventing ALLOW. */
  default CoreToolResult execute(Map<String, Object> arguments, CancellationToken token,
      ProgressListener progress, EffectInvocation invocation) {
    if (invocation == null || effectClass(arguments) != EffectClass.WRITE || persistenceEffect(arguments) == PersistenceEffect.MEMORY)
      return execute(arguments, token, progress);
    if (!hasCompleteEffectAdmissionCoverage())
      throw new IllegalStateException("Effect admission context is not supported by this adapter");
    token.throwIfCancelled();
    invocation.admit(new EffectInvocation.Admission(EffectInvocation.Authorization.ALLOW,
        "native", declaration().name, null, "native_contract"));
    // A legacy success flag is not a completion receipt. Producers must explicitly describe their effect.
    return execute(arguments, token, progress);
  }

}
