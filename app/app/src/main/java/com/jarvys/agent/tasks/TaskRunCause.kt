package com.jarvys.agent.tasks

import android.content.Context
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.R

/** Stable ledger cause codes. Provider and exception text belongs in TaskRunRecord.causeDetail. */
enum class TaskRunCause(val code: String, private val label: Int) {
    NO_PROVIDER("NO_PROVIDER", R.string.task_cause_no_provider),
    PREVIOUS_OCCURRENCE_INTERRUPTED("PREVIOUS_OCCURRENCE_INTERRUPTED", R.string.task_cause_previous_interrupted),
    INTERRUPTED("INTERRUPTED", R.string.task_cause_interrupted),
    MISSED_OCCURRENCE("MISSED_OCCURRENCE", R.string.task_cause_missed),
    BACKGROUND_RUN_ACTIVE("BACKGROUND_RUN_ACTIVE", R.string.task_cause_background_run_active),
    PROVIDER_RATE_LIMITED("PROVIDER_RATE_LIMITED", R.string.task_cause_provider_rate_limited),
    PROVIDER_UNAVAILABLE("PROVIDER_UNAVAILABLE", R.string.task_cause_provider_unavailable),
    PROVIDER_ERROR("PROVIDER_ERROR", R.string.task_cause_provider_error),
    NETWORK("NETWORK", R.string.task_cause_network),
    CONNECTOR_UNAVAILABLE("CONNECTOR_UNAVAILABLE", R.string.task_cause_connector_unavailable),
    TOOL_SCOPE_UNAVAILABLE("TOOL_SCOPE_UNAVAILABLE", R.string.task_cause_tool_scope_unavailable),
    EXECUTION_FAILED("EXECUTION_FAILED", R.string.task_cause_execution_failed),
    EFFECT_OCCURRED("EFFECT_OCCURRED", R.string.task_cause_effect_occurred),
    TASK_CHANGED("TASK_CHANGED", R.string.task_cause_task_changed),
    AWAITING_USER("AWAITING_USER", R.string.task_cause_awaiting_user),
    UNKNOWN("UNKNOWN", R.string.task_cause_unknown);

    fun localized(context: Context): String = AppLanguageRuntime.localizedContext(context).getString(label)

    companion object {
        fun fromCode(value: String?): TaskRunCause? = values().firstOrNull { it.code == value }

        /** Reads the fixed strings written by earlier app versions without treating arbitrary text as an enum. */
        fun fromLegacy(value: String?): TaskRunCause? {
            if (value.isNullOrBlank()) return null
            val raw = value.trim()
            val normalized = raw.lowercase()
            return when {
                normalized == "no_provider" || normalized == "sin proveedor" -> NO_PROVIDER
                normalized == "recovered_interrupted_run" || normalized == "previous occurrence was interrupted" ->
                    PREVIOUS_OCCURRENCE_INTERRUPTED
                normalized == "interrupted" || normalized == "worker_stopped" || normalized == "detenido" -> INTERRUPTED
                normalized == "missed_occurrence" -> MISSED_OCCURRENCE
                normalized == "background_run_active" -> BACKGROUND_RUN_ACTIVE
                normalized == "429" -> PROVIDER_RATE_LIMITED
                normalized == "5xx" -> PROVIDER_UNAVAILABLE
                normalized == "network" || normalized == "red" -> NETWORK
                normalized.startsWith("provider_http_") || normalized.startsWith("proveedor_http_") -> {
                    when (normalized.substringAfterLast('_').toIntOrNull()) {
                        429 -> PROVIDER_RATE_LIMITED
                        in 500..599 -> PROVIDER_UNAVAILABLE
                        else -> PROVIDER_ERROR
                    }
                }
                normalized.startsWith("tool_scope_not_read_only") -> TOOL_SCOPE_UNAVAILABLE
                normalized.startsWith("connector_unavailable:") -> CONNECTOR_UNAVAILABLE
                normalized.contains("connector_unavailable") || normalized.contains("connector unavailable") ->
                    CONNECTOR_UNAVAILABLE
                normalized == "task execution failed" || normalized == "task_error" || normalized == "error_en_revision" ->
                    EXECUTION_FAILED
                normalized == "task run stopped after an effect" -> EFFECT_OCCURRED
                else -> UNKNOWN
            }
        }

        fun legacyDetail(value: String?, cause: TaskRunCause?): String? {
            if (value.isNullOrBlank() || cause == null || cause == UNKNOWN) return value?.takeIf(String::isNotBlank)
            val normalized = value.trim().lowercase()
            return when {
                normalized.startsWith("provider_http_") || normalized.startsWith("proveedor_http_") -> value
                else -> null
            }
        }
    }
}
