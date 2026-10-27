package com.jarvys.agent;

/** Historical operational metadata, not effect authorization or a Coding durable-job receipt.
 * Scope and checkpoint identity are real; this value intentionally claims no current Room generation.
 * Input references describe plural checkpoint context, never an exclusive cause for this command.
 */
public final class CrewInvocationSource {
    public final String executionSessionId, botId, missionId, cycleId, scopeIdentity;
    public final String checkpointPayloadSha256, callId, toolName;
    public final CrewInputContext inputContext;

    CrewInvocationSource(CrewCheckpointStore.ConfirmedSnapshot checkpoint,
                         CoreAgentLoop.PreparedInvocationSnapshot prepared) {
        executionSessionId = checkpoint.conversationId; botId = checkpoint.botId;
        missionId = required(checkpoint.metadataJson().optString("missionId", ""));
        cycleId = required(checkpoint.metadataJson().optString("cycleId", ""));
        scopeIdentity = checkpoint.scopeIdentity; checkpointPayloadSha256 = checkpoint.payloadSha256;
        callId = prepared.callId(); toolName = prepared.toolName();
        inputContext = CrewInputContextExtractor.fromConfirmed(checkpoint);
    }
    private static String required(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException("Crew checkpoint identity missing");
        return value;
    }
}
