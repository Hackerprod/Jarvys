package com.jarvys.agent;

/** Preserves the network cause so background proactive runs can retry transport failures safely. */
public final class ProviderTransportException extends IllegalStateException {
    public ProviderTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
