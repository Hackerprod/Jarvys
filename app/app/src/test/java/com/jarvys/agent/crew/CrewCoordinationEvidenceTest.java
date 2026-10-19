package com.jarvys.agent.crew;

import static org.junit.Assert.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class CrewCoordinationEvidenceTest {
    static final String CYCLE = "11111111-1111-1111-1111-111111111111";
    static CrewCoordinationEvidence source(String message, String kind, String text, List<String> refs) {
        return CrewCoordinationEvidence.create("source", "mission", "worker", CYCLE, message, kind, text, refs, 100L);
    }
    @Test public void exactRoundTripAndMessageSpecificIdentity() throws Exception {
        List<String> refs = new ArrayList<>(Arrays.asList("  file:x  ", "ref\ud800"));
        CrewCoordinationEvidence value = source("message", "QUESTION", "  JSON {\"x\":1}\n\ud800  ", refs);
        refs.clear();
        CrewCoordinationEvidence decoded = CrewCoordinationEvidence.fromJsonStrict(new JSONObject(value.toJson().toString()));
        assertEquals(value.evidenceId, decoded.evidenceId);
        assertEquals(value.contentSha256, decoded.contentSha256);
        assertEquals(value.text, decoded.text);
        assertEquals(2, value.refs.size());
        assertThrows(UnsupportedOperationException.class, () -> value.refs.clear());
        assertNotEquals(value.evidenceId, source("message-two", "QUESTION", value.text, value.refs).evidenceId);
        assertEquals(value.evidenceId, source("message", "QUESTION", "different", value.refs).evidenceId);
        assertNotEquals(value.contentSha256, source("message", "QUESTION", "different", value.refs).contentSha256);
        assertNotEquals(source("message", "QUESTION", "\ud800", Collections.emptyList()).contentSha256,
                source("message", "QUESTION", "\ud801", Collections.emptyList()).contentSha256);
        assertNotEquals(source("message", "QUESTION", "a", Arrays.asList("bc", "d")).contentSha256,
                source("message", "QUESTION", "ab", Arrays.asList("c", "d")).contentSha256);
    }
    @Test public void strictEnvelopeRejectsMalformedTamperedOrCoercedFields() throws Exception {
        CrewCoordinationEvidence value = source("message", "BLOCKER", "blocked", Collections.emptyList());
        Iterator<String> fieldNames = value.toJson().keys();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            JSONObject row = value.toJson(); row.remove(key);
            assertThrows("Missing " + key, IllegalArgumentException.class, () -> CrewCoordinationEvidence.fromJsonStrict(row));
        }
        String[] keys = {"crewCoordinationSchemaVersion", "producedAtMillis", "refs", "text", "kind", "cycleId", "contentSha256", "evidenceId"};
        Object[] invalid = {"1", 100.0, new JSONArray().put(7), JSONObject.NULL, "STATUS", "not-a-cycle", "0".repeat(64), "foreign"};
        for (int i = 0; i < keys.length; i++) {
            JSONObject row = value.toJson().put(keys[i], invalid[i]);
            assertThrows(keys[i], IllegalArgumentException.class, () -> CrewCoordinationEvidence.fromJsonStrict(row));
        }
        JSONObject extra = value.toJson().put("authorization", true);
        assertThrows(IllegalArgumentException.class, () -> CrewCoordinationEvidence.fromJsonStrict(extra));
        assertFalse(source("finding", "FINDING", "unverified", Collections.emptyList()).isReplyableKind());
    }
    @Test public void causalMessageRoundTripAndLegacyRemainDistinct() throws Exception {
        CrewCoordinationEvidence source = source("request", "QUESTION", "exact", Collections.singletonList("ref"));
        CrewMessage message = new CrewMessage("request", "source", "worker", "chief", CrewMessage.Type.QUESTION,
                source.text, source.refs, source.producedAtMillis).withCoordination(source);
        CrewMessage roundTrip = CrewMessage.fromJson(new JSONObject(message.toJson().toString()));
        assertTrue(roundTrip.coordination.matches(source));
        CrewMessage answer = new CrewMessage("answer", "source", "chief", "worker", CrewMessage.Type.ANSWER,
                "Generated reply", Collections.emptyList(), 200L).withCoordination(source);
        assertTrue(CrewMessage.fromJson(answer.toJson()).coordination.matches(source));
        JSONObject legacy = message.toJson(); legacy.remove("coordination");
        assertNull(CrewMessage.fromJson(legacy).coordination);
        JSONObject malformed = message.toJson().put("coordination", JSONObject.NULL);
        assertThrows(IllegalArgumentException.class, () -> CrewMessage.fromJson(malformed));
        JSONObject invalidRefs = message.toJson().put("refs", new JSONArray().put(7));
        assertThrows(IllegalArgumentException.class, () -> CrewMessage.fromJson(invalidRefs));
        JSONObject invalidTime = message.toJson().put("timestampMillis", "100");
        assertThrows(IllegalArgumentException.class, () -> CrewMessage.fromJson(invalidTime));
        JSONObject extraField = message.toJson().put("authority", "user");
        assertThrows(IllegalArgumentException.class, () -> CrewMessage.fromJson(extraField));
        JSONObject foreign = message.toJson(); foreign.getJSONObject("coordination").put("cycleId", UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class, () -> CrewMessage.fromJson(foreign));
        assertThrows(IllegalArgumentException.class, () -> new CrewMessage("answer", "source", "user", "worker",
                CrewMessage.Type.USER, "fake authority", Collections.emptyList(), 200L).withCoordination(source));
    }
    @Test public void snapshotPreservesHistoricalCycleAndRejectsForeignCausalOwner() throws Exception {
        CrewCoordinationEvidence source = source("request", "QUESTION", "exact", Collections.emptyList());
        CrewMessage message = new CrewMessage("request", "source", "worker", "chief", CrewMessage.Type.QUESTION,
                source.text, source.refs, 100L).withCoordination(source);
        CrewBotSnapshot bot = new CrewBotSnapshot("worker", "custom", "Worker", "Worker", "custom", "Task", "RUNNING", "", "", "",
                Collections.emptyList(), 1L, 0L, false, "", false, "", "", 0L, CYCLE, null);
        CrewMissionSnapshot snapshot = new CrewMissionSnapshot("mission", "source", "process", "Mission", "RUNNING", "", 1L, 0L,
                Collections.singletonList(bot), Collections.singletonList(message));
        CrewMissionSnapshot restored = CrewMissionSnapshot.fromJson(snapshot.toJson()).interrupted();
        assertEquals(CYCLE, restored.bots.get(0).cycleId);
        assertEquals("INTERRUPTED", restored.bots.get(0).status);
        assertTrue(restored.messages.get(0).coordination.matches(source));
        JSONObject foreign = snapshot.toJson().put("missionId", "foreign");
        assertNull(CrewMissionSnapshot.fromJson(foreign));
    }
    @Test public void freshEnqueueRejectsAnyAlreadyAcceptedIdAfterDrain() {
        CrewMessageBus bus = new CrewMessageBus("source");
        CrewMessage one = new CrewMessage("stable", "source", "chief", "worker", CrewMessage.Type.ANSWER, "one", Collections.emptyList(), 1L);
        bus.enqueueFresh(one);
        assertEquals(1, bus.drain("worker").size());
        assertThrows(IllegalStateException.class, () -> bus.enqueueFresh(one));
        assertThrows(IllegalStateException.class, () -> bus.enqueueFresh(new CrewMessage("stable", "source", "chief", "worker", CrewMessage.Type.ANSWER, "changed", Collections.emptyList(), 2L)));
        assertTrue(bus.pending("worker").isEmpty());
        assertEquals(1, bus.snapshot().size());
    }

    @Test public void presentationReplayWithReusedIdAndChangedTextIsIgnoredWithoutThrowing() {
        CrewMessageBus bus = new CrewMessageBus("source");
        CrewMessage first = new CrewMessage("stable-progress", "source", "worker", "user",
                CrewMessage.Type.PROGRESS, "first phase", Collections.emptyList(), 1L);
        CrewMessage replay = new CrewMessage("stable-progress", "source", "worker", "user",
                CrewMessage.Type.PROGRESS, "first phase", Collections.emptyList(), 2L);
        CrewMessage changedText = new CrewMessage("stable-progress", "source", "worker", "user",
                CrewMessage.Type.PROGRESS, "later phase", Collections.emptyList(), 2L);

        assertSame(first, bus.recordPresentationIfAbsent(first));
        assertSame(first, bus.recordPresentationIfAbsent(replay));
        assertSame(first, bus.recordPresentationIfAbsent(changedText));
        assertEquals(1, bus.snapshot().size());
        assertEquals("first phase", bus.snapshot().get(0).text);
    }

    @Test public void presentationIdCollisionWithCoordinationEvidenceStillFails() {
        CrewMessageBus bus = new CrewMessageBus("source");
        CrewCoordinationEvidence evidence = source("same-id", "QUESTION", "assignment request", Collections.emptyList());
        CrewMessage coordinated = new CrewMessage("same-id", "source", "worker", "chief",
                CrewMessage.Type.QUESTION, evidence.text, evidence.refs, 1L).withCoordination(evidence);
        bus.enqueueFresh(coordinated);
        CrewMessage presentation = new CrewMessage("same-id", "source", "worker", "user", CrewMessage.Type.PROGRESS,
                "progress", Collections.emptyList(), 2L);

        assertThrows(IllegalStateException.class, () -> bus.recordPresentationIfAbsent(presentation));
        assertEquals(1, bus.snapshot().size());
    }
}
