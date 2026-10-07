package com.jarvys.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Coordinates one session's summary request and append-only compaction checkpoint. */
public final class ConversationCompactor {
    public interface Listener {
        default void onStarted(String trigger) { }
        default void onCompleted(String summary, int summarizedMessages, String mode) { }
    }

    public static final class Outcome {
        public final List<ConversationTurn> context;
        public final String summary;
        public final int summarizedMessages;
        public final ConversationCompactionPolicy.Mode mode;

        Outcome(List<ConversationTurn> context, String summary, int summarizedMessages,
                ConversationCompactionPolicy.Mode mode) {
            this.context = context;
            this.summary = summary;
            this.summarizedMessages = summarizedMessages;
            this.mode = mode;
        }
    }

    private static final ConcurrentHashMap<String, ReentrantLock> SESSION_LOCKS = new ConcurrentHashMap<>();
    private static final int TOOL_RETURN_SUMMARY_LIMIT = 2_000;
    private final String sessionId;
    private final CoreAgentModel model;
    private final LocalRunStore store;

    public ConversationCompactor(String sessionId, CoreAgentModel model, LocalRunStore store) {
        this.sessionId = sessionId;
        this.model = model;
        this.store = store;
    }

    public int currentUserMessageIndex() { return store.latestUserMessageIndex(sessionId); }

    public Outcome compact(List<ConversationTurn> transcript, int contextWindow,
                          String trigger, ConversationCompactionPolicy.Mode requestedMode,
                          CancellationToken token, Listener listener) {
        token.throwIfCancelled();
        ReentrantLock sessionLock = SESSION_LOCKS.computeIfAbsent(sessionId, ignored -> new ReentrantLock());
        if (!sessionLock.tryLock()) throw new IllegalStateException("Conversation compaction is already running");
        try {
            if (listener != null) listener.onStarted(trigger);
            token.throwIfCancelled();
            ConversationCompactionPolicy.Plan plan = requestedMode == ConversationCompactionPolicy.Mode.ALL
                    ? ConversationCompactionPolicy.planAll(transcript)
                    : ConversationCompactionPolicy.planSliding(transcript, contextWindow,
                    ConversationCompactionPolicy.DEFAULT_SLIDING_PERCENTAGE);
            if (plan.summarize.isEmpty()) return null;
            String summary = summarize(plan.summarize, plan.mode, token);
            // Local backend falls back from still-full sliding context to all-mode: local-backend.ts:746-787.
            if (plan.mode == ConversationCompactionPolicy.Mode.SLIDING_WINDOW
                    && ConversationCompactionPolicy.estimateTokens(summary)
                    + ConversationCompactionPolicy.estimateTurnsTokens(plan.keep) >= contextWindow) {
                plan = ConversationCompactionPolicy.planAll(transcript);
                if (plan.summarize.isEmpty()) return null;
                summary = summarize(plan.summarize, ConversationCompactionPolicy.Mode.ALL, token);
            }
            token.throwIfCancelled();
            int firstKept = store.conversationMessageCount(sessionId);
            int currentUserIndex = currentUserMessageIndex();
            boolean currentUserRepresented = false;
            for (ConversationTurn turn : plan.keep) {
                if (turn.originalMessageIndex >= 0) {
                    if (turn.originalMessageIndex == currentUserIndex) currentUserRepresented = true;
                    firstKept = Math.min(firstKept, turn.originalMessageIndex);
                }
            }
            for (ConversationTurn turn : plan.summarize) {
                if (turn.originalMessageIndex == currentUserIndex) currentUserRepresented = true;
            }
            // The active/reused user request must remain anchored to its raw ledger index.
            if (currentUserIndex >= 0 && !currentUserRepresented) firstKept = Math.min(firstKept, currentUserIndex);
            int summarizedCount = firstKept;
            token.throwIfCancelled();
            // Summary + firstKept checkpoint mirrors local-store.ts:1157-1221,3006-3033; persist only after a complete summary.
            store.appendCompaction(sessionId, summary, firstKept, trigger,
                    plan.mode.name().toLowerCase(java.util.Locale.ROOT), summarizedCount);
            List<ConversationTurn> updated = new ArrayList<>();
            updated.add(ConversationTurn.compactionSummary(summary, summarizedCount));
            updated.addAll(plan.keep);
            Outcome outcome = new Outcome(updated, summary, summarizedCount, plan.mode);
            if (listener != null) listener.onCompleted(summary, summarizedCount, plan.mode.name().toLowerCase(java.util.Locale.ROOT));
            return outcome;
        } finally {
            sessionLock.unlock();
        }
    }

    private String summarize(List<ConversationTurn> messages, ConversationCompactionPolicy.Mode mode,
                             CancellationToken token) {
        String transcript = ConversationCompactionPolicy.formatTranscript(messages, TOOL_RETURN_SUMMARY_LIMIT);
        String prompt = mode == ConversationCompactionPolicy.Mode.SLIDING_WINDOW
                ? ConversationCompactionPolicy.SLIDING_PROMPT : ConversationCompactionPolicy.ALL_PROMPT;
        RuntimeException lastOverflow = null;
        List<String> attempts = new ArrayList<>();
        attempts.add(transcript);
        for (int maxChars : ConversationCompactionPolicy.TRANSCRIPT_RETRY_CHAR_LIMITS) {
            String bounded = ConversationCompactionPolicy.middleTruncate(transcript, maxChars);
            if (!attempts.contains(bounded)) attempts.add(bounded);
        }
        for (String attempt : attempts) {
            token.throwIfCancelled();
            try {
                ModelReply reply = model.completeSummary(prompt, attempt, token);
                token.throwIfCancelled();
                if (reply == null || reply.text.trim().isEmpty() || !reply.calls.isEmpty()) {
                    throw new IllegalStateException("The configured model returned no usable compaction summary");
                }
                return ConversationCompactionPolicy.truncateSummary(reply.text);
            } catch (RuntimeException error) {
                if (token.isCancelled()) throw error;
                if (!ConversationCompactionPolicy.isContextOverflow(error)) throw error;
                lastOverflow = error;
            }
        }
        throw lastOverflow == null ? new IllegalStateException("Could not summarize conversation") : lastOverflow;
    }
}
