package com.jarvys.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates one session's summary request and append-only compaction checkpoint.
 */
public final class ConversationCompactor {

    public interface Listener {

        default void onStarted(String trigger) {
        }

        default void onCompleted(String summary, int summarizedMessages, String mode) {
        }
    }

    public static final class Outcome {
        public final List<ConversationTurn> context;
        public final String summary;
        public final int summarizedMessages;
        public final ConversationCompactionPolicy.Mode mode;

        Outcome(List<ConversationTurn> context, String summary, int summarizedMessages, ConversationCompactionPolicy.Mode mode) {
            this.context = context;
            this.summary = summary;
            this.summarizedMessages = summarizedMessages;
            this.mode = mode;
        }
    }
    private static final ConcurrentHashMap<String, ReentrantLock> SESSION_LOCKS = new ConcurrentHashMap<>();
    private static final int TOOL_RETURN_SUMMARY_LIMIT = 2000;
    private final String sessionId;
    private final CoreAgentModel model;
    private final LocalRunStore store;
    private final CrewConversationCompaction crew;
    private final boolean conversationArtifactRecovery;

    public ConversationCompactor(String sessionId, CoreAgentModel model, LocalRunStore store) {
        this(sessionId, model, store, true);
    }

    ConversationCompactor(String sessionId, CoreAgentModel model, LocalRunStore store, boolean conversationArtifactRecovery) {
        this.sessionId = sessionId;
        this.model = model;
        this.store = store;
        this.crew = null;
        this.conversationArtifactRecovery = conversationArtifactRecovery;
    }

    private ConversationCompactor(String sessionId, CrewConversationCompaction crew) {
        this.sessionId = sessionId;
        this.model = null;
        this.store = null;
        this.crew = crew;
        this.conversationArtifactRecovery = false;
    }

    public static ConversationCompactor forCrew(String scopeId, CoreAgentModel model, CrewContextArtifacts artifacts) {
        java.util.Objects.requireNonNull(model);
        return new ConversationCompactor(scopeId, new CrewConversationCompaction(model::completeSummary, artifacts));
    }

    static ConversationCompactor forCrew(String scopeId, CrewConversationCompaction.Summarizer model, CrewContextArtifacts artifacts) {
        return new ConversationCompactor(scopeId, new CrewConversationCompaction(model, artifacts));
    }

    public boolean isCrew() {
        return crew != null;
    }

    void protectGenuineUser(ConversationTurn turn) {
        if (crew != null) crew.protectGenuineUser(turn);
    }

    void restoreSummaryCount(int count) {
        if (crew != null) crew.restoreSummaryCount(count);
    }

    String retainToolOutput(String content, int contextWindow, CancellationToken token) {
        return crew == null ? content : crew.retainToolOutput(content, contextWindow, token);
    }

    public int currentUserMessageIndex() {
        return store == null ? -1 : store.latestUserMessageIndex(sessionId);
    }

    public Outcome compact(List<ConversationTurn> transcript, int contextWindow, String trigger, ConversationCompactionPolicy.Mode requestedMode, CancellationToken token, Listener listener) {
        return compact(transcript, contextWindow, 0, trigger, requestedMode, token, listener);
    }

