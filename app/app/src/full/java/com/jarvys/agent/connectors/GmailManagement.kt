package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Generic, account-bound mailbox actions. Selection and reconciliation never authorize mutations. */
internal class GmailManagement(
    private val oauth: GoogleRestAuthorization,
    private val store: GmailManagementStore,
    private val journal: GoogleWorkspaceWriteJournal,
) {
    private class Reviewed(val owner: GmailManagement, val operation: String, val record: String,
        val account: String, val epoch: Long, val attempted: AtomicBoolean = AtomicBoolean(false))

    fun read(operation: String, args: JSONObject, token: CancellationToken): JSONObject = synchronized(LOCK) {
        when (operation) {
            SELECT -> select(args, token)
            LIST_RECORDS -> listRecords(args, token)
            GET_SELECTION -> recordPage(args, "selection", token)
            RECEIPT -> recordPage(args, "receipt", token)
            GET_LABEL -> {
                checkArgs(args, setOf("id"), setOf("id"))
                val epoch = oauth.currentAuthorizationEpoch()
                account(token, epoch)
                val requestedId = id(args.getString("id"))
                val label = get("/users/me/labels/${path(requestedId)}", token, epoch)
                check(label.optString("id") == requestedId) { "Gmail returned a different label" }
                envelope("label", labelRow(label))
            }
            else -> error("Unknown Gmail management read")
        }
    }

    fun validateAutonomy(operation: String, args: JSONObject) {
        require(operation !in IRREVERSIBLE) { "This irreversible Gmail operation requires approval" }
        if (operation in MAIL_WRITES) require(args.has("selection_id") || args.has("ids") || args.has("thread_ids") || args.has("receipt_id")) {
            "Choose explicit Gmail targets before changing mail"
        }
    }

    fun prepare(operation: String, args: JSONObject, token: CancellationToken): ConnectorWritePreparation = synchronized(LOCK) {
        require(operation in WRITES)
        val scope = requiredScope(operation)
        check(oauth.isScopeGranted(scope)) { "Enable the required Gmail permission in Conectores. No write was sent." }
        val epoch = oauth.currentAuthorizationEpoch()
        val account = account(token, epoch, scope)
        check(account(token, epoch) == account) { "Gmail read and write permissions belong to different accounts; no write was sent" }
        val record = if (operation in MAIL_WRITES) prepareMail(operation, args, account, epoch, token)
            else prepareLabel(operation, args, account, epoch, token)
        token.throwIfCancelled()
        check(epoch == oauth.currentAuthorizationEpoch()) { "Google connection changed during preparation" }
        store.put(record)
        val items = record.getJSONArray("targets")
        val title = title(operation)
        val details = mutableListOf(
            ui(R.string.gmail_management_account, "Account: $account", account),
            ui(R.string.gmail_management_count, "Fixed targets: ${items.length()}", items.length()),
            ui(R.string.gmail_management_receipt, "Receipt: ${record.getString("record_id")}", record.getString("record_id")),
        )
        val exclusions = objects(items).count { it.optString("state").startsWith("skipped_") }
        if (exclusions > 0) details += ui(R.string.gmail_management_exclusions, "Excluded missing/unsupported targets: $exclusions", exclusions)
        val targetIds = objects(items).map { it.getString("id") }
        details += ui(R.string.gmail_management_targets, "IDs: ${targetIds.joinToString(", ")}", targetIds.joinToString(", "))
        if (operation in MAIL_WRITES) {
            val (add, remove) = desiredChanges(operation, record)
            details += ui(R.string.gmail_management_changes, "Add: ${add.joinToString()} · Remove: ${remove.joinToString()}", add.joinToString(), remove.joinToString())
            details += ui(R.string.gmail_management_fixed_set, "Only collected messages are included. New arrivals are excluded. Detected concurrent changes are skipped; Gmail does not guarantee atomic check-and-write.")
        } else {
            details += ui(R.string.gmail_management_label_preview, "Label: ${record.optString("name")} · ${record.optJSONObject("body") ?: JSONObject()}", record.optString("name"), (record.optJSONObject("body") ?: JSONObject()).toString())
        }
        if (operation in IRREVERSIBLE) details += ui(R.string.gmail_management_irreversible,
            "Permanent action. It cannot be undone. This approval applies only to this account and fixed set.")
        if (operation == DELETE_LABEL) details += ui(R.string.gmail_management_delete_label_warning,
            "Deleting this label removes it from all messages in this account. Messages themselves are not deleted.")
        ConnectorWritePreparation(ApprovalSummary(title.fallback, details.map { it.fallback },
            allowAlwaysAvailable = operation !in IRREVERSIBLE, localizedTitle = title, localizedLines = details,
            compactSummary = ui(R.string.gmail_management_compact, "${title.fallback} · ${items.length()} · $account", title, items.length(), account)),
            JSONObject(args.toString()), attachment = Reviewed(this, operation, record.toString(), account, epoch))
    }

    fun execute(operation: String, preparation: ConnectorWritePreparation, token: CancellationToken): JSONObject = synchronized(LOCK) {
        val reviewed = preparation.attachment as? Reviewed ?: error("Gmail management review is missing")
        check(reviewed.owner === this && reviewed.operation == operation) { "Gmail approval does not match this action" }
        check(reviewed.epoch == oauth.currentAuthorizationEpoch()) { "Google connection changed after review" }
        check(oauth.isScopeGranted(requiredScope(operation))) { "Gmail permission is no longer available" }
        check(account(token, reviewed.epoch, requiredScope(operation)) == reviewed.account && account(token, reviewed.epoch) == reviewed.account) { "Google account changed after review" }
        check(reviewed.attempted.compareAndSet(false, true)) { "This reviewed Gmail action was already attempted; read its receipt" }
        val record = JSONObject(reviewed.record)
        // Never trust caller-mutated executionArguments or a later stored plan as authorization.
        if (record.has("resumes_receipt")) {
            val previous = requireRecord(record.getString("resumes_receipt"), "receipt", reviewed.account)
            val ids = objects(record.getJSONArray("targets")).map { it.getString("id") }.toSet()
            objects(previous.getJSONArray("targets")).filter { it.getString("id") in ids }.forEach {
                check(it.optString("state") == "pending") { "The original receipt was already resumed; inspect its current result" }
                it.put("state", "resumed").put("resumed_by", record.getString("record_id"))
            }
            saveStatus(previous)
        }
        if (operation in MAIL_WRITES) executeMail(record, reviewed.epoch, token)
        else executeLabel(record, reviewed.epoch, token)
        receiptSummary(record)
    }

    private fun select(args: JSONObject, token: CancellationToken): JSONObject {
        checkArgs(args, setOf("query", "label_ids", "include_spam_trash", "ids", "thread_ids", "selection_id", "max_pages"))
        val epoch = oauth.currentAuthorizationEpoch()
        val owner = account(token, epoch)
        val pages = integer(args, "max_pages", 1, 1, 10)
        val record = if (args.has("selection_id")) {
            require(args.keys().asSequence().all { it in setOf("selection_id", "max_pages") }) { "Continuation cannot change a selection's filters" }
            requireRecord(args.getString("selection_id"), "selection", owner).also {
                if (it.optBoolean("complete")) return selectionSummary(it)
            }
        } else {
            val sources = listOf(args.has("ids"), args.has("thread_ids"), args.has("query") || args.has("label_ids")).count { it }
            require(sources == 1) { "Choose one selection source: query/labels, ids, or thread_ids" }
            val filter = JSONObject()
            if (args.has("query")) filter.put("query", query(args.getString("query")))
            if (args.has("label_ids")) filter.put("label_ids", JSONArray(validIds(args.getJSONArray("label_ids"), 100)))
            if (args.has("include_spam_trash")) { require(args.get("include_spam_trash") is Boolean); filter.put("include_spam_trash", args.getBoolean("include_spam_trash")) }
            newRecord("selection", owner).put("filter", filter).put("ids", JSONArray()).put("seen_cursors", JSONArray())
                .put("complete", false).put("next_page_token", "").put("pages", 0).apply {
                    if (args.has("ids")) { put("ids", JSONArray(validIds(args.getJSONArray("ids"), MAX_TARGETS))); put("complete", true) }
                    if (args.has("thread_ids")) {
                        val ids = expandThreads(validIds(args.getJSONArray("thread_ids"), 100), token, epoch)
                        put("ids", JSONArray(ids)); put("complete", true)
                    }
                }
        }
        if (!record.optBoolean("complete")) {
            val ids = strings(record.getJSONArray("ids")).toMutableSet()
            val cursors = strings(record.getJSONArray("seen_cursors")).toMutableSet()
            val filter = record.getJSONObject("filter")
            repeat(pages) {
                if (record.optBoolean("complete")) return@repeat
                token.throwIfCancelled()
                val cursor = record.optString("next_page_token")
                val endpoint = "/users/me/messages?maxResults=100&" + queryParameters(filter) +
                    if (cursor.isEmpty()) "" else "&pageToken=${path(cursor)}"
                val page = get(endpoint, token, epoch)
                val incoming = objects(page.optJSONArray("messages") ?: JSONArray()).map { id(it.getString("id")) }
                check(ids.size + incoming.filterNot(ids::contains).size <= MAX_SELECTION) {
                    "Selection exceeds $MAX_SELECTION messages. Narrow the date range; this incomplete selection cannot mutate mail."
                }
                ids.addAll(incoming)
                val next = cursor(page.optString("nextPageToken"))
                check(next.isEmpty() || (next != cursor && cursors.add(next))) { "Gmail repeated a pagination cursor; selection is incomplete and cannot mutate mail" }
                record.put("ids", JSONArray(ids)).put("seen_cursors", JSONArray(cursors)).put("next_page_token", next)
                    .put("complete", next.isEmpty()).put("pages", record.optInt("pages") + 1)
                    .put("result_size_estimate", page.optLong("resultSizeEstimate").coerceAtLeast(0)).put("collected_through", System.currentTimeMillis())
                store.put(record) // Every page can resume without re-enumerating or mutating its result set.
            }
        }
        store.put(record)
        return selectionSummary(record)
    }

    private fun prepareMail(operation: String, args: JSONObject, owner: String, epoch: Long, token: CancellationToken): JSONObject {
        checkArgs(args, setOf("selection_id", "selection_offset", "max_targets", "ids", "thread_ids", "receipt_id", "add_label_ids", "remove_label_ids"))
        if (operation in setOf(MODIFY_THREADS, TRASH_THREADS, UNTRASH_THREADS)) {
            require(args.has("thread_ids") || args.has("receipt_id")) { "Thread operations require explicit thread_ids" }
            require(!args.has("ids") && !args.has("selection_id")) { "Use message operations for message selections" }
        }
        val continuing = args.has("receipt_id")
        if (continuing) require(args.keys().asSequence().all { it == "receipt_id" }) { "Resume cannot change an existing batch" }
        val prior = if (continuing) requireRecord(args.getString("receipt_id"), "receipt", owner) else null
        if (prior != null) check(prior.optString("operation") == operation) { "Receipt action does not match" }
        val selected = if (prior != null) objects(prior.getJSONArray("targets")).filter { it.optString("state") == "pending" }.map { it.getString("id") }
            else {
                val choices = listOf("selection_id", "ids", "thread_ids").count(args::has)
                require(choices == 1) { "Choose a single fixed message selection" }
                when {
                    args.has("selection_id") -> requireRecord(args.getString("selection_id"), "selection", owner).let {
                        check(it.optBoolean("complete")) { "Continue selecting all pages before changing mail" }
                        val all = strings(it.getJSONArray("ids"))
                        require(all.size <= MAX_TARGETS || args.has("max_targets")) { "This selection has ${all.size} messages; choose an explicit max_targets slice before changing mail" }
                        val offset = integer(args, "selection_offset", 0, 0, MAX_SELECTION)
                        require(offset <= all.size) { "Selection offset is invalid" }
                        all.drop(offset).take(integer(args, "max_targets", MAX_TARGETS, 1, MAX_TARGETS))
                    }
                    args.has("thread_ids") -> expandThreads(validIds(args.getJSONArray("thread_ids"), 100), token, epoch, requiredScope(operation))
                    else -> validIds(args.getJSONArray("ids"), MAX_TARGETS)
                }
            }
        if (!args.has("selection_id")) require(!args.has("selection_offset") && !args.has("max_targets"))
        require(selected.isNotEmpty()) { "There are no pending message targets; inspect the receipt before any new action" }
        require(selected.size <= MAX_TARGETS) { "A reviewed batch allows $MAX_TARGETS fixed targets; split the collected selection explicitly" }
        val add = if (prior != null) strings(prior.optJSONArray("add_label_ids")) else validLabels(args.optJSONArray("add_label_ids"))
        val remove = if (prior != null) strings(prior.optJSONArray("remove_label_ids")) else validLabels(args.optJSONArray("remove_label_ids"))
        require(add.intersect(remove.toSet()).isEmpty()) { "A label cannot be both added and removed" }
        if (operation in MODIFIES) require(add.isNotEmpty() || remove.isNotEmpty()) { "Specify label changes" }
        else require(add.isEmpty() && remove.isEmpty()) { "Label arguments are only allowed for modify operations" }
        val targets = JSONArray()
        selected.forEach { messageId ->
            token.throwIfCancelled()
            val message = try { getMessage(messageId, token, epoch, requiredScope(operation)) } catch (failure: GoogleApiException) {
                if (failure.reason != GoogleApiFailure.NOT_FOUND) throw failure
                targets.put(JSONObject().put("id", messageId).put("state", "skipped_missing"))
                return@forEach
            }
            val labels = validLabelsFromResponse(message)
            if (operation == DELETE) require("TRASH" in labels) { "Permanent deletion requires every selected message to be in TRASH" }
            targets.put(JSONObject().put("id", messageId).put("thread_id", id(message.getString("threadId")))
                .put("history_id", history(message)).put("labels", JSONArray(labels)).put("state", if ("DRAFT" in labels) "skipped_unsupported" else "pending"))
        }
        require(objects(targets).any { it.optString("state") == "pending" }) { "No eligible existing non-draft messages remain; no write was sent" }
        return newRecord("receipt", owner).put("operation", operation).put("targets", targets)
            .put("add_label_ids", JSONArray(add)).put("remove_label_ids", JSONArray(remove))
            .put("status", "prepared").apply {
                prior?.let { put("resumes_receipt", it.getString("record_id")) }
                if (args.has("selection_id")) {
                    val selection = requireRecord(args.getString("selection_id"), "selection", owner)
                    val total = selection.getJSONArray("ids").length(); val offset = args.optInt("selection_offset")
                    put("selection_id", selection.getString("record_id")).put("selection_count", total)
                        .put("selection_offset", offset).put("selection_end", offset + selected.size).put("selection_remaining", total - offset - selected.size)
                }
            }
    }

    private fun executeMail(record: JSONObject, epoch: Long, token: CancellationToken) {
        val operation = record.getString("operation")
        val targets = objects(record.getJSONArray("targets")).filter { it.optString("state") == "pending" }
        for (candidateChunk in targets.chunked(if (operation in setOf(TRASH, UNTRASH, TRASH_THREADS, UNTRASH_THREADS)) 1 else CHUNK)) {
            token.throwIfCancelled()
            check(epoch == oauth.currentAuthorizationEpoch()) { "Google connection changed; remaining targets were not sent" }
            val chunk = candidateChunk.filter { target ->
                val current = try { getMessage(target.getString("id"), token, epoch, requiredScope(operation)) } catch (failure: GoogleApiException) {
                    if (failure.reason != GoogleApiFailure.NOT_FOUND) throw failure
                    target.put("state", "skipped_missing")
                    return@filter false
                }
                val unchanged = history(current) == target.getString("history_id") &&
                    validLabelsFromResponse(current).toSet() == strings(target.getJSONArray("labels")).toSet() &&
                    (operation != DELETE || "TRASH" in validLabelsFromResponse(current))
                if (!unchanged) target.put("state", "skipped_changed")
                unchanged
            }
            saveStatus(record)
            if (chunk.isEmpty()) continue
            val overlapping = store.records().filter { it.optString("kind") == "receipt" &&
                it.optString("account") == record.getString("account") && it.optString("record_id") != record.getString("record_id") &&
                it.optString("operation") in MAIL_WRITES }
                .flatMap { objects(it.optJSONArray("targets") ?: JSONArray()) }
                .any { other -> other.optString("state") in setOf("unknown", "accepted", "dispatched") &&
                    chunk.any { it.getString("id") == other.optString("id") } }
            check(!overlapping) { "A selected message belongs to an unresolved Gmail write. Reconcile that receipt before another mutation." }
            val ids = chunk.map { it.getString("id") }
            val change = desiredChanges(operation, record)
            val body = JSONObject().put("ids", JSONArray(ids))
            if (operation != DELETE) body.put("addLabelIds", JSONArray(change.first)).put("removeLabelIds", JSONArray(change.second))
            val hash = intent(record.getString("account"), operation, ids, change.first, change.second)
            check(!journal.isUncertain(hash)) { "An equivalent Gmail batch has an unknown result. Reconcile its receipt; do not repeat it." }
            chunk.forEach { it.put("state", "dispatched").put("intent_hash", hash) }
            retainIntent(record, hash)
            saveStatus(record) // Persist exact account, targets and action before dispatch, including process death.
            journal.reserve(hash)
            val response = try {
                oauth.requestCancellable(requiredScope(operation), "POST", GoogleRestEndpoints.GMAIL +
                    when(operation) {
                        DELETE -> "/users/me/messages/batchDelete"
                        TRASH, TRASH_THREADS -> "/users/me/messages/${path(ids.single())}/trash"
                        UNTRASH, UNTRASH_THREADS -> "/users/me/messages/${path(ids.single())}/untrash"
                        else -> "/users/me/messages/batchModify"
                    }, if (operation in setOf(TRASH, UNTRASH, TRASH_THREADS, UNTRASH_THREADS)) null else body.toString(),
                    token = token, expectedAuthorizationEpoch = epoch)
            } catch (failure: Exception) {
                val preDispatch = failure is GooglePreDispatchAuthorizationException
                chunk.forEach { it.put("state", if (preDispatch) "rejected" else "unknown") }
                if (preDispatch) resolveIntent(record, hash)
                saveStatus(record)
                if (failure is java.util.concurrent.CancellationException) throw failure
                break
            }
            if (response.status !in 200..299) {
                val uncertain = response.status >= 500 || response.status in setOf(408, 429)
                chunk.forEach { it.put("state", if (uncertain) "unknown" else "rejected").put("http_status", response.status) }
                if (!uncertain) resolveIntent(record, hash)
                saveStatus(record)
                break
            }
            // Both API methods have empty successful bodies. Verify each ID; do not parse or invent a success count.
            chunk.forEach { it.put("state", "accepted") }
            saveStatus(record)
            verifyTargets(record, chunk, epoch, token)
            if (chunk.all { it.optString("state") == "verified" }) resolveIntent(record, hash)
            saveStatus(record)
        }
    }

    private fun prepareLabel(operation: String, args: JSONObject, owner: String, epoch: Long, token: CancellationToken): JSONObject {
        checkArgs(args, setOf("id", "name", "label_list_visibility", "message_list_visibility", "color"))
        val body = JSONObject()
        if (args.has("name")) {
            val name = args.getString("name").trim()
            require(name.length in 1..225 && name.none(Char::isISOControl)) { "Invalid label name" }
            require(name.uppercase() !in SYSTEM_LABELS && !name.uppercase().startsWith("CATEGORY_")) { "System label names are reserved" }
            body.put("name", name)
        }
        for ((input, output, allowed) in listOf(
            Triple("label_list_visibility", "labelListVisibility", setOf("labelShow", "labelShowIfUnread", "labelHide")),
            Triple("message_list_visibility", "messageListVisibility", setOf("show", "hide")))) {
            if (args.has(input)) { val value = args.getString(input); require(value in allowed); body.put(output, value) }
        }
        if (args.has("color")) {
            val colors = args.getJSONObject("color"); checkArgs(colors, setOf("textColor", "backgroundColor"))
            require(colors.length() == 2 && colors.has("textColor") && colors.has("backgroundColor") &&
                colors.keys().asSequence().all { colors.getString(it).lowercase() in LABEL_COLORS }) { "Both label colors must use Gmail's supported palette" }
            body.put("color", JSONObject().put("textColor", colors.getString("textColor").lowercase())
                .put("backgroundColor", colors.getString("backgroundColor").lowercase()))
        }
        val targetId: String
        var name = body.optString("name")
        var baseline = JSONObject()
        if (operation == CREATE_LABEL) { require(!args.has("id") && body.has("name")); targetId = "new_label" }
        else {
            targetId = id(args.getString("id"))
            baseline = get("/users/me/labels/${path(targetId)}", token, epoch, requiredScope(operation))
            check(baseline.optString("id") == targetId) { "Gmail returned a different label" }
            require(baseline.optString("type") == "user") { "System labels cannot be renamed or deleted" }
            name = baseline.optString("name")
            if (operation == DELETE_LABEL) require(args.length() == 1) { "Delete label accepts only its verified id" }
            else require(body.length() > 0) { "No label changes supplied" }
        }
        return newRecord("receipt", owner).put("operation", operation).put("name", name).put("body", body)
            .put("baseline", labelRow(baseline)).put("targets", JSONArray().put(JSONObject().put("id", targetId).put("state", "pending")))
            .put("status", "prepared")
    }

    private fun executeLabel(record: JSONObject, epoch: Long, token: CancellationToken) {
        val operation = record.getString("operation")
        val target = record.getJSONArray("targets").getJSONObject(0)
        val targetId = target.getString("id")
        if (operation != CREATE_LABEL) {
            val current = labelRow(get("/users/me/labels/${path(targetId)}", token, epoch, requiredScope(operation)))
            check(current.optString("id") == targetId) { "Gmail returned a different label" }
            if (canonical(current) != canonical(record.getJSONObject("baseline"))) {
                target.put("state", "skipped_changed"); saveStatus(record); return
            }
        }
        val unresolved = store.records().filter { it.optString("kind") == "receipt" && it.optString("account") == record.getString("account") &&
            it.optString("record_id") != record.getString("record_id") && it.optString("operation") in setOf(CREATE_LABEL, UPDATE_LABEL, DELETE_LABEL) }
            .any { other -> objects(other.optJSONArray("targets") ?: JSONArray()).any { target ->
                target.optString("state") in setOf("unknown", "dispatched", "accepted") &&
                    (target.optString("id") == targetId || (operation == CREATE_LABEL && other.optString("name") == record.optString("name")))
            } }
        check(!unresolved) { "An equivalent label has an unresolved write; reconcile its receipt before another mutation" }
        val body = canonical(record.getJSONObject("body"))
        val hash = GmailContent.digest((record.getString("account") + "\n" + operation + "\n" + targetId + "\n" + body).toByteArray())
        check(!journal.isUncertain(hash)) { "An equivalent label write is unresolved; inspect its receipt before retrying" }
        target.put("state", "dispatched").put("intent_hash", hash); retainIntent(record, hash); saveStatus(record); journal.reserve(hash)
        val response = try {
            oauth.requestCancellable(GoogleOAuthProtocol.GMAIL_LABELS,
                when(operation) { CREATE_LABEL -> "POST"; DELETE_LABEL -> "DELETE"; else -> "PATCH" },
                GoogleRestEndpoints.GMAIL + "/users/me/labels" + if (operation == CREATE_LABEL) "" else "/${path(targetId)}",
                if (operation == DELETE_LABEL) null else body, token = token, expectedAuthorizationEpoch = epoch)
        } catch (failure: Exception) {
            val preDispatch = failure is GooglePreDispatchAuthorizationException
            target.put("state", if (preDispatch) "rejected" else "unknown")
            if (preDispatch) resolveIntent(record, hash)
            saveStatus(record)
            if (failure is java.util.concurrent.CancellationException) throw failure
            return
        }
        if (response.status !in 200..299) {
            val uncertain = response.status >= 500 || response.status in setOf(408, 429)
            target.put("state", if (uncertain) "unknown" else "rejected").put("http_status", response.status)
            if (!uncertain) resolveIntent(record, hash)
        } else if (operation == DELETE_LABEL) target.put("state", "accepted")
        else {
            val result = runCatching { JSONObject(response.body) }.getOrNull()
            val returned = result?.optString("id").orEmpty()
            if (!validId(returned) || result?.optString("type") != "user" || (operation != CREATE_LABEL && returned != targetId)) target.put("state", "unknown")
            else target.put("id", returned).put("state", "accepted")
        }
        saveStatus(record)
        if (target.optString("state") == "accepted") {
            verifyLabel(record, epoch, token)
            if (target.optString("state") == "verified") resolveIntent(record, hash)
            saveStatus(record)
        }
    }

    private fun listRecords(args: JSONObject, token: CancellationToken): JSONObject {
        checkArgs(args, setOf("offset", "max_results"))
        val owner = account(token, oauth.currentAuthorizationEpoch())
        val records = store.records().filter { it.optString("account") == owner }.sortedByDescending { it.optLong("created_at") }
        val offset = integer(args, "offset", 0, 0, JsonGmailManagementStore.MAX_RECORDS)
        val limit = integer(args, "max_results", 25, 1, 50)
        require(offset <= records.size) { "Checkpoint history changed; restart at offset zero" }
        val rows = records.drop(offset).take(limit).map { record ->
            if (record.optString("kind") == "selection") selectionSummary(record) else receiptSummary(record)
        }
        return JSONObject().put("untrusted_content", true).put("account", owner).put("items", JSONArray(rows))
            .put("has_more", offset + limit < records.size).put("next_offset", minOf(offset + limit, records.size))
            .put("count", records.size).put("note", "Private checkpoint history is bounded. Unknown effects remain retained. Read a receipt to reconcile; listing never replays writes.")
    }

    private fun recordPage(args: JSONObject, kind: String, token: CancellationToken): JSONObject {
        checkArgs(args, setOf("id", "offset", "max_results", "reconcile"), setOf("id"))
        val epoch = oauth.currentAuthorizationEpoch()
        val owner = account(token, epoch)
        val record = requireRecord(args.getString("id"), kind, owner)
        if (args.has("reconcile")) require(args.get("reconcile") is Boolean)
        if (kind == "receipt" && args.optBoolean("reconcile")) {
            check(account(token, epoch, requiredScope(record.getString("operation"))) == owner) { "Gmail read and write permissions belong to different accounts" }
            val candidates = objects(record.getJSONArray("targets")).filter { it.optString("state") in setOf("accepted", "unknown", "dispatched") }
            if (record.getString("operation") in MAIL_WRITES) verifyTargets(record, candidates, epoch, token)
            else if (candidates.isNotEmpty()) verifyLabel(record, epoch, token)
            objects(record.getJSONArray("targets")).map { it.optString("intent_hash") }.filter(String::isNotEmpty).distinct().forEach { hash ->
                if (objects(record.getJSONArray("targets")).filter { it.optString("intent_hash") == hash }
                        .all { it.optString("state") in setOf("verified", "rejected") }) resolveIntent(record, hash)
            }
            saveStatus(record)
        }
        val offset = integer(args, "offset", 0, 0, MAX_SELECTION)
        val limit = integer(args, "max_results", 25, 1, 50)
        val rows = if (kind == "selection") strings(record.getJSONArray("ids")).map { JSONObject().put("id", it) }
            else objects(record.getJSONArray("targets")).map { item -> JSONObject().put("id", item.getString("id")).put("state", item.getString("state"))
                .apply { if (item.has("resumed_by")) put("resumed_by", item.getString("resumed_by")) } }
        require(offset <= rows.size) { "Offset is outside this fixed record" }
        val output = if (kind == "selection") selectionSummary(record) else receiptSummary(record)
        return output.put("items", JSONArray(rows.drop(offset).take(limit))).put("has_more", offset + limit < rows.size)
            .put("next_offset", minOf(offset + limit, rows.size))
    }

    private fun verifyTargets(record: JSONObject, targets: List<JSONObject>, epoch: Long, token: CancellationToken) {
        val op = record.getString("operation")
        val changes = desiredChanges(op, record)
        for (target in targets) {
            token.throwIfCancelled()
            val response = oauth.requestCancellable(requiredScope(op), "GET", GoogleRestEndpoints.GMAIL +
                "/users/me/messages/${path(target.getString("id"))}?format=minimal", token = token, expectedAuthorizationEpoch = epoch)
            val verified = if (op == DELETE) response.status == 404
                else if (response.status in 200..299) runCatching {
                    val message = JSONObject(response.body)
                    check(message.getString("id") == target.getString("id"))
                    val labels = validLabelsFromResponse(message)
                    changes.first.all(labels::contains) && changes.second.none(labels::contains)
                }.getOrDefault(false) else false
            // This proves observed desired state, not that this request caused it or prior existence.
            target.put("state", if (verified) "verified" else "unknown")
        }
        saveStatus(record)
    }

    private fun verifyLabel(record: JSONObject, epoch: Long, token: CancellationToken) {
        val target = record.getJSONArray("targets").getJSONObject(0)
        if (target.optString("id") == "new_label") return // Never guess the identity of an ambiguous create.
        val response = oauth.requestCancellable(requiredScope(record.getString("operation")), "GET", GoogleRestEndpoints.GMAIL +
            "/users/me/labels/${path(target.getString("id"))}", token = token, expectedAuthorizationEpoch = epoch)
        val verified = if (record.getString("operation") == DELETE_LABEL) response.status == 404
            else if (response.status in 200..299) runCatching {
                val current = JSONObject(response.body); val body = record.getJSONObject("body")
                current.getString("id") == target.getString("id") && body.keys().asSequence().all { canonical(current.opt(it)) == canonical(body.get(it)) }
            }.getOrDefault(false) else false
        target.put("state", if (verified) "verified" else "unknown")
    }

    private fun getMessage(id: String, token: CancellationToken, epoch: Long, scope: String = GoogleOAuthProtocol.GMAIL_READ): JSONObject =
        get("/users/me/messages/${path(id)}?format=minimal", token, epoch, scope).also { check(it.getString("id") == id) { "Gmail returned a different message" } }
    private fun get(endpoint: String, token: CancellationToken, epoch: Long, scope: String = GoogleOAuthProtocol.GMAIL_READ): JSONObject {
        val response = oauth.requestCancellable(scope, "GET", GoogleRestEndpoints.GMAIL + endpoint,
            token = token, expectedAuthorizationEpoch = epoch)
        GoogleRestEndpoints.requireSuccess(response)
        return JSONObject(response.body)
    }
    private fun account(token: CancellationToken, epoch: Long, scope: String = GoogleOAuthProtocol.GMAIL_READ): String =
        oauth.verifyGmailAccount(scope, null, token, epoch)
    private fun requireRecord(id: String, kind: String, account: String): JSONObject {
        val value = store.get(id) ?: error("Gmail checkpoint not found")
        check(value.optString("kind") == kind && value.optString("account") == account) { "Gmail checkpoint belongs to a different account or kind" }
        return value
    }
    private fun expandThreads(ids: List<String>, token: CancellationToken, epoch: Long, scope: String = GoogleOAuthProtocol.GMAIL_READ): List<String> {
        val messages = linkedSetOf<String>()
        for (threadId in ids) {
            val thread = get("/users/me/threads/${path(threadId)}?format=minimal", token, epoch, scope)
            check(thread.getString("id") == threadId) { "Gmail returned a different thread" }
            for (message in objects(thread.optJSONArray("messages") ?: JSONArray())) messages += id(message.getString("id"))
            require(messages.size <= MAX_TARGETS) { "Expanded threads exceed the fixed batch limit" }
        }
        return messages.toList()
    }
    private fun desiredChanges(operation: String, record: JSONObject): Pair<List<String>, List<String>> = when(operation) {
        TRASH, TRASH_THREADS -> listOf("TRASH") to emptyList()
        UNTRASH, UNTRASH_THREADS -> emptyList<String>() to listOf("TRASH")
        else -> strings(record.optJSONArray("add_label_ids")) to strings(record.optJSONArray("remove_label_ids"))
    }
    private fun retainIntent(record: JSONObject, hash: String) {
        val retained = strings(record.optJSONArray("journal_intents")).toMutableSet()
        retained.add(hash)
        record.put("journal_intents", JSONArray(retained))
    }
    private fun resolveIntent(record: JSONObject, hash: String) {
        val unresolved = strings(record.optJSONArray("unresolved_intents")).toMutableSet()
        val retained = strings(record.optJSONArray("journal_intents")).toMutableSet()
        if (journal.resolve(hash)) { unresolved.remove(hash); retained.remove(hash) }
        else { unresolved.add(hash); retained.add(hash) }
        record.put("unresolved_intents", JSONArray(unresolved)).put("journal_intents", JSONArray(retained))
    }
    private fun saveStatus(record: JSONObject) {
        val states = objects(record.getJSONArray("targets")).map { it.optString("state") }
        record.put("status", when {
            (record.optJSONArray("unresolved_intents")?.length() ?: 0) > 0 -> "needs_marker_reconciliation"
            states.isNotEmpty() && states.all { it == "verified" } -> "verified"
            states.any { it in setOf("unknown", "dispatched", "accepted") } -> "needs_reconciliation"
            states.any { it == "pending" } -> "partial"
            else -> "completed_with_exclusions"
        })
        store.put(record)
    }
    private fun selectionSummary(record: JSONObject) = JSONObject().put("untrusted_content", true).put("kind", "selection").put("selection_id", record.getString("record_id"))
        .put("account", record.getString("account")).put("count", record.getJSONArray("ids").length()).put("complete", record.optBoolean("complete"))
        .put("collection_started_at", record.optLong("created_at")).put("collected_through", record.optLong("collected_through", record.optLong("created_at")))
        .put("can_mutate", record.optBoolean("complete")).put("pages", record.optInt("pages"))
        .put("result_size_estimate", record.optLong("result_size_estimate"))
        .put("note", "Collected fixed IDs, not a point-in-time mailbox snapshot. Continue selection_id until complete; estimates are not exact counts.")
    private fun receiptSummary(record: JSONObject): JSONObject {
        val targets = objects(record.getJSONArray("targets")); val counts = JSONObject()
        targets.groupingBy { it.optString("state") }.eachCount().forEach { (state, count) -> counts.put(state, count) }
        return JSONObject().put("untrusted_content", true).put("kind", "receipt").put("receipt_id", record.getString("record_id")).put("account", record.getString("account"))
            .put("operation", record.getString("operation")).put("status", record.optString("status")).put("count", targets.size).put("counts", counts)
            .put("resumes_receipt", record.optString("resumes_receipt"))
            .put("safety_marker_pending", (record.optJSONArray("unresolved_intents")?.length() ?: 0) > 0)
            .put("journal_marker_retained", (record.optJSONArray("journal_intents")?.length() ?: 0) > 0)
            .apply { for (key in listOf("selection_id", "selection_count", "selection_offset", "selection_end", "selection_remaining")) if (record.has(key)) put(key, record.get(key)) }
            .put("note", "Verified means the desired state was observed, not that Gmail proves this request caused it. Read receipt with reconcile=true for uncertain targets; never replay them.")
    }
    private fun newRecord(kind: String, account: String) = JSONObject().put("record_id", "$kind:${UUID.randomUUID()}")
        .put("kind", kind).put("account", account).put("created_at", System.currentTimeMillis())
    private fun intent(account: String, operation: String, ids: List<String>, add: List<String>, remove: List<String>) = GmailContent.digest(
        (account + "\n" + operation + "\n" + ids.sorted().joinToString(",") + "\n" + add.sorted().joinToString(",") + "\n" + remove.sorted().joinToString(",")).toByteArray())
    private fun labelRow(label: JSONObject) = JSONObject().apply {
        for (key in listOf("id", "name", "type", "labelListVisibility", "messageListVisibility", "color")) if (label.has(key)) put(key, label.get(key))
    }
    private fun envelope(source: String, item: JSONObject) = JSONObject().put("untrusted_content", true).put("source", "gmail.$source").put("items", JSONArray().put(item))

    companion object {
        const val SELECT = "select_messages"; const val GET_SELECTION = "get_selection"; const val RECEIPT = "get_batch_receipt"; const val LIST_RECORDS = "list_management_records"
        const val MODIFY = "modify_messages"; const val TRASH = "trash_messages"; const val UNTRASH = "untrash_messages"
        const val MODIFY_THREADS = "modify_threads"; const val TRASH_THREADS = "trash_threads"; const val UNTRASH_THREADS = "untrash_threads"
        const val DELETE = "delete_messages"; const val GET_LABEL = "get_label"; const val CREATE_LABEL = "create_label"
        const val UPDATE_LABEL = "update_label"; const val DELETE_LABEL = "delete_label"
        val MODIFIES = setOf(MODIFY, MODIFY_THREADS)
        val MAIL_WRITES = setOf(MODIFY, TRASH, UNTRASH, MODIFY_THREADS, TRASH_THREADS, UNTRASH_THREADS, DELETE)
        val WRITES = MAIL_WRITES + setOf(CREATE_LABEL, UPDATE_LABEL, DELETE_LABEL)
        val READS = setOf(SELECT, GET_SELECTION, RECEIPT, LIST_RECORDS, GET_LABEL)
        val IRREVERSIBLE = setOf(DELETE, DELETE_LABEL)
        private val LOCK = Any()
        private const val MAX_TARGETS = 1000
        private const val MAX_SELECTION = 10000
        private const val CHUNK = 100
        // Official users.labels Color palette; it is data from the API contract, not mailbox classification.
        private val LABEL_COLORS = ("#000000 #434343 #666666 #999999 #cccccc #efefef #f3f3f3 #ffffff #fb4c2f #ffad47 #fad165 #16a766 #43d692 #4a86e8 #a479e2 #f691b3 " +
            "#f6c5be #ffe6c7 #fef1d1 #b9e4d0 #c6f3de #c9daf8 #e4d7f5 #fcdee8 #efa093 #ffd6a2 #fce8b3 #89d3b2 #a0eac9 #a4c2f4 #d0bcf1 #fbc8d9 " +
            "#e66550 #ffbc6b #fcda83 #44b984 #68dfa9 #6d9eeb #b694e8 #f7a7c0 #cc3a21 #eaa041 #f2c960 #149e60 #3dc789 #3c78d8 #8e63ce #e07798 " +
            "#ac2b16 #cf8933 #d5ae49 #0b804b #2a9c68 #285bac #653e9b #b65775 #822111 #a46a21 #aa8831 #076239 #1a764d #1c4587 #41236d #83334c " +
            "#464646 #e7e7e7 #0d3472 #b6cff5 #0d3b44 #98d7e4 #3d188e #e3d7ff #711a36 #fbd3e0 #8a1c0a #f2b2a8 #7a2e0b #ffc8af #7a4706 #ffdeb5 " +
            "#594c05 #fbe983 #684e07 #fdedc1 #0b4f30 #b3efd3 #04502e #a2dcc1 #c2c2c2 #4986e7 #2da2bb #b99aff #994a64 #f691b2 #ff7537 #ffad46 " +
            "#662e37 #ebdbde #cca6ac #094228 #42d692 #16a765").split(' ').toSet()
        private val SYSTEM_LABELS = setOf("INBOX", "SPAM", "TRASH", "UNREAD", "STARRED", "IMPORTANT", "SENT", "DRAFT", "CHAT", "ALL")
        fun requiredScope(operation: String) = when(operation) { DELETE -> GoogleOAuthProtocol.GMAIL_FULL; CREATE_LABEL, UPDATE_LABEL, DELETE_LABEL -> GoogleOAuthProtocol.GMAIL_LABELS; in WRITES -> GoogleOAuthProtocol.GMAIL_MODIFY; else -> GoogleOAuthProtocol.GMAIL_READ }
        private fun canonical(value: Any?): String = when(value) {
            is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
            null, JSONObject.NULL -> "null"
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
        private fun objects(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }
        private fun strings(array: JSONArray?) = if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
        private fun validId(value: String) = value.length in 1..256 && value.all { it.isLetterOrDigit() && it.code < 128 || it in "_-" }
        private fun id(value: String): String { require(validId(value)) { "Invalid Gmail identifier" }; return value }
        private fun path(value: String) = GoogleRestEndpoints.encode(value)
        private fun cursor(value: String): String { require(value.length <= 2048 && value.none(Char::isISOControl)); return value }
        private fun history(message: JSONObject): String = message.optString("historyId").also { require(it.isNotEmpty() && it.length <= 40 && it.all(Char::isDigit)) { "Message history revision is missing" } }
        private fun validIds(array: JSONArray, maximum: Int): List<String> {
            require(array.length() in 1..maximum) { "Target count must be 1..$maximum" }
            require((0 until array.length()).all { array.get(it) is String }) { "Gmail identifiers must be strings" }
            val result = strings(array).map(::id); require(result.distinct().size == result.size) { "Duplicate Gmail identifiers" }; return result
        }
        private fun validLabels(array: JSONArray?): List<String> {
            if (array == null || array.length() == 0) return emptyList()
            val labels = validIds(array, 100)
            require(labels.none { it in setOf("SENT", "DRAFT", "CHAT", "ALL") }) { "This system label cannot be manually applied or removed" }
            return labels
        }
        private fun validLabelsFromResponse(message: JSONObject): List<String> {
            val array = message.optJSONArray("labelIds") ?: JSONArray()
            require(array.length() <= 1000) { "Message has too many labels to review" }
            return strings(array).map(::id)
        }
        private fun checkArgs(args: JSONObject, allowed: Set<String>, required: Set<String> = emptySet()) {
            require(args.keys().asSequence().all(allowed::contains) && required.all(args::has)) { "Unsupported or missing Gmail management argument" }
            args.keys().asSequence().forEach { key ->
                val value = args.get(key)
                require(when(key) {
                    "include_spam_trash", "reconcile" -> value is Boolean
                    "offset", "max_results", "max_pages", "selection_offset", "max_targets" -> value is Int || value is Long
                    "ids", "thread_ids", "label_ids", "add_label_ids", "remove_label_ids" -> value is JSONArray
                    "color" -> value is JSONObject
                    else -> value is String
                }) { "Invalid type for Gmail argument: $key" }
            }
        }
        private fun integer(args: JSONObject, name: String, default: Int, min: Int, max: Int): Int {
            if (!args.has(name)) return default
            require(args.get(name) is Int || args.get(name) is Long) { "$name must be an integer" }
            val value = args.getLong(name); require(value in min.toLong()..max.toLong()) { "$name must be $min..$max" }; return value.toInt()
        }
        private fun query(value: String): String { require(value.length <= 512 && '\u0000' !in value); return value }
        fun queryParameters(args: JSONObject): String = buildList {
            add("q=${path(query(args.optString("query")))}")
            if (args.has("include_spam_trash")) { require(args.get("include_spam_trash") is Boolean); add("includeSpamTrash=${args.getBoolean("include_spam_trash")}") }
            args.optJSONArray("label_ids")?.let { labels -> validIds(labels, 100).forEach { add("labelIds=${path(it)}") } }
        }.joinToString("&")
        private fun ui(resource: Int, fallback: String, vararg args: Any) = ConnectorUiText(resource, args.toList(), fallback)
        private fun title(op: String) = ui(labelResource(op), op.replace('_', ' '))
        fun labelResource(op: String): Int = when(op) {
            LIST_RECORDS -> R.string.gmail_management_records
            SELECT -> R.string.gmail_management_select; GET_SELECTION -> R.string.gmail_management_selection; RECEIPT -> R.string.gmail_management_batch_receipt
            MODIFY -> R.string.gmail_management_modify; TRASH -> R.string.gmail_management_trash; UNTRASH -> R.string.gmail_management_untrash
            MODIFY_THREADS -> R.string.gmail_management_modify_threads; TRASH_THREADS -> R.string.gmail_management_trash_threads; UNTRASH_THREADS -> R.string.gmail_management_untrash_threads
            DELETE -> R.string.gmail_management_delete; GET_LABEL -> R.string.gmail_management_get_label; CREATE_LABEL -> R.string.gmail_management_create_label
            UPDATE_LABEL -> R.string.gmail_management_update_label; DELETE_LABEL -> R.string.gmail_management_delete_label; else -> 0
        }
    }
}
