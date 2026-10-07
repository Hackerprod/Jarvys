package com.jarvys.agent.proactive

import android.content.Context
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreTool
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.ToolSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class ProactiveRespondCoreTool(context: Context, private val decisionSink: ProactiveDecisionSink) : CoreTool {
    private val app = context.applicationContext

    override fun declaration() = ToolSpec(
        NAME,
        "jarvys/proactive",
        app.getString(com.jarvys.agent.R.string.proactive_tool_respond_description),
        "proactive",
        ToolSpec.Status.IMPLEMENTED,
        emptyMap(),
        listOf("notify", "title", "body", "urgency", "event_ids", "thread_key"),
        linkedMapOf(
            "type" to "object",
            "properties" to linkedMapOf(
                "notify" to linkedMapOf("type" to "boolean"),
                "title" to linkedMapOf("type" to "string"),
                "body" to linkedMapOf("type" to "string"),
                "urgency" to linkedMapOf("type" to "string", "enum" to listOf("low", "normal", "high")),
                "event_ids" to linkedMapOf("type" to "array", "items" to linkedMapOf("type" to "string")),
                "thread_key" to linkedMapOf("type" to "string"),
                "suggested_replies" to linkedMapOf(
                    "type" to "array",
                    "items" to linkedMapOf<String, Any>(
                        "type" to "object",
                        "properties" to linkedMapOf(
                            "label" to linkedMapOf("type" to "string"),
                            "text" to linkedMapOf("type" to "string"),
                        ),
                        "required" to listOf("label", "text"),
                        "additionalProperties" to false,
                    ),
                ),
            ),
            "required" to listOf("notify", "title", "body", "urgency", "event_ids", "thread_key"),
            "additionalProperties" to false,
        ),
    )

    override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        token.throwIfCancelled()
        val notify = arguments["notify"] as? Boolean
            ?: return CoreToolResult.failure("notify must be a boolean")
        val title = arguments["title"] as? String
            ?: return CoreToolResult.failure("title must be a string")
        val body = arguments["body"] as? String
            ?: return CoreToolResult.failure("body must be a string")
        val urgency = arguments["urgency"] as? String
            ?: return CoreToolResult.failure("urgency must be a string")
        val eventIds = (arguments["event_ids"] as? List<*>)?.map { it as? String
            ?: return CoreToolResult.failure("event_ids must contain only strings") }
            ?: return CoreToolResult.failure("event_ids must be an array of strings")
        val threadKey = arguments["thread_key"] as? String
            ?: return CoreToolResult.failure("thread_key must be a string")
        val suggestedReplies = when (val raw = arguments["suggested_replies"]) {
            null -> emptyList()
            is List<*> -> raw.map { item ->
                val fields = item as? Map<*, *> ?: return CoreToolResult.failure("suggested_replies items must be objects")
                val label = fields["label"] as? String ?: return CoreToolResult.failure("suggested reply label must be a string")
                val text = fields["text"] as? String ?: return CoreToolResult.failure("suggested reply text must be a string")
                val cleanLabel = ProactiveTextSanitizer.sanitize(label)
                val cleanText = ProactiveTextSanitizer.sanitize(text)
                if (cleanLabel.isBlank() || cleanText.isBlank()) return CoreToolResult.failure("suggested reply label and text cannot be empty")
                ProactiveSuggestedReply(cleanLabel, cleanText)
            }
            else -> return CoreToolResult.failure("suggested_replies must be an array")
        }
        if (urgency !in URGENCIES) return CoreToolResult.failure("urgency must be low, normal, or high")
        if (notify && (title.isBlank() || body.isBlank())) {
            return CoreToolResult.failure("A notification requires a non-empty title and body")
        }
        return decisionSink.submit(ProactiveDecision(
            notify = notify,
            title = if (notify) title else "",
            body = if (notify) body else "",
            urgency = urgency,
            eventIds = eventIds,
            threadKey = if (notify) threadKey.trim() else "",
            suggestedReplies = suggestedReplies,
        ))
    }

    companion object {
        const val NAME = "proactive_respond"
        private val URGENCIES = setOf("low", "normal", "high")
    }
}

class ProactiveStatusCoreTool @JvmOverloads constructor(
    private val context: Context,
    private val includeRawTimes: Boolean = true,
) : CoreTool {
    override fun declaration() = ToolSpec(
        NAME,
        "jarvys/proactive",
        context.getString(if (includeRawTimes) com.jarvys.agent.R.string.proactive_tool_status_description
            else com.jarvys.agent.R.string.proactive_tool_status_model_description),
        "proactive",
        ToolSpec.Status.IMPLEMENTED,
        emptyMap(),
        emptyList(),
        linkedMapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any>(),
            "required" to emptyList<String>(),
            "additionalProperties" to false,
        ),
    )

    override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        token.throwIfCancelled()
        val status = runBlocking { ProactiveStatusProvider.read(context.applicationContext) }
        token.throwIfCancelled()
        val result = JSONObject()
            .put("enabled", status.enabled)
            .put("pending", status.pendingCount)
            .put("discarded", status.discardedCount)
            .put("lastBatchesSeen", status.lastBatchesSeen)
            .put("notificationPermissionGranted", status.notificationPermissionGranted)
            .put("lastFailureReason", status.lastFailureReason ?: JSONObject.NULL)
        if (includeRawTimes) {
            result.put("lastCheckMillis", status.lastCheckMillis)
            result.put("periodicIntervalMillis", status.periodicIntervalMillis)
            result.put("nextCheckMillis", status.nextCheckMillis ?: JSONObject.NULL)
        }
        return CoreToolResult.success(result.toString())
    }

    companion object { const val NAME = "proactive_status" }
}
