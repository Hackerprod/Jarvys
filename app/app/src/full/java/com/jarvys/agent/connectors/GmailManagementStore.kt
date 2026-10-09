package com.jarvys.agent.connectors

import android.content.Context
import com.jarvys.agent.SecretStore
import org.json.JSONObject

/** Private encrypted workflow checkpoints, never message bodies, credentials or agent memory. */
interface GmailManagementStore {
    fun get(id: String): JSONObject?
    fun put(record: JSONObject)
    fun records(): List<JSONObject>
}

object GmailManagementStores {
    fun inMemory(): GmailManagementStore = JsonGmailManagementStore(object : GmailManagementPersistence {
        private var data: String? = null
        override fun read() = data
        override fun write(value: String) { data = value }
    })

    fun persistent(context: Context): GmailManagementStore {
        val app = context.applicationContext
        return object : GmailManagementStore {
            private val delegate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                val secrets = SecretStore.get(app)
                JsonGmailManagementStore(object : GmailManagementPersistence {
                    override val identity: Any = secrets
                    override fun read() = secrets.getConnectorSecret("gmail_management", "checkpoints_v1")
                    override fun write(value: String) = secrets.saveConnectorSecret("gmail_management", "checkpoints_v1", value)
                })
            }
            override fun get(id: String) = delegate.get(id)
            override fun put(record: JSONObject) = delegate.put(record)
            override fun records() = delegate.records()
        }
    }
}

internal interface GmailManagementPersistence {
    val identity: Any get() = this
    fun read(): String?
    /** Must acknowledge durable storage or throw, before any network write is dispatched. */
    fun write(value: String)
}

internal class JsonGmailManagementStore(private val persistence: GmailManagementPersistence) : GmailManagementStore {
    override fun get(id: String): JSONObject? = synchronized(LOCK) {
        read().optJSONObject(id)?.let { JSONObject(it.toString()) }
    }
    override fun records(): List<JSONObject> = synchronized(LOCK) {
        val all = read()
        all.keys().asSequence().map { JSONObject(all.getJSONObject(it).toString()) }.toList()
    }
    override fun put(record: JSONObject) = synchronized(LOCK) {
        check(FAILED_STORES[persistence.identity] != true) { "Gmail checkpoint durability is uncertain. Restart the app before any new mailbox write." }
        val id = record.getString("record_id")
        require(ID.matches(id)) { "Invalid Gmail checkpoint identifier" }
        val all = read()
        if (!all.has(id) && all.length() >= MAX_RECORDS) {
            // Retire old read-only selections, unattempted plans and terminal receipts; never uncertain effects.
            val oldest = all.keys().asSequence().map { all.getJSONObject(it) }
                .filter { item -> item.optString("record_id") != record.optString("resumes_receipt") && (item.optJSONArray("unresolved_intents")?.length() ?: 0) == 0 && (item.optJSONArray("journal_intents")?.length() ?: 0) == 0 &&
                    (item.has("journal_intents") || item.optJSONArray("targets")?.let { targets -> (0 until targets.length()).none { targets.getJSONObject(it).has("intent_hash") } } != false) &&
                    (item.optString("kind") == "selection" ||
                    item.optJSONArray("targets")?.let { targets -> (0 until targets.length()).all {
                        targets.getJSONObject(it).optString("state") in setOf("pending", "verified", "rejected", "skipped_changed", "skipped_missing", "skipped_unsupported", "resumed")
                    } } == true) }
                .minByOrNull { it.optLong("created_at") }
            check(oldest != null) { "Gmail checkpoint storage is full; reconcile outstanding batches before new work." }
            all.remove(oldest.getString("record_id"))
        }
        all.put(id, JSONObject(record.toString()))
        val value = all.toString()
        check(value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Gmail checkpoint byte budget reached; no new write was sent." }
        try { persistence.write(value) } catch (failure: Exception) {
            // commit(false) may already have changed SharedPreferences process memory. Never trust that as durable.
            FAILED_STORES[persistence.identity] = true
            throw failure
        }
    }
    private fun read(): JSONObject {
        val value = persistence.read() ?: return JSONObject()
        check(value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Gmail checkpoint storage is invalid" }
        val data = runCatching { JSONObject(value) }.getOrElse { error("Gmail checkpoint storage is invalid") }
        check(data.length() <= MAX_RECORDS && data.keys().asSequence().all { key ->
            ID.matches(key) && data.optJSONObject(key)?.optString("record_id") == key
        }) { "Gmail checkpoint storage is invalid" }
        return data
    }
    companion object {
        private val LOCK = Any()
        private val FAILED_STORES = java.util.WeakHashMap<Any, Boolean>()
        const val MAX_RECORDS = 64
        const val MAX_BYTES = 6 * 1024 * 1024
        private val ID = Regex("(?:selection|receipt):[0-9a-f-]{36}")
    }
}
