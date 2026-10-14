package com.jarvys.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LocalRunStoreConversationHistoryStreamingTest {
    private static final long LARGE_LEDGER_BYTES = 20L * 1024L * 1024L;

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void listConversationsMatchesLegacyProjectionForTitlesHiddenRowsAndSystemSessions()
            throws Exception {
        File files = temporary.newFolder();
        LocalRunStore store = new LocalRunStore(files);

        writeConversation(files, "meta-wins",
                message("user", "first request", "meta-u1", 1.0),
                message("session_title", "Ledger title", "", 1.5),
                message("assistant", "regenerated answer", "meta-old", 2.0),
                marker("assistant_regenerated", "meta-old", 2.1),
                message("user", "latest request", "meta-u2", 3.0),
                message("assistant", "deleted answer", "meta-deleted", 4.0),
                marker("assistant_deleted", "meta-deleted", 4.1),
                message("assistant", "visible answer", "meta-final", 5.0),
                new JSONObject().put("type", "workspace_result_notice").put("role", "user")
                        .put("content", "result-only row must not affect drawer summary")
                        .put("messageId", "result-only").put("timestamp", 99.0));
        store.renameConversation("meta-wins", "Metadata title");
        store.setConversationPinned("meta-wins", true);
        store.setConversationArchived("meta-wins", true);

        writeConversation(files, "ledger-title",
                message("user", "first message", "ledger-u1", 1.0),
                message("session_title", "Ledger supplied title", "", 2.0),
                message("user", "last user prompt", "ledger-u2", 4.0));
        writeConversation(files, "fallback-title",
                message("user", "First request becomes the fallback title. Later text is ignored.", "fallback-u1", 1.0),
                message("assistant", "answer", "fallback-a1", 3.0));
        writeConversation(files, "before-order",
                message("user", "earlier prompt", "before-u1", 1.0),
                message("user", "target prompt remains latest", "before-u2", 2.0),
                new JSONObject().put("role", "assistant").put("content", "late intermediate answer")
                        .put("messageId", "before-a1").put("beforeMessageId", "before-u2")
                        .put("timestamp", 4.0));

        writeConversation(files, "jarvys-proactive",
                message("user", "proactive user", "proactive-u1", 1.0),
                message("assistant", "proactive response", "proactive-a1", 7.0));
        writeConversation(files, "jarvys-tasks",
                message("user", "scheduled task user", "tasks-u1", 1.0),
                message("assistant", "scheduled task response", "tasks-a1", 8.0));

        writeRun(files, "run-history-folder", "run-history-id", "run-duplicate",
                "original run goal", 1.0, 2, "COMPLETED", 7);
        writeConversation(files, "run-duplicate",
                message("user", "first chat turn", "run-u1", 10.0),
                message("assistant", "first answer", "run-a1", 11.0),
                message("user", "latest chat goal", "run-u2", 12.0),
                message("assistant", "latest chat answer", "run-a2", 13.0));

        // A stale ledger beside a deleted metadata tombstone must not resurrect the conversation.
        writeConversation(files, "deleted-tombstone", message("user", "deleted", "deleted-u1", 1.0));
        assertTrue(store.deleteConversation("deleted-tombstone"));
        writeConversation(files, "deleted-tombstone", message("user", "stale ledger", "deleted-u2", 50.0));

        List<JSONObject> expected = legacyListConversations(files, store);
        List<JSONObject> actual = store.listConversations();
        assertEquals(historySignatures(expected), historySignatures(actual));

        JSONObject meta = findSession(actual, "meta-wins");
        assertEquals("Metadata title", meta.optString("title"));
        assertEquals("latest request", meta.optString("goal"));
        assertEquals(5.0, meta.optDouble("timestamp"), 0.0);
        assertTrue(meta.optBoolean("pinned"));
        assertTrue(meta.optBoolean("archived"));
        assertEquals("Ledger supplied title", findSession(actual, "ledger-title").optString("title"));
        assertEquals("target prompt remains latest", findSession(actual, "before-order").optString("goal"));
        assertEquals("run-history-id", findSession(actual, "run-duplicate").optString("run_id"));
        assertEquals("CHAT", findSession(actual, "run-duplicate").optString("outcome"));
        assertEquals(0, findSession(actual, "run-duplicate").optInt("steps"));
        assertEquals(7, findSession(actual, "run-duplicate").optInt("turns"));
        assertTrue("listConversations includes the proactive and scheduled-task ledgers",
                findSession(actual, "jarvys-proactive") != null && findSession(actual, "jarvys-tasks") != null);
        assertEquals("deleted metadata tombstone excludes a stale ledger", null,
                findSession(actual, "deleted-tombstone"));
    }

    @Test public void largeConversationLedgerStreamsCachesAndExtendsOnlyTheAppendedSuffix() throws Exception {
        File files = temporary.newFolder();
        LocalRunStore store = new LocalRunStore(files);
        String session = "history-large-ledger";
        File ledger = conversationLedger(files, session);
        char[] payload = new char[900];
        Arrays.fill(payload, 'x');
        String ordinaryUserRow = new JSONObject().put("role", "user")
                .put("content", new String(payload)).put("messageId", "large-row")
                .put("timestamp", 1.0).toString() + "\n";
        int rowBytes = ordinaryUserRow.getBytes(StandardCharsets.UTF_8).length;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(ledger), StandardCharsets.UTF_8))) {
            long writtenBytes = 0L;
            while (writtenBytes < LARGE_LEDGER_BYTES) {
                writer.write(ordinaryUserRow);
                writtenBytes += rowBytes;
            }
        }
        assertTrue("fixture must be at least 20 MiB", ledger.length() >= LARGE_LEDGER_BYTES);

        LocalRunStore.resetHotPathMetricsForTests();
        List<JSONObject> first = store.listConversations();
        long firstBytes = LocalRunStore.historyLedgerScanBytesForTests();
        assertTrue("the cold summary scan should account for the large ledger",
                firstBytes >= ledger.length());
        assertEquals("drawer history must not use the full transcript materializer", 0L,
                LocalRunStore.conversationRowsFullParseCountForTests());
        assertEquals(1, first.size());
        assertEquals("the last user row supplies the drawer goal", new String(payload),
                findSession(first, session).optString("goal"));

        store.listConversations();
        assertEquals("unchanged drawer refresh should hit its summary cache", firstBytes,
                LocalRunStore.historyLedgerScanBytesForTests());
        assertEquals(0L, LocalRunStore.conversationRowsFullParseCountForTests());

        long oldLength = ledger.length();
        store.appendConversationMessage(session, "user", "appended goal");
        List<JSONObject> appended = store.listConversations();
        assertEquals("appended goal", findSession(appended, session).optString("goal"));
        assertEquals("incremental refresh reads only the new ledger suffix",
                ledger.length() - oldLength,
                LocalRunStore.historyLedgerScanBytesForTests() - firstBytes);
        assertEquals(0L, LocalRunStore.conversationRowsFullParseCountForTests());

        int turnsBeforeHiddenAssistant = findSession(appended, session).optInt("turns");
        long beforeMarkers = ledger.length();
        String assistantId = store.appendConversationMessage(session, "assistant", "later hidden answer");
        store.appendAssistantDeleted(session, assistantId);
        // appendAssistantDeleted resolves the owner with its own scan; only the drawer refresh is measured here.
        long parsesBeforeDrawerRefresh = LocalRunStore.conversationRowsFullParseCountForTests();
        List<JSONObject> tombstoned = store.listConversations();
        assertEquals("a tombstone in the appended suffix retracts the assistant from counts",
                turnsBeforeHiddenAssistant, findSession(tombstoned, session).optInt("turns"));
        assertEquals("tombstones also extend the cached projection by their suffix: every appended byte since the "
                        + "first cached read is scanned exactly once",
                ledger.length() - oldLength,
                LocalRunStore.historyLedgerScanBytesForTests() - firstBytes);
        assertTrue("the marker suffix itself was scanned", ledger.length() - beforeMarkers > 0L);
        assertEquals("the drawer refresh itself must not materialize the ledger", parsesBeforeDrawerRefresh,
                LocalRunStore.conversationRowsFullParseCountForTests());
    }

    @Test(timeout = 30000) public void drawerRetainsPerCallProgressAcrossMoreLedgersThanTheGlobalLruWhileOneGrows()
            throws Exception {
        File files = temporary.newFolder();
        LocalRunStore store = new LocalRunStore(files);
        char[] payload = new char[1000];
        Arrays.fill(payload, 'x');
        String row = new JSONObject().put("role", "user").put("content", new String(payload))
                .put("messageId", "bulk-row").put("timestamp", 1.0).toString() + "\n";
        long initialBytes = 0L;
        String writerSession = "history-stream-00";
        for (int index = 0; index < 20; index++) {
            String session = String.format(java.util.Locale.ROOT, "history-stream-%02d", index);
            File ledger = conversationLedger(files, session);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(ledger), StandardCharsets.UTF_8))) {
                long writtenBytes = 0L;
                long rowBytes = row.getBytes(StandardCharsets.UTF_8).length;
                while (writtenBytes < 1024L * 1024L) {
                    writer.write(row);
                    writtenBytes += rowBytes;
                }
            }
            assertTrue("each synthetic ledger must be at least 1 MiB", ledger.length() >= 1024L * 1024L);
            initialBytes += ledger.length();
        }

        AtomicBoolean keepWriting = new AtomicBoolean(true);
        AtomicInteger writes = new AtomicInteger();
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch firstAppend = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            writerStarted.countDown();
            int index = 0;
            while (keepWriting.get() && index++ < 4000) {
                store.appendConversationMessage(writerSession, "user", "concurrent append " + index);
                writes.incrementAndGet();
                firstAppend.countDown();
                try { Thread.sleep(1L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }, "history-ledger-appender");
        writer.start();
        assertTrue("writer did not start", writerStarted.await(5L, TimeUnit.SECONDS));
        assertTrue("writer did not append its first row", firstAppend.await(10L, TimeUnit.SECONDS));
        LocalRunStore.resetHotPathMetricsForTests();
        List<JSONObject> history = store.listConversations();
        keepWriting.set(false);
        writer.join(10000L);

        assertTrue("the background appender must overlap the drawer read", writes.get() > 0);
        assertTrue("drawer must include the growing ledger", findSession(history, writerSession) != null);
        assertTrue("a drawer call must not reread all ledgers on each retry",
                LocalRunStore.historyLedgerScanBytesForTests() < initialBytes * 3L);
    }

    @Test public void unterminatedTailIsSeparatedBeforeAppendAndFullAndIncrementalReadsAgree() throws Exception {
        File files = temporary.newFolder();
        LocalRunStore store = new LocalRunStore(files);
        String session = "history-torn-tail";
        File ledger = conversationLedger(files, session);
        try (FileOutputStream output = new FileOutputStream(ledger)) {
            output.write((message("user", "before", "before-id", 1.0).toString() + "\n{partial")
                    .getBytes(StandardCharsets.UTF_8));
        }

        store.listConversations(); // Cache the snapshot before the incomplete tail.
        store.appendConversationMessage(session, "user", "after repair");

        List<JSONObject> fullMessages = store.readConversationMessages(session);
        List<JSONObject> incrementalHistory = store.listConversations();
        assertEquals(2, fullMessages.size());
        assertEquals("after repair", fullMessages.get(1).optString("content"));
        assertEquals("incremental drawer projection must see the same appended row",
                "after repair", findSession(incrementalHistory, session).optString("goal"));
        String ledgerText = new String(java.nio.file.Files.readAllBytes(ledger.toPath()), StandardCharsets.UTF_8);
        assertTrue("the torn bytes must be isolated on their own line", ledgerText.contains("{partial\n{"));
    }

    @Test public void corruptFirstRunOwnerKeepsItsGoalWhenLaterRunStartSuppliesAValidOwner() throws Exception {
        File files = temporary.newFolder();
        LocalRunStore store = new LocalRunStore(files);
        File runDirectory = new File(new File(files, "jarvys/runs"), "corrupt-owner-run");
        assertTrue(runDirectory.mkdirs());
        File ledger = new File(runDirectory, "steps.jsonl");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(ledger), StandardCharsets.UTF_8))) {
            writer.write(new JSONObject().put("event", "run_start").put("run_id", "first-run")
                    .put("session_id", "invalid/session").put("goal", "first run goal")
                    .put("timestamp", 3.0).toString());
            writer.write('\n');
            writer.write(new JSONObject().put("event", "run_start").put("run_id", "later-run")
                    .put("session_id", "valid-later-owner").put("goal", "later run goal")
                    .put("timestamp", 9.0).toString());
            writer.write('\n');
        }

        JSONObject summary = findSession(store.listConversations(), "valid-later-owner");
        assertTrue("the later valid owner should allow the legacy first-run summary to display", summary != null);
        assertEquals("first-run", summary.optString("run_id"));
        assertEquals("first run goal", summary.optString("goal"));
        assertEquals(3.0, summary.optDouble("timestamp"), 0.0);
    }

    @Test public void drawerCacheEstimateIncludesRetainedRowAndStringObjects() throws Exception {
        File files = temporary.newFolder();
        LocalRunStore store = new LocalRunStore(files);
        String session = "history-cache-estimate";
        File ledger = conversationLedger(files, session);
        int rowCount = 1000;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(ledger), StandardCharsets.UTF_8))) {
            for (int index = 0; index < rowCount; index++) {
                writer.write(message("user", "cache row " + index, "measure-" + index, index + 1.0).toString());
                writer.write('\n');
            }
        }

        store.listConversations();
        long estimatedBytes = LocalRunStore.conversationHistoryCacheBytesForTests(ledger.getAbsolutePath());
        assertTrue("the estimate must include per-row and per-string retained object costs",
                estimatedBytes >= rowCount * 200L);
    }

    private static List<JSONObject> legacyListConversations(File files, LocalRunStore store) throws Exception {
        List<JSONObject> recent = new ArrayList<>();
        File runsDirectory = new File(new File(files, "jarvys"), "runs");
        File[] runs = runsDirectory.listFiles(File::isDirectory);
        if (runs != null) {
            Arrays.sort(runs, (left, right) -> right.getName().compareTo(left.getName()));
            for (File run : runs) {
                String sessionId = null;
                JSONObject summary = null;
                File runLedger = new File(run, "steps.jsonl");
                try (BufferedReader reader = new BufferedReader(new FileReader(runLedger))) {
                    String line;
                    while ((line = reader.readLine()) != null) try {
                        JSONObject row = new JSONObject(line);
                        String event = row.optString("event");
                        if ("run_start".equals(event) && summary == null) {
                            sessionId = row.optString("session_id", "");
                            if (sessionId.isEmpty()) sessionId = row.optString("run_id", run.getName());
                            if (store.readConversationMetadata(sessionId).deleted) break;
                            summary = new JSONObject().put("run_id", row.optString("run_id", run.getName()))
                                    .put("session_id", sessionId).put("goal", row.optString("goal", ""))
                                    .put("timestamp", row.optDouble("timestamp", 0)).put("outcome", "RUNNING")
                                    .put("steps", 0).put("turns", 0);
                        } else if (summary != null && "step_start".equals(event)) {
                            summary.put("steps", summary.optInt("steps") + 1);
                        } else if (summary != null && "run_end".equals(event)) {
                            summary.put("outcome", row.optString("outcome", "UNKNOWN"));
                            summary.put("turns", row.optInt("turns", 0));
                        }
                    } catch (Exception ignored) { }
                }
                if (summary != null) recent.add(summary);
            }
        }

        File conversations = new File(files, "jarvys/conversations");
        File[] ledgers = conversations.listFiles((directory, name) -> name.endsWith(".jsonl"));
        if (ledgers != null) {
            Arrays.sort(ledgers, Comparator.comparing(File::getName));
            for (File ledger : ledgers) {
                String sessionId = ledger.getName().substring(0, ledger.getName().length() - ".jsonl".length());
                ConversationMetadataStore.Snapshot metadata;
                try { metadata = store.readConversationMetadata(sessionId); }
                catch (RuntimeException ignored) { continue; }
                if (metadata.deleted) continue;

                List<JSONObject> rows = readLegacyConversationRows(ledger);
                reorderAssistantMessagesBeforeSteering(rows);
                Set<String> hidden = new HashSet<>();
                for (JSONObject row : rows) {
                    String type = row.optString("type");
                    if ("assistant_regenerated".equals(type) || "assistant_deleted".equals(type)) {
                        String id = row.optString("messageId", "");
                        if (!id.isEmpty()) hidden.add(id);
                    }
                }

                List<JSONObject> messages = new ArrayList<>();
                for (JSONObject row : rows) {
                    String role = row.optString("role");
                    if (("user".equals(role) || "assistant".equals(role))
                            && !("assistant".equals(role) && hidden.contains(row.optString("messageId", "")))) {
                        messages.add(row);
                    }
                }
                if (messages.isEmpty()) continue;

                String title = metadata.title;
                if (title == null) title = legacyLedgerTitle(ledger);
                if (title == null) {
                    for (JSONObject row : rows) if ("user".equals(row.optString("role")) && row.has("content")) {
                        title = ConversationTitle.fromFirstMessage(row.optString("content", ""));
                        break;
                    }
                }

                JSONObject latest = messages.get(messages.size() - 1);
                String latestGoal = "";
                for (int index = messages.size() - 1; index >= 0; index--) {
                    if ("user".equals(messages.get(index).optString("role"))) {
                        latestGoal = messages.get(index).optString("content", "");
                        break;
                    }
                }
                boolean found = false;
                for (JSONObject summary : recent) if (sessionId.equals(summary.optString("session_id"))) {
                    found = true;
                    if (!latestGoal.isEmpty()) summary.put("goal", latestGoal);
                    if (title != null) summary.put("title", title);
                    summary.put("timestamp", latest.optDouble("timestamp", summary.optDouble("timestamp")));
                    summary.put("turns", Math.max(summary.optInt("turns"), (messages.size() + 1) / 2));
                    summary.put("outcome", "CHAT").put("steps", 0);
                }
                if (!found) recent.add(new JSONObject().put("run_id", "chat_" + sessionId)
                        .put("session_id", sessionId).put("goal", latestGoal).put("title", title)
                        .put("timestamp", latest.optDouble("timestamp", 0)).put("outcome", "CHAT")
                        .put("steps", 0).put("turns", (messages.size() + 1) / 2));
            }
        }

        List<JSONObject> visible = new ArrayList<>();
        for (JSONObject row : recent) {
            String sessionId = row.optString("session_id");
            ConversationMetadataStore.Snapshot metadata = store.readConversationMetadata(sessionId);
            if (metadata.deleted) continue;
            row.put("pinned", metadata.pinned).put("archived", metadata.archived);
            if (metadata.title != null) row.put("title", metadata.title);
            visible.add(row);
        }
        visible.sort((left, right) -> Double.compare(right.optDouble("timestamp"), left.optDouble("timestamp")));
        Map<String, JSONObject> sessions = new LinkedHashMap<>();
        for (JSONObject row : visible) sessions.putIfAbsent(row.optString("session_id"), row);
        List<JSONObject> result = new ArrayList<>(sessions.values());
        result.sort((left, right) -> {
            int pinned = Boolean.compare(right.optBoolean("pinned"), left.optBoolean("pinned"));
            return pinned != 0 ? pinned : Double.compare(right.optDouble("timestamp"), left.optDouble("timestamp"));
        });
        return result;
    }

    private static List<JSONObject> readLegacyConversationRows(File ledger) throws Exception {
        List<JSONObject> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(ledger))) {
            String line;
            while ((line = reader.readLine()) != null) try {
                JSONObject row = new JSONObject(line);
                if (!LocalRunStore.isResultOnlyRow(row)) rows.add(row);
            } catch (Exception ignored) { }
        }
        return rows;
    }

    private static String legacyLedgerTitle(File ledger) throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader(ledger))) {
            String line;
            while ((line = reader.readLine()) != null) try {
                JSONObject row = new JSONObject(line);
                if (!"session_title".equals(row.optString("role"))) continue;
                String title = row.optString("content", "").trim();
                if (!title.isEmpty()) return title;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static void reorderAssistantMessagesBeforeSteering(List<JSONObject> rows) {
        for (JSONObject row : new ArrayList<>(rows)) {
            if (!"assistant".equals(row.optString("role"))) continue;
            String beforeMessageId = row.optString("beforeMessageId", "");
            if (beforeMessageId.isEmpty()) continue;
            int source = rows.indexOf(row);
            int target = -1;
            for (int index = 0; index < rows.size(); index++) {
                if (beforeMessageId.equals(rows.get(index).optString("messageId", ""))) {
                    target = index;
                    break;
                }
            }
            if (source < 0 || target < 0 || source == target) continue;
            rows.remove(source);
            if (source < target) target--;
            rows.add(target, row);
        }
    }

    private static List<String> historySignatures(List<JSONObject> rows) {
        List<String> signatures = new ArrayList<>();
        for (JSONObject row : rows) signatures.add(row.optString("run_id") + "|" + row.optString("session_id")
                + "|" + row.optString("goal") + "|" + row.optString("title") + "|" + row.optString("outcome")
                + "|" + row.optInt("steps") + "|" + row.optInt("turns") + "|" + row.optDouble("timestamp")
                + "|" + row.optBoolean("pinned") + "|" + row.optBoolean("archived"));
        return signatures;
    }

    private static JSONObject findSession(List<JSONObject> rows, String sessionId) {
        for (JSONObject row : rows) if (sessionId.equals(row.optString("session_id"))) return row;
        return null;
    }

    private static JSONObject message(String role, String content, String messageId, double timestamp)
            throws org.json.JSONException {
        JSONObject row = new JSONObject().put("role", role).put("content", content).put("timestamp", timestamp);
        if (!messageId.isEmpty()) row.put("messageId", messageId);
        return row;
    }

    private static JSONObject marker(String type, String messageId, double timestamp)
            throws org.json.JSONException {
        return new JSONObject().put("type", type).put("messageId", messageId).put("timestamp", timestamp);
    }

    private static void writeConversation(File files, String sessionId, JSONObject... rows) throws Exception {
        File ledger = conversationLedger(files, sessionId);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(ledger), StandardCharsets.UTF_8))) {
            for (JSONObject row : rows) {
                writer.write(row.toString());
                writer.write('\n');
            }
        }
    }

    private static File conversationLedger(File files, String sessionId) {
        File directory = new File(files, "jarvys/conversations");
        if (!directory.isDirectory()) assertTrue(directory.mkdirs());
        return new File(directory, sessionId + ".jsonl");
    }

    private static void writeRun(File files, String directoryName, String runId, String sessionId,
            String goal, double timestamp, int steps, String outcome, int turns) throws Exception {
        File runDirectory = new File(new File(files, "jarvys/runs"), directoryName);
        assertTrue(runDirectory.mkdirs());
        File ledger = new File(runDirectory, "steps.jsonl");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(ledger), StandardCharsets.UTF_8))) {
            writer.write(new JSONObject().put("event", "run_start").put("run_id", runId)
                    .put("session_id", sessionId).put("goal", goal).put("timestamp", timestamp).toString());
            writer.write('\n');
            for (int index = 0; index < steps; index++) {
                writer.write(new JSONObject().put("event", "step_start").put("step", index + 1).toString());
                writer.write('\n');
            }
            writer.write(new JSONObject().put("event", "run_end").put("outcome", outcome)
                    .put("turns", turns).toString());
            writer.write('\n');
        }
    }
}
