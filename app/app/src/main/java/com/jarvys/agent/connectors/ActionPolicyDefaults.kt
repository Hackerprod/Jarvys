package com.jarvys.agent.connectors

import java.util.concurrent.atomic.AtomicBoolean

/** Local action decisions are independent of tool selection and real platform/provider grants. */
enum class ActionPolicyOrigin { CATALOG_DEFAULT, INHERITED_SERVER_DEFAULT, USER, LEGACY_UNKNOWN, INVALID }

/** A descriptor never makes an unavailable operation available or broadens a capability scope. */
data class ActionPolicyDescriptor(val connectorId: String, val operationName: String) {
    val defaultPolicy: AutonomyPolicy get() = AutonomyPolicy.ALLOW
}

/** All current and future registered action families use this one absent-value default. */
object ActionPolicyDefaults {
    const val METADATA_VERSION = 1
    private val lock = Any()
    private val actions = linkedMapOf<Pair<String, String>, ActionPolicyDescriptor>()

    init {
        register("calendar", listOf("search_events", "create_event"))
        register("contacts", listOf("search", "get_contact_detail", "create_contact"))
        register("phone", listOf("open_dialer", "place_call"))
        register("email", listOf("compose_email"))
        register("sms_draft", listOf("send_sms"))
        register("files", listOf("pick_document", "create_document"))
        register("location", listOf("get_current_location", "reverse_geocode_current_location"))
        register("notifications", listOf("list_recent", "dismiss_notification", "open_notification", "reply_notification"))
        register("sms", listOf("list_sms"))
        register("call_log", listOf("list_calls"))
        register("gmail", listOf("search_messages", "get_message", "list_labels", "create_draft", "send_message",
            "get_thread", "reply_message", "list_drafts", "get_draft", "update_draft", "send_draft", "get_attachment",
            "delete_draft", "list_management_records", "select_messages", "get_selection", "get_batch_receipt", "get_label",
            "modify_messages", "trash_messages", "untrash_messages", "modify_threads", "trash_threads", "untrash_threads",
            "delete_messages", "create_label", "update_label", "delete_label"))
        register("drive", listOf("search_files", "get_file_metadata", "read_file", "create_file", "download_file",
            "export_file", "update_file", "create_folder"))
        register("native", listOf("memory.view", "memory.modify", "memory.delete", "memory.restore", "memory.clear",
            "memory.export", "memory.share_personal", "memory.reflection.run", "bots.create", "bots.mascot.compile",
            "factory.identity.create", "factory.identity.backup.export", "factory.identity.backup.import", "factory.identity.reconcile"))
    }

    fun descriptor(connectorId: String, operationName: String): ActionPolicyDescriptor = synchronized(lock) {
        require(connectorId.isNotEmpty() && operationName.isNotEmpty()) { "Action identity is required" }
        actions.getOrPut(connectorId to operationName) { ActionPolicyDescriptor(connectorId, operationName) }
    }

    fun register(connectorId: String, operationNames: Collection<String>): List<ActionPolicyDescriptor> =
        operationNames.map { descriptor(connectorId, it) }

    fun registeredActions(): List<ActionPolicyDescriptor> = synchronized(lock) { actions.values.toList() }
}

/** An opaque, one-use admission candidate. It is not itself approval or a completed-effect receipt. */
class ActionPolicySnapshot internal constructor(
    val connectorId: String,
    val operationName: String,
    val policy: AutonomyPolicy,
    val revision: Long,
    val origin: ActionPolicyOrigin,
    internal val owner: Any,
    internal val stateToken: String,
    internal val fallbackPolicy: AutonomyPolicy? = null,
) {
    private val consumed = AtomicBoolean(false)

    /** Compare live candidates without exposing the raw state/provenance token to callers. */
    fun sameAuthorizationAs(other: ActionPolicySnapshot): Boolean = owner === other.owner &&
        connectorId == other.connectorId && operationName == other.operationName && policy == other.policy &&
        revision == other.revision && origin == other.origin && stateToken == other.stateToken && fallbackPolicy == other.fallbackPolicy

    internal fun requireUnconsumed() {
        check(!consumed.get()) { "This action admission was already used; prepare a new action" }
    }

    internal fun consume() {
        check(consumed.compareAndSet(false, true)) { "This action admission was already used; prepare a new action" }
    }

    internal fun requireDecision(approvedAsk: Boolean) {
        check(policy != AutonomyPolicy.DENY) { "This action is denied by the current policy" }
        check(!approvedAsk || policy == AutonomyPolicy.ASK) { "Approval must belong to an ASK snapshot" }
        check(policy != AutonomyPolicy.ASK || approvedAsk) { "This exact action requires approval" }
    }
}
