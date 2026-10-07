package com.jarvys.agent;

/** Structured provider HTTP failure; existing provider-specific exceptions retain their types. */
public class ProviderHttpException extends IllegalStateException {
    public final int httpStatus;
    public final long retryAfterMillis;

    public ProviderHttpException(String message, int httpStatus, long retryAfterMillis, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.retryAfterMillis = Math.max(0L, retryAfterMillis);
    }

    public boolean isRateLimit() { return httpStatus == 429; }
}
