package com.jarvys.agent;

/** Typed identity for the existing finite run cap; it neither retries nor changes the cap. */
public final class AgentModelTurnLimitException extends IllegalStateException {
    public AgentModelTurnLimitException() {
        super("Agent reached its configured model-turn limit");
    }
}
