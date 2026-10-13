package com.jarvys.agent;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.File;
import com.jarvys.agent.crew.CrewTools;
import org.junit.Test;

public class ToolArgumentValidatorTest {
    @Test public void coercesNullOptionalFieldsToTheirDeclaredPrimitiveDefaults() {
        Map<String, Object> schema = objectSchema(properties(
                "label", type("string"),
                "offset", type("integer"),
                "enabled", type("boolean")));
        Map<String, Object> arguments = arguments("label", null, "offset", null, "enabled", null);

        ToolArgumentValidator.Result result = ToolArgumentValidator.coerceAndValidate(arguments, schema);

        assertNull(result.error);
        assertEquals("", result.arguments.get("label"));
        assertEquals(0, ((Number) result.arguments.get("offset")).intValue());
        assertEquals(Boolean.FALSE, result.arguments.get("enabled"));
    }

    @Test public void coercesNumericAndBooleanStrings() {
        Map<String, Object> schema = objectSchema(properties(
                "count", type("integer"),
                "enabled", type("boolean"),
                "ratio", type("number"),
                "label", type("string")));
        Map<String, Object> arguments = arguments("count", "5", "enabled", "true", "ratio", "2.5",
                "label", 5.0d);

        ToolArgumentValidator.Result result = ToolArgumentValidator.coerceAndValidate(arguments, schema);

        assertNull(result.error);
        assertEquals(5, ((Number) result.arguments.get("count")).intValue());
        assertEquals(Boolean.TRUE, result.arguments.get("enabled"));
        assertEquals(2.5, ((Number) result.arguments.get("ratio")).doubleValue(), 0.0);
        assertEquals("5", result.arguments.get("label"));
    }

    @Test public void parsesJsonTextForArrayAndObjectProperties() {
        Map<String, Object> nestedSchema = objectSchema(properties("limit", type("integer")));
        Map<String, Object> arraySchema = type("array");
        arraySchema.put("items", type("string"));
        Map<String, Object> schema = objectSchema(properties("tags", arraySchema, "settings", nestedSchema));
        Map<String, Object> arguments = arguments("tags", "[\"one\",\"two\"]",
                "settings", "{\"limit\":\"7\"}");

        ToolArgumentValidator.Result result = ToolArgumentValidator.coerceAndValidate(arguments, schema);

        assertNull(result.error);
        assertEquals(Arrays.asList("one", "two"), result.arguments.get("tags"));
        assertTrue(result.arguments.get("settings") instanceof Map<?, ?>);
        assertEquals(7, ((Number) ((Map<?, ?>) result.arguments.get("settings")).get("limit")).intValue());
    }

    @Test public void triesCoercionAcrossAnyOfBranches() {
        Map<String, Object> union = new LinkedHashMap<>();
        union.put("anyOf", Arrays.asList(type("integer"), type("boolean")));
        Map<String, Object> schema = objectSchema(properties("value", union));

        ToolArgumentValidator.Result result = ToolArgumentValidator.coerceAndValidate(
                arguments("value", "true"), schema);

        assertNull(result.error);
        assertEquals(Boolean.TRUE, result.arguments.get("value"));
    }

    @Test public void parsesJsonTextForArrayOrObjectAnyOfBranches() {
        Map<String, Object> union = new LinkedHashMap<>();
        union.put("anyOf", Arrays.asList(type("array"), type("object")));
        Map<String, Object> schema = objectSchema(properties("value", union));

        ToolArgumentValidator.Result arrayResult = ToolArgumentValidator.coerceAndValidate(
                arguments("value", "[\"one\"]"), schema);
        ToolArgumentValidator.Result objectResult = ToolArgumentValidator.coerceAndValidate(
                arguments("value", "{\"enabled\":true}"), schema);

        assertNull(arrayResult.error);
        assertEquals(Collections.singletonList("one"), arrayResult.arguments.get("value"));
        assertNull(objectResult.error);
        assertTrue(objectResult.arguments.get("value") instanceof Map<?, ?>);
    }

