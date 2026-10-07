package com.jarvys.agent;

import android.content.Context;

import com.jarvys.agent.providers.ProviderClientRegistry;

import java.util.List;

/** Provider boundary used only by CoreAgentLoop. */
public final class CoreAgentModel {
    private final ModelProviderClient provider;
    private final String sessionId;
    private final Context context;
    private final ProviderSettings settings;

    public CoreAgentModel(Context context, String sessionId) {
        this.context = context == null ? null : context.getApplicationContext();
        this.settings = context == null ? null : new ProviderSettings(context);
        SecretStore secrets = SecretStore.get(context);
        provider = ProviderClientRegistry.createClient(this.settings.getProvider(), secrets, this.settings);
        this.sessionId = sessionId;
    }

    CoreAgentModel(ModelProviderClient provider, String sessionId) {
        this.provider = provider;
        this.sessionId = sessionId;
        this.context = null;
        this.settings = null;
    }

    ModelReply complete(String instructions, List<ConversationTurn> transcript, String prompt,
                        List<ToolSpec> tools, CancellationToken token) {
        return provider.completeConversation(instructions, transcript, prompt,
                java.util.Collections.emptyList(), tools, sessionId, token);
    }

    int contextWindow(CancellationToken token) {
        if (context == null || settings == null) return ProviderContextWindowResolver.FALLBACK_CONTEXT_WINDOW;
        return ProviderContextWindowResolver.resolve(context, settings, token);
    }

    ModelReply completeSummary(String instructions, String serializedTranscript, CancellationToken token) {
        token.throwIfCancelled();
        return provider.completeConversation(instructions, java.util.Collections.emptyList(), serializedTranscript,
                java.util.Collections.emptyList(), sessionId, token);
    }
}
