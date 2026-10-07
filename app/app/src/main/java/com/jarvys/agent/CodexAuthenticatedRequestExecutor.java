package com.jarvys.agent;

import org.json.JSONObject;

/** Shared Codex credential lookup and one-shot 401 refresh contract for chat and image requests. */
final class CodexAuthenticatedRequestExecutor {
    interface CredentialProvider {
        Object get(boolean forceRefresh, CancellationToken token);
    }

    interface Sender {
        ProviderHttp.Response send(JSONObject request, Object credentials, String sessionId,
                                   CancellationToken token);
    }

    private CodexAuthenticatedRequestExecutor() { }

    static ProviderHttp.Response execute(JSONObject request, String sessionId, CancellationToken token,
                                         String model, CredentialProvider credentials, Sender sender) {
        Object current = credentials.get(false, token);
        ProviderHttp.Response response = sender.send(request, current, sessionId, token);
        if (response.status != 401) return response;
        token.throwIfCancelled();
        try {
            current = credentials.get(true, token);
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException refreshFailure) {
            throw new CodexHttpException(response.status, response.rawBody, model, refreshFailure,
                    response.retryAfterMillis);
        }
        return sender.send(request, current, sessionId, token);
    }
}
