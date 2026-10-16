package com.jarvys.agent;

import java.util.Map;

/**
 * Tool contract owned by the new conversation core; it has no Android/legacy dispatcher dependency.
 */
public interface CoreTool {
  enum ExecutionMode { PARALLEL, SEQUENTIAL }
  enum UnattendedAccess { EXCLUDED, READ, WRITE }
  enum AccessClassification { READ, WRITE }
  enum PersistenceEffect { NONE, MEMORY }

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

  /** Generic effect metadata used by post-turn evidence checks; it does not depend on tool names. */
  default AccessClassification accessClassification() { return AccessClassification.READ; }
  default PersistenceEffect persistenceEffect() { return PersistenceEffect.NONE; }

  /**
   * Tools keep ReAct's original call order unless their implementation explicitly opts into
   * parallel execution after establishing that calls are independent.
   */
  default ExecutionMode executionMode() {
    return ExecutionMode.SEQUENTIAL;
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
}
