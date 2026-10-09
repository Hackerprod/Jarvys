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

    static String failure(Context context, String details) {
        Context localized = AppLanguageRuntime.localizedContext(context);
        String value = details == null ? "" : details;
        java.util.regex.Matcher http = java.util.regex.Pattern.compile("HTTP (4\\d\\d|5\\d\\d)").matcher(value);
        if (http.find()) return localized.getString(R.string.agent_failure_http, http.group(1));
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("network") || lower.contains("connect")) return localized.getString(R.string.agent_failure_network);
        return localized.getString(R.string.agent_failure_generic);
    }
}
