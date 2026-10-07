package com.jarvys.agent.proactive

import org.json.JSONArray
import org.json.JSONObject

/** Redacts only textual leaves in connector JSON so source timestamps and numeric identifiers retain type/value. */
object ProactiveToolOutputRedactor {
    @JvmStatic
    fun redactJsonTextFields(content: String): String = runCatching {
        sanitize(JSONObject(content)).toString()
    }.getOrElse { ProactiveRedactor.redactForModel(content) }

    private fun sanitize(value: JSONObject): JSONObject {
        val result = JSONObject()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result.put(key, sanitizeValue(value.opt(key)))
        }
        return result
    }

    private fun sanitize(value: JSONArray): JSONArray {
        val result = JSONArray()
        for (index in 0 until value.length()) result.put(sanitizeValue(value.opt(index)))
        return result
    }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        is String -> ProactiveRedactor.redactForModel(value)
        is JSONObject -> sanitize(value)
        is JSONArray -> sanitize(value)
        else -> value
    }
}
