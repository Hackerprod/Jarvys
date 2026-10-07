package com.jarvys.agent;

/** Structured provider 429 so Crew workers can wait and retry without consuming tool/model turns. */
public final class ProviderRateLimitException extends ProviderHttpException {
    public ProviderRateLimitException(String message, long retryAfterMillis) {
        super(message, 429, retryAfterMillis, null);
    }
}
