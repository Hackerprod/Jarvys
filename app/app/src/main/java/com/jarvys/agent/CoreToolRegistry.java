package com.jarvys.agent;

import com.jarvys.agent.connectors.ConnectorRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

public final class CoreToolRegistry {
  private final Runnable invocationGuard;
  private final Map<String, CoreTool> tools;

  public CoreToolRegistry(Collection<? extends CoreTool> tools) {
    this(tools, null);
  }

  private CoreToolRegistry(Collection<? extends CoreTool> tools, Runnable invocationGuard) {
    this.invocationGuard = invocationGuard;
    Map<String, CoreTool> indexed = new LinkedHashMap<>();
    for (CoreTool tool : tools) {
      String name = tool.declaration().name;
      if (indexed.put(name, tool) != null) {
        throw new IllegalArgumentException("Duplicate core tool: " + name);
      }
    }
    this.tools = Collections.unmodifiableMap(indexed);
  }

  CoreTool get(String name) {
    return this.tools.get(name);
  }

  List<CoreTool> handlers() {
    return new ArrayList<>(this.tools.values());
  }

  CoreToolRegistry forDelegatedAgent() {
    List<CoreTool> delegable = new ArrayList<>();
    for (CoreTool tool : handlers()) {
      if (tool.canDelegate()) {
        delegable.add(tool);
      }
    }
    return new CoreToolRegistry(WorkspaceTools.forDelegatedAgent(delegable), this.invocationGuard)
        .rebindDelegation();
  }

  private CoreToolRegistry rebindDelegation() {
    List<CoreTool> scoped = new ArrayList<>();
    for (CoreTool tool : this.tools.values()) {
      scoped.add(
          tool instanceof DelegateSubtaskTool ? ((DelegateSubtaskTool) tool).forScope(this) : tool);
    }
    return new CoreToolRegistry(scoped, this.invocationGuard);
  }

  CoreToolRegistry withInvocationGuard(final Runnable guard) {
    if (guard == null) {
      return this;
    }
    Runnable combined =
        this.invocationGuard == null
            ? guard
            : () -> {
              this.invocationGuard.run();
              guard.run();
            };
    return new CoreToolRegistry(this.tools.values(), combined).rebindDelegation();
  }

  void runCombinedGuard(Runnable guard) {
    this.invocationGuard.run();
    guard.run();
  }

  CoreToolRegistry replaceSelectedHandlers(Collection<? extends CoreTool> handlers) {
    for (CoreTool tool : handlers) {
      if (!this.tools.containsKey(tool.declaration().name)) {
        throw new IllegalArgumentException("Replacement exceeds the selected capability scope");
      }
    }
    return new CoreToolRegistry(handlers, this.invocationGuard).rebindDelegation();
  }

  public List<ToolSpec> declarations() {
    List<ToolSpec> result = new ArrayList<>();
    for (CoreTool tool : this.tools.values()) {
      result.add(tool.declaration());
    }
    return Collections.unmodifiableList(result);
  }

  public List<String> names() {
    return Collections.unmodifiableList(new ArrayList<>(this.tools.keySet()));
  }

  CoreToolRegistry withoutAttachments() {
    return new CoreToolRegistry(
        WorkspaceTools.withoutAttachments(new ArrayList<>(this.tools.values())),
        this.invocationGuard);
  }

  public List<String> connectorToolNames() {
    List<String> names = new ArrayList<>();
    for (Map.Entry<String, CoreTool> entry : this.tools.entrySet()) {
      if (entry.getValue() instanceof CoreConnectorTool) {
        names.add(entry.getKey());
      }
    }
    return Collections.unmodifiableList(names);
  }

  /** Native Android connectors only; remote service connectors retain their separate scope. */
  public List<String> onDeviceConnectorToolNames() {
    List<String> names = new ArrayList<>();
    for (Map.Entry<String, CoreTool> entry : this.tools.entrySet()) {
      if (entry.getValue() instanceof CoreConnectorTool
          && ((CoreConnectorTool) entry.getValue()).isOnDevice()) names.add(entry.getKey());
    }
    return Collections.unmodifiableList(names);
  }

  public List<ConnectorRegistry> connectorRegistries() {
    List<ConnectorRegistry> registries = new ArrayList<>();
    for (CoreTool tool : this.tools.values()) {
      if (tool instanceof CoreConnectorTool) {
        ConnectorRegistry registry = ((CoreConnectorTool) tool).connectorRegistry();
        if (!registries.contains(registry)) {
          registries.add(registry);
        }
      }
    }
    return Collections.unmodifiableList(registries);
  }

