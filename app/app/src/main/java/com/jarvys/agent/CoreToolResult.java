package com.jarvys.agent;

/** Bounded, user-safe result returned into the ReAct transcript. */
public final class CoreToolResult {
    public final boolean success;
    public final String content;
    public final boolean completeContentRequired;
    public final String previewId;
    public final boolean finishRun;

    private CoreToolResult(boolean success, String content, boolean completeContentRequired, String previewId,
                           boolean finishRun) {
        this.success = success;
        this.content = content == null ? "" : content;
        this.completeContentRequired = completeContentRequired;
        this.previewId = previewId;
        this.finishRun = finishRun;
    }

    public static CoreToolResult success(String content) { return new CoreToolResult(true, content, false, null, false); }
    public static CoreToolResult complete(String content) { return new CoreToolResult(true, content, true, null, false); }
    public static CoreToolResult finish(String content) { return new CoreToolResult(true, content, false, null, true); }
    public static CoreToolResult preview(String content, String previewId) {
        return new CoreToolResult(true, content, false, previewId, false);
    }
    public static CoreToolResult failure(String content) { return new CoreToolResult(false, content, false, null, false); }
}
