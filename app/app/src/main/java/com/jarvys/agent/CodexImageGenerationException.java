package com.jarvys.agent;

/** Safe, user-copyable image-generation failure; never retains the prompt or credential values. */
public final class CodexImageGenerationException extends ProviderHttpException {
    public enum Kind { SESSION, ACCESS, QUOTA, POLICY, NETWORK, INCOMPLETE, INVALID_IMAGE, API, STORAGE }

    public final Kind kind;
    public final Integer httpStatus;
    public final String errorCode;
    public final String apiMessage;
    public final long retryAfterMillis;
    public final String lastEvent;

    public CodexImageGenerationException(Kind kind, Integer httpStatus, String errorCode,
                                         String apiMessage, long retryAfterMillis, String lastEvent,
                                         Throwable cause) {
        super(apiMessage == null || apiMessage.isEmpty() ? kind.name() : apiMessage,
                httpStatus == null ? 0 : httpStatus, retryAfterMillis, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode == null ? "" : errorCode;
        this.apiMessage = apiMessage == null ? "" : apiMessage;
        this.retryAfterMillis = Math.max(0L, retryAfterMillis);
        this.lastEvent = lastEvent == null ? "" : lastEvent;
    }

    public String diagnosticCode() {
        if (!errorCode.isEmpty()) return errorCode;
        if (httpStatus != null) return "HTTP_" + httpStatus;
        return kind.name();
    }
}
