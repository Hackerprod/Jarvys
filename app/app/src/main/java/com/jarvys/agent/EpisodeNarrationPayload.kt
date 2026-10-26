package com.jarvys.agent

import org.json.JSONArray
import org.json.JSONObject

/** Bounds the complete model input without changing the durable evidence behind it. */
internal object EpisodeNarrationPayload {
    /** Redact typed values before serialization/budgeting; never rewrite serialized JSON. */
    fun redact(value: Any?, redactText: (String) -> String): Any? = when (value) {
        is JSONObject -> JSONObject().also { clean ->
            value.keys().forEach { key ->
                val normalized = key.lowercase(java.util.Locale.ROOT).replace("_", "").replace("-", "")
                clean.put(key, if (normalized in sensitiveKeys) "[secret redacted]"
                    else redact(value.opt(key), redactText) ?: JSONObject.NULL)
            }
        }
        is JSONArray -> JSONArray().also { clean ->
            for (index in 0 until value.length()) clean.put(redact(value.opt(index), redactText) ?: JSONObject.NULL)
        }
        is String -> {
            // Tool output frequently embeds JSON as a string, rather than as a JSON object.
            val parsed = runCatching {
                when (value.trimStart().firstOrNull()) {
                    '{' -> JSONObject(value)
                    '[' -> JSONArray(value)
                    else -> null
                }
            }.getOrNull()
            if (parsed != null) redact(parsed, redactText).toString() else redactText(redactCredentialText(value))
        }
        else -> value
    }
    private val sensitiveKeys = setOf("password", "passwd", "apikey", "accesstoken", "refreshtoken",
        "clientsecret", "secret", "token", "privatekey", "credential", "credentials", "authorization", "cookie", "setcookie")

    fun redactEvidence(rows: JSONArray, redactText: (String) -> String): JSONArray = JSONArray().also { clean ->
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val redacted = redact(row, redactText) as JSONObject
            // Only this typed evidence field is an admission label, never an HTTP header.
            redacted.put("authorization", row.opt("authorization")?.let {
                if (it is String) redactText(redactCredentialText(it)) else it
            } ?: JSONObject.NULL)
            clean.put(redacted)
        }
    }

    private val quotedCredential = Regex(
        """(?i)"(?:password|passwd|api[_ -]?key|access[_ -]?token|refresh[_ -]?token|client[_ -]?secret|secret|token|private[_ -]?key|credentials?|authorization|cookie|set[_ -]?cookie)"\s*:\s*"(?:\\.|[^"\\])*(?:"|$)""")
    private val headerCredential = Regex("""(?i)\b(?:Basic|Bearer)\s+[^\s,;"}]+""")
    private fun redactCredentialText(value: String): String =
        headerCredential.replace(quotedCredential.replace(value, "\"credential\":\"[secret redacted]\""), "[secret redacted]")

    fun render(request: String, finalResponse: String, eventAt: Long?, evidence: JSONArray, budget: Int): String {
        require(budget > 0)
        val body = JSONObject().put("request", request).put("final_response", finalResponse)
            .put("event_at_ms", eventAt ?: JSONObject.NULL).put("evidence", evidence)
            .put("evidence_rows", evidence.length()).put("evidence_aggregated", false)
            .put("source_coverage", "Bounded source excerpts, not proof of an affected-item total unless every relevant source supports it.")
        fun fits() = body.toString().length <= budget
        if (fits()) return body.toString()
        for (index in 0 until evidence.length()) evidence.getJSONObject(index).remove("source")
        body.put("sources_omitted", true)
        if (fits()) return body.toString()

        val groups = linkedMapOf<List<String>, JSONObject>()
        for (index in 0 until evidence.length()) {
            val row = evidence.getJSONObject(index)
            val facts = row.optJSONObject("facts")
            val keys = listOf("kind", "tool", "connector", "outcome", "authorization")
            val identity = keys.map { row.optString(it) } +
                listOf(facts?.optString("reported_status").orEmpty(), facts?.optString("result_verified").orEmpty())
            val group = groups.getOrPut(identity) {
                JSONObject().apply {
                    keys.forEach { put(it, row.opt(it) ?: JSONObject.NULL) }
                    put("evidence_count", 0)
                    put("first_at_ms", row.optLong("at_ms"))
                    put("last_at_ms", row.optLong("at_ms"))
                    if (row.optString("kind") == "crew_result") {
                        put("result_verified", false)
                        put("reported_status", facts?.opt("reported_status") ?: JSONObject.NULL)
                    }
                }
            }
            group.put("evidence_count", group.getLong("evidence_count") + 1)
            group.put("first_at_ms", minOf(group.getLong("first_at_ms"), row.optLong("at_ms")))
            group.put("last_at_ms", maxOf(group.getLong("last_at_ms"), row.optLong("at_ms")))
        }
        body.put("evidence", JSONArray(groups.values.toList())).put("evidence_aggregated", true)
        if (fits()) return body.toString()
        body.put("request", "").put("final_response", "")
            .put("request_bounded", request.isNotEmpty()).put("final_response_bounded", finalResponse.isNotEmpty())
        if (!fits()) throw IllegalStateException("episode_narration_metadata_exceeds_context_budget")
        val remaining = budget - body.toString().length
        body.put("request", request.take(remaining / 2)).put("final_response", finalResponse.take(remaining - remaining / 2))
        // JSON escaping also consumes the existing budget; shorten only optional text, never outcome counts.
        while (!fits()) {
            val excess = body.toString().length - budget
            val key = if (body.getString("final_response").isNotEmpty()) "final_response" else "request"
            val value = body.getString(key)
            if (value.isEmpty()) throw IllegalStateException("episode_narration_metadata_exceeds_context_budget")
            body.put(key, value.dropLast(minOf(value.length, excess)))
        }
        return body.toString()
    }
}
