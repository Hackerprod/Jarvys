package com.jarvys.agent.chatdb;

import com.jarvys.agent.EffectInvocation;
import com.jarvys.agent.coding.CodingCancellation;
import com.jarvys.agent.connectors.ConnectionStateCoordinator;
import java.util.Objects;

/** Post-transaction identity only. No decoded metadata can construct an INTENT or RESULT. */
public final class CodingCancellationCheckpoint {
    public enum Stage { INTENT, RESULT }
    private final Stage stage;
    private final CodingCancellation.Request request;
    private final CodingCancellation.Result result;
    private final EffectInvocation.Admission admission;
    private final ConnectionStateCoordinator.DestinationEvidence lifecycle;
    private CodingCancellationCheckpoint(CodingCancellation.Request request, CodingCancellation.Result result,
            EffectInvocation.Admission admission, ConnectionStateCoordinator.DestinationEvidence lifecycle) {
        this.request = Objects.requireNonNull(request); this.result = result;
        this.stage = result == null ? Stage.INTENT : Stage.RESULT;
        this.admission = Objects.requireNonNull(admission); this.lifecycle = Objects.requireNonNull(lifecycle);
        if (result != null && result.request() != request) throw new IllegalArgumentException("Cancellation result belongs to another request");
    }
    static CodingCancellationCheckpoint confirmed(CodingCancellation.Request request, CodingCancellation.Result result,
            EffectInvocation.Admission admission, ConnectionStateCoordinator.DestinationEvidence lifecycle) {
        return new CodingCancellationCheckpoint(request, result, admission, lifecycle);
    }
    public Stage stage() { return stage; }
    public CodingCancellation.Request request() { return request; }
    public CodingCancellation.Result result() { return result; }
    public EffectInvocation.Admission admission() { return admission; }
    public void requireIntent(CodingCancellation.Request expected) {
        if (stage != Stage.INTENT || request != expected) throw new IllegalStateException("Exact cancellation intent is required");
    }
    public void requireResult(CodingCancellation.Result expected) {
        if (stage != Stage.RESULT || result != expected || request != expected.request())
            throw new IllegalStateException("Exact cancellation result is required");
    }
    void requireCurrentProjection() {
        if (!lifecycle.fenceIdentity().isHeldByCurrentThread() || !lifecycle.isCurrentPure())
            throw new IllegalStateException("Cancellation destination is no longer current");
    }
    ConnectionStateCoordinator.DestinationEvidence lifecycle() { return lifecycle; }
}
