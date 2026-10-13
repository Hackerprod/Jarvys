package com.jarvys.agent;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Coerces model arguments against a tool's JSON schema, then validates their structure. */
final class ToolArgumentValidator {
    static final class Result {
        final Map<String, Object> arguments;
        final String error;

        private Result(Map<String, Object> arguments, String error) {
            this.arguments = arguments;
            this.error = error;
        }
    }

    private ToolArgumentValidator() { }

    static Result coerceAndValidate(Map<String, Object> arguments, Map<String, Object> schema) {
        if (arguments == null) return new Result(new LinkedHashMap<>(), "Tool arguments must be an object");
        Object coerced = coerceValue(arguments, schema);
        if (!(coerced instanceof Map<?, ?>)) {
            return new Result(new LinkedHashMap<>(), "Tool arguments must be an object");
        }
        Map<String, Object> result = castMap(coerced);
        return new Result(result, validateValue(result, schema, "arguments"));
    }

    /** Retained for callers that only need the validation outcome. */
    static String validate(Map<String, Object> arguments, Map<String, Object> schema) {
        return coerceAndValidate(arguments, schema).error;
    }

    private static Object coerceValue(Object value, Map<String, Object> schema) {
        if (schema == null) return copyValue(value);

        Object valueToCoerce = value;
        Object anyOfValue = schema.get("anyOf");
        if (anyOfValue instanceof List<?>) {
            List<?> options = (List<?>) anyOfValue;
            // Keep already-valid values before trying another union branch.
            boolean alreadyMatches = false;
            for (Object option : options) {
                if (option instanceof Map<?, ?>
                        && validateValue(value, castMap(option), "arguments") == null) {
                    alreadyMatches = true;
                    break;
                }
            }
            if (!alreadyMatches) {
                for (Object option : options) {
                    if (!(option instanceof Map<?, ?>)) continue;
                    Map<String, Object> optionSchema = castMap(option);
                    if ("null".equals(schemaType(optionSchema)) && value != null) continue;
                    Object candidate = coerceValue(value, optionSchema);
                    if (validateValue(candidate, optionSchema, "arguments") == null) {
                        valueToCoerce = candidate;
                        break;
                    }
                }
            }
        }

        Object copied = copyValue(valueToCoerce);
        List<String> types = schemaTypes(schema);
        if (!types.isEmpty() && !matchesAnyType(copied, types)) {
            for (String type : types) {
                if ("null".equals(type) && copied != null && types.size() > 1) continue;
                Object candidate = coercePrimitive(copied, type);
                if (candidate != copied) {
                    copied = candidate;
                    break;
                }
            }
        }

        if (copied instanceof Map<?, ?>) {
            Map<String, Object> object = castMap(copied);
            Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?>
                    ? castMap(schema.get("properties")) : null;
            List<?> required = schema.get("required") instanceof List<?>
                    ? (List<?>) schema.get("required") : java.util.Collections.emptyList();
            if (properties != null) {
                for (Map.Entry<String, Object> property : properties.entrySet()) {
                    String key = property.getKey();
                    if (!object.containsKey(key)) continue;
                    if (object.get(key) == null && !required.contains(key)) {
                        object.remove(key);
                    } else if (property.getValue() instanceof Map<?, ?>) {
                        object.put(key, coerceValue(object.get(key), castMap(property.getValue())));
                    }
                }
            }
            if (schema.get("additionalProperties") instanceof Map<?, ?>) {
                Map<String, Object> additionalSchema = castMap(schema.get("additionalProperties"));
                for (Map.Entry<String, Object> entry : new ArrayList<>(object.entrySet())) {
                    if ((properties == null || !properties.containsKey(entry.getKey()))) {
                        if (entry.getValue() == null && !required.contains(entry.getKey())) {
                            object.remove(entry.getKey());
                        } else {
                            object.put(entry.getKey(), coerceValue(entry.getValue(), additionalSchema));
                        }
                    }
                }
            }
        } else if (copied instanceof List<?> && schema.get("items") instanceof Map<?, ?>) {
            List<Object> items = castList(copied);
            Map<String, Object> itemSchema = castMap(schema.get("items"));
            for (int index = 0; index < items.size(); index++) {
                items.set(index, coerceValue(items.get(index), itemSchema));
            }
        }
        return copied;
    }

    private static Object coercePrimitive(Object value, String type) {
        switch (type) {
            case "number":
            case "integer": {
                if (value == null) return 0;
                if (value instanceof String) {
                    Number parsed = parseFiniteNumber((String) value);
                    if (parsed != null && (!"integer".equals(type) || isSafeWhole(parsed))) return parsed;
                }
                if (value instanceof Boolean) return (Boolean) value ? 1 : 0;
                return value;
            }
            case "boolean": {
                if (value == null || "false".equals(value) || isNumeric(value, 0)) return false;
                if ("true".equals(value) || isNumeric(value, 1)) return true;
                return value;
            }
            case "string":
                if (value == null) return "";
                if (value instanceof Number) return stringFromNumber((Number) value);
                if (value instanceof Boolean) return String.valueOf(value);
                return value;
            case "array":
                if (value instanceof String) {
                    try {
                        JSONArray parsed = new JSONArray((String) value);
                        return fromJson(parsed);
                    } catch (Exception ignored) { }
                }
                return value;
            case "object":
                if (value instanceof String) {
                    try {
                        JSONObject parsed = new JSONObject((String) value);
                        return fromJson(parsed);
                    } catch (Exception ignored) { }
                }
                return value;
            case "null":
                if ("".equals(value) || isNumeric(value, 0) || Boolean.FALSE.equals(value)) return null;
                return value;
            default:
                return value;
        }
    }

