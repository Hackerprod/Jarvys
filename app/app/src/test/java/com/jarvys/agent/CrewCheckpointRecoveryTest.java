package com.jarvys.agent;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CrewCheckpointRecoveryTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void savesVerifiableCheckpointWithoutCredentialsOrPermissionGrants() throws Exception {
        File root = temporary.newFolder();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        ConversationTurn request = new ConversationTurn("user", "Inspect the project", 7);
        ConversationTurn observation = new ConversationTurn("assistant", "password=transcript-secret");
        CoreAgentLoop.Checkpoint loop = new CoreAgentLoop.Checkpoint(Arrays.asList(request, observation), new LinkedHashSet<>(Collections.singletonList("message-one")), Collections.emptyMap(), 0);
        store.save("chat-one", "bot-one", new JSONObject().put("access_token", "metadata-secret").put("nested", new JSONObject().put("approvalGrant", "old-permission").put("path", "src/Main.java")), "scope-one", loop, new JSONObject());
        CrewCheckpointStore.Snapshot saved = store.load("chat-one", "bot-one");
        assertEquals("scope-one", saved.scopeIdentity);
        assertEquals("[REDACTED]", saved.metadata.getString("access_token"));
        assertEquals("[REDACTED]", saved.metadata.getJSONObject("nested").getString("approvalGrant"));
        assertEquals("src/Main.java", saved.metadata.getJSONObject("nested").getString("path"));
        assertEquals(0, saved.loop.genuineUserIndex);
        assertEquals(7, saved.loop.transcript.get(0).originalMessageIndex);
        assertTrue(saved.loop.appliedIncomingIds.contains("message-one"));
        assertFalse(saved.loop.transcript.get(1).content.contains("transcript-secret"));
        assertTrue(new ConversationMetadataStore(root).read("chat-one").privateCode);
        assertEquals(1, store.list("chat-one").size());
        assertTrue(store.listIssues("chat-one").isEmpty());
    }

    @Test
    public void uncertainCallsAreRecoveredAsEvidenceAndNeverReportedAsCompleted() throws Exception {
        File root = temporary.newFolder();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        ModelReply.Call notStarted = new ModelReply.Call("intent", "coding_patch", Collections.emptyMap());
        ModelReply.Call started = new ModelReply.Call("started", "project_exec", Collections.emptyMap());
        Map<String, String> states = new LinkedHashMap<>();
        states.put("intent", "INTENT");
        states.put("started", "STARTED");
        CoreAgentLoop.Checkpoint original = new CoreAgentLoop.Checkpoint(Collections.singletonList(ConversationTurn.toolCalls("", Arrays.asList(notStarted, started))), Collections.emptySet(), states, -1);
        store.save("chat-one", "bot-one", new JSONObject(), "scope-one", original, new JSONObject());
        CoreAgentLoop.Checkpoint restored = store.load("chat-one", "bot-one").loop.reconciled();
        assertEquals("NEVER_LAUNCHED", restored.toolLifecycle.get("intent"));
        assertEquals("INTERRUPTED_UNCERTAIN", restored.toolLifecycle.get("started"));
        assertEquals(3, restored.transcript.size());
        assertTrue(restored.transcript.get(2).content.contains("effects may have occurred"));
        assertTrue(restored.transcript.get(2).content.contains("not automatically replayed"));
        assertEquals(restored.toolLifecycle, restored.reconciled().toolLifecycle);
    }

    @Test
    public void corruptCommittedCheckpointCannotBeSilentlyReplaced() throws Exception {
        File root = temporary.newFolder();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        saveEmpty(store);
        File target = checkpoint(root);
        JSONObject envelope = new JSONObject(read(target));
        envelope.put("sha256", repeat('0', 64));
        write(target, envelope.toString());
        String corruptEvidence = read(target);
        assertThrows(IllegalStateException.class, ()->store.load("chat-one", "bot-one"));
        assertThrows(IllegalStateException.class, ()->saveEmpty(store));
        assertTrue(store.list("chat-one").isEmpty());
        assertEquals(1, store.listIssues("chat-one").size());
        assertEquals(corruptEvidence, read(target));
    }

    @Test
    public void incompleteCommitWithoutPredecessorIsPreservedAndBlocksOverwrite() throws Exception {
        File root = temporary.newFolder();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        File directory = checkpoint(root).getParentFile();
        assertTrue(directory.mkdirs());
        File pending = new File(directory, "checkpoint-pending-evidence.json");
        write(pending, "incomplete evidence");
        assertThrows(IllegalStateException.class, ()->store.load("chat-one", "bot-one"));
        assertThrows(IllegalStateException.class, ()->saveEmpty(store));
        assertEquals("incomplete evidence", read(pending));
        assertEquals(1, store.listIssues("chat-one").size());
    }

    @Test
    public void signedPayloadStillRequiresValidIdentityLifecycleAndIncomingIds() throws Exception {
        File root = temporary.newFolder();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        saveEmpty(store);
        File target = checkpoint(root);
        JSONObject original = new JSONObject(read(target));
        JSONObject payload = new JSONObject(original.getString("payload"));
        payload.put("botId", "another-bot");
        writePayload(target, payload);
        assertThrows(IllegalStateException.class, ()->store.load("chat-one", "bot-one"));
        payload.put("botId", "bot-one");
        payload.getJSONObject("loop").put("toolLifecycle", new JSONObject().put("call-one", "APPROVED"));
        writePayload(target, payload);
        assertThrows(IllegalStateException.class, ()->store.load("chat-one", "bot-one"));
        payload.getJSONObject("loop").put("toolLifecycle", new JSONObject());
        payload.getJSONObject("loop").put("appliedIncomingIds", new JSONArray().put("message-one").put("message-one"));
        writePayload(target, payload);
        assertThrows(IllegalStateException.class, ()->store.load("chat-one", "bot-one"));
    }

    @Test
    public void deletedChatAndUnsafeMaterializationFailClosed() throws Exception {
        File root = temporary.newFolder();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        saveEmpty(store);
        new ConversationMetadataStore(root).update("chat-one", "deleted", true);
        assertThrows(IllegalStateException.class, ()->store.load("chat-one", "bot-one"));
        assertThrows(IllegalStateException.class, ()->saveEmpty(store));
        assertThrows(IOException.class, ()->CrewCheckpointStore.estimatedSize("too large", 1));
        assertTrue(CrewCheckpointStore.materializationBudget() >= 0);
    }

    @Test
    public void directorySyncPreservesThreadInterruptAfterCommit() throws Exception {
        CrewCheckpointStore store = new CrewCheckpointStore(temporary.newFolder());
        Thread.currentThread().interrupt();
        try {
            saveEmpty(store);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertNotNull(store.load("chat-one", "bot-one"));
    }

    private static void saveEmpty(CrewCheckpointStore store) {
        store.save("chat-one", "bot-one", new JSONObject(), "scope-one", CoreAgentLoop.Checkpoint.empty(), new JSONObject());
    }

    private static File checkpoint(File root) {
        return new File(root, "jarvys/crew-context/" + CrewCheckpointStore.digest("chat-one".getBytes(StandardCharsets.UTF_8)) + "/" + CrewCheckpointStore.digest("bot-one".getBytes(StandardCharsets.UTF_8)) + "/checkpoint.json");
    }

    private static void writePayload(File file, JSONObject payload) throws Exception {
        String serialized = payload.toString();
        write(file, new JSONObject().put("schemaVersion", 1).put("payload", serialized).put("sha256", CrewCheckpointStore.digest(serialized.getBytes(StandardCharsets.UTF_8))).toString());
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File file, String value) throws Exception {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
}