  public CoreToolRegistry with(Collection<? extends CoreTool> additional) {
    List<CoreTool> combined = new ArrayList<>(this.tools.values());
    combined.addAll(additional);
    return new CoreToolRegistry(combined, this.invocationGuard);
  }

  public CoreToolRegistry forRequester(String requester) {
    return forRequester(requester, null);
  }

  public CoreToolRegistry forRequester(String requester, String requesterColorKey) {
    List<CoreTool> requested = new ArrayList<>();
    for (CoreTool tool : this.tools.values()) {
      if (tool instanceof CoreConnectorTool) {
        requested.add(((CoreConnectorTool) tool).withRequester(requester, requesterColorKey));
      } else if (tool instanceof CoreMcpTool) {
        requested.add(((CoreMcpTool) tool).withRequester(requester, requesterColorKey));
      } else {
        requested.add(tool);
      }
    }
    return new CoreToolRegistry(requested, this.invocationGuard).rebindDelegation();
  }

  public String displayName(String name) {
    CoreTool tool = this.tools.get(name);
    if (tool instanceof CoreMcpTool) {
      return ((CoreMcpTool) tool).displayName();
    }
    return tool instanceof CoreConnectorTool
        ? ((CoreConnectorTool) tool).displayName()
        : humanizeToolName(name);
  }

  public String reflectionSource(String name) {
    CoreTool tool = this.tools.get(name);
    if (tool == null) {
      return "workspace";
    }
    if (tool instanceof CoreMcpTool) {
      return "mcp";
    }
    if (tool instanceof CoreConnectorTool) {
      return "connector:" + ((CoreConnectorTool) tool).connectorId();
    }
    if ("web".equals(tool.declaration().category)) {
      return "web";
    }
    return tool instanceof DelegateSubtaskTool ? "delegate" : "workspace";
  }

  public String auditDetail(String name, Map<String, Object> arguments) {
    CoreTool tool = tools.get(name);
    if (tool == null) return null;
    try {
      return tool.auditDetail(arguments == null ? Collections.emptyMap() : arguments);
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  public static String humanizeToolName(String name) {
    if ("ls".equalsIgnoreCase(name)) {
      return "List";
    }
    StringBuilder displayName = new StringBuilder(name.length());
    boolean capitalizeNext = true;
    for (int index = 0; index < name.length(); index++) {
      char character = name.charAt(index);
      if (character == '_') {
        displayName.append(' ');
        capitalizeNext = true;
      } else if (capitalizeNext) {
        displayName.append(Character.toTitleCase(character));
        capitalizeNext = false;
      } else {
        displayName.append(character);
      }
    }
    return displayName.toString().trim();
  }

  public CoreToolResult invoke(
      String name, Map<String, Object> arguments, CancellationToken token) {
    return invoke(name, arguments, token, null);
  }

  public CoreToolResult invoke(
      String name,
      Map<String, Object> arguments,
      CancellationToken token,
      CoreTool.ProgressListener progress) {
    CoreTool tool = this.tools.get(name);
    if (tool == null) {
      return CoreToolResult.failure("Tool is not available in this agent scope: " + name);
    }
    token.throwIfCancelled();
    try {
      if (this.invocationGuard != null) {
        this.invocationGuard.run();
      }
      if (progress == null) {
        return tool.execute(arguments == null ? Collections.emptyMap() : arguments, token);
      }
      return tool.execute(arguments == null ? Collections.emptyMap() : arguments, token, progress);
    } catch (CancellationException cancelled) {
      throw cancelled;
    } catch (RuntimeException failure) {
      String detail =
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
      return CoreToolResult.failure(detail);
    }
  }

  public CoreToolRegistry subset(Collection<String> requestedNames) {
    return rawSubset(requestedNames).rebindDelegation();
  }

  CoreToolRegistry rawSubset(Collection<String> requestedNames) {
    List<CoreTool> selected = new ArrayList<>();
    for (String name : requestedNames) {
      CoreTool tool = this.tools.get(name);
      if (tool != null && !selected.contains(tool)) {
        selected.add(tool);
      }
    }
    return new CoreToolRegistry(selected, this.invocationGuard);
  }
}
