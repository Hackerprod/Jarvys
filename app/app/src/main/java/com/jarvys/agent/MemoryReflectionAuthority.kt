package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.connectors.AutonomyPolicy

/** One exact reflection mission, bounded by existing memory-only tool and conversation capabilities. */
internal class MemoryReflectionAuthority(private val context: Context, private val session: String,
    private val manual: Boolean, private val token: CancellationToken) : AutoCloseable {
    private val memory = MemoryStore(context).forConversation(session)
    private val reflection = MemoryReflectionPreferences(context)
    private val chat = context.getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE)
    private var request = NativeActionPolicy(context).prepare(NativeActionPolicy.Operation.MEMORY_REFLECTION,
        NativeActionPolicy.binding(session, manual.toString()))
    fun authorize(transcript: String, throughMessage: String) {
        request = request.rebind(NativeActionPolicy.binding(session, manual.toString(), throughMessage,
            memory.nativeStateFingerprint(true), transcript))
        request.authorize(ApprovalSummary(context.getString(R.string.memory_native_reflection_title), listOf(
            context.getString(R.string.memory_native_reflection_conversation, session),
            context.getString(R.string.memory_native_reflection_through_message, throughMessage),
            context.getString(R.string.memory_native_reflection_manual, manual.toString()),
            context.getString(R.string.memory_native_reflection_explanation),
            context.getString(R.string.memory_native_reflection_transcript_hash, NativeActionPolicy.binding(transcript)), transcript,
        ), allowAlwaysAvailable = false), token) { summary, active -> ApprovalGate.INSTANCE.request(summary, active) }
    }
    fun admit() = request.admit(token) {
        check(memory.isEnabled && !chat.getBoolean(MemoryUiLogic.sessionMemoryDisabledKey(session), false))
        check(manual || reflection.enabled())
    }
    override fun close() = request.close()
    companion object {
        @JvmStatic fun maySchedule(context: Context): Boolean = runCatching {
            NativeActionPolicy(context).snapshot(NativeActionPolicy.Operation.MEMORY_REFLECTION).policy != AutonomyPolicy.DENY
        }.getOrDefault(false)
    }
}
