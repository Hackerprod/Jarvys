package com.jarvys.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable capability set for one core-agent run. Child agents receive a filtered copy. */
public final class CoreToolRegistry {
    private final Map<String, CoreTool> tools;

    public CoreToolRegistry(Collection<? extends CoreTool> tools) {
        Map<String, CoreTool> indexed = new LinkedHashMap<>();
        for (CoreTool tool : tools) {
            String name = tool.declaration().name;
            if (indexed.put(name, tool) != null) throw new IllegalArgumentException("Duplicate core tool: " + name);
        }
        this.tools = Collections.unmodifiableMap(indexed);
    }

    public List<ToolSpec> declarations() {
        List<ToolSpec> result = new ArrayList<>();
        for (CoreTool tool : tools.values()) result.add(tool.declaration());
        return Collections.unmodifiableList(result);
    }

    public List<String> names() { return Collections.unmodifiableList(new ArrayList<>(tools.keySet())); }

    public List<String> connectorToolNames() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, CoreTool> entry : tools.entrySet()) if (entry.getValue() instanceof CoreConnectorTool) names.add(entry.getKey());
        return Collections.unmodifiableList(names);
    }

    public List<com.jarvys.agent.connectors.ConnectorRegistry> connectorRegistries() {
        List<com.jarvys.agent.connectors.ConnectorRegistry> registries = new ArrayList<>();
        for (CoreTool tool : tools.values()) if (tool instanceof CoreConnectorTool) {
            com.jarvys.agent.connectors.ConnectorRegistry registry = ((CoreConnectorTool) tool).connectorRegistry();
            if (!registries.contains(registry)) registries.add(registry);
        }
        return Collections.unmodifiableList(registries);
    }

    public CoreToolRegistry with(Collection<? extends CoreTool> additional) {
        List<CoreTool> combined = new ArrayList<>(tools.values());
        combined.addAll(additional);
        return new CoreToolRegistry(combined);
    }

    public CoreToolRegistry forRequester(String requester) {
        return forRequester(requester, null);
    }

    public CoreToolRegistry forRequester(String requester, String requesterColorKey) {
        List<CoreTool> requested = new ArrayList<>();
        for (CoreTool tool : tools.values()) {
            if (tool instanceof CoreConnectorTool) requested.add(((CoreConnectorTool) tool).withRequester(requester, requesterColorKey));
            else if (tool instanceof CoreMcpTool) requested.add(((CoreMcpTool) tool).withRequester(requester, requesterColorKey));
            else requested.add(tool);
        }
        return new CoreToolRegistry(requested);
    }

    public String displayName(String name) {
        CoreTool tool = tools.get(name);
        if (tool instanceof CoreMcpTool) return ((CoreMcpTool) tool).displayName();
        if (tool instanceof CoreConnectorTool) return ((CoreConnectorTool) tool).displayName();
        return humanizeToolName(name);
    }

    public String reflectionSource(String name) {
        CoreTool tool = tools.get(name);
        if (tool == null) return "workspace";
        if (tool instanceof CoreMcpTool) return "mcp";
        if (tool instanceof CoreConnectorTool) return "connector:" + ((CoreConnectorTool) tool).connectorId();
        if ("web".equals(tool.declaration().category)) return "web";
        if (tool instanceof DelegateSubtaskTool) return "delegate";
        return "workspace";
    }

    public static String humanizeToolName(String name) {
        if ("ls".equalsIgnoreCase(name)) return "List";
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

    public CoreToolResult invoke(String name, Map<String, Object> arguments, CancellationToken token) {
        return invoke(name, arguments, token, null);
    }

    public CoreToolResult invoke(String name, Map<String, Object> arguments, CancellationToken token,
                                 CoreTool.ProgressListener progress) {
        CoreTool tool = tools.get(name);
        if (tool == null) return CoreToolResult.failure("Tool is not available in this agent scope: " + name);
        token.throwIfCancelled();
        try {
            return progress == null
                    ? tool.execute(arguments == null ? Collections.emptyMap() : arguments, token)
                    : tool.execute(arguments == null ? Collections.emptyMap() : arguments, token, progress);
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failure) {
            String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return CoreToolResult.failure(detail);
        }
    }

    public CoreToolRegistry subset(Collection<String> requestedNames) {
        List<CoreTool> selected = new ArrayList<>();
        for (String name : requestedNames) {
            CoreTool tool = tools.get(name);
            if (tool != null && !selected.contains(tool)) selected.add(tool);
        }
        return new CoreToolRegistry(selected);
    }
}
