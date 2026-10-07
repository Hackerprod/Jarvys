package com.jarvys.agent;

import com.jarvys.agent.device.ScreenData;

import java.util.List;
import java.util.Map;

/** Provider/model boundary shared by the live providers and deterministic no-network test model. */
public interface AgentModel {
    List<String> createPlan(String goal, CancellationToken token);
    default List<String> createPlan(String goal, String sessionId, String conversationContext, CancellationToken token) {
        return createPlan(goal, token);
    }
    OperatorDecision decide(AgentState state, CancellationToken token);

    /** Returns null when name is not a dynamic provider-specific tool; fixed tools remain in ToolRegistry. */
    default ToolResult invokeDynamicTool(String name, Map<String, Object> arguments, CancellationToken token) {
        return null;
    }

    /** Optional per-run skill allowlist gate; default models preserve their existing dispatch behavior. */
    default boolean isToolAllowed(String name) {
        return true;
    }

    default String visionQuery(String instruction, ScreenData screen, CancellationToken token) {
        throw new UnsupportedOperationException("Vision queries require a configured multimodal provider");
    }

    default boolean verifyVisualTarget(String description, int[] normalizedPoint,
                                       ScreenData screen, CancellationToken token) {
        throw new UnsupportedOperationException("Visual target validation requires a configured multimodal provider");
    }

    default String explore(String query, String feedback, AgentState state, CancellationToken token) {
        throw new UnsupportedOperationException("Explorer requires a configured multimodal provider");
    }

    default String detectObjects(List<String> queries, AgentState state, CancellationToken token) {
        throw new UnsupportedOperationException("Object detection requires a configured vision provider");
    }

    default String getOcrList(AgentState state, CancellationToken token) {
        throw new UnsupportedOperationException("OCR requires a configured vision provider");
    }

    default String askPerception(String query, int nx, int ny, List<String> detectQueries,
                                 AgentState state, CancellationToken token) {
        throw new UnsupportedOperationException("Perception tools require a configured multimodal provider");
    }

    default String diagnose(String query, AgentState state, CancellationToken token) {
        throw new UnsupportedOperationException("Diagnoser requires a configured provider");
    }

    default String summarizeStep(StepRecord step, CancellationToken token) {
        token.throwIfCancelled();
        String action = step.decisions.isEmpty() ? "no action" : String.valueOf(step.decisions.get(0).get("name"));
        return "Step " + step.number + ": " + action + " — "
                + (step.success ? "completed" : "failed") + ".";
    }

    default String summarizeStep(StepRecord step, String sessionId, CancellationToken token) {
        return summarizeStep(step, token);
    }
}
