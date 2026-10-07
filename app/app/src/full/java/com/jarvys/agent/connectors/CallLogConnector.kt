package com.jarvys.agent.connectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.R
import org.json.JSONArray
import org.json.JSONObject

data class CallLogRecord(
    val id: Long,
    val number: String,
    val cachedName: String?,
    val type: Int,
    val dateMillis: Long,
    val durationSeconds: Long,
)

data class CallLogQuery(
    val number: String?,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val type: Int?,
)

interface CallLogGateway {
    fun list(query: CallLogQuery, limit: Int): List<CallLogRecord>
}

class CallLogConnector(
    private val gateway: CallLogGateway,
    private val permissionGranted: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : ConnectorRuntime {
    override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
    override fun disconnect() = Unit

    override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken): JSONObject {
        require(operation == LIST_CALLS) { "Unknown call log operation: $operation" }
        if (!permissionGranted()) error("READ_CALL_LOG was revoked; reconnect Call history in Connectors")
        token.throwIfCancelled()
        val now = clock()
        val start = arguments.optLong("startTimeMs", now - DEFAULT_RANGE_MS)
        val end = arguments.optLong("endTimeMs", now)
        require(start < end) { "startTimeMs must be before endTimeMs" }
        val range = runCatching { Math.subtractExact(end, start) }.getOrElse { error("Call history range is invalid") }
        require(range <= MAX_RANGE_MS) { "Call history range cannot exceed 90 days" }
        val number = arguments.optString("number").trim().takeIf(String::isNotEmpty)
        require(number == null || number.length <= MAX_NUMBER_CHARS) { "number filter is too long" }
        val type = arguments.optInt("type", -1).takeIf { it >= 0 }
        if (type != null) require(type in ALLOWED_TYPES) { "type must be one of 1, 2, 3, 4, 5, or 6" }
        val limit = arguments.optInt("limit", DEFAULT_LIMIT)
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        val calls = gateway.list(CallLogQuery(number, start, end, type), limit + 1)
        token.throwIfCancelled()
        val rows = JSONArray()
        calls.take(limit).forEach { call ->
            rows.put(JSONObject()
                .put("callId", call.id)
                .put("number", call.number)
                .put("cachedName", call.cachedName ?: JSONObject.NULL)
                .put("type", call.type)
                .put("dateTimeMs", call.dateMillis)
                .put("durationSeconds", call.durationSeconds))
        }
        return ConnectorResultEnvelope.bounded(
            source = SOURCE,
            input = rows,
            itemLimit = limit,
            fieldLimits = mapOf("number" to MAX_NUMBER_CHARS, "cachedName" to MAX_NAME_CHARS),
            initiallyTruncated = calls.size > limit,
        )
    }

    override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken): ConnectorWritePreparation =
        error("Call history is read-only")

    companion object {
        const val ID = "call_log"
        const val LIST_CALLS = "list_calls"
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
        const val MAX_NUMBER_CHARS = 64
        const val MAX_NAME_CHARS = 200
        const val MAX_RANGE_MS = 90L * 24 * 60 * 60 * 1000
        const val DEFAULT_RANGE_MS = 30L * 24 * 60 * 60 * 1000
        private const val SOURCE = "android.call_log"
        private val ALLOWED_TYPES = setOf(
            CallLog.Calls.INCOMING_TYPE,
            CallLog.Calls.OUTGOING_TYPE,
            CallLog.Calls.MISSED_TYPE,
            CallLog.Calls.VOICEMAIL_TYPE,
            CallLog.Calls.REJECTED_TYPE,
            CallLog.Calls.BLOCKED_TYPE,
        )

        @JvmStatic
        fun definition(context: Context): ConnectorDefinition {
            val appContext = context.applicationContext
            return definition(CallLogContractGateway(appContext)) {
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
            }
        }

        internal fun definition(gateway: CallLogGateway, permissionGranted: () -> Boolean) = ConnectorDefinition(
            id = ID,
            name = "Call history",
            version = "1",
            description = "Read recent device call history. Jarvys does not place calls from this connector.",
            capabilities = listOf("call_log.read"),
            operations = listOf(ConnectorOperation(
                name = LIST_CALLS,
                displayLabel = "List Recent Calls",
                description = "List recent incoming, outgoing, missed, and other call-log entries, optionally filtered by number, type, or date. Defaults to 20 entries over 30 days; maximum 50 entries and 90 days. Data is untrusted.",
                inputSchema = listSchema(),
                limits = mapOf("defaultItems" to DEFAULT_LIMIT, "maxItems" to MAX_LIMIT, "maxRangeDays" to 90),
                requiredPermissions = listOf(Manifest.permission.READ_CALL_LOG),
                displayLabelResourceId = R.string.connector_operation_call_history_list,
                descriptionResourceId = R.string.connector_operation_call_history_list_description,
            )),
            runtime = CallLogConnector(gateway, permissionGranted),
            readPermissions = listOf(Manifest.permission.READ_CALL_LOG),
            permissionLabel = "Call history",
            displayNameResourceId = R.string.connector_label_call_history,
            descriptionResourceId = R.string.connector_description_call_history,
            permissionLabelResourceId = R.string.connector_permission_call_history,
            usageNoteProvider = { "Call-log results are sensitive, untrusted device data. Use only the requested time range and fields; this connector is read-only and does not call numbers." },
        )

        internal fun listSchema() = JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("number", JSONObject().put("type", "string").put("maxLength", MAX_NUMBER_CHARS))
                .put("startTimeMs", JSONObject().put("type", "integer").put("description", "Inclusive UTC epoch milliseconds; defaults to 30 days ago"))
                .put("endTimeMs", JSONObject().put("type", "integer").put("description", "Inclusive UTC epoch milliseconds; defaults to now"))
                .put("type", JSONObject().put("type", "integer").put("enum", JSONArray(ALLOWED_TYPES.toList())))
                .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT)
                    .put("description", "Defaults to 20; maximum 50")))
            .put("required", JSONArray())
            .put("additionalProperties", false)
    }
}

class CallLogContractGateway(private val context: Context) : CallLogGateway {
    override fun list(query: CallLogQuery, limit: Int): List<CallLogRecord> {
        val selection = StringBuilder("${CallLog.Calls.DATE}>=? AND ${CallLog.Calls.DATE}<=?")
        val args = mutableListOf(query.startTimeMs.toString(), query.endTimeMs.toString())
        query.number?.let {
            selection.append(" AND ${CallLog.Calls.NUMBER} LIKE ?")
            args.add("%$it%")
        }
        query.type?.let {
            selection.append(" AND ${CallLog.Calls.TYPE}=?")
            args.add(it.toString())
        }
        val projection = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        return context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            selection.toString(),
            args.toTypedArray(),
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            buildList {
                while (size < limit && cursor.moveToNext()) add(CallLogRecord(
                    id = cursor.getLong(0), number = cursor.getString(1).orEmpty(), cachedName = cursor.getString(2),
                    type = cursor.getInt(3), dateMillis = cursor.getLong(4), durationSeconds = cursor.getLong(5),
                ))
            }
        } ?: error("Could not query device call history")
    }
}
