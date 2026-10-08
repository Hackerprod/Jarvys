package com.jarvys.agent.crew;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Capability-scoped bot role; model is reserved for future per-role routing. */
public final class CrewRole {
    public final String id;
    public final String name;
    public final String colorKey;
    public final String missionPrompt;
    public final List<String> tools;
    public final String model;
    public final String description;
    public final int profileVersion;
    public final List<String> skillIds;
    public final CrewProfile.WorkspaceMode workspaceMode;
    public final CrewMissionAccess missionAccess;

    public CrewRole(String id, String name, String colorKey, String missionPrompt,
                    List<String> tools, String model) {
        this(id, name, colorKey, missionPrompt, tools, model, name, 0, Collections.emptyList());
    }

    public CrewRole(String id, String name, String colorKey, String missionPrompt,
                    List<String> tools, String model, String description, int profileVersion, List<String> skillIds) {
        this(id, name, colorKey, missionPrompt, tools, model, description, profileVersion, skillIds,
                CrewProfile.WorkspaceMode.LEGACY_CHAT);
    }

    public CrewRole(String id, String name, String colorKey, String missionPrompt,
                    List<String> tools, String model, String description, int profileVersion,
                    List<String> skillIds, CrewProfile.WorkspaceMode workspaceMode) {
        this(id, name, colorKey, missionPrompt, tools, model, description, profileVersion, skillIds,
                workspaceMode, CrewMissionAccess.STANDARD);
    }

    public CrewRole(String id, String name, String colorKey, String missionPrompt,
                    List<String> tools, String model, String description, int profileVersion,
                    List<String> skillIds, CrewProfile.WorkspaceMode workspaceMode, CrewMissionAccess missionAccess) {
        if (missionAccess == null) throw new IllegalArgumentException("mission access is required");
        this.missionAccess = missionAccess;
        this.id = required(id, "id");
        this.name = required(name, "name");
        this.colorKey = required(colorKey, "colorKey");
        this.missionPrompt = required(missionPrompt, "missionPrompt");
        LinkedHashSet<String> unique = new LinkedHashSet<>(tools == null ? Collections.emptyList() : tools);
        if (unique.size() != (tools == null ? 0 : tools.size())) throw new IllegalArgumentException("Duplicate role tools are not allowed");
        unique.removeIf(nameToCheck -> !missionAccess.permits(nameToCheck));
        this.tools = Collections.unmodifiableList(new ArrayList<>(unique));
        this.model = model == null || model.trim().isEmpty() ? null : model.trim();
        this.description = required(description, "description");
        if (profileVersion < 0) throw new IllegalArgumentException("profileVersion cannot be negative");
        this.profileVersion = profileVersion;
        if (workspaceMode == null) throw new IllegalArgumentException("workspaceMode is required");
        this.workspaceMode = workspaceMode;
        LinkedHashSet<String> selectedSkills = new LinkedHashSet<>(skillIds == null ? Collections.emptyList() : skillIds);
        if (selectedSkills.size() != (skillIds == null ? 0 : skillIds.size())) {
            throw new IllegalArgumentException("Duplicate role skills are not allowed");
        }
        this.skillIds = this.tools.contains("read_skill")
                ? Collections.unmodifiableList(new ArrayList<>(selectedSkills)) : Collections.emptyList();
    }

    public CrewRole withTools(Collection<String> selectedTools) {
        return new CrewRole(id, name, colorKey, missionPrompt, new ArrayList<>(selectedTools), model,
                description, profileVersion, skillIds, workspaceMode, missionAccess);
    }

    public CrewRole withMissionAccess(CrewMissionAccess access) {
        if (missionAccess == CrewMissionAccess.READ_ONLY && access != missionAccess) {
            throw new IllegalArgumentException("A read-only mission cannot be elevated; start a new authorized mission");
        }
        return new CrewRole(id, name, colorKey, missionPrompt, tools, model,
                description, profileVersion, skillIds, workspaceMode, access);
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
