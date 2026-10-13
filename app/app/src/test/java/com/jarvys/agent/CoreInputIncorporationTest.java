package com.jarvys.agent;

import static org.junit.Assert.*;
import com.jarvys.agent.crew.CrewInputBinding;
import com.jarvys.agent.crew.CrewInputIncorporation;
import com.jarvys.agent.crew.CrewManager;
import com.jarvys.agent.crew.CrewMessage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
        String actual = saved.transcript.stream().filter(turn -> ("crew-message-" + answer.id).equals(turn.toolCallId))
                .findFirst().get().content;
        assertEquals(CrewInputIncorporation.transportSha256(actual), reply.transportSha256);
        assertTrue(actual.contains("UNTRUSTED"));
        assertFalse(saved.transcript.stream().anyMatch(turn -> "user".equals(turn.role) && turn.content.contains(answer.text)));
        loop.setCheckpointScope(NEXT);
        loop.run("next", saved.transcript, CancellationToken.uncancellable(), null);
        assertEquals(FIRST, loop.checkpointSnapshot().incorporations.get(answer.id).firstIncorporationCycleId);
        assertTrue(reply.samePayload(loop.checkpointSnapshot().incorporations.get(answer.id)));
        assertTrue(user.samePayload(saved.reconciled().incorporations.get(correction.id)));
        assertThrows(UnsupportedOperationException.class, () -> saved.incorporations.clear());
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

    @Test public void exactAskChiefBatchIgnoresGlobalIdsAndUserWakeCannotAttributeOldAnswers() {
        CrewMessage first = bound("first", "answer one", false);
        CrewMessage second = bound("second", "answer two", false);
        AtomicInteger calls = new AtomicInteger();
        CoreTool tool = tool("ask_chief", () -> calls.incrementAndGet() == 1
                ? CoreToolResult.crewAnswers(Arrays.asList(first, second))
                : CoreToolResult.crewAnswers(Collections.emptyList()));
        AtomicInteger turns = new AtomicInteger();
        CoreAgentLoop loop = new CoreAgentLoop((CoreAgentLoop.Model) (transcript, prompt, tools, token) -> {
            int turn = turns.incrementAndGet();
            return turn < 3 ? new ModelReply("", Collections.singletonList(new ModelReply.Call("ask-" + turn,
                    "ask_chief", Collections.emptyMap()))) : new ModelReply("done", Collections.emptyList());
        }, new CoreToolRegistry(Collections.singletonList(tool)), "", "chat", CorePromptBudget.standard(), null,
                CoreAgentLoop.Limits.UNBOUNDED, new CoreAgentLoop.TurnContextProvider() {
                    @Override public String takeUntrustedContext() { return ""; }
                    @Override public List<String> completedToolIncomingIds(String name) { return Collections.singletonList("unrelated-global-id"); }
                }, null);
        loop.setCheckpointScope(FIRST);
        loop.run("mission", Collections.emptyList(), CancellationToken.uncancellable(), null);
        assertEquals(2, loop.checkpointSnapshot().appliedIncomingIds.size());
        assertFalse(loop.checkpointSnapshot().appliedIncomingIds.contains("unrelated-global-id"));
        String transport = CrewInputIncorporation.transportSha256(CrewManager.formatMessages(Arrays.asList(first, second)));
        for (CrewInputIncorporation proof : loop.checkpointSnapshot().incorporations.values()) {
            assertEquals(CrewInputIncorporation.TransportKind.ASK_CHIEF, proof.transportKind);
            assertEquals(transport, proof.transportSha256);
        }
        CoreToolResult actual = CoreToolResult.crewAnswers(Collections.singletonList(first));
        assertFalse(actual.incomingBatch.matches("ask_chief", actual.content + " changed"));
        assertFalse(actual.incomingBatch.matches("different_tool", actual.content));
        assertNull(CoreToolResult.success(actual.content).incomingBatch);
    }

    @Test public void wrongToolCannotUseAnswerSidecar() {
        CoreTool wrong = tool("other", () -> CoreToolResult.crewAnswers(Collections.singletonList(bound("answer", "text", false))));
        CoreAgentLoop loop = new CoreAgentLoop((CoreAgentLoop.Model) (transcript, prompt, tools, token) ->
                new ModelReply("", Collections.singletonList(new ModelReply.Call("call", "other", Collections.emptyMap()))),
                new CoreToolRegistry(Collections.singletonList(wrong)), "", "chat");
        loop.setCheckpointScope(FIRST);
        assertThrows(IllegalStateException.class, () -> loop.run("mission", Collections.emptyList(), CancellationToken.uncancellable(), null));
        assertTrue(loop.checkpointSnapshot().incorporations.isEmpty());
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

    public static CrewMessage bound(String id, String text, boolean user) {
        CrewInputBinding binding = CrewInputBinding.create(user ? CrewInputBinding.SourceKind.NATIVE_CORRECT : CrewInputBinding.SourceKind.WORKSPACE_REPLY,
                "source-" + id, "owner", "workspace", "chat", "assignment", "mission", "bot", id, SHA, FIRST, text);
        return new CrewMessage(id, "chat", user ? "user" : "chief", "bot", user ? CrewMessage.Type.USER : CrewMessage.Type.ANSWER,
                text, Collections.emptyList(), 1L).withInputBinding(binding);
    }
    private static CoreAgentLoop incoming(List<CrewMessage> messages) {
        return new CoreAgentLoop((CoreAgentLoop.Model) (transcript, prompt, tools, token) -> new ModelReply("done", Collections.emptyList()),
                new CoreToolRegistry(Collections.emptyList()), "", "chat", CorePromptBudget.standard(), null,
                CoreAgentLoop.Limits.UNBOUNDED, new CoreAgentLoop.TurnContextProvider() {
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
