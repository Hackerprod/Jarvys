package com.jarvys.agent;

import com.jarvys.agent.skills.SkillEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs a fresh, bounded child conversation with only explicitly selected parent capabilities. */
public final class DelegateSubtaskTool implements CoreTool {
    public interface Runner {
        String run(String objective, List<String> toolNames, List<String> skillIds, CancellationToken token);
    }

    private final Runner runner;
    private final List<String> availableTools;
    private final List<String> availableSkills;
    private final ToolSpec declaration;

    public DelegateSubtaskTool(Runner runner, Collection<String> availableTools,
                               Collection<SkillEntry> availableSkills) {
        this.runner = runner;
        this.availableTools = Collections.unmodifiableList(new ArrayList<>(availableTools));
        List<String> ids = new ArrayList<>();
        for (SkillEntry skill : availableSkills) ids.add(skill.getMetadata().getId());
        this.availableSkills = Collections.unmodifiableList(ids);
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("objective", "string");
        properties.put("tools", "array");
        properties.put("skills", "array");
        declaration = new ToolSpec("delegate_subtask", "jarvys/core",
                "Run a separate ReAct agent on a bounded subtask. It receives no parent transcript; choose only listed tools and skill ids. "
                        + "Available tools: " + this.availableTools + ". Available skill ids: " + this.availableSkills,
                "core", ToolSpec.Status.IMPLEMENTED, properties, Collections.singletonList("objective"));
    }

    @Override public ToolSpec declaration() { return declaration; }

    @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
        Object rawObjective = arguments.get("objective");
        if (!(rawObjective instanceof String) || ((String) rawObjective).trim().isEmpty()) {
            return CoreToolResult.failure("objective must be a non-empty string");
        }
        String objective = ((String) rawObjective).trim();
        if (objective.length() > 4000) return CoreToolResult.failure("objective exceeds 4000 characters");
        List<String> requestedTools = strings(arguments.get("tools"), "tools");
        List<String> requestedSkills = strings(arguments.get("skills"), "skills");
        if (requestedTools.size() > 8 || requestedSkills.size() > 8) {
            return CoreToolResult.failure("A subtask may receive at most 8 tools and 8 skills");
        }
        if (!availableTools.containsAll(requestedTools)) {
            return CoreToolResult.failure("Subtask requested a tool outside this agent's capability scope");
        }
        if (!availableSkills.containsAll(requestedSkills)) {
            return CoreToolResult.failure("Subtask requested a skill outside this agent's enabled skill scope");
        }
        token.throwIfCancelled();
        String result = runner.run(objective, requestedTools, requestedSkills, token);
        if (result == null || result.trim().isEmpty()) return CoreToolResult.failure("Subagent returned no final text");
        return CoreToolResult.success(result.length() <= 12000
                ? result : result.substring(0, 12000) + "…[truncated]");
    }

    private static List<String> strings(Object value, String field) {
        if (value == null) return Collections.emptyList();
        if (!(value instanceof List)) throw new IllegalArgumentException(field + " must be a list of strings");
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof String) || ((String) item).trim().isEmpty()) {
                throw new IllegalArgumentException(field + " must contain only non-empty strings");
            }
            if (result.contains(item)) throw new IllegalArgumentException(field + " must not contain duplicates");
            result.add((String) item);
        }
        return result;
    }
}
