package com.jarvys.agent.proactive

import android.content.Context
import com.jarvys.agent.AppLanguageRuntime
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class LocalizedDescription(val en: String, val es: String)

data class SourceSpec(
    val id: String,
    val descriptionForModel: LocalizedDescription,
    val requiredPermission: String?,
    val trigger: String,
)

object ProactiveSources {
    const val NOTIFICATIONS = "notifications"

    val notifications = SourceSpec(
        NOTIFICATIONS,
        LocalizedDescription(
            "A device notification. Use the preformatted app_label, received_local, received_weekday, age_human and source_kind fields verbatim when useful.",
            "Una notificación del dispositivo. Usa literalmente los campos ya formateados app_label, received_local, received_weekday, age_human y source_kind cuando sean útiles.",
        ),
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        "notification_listener",
    )
}

data class ProactiveEvent(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val dedupKey: String,
    val receivedAtMillis: Long,
    val observedAtMillis: Long,
    val appPackage: String? = null,
    val appLabel: String? = null,
    val sender: String? = null,
    val title: String? = null,
    val body: String? = null,
    val category: String,
    val direction: String? = null,
    val state: String = PENDING,
    val discardReason: String? = null,
    val ongoing: Boolean = false,
    val foregroundService: Boolean = false,
    val groupSummary: Boolean = false,
    val androidCategory: String? = null,
    val sourceInstanceKey: String? = null,
    val conversationThread: String? = null,
    val prefilterMark: String? = null,
) {
    init {
        require(state == PENDING || state == PROCESSED || state == DISCARDED)
        require(state == DISCARDED || discardReason == null)
    }

    /** Returns a content-only projection; callers must pass this projection, never the stored event, to a model. */
    fun modelSafe(context: Context, nowMillis: Long = System.currentTimeMillis()): ProactiveModelEvent {
        val localized = AppLanguageRuntime.localizedContext(context.applicationContext)
        val displayApp = appLabel?.takeIf(String::isNotBlank) ?: when (sourceId) {
            "sms" -> localized.getString(com.jarvys.agent.R.string.proactive_source_sms)
            "call_log" -> localized.getString(com.jarvys.agent.R.string.proactive_source_call)
            else -> sourceId
        }
        return ProactiveModelEvent(
            id = id,
            appLabel = ProactiveRedactor.redactForModel(displayApp),
            receivedLocal = ProactivePrompt.formatReceivedLocal(localized, receivedAtMillis),
            receivedWeekday = ProactivePrompt.formatReceivedWeekday(localized, receivedAtMillis),
            ageHuman = ProactivePrompt.formatAgeHuman(localized, receivedAtMillis, nowMillis),
            sourceKind = when (sourceId) {
                ProactiveSources.NOTIFICATIONS -> "notification"
                else -> sourceId
            },
            sender = sender?.let(ProactiveRedactor::redactForModel),
            title = title?.let(ProactiveRedactor::redactForModel),
            body = body?.let(ProactiveRedactor::redactForModel),
            category = category,
            direction = direction,
        )
    }

    companion object {
        const val PENDING = "pending"
        const val PROCESSED = "processed"
        const val DISCARDED = "discarded"
    }
}

data class ProactiveModelEvent(
    val id: String,
    val appLabel: String?,
    val receivedLocal: String,
    val receivedWeekday: String,
    val ageHuman: String,
    val sourceKind: String,
    val sender: String?,
    val title: String?,
    val body: String?,
    val category: String,
    val direction: String?,
)

data class NotificationInput(
    val sourceId: String = ProactiveSources.NOTIFICATIONS,
    val receivedAtMillis: Long,
    val observedAtMillis: Long,
    val appPackage: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val androidCategory: String?,
    val messagingSender: String? = null,
    val isCallStyle: Boolean = false,
    val isMissedCall: Boolean = false,
    val ongoing: Boolean = false,
    val foregroundService: Boolean = false,
    val groupSummary: Boolean = false,
    val notificationKey: String? = null,
    val conversationThread: String? = null,
)

object ProactiveNormalizer {
    fun notification(input: NotificationInput): ProactiveEvent {
        val normalizedBody = normalizeText(input.body)
        val normalizedTitle = normalizeText(input.title)
        val sender = input.messagingSender?.let(::normalizeText)?.takeIf(String::isNotEmpty)
        val key = dedupKey(
            input.sourceId,
            listOf(input.appPackage, sender.orEmpty(), normalizedTitle, normalizedBody).joinToString("\u001f"),
            input.receivedAtMillis,
        )
        return ProactiveEvent(
            sourceId = input.sourceId,
            dedupKey = key,
            receivedAtMillis = input.receivedAtMillis,
            observedAtMillis = input.observedAtMillis,
            appPackage = input.appPackage,
            appLabel = input.appLabel,
            sender = sender,
            title = normalizedTitle,
            body = normalizedBody,
            category = notificationCategory(input.androidCategory, input.isCallStyle, input.isMissedCall),
            ongoing = input.ongoing,
            foregroundService = input.foregroundService,
            groupSummary = input.groupSummary,
            androidCategory = input.androidCategory,
            sourceInstanceKey = input.notificationKey,
            conversationThread = input.conversationThread,
        )
    }

    fun dedupKey(sourceId: String, identity: String, receivedAtMillis: Long): String {
        val bytes = "$sourceId\u0000$identity\u0000$receivedAtMillis".toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    fun normalizeText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun notificationCategory(category: String?, isCallStyle: Boolean, missed: Boolean): String = when {
        missed || category.equals("missed_call", ignoreCase = true) -> "missed_call"
        isCallStyle || category.equals("call", ignoreCase = true) -> "call"
        category.equals("msg", ignoreCase = true) || category.equals("message", ignoreCase = true) -> "msg"
        category.equals("email", ignoreCase = true) -> "email"
        category.equals("social", ignoreCase = true) -> "social"
        else -> "other"
    }
}

object ProactiveRedactor {
    // Verification tokens are commonly 4–8 digits; redact only when a nearby label establishes code context.
    private val contextualCode = Regex(
        "(?i)(\\b(?:otp|one[- ]time(?: password)?|verification|verify|security|login|authentication|auth|code)\\b[^\\d]{0,32})(\\d{4,8})\\b",
    )
    // Payment-card candidates follow the common 13–19 digit PAN range and are hidden only when Luhn-valid.
    private val cardCandidate = Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)")
    // Account identifiers have no shared checksum/length standard; eight digits is the conservative long-run threshold.
    private val longAccountCandidate = Regex("(?<!\\d)(?:\\d[ -]?){7,}\\d(?!\\d)")

    @JvmStatic
    fun redactForModel(text: String): String {
        val codesReplaced = contextualCode.replace(text) { match ->
            match.groupValues[1] + "[código oculto]"
        }
        val cardsReplaced = cardCandidate.replace(codesReplaced) { match ->
            if (isLuhnValid(match.value)) "[tarjeta oculta]" else match.value
        }
        val accountsReplaced = longAccountCandidate.replace(cardsReplaced) { match ->
            if (match.value.any(Char::isLetter)) match.value else "[cuenta oculta]"
        }
        return accountsReplaced
    }

    private fun isLuhnValid(raw: String): Boolean {
        val digits = raw.filter(Char::isDigit)
        if (digits.length !in 13..19) return false
        val sum = digits.reversed().mapIndexed { index, char ->
            var digit = char.digitToInt()
            if (index % 2 == 1) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            digit
        }.sum()
        return sum % 10 == 0
    }
}
