package com.jarvys.agent.chatdb

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class DuplicateLedgerJsonKey(val key: String) : IllegalArgumentException("duplicate JSON key: $key")

/** Strict UTF-8 and duplicate-key rejecting line parser for legacy JSONL. */
internal object StrictLedgerJson {
    @Throws(CharacterCodingException::class)
    fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    fun parseObject(text: String): JSONObject {
        val reader = JsonReader(StringReader(text)).apply { isLenient = false }
        reader.use {
            if (it.peek() != JsonToken.BEGIN_OBJECT) throw IllegalArgumentException("JSON row must be an object")
            val value = readObject(it)
            if (it.peek() != JsonToken.END_DOCUMENT) throw IllegalArgumentException("trailing data after JSON object")
            return value
        }
    }

    private fun readObject(reader: JsonReader): JSONObject {
        val result = JSONObject()
        val seen = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (!seen.add(key)) throw DuplicateLedgerJsonKey(key)
            result.put(key, readValue(reader))
        }
        reader.endObject()
        return result
    }

    private fun readArray(reader: JsonReader): JSONArray {
        val result = JSONArray()
        reader.beginArray()
        while (reader.hasNext()) result.put(readValue(reader))
        reader.endArray()
        return result
    }

    private fun readValue(reader: JsonReader): Any = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> readObject(reader)
        JsonToken.BEGIN_ARRAY -> readArray(reader)
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> BigDecimal(reader.nextString())
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> {
            reader.nextNull()
            JSONObject.NULL
        }
        else -> throw IllegalArgumentException("unexpected JSON token ${reader.peek()}")
    }
}
