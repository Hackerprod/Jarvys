package com.jarvys.agent.tasks

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.ToolSpec
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

data class TaskResponse(
    val notify: Boolean,
    val title: String,
    val body: String,
    val urgency: String,
    val suggestedReplies: List<ProactiveSuggestedReply>,
)

/** The sole terminal tool for a silent scheduled run. */
class TaskRespondTool(private val allowSilence: Boolean = true) : CoreTool {
    private val response = AtomicReference<TaskResponse?>(null)

    override fun declaration(): ToolSpec = ToolSpec(NAME, "jarvys/tasks", DESCRIPTION, "scheduled_task",
        ToolSpec.Status.IMPLEMENTED, emptyMap(), listOf("notify"), SCHEMA)

    override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        token.throwIfCancelled()
        val unknown = arguments.keys - setOf("notify", "title", "body", "urgency", "suggested_replies")
        if (unknown.isNotEmpty()) return CoreToolResult.failure("Unexpected task_respond field(s): ${unknown.sorted().joinToString()}")
        val notify = arguments["notify"] as? Boolean
            ?: return CoreToolResult.failure("notify must be a boolean")
        val title = arguments["title"] as? String
        val body = arguments["body"] as? String
        val urgency = arguments["urgency"] as? String ?: "normal"
        if (urgency !in URGENCIES) return CoreToolResult.failure("urgency must be low, normal, or high")
        if (notify && title.isNullOrBlank()) return CoreToolResult.failure("A notifying task response requires a non-empty title")
        if (notify && body.isNullOrBlank()) return CoreToolResult.failure("A notifying task response requires a non-empty body")
        if (!notify && !allowSilence) return CoreToolResult.failure("This task requires a notification; provide a truthful result")
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
        if (!notify && replies.isNotEmpty()) return CoreToolResult.failure("A silent task response cannot include suggested replies")
        val value = TaskResponse(notify, title.orEmpty().trim(), body.orEmpty().trim(), urgency, replies)
        if (!response.compareAndSet(null, value)) return CoreToolResult.failure("task_respond may only be called once")
        token.throwIfCancelled()
        return CoreToolResult.finish(JSONObject().put("notify", value.notify).put("urgency", value.urgency)
            .put("suggested_replies", JSONArray(value.suggestedReplies.map { JSONObject()
                .put("label", it.label).put("text", it.text) })).toString())
    }

    fun result(): TaskResponse? = response.get()

    companion object {
        const val NAME = "task_respond"
        private const val DESCRIPTION = "Finish this unattended scheduled run with its truthful deliverable, or record intentional silence when there is nothing to report. Never ask the user for clarification or approval."
        private val URGENCIES = setOf("low", "normal", "high")
        private val SCHEMA: Map<String, Any> = linkedMapOf(
            "type" to "object",
            "properties" to linkedMapOf(
                "notify" to mapOf("type" to "boolean"),
                "title" to mapOf("type" to "string"),
                "body" to mapOf("type" to "string"),
                "urgency" to mapOf("type" to "string", "enum" to URGENCIES.toList()),
                "suggested_replies" to mapOf("type" to "array", "items" to linkedMapOf<String, Any>(
                    "type" to "object",
                    "properties" to linkedMapOf("label" to mapOf("type" to "string"), "text" to mapOf("type" to "string")),
                    "required" to listOf("label", "text"), "additionalProperties" to false,
                )),
            ),
            "required" to listOf("notify"), "additionalProperties" to false,
        )
    }
}
