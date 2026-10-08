package com.jarvys.agent.apkfactory

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Strict grammar/depth validation before Android JSONObject, including malformed project UTF-8. */
internal object FactoryJson {
    fun objectFrom(bytes: ByteArray, maximumBytes: Int): JSONObject {
        require(bytes.size <= maximumBytes) { "Factory JSON exceeds its size limit" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return FactoryJsonGrammar.parseObject(text, maximumBytes)
    }
}
