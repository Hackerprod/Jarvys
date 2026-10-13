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

  ToolSpec declaration();

  CoreToolResult execute(Map<String, Object> arguments, CancellationToken token);

  /** Optional live row updates for long operations; existing tools need not implement it. */
  default CoreToolResult execute(
      Map<String, Object> arguments, CancellationToken token, ProgressListener progress) {
    return execute(arguments, token);
  }
}
