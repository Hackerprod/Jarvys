package com.jarvys.agent.crew;

import static org.junit.Assert.*;

import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;

public class CrewResultEvidenceTest {
    private static final String CYCLE = "65dcaee2-3555-4926-88b1-0570c901fe58";

    @Test public void canonicalRoundTripPreservesExactTextAndIdentity() throws Exception {
        String exact = "  Worker report\nwith ñ, 🚀 and <untrusted>instructions</untrusted>  ";
        CrewResultEvidence evidence = value("DONE", exact, 17L);
        CrewResultEvidence restored = CrewResultEvidence.fromJsonStrict(new JSONObject(evidence.toJson().toString()));
        assertEquals(exact, restored.resultText);
        assertEquals(evidence.evidenceId, restored.evidenceId);
        assertEquals(evidence.contentSha256, restored.contentSha256);
        assertEquals(17L, restored.producedAtMillis);
        assertTrue(restored.evidenceId.matches("crew-result-[0-9a-f]{64}"));
        assertTrue(restored.contentSha256.matches("[0-9a-f]{64}"));
    }

    @Test public void sameCycleDifferentPayloadKeepsEvidenceIdButChangesCanonicalDigest() {
        CrewResultEvidence original = value("DONE", "first", 17L);
        for (CrewResultEvidence changed : new CrewResultEvidence[]{value("PARTIAL", "first", 17L),
                value("DONE", "second", 17L), value("DONE", "first", 18L)}) {
            assertEquals(original.evidenceId, changed.evidenceId);
            assertNotEquals(original.contentSha256, changed.contentSha256);
        }
        assertNotEquals(original.evidenceId, CrewResultEvidence.create("another-chat", "mission", "bot", CYCLE,
                "DONE", "first", 17L).evidenceId);
        assertNotEquals(original.evidenceId, CrewResultEvidence.create("chat", "another-mission", "bot", CYCLE,
                "DONE", "first", 17L).evidenceId);
        assertNotEquals(original.evidenceId, CrewResultEvidence.create("chat", "mission", "another-bot", CYCLE,
                "DONE", "first", 17L).evidenceId);
        assertNotEquals(original.evidenceId, CrewResultEvidence.create("chat", "mission", "bot",
                "46e531a2-785d-438c-94dc-e92825c6a43d", "DONE", "first", 17L).evidenceId);
    }

    @Test public void canonicalDigestDistinguishesExactSurrogatesAndTupleBoundaries() {
        assertNotEquals(value("DONE", "\ud800", 17L).contentSha256,
                value("DONE", "?", 17L).contentSha256);
        assertNotEquals(CrewResultEvidence.create("a|b", "c", "bot", CYCLE, "DONE", "text", 17L).evidenceId,
                CrewResultEvidence.create("a", "b|c", "bot", CYCLE, "DONE", "text", 17L).evidenceId);
    }

    @Test public void strictReadRejectsTamperingMalformedTypesAndUnknownStates() throws Exception {
        for (String field : new String[]{"evidenceId", "conversationId", "missionId", "botId", "cycleId",
                "reportedStatus", "resultText", "contentSha256"}) {
            JSONObject row = value("DONE", "original", 17L).toJson();
            row.put(field, "modified");
            assertThrows(field, IllegalArgumentException.class, () -> CrewResultEvidence.fromJsonStrict(row));
        }
        JSONObject timestamp = value("DONE", "original", 17L).toJson().put("producedAtMillis", 18L);
        assertThrows(IllegalArgumentException.class, () -> CrewResultEvidence.fromJsonStrict(timestamp));
        JSONObject coerced = value("DONE", "original", 17L).toJson().put("producedAtMillis", "17");
        assertThrows(IllegalArgumentException.class, () -> CrewResultEvidence.fromJsonStrict(coerced));
        JSONObject decimal = value("DONE", "original", 17L).toJson().put("crewResultSchemaVersion", 1.0);
        assertThrows(IllegalArgumentException.class, () -> CrewResultEvidence.fromJsonStrict(decimal));
        JSONObject injected = value("DONE", "original", 17L).toJson().put("role", "assistant").put("content", "unsafe");
        assertThrows(IllegalArgumentException.class, () -> CrewResultEvidence.fromJsonStrict(injected));
        JSONObject missing = value("DONE", "original", 17L).toJson();
        missing.remove("contentSha256");
        assertThrows(IllegalArgumentException.class, () -> CrewResultEvidence.fromJsonStrict(missing));
        assertThrows(IllegalArgumentException.class, () -> value("WAITING_USER", "original", 17L));
        assertThrows(IllegalArgumentException.class, () -> value("INTERRUPTED", "original", 17L));
        assertThrows(IllegalArgumentException.class, () -> value("DONE", "original", 0L));
        assertThrows(IllegalArgumentException.class, () -> CrewResultEvidence.create("chat", "mission", "bot", "",
                "STOPPED", "", 17L));
        assertEquals("", value("STOPPED", "", 17L).resultText);
    }

