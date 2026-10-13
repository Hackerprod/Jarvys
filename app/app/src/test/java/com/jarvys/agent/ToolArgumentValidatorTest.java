package com.jarvys.agent;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import com.jarvys.agent.coding.CodingProjectTools;
import com.jarvys.agent.coding.ProjectMutationService;
import com.jarvys.agent.coding.ProjectScope;
import com.jarvys.agent.crew.CrewTools;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public class ToolArgumentValidatorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void omitsNullOptionalFieldsAndKeepsOpenClawCoercionForRequiredFields() {
        Map<String, Object> schema = objectSchema(properties(
                "label", type("string"),
                "offset", type("integer"),
                "enabled", type("boolean"),
                "required_label", type("string"),
                "required_offset", type("integer")));
        schema.put("required", Arrays.asList("required_label", "required_offset"));
        Map<String, Object> arguments = arguments("label", null, "offset", null, "enabled", null,
                "required_label", null, "required_offset", null);

        ToolArgumentValidator.Result result = ToolArgumentValidator.coerceAndValidate(arguments, schema);

        assertNull(result.error);
        assertFalse(result.arguments.containsKey("label"));
        assertFalse(result.arguments.containsKey("offset"));
        assertFalse(result.arguments.containsKey("enabled"));
        assertEquals("", result.arguments.get("required_label"));
        assertEquals(0, ((Number) result.arguments.get("required_offset")).intValue());
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

    @Test public void realWorkspaceAndCodingSchemasOmitOptionalNullsAndKeepDefaultBehavior() throws Exception {
        String session = "tool-schema-validation";
        File appFiles = temporary.newFolder("app-files");
        File workspacesRoot = new File(new File(appFiles, "jarvys"), "workspaces");
        String projectId = WorkspaceStore.projectIdForSession(session);
        WorkspaceStore workspace = new WorkspaceStore(workspacesRoot, projectId, null, null, null, session, true);
        File workspaceRoot = new File(workspacesRoot, projectId);
        assertTrue(workspaceRoot.mkdirs() || workspaceRoot.isDirectory());
        Files.write(new File(workspaceRoot, "notes.txt").toPath(), "workspace note".getBytes(StandardCharsets.UTF_8));
        List<CoreTool> workspaceTools = WorkspaceTools.createWithSearchForTests(workspace, null);
        CoreTool workspaceReadTool = workspaceTools.stream()
                .filter(tool -> "read".equals(tool.declaration().name)).findFirst().get();
        ToolSpec workspaceRead = workspaceReadTool.declaration();
        ToolArgumentValidator.Result workspaceArguments = ToolArgumentValidator.coerceAndValidate(
                arguments("path", "notes.txt", "offset", null, "max_chars", null, "start_line", null),
                workspaceRead.jsonSchema());
        assertNull(workspaceArguments.error);
        assertFalse(workspaceArguments.arguments.containsKey("offset"));
        assertFalse(workspaceArguments.arguments.containsKey("max_chars"));
        assertFalse(workspaceArguments.arguments.containsKey("start_line"));
        assertEquals(12 * 1024, WorkspaceTools.integerArgument(workspaceArguments.arguments, "max_chars", 12 * 1024));
        assertTrue(workspaceReadTool.execute(workspaceArguments.arguments, CancellationToken.uncancellable()).success);

        CoreTool searchFilesTool = workspaceTools.stream()
                .filter(tool -> "search_files".equals(tool.declaration().name)).findFirst().get();
        ToolArgumentValidator.Result searchFilesArguments = ToolArgumentValidator.coerceAndValidate(
                arguments("query", "note", "zone", null, "limit", null),
                searchFilesTool.declaration().jsonSchema());
        assertNull(searchFilesArguments.error);
        assertFalse(searchFilesArguments.arguments.containsKey("zone"));
        assertFalse(searchFilesArguments.arguments.containsKey("limit"));
        assertTrue(searchFilesTool.execute(searchFilesArguments.arguments, CancellationToken.uncancellable()).success);

        ProjectScope scope = workspace.codingProjectScope();
        Files.write(scope.resolve("sample.txt").toPath(), "coding needle".getBytes(StandardCharsets.UTF_8));
        ToolArgumentValidator.Result routedReadArguments = ToolArgumentValidator.coerceAndValidate(
                arguments("path", "/project/sample.txt", "start_line", null, "end_line", null,
                        "offset", null, "cursor", null), workspaceRead.jsonSchema());
        assertNull(routedReadArguments.error);
        assertFalse(routedReadArguments.arguments.containsKey("start_line"));
        assertTrue(workspaceReadTool.execute(routedReadArguments.arguments, CancellationToken.uncancellable()).success);

        List<CoreTool> codingTools = CodingProjectTools.create(scope, new ProjectMutationService(), "owner", 4096);
        CoreTool codingRead = codingTool(codingTools, "read");
        ToolArgumentValidator.Result codingReadArguments = ToolArgumentValidator.coerceAndValidate(
                arguments("path", "/project/sample.txt", "start_line", null, "end_line", null,
                        "offset", null, "cursor", null), codingRead.declaration().jsonSchema());
        assertNull(codingReadArguments.error);
        assertFalse(codingReadArguments.arguments.containsKey("start_line"));
        assertFalse(codingReadArguments.arguments.containsKey("end_line"));
        assertTrue(codingRead.execute(codingReadArguments.arguments, CancellationToken.uncancellable()).success);

        CoreTool codingGlob = codingTool(codingTools, "coding_glob");
        ToolArgumentValidator.Result globArguments = ToolArgumentValidator.coerceAndValidate(
                arguments("glob", "*.txt", "include_ignored", null, "cursor", null),
                codingGlob.declaration().jsonSchema());
        assertNull(globArguments.error);
        assertFalse(globArguments.arguments.containsKey("include_ignored"));
        assertTrue(codingGlob.execute(globArguments.arguments, CancellationToken.uncancellable()).success);

        CoreTool codingGrep = codingTool(codingTools, "coding_grep");
        ToolArgumentValidator.Result grepArguments = ToolArgumentValidator.coerceAndValidate(
                arguments("query", "needle", "regex", null, "include_ignored", null, "cursor", null),
                codingGrep.declaration().jsonSchema());
        assertNull(grepArguments.error);
        assertFalse(grepArguments.arguments.containsKey("regex"));
        assertFalse(grepArguments.arguments.containsKey("include_ignored"));
        assertTrue(codingGrep.execute(grepArguments.arguments, CancellationToken.uncancellable()).success);

        ToolSpec registryRead = new ToolRegistry().get("read_note");
        ToolArgumentValidator.Result registryArguments = ToolArgumentValidator.coerceAndValidate(
                arguments("key", "note", "start_line", null, "end_line", "12"), registryRead.jsonSchema());
        assertNull(registryArguments.error);
        assertFalse(registryArguments.arguments.containsKey("start_line"));
        assertEquals(12, ((Number) registryArguments.arguments.get("end_line")).intValue());

        ToolSpec crewWait = CrewTools.captain(null, null).stream()
                .map(CoreTool::declaration).filter(spec -> "crew_wait".equals(spec.name)).findFirst().get();
        ToolArgumentValidator.Result crewArguments = ToolArgumentValidator.coerceAndValidate(
                arguments("mode", null, "ids", null), crewWait.jsonSchema());
        assertNull(crewArguments.error);
        assertFalse(crewArguments.arguments.containsKey("mode"));
        assertFalse(crewArguments.arguments.containsKey("ids"));
        ToolArgumentValidator.Result crewUnexpected = ToolArgumentValidator.coerceAndValidate(
                arguments("unknown", true), crewWait.jsonSchema());
        assertNotNull(crewUnexpected.error);
    }

    private static CoreTool codingTool(List<CoreTool> tools, String name) {
        return tools.stream().filter(tool -> name.equals(tool.declaration().name)).findFirst().get();
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
