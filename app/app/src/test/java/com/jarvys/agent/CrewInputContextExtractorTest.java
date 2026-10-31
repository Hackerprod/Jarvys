package com.jarvys.agent;

import com.jarvys.agent.crew.CrewInputBinding;
import com.jarvys.agent.crew.CrewInputIncorporation;
import java.io.File;
import java.util.*;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class CrewInputContextExtractorTest {
    @Rule public final ChatTestStore fixture = new ChatTestStore();
    private static final String CYCLE = "11111111-1111-1111-1111-111111111111";
    private static final String FIRST = "22222222-2222-2222-2222-222222222222";
    private static final String HASH = "a".repeat(64);
    private CrewCheckpointStore store() {
        File files = fixture.newFilesDirectory(); fixture.localRunStore(files);
        return new CrewCheckpointStore(files);
    }
    private CrewCheckpointStore.ConfirmedSnapshot save(CrewCheckpointStore store, CoreAgentLoop.Checkpoint loop) throws Exception {
        store.save("worker-session", "target-bot", new JSONObject().put("missionId", "mission").put("cycleId", CYCLE),
                "scope", loop, new JSONObject());
        return store.confirmDurable("worker-session", "target-bot", "scope");
    }
    private ConversationTurn incoming(String id, String recordedBot, ConversationInput.Provenance.PrincipalRole role) {
        ConversationInput.Provenance provenance = new ConversationInput.Provenance("main", "workspace", "assignment",
                recordedBot, id, ConversationInput.Kind.MESSAGE, role, "question", "workspace-coordination-" + HASH,
                HASH, 2, "recorded-request", "mission", FIRST);
        return ConversationTurn.internalInput("Synthetic input", -1,
                ConversationTurn.IncomingMetadata.forInternalInput(provenance), id);
    }
    @Test public void exactSnapshotPreservesPluralPeerAndSupervisorWithoutRewritingBotMeaning() throws Exception {
        CoreAgentLoop.Checkpoint loop = new CoreAgentLoop.Checkpoint(Arrays.asList(
                incoming("peer", "sender-bot", ConversationInput.Provenance.PrincipalRole.PEER),
                incoming("supervisor", "target-bot", ConversationInput.Provenance.PrincipalRole.SUPERVISOR),
                incoming("not-applied", "target-bot", ConversationInput.Provenance.PrincipalRole.SUPERVISOR)),
                new LinkedHashSet<>(Arrays.asList("peer", "supervisor")), Collections.emptyMap(), -1);
        CrewInputContext context = CrewInputContextExtractor.fromConfirmed(save(store(), loop));
        assertEquals(CrewInputContext.Coverage.COMPLETE, context.coverage);
        assertEquals(2, context.references.size());
        assertEquals("sender-bot", context.references.get(0).scope.recordedProvenanceBotId);
        assertEquals("target-bot", context.references.get(1).scope.recordedProvenanceBotId);
        assertEquals("PEER", context.references.get(0).principalRole);
        assertEquals("SUPERVISOR", context.references.get(1).principalRole);
        assertEquals(HASH, context.references.get(1).correlationSha256);
        assertEquals("", context.references.get(1).sourcePayloadSha256);
        assertEquals(FIRST, context.references.get(1).recordedProvenanceCycleId);
        assertEquals("", context.references.get(1).firstIncorporationCycleId);
    }
    @Test public void nativeCorrectionRetainsOriginalControlAndHistoricalFirstCycle() throws Exception {
        CrewInputBinding binding = CrewInputBinding.create(CrewInputBinding.SourceKind.NATIVE_CORRECT,
                "control-id", "main", "workspace", "worker-session", "assignment", "mission", "target-bot",
                "native-mailbox", HASH, CYCLE, "Synthetic correction");
        CrewInputIncorporation incorporation = new CrewInputIncorporation(binding, FIRST,
                CrewInputIncorporation.TransportKind.USER, CrewInputIncorporation.transportSha256("Synthetic correction"));
        CoreAgentLoop.Checkpoint loop = new CoreAgentLoop.Checkpoint(Collections.emptyList(),
                Collections.singleton("native-mailbox"), Collections.emptyMap(), -1,
                Collections.singletonMap("native-mailbox", incorporation));
        CrewInputContext.Reference reference = CrewInputContextExtractor.fromConfirmed(save(store(), loop)).references.get(0);
        assertEquals("NATIVE_CORRECT", reference.sourceKind);
        assertEquals("control-id", reference.sourceId);
        assertEquals(FIRST, reference.firstIncorporationCycleId);
        assertEquals(CYCLE, reference.admissionCycleId);
        assertEquals("", reference.correlationMessageId);
        assertEquals("", reference.recordedRequestId);
    }
    @Test public void compactedUnknownInputRemainsPartialAndLaterCheckpointDoesNotChangeCapturedContext() throws Exception {
        CrewCheckpointStore store = store();
        CrewCheckpointStore.ConfirmedSnapshot first = save(store, new CoreAgentLoop.Checkpoint(
                Collections.emptyList(), Collections.singleton("old-input"), Collections.emptyMap(), -1));
        CrewInputContext captured = CrewInputContextExtractor.fromConfirmed(first);
        save(store, new CoreAgentLoop.Checkpoint(Collections.singletonList(incoming("new-input", "target-bot",
                ConversationInput.Provenance.PrincipalRole.SUPERVISOR)), Collections.singleton("new-input"), Collections.emptyMap(), -1));
        assertEquals(CrewInputContext.Coverage.PARTIAL, captured.coverage);
        assertEquals(Collections.singleton("old-input"), captured.unresolvedInputIds);
        assertTrue(captured.references.isEmpty());
        assertEquals(captured, CrewInputContextExtractor.fromConfirmed(first));
    }
    @Test public void conflictingIncomingIdentityCannotProduceAResolvedReference() throws Exception {
        ConversationTurn valid = incoming("source-id", "target-bot", ConversationInput.Provenance.PrincipalRole.SUPERVISOR);
        ConversationTurn.IncomingMetadata metadata = new ConversationTurn.IncomingMetadata(ConversationInput.Origin.AGENT,
                "different-id", "crew", "chief", "MESSAGE", Collections.emptyList(), true, valid.incomingMetadata.provenance);
        CoreAgentLoop.Checkpoint loop = new CoreAgentLoop.Checkpoint(Collections.singletonList(
                ConversationTurn.internalInput("Synthetic input", -1, metadata, "different-id")),
                Collections.singleton("different-id"), Collections.emptyMap(), -1);
        CrewCheckpointStore.ConfirmedSnapshot snapshot = save(store(), loop);
        assertThrows(IllegalArgumentException.class, () -> CrewInputContextExtractor.fromConfirmed(snapshot));
    }
    @Test public void mutableLegacyPublicTurnSourceCannotChangeVerifiedEvidenceView() throws Exception {
        CrewCheckpointStore.ConfirmedSnapshot snapshot = save(store(), new CoreAgentLoop.Checkpoint(
                Collections.singletonList(incoming("original-input", "target-bot",
                        ConversationInput.Provenance.PrincipalRole.SUPERVISOR)),
                Collections.singleton("original-input"), Collections.emptyMap(), -1));
        CrewInputContext before = CrewInputContextExtractor.fromConfirmed(snapshot);
        snapshot.loop.transcript.get(0).bindSource(new ConversationTurn.Source(
                ConversationTurn.Source.Kind.MESSAGE, "invented-session", "invented-message", ""));
        assertNotNull(snapshot.loop.transcript.get(0).source());
        assertNotSame(snapshot.loop, snapshot.loopForEvidence());
        assertNull(snapshot.loopForEvidence().transcript.get(0).source());
        CrewInputContext after = CrewInputContextExtractor.fromConfirmed(snapshot);
        assertEquals(before, after);
        assertEquals("original-input", after.references.get(0).sourceId);
    }

}
