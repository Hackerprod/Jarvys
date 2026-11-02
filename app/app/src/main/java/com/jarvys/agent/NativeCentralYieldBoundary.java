package com.jarvys.agent;

/** Native service handoff only. Models and stored request DTOs cannot implement admission. */
interface NativeCentralYieldBoundary {
    CentralSubmissionAdmission.LivePermit pendingNewTurn();
    void requireCurrent(CentralSubmissionAdmission.LivePermit request);
    /** Rechecks the exact physical old owner and selected request under the service admission lock. */
    boolean closeInputForYield(CentralSubmissionAdmission.LivePermit request);
    void providerAttemptStarted(ProviderAttemptLease attempt);
    void providerAttemptFinished(ProviderAttemptLease attempt);
}
