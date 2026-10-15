package com.jarvys.agent;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CrewCheckpointDurabilityTest {
    @Rule public final ChatTestStore chatTestStore = new ChatTestStore();

    private File newRegisteredFilesDirectory() {
        File filesDirectory = chatTestStore.newFilesDirectory();
        chatTestStore.localRunStore(filesDirectory);
        return filesDirectory;
    }

    @Test public void missingCheckpointReturnsNullWithoutCreatingState() throws Exception {
        File root = newRegisteredFilesDirectory();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        assertNull(store.confirmDurable("chat-one", "bot-one", "scope-one"));
        assertFalse(checkpoint(root).getParentFile().exists());
        assertThrows(IllegalStateException.class, () -> store.confirmDurable("chat-one", "bot-one", null));
        assertThrows(IllegalStateException.class, () -> store.confirmDurable("chat-one", "bot-one", ""));
    }

    @Test public void renameBeforeDirectorySyncFailureNeedsLaterSuccessfulConfirmation() throws Exception {
        File root = newRegisteredFilesDirectory();
        File target = checkpoint(root);
        AtomicBoolean fail = new AtomicBoolean(true);
        CrewCheckpointStore store = new CrewCheckpointStore(root, directory -> {
            if (directory.equals(target.getParentFile())) {
                assertTrue("Fault is injected only after the atomic rename", target.isFile());
                assertTrue(Thread.holdsLock(ConversationMetadataAccess.lockForSession("chat-one")));
                assertFalse(Thread.holdsLock(ConversationMetadataAccess.lockForSession("chat-two")));
                if (fail.get()) throw new IOException("Injected post-rename directory fsync failure");
            }
        });
        assertThrows(IllegalStateException.class, () -> saveEmpty(store));
        assertNotNull("Readable data alone is not a durability receipt", store.load("chat-one", "bot-one"));
        String original = read(target);
        assertThrows(IllegalStateException.class, () -> store.confirmDurable("chat-one", "bot-one", "scope-one"));
        assertEquals(original, read(target));
        fail.set(false);
        CrewCheckpointStore.ConfirmedSnapshot confirmed = store.confirmDurable("chat-one", "bot-one", "scope-one");
        assertEquals(new JSONObject(original).getString("sha256"), confirmed.payloadSha256);
        assertEquals("chat-one", confirmed.conversationId);
        assertEquals("bot-one", confirmed.botId);
        assertEquals("scope-one", confirmed.scopeIdentity);
        assertEquals(store.load("chat-one", "bot-one").savedAtMs, confirmed.savedAtMs);
        assertEquals(confirmed.payloadSha256, store.confirmDurable("chat-one", "bot-one", "scope-one").payloadSha256);
        assertEquals(original, read(target));
        assertArrayEquals("Confirmation must not create a commit marker or replay file",
                new String[]{"checkpoint.json"}, target.getParentFile().list());
    }

    @Test public void exactOriginalPayloadDigestSurvivesNoncanonicalSerialization() throws Exception {
        File root = newRegisteredFilesDirectory();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        saveEmpty(store);
        File target = checkpoint(root);
        JSONObject payload = payload(target);
        String serialized = " \n" + payload.toString(2) + "\n ";
        writePayload(target, serialized);
        CrewCheckpointStore.ConfirmedSnapshot confirmed = store.confirmDurable("chat-one", "bot-one", "scope-one");
        assertEquals(CrewCheckpointStore.digest(serialized.getBytes(StandardCharsets.UTF_8)), confirmed.payloadSha256);
        assertNotEquals(CrewCheckpointStore.digest(new JSONObject(serialized).toString().getBytes(StandardCharsets.UTF_8)),
                confirmed.payloadSha256);
        assertEquals(serialized, new JSONObject(read(target)).getString("payload"));
    }

    @Test public void eachConfirmationReadsCurrentEnvelopeWithoutChangingAnOlderReceipt() throws Exception {
        File root = newRegisteredFilesDirectory();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        store.save("chat-one", "bot-one", new JSONObject().put("label", "first"), "scope-one",
                CoreAgentLoop.Checkpoint.empty(), new JSONObject());
        CrewCheckpointStore.ConfirmedSnapshot first = store.confirmDurable("chat-one", "bot-one", "scope-one");
        store.save("chat-one", "bot-one", new JSONObject().put("label", "later"), "scope-one",
                CoreAgentLoop.Checkpoint.empty(), new JSONObject());
        CrewCheckpointStore.ConfirmedSnapshot later = store.confirmDurable("chat-one", "bot-one", "scope-one");
        assertNotEquals(first.payloadSha256, later.payloadSha256);
        assertEquals("first", first.metadataJson().getString("label"));
        assertEquals("later", later.metadataJson().getString("label"));
        assertEquals(new JSONObject(read(checkpoint(root))).getString("sha256"), later.payloadSha256);
    }

    @SuppressWarnings("unchecked")
    @Test public void confirmedSnapshotCopiesJsonAndFreezesNestedTranscriptArguments() throws Exception {
        File root = newRegisteredFilesDirectory();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("path", "src/Main.java");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("options", nested);
        arguments.put("paths", new ArrayList<>(Collections.singletonList("src/Main.java")));
        ModelReply.Call call = new ModelReply.Call("call-one", "inspect", arguments);
        CoreAgentLoop.Checkpoint loop = new CoreAgentLoop.Checkpoint(Arrays.asList(
                ConversationTurn.toolCalls("", Collections.singletonList(call)),
                ConversationTurn.toolResult("call-one", "inspect", "observed")),
                Collections.singleton("message-one"), Collections.singletonMap("call-one", "RESULT"), -1);
        store.save("chat-one", "bot-one", new JSONObject().put("nested", new JSONObject().put("label", "saved")),
                "scope-one", loop, new JSONObject().put("nested", new JSONObject().put("path", "src/Main.java")));
        CrewCheckpointStore.ConfirmedSnapshot confirmed = store.confirmDurable("chat-one", "bot-one", "scope-one");
        confirmed.metadataJson().getJSONObject("nested").put("label", "changed");
        confirmed.artifactOwnershipJson().getJSONObject("nested").put("path", "changed");
        CrewCheckpointStore.Snapshot legacy = store.load("chat-one", "bot-one");
        legacy.metadata.getJSONObject("nested").put("label", "also changed");
        assertEquals("saved", confirmed.metadataJson().getJSONObject("nested").getString("label"));
        assertEquals("src/Main.java", confirmed.artifactOwnershipJson().getJSONObject("nested").getString("path"));
        Map<String, Object> confirmedArgs = confirmed.loop.transcript.get(0).toolCalls.get(0).arguments;
        assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) confirmedArgs.get("options")).put("path", "changed"));
        assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) confirmedArgs.get("paths")).add("changed"));
        assertThrows(UnsupportedOperationException.class, () -> confirmed.loop.transcript.clear());
        assertThrows(UnsupportedOperationException.class, () -> confirmed.loop.appliedIncomingIds.clear());
        assertThrows(UnsupportedOperationException.class, () -> confirmed.loop.toolLifecycle.clear());
        assertThrows(UnsupportedOperationException.class, () -> confirmed.loop.incorporations.put("new", null));
    }

    @Test public void confirmsEveryAncestorThroughPrivateStorageRootUnderStoreLock() throws Exception {
        File root = newRegisteredFilesDirectory().getCanonicalFile();
        saveEmpty(new CrewCheckpointStore(root));
        List<File> actual = new ArrayList<>();
        CrewCheckpointStore store = new CrewCheckpointStore(root, directory -> {
            assertTrue(Thread.holdsLock(ConversationMetadataAccess.lockForSession("chat-one")));
            assertFalse(Thread.holdsLock(ConversationMetadataAccess.lockForSession("chat-two")));
            actual.add(directory);
        });
        assertNotNull(store.confirmDurable("chat-one", "bot-one", "scope-one"));
        List<File> expected = new ArrayList<>();
        for (File parent = checkpoint(root).getParentFile(); parent != null; parent = parent.getParentFile()) {
            expected.add(parent);
            if (parent.equals(root)) break;
        }
        assertEquals(expected, actual);
    }

    @Test public void failureAtStorageRootCannotReturnAConfirmedSnapshot() throws Exception {
        File root = newRegisteredFilesDirectory().getCanonicalFile();
        saveEmpty(new CrewCheckpointStore(root));
        CrewCheckpointStore store = new CrewCheckpointStore(root, directory -> {
            if (directory.equals(root)) throw new IOException("Injected ancestor sync failure");
        });
        String before = read(checkpoint(root));
        assertThrows(IllegalStateException.class, () -> store.confirmDurable("chat-one", "bot-one", "scope-one"));
        assertEquals(before, read(checkpoint(root)));
    }

    @Test public void validatesExpectedScopeAndEnvelopeIdentityBeforeDirectorySync() throws Exception {
        File root = newRegisteredFilesDirectory();
        saveEmpty(new CrewCheckpointStore(root));
        CrewCheckpointStore store = new CrewCheckpointStore(root, directory -> fail("Invalid evidence must not reach directory sync"));
        assertThrows(IllegalStateException.class, () -> store.confirmDurable("chat-one", "bot-one", "another-scope"));
        File target = checkpoint(root);
        JSONObject original = payload(target);
        for (String field : Arrays.asList("conversationId", "botId", "scopeIdentity")) {
            JSONObject wrong = new JSONObject(original.toString()).put(field, "other-identity");
            writePayload(target, wrong.toString());
            assertThrows(IllegalStateException.class, () -> store.confirmDurable("chat-one", "bot-one", "scope-one"));
        }
    }

    @Test public void rejectsAlteredPayloadDigestSchemaAndInvalidSavedTimestamp() throws Exception {
        File root = newRegisteredFilesDirectory();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        saveEmpty(store);
        File target = checkpoint(root);
        JSONObject originalEnvelope = new JSONObject(read(target));
        write(target, new JSONObject(originalEnvelope.toString()).put("payload", originalEnvelope.getString("payload") + " ").toString());
        assertThrows(IllegalStateException.class, () -> store.confirmDurable("chat-one", "bot-one", "scope-one"));
        write(target, new JSONObject(originalEnvelope.toString()).put("sha256", String.join("", Collections.nCopies(64, "0"))).toString());
        assertThrows(IllegalStateException.class, () -> store.confirmDurable("chat-one", "bot-one", "scope-one"));
        write(target, new JSONObject(originalEnvelope.toString()).put("schemaVersion", 2).toString());
        assertThrows(IllegalStateException.class, () -> store.confirmDurable("chat-one", "bot-one", "scope-one"));
        for (Object value : Arrays.asList("123", 0, -1, 1.5)) {
            JSONObject invalid = new JSONObject(originalEnvelope.getString("payload")).put("savedAtMs", value);
            writePayload(target, invalid.toString());
            assertThrows(IllegalStateException.class, () -> store.confirmDurable("chat-one", "bot-one", "scope-one"));
        }
    }

    @Test public void deletedConversationAndIncompleteWriteStayUnavailable() throws Exception {
        File root = newRegisteredFilesDirectory();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        File directory = checkpoint(root).getParentFile();
        assertTrue(directory.mkdirs());
        File pending = new File(directory, "checkpoint-pending-evidence.json");
        write(pending, "unfinished");
        assertThrows(IllegalStateException.class, () -> store.confirmDurable("chat-one", "bot-one", "scope-one"));
        assertEquals("unfinished", read(pending));
        File secondRoot = newRegisteredFilesDirectory();
        CrewCheckpointStore secondStore = new CrewCheckpointStore(secondRoot);
        saveEmpty(secondStore);
        new ConversationMetadataAccess(secondRoot).update("chat-one", "deleted", true);
        assertThrows(IllegalStateException.class, () -> secondStore.confirmDurable("chat-one", "bot-one", "scope-one"));
    }

    @Test public void symlinkFileAndDirectoryAreNeverConfirmed() throws Exception {
        File root = newRegisteredFilesDirectory();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        saveEmpty(store);
        File target = checkpoint(root);
        File outside = new File(chatTestStore.newFilesDirectory(), "outside.txt");
        write(outside, read(target));
        assertTrue(target.delete());
        Files.createSymbolicLink(target.toPath(), outside.toPath());
        assertThrows(IllegalStateException.class, () -> store.confirmDurable("chat-one", "bot-one", "scope-one"));
        Files.delete(target.toPath());
        File originalDirectory = target.getParentFile();
        File relocated = new File(originalDirectory.getParentFile(), "relocated");
        assertTrue(originalDirectory.renameTo(relocated));
        Files.createSymbolicLink(originalDirectory.toPath(), relocated.toPath());
        assertThrows(IllegalStateException.class, () -> store.confirmDurable("chat-one", "bot-one", "scope-one"));
    }

    @Test public void fileAndDirectorySyncPreserveAnExistingThreadInterrupt() throws Exception {
        File root = newRegisteredFilesDirectory();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        saveEmpty(store);
        CrewCheckpointStore confirmingStore = new CrewCheckpointStore(root,
                directory -> Thread.currentThread().interrupt());
        try {
            assertNotNull(confirmingStore.confirmDurable("chat-one", "bot-one", "scope-one"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static void saveEmpty(CrewCheckpointStore store) {
        store.save("chat-one", "bot-one", new JSONObject(), "scope-one", CoreAgentLoop.Checkpoint.empty(), new JSONObject());
    }

    private static File checkpoint(File root) {
        return new File(root, "jarvys/crew-context/" + CrewCheckpointStore.digest("chat-one".getBytes(StandardCharsets.UTF_8))
                + "/" + CrewCheckpointStore.digest("bot-one".getBytes(StandardCharsets.UTF_8)) + "/checkpoint.json");
    }

    private static JSONObject payload(File file) throws Exception {
        return new JSONObject(new JSONObject(read(file)).getString("payload"));
    }

    private static void writePayload(File file, String serialized) throws Exception {
        write(file, new JSONObject().put("schemaVersion", 1).put("payload", serialized)
                .put("sha256", CrewCheckpointStore.digest(serialized.getBytes(StandardCharsets.UTF_8))).toString());
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File file, String value) throws Exception {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
}
