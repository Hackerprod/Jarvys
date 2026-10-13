package com.jarvys.agent;

import java.util.List;
import java.util.Map;

/** Validates the structural part of a tool's declared JSON schema before execution starts. */
final class ToolArgumentValidator {
    private ToolArgumentValidator() { }

    static String validate(Map<String, Object> arguments, Map<String, Object> schema) {
        if (arguments == null) return "Tool arguments must be an object";
        return validateValue(arguments, schema, "arguments");
    }

    private static String validateValue(Object value, Map<String, Object> schema, String path) {
        if (schema == null) return null;
        Object anyOfValue = schema.get("anyOf");
        if (anyOfValue instanceof List<?>) {
            for (Object option : (List<?>) anyOfValue) {
                if (option instanceof Map<?, ?>
                        && validateValue(value, castMap(option), path) == null) return null;
            }
            return "Argument '" + path + "' does not match any allowed type";
        }

        String type = schema.get("type") instanceof String ? (String) schema.get("type") : null;
        if (type != null && !matchesType(value, type)) {
            return "Argument '" + path + "' must be " + type;
        }

        if (value instanceof Map<?, ?>) {
            Map<String, Object> object = castMap(value);
            Object requiredValue = schema.get("required");
            if (requiredValue instanceof List<?>) {
                for (Object field : (List<?>) requiredValue) {
                    if (field instanceof String && !object.containsKey(field)) {
                        return "Missing required argument '" + field + "'";
                    }
                }
            }
            Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?>
                    ? castMap(schema.get("properties")) : null;
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                Object propertySchema = properties == null ? null : properties.get(entry.getKey());
                if (propertySchema == null && Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    return "Unexpected argument '" + entry.getKey() + "'";
                }
                if (propertySchema instanceof Map<?, ?>) {
                    String error = validateValue(entry.getValue(), castMap(propertySchema), entry.getKey());
                    if (error != null) return error;
                }
            }
        } else if (value instanceof List<?> && schema.get("items") instanceof Map<?, ?>) {
            int index = 0;
            for (Object item : (List<?>) value) {
                String error = validateValue(item, castMap(schema.get("items")), path + "[" + index + "]");
                if (error != null) return error;
                index++;
            }
        }
        return null;
    }

    private static boolean matchesType(Object value, String type) {
        switch (type) {
            case "object": return value instanceof Map<?, ?>;
            case "array": return value instanceof List<?>;
            case "string": return value instanceof String;
            case "boolean": return value instanceof Boolean;
            case "integer": return value instanceof Number && isWhole((Number) value);
            case "number": return value instanceof Number;
            case "null": return value == null;
            default: return true;
        }
    }

    private static boolean isWhole(Number number) {
        double value = number.doubleValue();
        return !Double.isNaN(value) && !Double.isInfinite(value) && value == Math.rint(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
