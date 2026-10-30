package com.jarvys.agent.chatdb

import com.jarvys.agent.CancellationToken
import java.io.Reader
import java.io.StringReader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder

/** Exact instant metadata; milliseconds remain the existing local projection, not the LWW precision. */
object MemoryPortableTime {
    data class Value(val epochMillis: Long, val submillisNanos: Int) : Comparable<Value> {
        init { require(submillisNanos in 0..999999) { "memory_portable_time_invalid" } }
        override fun compareTo(other: Value): Int = epochMillis.compareTo(other.epochMillis).takeIf { it != 0 }
            ?: submillisNanos.compareTo(other.submillisNanos)
    }
    private val millisFormat=DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    private val nanosFormat=DateTimeFormatterBuilder().appendInstant(9).toFormatter()
    @JvmStatic fun format(millis: Long?, remainder: Int): String? {
        require(remainder in 0..999999 && (millis!=null || remainder==0)) { "memory_portable_time_invalid" }
        if(millis==null) return null
        val instant=Instant.ofEpochMilli(millis).plusNanos(remainder.toLong())
        require(instant.atOffset(ZoneOffset.UTC).year in 0..9999) { "memory_portable_time_range" }
        return (if(remainder==0) millisFormat else nanosFormat).format(instant)
    }
    @JvmStatic fun parse(raw:String):Value? = parse(StringReader(raw),CancellationToken.cancellable())
    /** Only significant date syntax is retained; arbitrarily long whitespace/fractions are consumed, not allocated. */
    internal fun parse(reader:Reader,token:CancellationToken):Value? {
        val value=StringBuilder(48)
        var invalid=false;var started=false;var trailing=false;var fraction=false;var digits=0
        while(true) {
            token.throwIfCancelled();val next=reader.read();if(next<0)break
            val ch=next.toChar()
            val whitespace=MemoryObservationContent.isGoSpace(next)
            if(!started && whitespace)continue
            started=true
            if(whitespace) {
                if(ch==' ' && value.length==10 && !trailing) { value.append(ch);continue }
                trailing=true;continue
            }
            if(trailing)invalid=true
            if(fraction && ch in '0'..'9') {
                if(digits<9)value.append(ch)
                digits=(digits+1).coerceAtMost(10);continue
            }
            if(fraction) { if(digits==0)invalid=true;fraction=false }
            if(ch=='.' || ch==',') { fraction=true;digits=0 }
            if(value.length<48)value.append(ch) else invalid=true
        }
        if(fraction && digits==0)invalid=true
        if(invalid)return null
        val instant=MemoryReview.parseInstant(value.toString()) ?: return null
        require(instant.atOffset(ZoneOffset.UTC).year in 0..9999) { "memory_portable_time_range" }
        val millis=try {instant.toEpochMilli()} catch(_:ArithmeticException) {return null}
        return Value(millis,instant.nano%1_000_000)
    }
}
