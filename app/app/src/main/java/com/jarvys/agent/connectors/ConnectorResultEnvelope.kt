package com.jarvys.agent.connectors

import org.json.JSONArray
import org.json.JSONObject

/** Bounds connector-sourced text and item volume before device data enters the model transcript. */
object ConnectorResultEnvelope {
    const val DEFAULT_MAX_BYTES = 8 * 1024

    @JvmStatic
    fun bounded(
        source: String,
        input: JSONArray,
        itemLimit: Int,
        fieldLimits: Map<String, Int>,
        initiallyTruncated: Boolean = false,
        collectionKey: String = "items",
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): JSONObject {
        require(itemLimit in 0..50)
        val rows = JSONArray()
        var truncated = initiallyTruncated || input.length() > itemLimit
        for (index in 0 until minOf(input.length(), itemLimit)) {
            val original = input.optJSONObject(index) ?: continue
            val row = JSONObject()
            val keys = original.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val bounded = boundValue(original.opt(key), fieldLimits[key] ?: DEFAULT_TEXT_CHARS, fieldLimits)
                if (bounded.truncated) truncated = true
                row.put(key, bounded.value)
            }
            rows.put(row)
        }
        var result = JSONObject().put("untrusted_content", true).put("source", source).put(collectionKey, rows)
        if (truncated) result.put("truncated", true)
        while (result.toString().toByteArray(Charsets.UTF_8).size > maxBytes && rows.length() > 0) {
            rows.remove(rows.length() - 1)
            truncated = true
            result.put("truncated", true)
        }
        if (result.toString().toByteArray(Charsets.UTF_8).size > maxBytes) {
            result = JSONObject().put("untrusted_content", true).put("source", source)
                .put(collectionKey, JSONArray()).put("truncated", true)
        }
        return result
    }

    private data class BoundedValue(val value: Any?, val truncated: Boolean)

    private fun boundValue(value: Any?, limit: Int, fieldLimits: Map<String, Int>): BoundedValue = when (value) {
        is String -> BoundedValue(value.take(limit), value.length > limit)
        is JSONArray -> {
            val bounded = JSONArray()
            var truncated = value.length() > MAX_ARRAY_ITEMS
            val elementLimit = minOf(limit, MAX_ARRAY_TEXT_CHARS)
            for (index in 0 until minOf(value.length(), MAX_ARRAY_ITEMS)) {
                val item = value.opt(index)
                if (item is String && item.length > elementLimit) truncated = true
                bounded.put(boundValue(item, elementLimit, fieldLimits).value)
            }
            BoundedValue(bounded, truncated)
        }
        is JSONObject -> {
            val bounded = JSONObject()
            val keys = value.keys()
            var truncated = false
            while (keys.hasNext()) {
                val key = keys.next()
                val item = boundValue(value.opt(key), fieldLimits[key] ?: DEFAULT_TEXT_CHARS, fieldLimits)
                if (item.truncated) truncated = true
                bounded.put(key, item.value)
            }
            BoundedValue(bounded, truncated)
        }
        else -> BoundedValue(value, false)
    }

    private const val DEFAULT_TEXT_CHARS = 300
    private const val MAX_ARRAY_ITEMS = 10
    private const val MAX_ARRAY_TEXT_CHARS = 300
}
