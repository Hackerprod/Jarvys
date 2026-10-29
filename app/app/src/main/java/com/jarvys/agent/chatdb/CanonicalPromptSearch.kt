package com.jarvys.agent.chatdb

import java.math.BigDecimal
import java.math.BigInteger

/** Prompt search has its own full-content transport and sanitizer, not observation ranking/options. */
object CanonicalPromptSearch {
    @JvmStatic fun limit(raw: Any?): Long {
        if (raw !is Number) return 10
        val decimal = try {
            when(raw) {
                is Byte, is Short, is Int, is Long -> BigDecimal.valueOf(raw.toLong())
                is BigInteger -> BigDecimal(raw)
                is BigDecimal -> raw
                else -> {
                    require(raw.toDouble().isFinite()) { "memory_search_prompts_invalid_limit" }
                    BigDecimal(raw.toString())
                }
            }
        } catch(failure: NumberFormatException) {
            throw IllegalArgumentException("memory_search_prompts_invalid_limit",failure)
        }
        require(decimal >= BigDecimal.valueOf(Long.MIN_VALUE) && decimal <= BigDecimal.valueOf(Long.MAX_VALUE)) {
            "memory_search_prompts_invalid_limit"
        }
        val integer=decimal.toBigInteger()
        val value=integer.toLong()
        return if(value<=0) 10 else value
    }

    @JvmStatic fun validateArguments(args: Map<String, Any?>) {
        require(args["query"] is String && (args["query"] as String).isNotEmpty()) { "memory_search_prompts_query_required" }
        if(args.containsKey("project")) require(args["project"] is String && MemoryObservationContent.trimSpace(args["project"] as String).isNotEmpty()) {
            "memory_search_prompts_project_invalid"
        }
        if(args.containsKey("all_projects")) require(args["all_projects"] is Boolean) { "memory_search_prompts_all_projects_invalid" }
        require(args["all_projects"] != true || !args.containsKey("project")) { "memory_search_prompts_project_conflict" }
        limit(args["limit"])
    }

    @JvmStatic fun matchExpression(query: String): String = MemoryObservationContent.fields(query).joinToString(" ") {
        "\"${it.trim('"').replace("\"", "\"\"")}\""
    }
}
