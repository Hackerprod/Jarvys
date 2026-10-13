package com.jarvys.agent;

import java.util.Map;

/**
 * Tool contract owned by the new conversation core; it has no Android/legacy dispatcher dependency.
 */
public interface CoreTool {
  enum ExecutionMode { PARALLEL, SEQUENTIAL }

  interface ProgressListener {
    void onProgress(String message);
  }

  default boolean canDelegate() {
    return true;
  }

  /** Independent tools run concurrently; shared state or interactive effects opt into ordering. */
  default ExecutionMode executionMode() {
    return ExecutionMode.PARALLEL;
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
