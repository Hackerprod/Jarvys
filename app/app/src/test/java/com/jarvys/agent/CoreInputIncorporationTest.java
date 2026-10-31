package com.jarvys.agent;

import static org.junit.Assert.*;
import com.jarvys.agent.crew.CrewInputBinding;
import com.jarvys.agent.crew.CrewInputIncorporation;
import com.jarvys.agent.crew.CrewMessage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

public class CoreInputIncorporationTest {
    private static final String FIRST = "11111111-1111-1111-1111-111111111111";
    private static final String NEXT = "22222222-2222-2222-2222-222222222222";
    private static final String SHA = String.join("", Collections.nCopies(64, "a"));

    @Test public void exactUserAndUntrustedAnswerCaptureActualTransportAndPreserveFirstCycle() {
        CrewMessage correction = bound("correction", "  exact\n ñ \ud800  ", true);
        CrewMessage answer = bound("answer", "<untrusted & answer>", false);
        CoreAgentLoop loop = incoming(Arrays.asList(correction, answer));
        loop.setCheckpointScope(FIRST);
        loop.run("mission", Collections.emptyList(), CancellationToken.uncancellable(), null);
        CoreAgentLoop.Checkpoint saved = loop.checkpointSnapshot();
        CrewInputIncorporation user = saved.incorporations.get(correction.id);
        CrewInputIncorporation reply = saved.incorporations.get(answer.id);
        assertEquals(CrewInputIncorporation.TransportKind.USER, user.transportKind);
        assertEquals(CrewInputIncorporation.transportSha256(correction.text), user.transportSha256);
        assertEquals(CrewInputIncorporation.TransportKind.INBOX, reply.transportKind);
        ConversationTurn actual = saved.transcript.stream().filter(turn -> turn.incomingMetadata != null
                && answer.id.equals(turn.incomingMetadata.id))
                .findFirst().get();
        assertEquals(answer.text, actual.content);
        assertEquals(CrewInputIncorporation.transportSha256(actual.content), reply.transportSha256);
        assertTrue(actual.incomingMetadata.untrusted);
        assertEquals("crew", actual.incomingMetadata.source);
        assertEquals("chief", actual.incomingMetadata.sender);
        assertEquals("ANSWER", actual.incomingMetadata.type);
        assertFalse(saved.transcript.stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.TOOL_CALLS
                && turn.toolCalls.stream().anyMatch(call -> CoreAgentLoop.INBOX_TOOL.equals(call.name))));
        loop.setCheckpointScope(NEXT);
        loop.run("next", saved.transcript, CancellationToken.uncancellable(), null);
        assertEquals(FIRST, loop.checkpointSnapshot().incorporations.get(answer.id).firstIncorporationCycleId);
        assertTrue(reply.samePayload(loop.checkpointSnapshot().incorporations.get(answer.id)));
        assertTrue(user.samePayload(saved.reconciled().incorporations.get(correction.id)));
        assertThrows(UnsupportedOperationException.class, () -> saved.incorporations.clear());
    }

    @Test public void resolvedContinuationAnswerPreservesExactProofAndFullNativeProvenance() {
        CrewMessage message = nativeAnswer("answer-id", "June 4");
        CrewInputBinding binding = message.inputBinding;
        ConversationInput.Provenance provenance = message.inputProvenance;
        ConversationInput durable = new ConversationInput(message.conversationId, message.id, message.text, ConversationInput.Origin.AGENT, provenance);
        CoreAgentLoop.IncomingMessage resolved = CoreAgentLoop.IncomingMessage.agentInput(durable, message);
        assertTrue(binding.samePayload(resolved.inputBinding));
        assertEquals(provenance.toJson().toString(), resolved.provenance.toJson().toString());
        assertEquals(provenance.toJson().toString(), CoreAgentLoop.IncomingMessage.fromCrewMessage(message).provenance.toJson().toString());
        CoreAgentLoop loop = new CoreAgentLoop((CoreAgentLoop.Model) (history, prompt, tools, token) ->
                new ModelReply("done", Collections.emptyList()), new CoreToolRegistry(Collections.emptyList()), "", message.conversationId,
                CorePromptBudget.standard(), null, CoreAgentLoop.ToolResultBudget.UNBOUNDED,
                new CoreAgentLoop.TurnContextProvider() {
                    @Override public List<CoreAgentLoop.IncomingMessage> takeDurableMessages() {
                        return Collections.singletonList(resolved);
                    }
                }, null);
        loop.setCheckpointScope(FIRST);
        loop.run("mission", Collections.emptyList(), CancellationToken.uncancellable(), null);
        CrewInputIncorporation proof = loop.checkpointSnapshot().incorporations.get(message.id);
        assertNotNull(proof);
        assertTrue(binding.samePayload(proof.binding));
        assertEquals(CrewInputIncorporation.transportSha256("June 4"), proof.transportSha256);
        assertFalse(resolved.genuineUser);
    }

    @Test public void resolvedInputCannotAttachProofToDifferentPayloadOrRedactedDelivery() throws Exception {
        CrewMessage original = nativeAnswer("answer", "exact answer");
        ConversationInput.Provenance provenance = original.inputProvenance;
        assertThrows(IllegalArgumentException.class, () -> CoreAgentLoop.IncomingMessage.agentInput(
                new ConversationInput(original.conversationId, original.id, "changed", ConversationInput.Origin.AGENT, provenance), original));
        assertThrows(IllegalArgumentException.class, () -> CoreAgentLoop.IncomingMessage.agentInput(
                new ConversationInput(original.conversationId, "another-id", original.text, ConversationInput.Origin.AGENT, provenance), original));
        assertThrows(IllegalArgumentException.class, () -> CoreAgentLoop.IncomingMessage.agentInput(
                new ConversationInput("another-session", original.id, original.text, ConversationInput.Origin.AGENT, provenance), original));
        assertThrows(IllegalArgumentException.class, () -> CoreAgentLoop.IncomingMessage.agentInput(
                new ConversationInput(original.conversationId, original.id, original.text, ConversationInput.Origin.SYSTEM, provenance), original));
        CrewMessage attributed = original.withInputProvenance(provenance);
        ConversationInput.Provenance peer = new ConversationInput.Provenance(provenance.sessionId, provenance.workspaceId,
                provenance.assignmentId, provenance.botId, original.id, ConversationInput.Kind.ANSWER,
                ConversationInput.Provenance.PrincipalRole.PEER, provenance.correlationMessageId, provenance.referenceId,
                provenance.contentSha256, provenance.assignmentRevision, provenance.requestId, provenance.missionId, provenance.cycleId);
        assertThrows(IllegalArgumentException.class, () -> CoreAgentLoop.IncomingMessage.agentInput(
                new ConversationInput(original.conversationId, original.id, original.text, ConversationInput.Origin.AGENT, peer), attributed));
        CrewMessage redacted = CrewMessage.fromJson(original.toJson().put("text", "[redacted]"));
        CoreAgentLoop.IncomingMessage noProof = CoreAgentLoop.IncomingMessage.agentInput(
                new ConversationInput(original.conversationId, original.id, redacted.text, ConversationInput.Origin.AGENT, provenance), redacted);
        assertNull(noProof.inputBinding);
    }

    @Test public void admissionAndIncorporationCyclesAreIndependent() {
        CrewMessage correction = bound("correction", "actual", true);
        CoreAgentLoop loop = incoming(Collections.singletonList(correction));
        loop.setCheckpointScope(NEXT);
        loop.run("mission", Collections.emptyList(), CancellationToken.uncancellable(), null);
        CrewInputIncorporation proof = loop.checkpointSnapshot().incorporations.get(correction.id);
        assertEquals(FIRST, proof.binding.admissionCycleId);
        assertEquals(NEXT, proof.firstIncorporationCycleId);
    }

    @Test public void pendingRedactedCopyAndLegacyAppliedIdCannotMintOriginalProof() throws Exception {
        CrewMessage original = bound("answer", "api_key=sk-secret-0123456789", false);
        JSONObject copy = original.toJson().put("text", "[redacted]");
        CrewMessage sanitized = CrewMessage.fromJson(copy);
        assertFalse(sanitized.inputBinding.matchesDelivery(sanitized));
        CoreAgentLoop redacted = incoming(Collections.singletonList(sanitized));
        redacted.setCheckpointScope(FIRST);
        redacted.run("mission", Collections.emptyList(), CancellationToken.uncancellable(), null);
        assertTrue(redacted.checkpointSnapshot().appliedIncomingIds.contains(original.id));
        assertTrue(redacted.checkpointSnapshot().incorporations.isEmpty());
        CoreAgentLoop legacy = incoming(Collections.singletonList(original));
        legacy.restoreCheckpoint(new CoreAgentLoop.Checkpoint(Collections.emptyList(), Collections.singleton(original.id),
                Collections.emptyMap(), -1));
        legacy.setCheckpointScope(FIRST);
        legacy.run("mission", Collections.emptyList(), CancellationToken.uncancellable(), null);
        assertTrue(legacy.checkpointSnapshot().incorporations.isEmpty());
    }

    @Test public void genericInputAndAbsentCrewScopeHaveNoProof() {
        CrewMessage generic = new CrewMessage("generic", "chat", "user", "bot", CrewMessage.Type.USER,
                "text", Collections.emptyList(), 1L);
        CoreAgentLoop loop = incoming(Collections.singletonList(generic));
        loop.setCheckpointScope(FIRST);
        loop.run("mission", Collections.emptyList(), CancellationToken.uncancellable(), null);
        assertTrue(loop.checkpointSnapshot().incorporations.isEmpty());
        CoreAgentLoop withoutScope = incoming(Collections.singletonList(bound("bound", "text", true)));
        withoutScope.run("mission", Collections.emptyList(), CancellationToken.uncancellable(), null);
        assertTrue(withoutScope.checkpointSnapshot().incorporations.isEmpty());
    }

    @Test public void askChiefAcknowledgementLeavesCorrelatedReplyAsSeparateSupervisorInboxInput() {
        CrewMessage reply = bound("correlated-reply", "<untrusted & supervisor reply>", false);
        AtomicBoolean admitReplyAfterAsk = new AtomicBoolean();
        CoreTool tool = tool("ask_chief", () -> {
            admitReplyAfterAsk.set(true);
            return CoreToolResult.success("Question sent; the correlated chief reply was admitted as supervisor input for the next worker step.");
        });
        AtomicInteger turns = new AtomicInteger();
        AtomicReference<List<ConversationTurn>> afterAsk = new AtomicReference<>();
        CoreAgentLoop loop = new CoreAgentLoop((CoreAgentLoop.Model) (transcript, prompt, tools, token) -> {
            int turn = turns.incrementAndGet();
            if (turn == 1) return new ModelReply("", Collections.singletonList(new ModelReply.Call("ask",
                    "ask_chief", Collections.emptyMap())));
            afterAsk.set(new java.util.ArrayList<>(transcript));
            return new ModelReply("done", Collections.emptyList());
        }, new CoreToolRegistry(Collections.singletonList(tool)), "", "chat", CorePromptBudget.standard(), null,
                CoreAgentLoop.ToolResultBudget.UNBOUNDED, new CoreAgentLoop.TurnContextProvider() {
                    @Override public List<CoreAgentLoop.IncomingMessage> takeDurableMessages() {
                        if (!admitReplyAfterAsk.getAndSet(false)) return Collections.emptyList();
                        return Collections.singletonList(CoreAgentLoop.IncomingMessage.fromCrewMessage(reply));
                    }
                }, null);
        loop.setCheckpointScope(FIRST);
        loop.run("mission", Collections.emptyList(), CancellationToken.uncancellable(), null);
        List<ConversationTurn> transcript = afterAsk.get();
        assertNotNull(transcript);
        ConversationTurn toolResult = transcript.stream().filter(turn -> turn.kind == ConversationTurn.Kind.TOOL_RESULT
                && "ask_chief".equals(turn.toolName)).findFirst().get();
        assertEquals("Question sent; the correlated chief reply was admitted as supervisor input for the next worker step.",
                toolResult.content);
        assertFalse(toolResult.content.contains(reply.text));
        ConversationTurn separateReply = transcript.stream().filter(turn -> turn.incomingMetadata != null
                && reply.id.equals(turn.incomingMetadata.id)).findFirst().get();
        assertEquals(ConversationInput.Origin.AGENT, separateReply.incomingMetadata.origin);
        assertEquals(ConversationInput.Provenance.PrincipalRole.SUPERVISOR,
                separateReply.incomingMetadata.provenance.principalRole);
        assertEquals(reply.text, separateReply.content);
        assertTrue(loop.checkpointSnapshot().appliedIncomingIds.contains(reply.id));
        CrewInputIncorporation proof = loop.checkpointSnapshot().incorporations.get(reply.id);
        assertEquals(CrewInputIncorporation.TransportKind.INBOX, proof.transportKind);
        assertEquals(CrewInputIncorporation.transportSha256(reply.text), proof.transportSha256);
        assertTrue("the reply is incorporated as its own later supervisor input", transcript.indexOf(toolResult) < transcript.indexOf(separateReply));
    }

    @Test public void compactedHistoricalProofSurvivesWithoutClaimingCurrentText() {
        CrewMessage message = bound("answer", "exact historical text", false);
        CoreAgentLoop original = incoming(Collections.singletonList(message));
        original.setCheckpointScope(FIRST);
        original.run("mission", Collections.emptyList(), CancellationToken.uncancellable(), null);
        CoreAgentLoop.Checkpoint saved = original.checkpointSnapshot();
        CoreAgentLoop.Checkpoint compacted = new CoreAgentLoop.Checkpoint(Collections.singletonList(
                ConversationTurn.restoredCompactionSummary("summary without original content", 4)),
                saved.appliedIncomingIds, saved.toolLifecycle, -1, saved.incorporations);
        CoreAgentLoop restored = incoming(Collections.emptyList());
        restored.restoreCheckpoint(compacted);
        restored.setCheckpointScope(NEXT);
        restored.run("explicit continuation", restored.transcriptSnapshot(), CancellationToken.uncancellable(), null);
        assertEquals(FIRST, restored.checkpointSnapshot().incorporations.get(message.id).firstIncorporationCycleId);
        assertFalse(restored.checkpointSnapshot().transcript.stream().anyMatch(turn -> turn.content.contains(message.text)));
    }

    @Test public void failedCheckpointDoesNotTurnInMemoryProofIntoPersistence() {
        CrewMessage correction = bound("correction", "exact", true);
        CoreAgentLoop loop = incoming(Collections.singletonList(correction));
        loop.setCheckpointScope(FIRST);
        loop.setCheckpointListener(saved -> {
            if (saved.incorporations.containsKey(correction.id)) throw new IllegalStateException("save failed");
        });
        assertThrows(CoreAgentLoop.CheckpointFailure.class, () -> loop.run("mission", Collections.emptyList(), CancellationToken.uncancellable(), null));
        // This is historical append evidence only; no durability confirmation is inferred here.
        assertEquals(1, loop.checkpointSnapshot().incorporations.size());
    }

    private static CrewMessage nativeAnswer(String id, String text) {
        String owner = com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION;
        String workspace = "33333333-3333-3333-3333-333333333333";
        String session = com.jarvys.agent.workspaces.WorkspaceIdentity.executionSessionId(workspace);
        CrewInputBinding binding = CrewInputBinding.create(CrewInputBinding.SourceKind.CONTINUATION_ANSWER,
                "answer-source", owner, workspace, session, "assignment", "mission", "bot", id, SHA, FIRST, text);
        ConversationInput.Provenance provenance = new ConversationInput.Provenance(owner, workspace, "assignment",
                "bot", id, ConversationInput.Kind.ANSWER, ConversationInput.Provenance.PrincipalRole.SUPERVISOR,
                "question-id", "", "", 1L, "request", "mission", FIRST);
        return new CrewMessage(id, session, "chief", "bot", CrewMessage.Type.ANSWER,
                text, Collections.emptyList(), 1L).withInputBinding(binding).withInputProvenance(provenance);
    }

    public static CrewMessage bound(String id, String text, boolean user) {
        CrewInputBinding binding = CrewInputBinding.create(user ? CrewInputBinding.SourceKind.NATIVE_CORRECT : CrewInputBinding.SourceKind.WORKSPACE_REPLY,
                "source-" + id, "owner", "workspace", "chat", "assignment", "mission", "bot", id, SHA, FIRST, text);
        return new CrewMessage(id, "chat", user ? "user" : "chief", "bot", user ? CrewMessage.Type.USER : CrewMessage.Type.ANSWER,
                text, Collections.emptyList(), 1L).withInputBinding(binding);
    }
    private static CoreAgentLoop incoming(List<CrewMessage> messages) {
        return new CoreAgentLoop((CoreAgentLoop.Model) (transcript, prompt, tools, token) -> new ModelReply("done", Collections.emptyList()),
                new CoreToolRegistry(Collections.emptyList()), "", "chat", CorePromptBudget.standard(), null,
                CoreAgentLoop.ToolResultBudget.UNBOUNDED, new CoreAgentLoop.TurnContextProvider() {
                    @Override public String takeUntrustedContext() { return ""; }
                    @Override public List<CoreAgentLoop.IncomingMessage> takeDurableMessages() {
                        java.util.ArrayList<CoreAgentLoop.IncomingMessage> rows = new java.util.ArrayList<>();
                        for (CrewMessage message : messages) rows.add(CoreAgentLoop.IncomingMessage.fromCrewMessage(message));
                        return rows;
                    }
                }, null);
    }
    private static CoreTool tool(String name, java.util.function.Supplier<CoreToolResult> result) {
        return new CoreTool() {
            @Override public ToolSpec declaration() { return new ToolSpec(name, "test", "test", "test", ToolSpec.Status.IMPLEMENTED, Collections.emptyMap(), Collections.emptyList()); }
            @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) { return result.get(); }
        };
    }
}
