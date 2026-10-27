package com.jarvys.agent;

import com.jarvys.agent.crew.CrewInputBinding;
import com.jarvys.agent.crew.CrewInputIncorporation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure projection from one confirmed snapshot. Performs no lookup, IO, authority or causal inference. */
public final class CrewInputContextExtractor {
    private CrewInputContextExtractor() { }

    public static CrewInputContext fromConfirmed(CrewCheckpointStore.ConfirmedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "confirmed snapshot");
        CoreAgentLoop.Checkpoint loop = snapshot.loopForEvidence();
        List<CrewInputContext.Reference> references = new ArrayList<>();
        String mission = snapshot.metadataJson().optString("missionId", "");
        for (Map.Entry<String, CrewInputIncorporation> entry : loop.incorporations.entrySet()) {
            CrewInputIncorporation incorporated = entry.getValue();
            CrewInputBinding binding = incorporated.binding;
            if (!entry.getKey().equals(binding.mailboxMessageId)
                    || !loop.appliedIncomingIds.contains(entry.getKey())
                    || !snapshot.conversationId.equals(binding.executionSessionId)
                    || !snapshot.botId.equals(binding.botId) || !mission.equals(binding.missionId))
                throw new IllegalArgumentException("Input binding does not belong to the confirmed snapshot");
            CrewInputContext.Scope scope = new CrewInputContext.Scope(binding.ownerCentralSessionId,
                    binding.executionSessionId, binding.workspaceId, binding.assignmentId, binding.botId, binding.missionId);
            references.add(CrewInputContext.Reference.binding(binding.mailboxMessageId,
                    binding.sourceKind.name(), binding.sourceId, scope, binding.admissionCycleId,
                    incorporated.firstIncorporationCycleId, incorporated.transportKind.name(),
                    binding.sourcePayloadSha256, binding.deliverySha256, incorporated.transportSha256));
        }
        for (ConversationTurn turn : loop.transcript) {
            ConversationTurn.IncomingMetadata metadata = turn.incomingMetadata;
            if (metadata == null || metadata.provenance == null
                    || !loop.appliedIncomingIds.contains(metadata.id)) continue;
            ConversationInput.Provenance provenance = metadata.provenance;
            if (!metadata.id.equals(provenance.messageId))
                throw new IllegalArgumentException("Recorded conversation input identity conflicts");
            // Provenance.botId may identify the sender (peer input), not this snapshot's target bot.
            // Keep the recorded tuple; never rewrite it using the assignment root or current bot.
            CrewInputContext.Scope scope = new CrewInputContext.Scope(provenance.sessionId,
                    snapshot.conversationId, provenance.workspaceId, provenance.assignmentId,
                    provenance.botId, provenance.missionId);
            references.add(CrewInputContext.Reference.conversation(metadata.id, provenance.kind.name(),
                    provenance.messageId, scope, provenance.principalRole.name(), provenance.correlationMessageId,
                    provenance.referenceId, provenance.contentSha256, provenance.assignmentRevision,
                    metadata.origin.name(), provenance.requestId, provenance.cycleId));
        }
        return CrewInputContext.resolve(loop.appliedIncomingIds, references);
    }
}
