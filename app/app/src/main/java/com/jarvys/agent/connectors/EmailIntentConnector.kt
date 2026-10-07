package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.R
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Opens an external mail composer only; this runtime can never send the message itself. */
class EmailIntentConnector : ConnectorRuntime {
    override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
    override fun disconnect() = Unit
    override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken): JSONObject =
        error("Email composition must pass through the connector approval gate")

    override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation {
        require(operation == COMPOSE) { "Unknown email operation: $operation" }
        token.throwIfCancelled()
        val draft = EmailIntentPolicy.parse(
            arguments.optString("to"), arguments.optString("subject"), arguments.optString("body"),
            arguments.optString("cc"), arguments.optString("bcc"),
        )
        val summary = ApprovalSummary(
            title = "Open email draft",
            lines = listOf(
                "To: ${draft.to.ifBlank { "(none)" }}",
                "Cc: ${draft.cc.ifBlank { "(none)" }}",
                "Bcc: ${draft.bcc.ifBlank { "(none)" }}",
                "Subject: ${draft.subject.ifBlank { "(no subject)" }}",
                "Body preview: ${draft.body.take(PREVIEW_CHARS)}",
                "Jarvys will open the mail app; you review and send it there.",
            ),
            activityIntent = ApprovalIntentSpec(
                ApprovalIntentKind.EMAIL_COMPOSE,
                dataUri = EmailIntentPolicy.mailto(draft),
                extras = mapOf("to" to draft.to, "cc" to draft.cc, "bcc" to draft.bcc,
                    "subject" to draft.subject, "body" to draft.body),
            ),
            allowAlwaysAvailable = false,
            localizedTitle = ConnectorUiText(R.string.email_approval_title, fallback = "Open email draft"),
            localizedLines = listOf(
                ConnectorUiText(R.string.email_approval_to, listOf(draft.to.ifBlank { "(none)" }), "To: ${draft.to.ifBlank { "(none)" }}"),
                ConnectorUiText(R.string.email_approval_cc, listOf(draft.cc.ifBlank { "(none)" }), "Cc: ${draft.cc.ifBlank { "(none)" }}"),
                ConnectorUiText(R.string.email_approval_bcc, listOf(draft.bcc.ifBlank { "(none)" }), "Bcc: ${draft.bcc.ifBlank { "(none)" }}"),
                ConnectorUiText(R.string.email_approval_subject, listOf(draft.subject.ifBlank { "(no subject)" }), "Subject: ${draft.subject.ifBlank { "(no subject)" }}"),
                ConnectorUiText(R.string.email_approval_body, listOf(draft.body.take(PREVIEW_CHARS)), "Body preview: ${draft.body.take(PREVIEW_CHARS)}"),
                ConnectorUiText(R.string.email_approval_external_send, fallback = "Review and send in your mail app; Jarvys will not send it."),
            ),
        )
        return ConnectorWritePreparation(summary, JSONObject(arguments.toString()), attachment = draft)
    }

    override fun invokePrepared(
        operation: String,
        arguments: JSONObject,
        preparation: ConnectorWritePreparation,
        token: CancellationToken,
    ): JSONObject {
        token.throwIfCancelled()
        require(operation == COMPOSE)
        val draft = preparation.attachment as? EmailDraft ?: error("Email draft is missing")
        return JSONObject().put("source", "android.email_intent").put("untrusted_content", false)
            .put("status", "composer_opened").put("sent", false)
            .put("recipients", draft.to.split(',').filter(String::isNotBlank))
            .put("message", "The system mail composer opened; review and press Send there. Jarvys did not send the email.")
    }

    companion object {
        const val ID = "email"
        const val COMPOSE = "compose_email"
        const val MAX_RECIPIENTS = 20
        const val MAX_FIELD_CHARS = 20_000
        private const val PREVIEW_CHARS = 240

        fun definition() = ConnectorDefinition(
            id = ID,
            name = "Email",
            version = "1",
            description = "Opens a draft in the user's mail app. Jarvys cannot send email through this intent-only connector.",
            capabilities = listOf("email.compose_intent"),
            operations = listOf(ConnectorOperation(
                name = COMPOSE,
                displayLabel = "Compose email",
                description = "After approval, opens ACTION_SENDTO with a mailto draft; the user reviews and sends it in the external mail app. This operation cannot use Allow.",
                inputSchema = EmailIntentPolicy.schema(),
                write = true,
                autonomyAllowed = false,
                limits = mapOf("recipientCount" to MAX_RECIPIENTS, "fieldChars" to MAX_FIELD_CHARS),
                displayLabelResourceId = R.string.connector_operation_email_compose,
                descriptionResourceId = R.string.connector_operation_email_compose_description,
            )),
            runtime = EmailIntentConnector(),
            displayNameResourceId = R.string.connector_label_email,
            descriptionResourceId = R.string.connector_description_email,
            usageNoteProvider = { "Email composition only opens a prefilled system mail draft after approval. Jarvys never sends it; the user reviews and taps Send in their mail app." },
        )
    }
}

data class EmailDraft(val to: String, val subject: String, val body: String, val cc: String, val bcc: String)

/** Pure validation/URI construction seam for JVM tests; recipients are addresses, not display-name syntax. */
object EmailIntentPolicy {
    private val address = Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+")

    fun parse(to: String, subject: String, body: String, cc: String = "", bcc: String = ""): EmailDraft {
        val recipients = listOf(to, cc, bcc).map(::normalizeRecipients)
        require(recipients.sumOf { if (it.isBlank()) 0 else it.split(',').size } in 1..EmailIntentConnector.MAX_RECIPIENTS) {
            "Enter at least one valid recipient (maximum ${EmailIntentConnector.MAX_RECIPIENTS})"
        }
        require((subject.length + body.length) <= EmailIntentConnector.MAX_FIELD_CHARS) {
            "Email subject and body exceed the ${EmailIntentConnector.MAX_FIELD_CHARS}-character limit"
        }
        require(subject.none { it == '\r' || it == '\n' }) { "Email subject cannot contain line breaks" }
        return EmailDraft(recipients[0], subject, body, recipients[1], recipients[2])
    }

    fun mailto(draft: EmailDraft): String {
        val path = encodeQuery(draft.to).replace("%40", "@")
        val query = listOf("cc" to draft.cc, "bcc" to draft.bcc, "subject" to draft.subject, "body" to draft.body)
            .filter { it.second.isNotEmpty() }
            .joinToString("&") { (key, value) ->
                encodeQuery(key) + "=" + encodeQuery(value)
            }
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
