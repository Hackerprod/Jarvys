package com.jarvys.agent;

/** Native service handoff only. Models and stored request DTOs cannot implement admission. */
interface NativeCentralYieldBoundary {
    /** True only for the service owner bound to actual ordinary input admission, never tree authority. */
    default boolean ordinaryRun() { return false; }
    CentralSubmissionAdmission.LivePermit pendingNewTurn();
    void requireCurrent(CentralSubmissionAdmission.LivePermit request);
    /** Rechecks the exact physical old owner and selected request under the service admission lock. */
    boolean closeInputForYield(CentralSubmissionAdmission.LivePermit request);
    void providerAttemptStarted(ProviderAttemptLease attempt);
    void providerAttemptFinished(ProviderAttemptLease attempt);
}
