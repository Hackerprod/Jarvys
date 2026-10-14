package com.jarvys.agent.connectors

import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class EmailDraft(val to: String, val subject: String, val body: String, val cc: String, val bcc: String)

/** Shared email validation and MIME/URI data model used by Gmail. */
object EmailIntentPolicy {
    const val MAX_RECIPIENTS = 20
    const val MAX_FIELD_CHARS = 20_000
    private val address = Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+")

    fun parse(to: String, subject: String, body: String, cc: String = "", bcc: String = ""): EmailDraft {
        val recipients = listOf(to, cc, bcc).map(::normalizeRecipients)
        require(recipients.sumOf { if (it.isBlank()) 0 else it.split(',').size } in 1..MAX_RECIPIENTS) {
            "Enter at least one valid recipient (maximum $MAX_RECIPIENTS)"
        }
        require((subject.length + body.length) <= MAX_FIELD_CHARS) {
            "Email subject and body exceed the $MAX_FIELD_CHARS-character limit"
        }
        require(subject.none { it == '\r' || it == '\n' }) { "Email subject cannot contain line breaks" }
        return EmailDraft(recipients[0], subject, body, recipients[1], recipients[2])
    }

    fun mailto(draft: EmailDraft): String {
        val path = encodeQuery(draft.to).replace("%40", "@")
        val query = listOf("cc" to draft.cc, "bcc" to draft.bcc, "subject" to draft.subject, "body" to draft.body)
            .filter { it.second.isNotEmpty() }
            .joinToString("&") { (key, value) -> encodeQuery(key) + "=" + encodeQuery(value) }
        return "mailto:$path" + if (query.isEmpty()) "" else "?$query"
    }

    fun schema() = JSONObject().put("type", "object")
        .put("properties", JSONObject()
            .put("to", stringSchema(4000)).put("cc", stringSchema(4000)).put("bcc", stringSchema(4000))
            .put("subject", stringSchema(500)).put("body", stringSchema(19_500)))
        .put("required", org.json.JSONArray(listOf("to", "subject", "body")))
        .put("additionalProperties", false)

    private fun normalizeRecipients(input: String): String {
        val normalized = input.split(',', ';').map(String::trim).filter(String::isNotEmpty)
        require(normalized.all { it.length <= 254 && address.matches(it) }) {
            "Each email recipient must be a valid address without a display name"
        }
        require(normalized.distinctBy { it.lowercase() }.size == normalized.size) { "Duplicate email recipient" }
        return normalized.joinToString(",")
    }

    private fun encodeQuery(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")

    private fun stringSchema(max: Int) = JSONObject().put("type", "string").put("maxLength", max)
}
