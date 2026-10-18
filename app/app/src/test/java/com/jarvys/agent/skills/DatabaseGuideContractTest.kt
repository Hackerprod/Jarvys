package com.jarvys.agent.skills

import com.jarvys.factory.runtime.DatabaseRequest
import com.jarvys.factory.runtime.FactoryException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class DatabaseGuideContractTest {
    private val guide = File("src/main/assets/skills/com.jarvys.apk-factory/references/database.md")

    @Test fun documentedExamplesPassDatabaseRequestValidator() {
        val markdown = guide.readText(Charsets.UTF_8)
        val marker = markdown.indexOf("<!-- database-contract-examples -->")
        assertTrue("Missing tested database examples", marker >= 0)
        val fence = markdown.indexOf("```json\n", marker)
        assertTrue("Missing JSON examples block", fence >= 0)
        val start = fence + "```json\n".length
        val end = markdown.indexOf("\n```", start)
        assertTrue("Unclosed JSON examples block", end >= start)

        val examples = JSONArray(markdown.substring(start, end))
        val methods = linkedSetOf<String>()
        for (index in 0 until examples.length()) {
            val pair = examples.getJSONArray(index)
            val method = pair.getString(0)
            methods += method
            DatabaseRequest.validate(method, pair.getJSONObject(1))
        }
        assertEquals(setOf("database.info", "database.migrate", "database.transact", "database.select",
            "database.close", "database.cancel"), methods)
    }

    @Test fun createTableColumnsMustBeAnObjectMap() {
        val args = JSONObject()
            .put("fromVersion", 0)
            .put("toVersion", 1)
            .put("steps", JSONArray().put(JSONObject()
                .put("kind", "createTable")
                .put("table", "notes")
                .put("columns", JSONArray().put(JSONObject()
                    .put("name", "title")
                    .put("type", "text")
                    .put("nullable", false)))))
        try {
            DatabaseRequest.validate("database.migrate", args)
            fail("An array-form columns definition must be rejected")
        } catch (error: FactoryException) {
            assertEquals("INVALID_ARGUMENT", error.code)
        }
    }
}
