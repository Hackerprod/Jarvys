package com.jarvys.agent;

import java.util.List;

public final class AgentRunResult {
    public final String runId;
    public final String outcome;
    public final int completedSubgoals;
    public final int totalSubgoals;
    public final int toolCalls;
    public final List<String> graphNodes;
    public final int registeredTools;
    public final int implementedTools;
    public final int partialTools;
    public final int unsupportedTools;
    public final String message;
    public final Integer failureHttpStatus;
    public final String failureResponseBody;
    public final String failureType;
    public final String failureStackTrace;
    public final String failureModel;

    AgentRunResult(String runId, String outcome, int completedSubgoals, int totalSubgoals,
                   int toolCalls, List<String> graphNodes, int registeredTools,
                   int implementedTools, int partialTools, int unsupportedTools, String message,
                   Integer failureHttpStatus, String failureResponseBody, String failureType, String failureStackTrace,
                   String failureModel) {
        this.runId = runId;
        this.outcome = outcome;
        this.completedSubgoals = completedSubgoals;
        this.totalSubgoals = totalSubgoals;
        this.toolCalls = toolCalls;
        this.graphNodes = graphNodes;
        this.registeredTools = registeredTools;
        this.implementedTools = implementedTools;
        this.partialTools = partialTools;
        this.unsupportedTools = unsupportedTools;
        this.message = message;
        this.failureHttpStatus = failureHttpStatus;
        this.failureResponseBody = failureResponseBody == null ? "" : failureResponseBody;
        this.failureType = failureType == null ? "" : failureType;
        this.failureStackTrace = failureStackTrace == null ? "" : failureStackTrace;
        this.failureModel = failureModel == null ? "" : failureModel;
    }

    @Override
    public String toString() {
        return "Etapa C " + outcome + " — pasos " + completedSubgoals + "/" + totalSubgoals
                + ", tool calls " + toolCalls + ", tools " + implementedTools + "/"
                + registeredTools + " registradas: " + implementedTools + " implementadas, "
                + partialTools + " parciales, " + unsupportedTools + " explícitamente no soportadas."
                + " Graph: " + graphNodes + (message == null || message.isEmpty() ? "" : " " + message);
    }
}
