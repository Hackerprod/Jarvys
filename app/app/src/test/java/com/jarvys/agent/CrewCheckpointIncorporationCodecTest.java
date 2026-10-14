package com.jarvys.agent;

import static org.junit.Assert.*;

import com.jarvys.agent.crew.CrewInputBinding;
import com.jarvys.agent.crew.CrewInputIncorporation;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
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
public class CrewCheckpointIncorporationCodecTest {
    private static final String ADMISSION_CYCLE = "11111111-1111-1111-1111-111111111111";
    private static final String FIRST_CYCLE = "22222222-2222-2222-2222-222222222222";
    private static final String TEXT = "  Preserve exact whitespace.\npassword=never-persist-this  ";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void roundTripRetainsHistoricalProofWhenCheckpointTextIsRedacted() throws Exception {
        File root = temporary.newFolder();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        CrewInputIncorporation proof = proof();
        save(store, proof);
        CrewCheckpointStore.ConfirmedSnapshot confirmed = store.confirmDurable("chat-one", "bot-one", "scope-one");
        CrewInputIncorporation restored = confirmed.loop.incorporations.get("mailbox-one");
        assertTrue(proof.samePayload(restored));
        assertEquals(ADMISSION_CYCLE, restored.binding.admissionCycleId);
        assertEquals(FIRST_CYCLE, restored.firstIncorporationCycleId);
        assertFalse(confirmed.loop.transcript.get(0).content.contains("never-persist-this"));
        assertNotEquals(restored.transportSha256,
                CrewInputIncorporation.transportSha256(confirmed.loop.transcript.get(0).content));
        assertTrue(proof.samePayload(confirmed.loop.reconciled().incorporations.get("mailbox-one")));
        assertFalse(read(checkpoint(root)).contains("never-persist-this"));
        assertEquals(proof.binding.sourcePayloadSha256, restored.binding.sourcePayloadSha256);
    }

    @Test public void legacyAppliedIdsNeverGainInferredProof() throws Exception {
        File root = temporary.newFolder();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        save(store, proof());
        File target = checkpoint(root);
        JSONObject payload = payload(target);
        payload.getJSONObject("loop").remove("incorporations");
        writePayload(target, payload);
        CrewCheckpointStore.ConfirmedSnapshot confirmed = store.confirmDurable("chat-one", "bot-one", "scope-one");
        assertTrue(confirmed.loop.appliedIncomingIds.contains("mailbox-one"));
        assertTrue(confirmed.loop.incorporations.isEmpty());
        assertTrue(store.load("chat-one", "bot-one").loop.incorporations.isEmpty());
    }

