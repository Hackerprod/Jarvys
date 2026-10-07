package com.jarvys.agent;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Real provider-backed Planner role; outputs an ordered list of executable milestones. */
public final class PlannerAgent {
    private final ModelProviderClient provider;
    private final String model;

    public PlannerAgent(ModelProviderClient provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    public List<String> plan(String goal, CancellationToken token) {
        return plan(goal, "jarvys-planner-" + java.util.UUID.randomUUID(), "", token);
    }

    public List<String> plan(String goal, String sessionId, String conversationContext, CancellationToken token) {
        ModelReply reply = provider.complete(AgentPrompts.PLANNER, AgentPrompts.plannerUser(goal, conversationContext),
                Collections.emptyList(), Collections.emptyList(), sessionId, token);
        String text = reply.text.trim();
        int first = text.indexOf('['), last = text.lastIndexOf(']');
        if (first < 0 || last <= first) throw new IllegalStateException("Planner did not return a JSON array of subgoals");
        try {
            JSONArray payload = new JSONArray(text.substring(first, last + 1));
            List<String> plan = new ArrayList<>();
            for (int i = 0; i < payload.length(); i++) {
                String item = payload.optString(i, "").trim();
                if (!item.isEmpty()) plan.add(item);
            }
            if (plan.isEmpty() || plan.size() > 8) throw new IllegalStateException("Planner must return 1-8 non-empty subgoals");
            return plan;
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("Planner returned invalid JSON", e);
        }
    }
}
