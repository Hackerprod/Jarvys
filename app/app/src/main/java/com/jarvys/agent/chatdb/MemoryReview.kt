package com.jarvys.agent.chatdb

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Review reminders, never expiration or evidence of factual verification. */
object MemoryReview {
    private val sqlFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    @JvmStatic fun defaultAfter(type: String, nowMillis: Long): String? {
        val months = when(type) {
            "preference", "config" -> 3L
            "decision", "discovery", "pattern" -> 6L
            "policy", "architecture", "bugfix" -> 12L
            else -> return null
        }
        val now = Instant.ofEpochMilli(nowMillis).atOffset(ZoneOffset.UTC).toLocalDateTime()
        // Go AddDate normalizes overflow days rather than clamping the last day of a month.
        return now.withDayOfMonth(1).plusMonths(months).plusDays((now.dayOfMonth - 1).toLong()).format(sqlFormat)
    }
    @JvmStatic fun timestamp(nowMillis: Long): String = Instant.ofEpochMilli(nowMillis).atOffset(ZoneOffset.UTC).format(sqlFormat)
    @JvmStatic fun state(value: String?, nowMillis: Long): String {
        val text = MemoryObservationContent.trimSpace(value.orEmpty())
        if(text.isEmpty()) return "active"
        val parsed = parse(text) ?: return "active"
        return if(!parsed.isAfter(Instant.ofEpochMilli(nowMillis))) "needs_review" else "active"
    }
    private val dateTime = Regex("""([0-9]{4})-([0-9]{2})-([0-9]{2})([ T])([0-9]{1,2}):([0-9]{2}):([0-9]{2})(?:[.,]([0-9]+))?(Z|[+-][0-9]{2}:[0-9]{2})?""")
    /** Exact retained Go layouts, including normalized numeric offsets and nanosecond truncation. */
    @JvmStatic fun parseInstant(raw: String): Instant? {
        val value=MemoryObservationContent.trimSpace(raw)
        if(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) {
            try { return LocalDate.parse(value,DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay().toInstant(ZoneOffset.UTC) } catch (_:java.time.DateTimeException) { return null }
        }
        val m=dateTime.matchEntire(value) ?: return null
        val v=m.groupValues
        if(v[4]==" " && v[9].isNotEmpty() || v[4]=="T" && v[9].isEmpty()) return null
        return try {
            val nanos=v[8].take(9).padEnd(9,'0').toInt()
            val local=LocalDateTime.of(v[1].toInt(),v[2].toInt(),v[3].toInt(),v[5].toInt(),v[6].toInt(),v[7].toInt(),nanos)
            var seconds=0L
            if(v[9].isNotEmpty() && v[9]!="Z") {
                val hours=v[9].substring(1,3).toInt(); val minutes=v[9].substring(4,6).toInt()
                if(hours>24 || minutes>60) return null
                seconds=(hours*3600L+minutes*60L)*(if(v[9][0]=='-') -1 else 1)
            }
            local.toInstant(ZoneOffset.UTC).minusSeconds(seconds)
        } catch (_:java.time.DateTimeException) { null }
    }
    private fun parse(value:String):Instant? = parseInstant(value)

}

data class PersonalMemoryReviewReceipt(val id:String,val project:String,val reviewAfter:String?,val reviewState:String,val updatedAt:Long)
data class MemoryReviewClearEvent(val id:Long,val eventKey:String,val observationId:String)
