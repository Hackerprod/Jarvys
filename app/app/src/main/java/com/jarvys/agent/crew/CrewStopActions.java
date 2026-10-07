package com.jarvys.agent.crew;

/** Shared stop-all action used by Crew UI and service-level stop controls. */
public final class CrewStopActions {
    private CrewStopActions() { }

    public static void stopAll(Runnable stopCaptainRun, CrewManager manager) {
        if (stopCaptainRun != null) stopCaptainRun.run();
        if (manager != null) manager.stopAll();
    }
}
