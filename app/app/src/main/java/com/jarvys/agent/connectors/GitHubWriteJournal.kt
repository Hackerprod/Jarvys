package com.jarvys.agent.connectors

import android.content.Context

/** A fail-closed ambiguity guard. Only SHA-256 intent digests are stored, never repository content or tokens. */
interface GitHubWriteJournal {
    fun isUncertain(intentHash: String): Boolean
    /** Persist before dispatch. A duplicate, full, corrupted, or unwritable journal blocks dispatch. */
    fun reserve(intentHash: String)
    /** Clear only after a definitive result. False means keep the marker and do not retry. */
    fun resolve(intentHash: String): Boolean
}

object GitHubWriteJournals {
    fun inMemory(): GitHubWriteJournal = BoundedGitHubWriteJournal(MemoryGitHubWriteStore())
    /** Catalog discovery must not initialize private persistence; only an actual write uses the journal. */
    fun persistent(context: Context): GitHubWriteJournal {
        val app = context.applicationContext
        return object : GitHubWriteJournal {
            private val journal by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { persistentJournal(app) }
            override fun isUncertain(intentHash: String): Boolean = journal.isUncertain(intentHash)
            override fun reserve(intentHash: String) = journal.reserve(intentHash)
            override fun resolve(intentHash: String): Boolean = journal.resolve(intentHash)
        }
    }

    // The Boolean durability acknowledgement is required before dispatch; KTX edit returns no commit result.
    @android.annotation.SuppressLint("UseKtx")
    private fun persistentJournal(context: Context): GitHubWriteJournal {
        val preferences = context.getSharedPreferences("jarvys_github_write_journal", Context.MODE_PRIVATE)
        return BoundedGitHubWriteJournal(object : GitHubWriteJournalStore {
            override val identity: Any = preferences
            override fun read(): Set<String> = preferences.getStringSet("uncertain_intents", emptySet()).orEmpty().toSet()
            override fun write(hashes: Set<String>): Boolean = preferences.edit().putStringSet("uncertain_intents", hashes.toSet()).commit()
        })
    }
}

internal interface GitHubWriteJournalStore {
    /** Wrappers over the same backing store must expose the same identity object. */
    val identity: Any get() = this
    fun read(): Set<String>
    fun write(hashes: Set<String>): Boolean
}
private class MemoryGitHubWriteStore : GitHubWriteJournalStore {
    private var values = emptySet<String>()
    override fun read() = values.toSet()
    override fun write(hashes: Set<String>): Boolean { values = hashes.toSet(); return true }
}

/** Shared lock also serializes independently constructed GitHub journals backed by the same prefs. */
internal class BoundedGitHubWriteJournal(private val store: GitHubWriteJournalStore) : GitHubWriteJournal {
    private val retained = synchronized(LOCK) {
        RETAINED_BY_STORE.getOrPut(store.identity) { mutableSetOf() }
    }
    override fun isUncertain(intentHash: String): Boolean = synchronized(LOCK) {
        validateHash(intentHash)
        intentHash in current()
    }
    override fun reserve(intentHash: String) = synchronized(LOCK) {
        validateHash(intentHash)
        val hashes = current()
        check(intentHash !in hashes) { "GitHub write outcome is unknown; verify the existing action before any retry." }
        check(hashes.size < MAX_ENTRIES) { "GitHub write safety journal is full. Review unresolved actions in GitHub; no new write was sent." }
        // Retain even if persistence fails: SharedPreferences commit failures can still update process memory.
        retained += intentHash
        check(store.write(hashes + intentHash)) { "GitHub write safety state could not be saved; no write was sent." }
    }
    override fun resolve(intentHash: String): Boolean = synchronized(LOCK) {
        validateHash(intentHash)
        try {
            val hashes = current()
            if (intentHash !in hashes) return@synchronized true
            // A failed commit may already remove the value from the shared in-memory prefs map.
            retained += intentHash
            if (!store.write(hashes - intentHash)) return@synchronized false
            retained.remove(intentHash)
            true
        } catch (_: Exception) {
            false
        }
    }
    private fun current(): Set<String> {
        val stored = store.read()
        check(stored.size <= MAX_ENTRIES && stored.all { HASH.matches(it) }) { "GitHub write safety state is invalid; review the action in GitHub before retrying." }
        return stored + retained
    }
    private fun validateHash(hash: String) { require(HASH.matches(hash)) { "Invalid GitHub write intent digest" } }
    companion object {
        const val MAX_ENTRIES = 128
        private val LOCK = Any()
        private val RETAINED_BY_STORE = java.util.WeakHashMap<Any, MutableSet<String>>()
        private val HASH = Regex("[0-9a-f]{64}")
    }
}
