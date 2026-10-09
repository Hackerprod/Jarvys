package com.jarvys.agent.crew;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * UI/persistence projection of one bot. Deliberately contains no token counters.
 */
public final class CrewBotSnapshot {
    public final String id;
    public final String roleId;
    public final String roleName;
    public final String name;
    public final String colorKey;
    public final String mission;
    public final String status;
    public final String error;
    public final String result;
    public final String waitingReason;
    public final List<String> tools;
    public final long startedAtMillis;
    public final long finishedAtMillis;
    public final boolean canResume;
    public final boolean resumeRequired;
    public final String recoveryNote;
    public final String phase;
    public final String lastProgress;
    public final long lastProgressAtMillis;

    public CrewBotSnapshot(String id, String roleId, String roleName, String name, String colorKey, String mission, String status, String error, String result, String waitingReason, List<String> tools, long startedAtMillis, long finishedAtMillis) {
        this(id, roleId, roleName, name, colorKey, mission, status, error, result, waitingReason, tools, startedAtMillis, finishedAtMillis, false, "");
    }

    public CrewBotSnapshot(String id, String roleId, String roleName, String name, String colorKey, String mission, String status, String error, String result, String waitingReason, List<String> tools, long startedAtMillis, long finishedAtMillis, boolean canResume, String recoveryNote) {
        this(id, roleId, roleName, name, colorKey, mission, status, error, result, waitingReason, tools, startedAtMillis, finishedAtMillis, canResume, recoveryNote, false);
    }

    public CrewBotSnapshot(String id, String roleId, String roleName, String name, String colorKey, String mission, String status, String error, String result, String waitingReason, List<String> tools, long startedAtMillis, long finishedAtMillis, boolean canResume, String recoveryNote, boolean resumeRequired) {
        this(id, roleId, roleName, name, colorKey, mission, status, error, result, waitingReason, tools, startedAtMillis, finishedAtMillis, canResume, recoveryNote, resumeRequired, "", "", 0L);
    }

    public CrewBotSnapshot(String id, String roleId, String roleName, String name, String colorKey, String mission, String status, String error, String result, String waitingReason, List<String> tools, long startedAtMillis, long finishedAtMillis, boolean canResume, String recoveryNote, boolean resumeRequired, String phase, String lastProgress, long lastProgressAtMillis) {
        this.phase = "RUNNING".equals(status) ? safeProgress(phase) : "";
        this.lastProgress = safeProgress(lastProgress);
        this.lastProgressAtMillis = Math.max(0L, lastProgressAtMillis);
        this.canResume = canResume;
        this.resumeRequired = resumeRequired;
        this.recoveryNote = recoveryNote == null ? "" : recoveryNote;
        this.id = id;
        this.roleId = roleId;
        this.roleName = roleName;
        this.name = name;
        this.colorKey = colorKey;
        this.mission = mission;
        this.status = status;
        this.error = error == null ? "" : error;
        this.result = result == null ? "" : result;
        this.waitingReason = waitingReason == null ? "" : waitingReason;
        this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
        this.startedAtMillis = startedAtMillis;
        this.finishedAtMillis = finishedAtMillis;
    }

    private static String safeProgress(String value) {
        if (value == null) return "";
        switch (value) {
            case "model_wait": case "model_response": case "compacting": case "compacted":
            case "compaction_error": case "tool_call": case "tool_result": case "tool_error": return value;
            default: return "";
        }
    }

    public boolean active() {
        return "QUEUED".equals(status) || "RUNNING".equals(status) || "WAITING".equals(status);
    }

    public CrewBotSnapshot interrupted() {
        if (!active()) return this;
        return new CrewBotSnapshot(id, roleId, roleName, name, colorKey, mission, "INTERRUPTED", "", result, "", tools, startedAtMillis, System.currentTimeMillis(), false, recoveryNote, true, "", lastProgress, lastProgressAtMillis);
    }
}