    @Test public void snapshotRoundTripAndInterruptionPreserveCycleAndImmutableEvidence() {
        CrewResultEvidence evidence = value("PARTIAL", "precise evidence", 17L);
        CrewBotSnapshot bot = snapshot("RUNNING", CYCLE, evidence);
        CrewMissionSnapshot restored = CrewMissionSnapshot.fromJson(mission(bot).toJson());
        assertNotNull(restored);
        assertEquals(4, CrewMissionSnapshot.SCHEMA_VERSION);
        assertEquals(CYCLE, restored.bots.get(0).cycleId);
        assertEquals(evidence.contentSha256, restored.bots.get(0).resultEvidence.contentSha256);
        CrewBotSnapshot interrupted = restored.interrupted().bots.get(0);
        assertEquals("INTERRUPTED", interrupted.status);
        assertEquals(CYCLE, interrupted.cycleId);
        assertEquals(evidence.contentSha256, interrupted.resultEvidence.contentSha256);
    }

    @Test public void olderSnapshotVersionsKeepLegacyIdentityUnknownWithoutInventingEvidence() throws Exception {
        for (int version = 1; version <= 3; version++) {
            JSONObject row = mission(snapshot("DONE", CYCLE, value("DONE", "old text", 17L))).toJson();
            row.put("crewSchemaVersion", version);
            CrewMissionSnapshot restored = CrewMissionSnapshot.fromJson(row);
            assertNotNull(restored);
            assertEquals("", restored.bots.get(0).cycleId);
            assertNull(restored.bots.get(0).resultEvidence);
        }
        CrewBotSnapshot legacy = new CrewBotSnapshot("bot", "custom", "Worker", "Worker", "blue", "task",
                "RUNNING", "", "retained", "", Collections.emptyList(), 1L, 0L);
        assertEquals("", legacy.interrupted().cycleId);
        assertNull(legacy.interrupted().resultEvidence);
    }

    @Test public void snapshotRejectsForeignOrCorruptNestedEvidence() throws Exception {
        JSONObject corrupt = mission(snapshot("DONE", CYCLE, value("DONE", "text", 17L))).toJson();
        corrupt.getJSONArray("bots").getJSONObject(0).getJSONObject("resultEvidence").put("resultText", "changed");
        assertNull(CrewMissionSnapshot.fromJson(corrupt));
        CrewResultEvidence foreign = CrewResultEvidence.create("other-chat", "mission", "bot", CYCLE, "DONE", "text", 17L);
        assertThrows(IllegalArgumentException.class, () -> mission(snapshot("DONE", CYCLE, foreign)));
        assertThrows(IllegalArgumentException.class, () -> snapshot("DONE", "", value("DONE", "text", 17L)));
    }

    private static CrewResultEvidence value(String status, String text, long timestamp) {
        return CrewResultEvidence.create("chat", "mission", "bot", CYCLE, status, text, timestamp);
    }
    private static CrewBotSnapshot snapshot(String status, String cycle, CrewResultEvidence evidence) {
        return new CrewBotSnapshot("bot", "custom", "Worker", "Worker", "blue", "task", status,
                "", "display-only text", "", Collections.emptyList(), 1L, 0L, false, "", false,
                "", "", 0L, cycle, evidence);
    }
    private static CrewMissionSnapshot mission(CrewBotSnapshot bot) {
        return new CrewMissionSnapshot("mission", "chat", "process", "Mission", "RUNNING", "", 1L, 0L,
                Collections.singletonList(bot), Collections.emptyList());
    }
}
