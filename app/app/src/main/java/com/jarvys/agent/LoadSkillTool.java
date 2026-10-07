package com.jarvys.agent;

import com.jarvys.agent.skills.SkillEntry;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads one enabled skill body on demand instead of paying its context cost on every model turn. */
public final class LoadSkillTool implements CoreTool {
    private final Map<String, SkillEntry> skills = new LinkedHashMap<>();
    private final int maxBodyChars;
    private final ToolSpec declaration;

    public LoadSkillTool(Collection<SkillEntry> availableSkills, int maxBodyChars) {
        for (SkillEntry skill : availableSkills) skills.put(skill.getMetadata().getId(), skill);
        this.maxBodyChars = maxBodyChars;
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("skill_id", "string");
        declaration = new ToolSpec("read_skill", "jarvys/core",
                "Load the complete body of one catalogued skill by id. Available ids: " + skills.keySet(),
                "core", ToolSpec.Status.IMPLEMENTED, properties, Collections.singletonList("skill_id"));
    }

    @Override public ToolSpec declaration() { return declaration; }

    @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
        Object rawId = arguments.get("skill_id");
        if (!(rawId instanceof String)) return CoreToolResult.failure("skill_id must be a string from the available catalog");
        SkillEntry skill = skills.get(rawId);
        if (skill == null) return CoreToolResult.failure("That skill is not available in this agent's scope");
        if (skill.getBody().length() > maxBodyChars) {
            return CoreToolResult.failure("Skill body exceeds this run's full-content budget (" + maxBodyChars
                    + " characters); it was not partially loaded. Ask a narrower question or use another skill.");
        }
        token.throwIfCancelled();
        String content = "Skill " + skill.getMetadata().getId() + " — " + skill.getMetadata().getName()
                + "\n" + skill.getBody();
        return CoreToolResult.complete(content);
    }
}
