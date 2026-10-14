package com.jarvys.agent.tasks

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.ToolSpec
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Optional notification metadata supplied alongside the model's final text. */
data class TaskResponseMetadata(
    val title: String?,
    val urgency: String,
    val suggestedReplies: List<ProactiveSuggestedReply>,
)

/** Optional, non-terminal metadata tool for scheduled task result notifications. */
class TaskRespondTool : CoreTool {
    override fun executionMode() = CoreTool.ExecutionMode.SEQUENTIAL
    private val metadata = AtomicReference<TaskResponseMetadata?>(null)

    override fun declaration(): ToolSpec = ToolSpec(NAME, "jarvys/tasks", DESCRIPTION, "scheduled_task",
        ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList(), SCHEMA)

    override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        token.throwIfCancelled()
        val unknown = arguments.keys - setOf("title", "urgency", "suggested_replies")
        if (unknown.isNotEmpty()) return CoreToolResult.failure("Unexpected task_respond field(s): ${unknown.sorted().joinToString()}")
        val title = arguments["title"] as? String
        if (arguments.containsKey("title") && title == null) return CoreToolResult.failure("title must be a string")
        val urgency = arguments["urgency"] as? String ?: "normal"
        if (urgency !in URGENCIES) return CoreToolResult.failure("urgency must be low, normal, or high")
        val replies = when (val raw = arguments["suggested_replies"]) {
            null -> emptyList()
            is List<*> -> raw.mapIndexed { index, value ->
                val fields = value as? Map<*, *> ?: return CoreToolResult.failure("suggested_replies[$index] must be an object")
                if (!fields.keys.all { it is String }) return CoreToolResult.failure("suggested_replies[$index] field names must be strings")
                val extras = fields.keys.filterIsInstance<String>().toSet() - setOf("label", "text")
                if (extras.isNotEmpty()) return CoreToolResult.failure("suggested_replies[$index] has unexpected fields")
                val label = fields["label"] as? String
                    ?: return CoreToolResult.failure("suggested_replies[$index].label must be a string")
                val text = fields["text"] as? String
                    ?: return CoreToolResult.failure("suggested_replies[$index].text must be a string")
                if (label.isBlank() || text.isBlank()) return CoreToolResult.failure("Suggested reply label and text must not be empty")
                ProactiveSuggestedReply(label.trim(), text.trim())
            }
            else -> return CoreToolResult.failure("suggested_replies must be an array")
        }
        val value = TaskResponseMetadata(title?.trim()?.takeIf(String::isNotBlank), urgency, replies)
        metadata.set(value)
        token.throwIfCancelled()
        return CoreToolResult.success(JSONObject().put("title", value.title ?: JSONObject.NULL)
            .put("urgency", value.urgency).put("suggested_replies", JSONArray(value.suggestedReplies.map {
                JSONObject().put("label", it.label).put("text", it.text)
            })).toString())
    }

    fun result(): TaskResponseMetadata? = metadata.get()

    companion object {
        const val NAME = "task_respond"
        private const val DESCRIPTION = "Set optional title, urgency, and suggested replies for the scheduled task notification."
        private val URGENCIES = setOf("low", "normal", "high")
        private val SCHEMA: Map<String, Any> = linkedMapOf(
            "type" to "object",
            "properties" to linkedMapOf(
                "title" to mapOf("type" to "string"),
                "urgency" to mapOf("type" to "string", "enum" to URGENCIES.toList()),
                "suggested_replies" to mapOf("type" to "array", "items" to linkedMapOf<String, Any>(
                    "type" to "object",
                    "properties" to linkedMapOf("label" to mapOf("type" to "string"), "text" to mapOf("type" to "string")),
                    "required" to listOf("label", "text"), "additionalProperties" to false,
                )),
            ),
            "required" to emptyList<String>(), "additionalProperties" to false,
        )
    }
}
