package com.jarvys.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Optional provider evidence. It does not grant authority or change legacy reply behavior. */
public final class ResponseDiagnostics {
  public enum Completion { SUCCEEDED, INCOMPLETE, FILTERED, FAILED, CANCELLED, UNKNOWN }
  public enum Origin { CHAT_COMPLETION_CHOICE, CODEX_OUTPUT_ITEM }
  public enum ItemFinality { COMPLETED, INCOMPLETE, UNKNOWN }
  public enum TotalSource { REPORTED, CALCULATED, UNAVAILABLE }

  public static final class AssistantItem {
    /** Identity within this diagnostic only; never a fabricated provider ID or durable message ID. */
    public final String key;
    public final String providerId;
    public final Integer outputIndex;
    public final String text;
    public final String status;
    public final String phase;
    public final Origin origin;
    public final ItemFinality finality;

    AssistantItem(String key, String providerId, Integer outputIndex, String text, String status,
        String phase, Origin origin, ItemFinality finality) {
      this.key = key;
      this.providerId = providerId;
      this.outputIndex = outputIndex;
      this.text = text;
      this.status = status;
      this.phase = phase;
      this.origin = origin;
      this.finality = finality;
    }
  }

  public static final class Usage {
    public final Long inputTokens;
    public final Long outputTokens;
    public final Long totalTokens;
    /** A subset of inputTokens, not an additional contribution to totalTokens. */
    public final Long cachedInputTokens;
    /** A subset of outputTokens, not an additional contribution to totalTokens. */
    public final Long reasoningTokens;
    public final TotalSource totalSource;
    public final boolean inconsistent;

    Usage(Long input, Long output, Long total, Long cached, Long reasoning,
        TotalSource totalSource, boolean inconsistent) {
      this.inputTokens = input;
      this.outputTokens = output;
      this.totalTokens = total;
      this.cachedInputTokens = cached;
      this.reasoningTokens = reasoning;
      this.totalSource = totalSource;
      this.inconsistent = inconsistent;
    }
  }

  public final Completion completion;
  /** Exact provider reason/code when present; null means unavailable. */
  public final String finishReason;
  public final String responseId;
  public final List<AssistantItem> assistantItems;
  public final String finalAssistantItemKey;
  /** Null when usage is absent. All counters except a calculated total are provider-reported. */
  public final Usage usage;
  public final boolean hasToolCalls;
  /** Generic diagnostics only, never raw payloads or exception text. Nothing is logged. */
  public final List<String> warningCodes;

  ResponseDiagnostics(Completion completion, String finishReason, String responseId,
      List<AssistantItem> items, String finalKey, Usage usage, boolean hasToolCalls,
      List<String> warningCodes) {
    this.completion = completion;
    this.finishReason = finishReason;
    this.responseId = responseId;
    this.assistantItems = Collections.unmodifiableList(new ArrayList<>(items));
    this.finalAssistantItemKey = finalKey;
    this.usage = usage;
    this.hasToolCalls = hasToolCalls;
    this.warningCodes = Collections.unmodifiableList(new ArrayList<>(warningCodes));
  }

  public static ResponseDiagnostics unknown() {
    return new ResponseDiagnostics(Completion.UNKNOWN, null, null, Collections.emptyList(), null,
        null, false, Collections.emptyList());
  }

  public static ResponseDiagnostics fromChatCompletions(String body) {
    return ResponseDiagnosticsParser.chatCompletions(body);
  }

  /** Accepts the received SSE body or a complete Responses JSON object, never inferred EOF success. */
  public static ResponseDiagnostics fromCodex(String rawBody) {
    return ResponseDiagnosticsParser.codex(rawBody);
  }

  /** Local cancellation wins over late provider success, while preserving available evidence. */
  public static ResponseDiagnostics cancelled(ResponseDiagnostics partial) {
    ResponseDiagnostics value = partial == null ? unknown() : partial;
    return new ResponseDiagnostics(Completion.CANCELLED, value.finishReason, value.responseId,
        value.assistantItems, null, value.usage, value.hasToolCalls, value.warningCodes);
  }

  public boolean isSuccessfulFinalAnswer() {
    if (completion != Completion.SUCCEEDED || hasToolCalls || finalAssistantItemKey == null)
      return false;
    for (AssistantItem item : assistantItems) {
      if (finalAssistantItemKey.equals(item.key))
        return item.finality == ItemFinality.COMPLETED && !item.text.trim().isEmpty();
    }
    return false;
  }
}
