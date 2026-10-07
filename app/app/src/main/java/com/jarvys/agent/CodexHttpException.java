package com.jarvys.agent;

/** Full, unmodified HTTP error detail from the OpenAI Codex Responses endpoint. */
public final class CodexHttpException extends ProviderHttpException {
    public final int statusCode;
    public final String responseBody;
    public final String model;

    public CodexHttpException(int statusCode, String responseBody, String model) {
        this(statusCode, responseBody, model, null);
    }

    public CodexHttpException(int statusCode, String responseBody, String model, Throwable cause) {
        this(statusCode, responseBody, model, cause, 0L);
    }

    public CodexHttpException(int statusCode, String responseBody, String model,
                              Throwable cause, long retryAfterMillis) {
        super("OpenAI Codex Responses request failed with HTTP " + statusCode + ": " + responseBody,
                statusCode, retryAfterMillis, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
        this.model = model;
    }
}
