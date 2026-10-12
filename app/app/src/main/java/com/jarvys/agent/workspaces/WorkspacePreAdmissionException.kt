package com.jarvys.agent.workspaces

/** Only thrown before this assignment crosses its durable STARTED boundary. */
internal class WorkspacePreAdmissionException(val reason: Reason, cause: Throwable? = null) :
    IllegalStateException(reason.safeMessage, cause) {
    enum class Reason(val safeMessage: String) {
        CREW_DISABLED("Crew is OFF in the saved settings. No worker was started for this assignment. " +
            "Ask the user to enable Crew before building; do not change that setting automatically. " +
            "Read the existing assignment before continuing. In the original user turn, keep the same key and payload; " +
            "a later user turn needs a new assignment key only after confirming this assignment never started."),
        WORKER_CONFIGURATION_REJECTED("Worker admission checks rejected this assignment before starting a worker. " +
            "Check that the requested role is enabled and its selected tools and mission access are supported. " +
            "Read current workspace state. Within the original user turn, retry the same key only with the same payload " +
            "after fixing the prerequisite. A later user turn or corrected payload needs a new key only after confirming " +
            "this assignment never started. Do not repeat unchanged rejected calls."),
    }
}