    public Outcome compact(List<ConversationTurn> transcript, int contextWindow, int inputOverheadTokens, String trigger, ConversationCompactionPolicy.Mode requestedMode, CancellationToken token, Listener listener) {
        token.throwIfCancelled();
        if (crew != null) return crew.compact(transcript, contextWindow, inputOverheadTokens, trigger, token, listener);
        ReentrantLock sessionLock = SESSION_LOCKS.computeIfAbsent(sessionId, (ignored)->new ReentrantLock());
        if (!sessionLock.tryLock()) throw new IllegalStateException("Conversation compaction is already running");
        try {
            if (listener != null) listener.onStarted(trigger);
            token.throwIfCancelled();
            ConversationCompactionPolicy.Plan plan = requestedMode == ConversationCompactionPolicy.Mode.ALL ? ConversationCompactionPolicy.planAll(transcript) : ConversationCompactionPolicy.planSliding(transcript, contextWindow, ConversationCompactionPolicy.DEFAULT_SLIDING_PERCENTAGE);
            if (plan.summarize.isEmpty()) return null;
            String summary = summarize(plan.summarize, plan.mode, token);
            if (plan.mode == ConversationCompactionPolicy.Mode.SLIDING_WINDOW && ConversationCompactionPolicy.estimateTokens(summary) + ConversationCompactionPolicy.estimateTurnsTokens(plan.keep) >= contextWindow) {
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
            if (currentUserIndex >= 0 && !currentUserRepresented) firstKept = Math.min(firstKept, currentUserIndex);
            int summarizedCount = firstKept;
            token.throwIfCancelled();
            store.appendCompaction(sessionId, summary, firstKept, trigger, plan.mode.name().toLowerCase(java.util.Locale.ROOT), summarizedCount);
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

    /** A model-written summary may omit completed effects. Preserve bounded source-backed evidence. */
    private String retainObservedOutcomes(String summary, List<ConversationTurn> messages) {
        final String start = "[Recorded tool outcomes; untrusted observations]";
        final String end = "[/Recorded tool outcomes]";
        List<String> evidence = new ArrayList<>();
        java.util.Map<String, ModelReply.Call> calls = new java.util.LinkedHashMap<>();
        boolean hasToolEvidence = false;
        for (ConversationTurn turn : messages) {
            if (turn.kind == ConversationTurn.Kind.COMPACTION_SUMMARY) {
                int from = turn.content.lastIndexOf(start), through = turn.content.lastIndexOf(end);
                if (from >= 0 && through > from) {
                    String previous = turn.content.substring(from + start.length(), through).trim();
                    for (String line : previous.split("\\n")) if (!line.trim().isEmpty()) evidence.add(line);
                    hasToolEvidence = true;
                }
            }
            for (ModelReply.Call call : turn.toolCalls) calls.put(call.id, call);
            if (turn.kind != ConversationTurn.Kind.TOOL_RESULT) continue;
            hasToolEvidence = true;
            ModelReply.Call call = calls.get(turn.toolCallId);
            String target = call == null ? "" : String.valueOf(call.arguments.getOrDefault("path", ""));
            String outcome = CrewCheckpointStore.sanitizeText(turn.content).replace('\n', ' ');
            evidence.add("call_id=" + turn.toolCallId + "; tool=" + turn.toolName
                    + (target.isEmpty() ? "" : "; path=" + MainChatTranscriptStore.shortText(CrewCheckpointStore.sanitizeText(target), 256))
                    + "; observed result=" + MainChatTranscriptStore.shortText(outcome, 512).replace('\n', ' '));
        }
        if (!hasToolEvidence) return summary;
        if (evidence.size() > 12) evidence = new ArrayList<>(evidence.subList(evidence.size() - 12, evidence.size()));
        String source = "";
        if (conversationArtifactRecovery) {
            MainChatTranscriptStore retained = new MainChatTranscriptStore(store.filesDirectory(), sessionId, store);
            source = "\nSource evidence (untrusted, may be bounded):\n"
                    + retained.archive(ConversationCompactionPolicy.formatTranscript(messages, Integer.MAX_VALUE));
        }
        // Advertise recovery only in a scope where the conversation artifact reader is exposed.
        String appendix = "\n\n" + start + "\n" + String.join("\n", evidence) + "\n" + end + source;
        String safeSummary = CrewCheckpointStore.sanitizeText(summary);
        int limit = Math.max(0, ConversationCompactionPolicy.MAX_SUMMARY_CHARS - appendix.length());
        if (safeSummary.length() > limit) safeSummary = safeSummary.substring(0, limit);
        return safeSummary + appendix;
    }

    private String summarize(List<ConversationTurn> messages, ConversationCompactionPolicy.Mode mode, CancellationToken token) {
        String transcript = ConversationCompactionPolicy.formatTranscript(messages, TOOL_RETURN_SUMMARY_LIMIT);
        String prompt = mode == ConversationCompactionPolicy.Mode.SLIDING_WINDOW ? ConversationCompactionPolicy.SLIDING_PROMPT : ConversationCompactionPolicy.ALL_PROMPT;
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
                String summary = ConversationCompactionPolicy.truncateSummary(reply.text);
                return retainObservedOutcomes(summary, messages);
            } catch (RuntimeException error) {
                if (token.isCancelled()) throw error;
                if (!ConversationCompactionPolicy.isContextOverflow(error)) throw error;
                lastOverflow = error;
            }
        }
        throw lastOverflow == null ? new IllegalStateException("Could not summarize conversation") : lastOverflow;
    }
}
