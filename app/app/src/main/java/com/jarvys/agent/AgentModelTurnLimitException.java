package com.jarvys.agent;

/** Typed identity for the memory-reflection model wrapper's existing turn budget. */
public final class AgentModelTurnLimitException extends IllegalStateException {
    public AgentModelTurnLimitException() {
        super("Agent reached its configured model-turn limit");
    }
}
