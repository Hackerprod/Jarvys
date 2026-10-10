package com.jarvys.agent;

import com.jarvys.agent.skills.SkillEntry;
import com.jarvys.agent.skills.SkillScopePolicy;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads one enabled skill body on demand instead of paying its context cost on every model turn. */
public final class LoadSkillTool implements CoreTool {
    private final Map<String, SkillEntry> skills = new LinkedHashMap<>();
    private final int maxBodyChars;
    private final java.util.function.Predicate<String> currentlyAvailable;
    private final ToolSpec declaration;

    public LoadSkillTool(Collection<SkillEntry> availableSkills, int maxBodyChars) {
        this(availableSkills, maxBodyChars, id -> true);
    }

    public LoadSkillTool(Collection<SkillEntry> availableSkills, int maxBodyChars,
                         java.util.function.Predicate<String> currentlyAvailable) {
        this(availableSkills, maxBodyChars, currentlyAvailable, null);
    }

    public LoadSkillTool(Collection<SkillEntry> availableSkills, int maxBodyChars,
                         java.util.function.Predicate<String> currentlyAvailable, String profileId) {
        this.currentlyAvailable = currentlyAvailable;
        for (SkillEntry skill : SkillScopePolicy.forProfile(availableSkills, profileId)) {
            skills.put(skill.getMetadata().getId(), skill);
        }
        this.maxBodyChars = maxBodyChars;
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("skill_id", "string");
        if (skills.values().stream().anyMatch(skill -> skill.getFactoryGuidance() != null)) {
            properties.put("resource", "string");
            properties.put("resource_version", "string");
        }
        declaration = new ToolSpec("read_skill", "jarvys/core",
                "Load the complete body of one catalogued skill by id. Available ids: " + skills.keySet()
                    + (properties.containsKey("resource") ? ". For Factory details, supply resource and resource_version together, exactly as listed in its core. No paths or external files." : ""),
                "core", ToolSpec.Status.IMPLEMENTED, properties, Collections.singletonList("skill_id"));
    }

    Collection<SkillEntry> availableSkills() { return Collections.unmodifiableCollection(skills.values()); }

    LoadSkillTool narrow(Collection<String> selectedIds) {
        for (String id : selectedIds) {
            if (SkillScopePolicy.isReserved(id)) {
                throw new IllegalArgumentException("Coding runtime skills cannot be passed to delegated workers");
            }
        }
        java.util.List<SkillEntry> selected = new java.util.ArrayList<>();
        for (SkillEntry skill : skills.values()) {
            if (selectedIds.contains(skill.getMetadata().getId())) selected.add(skill);
        }
        return new LoadSkillTool(selected, maxBodyChars, currentlyAvailable);
    }

    public String displaySkillName(String id) {
        SkillEntry entry = skills.get(id);
        return entry == null ? id : entry.getMetadata().getName();
    }

    @Override public ToolSpec declaration() { return declaration; }

    @Override public CoreToolResult execute(Map<String, Object> arguments, CancellationToken token) {
        Object rawId = arguments.get("skill_id");
        if (!(rawId instanceof String)) return CoreToolResult.failure("skill_id must be a string from the available catalog");
        SkillEntry skill = skills.get(rawId);
        if (skill == null || !currentlyAvailable.test((String) rawId)) return CoreToolResult.failure("That skill is not available in this agent's scope or is no longer enabled");
        boolean hasResource = arguments.containsKey("resource");
        boolean hasVersion = arguments.containsKey("resource_version");
        if (hasResource != hasVersion) return CoreToolResult.failure("resource and resource_version must be supplied together");
        String content;
        if (hasResource) {
            Object resource = arguments.get("resource");
            Object version = arguments.get("resource_version");
            com.jarvys.agent.skills.FactoryGuidanceBundle bundle = skill.getFactoryGuidance();
            if (!(resource instanceof String) || !(version instanceof String) || bundle == null
                    || !bundle.getVersion().equals(version) || !bundle.getResources().containsKey(resource)) {
                return CoreToolResult.failure("Unknown or unavailable skill resource/version; read the current core catalog. No partial resource was loaded.");
            }
            content = "Skill " + skill.getMetadata().getId() + " — resource " + resource
                    + " @ " + bundle.getVersion() + "\n" + bundle.getResources().get(resource);
        } else {
            content = "Skill " + skill.getMetadata().getId() + " — " + skill.getMetadata().getName()
                    + "\n" + skill.getBody();
        }
        if (content.length() > maxBodyChars) {
            return CoreToolResult.failure("Skill body exceeds this run's full-content budget (" + maxBodyChars
                    + " characters, including header); it was not partially loaded. Ask a narrower question or use another skill.");
        }
        token.throwIfCancelled();
        return CoreToolResult.complete(content);
    }
}
