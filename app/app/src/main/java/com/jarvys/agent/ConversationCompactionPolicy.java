package com.jarvys.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Provider-independent compaction policy and transcript formatter. */
public final class ConversationCompactionPolicy {
    public static final int DEFAULT_CONTEXT_WINDOW = 32_768;
    public static final int MAX_RESERVE_TOKENS = 16_384;
    public static final double RESERVE_RATIO = 0.20;
    public static final double DEFAULT_SLIDING_PERCENTAGE = 0.30;
    public static final int MAX_SUMMARY_CHARS = 50_000;
    public static final String SUMMARY_TRUNCATION_SUFFIX = "... [summary truncated to fit]";
    public static final int[] TRANSCRIPT_RETRY_CHAR_LIMITS = {
            120_000, 90_000, 60_000, 40_000, 25_000, 15_000, 10_000, 6_000, 4_000, 2_000
    };

    // Prompt sections and limits follow letta-code@e961a2b3 src/backend/local/compaction.ts:61-102.
    public static final String ALL_PROMPT = ConversationCompactionPrompts.ALL;
    public static final String SLIDING_PROMPT = ConversationCompactionPrompts.SLIDING;

    public enum Mode { ALL, SLIDING_WINDOW }
    public static final class Plan {
        public final List<ConversationTurn> summarize;
        public final List<ConversationTurn> keep;
        public final int cutoffIndex;
        public final Mode mode;
        Plan(List<ConversationTurn> summarize, List<ConversationTurn> keep, int cutoffIndex, Mode mode) {
            this.summarize = Collections.unmodifiableList(new ArrayList<>(summarize));
            this.keep = Collections.unmodifiableList(new ArrayList<>(keep));
            this.cutoffIndex = cutoffIndex;
            this.mode = mode;
        }
    }

    private ConversationCompactionPolicy() { }

    // Context-pressure reserve follows letta-code@e961a2b3 src/backend/dev/provider-turn-executor.ts:241-272.
    public static int reserveTokens(int window) {
        int safeWindow = Math.max(1, window);
        return Math.min(MAX_RESERVE_TOKENS, Math.max(1, (int) Math.floor(safeWindow * RESERVE_RATIO)));
    }

    public static int pressureThreshold(int window) { return Math.max(0, window - reserveTokens(window)); }

