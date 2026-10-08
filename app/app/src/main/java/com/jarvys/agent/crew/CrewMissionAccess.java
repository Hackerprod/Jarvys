package com.jarvys.agent.crew;

import java.util.Arrays;
import java.util.List;

/** Trusted mission restriction. It can remove capabilities, never approve or add them. */
public enum CrewMissionAccess {
    STANDARD("standard"), READ_ONLY("read_only");

    public final String value;
    private static final List<String> READ_ONLY_TOOLS = Arrays.asList(
            "ls", "read", "coding_grep", "coding_glob", "read_skill", "project_environment_status",
            "board_read", "msg_send", "ask_chief", "report_done");

    CrewMissionAccess(String value) { this.value = value; }

    public static CrewMissionAccess parse(String value) {
        if (value == null) return STANDARD; // Old checkpoints predate an explicit restriction.
        for (CrewMissionAccess mode : values()) if (mode.value.equals(value)) return mode;
        throw new IllegalArgumentException("mission_access must be standard or read_only");
    }

    public boolean permits(String name) { return this == STANDARD || READ_ONLY_TOOLS.contains(name); }
}
