package com.jarvys.agent;

import android.content.Context;

/** Localizes runtime-owned messages without translating model output or internal instructions. */
final class AgentResultPresentation {
    private AgentResultPresentation() { }

    static CoreAgentLoop.Result localize(Context context, CoreAgentLoop.Result result) {
        if (context == null || result.interruptionReason == CoreAgentLoop.InterruptionReason.NONE) return result;
        int resource;
        switch (result.interruptionReason) {
            case DEADLINE: resource = R.string.agent_timeout_result; break;
            case NO_PROGRESS: resource = R.string.agent_loop_result; break;
            case PROVIDER_UNAVAILABLE: resource = R.string.agent_provider_partial; break;
            default: return result;
        }
        return result.withText(AppLanguageRuntime.localizedContext(context).getString(resource));
    }

    static String timeout(Context context) {
        return AppLanguageRuntime.localizedContext(context).getString(R.string.agent_timeout_result);
    }

    /** Unstructured legacy text cannot establish whether a provider caused the failure. */
    static String failure(Context context, String ignoredDetails) {
        return AppLanguageRuntime.localizedContext(context).getString(R.string.agent_failure_generic);
    }

    /** Classify only typed evidence; never inspect exception text or expose it in the chat. */
    static String failure(Context context, Throwable failure) {
        Context localized = AppLanguageRuntime.localizedContext(context);
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < LocalDiagnostics.MAX_CAUSES && seen.add(cause); depth++) {
            if (cause instanceof AgentModelTurnLimitException)
                return localized.getString(R.string.agent_failure_turn_limit);
            if (cause instanceof ProviderHttpException) {
                int status = ((ProviderHttpException) cause).httpStatus;
                if (status >= 400 && status <= 599)
                    return localized.getString(R.string.agent_failure_http, Integer.toString(status));
            }
            if (cause instanceof ProviderTransportException)
                return localized.getString(R.string.agent_failure_network);
            try { cause = cause.getCause(); }
            catch (Throwable unavailable) { break; }
        }
        return localized.getString(R.string.agent_failure_generic);
    }
}
