package com.jarvys.agent.connectors

import org.json.JSONArray
import org.json.JSONObject

/** Small composable primitives; no keyword-based mailbox classification or task-specific workflow. */
internal object GmailManagementCatalog {
    private fun text(max: Int = 256) = JSONObject().put("type", "string").put("maxLength", max)
    private fun number(min: Int, max: Int) = JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)
    private fun ids(max: Int) = JSONObject().put("type", "array").put("items", text()).put("minItems", 1).put("maxItems", max).put("uniqueItems", true)
    private fun schema(properties: JSONObject, required: List<String> = emptyList()) = JSONObject().put("type", "object")
        .put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false)
    private fun choices(vararg names: String) = text().put("enum", JSONArray(names.toList()))
    private fun selectSchema() = schema(JSONObject().put("query", text(512)).put("label_ids", ids(100))
        .put("include_spam_trash", JSONObject().put("type", "boolean")).put("ids", ids(1000)).put("thread_ids", ids(100))
        .put("selection_id", text()).put("max_pages", number(1, 10)))
    private fun recordSchema() = schema(JSONObject().put("id", text()).put("offset", number(0, 10000))
        .put("max_results", number(1, 50)).put("reconcile", JSONObject().put("type", "boolean")), listOf("id"))
    private fun actionSchema(modify: Boolean, threads: Boolean): JSONObject {
        val props = JSONObject().put("receipt_id", text())
        if (threads) props.put("thread_ids", ids(100)) else props.put("ids", ids(1000)).put("selection_id", text())
            .put("selection_offset", number(0, 10000)).put("max_targets", number(1, 1000))
        if (modify) props.put("add_label_ids", ids(100)).put("remove_label_ids", ids(100))
        return schema(props)
    }
    private fun labelSchema(create: Boolean) = schema(JSONObject().apply {
        if (!create) put("id", text())
        put("name", text(225)).put("label_list_visibility", choices("labelShow", "labelShowIfUnread", "labelHide"))
            .put("message_list_visibility", choices("show", "hide"))
            .put("color", schema(JSONObject().put("textColor", text(7)).put("backgroundColor", text(7)), listOf("textColor", "backgroundColor")))
    }, if (create) listOf("name") else listOf("id"))
    fun operations(): List<ConnectorOperation> = listOf(
        op(GmailManagement.LIST_RECORDS, "Find this account's retained selections and batch receipts after cancellation or app restart. List/read/reconcile never authorize or replay writes.", schema(JSONObject().put("offset", number(0, 64)).put("max_results", number(1, 50)))),
        op(GmailManagement.SELECT, "Collect fixed message IDs from query/labels or exact message/thread IDs. Query pages are checkpointed; continue selection_id until complete. It never changes mail. At most 10,000 IDs per collected selection; use explicit date ranges for larger mailboxes.", selectSchema()),
        op(GmailManagement.GET_SELECTION, "Page the fixed collected IDs and completion state. Never mutate an incomplete selection. A collected set is not a point-in-time mailbox snapshot.", recordSchema()),
        op(GmailManagement.RECEIPT, "Read a durable batch receipt. reconcile=true only checks observed state; it never replays writes. Resume only pending targets through the original action using receipt_id.", recordSchema()),
        op(GmailManagement.MODIFY, "Add/remove allowed Gmail label IDs on exact messages or a completed selection slice. Covers read/unread, archive/inbox, stars, importance, categories and custom labels. No classification rules are hardcoded.", actionSchema(true, false), true),
        op(GmailManagement.TRASH, "Move fixed selected messages to Gmail Trash. Reversible; uses messages.trash and verifies the TRASH label. Does not permanently delete.", actionSchema(false, false), true),
        op(GmailManagement.UNTRASH, "Remove fixed selected messages from Trash using messages.untrash. Does not promise restoration of a previous folder or add INBOX explicitly; use modify_messages for a chosen destination.", actionSchema(false, false), true),
        op(GmailManagement.MODIFY_THREADS, "Expand exact threads to their existing message IDs and add/remove labels on those fixed members. New arrivals are excluded; draft members are not eligible.", actionSchema(true, true), true),
        op(GmailManagement.TRASH_THREADS, "Expand exact threads and move only their fixed existing messages to Trash; new arrivals are excluded.", actionSchema(false, true), true),
        op(GmailManagement.UNTRASH_THREADS, "Expand exact threads and restore only their fixed existing messages from Trash; new arrivals are excluded.", actionSchema(false, true), true),
        op(GmailManagement.DELETE, "Permanently delete an exact fixed set of messages currently in TRASH. Requires optional full Gmail scope and one explicit irreversible approval for this account/batch. Never automatic. New arrivals and concurrent changes detected before dispatch are excluded; Gmail offers no atomic precondition.", actionSchema(false, false), true),
        op(GmailManagement.GET_LABEL, "Read one Gmail label's name, type, visibility and color using a verified label ID.", schema(JSONObject().put("id", text()), listOf("id"))),
        op(GmailManagement.CREATE_LABEL, "Create a user label with optional visibility and Gmail-supported colors. Does not label messages or send mail.", labelSchema(true), true),
        op(GmailManagement.UPDATE_LABEL, "Patch a user label's name, visibility or supported colors. Rechecks the exact label before applying the reviewed changes; reserved system labels cannot be edited.", labelSchema(false), true),
        op(GmailManagement.DELETE_LABEL, "Permanently remove one user label and all of its assignments across the account, without deleting messages. Always requires explicit approval; no automatic mode.", schema(JSONObject().put("id", text()), listOf("id")), true),
    )
    private fun op(name: String, description: String, schema: JSONObject, write: Boolean = false) = ConnectorOperation(
        name, description, schema, write, displayLabel = name.replace('_', ' '),
        autonomyAllowed = name !in GmailManagement.IRREVERSIBLE, displayLabelResourceId = GmailManagement.labelResource(name))
}