    @Test public void checkpointRoundTripRetainsRealIncomingMessageProvenance() throws Exception {
        File root = temporary.newFolder();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        CrewInputIncorporation proof = proof();
        ConversationTurn incoming = new ConversationTurn("user", "Bot evidence", -1,
                new ConversationTurn.IncomingMetadata("mailbox-one", "crew", "bot-two", "FINDING",
                        Collections.singletonList("/board/source.md"), true));
        CoreAgentLoop.Checkpoint checkpoint = new CoreAgentLoop.Checkpoint(
                Collections.singletonList(incoming), Collections.singleton("mailbox-one"),
                Collections.emptyMap(), -1, Collections.singletonMap("mailbox-one", proof));
        store.save("chat-one", "bot-one", new JSONObject().put("missionId", "mission-one"),
                "scope-one", checkpoint, new JSONObject());

        CoreAgentLoop.Checkpoint restored = store.confirmDurable("chat-one", "bot-one", "scope-one").loop.reconciled();
        ConversationTurn message = restored.transcript.get(0);
        assertEquals(ConversationTurn.Kind.MESSAGE, message.kind);
        assertEquals("user", message.role);
        assertEquals("Bot evidence", message.content);
        assertEquals("mailbox-one", message.incomingMetadata.id);
        assertEquals("crew", message.incomingMetadata.source);
        assertEquals("bot-two", message.incomingMetadata.sender);
        assertEquals("FINDING", message.incomingMetadata.type);
        assertEquals(Collections.singletonList("/board/source.md"), message.incomingMetadata.references);
        assertTrue(message.incomingMetadata.untrusted);
        assertTrue(restored.appliedIncomingIds.contains("mailbox-one"));
        assertFalse(restored.transcript.stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.TOOL_CALLS
                && turn.toolCalls.stream().anyMatch(call -> CoreAgentLoop.INBOX_TOOL.equals(call.name))));
    }

    @Test public void codecRoundTripRetainsIncomingProvenanceAndProofInMemory() throws Exception {
        CrewInputIncorporation proof = proof();
        ConversationTurn incoming = new ConversationTurn("user", "Bot evidence", -1,
                new ConversationTurn.IncomingMetadata("mailbox-one", "crew", "bot-two", "FINDING",
                        Collections.singletonList("/board/source.md"), true));
        CoreAgentLoop.Checkpoint checkpoint = new CoreAgentLoop.Checkpoint(
                Collections.singletonList(incoming), Collections.singleton("mailbox-one"),
                Collections.emptyMap(), -1, Collections.singletonMap("mailbox-one", proof));

        JSONObject encoded = new JSONObject(CrewCheckpointStore.encodeLoop(checkpoint).toString());
        CoreAgentLoop.Checkpoint restored = CrewCheckpointStore.decodeLoop(encoded);

        ConversationTurn message = restored.transcript.get(0);
        assertEquals("mailbox-one", message.incomingMetadata.id);
        assertEquals("crew", message.incomingMetadata.source);
        assertEquals("bot-two", message.incomingMetadata.sender);
        assertEquals("FINDING", message.incomingMetadata.type);
        assertEquals(Collections.singletonList("/board/source.md"), message.incomingMetadata.references);
        assertTrue(message.incomingMetadata.untrusted);
        assertTrue(proof.samePayload(restored.incorporations.get("mailbox-one")));
    }

    @Test public void duplicatesAndProofsWithoutAppliedMailboxIdsFailClosed() throws Exception {
        File root = temporary.newFolder();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        save(store, proof());
        File target = checkpoint(root);
        JSONObject original = payload(target);
        JSONObject duplicate = new JSONObject(original.toString());
        duplicate.getJSONObject("loop").getJSONArray("incorporations").put(proof().toJson());
        assertRejected(store, target, duplicate);
        JSONObject unapplied = new JSONObject(original.toString());
        unapplied.getJSONObject("loop").put("appliedIncomingIds", new JSONArray());
        assertRejected(store, target, unapplied);
    }

    @Test public void strictProofArrayRejectsNullMalformedAndUnsupportedRows() throws Exception {
        File root = temporary.newFolder();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        save(store, proof());
        File target = checkpoint(root);
        JSONObject original = payload(target);
        for (Object malformed : Arrays.asList(JSONObject.NULL, new JSONObject(), "[]", 1,
                new JSONArray().put(JSONObject.NULL), new JSONArray().put("proof"),
                new JSONArray().put(proof().toJson().put("schemaVersion", 2)),
                new JSONArray().put(proof().toJson().put("schemaVersion", "1")),
                new JSONArray().put(proof().toJson().put("extra", "unknown")),
                new JSONArray().put(proof().toJson().put("transportSha256", "not-a-digest")),
                new JSONArray().put(proof().toJson().put("transportKind", "ASK_CHIEF")))) {
            JSONObject invalid = new JSONObject(original.toString());
            invalid.getJSONObject("loop").put("incorporations", malformed);
            assertRejected(store, target, invalid);
        }
    }

    @Test public void bindingRoutesMustMatchCheckpointConversationBotAndMission() throws Exception {
        File root = temporary.newFolder();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        save(store, proof());
        File target = checkpoint(root);
        JSONObject original = payload(target);
        for (String field : Arrays.asList("executionSessionId", "botId", "missionId")) {
            JSONObject invalid = new JSONObject(original.toString());
            invalid.getJSONObject("loop").getJSONArray("incorporations").getJSONObject(0)
                    .getJSONObject("binding").put(field, "another-route");
            assertRejected(store, target, invalid);
        }
        JSONObject missingMission = new JSONObject(original.toString());
        missingMission.getJSONObject("metadata").remove("missionId");
        assertRejected(store, target, missingMission);
        JSONObject wrongMissionType = new JSONObject(original.toString());
        wrongMissionType.getJSONObject("metadata").put("missionId", 12);
        assertRejected(store, target, wrongMissionType);
    }

    @Test public void saveRejectsInvalidRoutingBeforeCreatingCheckpoint() throws Exception {
        File root = temporary.newFolder();
        CrewCheckpointStore store = new CrewCheckpointStore(root);
        CoreAgentLoop.Checkpoint loop = checkpoint(proof());
        assertThrows(IllegalStateException.class, () -> store.save("another-chat", "bot-one",
                new JSONObject().put("missionId", "mission-one"), "scope-one", loop, new JSONObject()));
        assertThrows(IllegalStateException.class, () -> store.save("chat-one", "another-bot",
                new JSONObject().put("missionId", "mission-one"), "scope-one", loop, new JSONObject()));
        assertThrows(IllegalStateException.class, () -> store.save("chat-one", "bot-one",
                new JSONObject().put("missionId", "another-mission"), "scope-one", loop, new JSONObject()));
        assertEquals(0, root.list().length);
    }

    @Test public void bindingAndProofJsonContributeToMaterializationCost() throws Exception {
        CrewInputIncorporation proof = proof();
        long shallowCost = CrewCheckpointStore.estimatedSize(proof, Long.MAX_VALUE);
        long serializedCost = CrewCheckpointStore.estimatedSize(proof.toJson(), Long.MAX_VALUE);
        assertTrue(serializedCost > shallowCost);
        assertThrows(IOException.class, () -> CrewCheckpointStore.estimatedSize(proof.toJson(), shallowCost));
    }

    private static CrewInputIncorporation proof() {
        CrewInputBinding binding = CrewInputBinding.create(CrewInputBinding.SourceKind.NATIVE_CORRECT,
                "control-one", "central-one", "workspace-one", "chat-one", "assignment-one",
                "mission-one", "bot-one", "mailbox-one",
                CrewCheckpointStore.digest("original immutable source".getBytes(StandardCharsets.UTF_8)),
                ADMISSION_CYCLE, TEXT);
        return new CrewInputIncorporation(binding, FIRST_CYCLE, CrewInputIncorporation.TransportKind.USER,
                CrewInputIncorporation.transportSha256(TEXT));
    }

    private static CoreAgentLoop.Checkpoint checkpoint(CrewInputIncorporation proof) {
        return new CoreAgentLoop.Checkpoint(Collections.singletonList(new ConversationTurn("user", TEXT)),
                Collections.singleton("mailbox-one"), Collections.emptyMap(), 0,
                Collections.singletonMap("mailbox-one", proof));
    }

    private static void save(CrewCheckpointStore store, CrewInputIncorporation proof) throws Exception {
        store.save("chat-one", "bot-one", new JSONObject().put("missionId", "mission-one"),
                "scope-one", checkpoint(proof), new JSONObject());
    }

    private static void assertRejected(CrewCheckpointStore store, File target, JSONObject payload) throws Exception {
        writePayload(target, payload);
        String original = read(target);
        assertThrows(IllegalStateException.class, () -> store.load("chat-one", "bot-one"));
        assertThrows(IllegalStateException.class, () -> store.confirmDurable("chat-one", "bot-one", "scope-one"));
        assertEquals(original, read(target));
    }

    private static File checkpoint(File root) {
        return new File(root, "jarvys/crew-context/" + CrewCheckpointStore.digest("chat-one".getBytes(StandardCharsets.UTF_8))
                + "/" + CrewCheckpointStore.digest("bot-one".getBytes(StandardCharsets.UTF_8)) + "/checkpoint.json");
    }

    private static JSONObject payload(File file) throws Exception {
        return new JSONObject(new JSONObject(read(file)).getString("payload"));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void writePayload(File file, JSONObject payload) throws Exception {
        String serialized = payload.toString();
        JSONObject envelope = new JSONObject().put("schemaVersion", 1).put("payload", serialized)
                .put("sha256", CrewCheckpointStore.digest(serialized.getBytes(StandardCharsets.UTF_8)));
        Files.write(file.toPath(), envelope.toString().getBytes(StandardCharsets.UTF_8));
    }
}
