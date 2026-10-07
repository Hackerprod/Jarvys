package com.jarvys.agent.proactive

import android.provider.CallLog
import com.jarvys.agent.connectors.CallLogRecord
import com.jarvys.agent.connectors.FullSmsRecord

object FullProactiveSources {
    const val SMS = "sms"
    const val CALL_LOG = "call_log"

    val sms = SourceSpec(
        SMS,
        LocalizedDescription(
            "An SMS record. receivedAtMillis is the provider's original message time; interpret it in the device's local time zone. direction distinguishes inbound from outbound.",
            "Un registro SMS. receivedAtMillis es la hora original del mensaje según el proveedor; interprétala en la zona horaria local del dispositivo. direction distingue entrante de saliente.",
        ),
        "android.permission.READ_SMS",
        "sms_provider",
    )

    val callLog = SourceSpec(
        CALL_LOG,
        LocalizedDescription(
            "A call-log record. receivedAtMillis is the call's original local-device time; use direction and category rather than raw provider codes.",
            "Un registro del historial de llamadas. receivedAtMillis es la hora original local de la llamada; usa direction y category, no códigos crudos del proveedor.",
        ),
        "android.permission.READ_CALL_LOG",
        "call_log_provider",
    )
}

/** These source adapters exist only in the `full` source set; Play cannot reference SMS or call-log providers. */
object FullProactiveNormalizers {
    fun sms(record: FullSmsRecord, observedAtMillis: Long): ProactiveEvent {
        val direction = when (record.type.lowercase()) {
            "inbox", "received", "inbound" -> "inbound"
            "sent", "outbox", "outbound" -> "outbound"
            else -> null
        }
        val title = "SMS"
        val body = ProactiveNormalizer.normalizeText(record.body)
        return ProactiveEvent(
            sourceId = FullProactiveSources.SMS,
            dedupKey = ProactiveNormalizer.dedupKey(
                FullProactiveSources.SMS,
                listOf(record.address, title, body, direction.orEmpty()).joinToString("\u001f"),
                record.dateMillis,
            ),
            receivedAtMillis = record.dateMillis,
            observedAtMillis = observedAtMillis,
            appLabel = "SMS",
            sender = record.address,
            title = title,
            body = body,
            category = "msg",
            direction = direction,
        )
    }

    fun call(record: CallLogRecord, observedAtMillis: Long): ProactiveEvent {
        val missed = record.type == CallLog.Calls.MISSED_TYPE
        val direction = when (record.type) {
            CallLog.Calls.INCOMING_TYPE, CallLog.Calls.MISSED_TYPE, CallLog.Calls.VOICEMAIL_TYPE -> "inbound"
            CallLog.Calls.OUTGOING_TYPE -> "outbound"
            else -> null
        }
        val label = record.cachedName?.takeIf(String::isNotBlank) ?: record.number
        val title = if (missed) "Missed call" else "Call"
        return ProactiveEvent(
            sourceId = FullProactiveSources.CALL_LOG,
            dedupKey = ProactiveNormalizer.dedupKey(
                FullProactiveSources.CALL_LOG,
                listOf(record.number, label, title, record.type.toString(), record.durationSeconds.toString()).joinToString("\u001f"),
                record.dateMillis,
            ),
            receivedAtMillis = record.dateMillis,
            observedAtMillis = observedAtMillis,
            appLabel = "Phone",
            sender = label,
            title = title,
            body = null,
            category = if (missed) "missed_call" else "call",
            direction = direction,
        )
    }
}
