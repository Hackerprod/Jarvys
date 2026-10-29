package com.jarvys.agent.chatdb


/** Effective native-save inputs; neither this value nor its fingerprints are authority. */
data class MemorySaveRequest(val text: String, val layer: String, val category: String?, val importance: Double,
    val sourceRef: String?, val options: MemorySaveOptions, val callerConversation: String,
    val requestedProject: String?, val rawScope: String?) {
    companion object {
        @JvmStatic fun parse(args: Map<String, Any?>, conversationId: String, sourceMessageId: String): MemorySaveRequest {
            fun raw(key: String) = args[key] as? String
            fun required(key: String) = raw(key) ?: throw IllegalArgumentException("$key is required")
            // Preserve the existing native parser for non-selector metadata, including Java.trim.
            fun optional(key: String) = raw(key)?.trim { it <= ' ' }?.takeIf { it.isNotEmpty() }
            val project = if (args.containsKey("project")) {
                require(args["project"] is String) { "memory_project_invalid" }
                required("project").also { require(MemoryNamespace.normalizeProject(it).isNotEmpty()) { "memory_project_empty" } }
            } else null
            val related = when (val ids = args["related_ids"]) {
                is String -> listOf(ids)
                is List<*> -> ids.filterIsInstance<String>()
                else -> emptyList()
            }
            val event = optional("event_date")?.let { MemorySearchFilter.parseInstant(it, "event_date", false) }
            return MemorySaveRequest(required("content"), optional("layer") ?: "curated", optional("category"),
                (args["importance"] as? Number)?.toDouble() ?: 0.5, sourceMessageId,
                MemorySaveOptions(raw("type"), raw("topic_key"), required("title"), event, optional("supersedes_id"),
                    related, optional("relation") ?: "related", conversationId, sourceMessageId.takeIf { it.isNotEmpty() }, "agent"),
                conversationId, project, raw("scope"))
        }
        /** Shared with the actual tool declaration and Main checkpoint coercion, not a second schema. */
        @JvmStatic fun schemaProperties(): Map<String, String> = linkedMapOf(
            "project" to "string", "scope" to "string", "content" to "string", "layer" to "string",
            "category" to "string", "importance" to "number", "type" to "string", "topic_key" to "string",
            "title" to "string", "event_date" to "string", "supersedes_id" to "string",
            "related_ids" to "string_or_array", "relation" to "string")
        @JvmStatic fun requiredFields(): List<String> = listOf("title", "content")
    }
}
