package com.jarvys.agent.crew;

import java.util.ArrayList;
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

    public CrewRole(String id, String name, String colorKey, String missionPrompt,
                    List<String> tools, String model) {
        this.id = required(id, "id");
        this.name = required(name, "name");
        this.colorKey = required(colorKey, "colorKey");
        this.missionPrompt = required(missionPrompt, "missionPrompt");
        LinkedHashSet<String> unique = new LinkedHashSet<>(tools == null ? Collections.emptyList() : tools);
        if (unique.size() != (tools == null ? 0 : tools.size())) throw new IllegalArgumentException("Duplicate role tools are not allowed");
        this.tools = Collections.unmodifiableList(new ArrayList<>(unique));
        this.model = model == null || model.trim().isEmpty() ? null : model.trim();
    }
    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