    public static boolean shouldCompact(int contextTokens, int window) {
        return contextTokens > pressureThreshold(window);
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.getBytes(StandardCharsets.UTF_8).length / 4.0);
    }

    public static int estimateTokens(String system, String tools, List<ConversationTurn> transcript, String prompt) {
        long bytes = utf8(system) + utf8(tools) + utf8(prompt);
        if (transcript != null) for (ConversationTurn turn : transcript) {
            bytes += utf8(turn.content) + utf8(turn.thinking) + (long) turn.imageCount * 4_800L + 24;
            for (ModelReply.Call call : turn.toolCalls) bytes += utf8(call.name) + utf8(call.arguments.toString()) + 48;
            bytes += utf8(turn.toolCallId) + utf8(turn.toolName);
        }
        return (int) Math.min(Integer.MAX_VALUE, (bytes + 3) / 4);
    }

    private static long utf8(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }

    // Sliding boundaries and pending-tool handling follow src/backend/local/compaction.ts:584-667.
    public static Plan planAll(List<ConversationTurn> messages) {
        List<ConversationTurn> input = messages == null ? Collections.emptyList() : messages;
        int last = input.size() - 1;
        if (last >= 0 && hasPendingToolCall(input.get(last))) {
            return new Plan(input.subList(0, last), input.subList(last, input.size()), last, Mode.ALL);
        }
        return new Plan(input, Collections.emptyList(), input.size(), Mode.ALL);
    }

    public static Plan planSliding(List<ConversationTurn> messages, int contextWindow, double percentage) {
        List<ConversationTurn> input = messages == null ? Collections.emptyList() : messages;
        if (input.size() < 4) return planAll(input);
        double targetPercent = Double.isFinite(percentage) ? Math.min(1, Math.max(0.1, percentage)) : DEFAULT_SLIDING_PERCENTAGE;
        int maximumCutoff = input.size() - (hasPendingToolCall(input.get(input.size() - 1)) ? 2 : 1);
        int cutoff = -1;
        int retainedTokens = Integer.MAX_VALUE;
        double evictionPercent = targetPercent;
        while (evictionPercent < 1.0) {
            evictionPercent += 0.1;
            int candidateMax = Math.min((int) Math.round(evictionPercent * input.size()), input.size() - 1);
            cutoff = -1;
            for (int index = candidateMax; index >= 0; index--) {
                if (index > 0 && index < maximumCutoff && isAssistantBoundary(input.get(index))) {
                    cutoff = index;
                    break;
                }
            }
            if (cutoff < 0) continue;
            retainedTokens = estimateTurnsTokens(input.subList(cutoff, input.size()));
            if (retainedTokens < (1.0 - targetPercent) * contextWindow) break;
        }
        if (cutoff < 0 || cutoff >= maximumCutoff || evictionPercent >= 1.0 || retainedTokens >= contextWindow) return planAll(input);
        return new Plan(input.subList(0, cutoff), input.subList(cutoff, input.size()), cutoff, Mode.SLIDING_WINDOW);
    }

    private static boolean isAssistantBoundary(ConversationTurn turn) {
        return turn != null && "assistant".equals(turn.role) && turn.kind == ConversationTurn.Kind.MESSAGE;
    }

    private static boolean hasPendingToolCall(ConversationTurn turn) {
        return turn != null && turn.kind == ConversationTurn.Kind.TOOL_CALLS;
    }

    public static int estimateTurnsTokens(List<ConversationTurn> turns) {
        long bytes = 0;
        if (turns != null) for (ConversationTurn turn : turns) {
            bytes += utf8(turn.content) + utf8(turn.thinking) + (long) turn.imageCount * 4_800L + 24;
            for (ModelReply.Call call : turn.toolCalls) bytes += utf8(call.name) + utf8(call.arguments.toString()) + 48;
            bytes += utf8(turn.toolCallId) + utf8(turn.toolName);
        }
        return (int) Math.min(Integer.MAX_VALUE, (bytes + 3) / 4);
    }

    public static String formatTranscript(List<ConversationTurn> turns, int toolResultLimit) {
        StringBuilder out = new StringBuilder(" \n");
        if (turns != null) for (ConversationTurn turn : turns) {
            String role = turn.kind == ConversationTurn.Kind.TOOL_RESULT ? "tool" : turn.role;
            String text = turn.content == null ? "" : turn.content;
            if (!turn.thinking.isEmpty()) text += (text.isEmpty() ? "" : "\n\n") + "[thinking] " + turn.thinking;
            if (turn.imageCount > 0) text += (text.isEmpty() ? "" : " ")
                    + (turn.imageCount == 1 ? "[Image omitted]" : "[" + turn.imageCount + " images omitted]");
            if (turn.kind == ConversationTurn.Kind.TOOL_RESULT && text.length() > toolResultLimit) {
                text = text.substring(0, toolResultLimit) + "... [truncated " + (text.length() - toolResultLimit) + " chars]";
            }
            if (turn.kind == ConversationTurn.Kind.COMPACTION_SUMMARY) text = "[Previous conversation summary]\n" + text;
            if (!text.isEmpty()) out.append('[').append(role).append("] ").append(text);
            if (turn.kind == ConversationTurn.Kind.TOOL_CALLS) {
                if (!text.isEmpty()) out.append(' ');
                out.append("-> ");
                for (int i = 0; i < turn.toolCalls.size(); i++) {
                    if (i > 0) out.append(", ");
                    ModelReply.Call call = turn.toolCalls.get(i);
                    out.append(call.name).append('(').append(call.arguments).append(')');
                }
            } else if (turn.kind == ConversationTurn.Kind.TOOL_RESULT) {
                out.append(" {call_id=").append(turn.toolCallId).append(", name=").append(turn.toolName).append('}');
            }
            out.append('\n');
        }
        out.append(" \n. Generate the summary.");
        return out.toString();
    }

    public static String middleTruncate(String transcript, int maxChars) {
        if (maxChars <= 0 || transcript.length() <= maxChars) return transcript;
        int head = (int) (maxChars * 0.3);
        int tail = maxChars - head;
        int tailStart = transcript.length() - tail;
        return transcript.substring(0, head) + "\n[TRUNCATED: dropped " + (tailStart - head) + " middle chars due to context budget]\n"
                + transcript.substring(tailStart);
    }

    public static String truncateSummary(String summary) {
        String value = summary == null ? "" : summary.trim();
        if (value.length() <= MAX_SUMMARY_CHARS) return value;
        return value.substring(0, MAX_SUMMARY_CHARS) + SUMMARY_TRUNCATION_SUFFIX;
    }

    public static boolean isContextOverflow(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("context_length_exceeded") || message.contains("context window")
                    || message.contains("context length") || message.contains("maximum context")
                    || message.contains("too many tokens") || message.contains("prompt is too long")) return true;
        }
        return false;
    }
}
