package com.jarvys.agent.connectors

import android.Manifest
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.StopController
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class LocationConnectorTest {
    private class Preferences : ConnectorConnectionPreferences {
        private val values = mutableMapOf<String, Boolean>()
        override fun isConnected(id: String) = values[id] == true
        override fun setConnected(id: String, connected: Boolean) { values[id] = connected }
    }

    private class FakeLocationGateway : DeviceLocationGateway {
        var enabled = true
        var visible = true
        var lastKnown = emptyList<DeviceLocationFix>()
        var currentAttempt: LocationAttempt = LocationAttempt.Unavailable(LocationConnector.REASON_NO_FIX)
        var geocoderPresent = true
        var geocoderAttempt: GeocoderAttempt = GeocoderAttempt.Found(listOf("Test City, Test Country"))
        var currentCalls = 0
        var geocoderCalls = 0
        var onCurrent: ((CancellationToken) -> LocationAttempt)? = null

        override fun isLocationEnabled() = enabled
        override fun isAppActivityVisible() = visible
        override fun lastKnownLocations() = lastKnown
        override fun currentLocation(timeoutMillis: Long, token: CancellationToken): LocationAttempt {
            currentCalls++
            return onCurrent?.invoke(token) ?: currentAttempt
        }
        override fun isGeocoderPresent() = geocoderPresent
        var lastGeocodePrecision: String? = null
        override fun reverseGeocode(latitude: Double, longitude: Double, precision: String,
                                    timeoutMillis: Long, token: CancellationToken): GeocoderAttempt {
            geocoderCalls++
            lastGeocodePrecision = precision
            return geocoderAttempt
        }
    }

    private val now = 1_800_000_000_000L
    private val token = CancellationToken.uncancellable()

    private fun connector(
        gateway: FakeLocationGateway,
        coarse: Boolean = true,
        fine: Boolean = false,
    ) = LocationConnector(gateway, { coarse }, { fine }, { now }, { java.util.TimeZone.getTimeZone("America/Los_Angeles") })

    private fun args(precision: String? = null, maxAge: Int? = null) = JSONObject().apply {
        precision?.let { put("precision", it) }
        maxAge?.let { put("max_age_seconds", it) }
    }

    private fun fix(time: Long = now, provider: String = "network", accuracy: Float = 120f) =
        DeviceLocationFix(37.4219999, -122.0840575, accuracy, time, provider)

    @Test fun schemasHaveTypedPrecisionAndBoundedAgeDefaults() {
        val definition = LocationConnector.definition(FakeLocationGateway(), { true }, { false })
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), definition.readPermissions)
        assertEquals(setOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            definition.connectionPermissions.toSet())
        val current = definition.operations.first { it.name == LocationConnector.GET_CURRENT_LOCATION }
        val schema = current.inputSchema
        assertEquals("string", schema.getJSONObject("properties").getJSONObject("precision").getString("type"))
        assertEquals(listOf("approximate", "precise"), (0 until schema.getJSONObject("properties")
            .getJSONObject("precision").getJSONArray("enum").length()).map {
            schema.getJSONObject("properties").getJSONObject("precision").getJSONArray("enum").getString(it)
        })
        assertEquals("integer", schema.getJSONObject("properties").getJSONObject("max_age_seconds").getString("type"))
        assertEquals(LocationConnector.DEFAULT_MAX_AGE_SECONDS,
            schema.getJSONObject("properties").getJSONObject("max_age_seconds").getInt("default"))
    }

    @Test fun connectedLocationGuidanceEnforcesMinimizationAndNoPersistentCoordinates() {
        val note = LocationConnector.definition(FakeLocationGateway(), { true }, { true }).usageNote()

        assertTrue(note.contains("only when it is needed"))
        assertTrue(note.contains("Prefer approximate precision"))
        assertTrue(note.contains("Do not repeat exact coordinates"))
        assertTrue(note.contains("Do not save location in persistent memory"))
        assertTrue(note.contains("Do not include coordinates in text sent to third parties"))
        assertFalse(note.contains("such as"))
    }

    @Test fun approximateGrantConnectsAndRemainsConnectedWithoutFineGrant() {
        var coarse = false
        val definition = LocationConnector.definition(FakeLocationGateway(), { coarse }, { false })
        val registry = ConnectorRegistry.createForTests(
            Preferences(), { permission -> permission == Manifest.permission.ACCESS_COARSE_LOCATION && coarse },
        )
        registry.register(definition)
        assertEquals(ConnectorState.DISCONNECTED, registry.state(definition))

        coarse = true
        registry.connect(LocationConnector.ID)
        assertEquals(ConnectorState.CONNECTED, registry.state(definition))
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), definition.readPermissions)
        assertEquals(setOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            definition.connectionPermissions.toSet())

        coarse = false
        assertEquals(ConnectorState.PERMISSION_REVOKED, registry.state(definition))
    }

    @Test fun approximateDefaultRoundsCoordinatesAndReturnsLocationMetadata() {
        val gateway = FakeLocationGateway().apply { lastKnown = listOf(fix()) }
        val result = connector(gateway).invoke(LocationConnector.GET_CURRENT_LOCATION, JSONObject(), token)

        assertEquals("ok", result.getString("status"))
        assertEquals("approximate", result.getString("precision_used"))
        assertEquals(37.422, result.getDouble("latitude"), 0.0)
        assertEquals(-122.084, result.getDouble("longitude"), 0.0)
        assertEquals(0L, result.getLong("age_seconds"))
        assertEquals("America/Los_Angeles", result.getString("timezone"))
        assertEquals("-08:00", result.getString("utc_offset"))
        assertEquals("network", result.getString("provider"))
        assertFalse(result.getBoolean("untrusted_content"))
        assertFalse(result.has("items"))
    }

    @Test fun preciseRequestWithoutFinePermissionDegradesToApproximate() {
        val gateway = FakeLocationGateway().apply { currentAttempt = LocationAttempt.Found(fix(accuracy = 4f)) }
        val result = connector(gateway, coarse = true, fine = false).invoke(
            LocationConnector.GET_CURRENT_LOCATION, args("precise"), token,
        )

        assertEquals("precise", result.getString("precision_requested"))
        assertEquals("approximate", result.getString("precision_used"))
        assertTrue(result.getBoolean("precision_degraded"))
        assertEquals(37.422, result.getDouble("latitude"), 0.0)
    }

    @Test fun preciseRequestWithFinePermissionUsesFiveDecimalPlaces() {
        val gateway = FakeLocationGateway().apply { lastKnown = listOf(fix(accuracy = 2f)) }
        val result = connector(gateway, fine = true).invoke(
            LocationConnector.GET_CURRENT_LOCATION, args("precise"), token,
        )

        assertEquals("precise", result.getString("precision_used"))
        assertFalse(result.getBoolean("precision_degraded"))
        assertEquals(37.422, result.getDouble("latitude"), 0.0) // 37.4219999 rounds to 37.42200.
        assertEquals(-122.08406, result.getDouble("longitude"), 0.0)
    }

    @Test fun maxAgeAcceptsFreshCachedFixButFallsBackToCurrentForStaleFix() {
        val fresh = FakeLocationGateway().apply { lastKnown = listOf(fix(now - 20_000L)) }
        val freshResult = connector(fresh).invoke(LocationConnector.GET_CURRENT_LOCATION, args(maxAge = 30), token)
        assertEquals(0, fresh.currentCalls)
        assertEquals(20L, freshResult.getLong("age_seconds"))

        val stale = FakeLocationGateway().apply {
            lastKnown = listOf(fix(now - 31_000L))
            currentAttempt = LocationAttempt.Found(fix(now - 1_000L, "gps", 3f))
        }
        val current = connector(stale).invoke(LocationConnector.GET_CURRENT_LOCATION, args(maxAge = 30), token)
        assertEquals(1, stale.currentCalls)
        assertEquals("gps", current.getString("provider"))
        assertEquals(1L, current.getLong("age_seconds"))
    }

    @Test fun eachUnavailableReasonIsReturnedAsStructuredStatus() {
        val missingPermission = connector(FakeLocationGateway(), coarse = false)
            .invoke(LocationConnector.GET_CURRENT_LOCATION, JSONObject(), token)
        assertUnavailable(missingPermission, LocationConnector.REASON_PERMISSION_MISSING)

        val disabledGateway = FakeLocationGateway().apply { enabled = false }
        assertUnavailable(connector(disabledGateway).invoke(LocationConnector.GET_CURRENT_LOCATION, JSONObject(), token),
            LocationConnector.REASON_LOCATION_DISABLED)

        val backgroundGateway = FakeLocationGateway().apply { visible = false }
        assertUnavailable(connector(backgroundGateway).invoke(LocationConnector.GET_CURRENT_LOCATION, JSONObject(), token),
            LocationConnector.REASON_APP_IN_BACKGROUND)

        val noFixGateway = FakeLocationGateway().apply {
            currentAttempt = LocationAttempt.Unavailable(LocationConnector.REASON_NO_FIX)
        }
        assertUnavailable(connector(noFixGateway).invoke(LocationConnector.GET_CURRENT_LOCATION, args(maxAge = 0), token),
            LocationConnector.REASON_NO_FIX)

        val timeoutGateway = FakeLocationGateway().apply {
            currentAttempt = LocationAttempt.Unavailable(LocationConnector.REASON_TIMEOUT)
        }
        assertUnavailable(connector(timeoutGateway).invoke(LocationConnector.GET_CURRENT_LOCATION, args(maxAge = 0), token),
            LocationConnector.REASON_TIMEOUT)

        val geocoderMissing = FakeLocationGateway().apply {
            lastKnown = listOf(fix())
            geocoderPresent = false
        }
        assertUnavailable(connector(geocoderMissing).invoke(LocationConnector.REVERSE_GEOCODE_CURRENT_LOCATION, JSONObject(), token),
            LocationConnector.REASON_GEOCODER_UNAVAILABLE)
        assertEquals(0, geocoderMissing.geocoderCalls)
    }

    @Test fun geocoderTextUsesBoundedUntrustedEnvelopeAndNumericCoordinatesStayStructured() {
        val address = "A".repeat(LocationConnector.MAX_GEOCODER_TEXT_CHARS + 200)
        val gateway = FakeLocationGateway().apply {
            lastKnown = listOf(fix())
            geocoderAttempt = GeocoderAttempt.Found(listOf(address))
        }
        val result = connector(gateway).invoke(LocationConnector.REVERSE_GEOCODE_CURRENT_LOCATION, JSONObject(), token)

        assertEquals("ok", result.getString("status"))
        assertEquals("approximate", gateway.lastGeocodePrecision)
        assertTrue(result.getBoolean("untrusted_content"))
        assertTrue(result.get("latitude") is Number)
        assertTrue(result.get("longitude") is Number)
        assertTrue(result.getJSONArray("items").getJSONObject(0).getString("address").length <=
            LocationConnector.MAX_GEOCODER_TEXT_CHARS)
        assertEquals("android.location.geocoder", result.getString("source"))
    }

    @Test fun geocoderFailureIsUnavailableWithoutUntrustedText() {
        val gateway = FakeLocationGateway().apply {
            lastKnown = listOf(fix())
            geocoderAttempt = GeocoderAttempt.Unavailable(LocationConnector.REASON_GEOCODER_FAILED)
        }
        val result = connector(gateway).invoke(LocationConnector.REVERSE_GEOCODE_CURRENT_LOCATION, JSONObject(), token)
        assertUnavailable(result, LocationConnector.REASON_GEOCODER_FAILED)
        assertFalse(result.has("items"))
    }

    @Test fun emptyAndTimedOutGeocoderResultsStayUnavailable() {
        val empty = FakeLocationGateway().apply {
            lastKnown = listOf(fix())
            geocoderAttempt = GeocoderAttempt.Found(emptyList())
        }
        assertUnavailable(connector(empty).invoke(LocationConnector.REVERSE_GEOCODE_CURRENT_LOCATION, JSONObject(), token),
            LocationConnector.REASON_GEOCODER_NO_RESULT)

        val timedOut = FakeLocationGateway().apply {
            lastKnown = listOf(fix())
            geocoderAttempt = GeocoderAttempt.Unavailable(LocationConnector.REASON_GEOCODER_TIMEOUT)
        }
        assertUnavailable(connector(timedOut).invoke(LocationConnector.REVERSE_GEOCODE_CURRENT_LOCATION, JSONObject(), token),
            LocationConnector.REASON_GEOCODER_TIMEOUT)
    }

    @Test fun stopCancellationReachesInFlightLocationRequest() {
        val stop = StopController.getInstance()
        val runToken = requireNotNull(stop.beginRun())
        var cancelled = false
        val gateway = FakeLocationGateway().apply {
            onCurrent = { activeToken ->
                activeToken.registerCancelAction { cancelled = true }
                stop.stopRun()
                LocationAttempt.Unavailable(LocationConnector.REASON_TIMEOUT)
            }
        }

        assertTrue(runCatching {
            connector(gateway).invoke(LocationConnector.GET_CURRENT_LOCATION, args(maxAge = 0), runToken)
        }.exceptionOrNull() is CancellationException)
        assertTrue(cancelled)
    }

    private fun assertUnavailable(result: JSONObject, reason: String) {
        assertEquals("unavailable", result.getString("status"))
        assertEquals(reason, result.getString("reason"))
        assertFalse(result.getBoolean("untrusted_content"))
    }
}
