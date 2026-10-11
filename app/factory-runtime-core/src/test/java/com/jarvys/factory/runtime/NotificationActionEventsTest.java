package com.jarvys.factory.runtime;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationActionEventsTest {
    private static final String ARTIFACT = repeat('a'), OTHER = repeat('b'), TOKEN = repeat('c'), EVENT = repeat('d');
    private static String repeat(char c) { StringBuilder b = new StringBuilder(); for (int i = 0; i < 64; i++) b.append(c); return b.toString(); }
    private static NotificationScheduleRequest.Action action() throws Exception {
        return NotificationScheduleRequest.parseNotification(NotificationScheduleRequestTest.withAction()).actions.get(0);
    }
    /** Models the persistence contract; intentionally not an Android ledger implementation. */
    private static final class SyntheticDurableRows {
        NotificationActionEvents.Event event;
        boolean consumed, acknowledged;
    }
    private static final class SyntheticStore implements NotificationActionEvents.Store {
        private final SyntheticDurableRows rows;
        SyntheticStore(SyntheticDurableRows rows) { this.rows = rows; }
        public synchronized NotificationActionEvents.Event acceptTap(String artifact, String token, long now) {
            if (!ARTIFACT.equals(artifact) || !TOKEN.equals(token)) return null;
            if (!rows.consumed) {
                try { rows.event = NotificationActionEvents.fromPersistedAction(EVENT, "review", 7, action(), now); }
                catch (Exception e) { throw new AssertionError(e); }
                rows.consumed = true;
            }
            return rows.event;
        }
        public NotificationActionEvents.Event poll(String artifact) { return ARTIFACT.equals(artifact) && !rows.acknowledged ? rows.event : null; }
        public boolean acknowledge(String artifact, String eventId) {
            if (!ARTIFACT.equals(artifact) || !EVENT.equals(eventId) || rows.event == null) return false;
            rows.acknowledged = true; return true;
        }
    }
    @Test public void generatedLookupTokensAreOpaqueAndBounded() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 100; i++) { String token = NotificationActionEvents.randomToken(); assertTrue(NotificationActionEvents.validToken(token)); assertTrue(values.add(token)); }
        assertFalse(NotificationActionEvents.validToken("../x")); assertFalse(NotificationActionEvents.validToken(null));
    }
    @Test public void eventContainsOnlyTypedOwnAppDataAndFreshPayload() throws Exception {
        NotificationActionEvents.Event event = NotificationActionEvents.fromPersistedAction(EVENT, "review", 7, action(), 3000);
        JSONObject details = event.details(); assertEquals(7, details.length()); assertEquals("notification_action", details.getString("type"));
        details.getJSONObject("data").put("key", "mutated"); assertEquals("draft-1", event.details().getJSONObject("data").getString("key"));
        assertFalse(details.has("script")); assertFalse(details.has("intent")); assertFalse(details.has("granted"));
        assertTrue(NotificationActionEvents.fromPersistedAction(EVENT, null, 7, action(), 3000).details().isNull("scheduleId"));
    }
    @Test public void pollingIsAtLeastOnceAcrossSyntheticProcessRestartUntilExplicitAck() {
        SyntheticDurableRows rows = new SyntheticDurableRows();
        SyntheticStore firstProcess = new SyntheticStore(rows);
        NotificationActionEvents.Event first = NotificationActionEvents.acceptTap(firstProcess, ARTIFACT, TOKEN, 3000);
        assertNotNull(first); assertEquals(EVENT, NotificationActionEvents.poll(firstProcess, ARTIFACT).eventId);
        SyntheticStore restarted = new SyntheticStore(rows);
        assertEquals(EVENT, NotificationActionEvents.poll(restarted, ARTIFACT).eventId);
        assertEquals(EVENT, NotificationActionEvents.acceptTap(restarted, ARTIFACT, TOKEN, 4000).eventId);
        assertTrue(NotificationActionEvents.acknowledge(restarted, ARTIFACT, EVENT));
        assertNull(NotificationActionEvents.poll(restarted, ARTIFACT));
        assertTrue(NotificationActionEvents.acknowledge(restarted, ARTIFACT, EVENT));
        NotificationActionEvents.acceptTap(restarted, ARTIFACT, TOKEN, 4500);
        assertNull(NotificationActionEvents.poll(restarted, ARTIFACT));
    }
    @Test public void wrongArtifactUnknownTokenAndUntrustedFieldsCannotCreateAnEvent() {
        SyntheticStore store = new SyntheticStore(new SyntheticDurableRows());
        assertNull(NotificationActionEvents.acceptTap(store, OTHER, TOKEN, 3000));
        assertNull(NotificationActionEvents.acceptTap(store, ARTIFACT, EVENT, 3000));
        assertNull(NotificationActionEvents.acceptTap(store, ARTIFACT, "intent://untrusted", 3000));
        assertNull(NotificationActionEvents.poll(store, ARTIFACT));
        assertThrows(IllegalArgumentException.class, () -> NotificationActionEvents.poll(store, "app-declared-identity"));
    }
    @Test public void ackIsOwnedAndDoesNotAcknowledgeOtherEvents() {
        SyntheticStore store = new SyntheticStore(new SyntheticDurableRows()); NotificationActionEvents.acceptTap(store, ARTIFACT, TOKEN, 3000);
        assertFalse(NotificationActionEvents.acknowledge(store, OTHER, EVENT));
        assertFalse(NotificationActionEvents.acknowledge(store, ARTIFACT, TOKEN));
        assertNotNull(NotificationActionEvents.poll(store, ARTIFACT));
        assertThrows(IllegalArgumentException.class, () -> NotificationActionEvents.acknowledge(store, ARTIFACT, "*"));
    }
}
