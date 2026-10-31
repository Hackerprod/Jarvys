package com.jarvys.agent.coding;

import com.jarvys.agent.CentralInvocationSource;
import com.jarvys.agent.CrewInputContext;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.jarvys.agent.coding.CodingJobEvidence.*;

/** Independent metadata oracles; no manager, IO, authority minting or ASYNC opt-in. */
public class CodingJobEvidenceAlignmentTest {
    private static final String TECH = "00000000-0000-0000-0000-000000000010";
    private static final String JOB = "00000000-0000-0000-0000-000000000001";
    private static final String SUBMISSION = "00000000-0000-0000-0000-000000000002";
    private static final String PROCESS = "00000000-0000-0000-0000-000000000003";
    private static final String MANAGER = "00000000-0000-0000-0000-000000000004";
    private static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SESSION = "execution-session";
    private static CrewInputContext empty() {
        return CrewInputContext.resolve(Collections.emptyList(), Collections.emptyList());
    }
    private static CrewSource crew(CrewInputContext context) {
        return new CrewSource(SESSION, "worker", "mission", "current-cycle", "scope-identity",
                DIGEST, "current-call", "project_exec", context);
    }
    private static CentralInvocationSource root() {
        return new CentralInvocationSource("historical-root-generation", "central", "root-message",
                "root-batch", "root-call", "root-run", "root-process");
    }
    private static Binding binding(String generation, SemanticCapture semantic, boolean withRoot,
            CrewInputContext context) {
        return new Binding(generation, JOB, SUBMISSION, "owner", "scope", "scope-identity", PROCESS,
                MANAGER, new Source(crew(context), new WorkspaceBinding("workspace", "assignment", "request"),
                        withRoot ? root() : null), semantic);
    }
    private static CodingJobEvidence.Record record(Binding binding) {
        return new CodingJobEvidence.Record(binding, 1, Phase.SUBMISSION_INTENT, false, null, null);
    }
    private static CodingJobEvidence.Record roundTrip(CodingJobEvidence.Record record) {
        String encoded = CodingJobEvidenceCodec.encode(record);
        return CodingJobEvidenceCodec.decode(encoded, encoded.length()).record;
    }
    private static void reject(Runnable action) {
        try { action.run(); fail("Invalid metadata must be rejected"); }
        catch (IllegalArgumentException | NullPointerException expected) { }
    }
    private static CrewInputContext.Reference conversation(String id, String destination, long revision) {
        return CrewInputContext.Reference.conversation(id, "MESSAGE", "historical-source-message",
                new CrewInputContext.Scope("peer-source-session", destination, "workspace", "assignment",
                        "peer-sender-not-worker", "historical-mission"),
                "PEER", "correlated-message", "correlated-reference", DIGEST, revision,
                "CREW", "historical-request", "historical-cycle");
    }
    private static CrewInputContext.Reference transport(String id) {
        return CrewInputContext.Reference.binding(id, "SUPERVISOR", "root-source-message",
                new CrewInputContext.Scope("central", SESSION, "workspace", "assignment", "worker", "mission"),
                "admission-cycle", "first-cycle", "MAILBOX", DIGEST, DIGEST, DIGEST);
    }

    @Test public void semanticCaptureAndHistoricalRootPresenceAreIndependentFourWayMatrix() {
        for (SemanticCapture semantic : SemanticCapture.values()) for (boolean present : new boolean[]{false, true}) {
            Binding expected = binding(TECH, semantic, present, empty());
            Binding actual = roundTrip(record(expected)).binding;
            assertEquals(expected, actual);
            assertEquals(TECH, actual.technicalStoreGeneration);
            assertEquals(semantic, actual.semanticCapture);
            assertEquals(present ? root() : null, actual.source.rootCentral);
            assertEquals("current-call", actual.source.crew.callId);
            if (present) {
                assertEquals("root-call", actual.source.rootCentral.sourceCallId);
                assertNotEquals(actual.technicalStoreGeneration, actual.source.rootCentral.storageGeneration);
            }
        }
    }

