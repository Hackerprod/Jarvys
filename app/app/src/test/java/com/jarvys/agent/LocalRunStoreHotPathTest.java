package com.jarvys.agent;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LocalRunStoreHotPathTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void largeLedgerActivityAppendUsesTailScanWithoutFullTimelineParseOrGlobalLock() throws Exception {
        File files = temporary.newFolder();
        LocalRunStore store = new LocalRunStore(files);
        String userId = store.appendConversationMessage("chat", "user", "Run a tool");
        File ledger = ledger(files);
        appendLargeLegacyPrefix(ledger, 20L * 1024L * 1024L);
        appendToolCalls(store, userId, "batch-large", "same-call");
        assertTrue("synthetic fixture must exercise a large ledger", ledger.length() >= 20L * 1024L * 1024L);

        LocalRunStore.resetHotPathMetricsForTests();
        store.appendToolActivity("chat", userId, activity("run-large", "event-start"));
        long scansAfterFirst = LocalRunStore.toolActivityLedgerScanCountForTests();
        assertTrue("first lookup must use the reverse ledger scanner", scansAfterFirst > 0L);
        assertTrue("tail lookup must stop after the nearby call row instead of reading the ledger prefix",
                LocalRunStore.toolActivityLedgerScanBytesForTests() < ledger.length());
        assertEquals("the hot append must not materialize the complete row list", 0L,
                LocalRunStore.conversationRowsFullParseCountForTests());
        assertEquals("long scans must release SESSION_TITLE_LOCK", 0L,
                LocalRunStore.toolActivityScanUnderSessionLockCountForTests());
        assertEquals("batch ownership must match the legacy full scan", "batch-large",
                lastRow(ledger).optString("batchId"));

        store.appendToolActivity("chat", userId, activity("run-large", "event-progress"));
        assertEquals("own appends advance the per-session lookup cache", scansAfterFirst,
                LocalRunStore.toolActivityLedgerScanCountForTests());
        assertEquals("later progress keeps the same source batch", "batch-large",
                lastRow(ledger).optString("batchId"));
    }

    @Test public void legacyRowsWithoutExecutionFieldsFallBackToNewestMatchingCall() throws Exception {
        File files = temporary.newFolder();
        LocalRunStore store = new LocalRunStore(files);
        String userId = store.appendConversationMessage("chat", "user", "Run a tool");
        File ledger = ledger(files);
        appendRaw(ledger, new JSONObject().put("type", "tool_activity").put("userMessageId", userId)
                .put("batchId", "old-activity").put("activity", new JSONObject().put("callId", "same-call")));
        appendToolCalls(store, userId, "batch-old", "same-call");
        appendToolCalls(store, userId, "batch-current", "same-call");

        LocalRunStore.resetHotPathMetricsForTests();
        store.appendToolActivity("chat", userId, activity("run-current", "event-legacy"));

        assertEquals("legacy activity without executionId cannot mask its matching call row",
                "batch-current", lastRow(ledger).optString("batchId"));
        assertEquals(0L, LocalRunStore.conversationRowsFullParseCountForTests());
        assertEquals(0L, LocalRunStore.toolActivityScanUnderSessionLockCountForTests());
    }

    @Test public void concurrentAppendInvalidatesScanSnapshotAndRetriesWithNewestBatch() throws Exception {
        File files = temporary.newFolder();
        LocalRunStore store = new LocalRunStore(files);
        String userId = store.appendConversationMessage("chat", "user", "Run a tool");
        File ledger = ledger(files);
        appendToolCalls(store, userId, "batch-before", "same-call");
        LocalRunStore.resetHotPathMetricsForTests();
        LocalRunStore.setToolActivityScanTestHookForTests(() -> {
            try { appendToolCalls(store, userId, "batch-during-scan", "same-call"); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });

        store.appendToolActivity("chat", userId, activity("run-concurrent", "event-concurrent"));

        assertEquals("the stale snapshot must be retried before the activity append",
                "batch-during-scan", lastRow(ledger).optString("batchId"));
        assertEquals("snapshot change during scan must force a retry", 2L,
                LocalRunStore.toolActivityLedgerScanCountForTests());
        assertTrue(LocalRunStore.toolActivityLedgerScanBytesForTests() < ledger.length() * 2L);
        assertEquals(0L, LocalRunStore.conversationRowsFullParseCountForTests());
        assertEquals(0L, LocalRunStore.toolActivityScanUnderSessionLockCountForTests());
    }

    @Test public void durableSteeringReadScansOnlyRowsAfterItsCursorAndPreservesOrder() throws Exception {
        File files = temporary.newFolder();
        LocalRunStore store = new LocalRunStore(files);
        store.appendConversationMessage("chat", "user", "old request");
        File ledger = ledger(files);
        appendLargeLegacyPrefix(ledger, 8L * 1024L * 1024L);
        String cursor = store.appendConversationMessage("chat", "user", "active request");
        String first = store.appendConversationMessage("chat", "user", "first follow-up");
        String second = store.appendConversationMessage("chat", "user", "second follow-up");

        LocalRunStore.resetHotPathMetricsForTests();
        java.util.List<JSONObject> messages = store.readUserMessagesAfter("chat", cursor);

        assertEquals(Arrays.asList(first, second), Arrays.asList(
                messages.get(0).optString("messageId"), messages.get(1).optString("messageId")));
        assertEquals(1L, LocalRunStore.userMessagesAfterScanCountForTests());
        assertTrue("steering reads must stop at the cursor near EOF",
                LocalRunStore.userMessagesAfterScanBytesForTests() < ledger.length() / 10L);
        assertEquals(0L, LocalRunStore.userMessagesAfterScanUnderSessionLockCountForTests());
        assertEquals(0L, LocalRunStore.conversationRowsFullParseCountForTests());
    }

    private static ToolActivity activity(String executionId, String eventId) {
        return new ToolActivity(eventId, executionId, "same-call", "read", "Read",
                "", "", "tool_progress", "progress", "", "audit", "workspace",
                System.currentTimeMillis());
    }

    private static void appendToolCalls(LocalRunStore store, String userId, String batchId, String callId)
            throws Exception {
        store.appendModelTranscriptRow("chat", new JSONObject().put("type", "model_tool_calls")
                .put("batchId", batchId).put("userMessageId", userId)
                .put("calls", new JSONArray().put(new JSONObject().put("id", callId)
                        .put("name", "read").put("arguments", new JSONObject()))));
    }

    private static File ledger(File files) {
        return new File(new File(new File(files, "jarvys"), "conversations"), "chat.jsonl");
    }

    private static void appendLargeLegacyPrefix(File ledger, long targetBytes) throws Exception {
        String row = "{\"type\":\"tool_progress\",\"detail\":\"" + repeat('x', 220) + "\"}\n";
        int count = (int) ((targetBytes + row.length() - 1L) / row.length());
        char[] block = new char[64 * 1024];
        Arrays.fill(block, ' ');
        try (BufferedWriter writer = Files.newBufferedWriter(ledger.toPath(), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            StringBuilder buffered = new StringBuilder(block.length + row.length());
            for (int index = 0; index < count; index++) {
                buffered.append(row);
                if (buffered.length() >= block.length) {
                    writer.write(buffered.toString());
                    buffered.setLength(0);
                }
            }
            if (buffered.length() > 0) writer.write(buffered.toString());
        }
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static void appendRaw(File ledger, JSONObject row) throws Exception {
        Files.write(ledger.toPath(), (row.toString() + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
    }

    private static JSONObject lastRow(File ledger) throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(ledger, "r")) {
            long cursor = input.length() - 1;
            if (cursor >= 0) {
                input.seek(cursor);
                if (input.readByte() == '\n') cursor--;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (cursor >= 0) {
                input.seek(cursor--);
                int value = input.readByte() & 0xff;
                if (value == '\n') break;
                bytes.write(value);
            }
            byte[] reversed = bytes.toByteArray();
            for (int left = 0, right = reversed.length - 1; left < right; left++, right--) {
                byte value = reversed[left];
                reversed[left] = reversed[right];
                reversed[right] = value;
            }
            return new JSONObject(new String(reversed, StandardCharsets.UTF_8));
        }
    }
}
