package com.jarvys.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jarvys.agent.crew.CrewMissionSnapshot;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LocalRunStoreCrewSnapshotReadTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void streamingProjectionMatchesLegacyLatestValuesAndInsertionOrder() throws Exception {
        File files = temporary.newFolder();
        LocalRunStore store = new LocalRunStore(files);
        String session = "crew-stream-parity";
        File ledger = ledger(files, session);
        CrewMissionSnapshot firstA = snapshot("mission-a", session, "first-a");
        CrewMissionSnapshot firstB = snapshot("mission-b", session, "first-b");
        CrewMissionSnapshot secondA = snapshot("mission-a", session, "latest-a");
        CrewMissionSnapshot wrongSession = snapshot("mission-c", "another-session", "wrong-session");
        JSONObject unsupported = snapshot("mission-d", session, "unsupported").toJson();
        unsupported.put("crewSchemaVersion", 99);

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(ledger), StandardCharsets.UTF_8))) {
            writer.write("{\"role\":\"user\",\"content\":\"crew_snapshot appears in content\"}\n");
            writer.write(firstA.toJson().toString()); writer.write('\n');
            writer.write("{broken json\n");
            writer.write("{\"type\":\"crew_snapshot\",broken}\n");
            writer.write(firstB.toJson().toString()); writer.write('\n');
            writer.write(wrongSession.toJson().toString()); writer.write('\n');
            writer.write(unsupported.toString()); writer.write('\n');
            writer.write(secondA.toJson().toString()); writer.write('\n');
        }

        List<CrewMissionSnapshot> expected = legacyProjection(ledger, session);
        LocalRunStore.resetHotPathMetricsForTests();
        List<CrewMissionSnapshot> actual = store.readCrewMissionSnapshots(session);

        assertEquals(jsonValues(expected), jsonValues(actual));
        assertEquals("put() must keep the first insertion position when a mission is updated",
                Arrays.asList("mission-a", "mission-b"), missionIds(actual));
        assertEquals(6L, LocalRunStore.crewSnapshotRowsParsedCountForTests());
        assertEquals(0L, LocalRunStore.conversationRowsFullParseCountForTests());
    }

    @Test public void largeLedgerSkipsOrdinaryRowsCachesExactReadsAndExtendsAfterAppend() throws Exception {
        File files = temporary.newFolder();
        LocalRunStore store = new LocalRunStore(files);
        String session = "crew-stream-large";
        File ledger = ledger(files, session);
        char[] payload = new char[900];
        java.util.Arrays.fill(payload, 'x');
        String ordinaryRow = "{\"role\":\"user\",\"content\":\"" + new String(payload) + "\"}\n";
        int ordinaryRows = 0;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(ledger), StandardCharsets.UTF_8))) {
            while (ledger.length() < 20L * 1024L * 1024L) {
                writer.write(ordinaryRow);
                ordinaryRows++;
            }
        }
        assertTrue("fixture must represent a large device ledger", ledger.length() >= 20L * 1024L * 1024L);
        assertEquals(ordinaryRows, store.conversationMessageCount(session));
        store.appendCrewMissionSnapshot(snapshot("mission-a", session, "saved-a"));
        LocalRunStore.resetHotPathMetricsForTests();
        LocalRunStore.setCrewSnapshotReadTestHookForTests(() -> {
            LocalRunStore.setCrewSnapshotReadTestHookForTests(null);
            store.appendCrewMissionSnapshot(snapshot("mission-b", session, "saved-b"));
        });

        List<CrewMissionSnapshot> first;
        try { first = store.readCrewMissionSnapshots(session); }
        finally { LocalRunStore.setCrewSnapshotReadTestHookForTests(null); }
        long firstBytes = LocalRunStore.crewSnapshotLedgerScanBytesForTests();
        long firstScans = LocalRunStore.crewSnapshotLedgerScanCountForTests();
        assertEquals(Arrays.asList("mission-a", "mission-b"), missionIds(first));
        assertTrue(firstBytes >= 20L * 1024L * 1024L);
        assertEquals("a cold read that races an append extends the saved prefix",
                ledger.length(), firstBytes);
        assertEquals(2L, LocalRunStore.crewSnapshotRowsParsedCountForTests());
        assertEquals(0L, LocalRunStore.conversationRowsFullParseCountForTests());

        store.readCrewMissionSnapshots(session);
        assertEquals("an identical refresh must return the cached projection", firstScans,
                LocalRunStore.crewSnapshotLedgerScanCountForTests());
        assertEquals(firstBytes, LocalRunStore.crewSnapshotLedgerScanBytesForTests());
        assertEquals(1L, LocalRunStore.crewSnapshotCacheHitCountForTests());

        long previousLength = ledger.length();
        store.appendCrewMissionSnapshot(snapshot("mission-c", session, "saved-c"));
        List<CrewMissionSnapshot> appended = store.readCrewMissionSnapshots(session);
        assertEquals(Arrays.asList("mission-a", "mission-b", "mission-c"), missionIds(appended));
        assertEquals(firstScans + 1L, LocalRunStore.crewSnapshotLedgerScanCountForTests());
        assertEquals("append extension reads only the new suffix", ledger.length() - previousLength,
                LocalRunStore.crewSnapshotLedgerScanBytesForTests() - firstBytes);
        assertEquals(3L, LocalRunStore.crewSnapshotRowsParsedCountForTests());
    }

    @Test public void crewSnapshotCacheRejectsValuesThatExceedItsEstimatedByteBudget() throws Exception {
        File files = temporary.newFolder();
        LocalRunStore store = new LocalRunStore(files);
        String session = "crew-byte-budget";
        File ledger = ledger(files, session);
        char[] payload = new char[60 * 1024];
        Arrays.fill(payload, 'x');
        String synthesis = new String(payload);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(ledger), StandardCharsets.UTF_8))) {
            for (int index = 0; index < 300; index++) {
                writer.write(snapshotWithSynthesis("mission-" + index, session, synthesis).toJson().toString());
                writer.write('\n');
            }
        }

        LocalRunStore.resetHotPathMetricsForTests();
        assertEquals(300, store.readCrewMissionSnapshots(session).size());
        String path = ledger.getAbsolutePath();
        assertEquals("a single oversized cache entry must not be retained", 0L,
                LocalRunStore.crewSnapshotCacheBytesForTests(path));
        long scans = LocalRunStore.crewSnapshotLedgerScanCountForTests();
        assertEquals(300, store.readCrewMissionSnapshots(session).size());
        assertEquals("oversized values are rescanned instead of bypassing the byte budget",
                scans + 1L, LocalRunStore.crewSnapshotLedgerScanCountForTests());
        assertEquals(0L, LocalRunStore.crewSnapshotCacheHitCountForTests());
    }

    private static File ledger(File files, String session) {
        File directory = new File(new File(files, "jarvys"), "conversations");
        assertTrue(directory.isDirectory());
        return new File(directory, session + ".jsonl");
    }

    private static CrewMissionSnapshot snapshot(String mission, String session, String title) {
        return new CrewMissionSnapshot(mission, session, "process", title, "DONE", "", 1L, 2L,
                Collections.emptyList(), Collections.emptyList());
    }

    private static CrewMissionSnapshot snapshotWithSynthesis(String mission, String session, String synthesis) {
        return new CrewMissionSnapshot(mission, session, "process", "title", "DONE", synthesis, 1L, 2L,
                Collections.emptyList(), Collections.emptyList());
    }

    private static List<CrewMissionSnapshot> legacyProjection(File ledger, String session) throws Exception {
        Map<String, CrewMissionSnapshot> latest = new LinkedHashMap<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(ledger))) {
            String line;
            while ((line = reader.readLine()) != null) try {
                JSONObject row = new JSONObject(line);
                if (LocalRunStore.isResultOnlyRow(row)) continue;
                if (!"crew_snapshot".equals(row.optString("type"))) continue;
                CrewMissionSnapshot value = CrewMissionSnapshot.fromJson(row);
                if (value != null && session.equals(value.conversationId)) latest.put(value.missionId, value);
            } catch (Exception ignored) { }
        }
        return new ArrayList<>(latest.values());
    }

    private static List<String> jsonValues(List<CrewMissionSnapshot> snapshots) {
        List<String> values = new ArrayList<>();
        for (CrewMissionSnapshot value : snapshots) values.add(value.toJson().toString());
        return values;
    }

    private static List<String> missionIds(List<CrewMissionSnapshot> snapshots) {
        List<String> ids = new ArrayList<>();
        for (CrewMissionSnapshot value : snapshots) ids.add(value.missionId);
        return ids;
    }
}
