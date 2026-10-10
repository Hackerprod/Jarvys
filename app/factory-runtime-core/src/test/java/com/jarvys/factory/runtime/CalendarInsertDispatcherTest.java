package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.CapabilityCatalog;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CalendarInsertDispatcherTest {
    private FactoryConfig config(String caps) throws Exception { return FactoryConfig.parsePreview(FactoryDispatcherTest.configuration(caps)); }
    private boolean contains(JSONArray array, String expected) throws Exception {
        for (int i = 0; i < array.length(); i++) if (expected.equals(array.getString(i))) return true;
        return false;
    }
    @Test public void calendarAloneGrantsOnlyOneMethodAndPinnedHostVisibility() throws Exception {
        CapabilityCatalog.Capability entry = CapabilityCatalog.CAPABILITIES.get("calendar");
        assertNotNull(entry); assertEquals("calendar", CapabilityCatalog.Method.CALENDAR_INSERT.capability);
        assertEquals(java.util.Collections.singletonList(CapabilityCatalog.Method.CALENDAR_INSERT), entry.methods);
        assertTrue(entry.permissions.isEmpty()); assertTrue(entry.features.isEmpty()); assertTrue(entry.components.isEmpty());
        assertTrue(entry.intentFilters.isEmpty()); assertTrue(entry.dependencies.isEmpty()); assertTrue(entry.resources.isEmpty());
        assertEquals(CapabilityCatalog.DOCUMENT_BROKER_PACKAGES, entry.queries);
        assertEquals(1, entry.manifestNodes.size());
        assertEquals("calendar.insert", FactoryDispatcherTest.request("calendar.insert", CalendarInsertRequestTest.args(), config("[\"calendar\"]")).method);
        for (String capability : CapabilityCatalog.NAMES) if (!capability.equals("calendar")) {
            FactoryException denied = assertThrows(FactoryException.class, () -> FactoryDispatcherTest.request("calendar.insert", CalendarInsertRequestTest.args(), config("[\"" + capability + "\"]")));
            assertEquals("CAPABILITY_DENIED", denied.code);
        }
        assertEquals("CAPABILITY_DENIED", assertThrows(FactoryException.class, () -> FactoryDispatcherTest.request("calendar.insert", CalendarInsertRequestTest.args(), config("[]"))).code);
    }
    @Test public void previewNeverLaunchesOrClaimsAnEventWasSaved() throws Exception {
        FactoryConfig calendar = config("[\"calendar\"]");
        FactoryException unavailable = assertThrows(FactoryException.class, () -> FactoryDispatcher.dispatch(
                FactoryDispatcherTest.request("calendar.insert", CalendarInsertRequestTest.args(), calendar), calendar, new BoundedStore(new MemoryBackend()),
                FactoryDispatcher.previewMetadata("synthetic.host", 35, 35), FactoryDispatcher.simulatedEffects()));
        assertEquals("UNAVAILABLE", unavailable.code);
    }
    @Test public void installedDispatchRevalidatesExactTypesAndDelegatesOnceWithUnconfirmedReceipt() throws Exception {
        FactoryConfig calendar = config("[\"calendar\"]");
        BridgeProtocol.Request call = FactoryDispatcherTest.request("calendar.insert", CalendarInsertRequestTest.args(), calendar);
        AtomicInteger calls = new AtomicInteger();
        FactoryDispatcher.Effects effects = (operation, args) -> {
            assertEquals(CapabilityCatalog.Method.CALENDAR_INSERT, operation); calls.incrementAndGet();
            return new JSONObject().put("launchRequested", true).put("actionConfirmed", false);
        };
        JSONObject receipt = (JSONObject) FactoryDispatcher.dispatch(call, calendar, new BoundedStore(new MemoryBackend()),
                FactoryDispatcher.metadata("installed", "com.example.generated", 35, 35), effects);
        assertEquals(1, calls.get()); assertEquals(2, receipt.length()); assertTrue(receipt.getBoolean("launchRequested")); assertFalse(receipt.getBoolean("actionConfirmed"));
        for (Object invalid : new Object[]{0.0, new java.math.BigDecimal("0.0"), "0", JSONObject.NULL}) {
            call.args.put("startTimeMillis", invalid);
            assertEquals("INVALID_ARGUMENT", assertThrows(FactoryException.class, () -> FactoryDispatcher.dispatch(call, calendar, new BoundedStore(new MemoryBackend()),
                    FactoryDispatcher.metadata("installed", "com.example.generated", 35, 35), effects)).code);
        }
        call.args.put("startTimeMillis", 0).put("attendees", "forbidden");
        assertThrows(FactoryException.class, () -> FactoryDispatcher.dispatch(call, calendar, new BoundedStore(new MemoryBackend()),
                FactoryDispatcher.metadata("installed", "com.example.generated", 35, 35), effects));
        call.args.remove("attendees");
        assertEquals("CAPABILITY_DENIED", assertThrows(FactoryException.class, () -> FactoryDispatcher.dispatch(call, config("[]"), new BoundedStore(new MemoryBackend()),
                FactoryDispatcher.metadata("installed", "com.example.generated", 35, 35), effects)).code);
        assertEquals(1, calls.get());
    }
    @Test public void metadataDescribesPreviewLimitsCalendarSemanticsAndExternalUncertainty() throws Exception {
        FactoryConfig calendar = config("[\"calendar\"]");
        for (String mode : new String[]{"preview", "installed"}) {
            JSONObject info = (JSONObject) FactoryDispatcher.dispatch(FactoryDispatcherTest.request("runtime.info", new JSONObject(), calendar), calendar,
                    new BoundedStore(new MemoryBackend()), FactoryDispatcher.metadata(mode, "synthetic.host", 35, 35), (operation, args) -> { throw new AssertionError("Metadata called effect"); });
            assertTrue(contains(info.getJSONArray("implementedCapabilities"), "calendar"));
            assertEquals(mode.equals("preview"), contains(info.getJSONArray("unavailableCapabilities"), "calendar"));
            assertEquals(mode.equals("preview"), contains(info.getJSONArray("unavailableMethods"), "calendar.insert"));
            assertFalse(contains(info.getJSONArray("simulatedCapabilities"), "calendar"));
            assertEquals("[\"calendar\"]", info.getJSONArray("calendarRequires").toString());
            assertTrue(info.getBoolean("calendarBrokerRequired")); assertTrue(info.getBoolean("calendarTimeZoneAdvisory"));
            assertTrue(info.getBoolean("calendarTimeZoneIdsRuntimeSpecific")); assertTrue(info.getBoolean("calendarAllDayEndExclusive"));
            assertTrue(info.getBoolean("externalEditorsMaySyncDrafts")); assertTrue(info.getBoolean("externalRecipientsMayUseNetwork"));
            assertFalse(info.getBoolean("externalActionConfirmed"));
            JSONObject limits = info.getJSONObject("limits");
            assertEquals(256, limits.getInt("calendarTitleCodePoints")); assertEquals(1024, limits.getInt("calendarTitleBytes"));
            assertEquals(256, limits.getInt("calendarLocationCodePoints")); assertEquals(1024, limits.getInt("calendarLocationBytes"));
            assertEquals(4096, limits.getInt("calendarDescriptionBytes")); assertEquals(32768, limits.getInt("editorArgumentsBytes"));
            assertEquals(0, limits.getLong("calendarMinTimeMillis")); assertEquals(4102444800000L, limits.getLong("calendarMaxTimeMillis"));
            assertEquals(31622400000L, limits.getLong("calendarMaxDurationMillis"));
        }
    }
    @Test public void installedCalendarRequiresACompatiblePinnedBrokerWithoutDocuments() throws Exception {
        String plain = FactoryDispatcherTest.configuration("[\"calendar\"]");
        assertEquals("INVALID_CONFIG", assertThrows(FactoryException.class, () -> FactoryConfig.parse(plain, "com.example.generated")).code);
        assertNull(FactoryConfig.parsePreview(plain).documentBroker);
        for (String host : CapabilityCatalog.DOCUMENT_BROKER_PACKAGES) {
            String pinned = new JSONObject(plain).put("documentBroker", new JSONObject().put("packageName", host)
                    .put("certificateSha256", CalendarInsertSpecTest.repeat("a", 64))).toString();
            FactoryConfig valid = FactoryConfig.parse(pinned, "com.example.generated");
            assertEquals(host, valid.documentBroker.packageName); assertEquals(java.util.Collections.singleton("calendar"), valid.capabilities);
        }
    }
}
