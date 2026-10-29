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
    provider =
        ProviderClientRegistry.createClient(this.settings.getProvider(), secrets, this.settings, this.context);
    this.sessionId = sessionId;
  }

  CoreAgentModel(ModelProviderClient provider, String sessionId) {
    this(null, provider, sessionId);
  }

  CoreAgentModel(Context context, ModelProviderClient provider, String sessionId) {
    this.provider = provider;
    this.sessionId = sessionId;
    this.context = context == null ? null : context.getApplicationContext();
    this.settings = null;
  }

  ModelReply complete(
      String instructions,
      List<ConversationTurn> transcript,
      String prompt,
      List<ToolSpec> tools,
      CancellationToken token) {
    try {
      return provider.completeConversation(
        instructions,
        AttachmentModelContext.withoutAttachments(transcript),
        prompt,
        java.util.Collections.emptyList(),
        tools,
        sessionId,
        token);
    } catch (RuntimeException failure) {
      recordProviderFailure(failure, token);
      throw failure;
    }
  }

  ModelReply completeMainChat(
      String instructions,
      List<ConversationTurn> transcript,
      String prompt,
      List<ToolSpec> tools,
      CancellationToken token) {
    return completeMainChat(instructions, transcript, prompt, tools, token, null);
  }

  ModelReply completeMainChat(
      String instructions,
      List<ConversationTurn> transcript,
      String prompt,
      List<ToolSpec> tools,
      CancellationToken token,
      ModelProviderClient.TextDeltaListener listener) {
    if (context == null) {
      if (listener == null) return complete(instructions, transcript, prompt, tools, token);
      try {
        return provider.completeConversation(instructions,
            AttachmentModelContext.withoutAttachments(transcript), prompt,
            java.util.Collections.emptyList(), tools, sessionId, token, listener);
      } catch (RuntimeException failure) {
        recordProviderFailure(failure, token);
        throw failure;
      }
    }
    Context localized = AppLanguageRuntime.localizedContext(context);
    List<ConversationTurn> prepared =
        AttachmentModelContext.prepare(localized, sessionId, transcript, token);
    try {
      return provider.completeConversation(
        instructions,
        prepared,
        prompt,
        java.util.Collections.emptyList(),
        tools,
        sessionId,
        token,
        listener);
    } catch (RuntimeException failure) {
      recordProviderFailure(failure, token);
      if (AttachmentModelContext.hasImages(prepared))
        throw AttachmentModelContext.providerFailure(localized, failure);
      throw failure;
    }
  }

  /** Preparation is done once per attempt, before the loop estimates the actual request. */
  PreparedModelRequest prepareMainChat(String instructions, List<ConversationTurn> transcript,
      String prompt, List<ToolSpec> tools, CancellationToken token) {
    return prepareMainChat(instructions, transcript, prompt, tools, token, null, null);
  }

  PreparedModelRequest prepareMainChat(String instructions, List<ConversationTurn> transcript,
      String prompt, List<ToolSpec> tools, CancellationToken token,
      com.jarvys.agent.chatdb.InteractionProtectionSnapshot protection,
      PreparedModelRequest.MemoryBoundary memoryBoundary) {
    token.throwIfCancelled();
    List<ConversationTurn> prepared = context == null
        ? AttachmentModelContext.withoutAttachments(transcript)
        : AttachmentModelContext.prepare(AppLanguageRuntime.localizedContext(context), sessionId, transcript, token);
    return new PreparedModelRequest(instructions, prepared, prompt, tools, contextWindow(token), protection, transcript, memoryBoundary);
  }

  ModelReply completePrepared(PreparedModelRequest request, CancellationToken token,
      ModelProviderClient.TextDeltaListener listener) {
    // The final gate consumes the exact logical payload subsequently handed to the provider.
    request.requireFits(token);
    try {
      return provider.completeConversation(request.instructions, request.transcript, request.prompt,
          java.util.Collections.emptyList(), request.tools, sessionId, token, listener);
    } catch (RuntimeException failure) {
      recordProviderFailure(failure, token);
      if (context != null && AttachmentModelContext.hasImages(request.transcript))
        throw AttachmentModelContext.providerFailure(AppLanguageRuntime.localizedContext(context), failure);
      throw failure;
    }
  }

  int contextWindow(CancellationToken token) {
    if (context == null || settings == null)
      return ProviderContextWindowResolver.FALLBACK_CONTEXT_WINDOW;
    return ProviderContextWindowResolver.resolve(context, settings, token);
  }

  ModelReply completeSummary(
      String instructions, String serializedTranscript, CancellationToken token) {
    token.throwIfCancelled();
    try {
      return provider.completeConversation(
        instructions,
        java.util.Collections.emptyList(),
        serializedTranscript,
        java.util.Collections.emptyList(),
        sessionId,
        token);
    } catch (RuntimeException failure) {
      recordProviderFailure(failure, token);
      throw failure;
    }
  }

  private void recordProviderFailure(RuntimeException failure, CancellationToken token) {
    if (failure instanceof java.util.concurrent.CancellationException || token.isCancelled()) return;
    // This is a failed request, not proof that retries or the entire agent run failed.
    LocalDiagnostics.recoverable(context, LocalDiagnostics.Origin.PROVIDER, failure);
  }

  void recordEmptyReply(ModelReply reply) {
    if (context == null || reply == null || reply.diagnostics.codexShape == null) return;
    LocalDiagnostics.responseShape(context, reply.diagnostics.codexShape, reply.text.length());
  }
}
