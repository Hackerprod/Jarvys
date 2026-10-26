package com.jarvys.agent;

/** Bounded, user-safe result returned into the ReAct transcript. */
public final class CoreToolResult {
    public final boolean success;
    public final String content;
    public final boolean completeContentRequired;
    public final String previewId;
    public final boolean finishRun;
    /** Durable receipt returned by memory writes and checked by memory maintenance. */
    public final String persistenceEvidence;
    /** Optional typed local effect evidence; independent of the presentation success flag. */
    public final EffectInvocation.Receipt effectReceipt;
    public final EffectInvocation.AdministrativeReceipt administrativeReceipt;

    private CoreToolResult(boolean success, String content, boolean completeContentRequired, String previewId,
                           boolean finishRun) {
        this(success, content, completeContentRequired, previewId, finishRun, null);
    }

    private CoreToolResult(boolean success, String content, boolean completeContentRequired, String previewId,
                           boolean finishRun, String persistenceEvidence) {
        this(success, content, completeContentRequired, previewId, finishRun, persistenceEvidence, null);
    }

    private CoreToolResult(boolean success, String content, boolean completeContentRequired, String previewId,
                           boolean finishRun, String persistenceEvidence, EffectInvocation.Receipt effectReceipt) {
        this(success, content, completeContentRequired, previewId, finishRun, persistenceEvidence, effectReceipt, null);
    }

    private CoreToolResult(boolean success, String content, boolean completeContentRequired, String previewId,
                           boolean finishRun, String persistenceEvidence, EffectInvocation.Receipt effectReceipt,
                           EffectInvocation.AdministrativeReceipt administrativeReceipt) {
        this.administrativeReceipt = administrativeReceipt;
        this.effectReceipt = effectReceipt;
        this.persistenceEvidence = persistenceEvidence;
        this.success = success;
        this.content = content == null ? "" : content;
        this.completeContentRequired = completeContentRequired;
        this.previewId = previewId;
        this.finishRun = finishRun;
    }

    /** Preserve all execution metadata when a wrapper redacts or reformats presentation content. */
    public CoreToolResult withContent(String content) {
        return new CoreToolResult(success, content, completeContentRequired, previewId, finishRun,
                persistenceEvidence, effectReceipt, administrativeReceipt);
    }

    public CoreToolResult withEffectReceipt(EffectInvocation.Receipt receipt) {
        return new CoreToolResult(success, content, completeContentRequired, previewId, finishRun,
                persistenceEvidence, receipt, administrativeReceipt);
    }

    public CoreToolResult withAdministrativeReceipt(EffectInvocation.AdministrativeReceipt receipt) {
        return new CoreToolResult(success, content, completeContentRequired, previewId, finishRun,
                persistenceEvidence, effectReceipt, receipt);
    }

    public static CoreToolResult success(String content) { return new CoreToolResult(true, content, false, null, false); }
    public static CoreToolResult complete(String content) { return new CoreToolResult(true, content, true, null, false); }
    public static CoreToolResult finish(String content) { return new CoreToolResult(true, content, false, null, true); }
    public static CoreToolResult preview(String content, String previewId) {
        return new CoreToolResult(true, content, false, previewId, false);
    }
    public static CoreToolResult failure(String content) { return new CoreToolResult(false, content, false, null, false); }
    public static CoreToolResult persisted(String content, String evidence) {
        if (evidence == null || evidence.trim().isEmpty()) throw new IllegalArgumentException("Persistence evidence is required");
        return new CoreToolResult(true, content, false, null, false, evidence);
    }
}
