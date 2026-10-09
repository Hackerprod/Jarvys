package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.R
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Deletes one immutable reviewed draft. It never interprets mailbox content as authorization. */
internal class GmailDraftDeletion(
    private val oauth: GoogleRestAuthorization,
    private val journal: GoogleWorkspaceWriteJournal,
) {
    private class Reviewed(val owner: GmailDraftDeletion, val account: String, val epoch: Long,
        val id: String, val revision: String, val rawDigest: String, val intent: String,
        val attempted: AtomicBoolean = AtomicBoolean(false))

    fun prepare(args: JSONObject, token: CancellationToken): ConnectorWritePreparation {
        require(args.keys().asSequence().toSet() == setOf("id", "expected_revision")) {
            "delete_draft requires only an exact id and expected_revision"
        }
        val id = identifier(args.get("id"))
        val revision = identifier(args.get("expected_revision"))
        token.throwIfCancelled()
        requireScope()
        val epoch = oauth.currentAuthorizationEpoch()
        val account = verifyAccount(null, epoch, token)
        // Stable per account/target: a changed revision cannot evade an unresolved DELETE.
        val intent = GmailContent.digest("$OPERATION\n$account\n$id".toByteArray(StandardCharsets.UTF_8))
        check(!journal.isUncertain(intent)) { UNKNOWN }
        val full = readDraft(id, "full", revision, epoch, token)
        val raw = readDraft(id, "raw", revision, epoch, token)
        val digest = rawDigest(raw)
        val details = preview(full, account, id, revision)
        token.throwIfCancelled()
        checkEpoch(epoch)
        val title = ui(R.string.gmail_draft_delete_title, "Permanently delete Gmail draft")
        return ConnectorWritePreparation(
            ApprovalSummary(title.fallback, details.map { it.fallback }, allowAlwaysAvailable = false,
                localizedTitle = title, localizedLines = details,
                compactSummary = ui(R.string.gmail_draft_delete_compact, "${title.fallback} · $id · $account", title, id, account)),
            JSONObject().put("id", id).put("expected_revision", revision),
            Reviewed(this, account, epoch, id, revision, digest, intent),
        )
    }

    fun execute(preparation: ConnectorWritePreparation, token: CancellationToken): JSONObject {
        val reviewed = preparation.attachment as? Reviewed ?: error("Reviewed Gmail draft deletion is missing")
        check(reviewed.owner === this) { "Gmail draft deletion approval belongs to another action" }
        token.throwIfCancelled()
        check(!reviewed.attempted.get() && !journal.isUncertain(reviewed.intent)) { UNKNOWN }
        requireScope()
        verifyAccount(reviewed.account, reviewed.epoch, token)
        val latest = readDraft(reviewed.id, "raw", reviewed.revision, reviewed.epoch, token)
        check(rawDigest(latest) == reviewed.rawDigest) {
            "Draft content changed after approval; read and approve it again. No delete was sent."
        }
        // The transport also proves the exact mutation token, including after token refresh.
        verifyAccount(reviewed.account, reviewed.epoch, token)
        token.throwIfCancelled()
        checkEpoch(reviewed.epoch)
        check(reviewed.attempted.compareAndSet(false, true)) { UNKNOWN }
        journal.reserve(reviewed.intent)
        // These local checks are provably before entering the mutation transport.
        try { token.throwIfCancelled(); checkEpoch(reviewed.epoch); requireScope() }
        catch (failure: Exception) { journal.resolve(reviewed.intent); throw failure }
        val response = try {
            oauth.requestCancellable(GoogleOAuthProtocol.GMAIL_COMPOSE, "DELETE", endpoint(reviewed.id),
                token = token, expectedAuthorizationEpoch = reviewed.epoch)
        } catch (failure: Exception) {
            if (failure is GooglePreDispatchAuthorizationException) {
                journal.resolve(reviewed.intent)
                throw failure
            }
            if (failure is CancellationException) throw failure
            throw IllegalStateException(UNKNOWN, failure)
        }
        if (response.status !in 200..299) {
            // Explicit rejections are terminal. Timeouts and server failures remain ambiguous.
            if (response.status in 400..499 && response.status != 408) journal.resolve(reviewed.intent)
            if (response.status !in 400..499 || response.status == 408) error(UNKNOWN)
            throw GoogleHttpPolicy.failure(response.status, response.body)
        }
        // Gmail drafts.delete returns an empty response, not an arbitrary success marker.
        check(response.body.length <= GoogleApiLimits.MAX_RESPONSE_BYTES &&
            (response.body.isBlank() || runCatching { json(response.body).length() == 0 }.getOrDefault(false))) { UNKNOWN }
        token.throwIfCancelled()
        val verification = try {
            verifyAccount(reviewed.account, reviewed.epoch, token)
            val check = requestRead(reviewed.id, "metadata", reviewed.epoch, token)
            when {
                check.status == 404 -> "observed_absent"
                check.status in 200..299 -> {
                    validateDraft(json(check.body), reviewed.id, null)
                    "observed_present"
                }
                else -> "unknown"
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            "unknown"
        }
        token.throwIfCancelled()
        // Absence is an observation, never proof that this request caused the deletion.
        val resolved = verification == "observed_absent" && journal.resolve(reviewed.intent)
        val item = JSONObject().put("id", reviewed.id).put("revision", reviewed.revision)
            .put("account", reviewed.account).put("status", "delete_accepted")
            .put("verification", verification).put("deletion_causation_verified", false)
            .put("retry_blocked", !resolved)
        return GmailContent.envelope("gmail.$OPERATION", JSONArray().put(item), 1).also {
            if (!resolved) it.put("safety_warning", "Gmail accepted draft deletion, but verification or local safety state is unresolved. Do not retry; inspect the draft in Gmail.")
        }
    }

    private fun verifyAccount(expected: String?, epoch: Long, token: CancellationToken): String {
        checkEpoch(epoch)
        val account = oauth.verifyGmailAccount(GoogleOAuthProtocol.GMAIL_COMPOSE, expected, token, epoch)
        require(account.length in 3..320 && account.none { it.isWhitespace() || it.isISOControl() } && '@' in account) {
            "Gmail did not prove a valid account"
        }
        check(expected == null || account == expected) { "Google account changed after approval; no delete was sent" }
        token.throwIfCancelled()
        checkEpoch(epoch)
        return account
    }

    private fun requireScope() = check(oauth.isScopeGranted(GoogleOAuthProtocol.GMAIL_COMPOSE)) {
        "Enable Gmail draft permission before deleting a draft. No delete was sent."
    }
    private fun checkEpoch(epoch: Long) = check(epoch == oauth.currentAuthorizationEpoch()) {
        "Google authorization changed; review the draft deletion again."
    }
    private fun endpoint(id: String) = "${GoogleRestEndpoints.GMAIL}/users/me/drafts/${GoogleRestEndpoints.path(id)}"

    private fun requestRead(id: String, format: String, epoch: Long, token: CancellationToken): GoogleHttpResponse {
        token.throwIfCancelled()
        checkEpoch(epoch)
        val response = oauth.requestBytes(GoogleOAuthProtocol.GMAIL_COMPOSE, "GET", "${endpoint(id)}?format=$format",
            token = token, maxResponseBytes = GoogleApiLimits.MAX_TRANSFER_BYTES, expectedAuthorizationEpoch = epoch)
        token.throwIfCancelled()
        checkEpoch(epoch)
        // Also enforce the limit on custom transports; never retain unbounded raw MIME.
        GoogleHttpPolicy.requireSize(response.body.size, GoogleApiLimits.MAX_TRANSFER_BYTES)
        val body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body)).toString()
        return GoogleHttpResponse(response.status, body, response.headers)
    }
    private fun readDraft(id: String, format: String, revision: String, epoch: Long, token: CancellationToken): JSONObject {
        val response = requestRead(id, format, epoch, token)
        GoogleRestEndpoints.requireSuccess(response)
        return validateDraft(json(response.body), id, revision)
    }
    private fun validateDraft(value: JSONObject, id: String, revision: String?): JSONObject {
        check(value.opt("id") == id) { "Gmail returned a different draft" }
        val message = value.optJSONObject("message") ?: error("Gmail returned an incomplete draft")
        val actual = identifier(message.opt("id"))
        check(revision == null || actual == revision) { "Draft revision changed; read and approve the latest draft. No delete was sent." }
        return message
    }
    private fun rawDigest(message: JSONObject): String {
        val raw = message.opt("raw")
        require(raw is String) { "Draft MIME snapshot is missing" }
        // Empty MIME is a valid empty draft. Deletion does not need sendable recipients or body.
        return GmailContent.digest(GmailContent.decode(raw, MAX_RAW_BYTES))
    }

    private fun preview(message: JSONObject, account: String, id: String, revision: String): List<ConnectorUiText> {
        val row = GmailContent.messageRow(message)
        var truncated = row.optBoolean("truncated")
        val empty = ui(R.string.gmail_draft_delete_empty, "(empty)")
        fun text(value: String, maximum: Int): Any {
            val bounded = value.take(maximum)
            val safe = bounded.map { if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }.joinToString("")
            if (safe != bounded || value.length > maximum) truncated = true
            return if (safe.isBlank()) empty else safe
        }
        // A duplicate header cannot silently masquerade as a complete preview.
        val headers = message.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
        for (name in listOf("To", "Cc", "Bcc", "Subject")) {
            if ((0 until headers.length()).count { headers.optJSONObject(it)?.optString("name").equals(name, true) } > 1) truncated = true
        }
        val lines = mutableListOf(
            ui(R.string.gmail_management_account, "Account: $account", account),
            ui(R.string.gmail_draft_delete_identity, "Draft: $id · revision: $revision", id, revision),
        )
        listOf(Triple("subject", R.string.gmail_draft_delete_subject, "Subject preview"),
            Triple("to", R.string.gmail_draft_delete_to, "To preview"),
            Triple("cc", R.string.gmail_draft_delete_cc, "Cc preview"),
            Triple("bcc", R.string.gmail_draft_delete_bcc, "Bcc preview"),
            Triple("text", R.string.gmail_draft_delete_body, "Body preview")).forEach { (key, resource, label) ->
            val preview = text(row.optString(key), if (key == "text") 1_000 else 500)
            lines += ui(resource, "$label: ${if (preview is ConnectorUiText) preview.fallback else preview}", preview)
        }
        val attachments = row.optJSONArray("attachments") ?: JSONArray()
        if (attachments.length() > MAX_ATTACHMENT_PREVIEWS) truncated = true
        for (index in 0 until minOf(attachments.length(), MAX_ATTACHMENT_PREVIEWS)) {
            val attachment = attachments.getJSONObject(index)
            val name = text(attachment.optString("name"), 200)
            val mime = text(attachment.optString("mimeType"), 128)
            val size = attachment.optLong("size", -1)
            val sizeText: Any = if (size >= 0) size.toString() else ui(R.string.gmail_draft_delete_unknown_size, "unknown")
            fun fallback(value: Any) = if (value is ConnectorUiText) value.fallback else value.toString()
            lines += ui(R.string.gmail_draft_delete_attachment,
                "Attachment: ${fallback(name)} · ${fallback(mime)} · ${fallback(sizeText)} bytes", name, mime, sizeText)
        }
        if (truncated) lines += ui(R.string.gmail_draft_delete_truncated,
            "Preview is truncated or simplified; it does not show all draft content or attachments. This approval permanently deletes the entire identified draft.")
        lines += ui(R.string.gmail_draft_delete_irreversible,
            "Permanent deletion. The entire draft and its attachments are removed and cannot be recovered from Trash. This one-time approval applies only to this account, draft and reviewed revision.")
        lines += ui(R.string.gmail_draft_delete_race,
            "Changes detected before deletion block the request. Gmail does not provide an atomic revision condition, so a change between the final check and deletion cannot be prevented.")
        return lines
    }

    companion object {
        const val OPERATION = "delete_draft"
        private const val MAX_RAW_BYTES = 6 * 1024 * 1024
        private const val MAX_ATTACHMENT_PREVIEWS = 10
        private const val UNKNOWN = "Draft deletion outcome is unknown or this review was already attempted. Do not retry; inspect the draft in Gmail."
        private val ID = Regex("[A-Za-z0-9_-]{1,256}")
        private fun identifier(value: Any?): String {
            require(value is String && ID.matches(value)) { "An exact valid Gmail draft id and expected_revision are required" }
            return value
        }
        private fun json(value: String): JSONObject {
            val parser = JSONTokener(value)
            val parsed = parser.nextValue()
            require(parsed is JSONObject && parser.nextClean() == '\u0000') { "Malformed Gmail response" }
            return parsed
        }
        private fun ui(resource: Int, fallback: String, vararg arguments: Any) = ConnectorUiText(resource, arguments.toList(), fallback)
        fun operation(): ConnectorOperation = ConnectorOperation(
            OPERATION,
            "Permanently delete one exact Gmail draft and its attachments using id and expected_revision from get_draft. Always requires explicit irreversible approval; no Allow mode. Changed content, account or authorization invalidates review. Empty drafts are supported. Gmail has no atomic revision precondition; 404 readback proves only observed absence, not causation. Never retry an unknown outcome.",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("id", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 256))
                .put("expected_revision", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 256)))
                .put("required", JSONArray(listOf("id", "expected_revision"))).put("additionalProperties", false),
            write = true, displayLabel = "Permanently delete draft", autonomyAllowed = false,
            displayLabelResourceId = R.string.gmail_draft_delete_title,
            descriptionResourceId = R.string.gmail_draft_delete_description,
        )
    }
}
