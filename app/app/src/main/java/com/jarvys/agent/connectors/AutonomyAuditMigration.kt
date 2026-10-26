package com.jarvys.agent.connectors

import org.json.JSONTokener
import java.util.UUID

/** Local audit facts; never authorization to execute an operation. */
data class AutonomyAuditRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestampMillis: Long,
    val connectorId: String,
    val connectorName: String,
    val operationName: String,
    val operationLabel: String,
    val summary: String,
)

/** Whole-source validation: no malformed row may disappear when legacy storage is retired. */
internal object LegacyAutonomyAuditParser {
    fun parse(source: String): List<AutonomyAuditRecord> {
        try {
            // Android JSONTokener is lenient about comments/control characters; this schema is not.
            var quotedRegion = false
            var escaped = false
            var unicodeDigits = 0
            for (character in source) {
                if (quotedRegion) {
                    require(character.code >= 0x20)
                    if (unicodeDigits > 0) {
                        require(character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F')
                        unicodeDigits--
                    } else if (escaped) {
                        require(character in "\"\\/bfnrtu")
                        if (character == 'u') unicodeDigits = 4
                        escaped = false
                    }
                    else if (character == '\\') escaped = true
                    else if (character == '"') quotedRegion = false
                } else if (character == '"') quotedRegion = true
                else require(character in "{}[]:,-0123456789 \t\r\n")
            }
            require(!quotedRegion)
            val parser = JSONTokener(source)
            val fields = setOf("id", "timestampMillis", "connectorId", "connectorName",
                "operationName", "operationLabel", "summary")
            fun quoted(): String {
                require(parser.nextClean() == '"')
                return parser.nextString('"')
            }
            fun timestamp(): Long {
                val token = StringBuilder()
                var character = parser.nextClean()
                while (character != '\u0000' && character != ',' && character != '}' && !character.isWhitespace()) {
                    token.append(character)
                    character = parser.next()
                }
                if (character == ',' || character == '}') parser.back()
                val number = token.toString()
                require(Regex("-?(0|[1-9][0-9]*)").matches(number))
                return requireNotNull(number.toLongOrNull())
            }
            require(parser.nextClean() == '[')
            val result = LinkedHashMap<String, AutonomyAuditRecord>()
            if (parser.nextClean() != ']') {
                parser.back()
                while (true) {
                    require(parser.nextClean() == '{')
                    val values = LinkedHashMap<String, Any>()
                    while (true) {
                        val key = quoted()
                        require(key in fields && key !in values)
                        require(parser.nextClean() == ':')
                        values[key] = if (key == "timestampMillis") timestamp() else quoted()
                        when (parser.nextClean()) {
                            '}' -> break
                            ',' -> Unit
                            else -> error("Invalid audit separator")
                        }
                    }
                    require(values.keys == fields)
                    fun text(key: String) = values.getValue(key) as String
                    val record = AutonomyAuditRecord(text("id").also { require(it.isNotBlank()) },
                        values.getValue("timestampMillis") as Long,
                        text("connectorId").also { require(it.isNotBlank()) }, text("connectorName"),
                        text("operationName").also { require(it.isNotBlank()) }, text("operationLabel"), text("summary"))
                    val previous = result.putIfAbsent(record.id, record)
                    require(previous == null || previous == record)
                    when (parser.nextClean()) {
                        ']' -> break
                        ',' -> Unit
                        else -> error("Invalid audit separator")
                    }
                }
            }
            require(parser.nextClean() == '\u0000')
            return result.values.toList()
        } catch (_: Exception) {
            // Do not include JSON or field values in diagnostics.
            throw IllegalStateException("Legacy audit requires repair; original data was preserved")
        }
    }
}

internal interface LegacyAutonomyAuditSource {
    fun migrated(): Boolean
    fun original(): String?
    /** Check the original snapshot and durably commit removal + marker together. */
    fun complete(expectedOriginal: String?): Boolean
}

internal fun interface AutonomyAuditMigrationTarget {
    /** Atomic, idempotent batch; an existing ID with different facts must reject the whole batch. */
    fun importRecords(records: List<AutonomyAuditRecord>)
}

internal class AutonomyAuditMigration(
    private val source: LegacyAutonomyAuditSource,
    private val target: AutonomyAuditMigrationTarget,
    private val migrationLock: Any,
) {
    fun migrate() = synchronized(migrationLock) {
        if (source.migrated()) return@synchronized
        val original = source.original()
        val rows = LegacyAutonomyAuditParser.parse(original ?: "[]")
        target.importRecords(rows.filterNot { it.connectorId in setOf("email", "sms_draft", "native") })
        check(source.complete(original)) { "Audit imported; legacy cleanup remains pending" }
    }
}
