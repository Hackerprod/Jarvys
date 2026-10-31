package com.jarvys.agent;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CrewInputContextTest {
    private static final String HASH = "a".repeat(64);
    private CrewInputContext.Scope scope(String bot) {
        return new CrewInputContext.Scope("main", "worker-session", "workspace", "assignment", bot, "mission");
    }
    private CrewInputContext.Reference message(String id) {
        return CrewInputContext.Reference.conversation(id, "MESSAGE", id, scope("worker"),
                "SUPERVISOR", "question-1", "reference-1", HASH, 3, "AGENT", "recorded-request", "correlation-cycle");
    }
    private CrewInputContext.Reference binding(String id, String kind, String source) {
        return CrewInputContext.Reference.binding(id, kind, source, scope("worker"),
                "admission-cycle", "first-cycle", "USER", HASH, HASH, HASH);
    }
    @Test public void twoAppliedInputsRemainPluralNotLatestOnly() {
        CrewInputContext context = CrewInputContext.resolve(Arrays.asList("first", "second"),
                Arrays.asList(message("second"), message("first")));
        assertEquals(CrewInputContext.Coverage.COMPLETE, context.coverage);
        assertEquals(Arrays.asList("first", "second"), Arrays.asList(context.references.get(0).inputId, context.references.get(1).inputId));
    }
    @Test public void queuedInputIsNotIncorporated() {
        CrewInputContext context = CrewInputContext.resolve(Collections.singleton("applied"),
                Arrays.asList(message("queued"), message("applied")));
        assertEquals(1, context.references.size());
        assertEquals("applied", context.references.get(0).inputId);
    }
    @Test public void compactedAppliedIdsStayUnresolvedAndPartial() {
        CrewInputContext context = CrewInputContext.resolve(Arrays.asList("old", "new"), Collections.singleton(message("new")));
        assertEquals(CrewInputContext.Coverage.PARTIAL, context.coverage);
        assertEquals(Collections.singleton("old"), context.unresolvedInputIds);
    }
    @Test public void duplicateMetadataIsIdempotentButConflictingIdentityRejects() {
        assertEquals(1, CrewInputContext.resolve(Collections.singleton("input"),
                Arrays.asList(message("input"), message("input"))).references.size());
        CrewInputContext.Reference other = CrewInputContext.Reference.conversation("input", "TASK", "input",
                scope("worker"), "SUPERVISOR", "", "", "", 0, "AGENT", "", "");
        assertThrows(IllegalArgumentException.class, () -> CrewInputContext.resolve(Collections.singleton("input"),
                Arrays.asList(message("input"), other)));
    }
    @Test public void channelsArePreservedWithoutCollapsingTheirDifferentMeaning() {
        CrewInputContext oneKnownChannel = CrewInputContext.resolve(Collections.singleton("input"),
                Collections.singleton(message("input")));
        assertEquals("COMPLETE covers applied IDs, not discovery of every possible channel",
                CrewInputContext.Coverage.COMPLETE, oneKnownChannel.coverage);
        CrewInputContext context = CrewInputContext.resolve(Collections.singleton("input"), Arrays.asList(
                message("input"), binding("input", "WORKSPACE_REPLY", "reply-record")));
        assertEquals(2, context.references.size());
        assertEquals(CrewInputContext.Coverage.COMPLETE, context.coverage);
        assertNotEquals(oneKnownChannel, context);
        assertEquals(CrewInputContext.Channel.CREW_BINDING, context.references.get(0).channel);
        assertEquals(CrewInputContext.Channel.CONVERSATION_INPUT, context.references.get(1).channel);
    }
    @Test public void captureIsDefensiveAndCannotBeMutatedByLaterCollections() {
        List<String> applied = new ArrayList<>(Collections.singleton("input"));
        List<CrewInputContext.Reference> refs = new ArrayList<>(Collections.singleton(message("input")));
        CrewInputContext context = CrewInputContext.resolve(applied, refs);
        applied.add("later"); refs.clear();
        assertEquals(1, context.references.size());
        assertEquals(CrewInputContext.Coverage.COMPLETE, context.coverage);
        assertThrows(UnsupportedOperationException.class, () -> context.references.clear());
        assertThrows(UnsupportedOperationException.class, () -> context.unresolvedInputIds.add("injected"));
    }
    @Test public void correlationHashNeverBecomesInputPayloadHash() {
        CrewInputContext.Reference ref = message("input");
        assertEquals(HASH, ref.correlationSha256);
        assertEquals("", ref.sourcePayloadSha256);
        assertEquals("", ref.deliverySha256);
        assertEquals("", ref.transportSha256);
        assertEquals("", ref.firstIncorporationCycleId);
    }
    @Test public void nativeCorrectionKeepsControlIdentityRatherThanInventingCentralMessage() {
        CrewInputContext.Reference ref = binding("mailbox", "NATIVE_CORRECT", "native-control");
        assertEquals("native-control", ref.sourceId);
        assertEquals("NATIVE_CORRECT", ref.sourceKind);
        assertEquals("first-cycle", ref.firstIncorporationCycleId);
        assertEquals("", ref.correlationMessageId);
        assertEquals("", ref.correlationReferenceId);
    }
    @Test public void resultOrderAndEqualityDoNotDependOnCandidateIterationOrder() {
        List<CrewInputContext.Reference> refs = Arrays.asList(message("second"), message("first"));
        CrewInputContext first = CrewInputContext.resolve(Arrays.asList("second", "first", "missing"), refs);
        CrewInputContext second = CrewInputContext.resolve(Arrays.asList("missing", "first", "second"),
                Arrays.asList(message("first"), message("second")));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }
}