    @Test public void disabledSemanticCaptureStillRequiresActualTechnicalGeneration() {
        reject(() -> binding(null, SemanticCapture.DISABLED, false, empty()));
        reject(() -> binding("g3", SemanticCapture.DISABLED, true, empty()));
        assertEquals(TECH, roundTrip(record(binding(TECH, SemanticCapture.DISABLED, false, empty())))
                .binding.technicalStoreGeneration);
    }

    @Test public void technicalDestinationChangeCannotBeIdempotentContinuation() {
        CodingJobEvidence.Record prior = record(binding(TECH, SemanticCapture.ENABLED, true, empty()));
        Binding changed = binding("00000000-0000-0000-0000-000000000011", SemanticCapture.ENABLED, true, empty());
        reject(() -> validateAdvance(prior, new CodingJobEvidence.Record(changed, 2,
                Phase.SUBMISSION_INTENT, true, null, null)));
    }

    @Test public void pluralChannelsAndUnresolvedIdsRoundTripWithoutMergingHistoricalClaims() {
        CrewInputContext.Reference peer = conversation("same-input", SESSION, 9007199254740993L);
        CrewInputContext.Reference mailbox = transport("same-input");
        CrewInputContext context = CrewInputContext.resolve(Arrays.asList("same-input", "compacted-input"),
                Arrays.asList(peer, mailbox));
        CrewInputContext actual = roundTrip(record(binding(TECH, SemanticCapture.DISABLED, true, context)))
                .binding.source.crew.inputContext;
        assertEquals(context, actual);
        assertEquals(2, actual.references.size());
        assertEquals(Collections.singleton("compacted-input"), actual.unresolvedInputIds);
        assertEquals(CrewInputContext.Coverage.PARTIAL, actual.coverage);
        CrewInputContext.Reference decodedPeer = actual.references.stream()
                .filter(ref -> ref.channel == CrewInputContext.Channel.CONVERSATION_INPUT).findFirst().get();
        assertEquals(9007199254740993L, decodedPeer.assignmentRevision);
        assertEquals("peer-source-session", decodedPeer.scope.sourceSessionId);
        assertEquals("peer-sender-not-worker", decodedPeer.scope.recordedProvenanceBotId);
        assertEquals("historical-cycle", decodedPeer.recordedProvenanceCycleId);
        assertEquals(DIGEST, decodedPeer.correlationSha256);
        assertEquals("", decodedPeer.sourcePayloadSha256);
        assertEquals("", decodedPeer.firstIncorporationCycleId);
    }

    @Test public void completeMeansKnownReferencePerAppliedIdNotAllPossibleChannels() {
        CrewInputContext context = CrewInputContext.resolve(Collections.singleton("input"),
                Collections.singleton(conversation("input", SESSION, Long.MAX_VALUE)));
        CrewInputContext actual = roundTrip(record(binding(TECH, SemanticCapture.ENABLED, false, context)))
                .binding.source.crew.inputContext;
        assertEquals(CrewInputContext.Coverage.COMPLETE, actual.coverage);
        assertTrue(actual.unresolvedInputIds.isEmpty());
        assertEquals(1, actual.references.size());
        assertEquals(Long.MAX_VALUE, actual.references.get(0).assignmentRevision);
    }

    @Test public void contextDestinationMustMatchCrewButHistoricalPeerIdentityMayDiffer() {
        CrewInputContext foreign = CrewInputContext.resolve(Collections.singleton("input"),
                Collections.singleton(conversation("input", "other-execution-session", 1)));
        reject(() -> crew(foreign));
        CrewInputContext valid = CrewInputContext.resolve(Collections.singleton("input"),
                Collections.singleton(conversation("input", SESSION, 1)));
        assertEquals(valid, crew(valid).inputContext);
    }
}
