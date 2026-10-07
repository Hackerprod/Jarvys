package com.jarvys.agent;

import com.jarvys.agent.device.ScreenData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Explicit graph state carried from one observe-and-act turn to the next. */
public final class AgentState {
    public final String runId;
    public final String sessionId;
    public final String initialGoal;
    public final String conversationContext;
    public final List<ConversationTurn> conversationHistory;
    public final List<String> plan;
    public final List<String> nodeTrace = new ArrayList<>();
    public final List<StepRecord> steps = new ArrayList<>();
    public final List<String> findings = new ArrayList<>();
    public List<Map<String, Object>> indexedElements = Collections.emptyList();
    public final List<ScreenData> supplementalImages = new ArrayList<>();
    public final List<String> supplementalImageLabels = new ArrayList<>();
    public ScreenData observation;
    public int turn;
    public int completedSubgoals;
    public int successfulActions;
    public String currentNode = "START";
    public boolean done;

    public AgentState(String runId, String initialGoal, List<String> plan) {
        this(runId, runId, initialGoal, plan, "", Collections.emptyList());
    }

    public AgentState(String runId, String sessionId, String initialGoal, List<String> plan, String conversationContext) {
        this(runId, sessionId, initialGoal, plan, conversationContext, Collections.emptyList());
    }

    public AgentState(String runId, String sessionId, String initialGoal, List<String> plan,
                      String conversationContext, List<ConversationTurn> conversationHistory) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.initialGoal = initialGoal;
        this.conversationContext = conversationContext == null ? "" : conversationContext;
        this.conversationHistory = Collections.unmodifiableList(new ArrayList<>(conversationHistory));
        this.plan = Collections.unmodifiableList(new ArrayList<>(plan));
    }

    public void enter(String node) {
        currentNode = node;
        nodeTrace.add(node);
    }
}
