package com.jarvys.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EpisodeNarrationPayloadTest {
    private fun row(outcome: String = "ok", kind: String = "tool_call") = JSONObject()
        .put("kind", kind).put("tool", "gmail_trash").put("connector", "gmail")
        .put("outcome", outcome).put("authorization", "ask_approved").put("at_ms", 42)
        .put("facts", JSONObject().put("classification", "WRITE"))

    @Test fun structuredAndNestedJsonSecretsAreRedactedBeforeSerialization() {
        val facts = JSONObject().put("password", "synthetic123").put("api_key", "synthetic456")
            .put("detail", "password=synthetic789")
            .put("nested", """{"access_token":"syntheticABC"}""")
        val source = JSONArray().put(row().put("facts", facts))
        val clean = EpisodeNarrationPayload.redactEvidence(source) { value ->
            value.replace(Regex("password=\\S+"), "[secret redacted]")
        } as JSONArray
        val rendered = EpisodeNarrationPayload.render("", "", 42, clean, 4096)
        assertFalse(rendered.contains("synthetic"))
        assertEquals("synthetic123", facts.getString("password")) // Durable/transient source is untouched.
        val parsed = JSONObject(rendered).getJSONArray("evidence").getJSONObject(0)
        assertEquals("ask_approved", parsed.getString("authorization"))
        assertEquals("[secret redacted]", parsed.getJSONObject("facts").getString("password"))
        assertEquals("[secret redacted]", JSONObject(parsed.getJSONObject("facts").getString("nested")).getString("access_token"))
    }

    @Test fun headersTruncatedJsonAndModelTextDoNotRetainCredentials() {
        val row = row().put("source", JSONObject().put("headers", JSONObject().put("Authorization", "Basic synthetic123"))
            .put("prefix", """{"password":"synthetic456","remaining":"""))
            .put("facts", JSONObject().put("credential", "synthetic789"))
        val clean = EpisodeNarrationPayload.redactEvidence(JSONArray().put(row)) { it }
        assertFalse(clean.toString().contains("synthetic"))
        assertEquals("ask_approved", clean.getJSONObject(0).getString("authorization"))
        assertFalse(EpisodeNarrationPayload.redact("Narration: {\"password\":\"syntheticABC\"}") { it }.toString().contains("synthetic"))
    }

    @Test fun redactionExpansionIsIncludedInFinalBudgetAndJsonEscaping() {
        val clean = EpisodeNarrationPayload.redact(JSONArray().put(row().put("source", "x".repeat(2000)))) {
            it.replace("x", "[secret redacted]\"\\")
        } as JSONArray
        val rendered = EpisodeNarrationPayload.render("", "", 42, clean, 1000)
        assertTrue(rendered.length <= 1000)
        assertTrue(JSONObject(rendered).getBoolean("sources_omitted"))
    }

    @Test fun smallPayloadKeepsExactEvidenceAndSource() {
        val evidence = JSONArray().put(row().put("source", "53 affected items"))
        val rendered = EpisodeNarrationPayload.render("Clean spam", "Completed", 42, evidence, 4096)
        assertTrue(rendered.length <= 4096)
        val body = JSONObject(rendered)
        assertFalse(body.getBoolean("evidence_aggregated"))
        assertEquals("53 affected items", body.getJSONArray("evidence").getJSONObject(0).getString("source"))
    }

    @Test fun oversizedFactsAggregateAllCountsWithoutTreatingCountsAsAffectedItems() {
        val evidence = JSONArray()
        repeat(100) { index -> evidence.put(row(if (index % 2 == 0) "ok" else "error")
            .put("facts", JSONObject().put("classification", "WRITE").put("detail", "x".repeat(1000)))
            .put("source", "large output".repeat(100))) }
        val rendered = EpisodeNarrationPayload.render("Clean spam", "Mixed outcome", 42, evidence, 1600)
        assertTrue(rendered.length <= 1600)
        val body = JSONObject(rendered)
        assertTrue(body.getBoolean("evidence_aggregated"))
        assertTrue(body.getBoolean("sources_omitted"))
        assertEquals(100, body.getInt("evidence_rows"))
        val rows = body.getJSONArray("evidence")
        assertEquals(2, rows.length())
        assertEquals(50, rows.getJSONObject(0).getInt("evidence_count"))
        assertEquals("ok", rows.getJSONObject(0).getString("outcome"))
        assertEquals(50, rows.getJSONObject(1).getInt("evidence_count"))
        assertEquals("error", rows.getJSONObject(1).getString("outcome"))
        assertTrue(body.getString("source_coverage").contains("not proof"))
    }

    @Test fun escapedOptionalTextCannotOverflowTheCompleteJsonBudget() {
        val rendered = EpisodeNarrationPayload.render("\"\\\n".repeat(1000), "\"\\\n".repeat(1000), 42,
            JSONArray().put(row()), 1200)
        assertTrue(rendered.length <= 1200)
        val body = JSONObject(rendered)
        assertTrue(body.getBoolean("request_bounded"))
        assertTrue(body.getBoolean("final_response_bounded"))
        assertEquals(1, body.getJSONArray("evidence").getJSONObject(0).getInt("evidence_count"))
    }

    @Test fun aggregatedWorkerReceiptNeverBecomesVerifiedSuccess() {
        val evidence = JSONArray()
        repeat(30) { evidence.put(row(kind = "crew_result")
            .put("facts", JSONObject().put("reported_status", "COMPLETED").put("result_verified", false)
                .put("large_receipt", "x".repeat(1000)))) }
        val body = JSONObject(EpisodeNarrationPayload.render("", "", 42, evidence, 1400))
        val receipt = body.getJSONArray("evidence").getJSONObject(0)
        assertFalse(receipt.getBoolean("result_verified"))
        assertEquals("COMPLETED", receipt.getString("reported_status"))
        assertEquals(30, receipt.getInt("evidence_count"))
    }

    @Test fun irreducibleMetadataFailsExplicitlyInsteadOfDiscardingOutcomes() {
        val evidence = JSONArray()
        repeat(20) { evidence.put(row().put("tool", "distinct-$it-" + "x".repeat(200))) }
        val failure = runCatching { EpisodeNarrationPayload.render("", "", 42, evidence, 500) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("episode_narration_metadata_exceeds_context_budget", failure?.message)
    }
}
