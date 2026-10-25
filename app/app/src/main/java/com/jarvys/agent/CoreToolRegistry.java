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
  public interface InvocationObserver {
    void beforeDispatch(CoreTool tool, String callId, Map<String, Object> arguments);
    void afterDispatch(CoreTool tool, String callId, CoreToolResult result, Throwable failure);
  }
  private final Runnable invocationGuard;
  private final InvocationObserver invocationObserver;
  private final Map<String, CoreTool> tools;

  public CoreToolRegistry(Collection<? extends CoreTool> tools) {
    this(tools, null, null);
  }

  private CoreToolRegistry(Collection<? extends CoreTool> tools, Runnable invocationGuard) {
    this(tools, invocationGuard, null);
  }

  private CoreToolRegistry(Collection<? extends CoreTool> tools, Runnable invocationGuard, InvocationObserver invocationObserver) {
    this.invocationGuard = invocationGuard;
    this.invocationObserver = invocationObserver;
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
    return new CoreToolRegistry(WorkspaceTools.forDelegatedAgent(delegable), this.invocationGuard, this.invocationObserver)
        .rebindDelegation();
  }

  private CoreToolRegistry rebindDelegation() {
    List<CoreTool> scoped = new ArrayList<>();
    for (CoreTool tool : this.tools.values()) {
      scoped.add(
          tool instanceof DelegateSubtaskTool ? ((DelegateSubtaskTool) tool).forScope(this) : tool);
    }
    return new CoreToolRegistry(scoped, this.invocationGuard, this.invocationObserver);
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
    return new CoreToolRegistry(this.tools.values(), combined, this.invocationObserver).rebindDelegation();
  }

  public CoreToolRegistry withInvocationObserver(InvocationObserver observer) {
    return new CoreToolRegistry(this.tools.values(), this.invocationGuard, observer).rebindDelegation();
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
    return new CoreToolRegistry(handlers, this.invocationGuard, this.invocationObserver).rebindDelegation();
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

  public CoreTool tool(String name) {
    return this.tools.get(name);
  }

  CoreToolRegistry withoutAttachments() {
    return new CoreToolRegistry(
        WorkspaceTools.withoutAttachments(new ArrayList<>(this.tools.values())),
        this.invocationGuard, this.invocationObserver);
  }

  public List<String> connectorToolNames() {
    List<String> names = new ArrayList<>();
    for (Map.Entry<String, CoreTool> entry : this.tools.entrySet()) {
      if (entry.getValue().isConnectorTool()) {
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
    return new CoreToolRegistry(combined, this.invocationGuard, this.invocationObserver);
  }

  public CoreToolRegistry forRequester(String requester) {
    return forRequester(requester, null);
  }

  public CoreToolRegistry forRequester(String requester, String requesterColorKey) {
    List<CoreTool> requested = new ArrayList<>();
    for (CoreTool tool : this.tools.values()) {
      if (tool instanceof CoreConnectorTool) {
        requested.add(((CoreConnectorTool) tool).withRequester(requester, requesterColorKey));
      } else if (tool instanceof PolicyGatedTool) {
        requested.add(((PolicyGatedTool) tool).withRequester(requester, requesterColorKey));
      } else {
        requested.add(tool);
      }
    }
    return new CoreToolRegistry(requested, this.invocationGuard, this.invocationObserver).rebindDelegation();
  }

  public String displayName(String name) {
    CoreTool tool = this.tools.get(name);
    if (tool instanceof PolicyGatedTool) {
      return ((PolicyGatedTool) tool).policyDisplayName();
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
    if (tool instanceof PolicyGatedTool) {
      return ((PolicyGatedTool) tool).policyReflectionSource();
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
    return invoke(name, arguments, token, null, null);
  }

  public CoreToolResult invoke(
      String name,
      Map<String, Object> arguments,
      CancellationToken token,
      CoreTool.ProgressListener progress) {
    return invoke(name, arguments, token, progress, null);
  }

  public CoreToolResult invoke(String name, Map<String, Object> arguments, CancellationToken token,
      CoreTool.ProgressListener progress, String callId) {
    CoreTool tool = this.tools.get(name);
    if (tool == null) {
      return CoreToolResult.failure("Tool is not available in this agent scope: " + name);
    }
    token.throwIfCancelled();
    try {
      if (this.invocationGuard != null) {
        this.invocationGuard.run();
      }
      Map<String, Object> safeArguments = arguments == null ? Collections.emptyMap() : arguments;
      if (this.invocationObserver != null && tool.effectClass(safeArguments) == CoreTool.EffectClass.WRITE
          && tool.persistenceEffect() != CoreTool.PersistenceEffect.MEMORY)
        this.invocationObserver.beforeDispatch(tool, callId, safeArguments);
      CoreToolResult result = progress == null ? tool.execute(safeArguments, token)
          : tool.execute(safeArguments, token, progress);
      if (this.invocationObserver != null && tool.effectClass(safeArguments) == CoreTool.EffectClass.WRITE
          && tool.persistenceEffect() != CoreTool.PersistenceEffect.MEMORY)
        this.invocationObserver.afterDispatch(tool, callId, result, null);
      return result;
    } catch (CancellationException cancelled) {
      if (this.invocationObserver != null && tool.effectClass(arguments == null ? Collections.emptyMap() : arguments) == CoreTool.EffectClass.WRITE
          && tool.persistenceEffect() != CoreTool.PersistenceEffect.MEMORY)
        this.invocationObserver.afterDispatch(tool, callId, null, cancelled);
      throw cancelled;
    } catch (RuntimeException failure) {
      if (this.invocationObserver != null && tool.effectClass(arguments == null ? Collections.emptyMap() : arguments) == CoreTool.EffectClass.WRITE
          && tool.persistenceEffect() != CoreTool.PersistenceEffect.MEMORY)
        this.invocationObserver.afterDispatch(tool, callId, null, failure);
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
    return new CoreToolRegistry(selected, this.invocationGuard, this.invocationObserver);
  }
}
