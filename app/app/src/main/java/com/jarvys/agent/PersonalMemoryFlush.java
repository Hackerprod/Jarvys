package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.chatdb.ChatDatabaseBridge;
import com.jarvys.agent.chatdb.PersonalAgentPromptContext;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Silent maintenance pass over a private copy of genuine user messages. */
final class PersonalMemoryFlush {
  private static final Pattern[] SECRET_PATTERNS = new Pattern[] {
      Pattern.compile("(?is)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----.*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
      Pattern.compile("(?i)\\b(?:AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|sk-(?:proj-)?[A-Za-z0-9_-]{20,})\\b"),
      Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{16,}|\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
      Pattern.compile("(?i)\\b(?:password|passwd|api[_ -]?key|access[_ -]?token|refresh[_ -]?token|secret|credential|authorization)\\s*[:=]\\s*(?!<redacted>|redacted\\b|example\\b|placeholder\\b|your[_ -]?)([^\\s,;]{4,})")
  };

  private final Context context;
  private final String conversationId;
  private final String sessionKind;
  private final CoreAgentModel model;

  PersonalMemoryFlush(Context context, String conversationId, String sessionKind, CoreAgentModel model) {
    this.context = context.getApplicationContext();
    this.conversationId = conversationId;
    this.sessionKind = sessionKind;
    this.model = model;
  }

  /** Runs before compaction. A failed maintenance pass is reported and does not block summarization. */
  boolean run(List<ConversationTurn> transcript, CancellationToken token) {
    try {
      if (!eligible()) return false;
      ChatDatabaseBridge bridge = ChatDatabaseBridge.getInstance(context);
      if (!bridge.personalAgentNeedsMemoryFlush(conversationId)) return false;
      if (!new EpisodeNarrator(context, conversationId, model).narratePending(token)) return false;
      return runPass(transcript, token, bridge, true, false);
    } catch (RuntimeException failure) {
      if (token.isCancelled()) throw failure;
      AgentErrorReporter.reportBackgroundFailure(context, failure);
      return false;
    }
  }

  /** Runs through a durable WorkManager job. A quiet no-op is a successful job. */
  boolean runOffline(List<ConversationTurn> transcript, CancellationToken token, boolean snapshot) {
    try {
      if (!eligible()) return true;
      ChatDatabaseBridge bridge = ChatDatabaseBridge.getInstance(context);
      return runPass(transcript, token, bridge, false, snapshot);
    } catch (RuntimeException failure) {
      if (token.isCancelled()) throw failure;
      AgentErrorReporter.reportBackgroundFailure(context, failure);
      return false;
    }
  }

  private boolean eligible() {
    if (!MessageReactionTool.isOrdinaryChat(conversationId)) return false;
    return context.getSharedPreferences(MemoryConstants.PREFERENCES_FILE, Context.MODE_PRIVATE)
        .getBoolean(MemoryConstants.ENABLED_KEY, true);
  }

  private boolean runPass(List<ConversationTurn> transcript, CancellationToken token,
                          ChatDatabaseBridge bridge, boolean compactionFlush, boolean snapshot) {
    List<ConversationTurn> privateView = privateUserMessages(transcript);
    List<CoreTool> handlers = PersonalMemoryTools.createForExtraction(context, conversationId);
    CoreToolRegistry registry = new CoreToolRegistry(handlers);
    boolean sawToolFailure = false;
    boolean sawSuccessfulMemoryWrite = false;
    while (true) {
      token.throwIfCancelled();
      PersonalAgentPromptContext promptContext = bridge.personalAgentPromptContext(conversationId, true);
      String maintenancePrompt = compactionFlush
          ? promptContext.getCompactionFlushPrompt()
          : promptContext.getOfflineExtractionPrompt() + (snapshot ? "\n" + promptContext.getSnapshotPrompt() : "");
      ModelReply reply = model.complete(
          promptContext.getRules() + "\n\n" + promptContext.getSoul(),
          privateView,
          maintenancePrompt,
          registry.declarations(),
          token);
      token.throwIfCancelled();
      if (reply == null) throw new IllegalStateException("Memory maintenance model returned no response");
      if (reply.calls.isEmpty()) {
        boolean complete = !sawToolFailure
            && (sawSuccessfulMemoryWrite || "NO_REPLY".equalsIgnoreCase(reply.text.trim()));
        if (complete && compactionFlush) {
          bridge.personalAgentMarkMemoryFlushCompleted(conversationId, sessionKind);
        } else if (!complete && compactionFlush) {
          AgentErrorReporter.reportBackgroundFailure(context,
              new IllegalStateException("memory_flush_missing_write_or_noop_evidence"));
        }
        return complete;
      }
      privateView.add(ConversationTurn.toolCalls(reply.text, reply.calls, reply.providerOutputItems));
      for (ModelReply.Call call : reply.calls) {
        token.throwIfCancelled();
        CoreTool tool = registry.tool(call.name);
        CoreToolResult result;
        if (tool == null) {
          result = CoreToolResult.failure("Memory maintenance permits only declared profile and memory tools.");
        } else {
          ToolArgumentValidator.Result validated = ToolArgumentValidator.coerceAndValidate(
              call.arguments, tool.declaration().jsonSchema());
          result = validated.error == null
              ? tool.execute(validated.arguments, token)
              : CoreToolResult.failure(validated.error);
        }
        if (!result.success) sawToolFailure = true;
        if (result.success && tool != null
            && tool.accessClassification() == CoreTool.AccessClassification.WRITE
            && tool.persistenceEffect() == CoreTool.PersistenceEffect.MEMORY
            && result.persistenceEvidence != null && !result.persistenceEvidence.trim().isEmpty()) {
          sawSuccessfulMemoryWrite = true;
        }
        privateView.add(ConversationTurn.toolResult(call.id, call.name,
            (result.success ? "Success: " : "Tool error: ") + result.content));
      }
    }
  }

  private static List<ConversationTurn> privateUserMessages(List<ConversationTurn> transcript) {
    List<ConversationTurn> result = new ArrayList<>();
    if (transcript != null) for (ConversationTurn turn : transcript) {
      if (turn == null || turn.kind != ConversationTurn.Kind.MESSAGE || !"user".equals(turn.role)) continue;
      if (turn.incomingMetadata != null && turn.incomingMetadata.untrusted) continue;
      String safe = redactSecrets(turn.content);
      if (!safe.isBlank()) result.add(new ConversationTurn("user", safe, turn.originalMessageIndex));
    }
    return result;
  }

  static String redactSecrets(String value) {
    String result = value == null ? "" : value;
    for (Pattern pattern : SECRET_PATTERNS) result = pattern.matcher(result).replaceAll("[secret redacted]");
    return result;
  }
}
