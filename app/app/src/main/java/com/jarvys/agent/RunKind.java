package com.jarvys.agent;

/**
 * What one model run is for. The kind belongs to the run and never to the session it writes into: Central
 * hosts principal turns, proactive notices and task results at the same time, so a session cannot carry it.
 */
public enum RunKind {
    PRINCIPAL("principal"),
    TASK("task"),
    /** The proactive decision loop has no CoreAgentRuntime; it only uses this kind to request its profile context. */
    PROACTIVE("proactive"),
    SUBAGENT("subagent");

    private final String memoryKind;

    RunKind(String memoryKind) {
        this.memoryKind = memoryKind;
    }

    /** Name the personal-agent repository uses to decide which parts of the user's memory this run may read. */
    public String memoryKind() {
        return memoryKind;
    }

    /** True for the unattended kinds that run without a conversation turn of their own. */
    public boolean isBackground() {
        return this == TASK || this == PROACTIVE;
    }
}
