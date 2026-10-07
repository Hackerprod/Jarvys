package com.jarvys.agent;

import com.jarvys.agent.connectors.ConnectorDefinition;
import com.jarvys.agent.connectors.ConnectorOperation;
import com.jarvys.agent.connectors.ConnectorRegistry;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapter from the native connector contract into the agent's schema-based CoreTool interface. */
public final class CoreConnectorTool implements CoreTool {
    private final ConnectorRegistry registry;
    private final ConnectorDefinition definition;
    private final ConnectorOperation operation;
    private final String requester;
    private final String requesterColorKey;
    private final ToolSpec declaration;

    public CoreConnectorTool(ConnectorRegistry registry, ConnectorDefinition definition, ConnectorOperation operation) {
        this(registry, definition, operation, null, null);
    }

    private CoreConnectorTool(ConnectorRegistry registry, ConnectorDefinition definition, ConnectorOperation operation,
                              String requester, String requesterColorKey) {
        this.registry = registry;
        this.definition = definition;
        this.operation = operation;
        this.requester = requester;
        this.requesterColorKey = requesterColorKey;
        this.declaration = new ToolSpec(
                toolName(definition.getId(), operation.getName()), "device_connector",
                operation.getDescription(), "connector:" + definition.getId(), ToolSpec.Status.IMPLEMENTED,
                Collections.emptyMap(), Collections.emptyList(), jsonMap(operation.getInputSchema()));
    }

    @Override public ToolSpec declaration() { return declaration; }
    String groupName() { return "connector:" + definition.getId(); }
    String displayName() { return operation.getDisplayLabel(); }
    String usageNote() { return definition.usageNote(); }
    String connectorId() { return definition.getId(); }
    ConnectorRegistry connectorRegistry() { return registry; }
    CoreConnectorTool withRequester(String name, String colorKey) {
        return new CoreConnectorTool(registry, definition, operation, name, colorKey);
    }

    @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
        JSONObject result = registry.invoke(definition, operation,
                new JSONObject(arguments == null ? Collections.emptyMap() : arguments), token, requester, requesterColorKey);
        return CoreToolResult.success(result.toString());
    }

    public static String toolName(String connectorId, String operationName) {
        if ("sms_draft".equals(connectorId) && "send_sms".equals(operationName)) return "send_sms";
        String raw = (connectorId + "_" + operationName).replaceAll("[^A-Za-z0-9_]", "_")
                .replaceAll("_+", "_").toLowerCase(java.util.Locale.ROOT);
        if (raw.isEmpty() || !Character.isLetter(raw.charAt(0))) raw = "connector_" + raw;
        if (raw.length() > 64) throw new IllegalArgumentException("Connector tool names must be at most 64 characters");
        return raw;
    }

    private static Map<String, Object> jsonMap(JSONObject object) {
        Map<String, Object> result = new LinkedHashMap<>();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, jsonValue(object.opt(key)));
        }
        return result;
    }

    private static Object jsonValue(Object value) {
        if (value instanceof JSONObject) return jsonMap((JSONObject) value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> result = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) result.add(jsonValue(array.opt(i)));
            return result;
        }
        return value == JSONObject.NULL ? null : value;
    }
}
