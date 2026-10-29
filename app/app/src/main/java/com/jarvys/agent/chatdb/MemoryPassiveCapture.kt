package com.jarvys.agent.chatdb

import java.util.regex.Pattern

/** Deterministic upstream learning grammar. Parsing is not admission or write authority. */
object MemoryPassiveCapture {
    private val FLAGS = Pattern.MULTILINE or Pattern.UNIX_LINES
    private val header = Pattern.compile("^#{2,3}[\\t\\n\\f\\r ]+(?:Aprendizaje[sSſ](?:[\\t\\n\\f\\r ]+Clave)?|[kKK]ey[\\t\\n\\f\\r ]+Learning[sSſ]?|Learning[sSſ]?):?[\\t\\n\\f\\r ]*$", FLAGS or Pattern.CASE_INSENSITIVE)
    private val cutoff = Pattern.compile("\\n#{1,3} ")
    private val numbered = Pattern.compile("^[\\t\\n\\f\\r ]*\\d+[.)][\\t\\n\\f\\r ]+(.+)", FLAGS)
    private val bullets = Pattern.compile("^[\\t\\n\\f\\r ]*[-*][\\t\\n\\f\\r ]+(.+)", FLAGS)
    private val bold = Pattern.compile("\\*\\*([^*]+)\\*\\*")
    private val code = Pattern.compile("`([^`]+)`")
    private val italic = Pattern.compile("\\*([^*]+)\\*")
    private fun clean(raw:String):String {
        val a=bold.matcher(raw).replaceAll("$1")
        val b=code.matcher(a).replaceAll("$1")
        return MemoryObservationContent.fields(italic.matcher(b).replaceAll("$1")).joinToString(" ")
    }
    @JvmStatic fun extractLearnings(text:String):List<String> {
        val ends=ArrayList<Int>(); val headers=header.matcher(text)
        while(headers.find()) ends.add(headers.end())
        for(end in ends.asReversed()) {
            var section=text.substring(end)
            val next=cutoff.matcher(section);if(next.find()) section=section.substring(0,next.start())
            fun items(pattern:Pattern):List<String> {
                val result=ArrayList<String>();val matches=pattern.matcher(section)
                while(matches.find()) {
                    val value=clean(matches.group(1))
                    if(value.toByteArray(Charsets.UTF_8).size>=20 && MemoryObservationContent.fields(value).size>=4) result.add(value)
                }
                return result
            }
            val values=items(numbered).ifEmpty { items(bullets) }
            if(values.isNotEmpty()) return values
        }
        return emptyList()
    }
    /** Privacy adapter: redact before the upstream byte cut, never leave a cut private span in title. */
    @JvmStatic fun rawTitle(cleanedLearning:String):ByteArray {
        val bytes=MemoryObservationContent.prepareTitle(cleanedLearning).toByteArray(Charsets.UTF_8)
        return if(bytes.size<=60) bytes else bytes.copyOf(60)+byteArrayOf(46,46,46)
    }
}

data class MemoryPassiveCaptureResult(val extracted:Int,val saved:Int,val duplicates:Int)

/** Trusted runtime call data; no model-facing constructor or scalar authority. */
data class PassiveMemoryInput internal constructor(val binding:MemorySessionBinding,val ordinal:Int,val titleBytes:ByteArray)

class MemoryPassiveCaptureException(val confirmed:MemoryPassiveCaptureResult,cause:Exception):IllegalStateException("memory_passive_capture_unconfirmed",cause)
