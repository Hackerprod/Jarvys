package com.jarvys.agent;

import com.jarvys.agent.coding.CodingProjectTools;
import com.jarvys.agent.coding.ProjectMutationService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class CodingWorkspaceRouter {
  private CoreToolRegistry registry;
  private final int responseBudgetChars;
  private final WorkspaceStore workspace;

  CodingWorkspaceRouter(WorkspaceStore workspace, int responseBudgetChars) {
    this.workspace = workspace;
    this.responseBudgetChars = responseBudgetChars;
  }

  CoreToolResult invoke(String name, Map<String, Object> arguments, CancellationToken token) {
    Object rawPath = arguments.get("path");
    boolean receipt = arguments.containsKey("result_cursor");
    if (!receipt
        && (!(rawPath instanceof String)
            || !this.workspace.isCodingProjectPath((String) rawPath))) {
      return null;
    }
    Map<String, Object> scoped = new LinkedHashMap<>(arguments);
    if (!receipt) {
      String path = (String) rawPath;
      String relative =
          (path.equals("/project") || path.equals("/project/"))
              ? "."
              : path.substring("/project/".length());
      scoped.put("path", relative);
    }
    try {
      synchronized (this) {
        if (this.registry == null) {
          this.registry =
              new CoreToolRegistry(
                  CodingProjectTools.create(
                      this.workspace.codingProjectScope(),
                      new ProjectMutationService(),
                      this.workspace.codingProjectOwner(),
                      this.responseBudgetChars));
        }
      }
      return this.registry.invoke(name, scoped, token);
    } catch (IOException | IllegalArgumentException unavailable) {
      return CoreToolResult.failure("Coding project: " + unavailable.getMessage());
    }
  }
}
