package com.jarvys.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ephemeral native-UI consent. Never save/restore this state or expose it as an agent tool. */
internal class MemoryScopeReviewSession {
    internal data class Pending(val source: MemoryStore, val review: MemoryScopeReview)

    var pending: Pending? by mutableStateOf(null)
        private set
    var acknowledged by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    private var generation = 0L
    private var closed = false

    fun beginReview(): Long {
        check(!closed)
        generation++
        pending = null
        acknowledged = false
        loading = true
        return generation
    }

    fun present(ticket: Long, source: MemoryStore, review: MemoryScopeReview): Boolean {
        if (closed || ticket != generation || !loading) return false
        pending = Pending(source, review)
        loading = false
        return true
    }

    fun failed(ticket: Long): Boolean {
        if (closed || ticket != generation || !loading) return false
        cancel()
        return true
    }

    fun acknowledge(value: Boolean) {
        acknowledged = value && !closed && pending != null && !loading
    }

    /** Consume before starting IO: repeated callbacks cannot reuse approval. */
    fun takeApproved(): Pending? {
        if (closed || loading || !acknowledged) return null
        val result = pending ?: return null
        cancel()
        return result
    }

    fun cancel() {
        generation++
        pending = null
        acknowledged = false
        loading = false
    }

    fun close() {
        cancel()
        closed = true
    }
}
