package com.jarvys.agent

import android.content.Context

/** Keeps reflection within the selected, enabled conversation memory. */
internal class MemoryReflectionAuthority(private val context: Context, private val session: String,
    private val manual: Boolean, private val token: CancellationToken) : AutoCloseable {
    private val memory = MemoryStore(context).forConversation(session)
    private val reflection = MemoryReflectionPreferences(context)
    private val chat = context.getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE)

    fun authorize(transcript: String, throughMessage: String) {
        token.throwIfCancelled()
        require(transcript.isNotBlank() && throughMessage.isNotBlank())
        memory.ensureInitialized()
    }

    fun admit() {
        token.throwIfCancelled()
        check(memory.isEnabled && !chat.getBoolean(MemoryUiLogic.sessionMemoryDisabledKey(session), false))
        check(manual || reflection.enabled())
    }

    override fun close() = Unit
}
