package com.jarvys.agent.proactive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvys.agent.FreshStorageBootstrap
import com.jarvys.agent.chatdb.ProactiveReplyAcceptance
import com.jarvys.agent.workspaces.WorkspaceIdentity

/** Data-only notification binding. Neither constructing nor decoding it grants native reply authority. */
object ProactiveReplyNotificationIdentity {
    const val EXTRA_PHYSICAL_GENERATION = "proactive_physical_generation"
    const val EXTRA_THREAD_KEY = "proactive_thread_key"
    const val EXTRA_ACTION_KIND = "proactive_action_kind"
    const val EXTRA_ACTION_ID = "proactive_action_id"
    const val EXTRA_SOURCE_FINGERPRINT = "proactive_source_fingerprint"

    data class Binding(
        val physicalGeneration: String,
        val sourceMessageId: String,
        val threadKey: String,
        val actionKind: String,
        val actionId: String,
        val selectedOrdinal: Int?,
        val sourceFingerprint: String,
    )

    fun createIntent(context: Context, binding: Binding): Intent {
        require(validBinding(binding)) { "Invalid proactive notification binding" }
        return FreshStorageBootstrap.stamp(Intent(context, ProactiveActionReceiver::class.java))
            .setAction(actionFor(binding.actionKind))
            .setData(dataUri(binding))
            .putExtra(EXTRA_PHYSICAL_GENERATION, binding.physicalGeneration)
            .putExtra(ProactiveActionReceiver.EXTRA_MESSAGE_ID, binding.sourceMessageId)
            .putExtra(EXTRA_THREAD_KEY, binding.threadKey)
            .putExtra(ProactiveActionReceiver.EXTRA_THREAD_TOKEN, ProactiveThreadKey.fingerprint(binding.threadKey))
            .putExtra(EXTRA_ACTION_KIND, binding.actionKind)
            .putExtra(EXTRA_ACTION_ID, binding.actionId)
            .putExtra(EXTRA_SOURCE_FINGERPRINT, binding.sourceFingerprint)
            .also { intent -> binding.selectedOrdinal?.let { intent.putExtra(ProactiveActionReceiver.EXTRA_REPLY_INDEX, it) } }
    }

    /** Current explicit URI and extras must agree exactly; canonical Room generation/source checks follow at admission. */
    fun readValidated(context: Context, intent: Intent): Binding? = runCatching {
        if (!FreshStorageBootstrap.isCurrent(intent)
            || intent.component != ComponentName(context, ProactiveActionReceiver::class.java)) return null
        val kind = intent.getStringExtra(EXTRA_ACTION_KIND) ?: return null
        val ordinal = when (kind) {
            "OPTION" -> {
                if (!intent.hasExtra(ProactiveActionReceiver.EXTRA_REPLY_INDEX)) return null
                intent.getIntExtra(ProactiveActionReceiver.EXTRA_REPLY_INDEX, -1)
            }
            "INLINE" -> {
                if (intent.hasExtra(ProactiveActionReceiver.EXTRA_REPLY_INDEX)) return null
                null
            }
            else -> return null
        }
        val binding = Binding(
            physicalGeneration = intent.getStringExtra(EXTRA_PHYSICAL_GENERATION) ?: return null,
            sourceMessageId = intent.getStringExtra(ProactiveActionReceiver.EXTRA_MESSAGE_ID) ?: return null,
            threadKey = intent.getStringExtra(EXTRA_THREAD_KEY) ?: return null,
            actionKind = kind,
            actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return null,
            selectedOrdinal = ordinal,
            sourceFingerprint = intent.getStringExtra(EXTRA_SOURCE_FINGERPRINT) ?: return null,
        )
        if (!validBinding(binding) || intent.action != actionFor(kind) || intent.data != dataUri(binding)
            || intent.getStringExtra(ProactiveActionReceiver.EXTRA_THREAD_TOKEN) != ProactiveThreadKey.fingerprint(binding.threadKey)) {
            return null
        }
        binding
    }.getOrNull()

    private fun validBinding(binding: Binding): Boolean =
        binding.physicalGeneration.isNotBlank() && binding.sourceMessageId.isNotBlank() && binding.threadKey.isNotBlank()
            && binding.sourceFingerprint.isNotBlank()
            && when (binding.actionKind) {
                "OPTION" -> binding.selectedOrdinal != null && binding.selectedOrdinal >= 0
                "INLINE" -> binding.selectedOrdinal == null
                else -> false
            }
            && binding.actionId == ProactiveReplyAcceptance.actionId(
                binding.physicalGeneration, WorkspaceIdentity.CENTRAL_SESSION, binding.threadKey,
                binding.sourceMessageId, binding.actionKind,
            )

    private fun actionFor(kind: String): String = when (kind) {
        "OPTION" -> ProactiveActionReceiver.ACTION_SUGGESTED_REPLY
        "INLINE" -> ProactiveActionReceiver.ACTION_INLINE_REPLY
        else -> error("Unknown proactive notification action")
    }

    /** Full identity is in data, so FLAG_UPDATE_CURRENT cannot retarget an older source's handle. */
    private fun dataUri(binding: Binding): Uri = Uri.Builder()
        .scheme("jarvys").authority("proactive-native").appendPath("v1")
        .appendPath(binding.physicalGeneration).appendPath(WorkspaceIdentity.CENTRAL_SESSION)
        .appendPath(binding.threadKey).appendPath(binding.sourceMessageId)
        .appendPath(binding.actionKind).appendPath(binding.actionId).appendPath(binding.sourceFingerprint)
        .appendPath(binding.selectedOrdinal?.toString() ?: "inline")
        .build()
}
