package com.jarvys.agent.crew;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Versioned, immutable projection used by the live UI and the per-chat append-only ledger.
 */
public final class CrewMissionSnapshot {
    public static final int SCHEMA_VERSION = 3;
    public final String missionId;
    public final String conversationId;
    public final String processId;
    public final String title;
    public final String originalInstructions;
    public final String titleSource;
    public final String status;
    public final String synthesis;
    public final long startedAtMillis;
    public final long finishedAtMillis;
    public final List<CrewBotSnapshot> bots;
    public final List<CrewMessage> messages;

    public CrewMissionSnapshot(String missionId, String conversationId, String processId, String title, String status, String synthesis, long startedAtMillis, long finishedAtMillis, List<CrewBotSnapshot> bots, List<CrewMessage> messages) {
        this(missionId, conversationId, processId, title, title, CrewMissionTitle.LEGACY,
                status, synthesis, startedAtMillis, finishedAtMillis, bots, messages);
    }

    public CrewMissionSnapshot(String missionId, String conversationId, String processId, String title,
            String originalInstructions, String titleSource, String status, String synthesis,
            long startedAtMillis, long finishedAtMillis, List<CrewBotSnapshot> bots, List<CrewMessage> messages) {
        this.missionId = missionId;
        this.conversationId = conversationId;
        this.processId = processId;
        CrewMissionTitle presentation = CrewMissionTitle.stored(title, titleSource);
        this.title = presentation.title;
        this.titleSource = presentation.source;
        this.originalInstructions = originalInstructions == null ? "" : originalInstructions;
        this.status = status == null ? "RUNNING" : status;
        this.synthesis = synthesis == null ? "" : synthesis;
        this.startedAtMillis = startedAtMillis;
        this.finishedAtMillis = finishedAtMillis;
        this.bots = Collections.unmodifiableList(new ArrayList<>(bots));
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
    }

    public int messageCount() {
        return CrewActivityTimeline.project(messages,bots).size();
    }

    public boolean active() {
        return "RUNNING".equals(status) || bots.stream().anyMatch(CrewBotSnapshot::active);
    }

    public CrewMissionSnapshot interrupted() {
        List<CrewBotSnapshot> recovered = new ArrayList<>();
        boolean changed = false;
        for (CrewBotSnapshot bot : bots) {
            CrewBotSnapshot value = bot.interrupted();
            recovered.add(value);
            changed |= value != bot;
        }
        String recoveredStatus = active() ? "INTERRUPTED" : status;
        changed |= !recoveredStatus.equals(status);
        return changed ? new CrewMissionSnapshot(missionId, conversationId, CrewProcessIdentity.ID, title,
                originalInstructions, titleSource, recoveredStatus, synthesis, startedAtMillis,
                System.currentTimeMillis(), recovered, messages) : this;
    }

    public JSONObject toJson() {
        try {
            JSONObject row = new JSONObject();
            row.put("type", "crew_snapshot");
            row.put("crewSchemaVersion", SCHEMA_VERSION);
            row.put("missionId", missionId);
            row.put("conversationId", conversationId);
            row.put("processId", processId);
            row.put("title", title);
            row.put("originalInstructions", originalInstructions);
            row.put("titleSource", titleSource);
            row.put("status", status);
            row.put("synthesis", synthesis);
            row.put("startedAtMillis", startedAtMillis);
            row.put("finishedAtMillis", finishedAtMillis);
            JSONArray botRows = new JSONArray();
            for (CrewBotSnapshot bot : bots) {
                JSONObject value = new JSONObject();
                value.put("id", bot.id);
                value.put("roleId", bot.roleId);
                value.put("roleName", bot.roleName);
                value.put("name", bot.name);
                value.put("colorKey", bot.colorKey);
                value.put("mission", bot.mission);
                value.put("status", bot.status);
                value.put("error", bot.error);
                value.put("result", bot.result);
                value.put("waitingReason", bot.waitingReason);
                value.put("phase", bot.phase);
                value.put("lastProgress", bot.lastProgress);
                value.put("lastProgressAtMillis", bot.lastProgressAtMillis);
                value.put("tools", new JSONArray(bot.tools));
                value.put("resumeRequired", bot.resumeRequired);
                value.put("canResume", bot.canResume);
                value.put("recoveryNote", bot.recoveryNote);
                value.put("startedAtMillis", bot.startedAtMillis);
                value.put("finishedAtMillis", bot.finishedAtMillis);
                botRows.put(value);
            }
            row.put("bots", botRows);
            JSONArray messageRows = new JSONArray();
            for (CrewMessage message : messages) {
                messageRows.put(message.toJson());
            }
            row.put("messages", messageRows);
            return row;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not serialize Crew mission", failure);
        }
    }

    public static CrewMissionSnapshot fromJson(JSONObject row) {
        int version = row.optInt("crewSchemaVersion", 0);
        if (version != 1 && version != 2 && version != SCHEMA_VERSION) return null;
        try {
            List<CrewBotSnapshot> bots = new ArrayList<>();
            JSONArray botRows = row.optJSONArray("bots");
            if (botRows != null) for (int index = 0; index < botRows.length(); index++) {
                JSONObject value = botRows.optJSONObject(index);
                if (value == null) continue;
                JSONArray toolRows = value.optJSONArray("tools");
                List<String> tools = new ArrayList<>();
                if (toolRows != null) for (int tool = 0; tool < toolRows.length(); tool++) tools.add(toolRows.optString(tool));
                bots.add(new CrewBotSnapshot(value.optString("id"), value.optString("roleId"), value.optString("roleName"), value.optString("name"), value.optString("colorKey"), value.optString("mission"), value.optString("status"), value.optString("error"), value.optString("result"), value.optString("waitingReason"), tools, value.optLong("startedAtMillis"), value.optLong("finishedAtMillis"), value.optBoolean("canResume"), value.optString("recoveryNote"), value.optBoolean("resumeRequired"), value.optString("phase"), value.optString("lastProgress"), value.optLong("lastProgressAtMillis")));
            }
            List<CrewMessage> messages = new ArrayList<>();
            JSONArray messageRows = row.optJSONArray("messages");
            if (messageRows != null) for (int index = 0; index < messageRows.length(); index++) {
                JSONObject value = messageRows.optJSONObject(index);
                if (value == null) continue;
                messages.add(CrewMessage.fromJson(value));
            }
            String rawTitle = row.opt("title") instanceof String ? row.optString("title") : "";
            String instructions = version == 1 ? rawTitle
                    : row.opt("originalInstructions") instanceof String ? row.optString("originalInstructions") : "";
            return new CrewMissionSnapshot(row.optString("missionId"), row.optString("conversationId"), row.optString("processId"),
                    rawTitle, instructions, version == 1 ? CrewMissionTitle.LEGACY : row.optString("titleSource"),
                    row.optString("status"), row.optString("synthesis"), row.optLong("startedAtMillis"), row.optLong("finishedAtMillis"), bots, messages);
        } catch (Exception failure) {
            return null;
        }
    }
}