    @Test public void stillRejectsMissingRequiredAndIrreconcilableValues() {
        Map<String, Object> schema = objectSchema(properties("count", type("integer")));
        schema.put("required", Collections.singletonList("count"));

        ToolArgumentValidator.Result missing = ToolArgumentValidator.coerceAndValidate(
                Collections.emptyMap(), schema);
        ToolArgumentValidator.Result invalid = ToolArgumentValidator.coerceAndValidate(
                arguments("count", "not-an-integer"), schema);

        assertNotNull(missing.error);
        assertTrue(missing.error.contains("count"));
        assertNotNull(invalid.error);
        assertTrue(invalid.error.contains("count"));
    }

    @Test public void stillRejectsAdditionalPropertiesWhenDisabled() {
        Map<String, Object> schema = objectSchema(properties("count", type("integer")));

        ToolArgumentValidator.Result result = ToolArgumentValidator.coerceAndValidate(
                arguments("count", "3", "unexpected", "value"), schema);

        assertNotNull(result.error);
        assertTrue(result.error.contains("unexpected"));
    }

    @Test public void mainToolSchemasAcceptExistingOptionalNullAndTextArgumentForms() {
        WorkspaceStore workspace = new WorkspaceStore(new File(System.getProperty("java.io.tmpdir"), "jarvys-schema-validation"),
                WorkspaceStore.projectIdForSession("tool-schema-validation"));
        ToolSpec workspaceRead = WorkspaceTools.create(workspace).stream()
                .map(CoreTool::declaration).filter(spec -> "read".equals(spec.name)).findFirst().get();
        ToolArgumentValidator.Result workspaceArguments = ToolArgumentValidator.coerceAndValidate(
                arguments("path", "notes.txt", "offset", null, "max_chars", null), workspaceRead.jsonSchema());
        assertNull(workspaceArguments.error);
        assertEquals(0, ((Number) workspaceArguments.arguments.get("offset")).intValue());
        assertEquals(0, ((Number) workspaceArguments.arguments.get("max_chars")).intValue());
        assertEquals(12 * 1024, WorkspaceTools.integerArgument(workspaceArguments.arguments, "max_chars", 12 * 1024));

        ToolSpec registryRead = new ToolRegistry().get("read_note");
        ToolArgumentValidator.Result registryArguments = ToolArgumentValidator.coerceAndValidate(
                arguments("key", "note", "start_line", null, "end_line", "12"), registryRead.jsonSchema());
        assertNull(registryArguments.error);
        assertEquals(0, ((Number) registryArguments.arguments.get("start_line")).intValue());
        assertEquals(12, ((Number) registryArguments.arguments.get("end_line")).intValue());
        assertTrue(ToolArgumentValidator.wasNumericNull(registryArguments.arguments, "start_line"));

        ToolSpec crewWait = CrewTools.captain(null, null).stream()
                .map(CoreTool::declaration).filter(spec -> "crew_wait".equals(spec.name)).findFirst().get();
        ToolArgumentValidator.Result crewArguments = ToolArgumentValidator.coerceAndValidate(
                arguments("mode", null), crewWait.jsonSchema());
        assertNull(crewArguments.error);
        assertEquals("", crewArguments.arguments.get("mode"));
        ToolArgumentValidator.Result crewUnexpected = ToolArgumentValidator.coerceAndValidate(
                arguments("unknown", true), crewWait.jsonSchema());
        assertNotNull(crewUnexpected.error);
    }

    private static Map<String, Object> type(String name) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", name);
        return schema;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        Map<String, Object> schema = type("object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Object... pairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            properties.put((String) pairs[i], (Map<String, Object>) pairs[i + 1]);
        }
        return properties;
    }

    private static Map<String, Object> arguments(Object... pairs) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            arguments.put((String) pairs[i], pairs[i + 1]);
        }
        return arguments;
    }
}