    private static String stringFromNumber(Number value) {
        try {
            BigDecimal decimal = new BigDecimal(value.toString()).stripTrailingZeros();
            if (decimal.signum() == 0) return "0";
            int exponent = decimal.precision() - decimal.scale() - 1;
            if (exponent >= -6 && exponent < 21) return decimal.toPlainString();
            String digits = decimal.unscaledValue().abs().toString();
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            String sign = decimal.signum() < 0 ? "-" : "";
            return sign + mantissa + "e" + (exponent >= 0 ? "+" : "") + exponent;
        } catch (NumberFormatException ignored) {
            return String.valueOf(value);
        }
    }

    private static String validateValue(Object value, Map<String, Object> schema, String path) {
        if (schema == null) return null;
        Object anyOfValue = schema.get("anyOf");
        if (anyOfValue instanceof List<?>) {
            boolean matchesOption = false;
            for (Object option : (List<?>) anyOfValue) {
                if (option instanceof Map<?, ?>
                        && validateValue(value, castMap(option), path) == null) {
                    matchesOption = true;
                    break;
                }
            }
            if (!matchesOption) return "Argument '" + path + "' does not match any allowed type";
        }

        List<String> types = schemaTypes(schema);
        if (!types.isEmpty() && !matchesAnyType(value, types)) {
            String expectedType = types.size() == 1 ? types.get(0) : types.toString();
            return "Argument '" + path + "' must be " + expectedType;
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
                } else if (propertySchema == null && schema.get("additionalProperties") instanceof Map<?, ?>) {
                    String error = validateValue(entry.getValue(), castMap(schema.get("additionalProperties")), entry.getKey());
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

    private static List<String> schemaTypes(Map<String, Object> schema) {
        Object type = schema.get("type");
        if (type instanceof String) return java.util.Collections.singletonList((String) type);
        if (type instanceof List<?>) {
            List<String> types = new ArrayList<>();
            for (Object candidate : (List<?>) type) if (candidate instanceof String) types.add((String) candidate);
            return types;
        }
        return java.util.Collections.emptyList();
    }

    private static String schemaType(Map<String, Object> schema) {
        List<String> types = schemaTypes(schema);
        return types.size() == 1 ? types.get(0) : null;
    }

    private static boolean matchesAnyType(Object value, List<String> types) {
        for (String type : types) if (matchesType(value, type)) return true;
        return false;
    }

    private static boolean matchesType(Object value, String type) {
        switch (type) {
            case "object": return value instanceof Map<?, ?>;
            case "array": return value instanceof List<?>;
            case "string": return value instanceof String;
            case "boolean": return value instanceof Boolean;
            case "integer": return value instanceof Number && isWhole((Number) value);
            case "number": return value instanceof Number && isFinite((Number) value);
            case "null": return value == null;
            default: return true;
        }
    }

    private static Number parseFiniteNumber(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            BigDecimal parsed = new BigDecimal(value.trim());
            double asDouble = parsed.doubleValue();
            if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) return null;
            BigDecimal stripped = parsed.stripTrailingZeros();
            if (stripped.scale() <= 0) {
                try { return stripped.intValueExact(); } catch (ArithmeticException ignored) { }
                try { return stripped.longValueExact(); } catch (ArithmeticException ignored) { }
                return stripped.toBigIntegerExact();
            }
            return asDouble;
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static boolean isSafeWhole(Number number) {
        if (!isWhole(number)) return false;
        double value = number.doubleValue();
        return Math.abs(value) <= 9007199254740991d;
    }

    private static boolean isWhole(Number number) {
        double value = number.doubleValue();
        return isFinite(number) && value == Math.rint(value);
    }

    private static boolean isFinite(Number number) {
        double value = number.doubleValue();
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static boolean isNumeric(Object value, int expected) {
        return value instanceof Number && ((Number) value).doubleValue() == expected;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() instanceof String) copy.put((String) entry.getKey(), copyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?>) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) copy.add(copyValue(item));
            return copy;
        }
        return value;
    }

    private static Object fromJson(Object value) throws Exception {
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) {
            Map<String, Object> result = new LinkedHashMap<>();
            JSONObject object = (JSONObject) value;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                result.put(key, fromJson(object.get(key)));
            }
            return result;
        }
        if (value instanceof JSONArray) {
            List<Object> result = new ArrayList<>();
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) result.add(fromJson(array.get(index)));
            return result;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }
}
