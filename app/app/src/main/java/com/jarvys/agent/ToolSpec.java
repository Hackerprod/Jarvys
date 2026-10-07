package com.jarvys.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Auditable model-facing tool definition, including origin and honest implementation status. */
public final class ToolSpec {
    public enum Status { IMPLEMENTED, PARTIAL, UNSUPPORTED }

    public final String name;
    public final String source;
    public final String description;
    public final String category;
    public final Status status;
    public final Map<String, String> propertyTypes;
    public final List<String> required;
    private final Map<String, Object> schemaOverride;

    public ToolSpec(String name, String source, String description, String category,
                    Status status, Map<String, String> propertyTypes, List<String> required) {
        this(name, source, description, category, status, propertyTypes, required, null);
    }

    /** Optional full JSON Schema for dynamic tools; fixed inventory callers continue using typed properties. */
    public ToolSpec(String name, String source, String description, String category,
                    Status status, Map<String, String> propertyTypes, List<String> required,
                    Map<String, ?> schemaOverride) {
        this.name = name;
        this.source = source;
        this.description = description;
        this.category = category;
        this.status = status;
        this.propertyTypes = Collections.unmodifiableMap(new LinkedHashMap<>(propertyTypes));
        this.required = Collections.unmodifiableList(new ArrayList<>(required));
        if (schemaOverride == null) {
            this.schemaOverride = null;
        } else {
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.putAll(schemaOverride);
            this.schemaOverride = Collections.unmodifiableMap(copy);
        }
    }

    public Map<String, Object> jsonSchema() {
        if (schemaOverride != null) return schemaOverride;
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : propertyTypes.entrySet()) {
            String type = entry.getValue();
            if ("integer_or_array".equals(type)) {
                properties.put(entry.getKey(), alternatives("integer", "array"));
            } else if ("string_or_array".equals(type)) {
                properties.put(entry.getKey(), alternatives("string", "array"));
            } else if ("integer_array_or_string".equals(type)) {
                properties.put(entry.getKey(), alternatives("integer", "array", "string"));
            } else if ("normalized_point".equals(type)) {
                properties.put(entry.getKey(), normalizedPointSchema());
            } else if ("swipe_gesture".equals(type)) {
                properties.put(entry.getKey(), swipeGestureSchema());
            } else {
                properties.put(entry.getKey(), Collections.singletonMap("type", type));
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> alternatives(String... types) {
        java.util.List<Map<String, String>> options = new java.util.ArrayList<>();
        for (String type : types) options.add(Collections.singletonMap("type", type));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("anyOf", options);
        return result;
    }

    private static Map<String, Object> normalizedPointSchema() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "integer");
        item.put("minimum", 0);
        item.put("maximum", 1000);
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("type", "array");
        point.put("description", "Exactly [x,y], integer coordinates normalized to 0-1000 relative to the screenshot.");
        point.put("minItems", 2);
        point.put("maxItems", 2);
        point.put("items", item);
        return point;
    }

    private static Map<String, Object> swipeGestureSchema() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "integer");
        item.put("minimum", 0);
        item.put("maximum", 1000);
        Map<String, Object> coordinates = new LinkedHashMap<>();
        coordinates.put("type", "array");
        coordinates.put("description", "Legacy [x1,y1,x2,y2], integer screenshot-normalized coordinates in 0-1000.");
        coordinates.put("minItems", 4);
        coordinates.put("maxItems", 4);
        coordinates.put("items", item);
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(Collections.singletonMap("type", "string"));
        options.add(coordinates);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("anyOf", options);
        return result;
    }
}
