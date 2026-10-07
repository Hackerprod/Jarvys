package com.jarvys.agent;

/** Internal signal used to turn a run-budget abort into a visible PARTIAL result. */
final class AgentRunTimeoutException extends java.util.concurrent.CancellationException {
    AgentRunTimeoutException() {
        super("Jarvys run timed out");
    }
}
