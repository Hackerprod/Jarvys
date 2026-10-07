package com.jarvys.agent.connectors

import android.Manifest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CalendarConnectorTest {
    private val now = 1_700_000_000_000L

    private class FakeGateway : CalendarGateway {
        var listed = listOf<CalendarDescriptor>()
        var queriedStart = -1L
        var queriedEnd = -1L
        var queriedLimit = -1
        var queriedIds: List<Long>? = null
        var results = emptyList<CalendarEventRecord>()
        var inserted: CalendarEventDraft? = null
        override fun calendars() = listed
        override fun instances(startTimeMs: Long, endTimeMs: Long, calendarIds: List<Long>?, limit: Int): List<CalendarEventRecord> {
            queriedStart = startTimeMs; queriedEnd = endTimeMs; queriedIds = calendarIds; queriedLimit = limit
            return results.take(limit)
        }
        override fun insert(draft: CalendarEventDraft): Long { inserted = draft; return 712L }
    }

    @Test fun searchDefaultsToSevenDaysAndCapsItemsAndTextAsUntrusted() {
        val gateway = FakeGateway()
        gateway.results = (1..21).map { index ->
            CalendarEventRecord(index.toLong(), "Event $index", now, now + 3_600_000, false,
                "Room", "Personal", "Notes")
        }
        val connector = connector(gateway, setOf(Manifest.permission.READ_CALENDAR))
        val result = connector.invoke(CalendarConnector.SEARCH, JSONObject(), com.jarvys.agent.CancellationToken.uncancellable())
        assertEquals(now, gateway.queriedStart)
        assertEquals(now + CalendarConnector.DEFAULT_RANGE_MS, gateway.queriedEnd)
        assertEquals(CalendarConnector.DEFAULT_LIMIT + 1, gateway.queriedLimit)
        assertEquals(20, result.getJSONArray("events").length())
        assertTrue(result.getBoolean("untrusted_content"))
        assertEquals("android.calendar", result.getString("source"))
        assertTrue(result.getBoolean("truncated"))
        val first = result.getJSONArray("events").getJSONObject(0)
        assertEquals("Event 1", first.getString("title"))
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size <= CalendarResultBounds.MAX_RESULT_BYTES)
    }

    @Test fun truncatesEveryCalendarTextFieldAndBoundsPayloadSize() {
        val gateway = FakeGateway()
        gateway.results = listOf(CalendarEventRecord(1, "T".repeat(250), now, now + 1_000, false,
            "L".repeat(250), "C".repeat(250), "N".repeat(350)))
        val result = connector(gateway, setOf(Manifest.permission.READ_CALENDAR))
            .invoke(CalendarConnector.SEARCH, JSONObject(), token())
        assertTrue(result.getBoolean("untrusted_content"))
        assertTrue(result.getBoolean("truncated"))
        val first = result.getJSONArray("events").getJSONObject(0)
        assertEquals(200, first.getString("title").length)
        assertEquals(200, first.getString("location").length)
        assertEquals(200, first.getString("calendarName").length)
        assertEquals(300, first.getString("notes").length)
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size <= CalendarResultBounds.MAX_RESULT_BYTES)
    }

    @Test fun rejectsRangeOverNinetyDaysAndInvalidLimit() {
        val connector = connector(FakeGateway(), setOf(Manifest.permission.READ_CALENDAR))
        val tooWide = JSONObject().put("startTimeMs", now).put("endTimeMs", now + CalendarConnector.MAX_RANGE_MS + 1)
        assertTrue(runCatching { connector.invoke(CalendarConnector.SEARCH, tooWide, token()) }.exceptionOrNull()?.message.orEmpty().contains("90 días"))
        val badLimit = JSONObject().put("limit", 51)
        assertTrue(runCatching { connector.invoke(CalendarConnector.SEARCH, badLimit, token()) }.exceptionOrNull()?.message.orEmpty().contains("limit"))
    }

    @Test fun modelSchemasDeclareTypedFieldsRequiredValuesAndLimits() {
        val search = CalendarConnector.searchSchema()
        val searchProperties = search.getJSONObject("properties")
        assertEquals("integer", searchProperties.getJSONObject("startTimeMs").getString("type"))
        assertEquals(1, searchProperties.getJSONObject("limit").getInt("minimum"))
        assertEquals(50, searchProperties.getJSONObject("limit").getInt("maximum"))
        assertEquals("integer", searchProperties.getJSONObject("calendarIds").getJSONObject("items").getString("type"))
        assertEquals(0, search.getJSONArray("required").length())
        val create = CalendarConnector.createSchema()
        assertEquals(listOf("title", "startTimeMs", "endTimeMs"),
            (0 until create.getJSONArray("required").length()).map { create.getJSONArray("required").getString(it) })
        assertEquals(200, create.getJSONObject("properties").getJSONObject("title").getInt("maxLength"))
        assertEquals(300, create.getJSONObject("properties").getJSONObject("notes").getInt("maxLength"))
    }

    @Test fun writeNeedsApprovalAndChoosesFirstEligibleCalendar() {
        val gateway = FakeGateway().apply {
            listed = listOf(
                CalendarDescriptor(1, "Hidden", false, true, 700),
                CalendarDescriptor(2, "Not synced", true, false, 700),
                CalendarDescriptor(3, "Read only", true, true, 400),
                CalendarDescriptor(4, "Writable", true, true, 500),
            )
        }
        val connector = CalendarConnector(gateway, { true }, { now }, { TimeZone.getTimeZone("America/Argentina/Buenos_Aires") })
        val args = JSONObject().put("title", "Review").put("startTimeMs", now + 1_000)
            .put("endTimeMs", now + 3_600_000).put("location", "Office")
        val prepared = connector.prepareWrite(CalendarConnector.CREATE, args, token())
        assertEquals("Writable", prepared.approval.lines[2].removePrefix("Calendar: "))
        val approvedGateway = FakeGateway().apply { listed = gateway.listed }
        val approved = CalendarConnector(approvedGateway, { true }, { now }, { TimeZone.getTimeZone("America/Argentina/Buenos_Aires") })
        val result = approved.invokePrepared(CalendarConnector.CREATE, prepared.executionArguments, prepared, token())
        assertEquals(4L, approvedGateway.inserted?.calendarId)
        assertEquals("America/Argentina/Buenos_Aires", approvedGateway.inserted?.timeZone)
        assertEquals(712L, result.getLong("eventId"))
        assertTrue(result.getBoolean("untrusted_content"))
    }

    @Test fun allDayEventsUseUtcAndRejectEmptyTitleOrUnreasonableDates() {
        val gateway = FakeGateway().apply { listed = listOf(CalendarDescriptor(5, "Writable", true, true, 700)) }
        val connector = CalendarConnector(gateway, { true }, { now })
        val args = JSONObject().put("title", "Holiday").put("startTimeMs", now + 1_000)
            .put("endTimeMs", now + 86_400_000).put("allDay", true)
        val prepared = connector.prepareWrite(CalendarConnector.CREATE, args, token())
        connector.invokePrepared(CalendarConnector.CREATE, prepared.executionArguments, prepared, token())
        assertEquals("UTC", gateway.inserted?.timeZone)
        assertTrue(runCatching {
            CalendarConnector.validateEventDate(now - 400L * 24 * 60 * 60 * 1000, now, now)
        }.isFailure)
        assertTrue(runCatching {
            connector.prepareWrite(CalendarConnector.CREATE, args.put("title", "  "), token())
        }.exceptionOrNull()?.message.orEmpty().contains("vacío"))
    }

    @Test fun createFailsClearlyWhenNoVisibleSyncedWritableCalendarExists() {
        val connector = CalendarConnector(FakeGateway(), { true }, { now })
        val arguments = JSONObject().put("title", "Review").put("startTimeMs", now + 1_000)
            .put("endTimeMs", now + 2_000)
        val failure = runCatching { connector.prepareWrite(CalendarConnector.CREATE, arguments, token()) }.exceptionOrNull()
        assertEquals("No hay un calendario visible, sincronizado y escribible disponible", failure?.message)
    }

    @Test fun registryRequiresApprovalAndWritePermissionBeforeInserting() {
        val gateway = FakeGateway().apply {
            listed = listOf(CalendarDescriptor(9, "Writable", true, true, 700))
        }
        var writeGranted = false
        val runtime = CalendarConnector(gateway, { permission ->
            permission == Manifest.permission.READ_CALENDAR || (permission == Manifest.permission.WRITE_CALENDAR && writeGranted)
        }, { now })
        val operation = ConnectorOperation(
            name = CalendarConnector.CREATE, description = "Create", inputSchema = JSONObject(),
            write = true, displayLabel = "Create Calendar Event",
            requiredPermissions = listOf(Manifest.permission.WRITE_CALENDAR),
        )
        val definition = ConnectorDefinition("calendar", "Calendar", "1", "Calendar",
            operations = listOf(operation), runtime = runtime,
            readPermissions = listOf(Manifest.permission.READ_CALENDAR),
            writePermissions = listOf(Manifest.permission.WRITE_CALENDAR))
        val preferences = object : ConnectorConnectionPreferences {
            var connected = false
            override fun isConnected(id: String) = connected
            override fun setConnected(id: String, connected: Boolean) { this.connected = connected }
        }
        val rejectedGate = gateResolving(ApprovalDecision.DENIED) { }
        val registry = ConnectorRegistry.createForTests(preferences,
            { it == Manifest.permission.READ_CALENDAR || (it == Manifest.permission.WRITE_CALENDAR && writeGranted) }, rejectedGate)
        registry.register(definition)
        registry.connect("calendar")
        val arguments = JSONObject().put("title", "Review").put("startTimeMs", now + 10_000)
            .put("endTimeMs", now + 20_000)
        val rejection = runCatching { registry.invoke(definition, operation, arguments, token()) }.exceptionOrNull()
        assertTrue(rejection?.message.orEmpty().startsWith("El usuario rechazó la acción; no la reintentes"))
        assertEquals(null, gateway.inserted)

        var captured: ApprovalSummary? = null
        val approvedGate = gateResolving(ApprovalDecision.APPROVED) {
            captured = it
            writeGranted = true
        }
        val allowedRegistry = ConnectorRegistry.createForTests(preferences,
            { it == Manifest.permission.READ_CALENDAR || (it == Manifest.permission.WRITE_CALENDAR && writeGranted) }, approvedGate)
        allowedRegistry.register(definition)
        val allowedResult = allowedRegistry.invoke(definition, operation, arguments, token())
        assertEquals(Manifest.permission.WRITE_CALENDAR, captured?.permission)
        assertEquals(9L, gateway.inserted?.calendarId)
        assertEquals(712L, allowedResult.getLong("eventId"))
    }

    @Test fun resultTotalSizeIsBoundedEvenForManyLargeFields() {
        val events = JSONArray()
        repeat(50) { index -> events.put(JSONObject().put("id", index).put("title", "x".repeat(200))
            .put("location", "y".repeat(200)).put("calendarName", "z".repeat(200)).put("notes", "n".repeat(300))) }
        val result = CalendarResultBounds.envelope("android.calendar", events)
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size <= CalendarResultBounds.MAX_RESULT_BYTES)
        assertTrue(result.getBoolean("truncated"))
    }

    private fun connector(gateway: FakeGateway, permissions: Set<String>) = CalendarConnector(
        gateway, { it in permissions }, { now }, { TimeZone.getTimeZone("UTC") },
    )
    private fun token() = com.jarvys.agent.CancellationToken.uncancellable()

    private fun gateResolving(decision: ApprovalDecision, beforeResolve: (ApprovalSummary) -> Unit): ApprovalGate {
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(1_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                beforeResolve(summary)
                gate.resolve(id, decision)
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        return gate
    }
}
