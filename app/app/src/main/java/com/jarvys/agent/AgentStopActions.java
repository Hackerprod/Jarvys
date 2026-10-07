package com.jarvys.agent;

/** Centralized ordering for the foreground notification's global STOP command. */
public final class AgentStopActions {
    private AgentStopActions() { }

    public static void stopAll(Runnable stopCaptainRun, Runnable cancelReflections, Runnable stopCrewManagers) {
        if (stopCaptainRun != null) stopCaptainRun.run();
        if (cancelReflections != null) cancelReflections.run();
        if (stopCrewManagers != null) stopCrewManagers.run();
    }
}
