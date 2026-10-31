package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class DatabaseRequestTest {
    interface Checked { void run() throws Exception; }
    static void invalid(Checked work) throws Exception {
        try { work.run(); fail("Expected INVALID_ARGUMENT"); }
        catch (FactoryException e) { assertEquals("INVALID_ARGUMENT", e.code); }
    }
    static JSONObject definition(String type, boolean nullable) throws Exception {
        return new JSONObject().put("type", type).put("nullable", nullable);
    }
    static JSONObject selection() throws Exception {
        return new JSONObject().put("version", 1).put("table", "notes");
    }
    static JSONObject migration(JSONArray steps) throws Exception {
        return new JSONObject().put("fromVersion", 0).put("toVersion", 1).put("steps", steps);
    }
    static JSONObject create() throws Exception {
        return new JSONObject().put("kind", "createTable").put("table", "notes")
                .put("columns", new JSONObject().put("title", definition("text", false)));
    }
    private static void actionable(String method, JSONObject args, String expected, String submitted) throws Exception {
        try { DatabaseRequest.validate(method, args); fail("Expected INVALID_ARGUMENT"); }
        catch (FactoryException failure) {
            assertEquals("INVALID_ARGUMENT", failure.code);
            assertEquals(expected, failure.getMessage());
            assertFalse(failure.getMessage().contains(submitted));
        }
    }
    @Test public void closedMethodsAndUnknownFields() throws Exception {
        for (String method : new String[]{"database.info", "database.close", "database.cancel"}) {
            DatabaseRequest.validate(method, new JSONObject());
            invalid(() -> DatabaseRequest.validate(method, new JSONObject().put("sql", "SELECT 1")));
        }
        invalid(() -> DatabaseRequest.validate("database.rawQuery", new JSONObject()));
        invalid(() -> DatabaseRequest.validate("database.select", selection().put("path", "/tmp/other.db")));
        invalid(() -> DatabaseRequest.validate("database.select", selection().put("orderBy", "title")));
        invalid(() -> DatabaseRequest.validate("database.select", new JSONObject().put("table", "notes")));
        invalid(() -> DatabaseRequest.validate("database.select", selection().put("where", JSONObject.NULL)));
    }
    @Test public void identifiersAndIdsCannotIntroduceSqlOrReservedTables() throws Exception {
        for (String value : new String[]{"id", "sqlite_master", "factory_schema", "a;DROP TABLE x", "a.b", "a b", "A", "é", "", "_a", repeat("a", 33)}) {
            invalid(() -> DatabaseRequest.identifier(value));
        }
        assertEquals("a_123", DatabaseRequest.identifier("a_123"));
        assertEquals(repeat("a", 32), DatabaseRequest.identifier(repeat("a", 32)));
        assertEquals("A_z-09", DatabaseRequest.id("A_z-09"));
        DatabaseRequest.id(repeat("x", 96));
        for (String value : new String[]{"", "a' OR 1=1--", "a/b", "a b", "é", repeat("a", 97)}) invalid(() -> DatabaseRequest.id(value));
    }
    @Test public void textUsesUtf8AndRejectsMalformedUnicodeAndNul() throws Exception {
        DatabaseRequest.scalar("x'; DROP TABLE notes; --");
        DatabaseRequest.scalar("日本語 🌱");
        DatabaseRequest.scalar(repeat("é", 2048));
        DatabaseRequest.scalar(repeat("🌱", 1024));
        invalid(() -> DatabaseRequest.scalar(repeat("é", DatabaseRequest.HARD_MAX_CELL_BYTES / 2 + 1)));
        invalid(() -> DatabaseRequest.scalar(repeat("x", DatabaseRequest.HARD_MAX_CELL_BYTES + 1)));
        invalid(() -> DatabaseRequest.scalar("a\0b"));
        invalid(() -> DatabaseRequest.scalar("\uD800"));
        invalid(() -> DatabaseRequest.scalar("\uDC00"));
        invalid(() -> DatabaseRequest.scalar("\uD800a"));
    }
    @Test public void scalarsPreserveNullBooleanAndSafeIntegerTypes() throws Exception {
        DatabaseRequest.scalar(JSONObject.NULL);
        DatabaseRequest.scalar(true);
        DatabaseRequest.scalar(false);
        DatabaseRequest.scalar(DatabaseRequest.MAX_SAFE_INTEGER);
        DatabaseRequest.scalar(-DatabaseRequest.MAX_SAFE_INTEGER);
        DatabaseRequest.scalar(1.25);
        invalid(() -> DatabaseRequest.scalar(DatabaseRequest.MAX_SAFE_INTEGER + 1));
        invalid(() -> DatabaseRequest.scalar(-DatabaseRequest.MAX_SAFE_INTEGER - 1));
        invalid(() -> DatabaseRequest.scalar(Double.NaN));
        invalid(() -> DatabaseRequest.scalar(Double.POSITIVE_INFINITY));
        invalid(() -> DatabaseRequest.scalar(new JSONArray()));
        invalid(() -> DatabaseRequest.scalar(new JSONObject()));
        invalid(() -> DatabaseRequest.scalar(null));
        DatabaseRequest.typed(JSONObject.NULL, definition("text", true));
        invalid(() -> DatabaseRequest.typed(JSONObject.NULL, definition("text", false)));
        invalid(() -> DatabaseRequest.typed("true", definition("boolean", false)));
        invalid(() -> DatabaseRequest.typed(1, definition("boolean", false)));
        invalid(() -> DatabaseRequest.typed(1.0, definition("integer", false)));
        invalid(() -> DatabaseRequest.typed(true, definition("integer", false)));
        invalid(() -> DatabaseRequest.typed(1, definition("text", false)));
        DatabaseRequest.typed(1L, definition("real", false));
    }
    @Test public void schemaDefinitionsAreClosedAndStrictlyTyped() throws Exception {
        for (String type : new String[]{"text", "integer", "real", "boolean"}) DatabaseRequest.definition(definition(type, true));
        invalid(() -> DatabaseRequest.definition(definition("blob", true)));
        invalid(() -> DatabaseRequest.definition(definition("text", true).put("nullable", "true")));
        invalid(() -> DatabaseRequest.definition(definition("text", true).put("default", "x")));
        invalid(() -> DatabaseRequest.columns(new JSONObject()));
        JSONObject columns = new JSONObject();
        for (int i = 0; i < DatabaseRequest.HARD_MAX_COLUMNS; i++) columns.put("c" + i, definition("text", true));
        DatabaseRequest.columns(columns);
        columns.put("extra", definition("text", true));
        invalid(() -> DatabaseRequest.columns(columns));
    }
    @Test public void adjacentMigrationsAndStepBounds() throws Exception {
        DatabaseRequest.validate("database.migrate", migration(new JSONArray().put(create())));
        invalid(() -> DatabaseRequest.validate("database.migrate", migration(new JSONArray())));
        invalid(() -> DatabaseRequest.validate("database.migrate", migration(new JSONArray().put(create())).put("toVersion", 2)));
        invalid(() -> DatabaseRequest.validate("database.migrate", migration(new JSONArray().put(create())).put("fromVersion", 0.0)));
        invalid(() -> DatabaseRequest.validate("database.migrate", migration(new JSONArray().put(create().put("sql", "DROP TABLE x")))));
        JSONObject add = new JSONObject().put("kind", "addColumn").put("table", "notes").put("column", "extra").put("definition", definition("text", false));
        invalid(() -> DatabaseRequest.validate("database.migrate", migration(new JSONArray().put(add))));
        add.put("definition", definition("text", true));
        DatabaseRequest.validate("database.migrate", migration(new JSONArray().put(add)));
        JSONArray steps = new JSONArray();
        for (int i = 0; i < 16; i++) steps.put(create());
        DatabaseRequest.validate("database.migrate", migration(steps));
        steps.put(create());
        invalid(() -> DatabaseRequest.validate("database.migrate", migration(steps)));
    }
    @Test public void databaseValidationNamesTheFieldAndExpectationWithoutEchoingInput() throws Exception {
        JSONObject badStep = new JSONObject(create().toString()).put("columns", new JSONArray());
        try {
            DatabaseRequest.validate("database.migrate", migration(new JSONArray().put(badStep)));
            fail("Expected INVALID_ARGUMENT");
        } catch (FactoryException failure) {
            assertEquals("INVALID_ARGUMENT", failure.code);
            assertEquals("database.migrate.steps[0].columns must be an object mapping column name to {type, nullable}", failure.getMessage());
        }

        JSONObject privateColumnName = new JSONObject().put("synthetic-private-column", definition("text", false));
        badStep.put("columns", privateColumnName);
        try {
            DatabaseRequest.validate("database.migrate", migration(new JSONArray().put(badStep)));
            fail("Expected INVALID_ARGUMENT");
        } catch (FactoryException failure) {
            assertEquals("INVALID_ARGUMENT", failure.code);
            assertTrue(failure.getMessage().contains("columns keys must be lowercase column identifiers"));
            assertFalse(failure.getMessage().contains("synthetic-private-column"));
        }
    }
    @Test public void databaseMethodsNameTypedFieldsWithoutEchoingValues() throws Exception {
        actionable("database.migrate", migration(new JSONArray().put(create())).put("toVersion", "private-version"),
                "database.migrate.toVersion must be the next integer schema version", "private-version");

        JSONObject operation = new JSONObject().put("kind", "insert").put("table", "notes").put("id", "n1")
                .put("values", "private-values");
        actionable("database.transact", new JSONObject().put("version", 1).put("operations", new JSONArray().put(operation)),
                "database.transact.operations[0].values must be an object of scalar values", "private-values");

        actionable("database.select", selection().put("limit", "private-limit"),
                "database.select.limit must be an integer from 1 through " + DatabaseRequest.HARD_MAX_SELECT_ROWS, "private-limit");
        actionable("database.info", new JSONObject().put("private-field", "private-value"),
                "database.info.args may contain no fields.", "private-value");
    }
    @Test public void operationGrammarAndBounds() throws Exception {
        JSONObject op = new JSONObject().put("kind", "insert").put("table", "notes").put("id", "one").put("values", new JSONObject().put("title", "hello"));
        JSONObject args = new JSONObject().put("version", 1).put("operations", new JSONArray().put(op));
        DatabaseRequest.validate("database.transact", args);
        invalid(() -> DatabaseRequest.validate("database.transact", new JSONObject(args.toString()).put("operations", new JSONArray())));
        invalid(() -> DatabaseRequest.validate("database.transact", new JSONObject(args.toString()).put("version", "1")));
        JSONObject bad = new JSONObject(op.toString()).put("values", new JSONObject());
        invalid(() -> DatabaseRequest.validate("database.transact", new JSONObject(args.toString()).put("operations", new JSONArray().put(bad))));
        bad.put("kind", "upsert");
        invalid(() -> DatabaseRequest.validate("database.transact", new JSONObject(args.toString()).put("operations", new JSONArray().put(bad))));
        JSONObject delete = new JSONObject().put("kind", "delete").put("table", "notes").put("id", "one");
        JSONArray operations = new JSONArray();
        for (int i = 0; i < DatabaseRequest.HARD_MAX_OPERATIONS; i++) operations.put(delete);
        args.put("operations", operations);
        DatabaseRequest.validate("database.transact", args);
        operations.put(delete);
        invalid(() -> DatabaseRequest.validate("database.transact", args));
    }
    @Test public void selectionAndByteBounds() throws Exception {
        DatabaseRequest.validate("database.select", selection().put("limit", 100).put("afterId", "z"));
        for (Object limit : new Object[]{0, DatabaseRequest.HARD_MAX_SELECT_ROWS + 1, 1.0, "1", true, JSONObject.NULL}) invalid(() -> DatabaseRequest.validate("database.select", selection().put("limit", limit)));
        JSONObject values = new JSONObject();
        for (int i = 0; i < 4; i++) values.put("c" + i, repeat("x", DatabaseRequest.HARD_MAX_ROW_BYTES / 4));
        invalid(() -> DatabaseRequest.values(values));
        JSONObject huge = selection().put("where", new JSONObject().put("title", repeat("x", DatabaseRequest.MAX_ARGUMENT_BYTES)));
        invalid(() -> DatabaseRequest.validate("database.select", huge));
    }
    static String repeat(String text, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(text);
        return result.toString();
    }
}
