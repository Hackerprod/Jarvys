package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.connectors.ApprovalSummary

/** Presentation only: canonical authorization arguments and user content remain unchanged. */
internal fun nativeMemoryReviewLines(context: Context, summary: ApprovalSummary): List<String> {
    val lines = summary.lines.toMutableList()
    fun label(index: Int, canonical: String, resource: Int) {
        if (lines.getOrNull(index) == canonical) lines[index] = context.getString(resource)
    }
    fun field(index: Int, prefix: String, resource: Int) {
        val exact = lines.getOrNull(index) ?: return
        if (exact.startsWith(prefix)) lines[index] = context.getString(resource, exact.removePrefix(prefix))
    }
    // These positions are metadata emitted by MemoryNativeActions, never paths or note bodies.
    field(0, "Selected memory scope: ", R.string.memory_native_selected_scope)
    field(1, "Exact state: ", R.string.memory_native_exact_state)
    when (summary.title) {
        NativeActionPolicy.Operation.MEMORY_MODIFY.id -> {
            label(2, "Create directory", R.string.memory_native_create_directory)
            label(2, "Save note", R.string.memory_native_save_note)
        }
        NativeActionPolicy.Operation.MEMORY_DELETE.id ->
            label(2, "Delete note", R.string.memory_native_delete_note)
        NativeActionPolicy.Operation.MEMORY_CLEAR.id ->
            label(2, "Irreversibly clear the selected memory and its history", R.string.memory_native_clear_history)
        NativeActionPolicy.Operation.MEMORY_RESTORE.id -> {
            label(2, "Restore revision", R.string.memory_native_restore_revision)
            label(2, "Undo latest revision", R.string.memory_native_undo_revision)
            label(2, "Undo exact chat memory revision", R.string.memory_native_undo_chat_revision)
            label(2, "Undo exact reflection group", R.string.memory_native_undo_reflection_group)
        }
        NativeActionPolicy.Operation.MEMORY_EXPORT.id -> {
            label(2, "Export selected memory", R.string.memory_native_export_selected)
            field(3, "Include journal: ", R.string.memory_native_export_include_journal)
            field(4, "Android-selected destination: ", R.string.memory_native_export_destination)
        }
        NativeActionPolicy.Operation.MEMORY_SHARE_PERSONAL.id -> {
            when (lines.getOrNull(2)) {
                "Share immutable PERSONAL snapshot" -> {
                    label(2, "Share immutable PERSONAL snapshot", R.string.memory_native_share_snapshot)
                    label(4, "Destination: shared PERSONAL projection", R.string.memory_native_share_destination)
                    field(5, "Source conversation: ", R.string.memory_native_source_conversation)
                    field(6, "Revision: ", R.string.memory_native_source_revision)
                    field(7, "SHA-256: ", R.string.memory_native_source_hash)
                    label(8, "Exact content:", R.string.memory_native_exact_content)
                }
                "Revoke PERSONAL projection" ->
                    label(2, "Revoke PERSONAL projection", R.string.memory_native_revoke_projection)
            }
        }
        else -> Unit
    }
    return lines
}
