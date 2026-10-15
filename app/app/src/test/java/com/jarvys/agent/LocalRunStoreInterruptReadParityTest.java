package com.jarvys.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jarvys.agent.crew.CrewMissionSnapshot;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LocalRunStoreInterruptReadParityTest {
    private static final String SESSION = "interrupt-read-parity";
    private static final String USER_ID = "user-1";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void publicLedgerReadersMatchWithInterruptFlagSet() throws Exception {
        File ordinaryFiles = temporary.newFolder("ordinary");
        File interruptedFiles = temporary.newFolder("interrupted");
        LocalRunStore ordinary = new LocalRunStore(ordinaryFiles);
        LocalRunStore interrupted = new LocalRunStore(interruptedFiles);
        byte[] ledgerBytes = fixtureLedger();
        File ordinaryLedger = conversationLedger(ordinaryFiles);
        File interruptedLedger = conversationLedger(interruptedFiles);
        Files.write(ordinaryLedger.toPath(), ledgerBytes);
        Files.write(interruptedLedger.toPath(), ledgerBytes);

        assertEquals(jsonValues(ordinary.readPendingInterruptRequests(SESSION)),
                interrupted(() -> jsonValues(interrupted.readPendingInterruptRequests(SESSION))));
        assertEquals(jsonValues(ordinary.readConversationMessages(SESSION)),
                interrupted(() -> jsonValues(interrupted.readConversationMessages(SESSION))));
        assertEquals(ordinary.readFirstUserMessage(SESSION),
                interrupted(() -> interrupted.readFirstUserMessage(SESSION)));
        assertEquals(ordinary.readConversationTitle(SESSION),
                interrupted(() -> interrupted.readConversationTitle(SESSION)));
        assertEquals(turnValues(ordinary.loadConversationContext(SESSION)),
                interrupted(() -> turnValues(interrupted.loadConversationContext(SESSION))));
        assertEquals(snapshotValues(ordinary.readCrewMissionSnapshots(SESSION)),
                interrupted(() -> snapshotValues(interrupted.readCrewMissionSnapshots(SESSION))));

        ToolActivity activity = new ToolActivity("event-1", "execution-1", "call-1", "search", "Search",
                "", "", "tool_progress", "working", "", "audit", "workspace", 5_000L);
        ordinary.appendToolActivity(SESSION, USER_ID, activity);
        JSONObject ordinaryActivityRow = readLastRow(ordinaryLedger);
        interrupted(() -> {
            interrupted.appendToolActivity(SESSION, USER_ID, activity);
            return null;
        });
        JSONObject interruptedActivityRow = readLastRow(interruptedLedger);
        assertEquals(ordinaryActivityRow.toString(), interruptedActivityRow.toString());

        LocalRunStore.ConversationTimelinePage ordinaryPage = ordinary.readConversationTimelinePage(
                SESSION, ConversationLedgerPager.NO_CURSOR, 2);
        LocalRunStore.ConversationTimelinePage interruptedPage = interrupted(() ->
                interrupted.readConversationTimelinePage(SESSION, ConversationLedgerPager.NO_CURSOR, 2));
        assertEquals(stable(ordinaryPage.events), stable(interruptedPage.events));
        assertEquals(ordinaryPage.olderCursor, interruptedPage.olderCursor);
        assertEquals(ordinaryPage.hasOlder, interruptedPage.hasOlder);

        assertEquals(stable(ordinary.readRecentConversationTimeline(SESSION)),
                stable(interrupted(() -> interrupted.readRecentConversationTimeline(SESSION))));
        assertEquals(jsonValues(ordinary.readUserMessagesAfter(SESSION, USER_ID)),
                interrupted(() -> jsonValues(interrupted.readUserMessagesAfter(SESSION, USER_ID))));
        assertEquals(jsonValues(ordinary.listConversations()),
                interrupted(() -> jsonValues(interrupted.listConversations())));

        ordinary.cleanupOrphanAttachments();
        byte[] ordinaryAfterCleanup = Files.readAllBytes(ordinaryLedger.toPath());
        interrupted(() -> {
            interrupted.cleanupOrphanAttachments();
            return null;
        });
        byte[] interruptedAfterCleanup = Files.readAllBytes(interruptedLedger.toPath());
        assertTrue(Arrays.equals(ordinaryAfterCleanup, interruptedAfterCleanup));

        ordinary.markConversationMessagesIncorporated(SESSION, Arrays.asList(USER_ID));
        JSONObject ordinaryStatusRow = readLastRow(ordinaryLedger);
        interrupted(() -> {
            interrupted.markConversationMessagesIncorporated(SESSION, Arrays.asList(USER_ID));
            return null;
        });
        JSONObject interruptedStatusRow = readLastRow(interruptedLedger);
        assertEquals(ordinaryStatusRow.optString("type"), interruptedStatusRow.optString("type"));
        assertEquals(ordinaryStatusRow.optJSONArray("messageIds").toString(),
                interruptedStatusRow.optJSONArray("messageIds").toString());
    }

    private static byte[] fixtureLedger() throws Exception {
        List<JSONObject> rows = new ArrayList<>();
        rows.add(new JSONObject().put("role", "user").put("content", "first question")
                .put("messageId", USER_ID).put("timestamp", 1.0));
        rows.add(new JSONObject().put("role", "session_title").put("content", "Synthetic conversation")
                .put("timestamp", 1.5));
        rows.add(new JSONObject().put("type", "model_tool_calls").put("batchId", "batch-1")
                .put("userMessageId", USER_ID).put("calls", new JSONArray().put(new JSONObject()
                        .put("id", "call-1").put("name", "search").put("arguments", new JSONObject()))));
        rows.add(new CrewMissionSnapshot("mission-1", SESSION, "process-1", "mission title", "DONE", "",
                1L, 2L, java.util.Collections.emptyList(), java.util.Collections.emptyList()).toJson());
        rows.add(new JSONObject().put("role", "assistant").put("content", "first answer")
                .put("messageId", "assistant-1").put("userMessageId", USER_ID).put("timestamp", 2.0));
        rows.add(new JSONObject().put("role", "user").put("content", "second question")
                .put("messageId", "user-2").put("timestamp", 3.0));
        rows.add(new JSONObject().put("type", "main_interrupt_request").put("requestId", "request-1")
                .put("content", "follow-up")
                .put("skills", new JSONArray()).put("memoryDisabled", false).put("timestamp", 4.0));
        StringBuilder ledger = new StringBuilder();
        for (JSONObject row : rows) ledger.append(row.toString()).append('\n');
        return ledger.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> jsonValues(List<JSONObject> rows) {
        List<String> values = new ArrayList<>();
        for (JSONObject row : rows) values.add(row.toString());
        return values;
    }

    private static List<String> snapshotValues(List<CrewMissionSnapshot> values) {
        List<String> snapshots = new ArrayList<>();
        for (CrewMissionSnapshot value : values) snapshots.add(value.toJson().toString());
        return snapshots;
    }

    private static List<String> turnValues(List<ConversationTurn> turns) {
        List<String> values = new ArrayList<>();
        for (ConversationTurn turn : turns) {
            values.add(turn.kind + "|" + turn.role + "|" + turn.content + "|"
                    + turn.originalMessageIndex + "|" + turn.toolCallId + "|" + turn.toolName);
        }
        return values;
    }

    private static <T> T interrupted(ThrowingSupplier<T> action) throws Exception {
        Thread.currentThread().interrupt();
        try {
            T result = action.get();
            assertTrue("ledger operation must retain the interrupted status",
                    Thread.currentThread().isInterrupted());
            return result;
        } finally {
            Thread.interrupted();
        }
    }

    private static File conversationLedger(File files) {
        return new File(new File(new File(files, "jarvys"), "conversations"), SESSION + ".jsonl");
    }

    private static JSONObject readLastRow(File ledger) {
        try {
            List<String> lines = Files.readAllLines(ledger.toPath(), StandardCharsets.UTF_8);
            return new JSONObject(lines.get(lines.size() - 1));
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** Compare timeline event payloads by durable content instead of their identity-based toString values. */
    private static String stable(Object value) {
        if (value instanceof Iterable<?>) {
            StringBuilder values = new StringBuilder("[");
            for (Object item : (Iterable<?>) value) {
                if (values.length() > 1) values.append(',');
                values.append(stable(item));
            }
            return values.append(']').toString();
        }
        if (value instanceof AgentRunUiEvent) {
            AgentRunUiEvent event = (AgentRunUiEvent) value;
            return String.valueOf(event).replaceAll("@[0-9a-fA-F]+", "@obj")
                    + "|toolActivity=" + stable(event.getToolActivity())
                    + "|toolActivityEvents=" + stable(event.getToolActivityEvents())
                    + "|crewMissionSnapshot=" + stable(event.getCrewMissionSnapshot());
        }
        if (value instanceof ToolActivity) {
            ToolActivity activity = (ToolActivity) value;
            return activity.toJson().toString() + "|allDetail=" + activity.allDetail();
        }
        if (value instanceof CrewMissionSnapshot)
            return ((CrewMissionSnapshot) value).toJson().toString();
        return String.valueOf(value).replaceAll("@[0-9a-fA-F]+","@obj");
    }
}
