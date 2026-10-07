package com.jarvys.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** One ReAct loop: model calls and tool results share a local transcript until a text answer ends the run. */
public final class CoreAgentLoop {
    public interface ProgressListener {
        void onProgress(String stage, String message);

        default void onToolProgress(String stage, String callId, String displayName, String detail) {
            onProgress(stage, displayName);
        }

        default void onToolProgress(String stage, String callId, String displayName,
                                    String detail, String previewId) {
            onToolProgress(stage, callId, displayName, detail);
        }

        default void onToolProgress(String stage, String callId, String displayName,
                                    String detail, String previewId, String reflectionSource) {
            onToolProgress(stage, callId, displayName, detail, previewId);
        }

        default void onCompactionStarted(String trigger) { onProgress("compacting", "Compactando conversación…"); }
        default void onCompactionCompleted(String summary, int summarizedMessages, String mode) { }
        default void onCompactionFailed(String message) { onProgress("compaction_error", message); }
    }

    public static final class Result {
        public final String runId;
        public final String text;
        public final int turns;
        public final String outcome;
        public final long durationMs;

        Result(String runId, String text, int turns, String outcome) {
            this(runId, text, turns, outcome, 0L);
        }

        Result(String runId, String text, int turns, String outcome, long durationMs) {
            this.runId = runId;
            this.text = text;
            this.turns = turns;
            this.outcome = outcome;
            this.durationMs = durationMs;
        }

        public Result withText(String replacement) {
            return new Result(runId, replacement, turns, outcome, durationMs);
        }
    }

    public interface Model {
        ModelReply complete(List<ConversationTurn> transcript, String prompt,
                            List<ToolSpec> tools, CancellationToken token);
        default int contextWindow(CancellationToken token) { return ProviderContextWindowResolver.FALLBACK_CONTEXT_WINDOW; }
        default Integer usageTokens(ModelReply reply) { return reply == null ? null : reply.contextTokensUsed; }
    }

    public interface TurnContextProvider {
        /** Called immediately before each model turn; returned data is appended to the user prompt. */
        String takeUntrustedContext();
        default List<String> takeTrustedUserMessages() { return Collections.emptyList(); }
        default void onRateLimit(boolean waiting) { }
    }

    public interface RateLimitWaiter {
        void await(ProviderHttpException failure, long retryNumber, CancellationToken token);
    }

    /** Zero means unlimited. Existing constructors preserve the current ordinary-run limits. */
    public static final class Limits {
        public static final Limits ORDINARY = new Limits(128, MAX_TOOL_CALLS_PER_TURN);
        public static final Limits UNBOUNDED = new Limits(0, 0);
        public final int maxModelTurns;
        public final int maxToolCallsPerTurn;
        public Limits(int maxModelTurns, int maxToolCallsPerTurn) {
            if (maxModelTurns < 0 || maxToolCallsPerTurn < 0) throw new IllegalArgumentException("Limits must be zero or positive");
            this.maxModelTurns = maxModelTurns;
            this.maxToolCallsPerTurn = maxToolCallsPerTurn;
        }
        public boolean isUnbounded() { return maxModelTurns == 0 && maxToolCallsPerTurn == 0; }
    }

    static final String TIMEOUT_MESSAGE = "La tarea superó el tiempo máximo de ejecución. El trabajo puede estar parcialmente hecho; revisa el workspace o pídele a Jarvys que continúe.";
    static final String LOOP_MESSAGE = "Jarvys detuvo la tarea porque detectó llamadas repetidas sin avance. El trabajo puede estar parcialmente hecho; revisa el workspace o pídele que continúe.";
    private static final int MAX_TOOL_CALLS_PER_TURN = 4;
    private static final int MAX_TRANSCRIPT_MESSAGES = 40;
    private final Model model;
    private final CoreToolRegistry tools;
    private final CorePromptBudget budget;
    private final String instructions;
    private final ConversationCompactor compactor;
    private final int maxModelTurns;
    private final Limits limits;
    private final TurnContextProvider turnContextProvider;
    private final RateLimitWaiter rateLimitWaiter;
    private volatile List<ConversationTurn> transcriptSnapshot = Collections.emptyList();

    /** Last cycle transcript, with interrupted tool calls paired to honest non-executed results. */
    public List<ConversationTurn> transcriptSnapshot() {
        List<ConversationTurn> snapshot = new ArrayList<>(transcriptSnapshot);
        java.util.Set<String> results = new java.util.HashSet<>();
        for (ConversationTurn turn : snapshot) if (turn.kind == ConversationTurn.Kind.TOOL_RESULT) results.add(turn.toolCallId);
        List<ConversationTurn> missing = new ArrayList<>();
        for (ConversationTurn turn : snapshot) if (turn.kind == ConversationTurn.Kind.TOOL_CALLS) {
            for (ModelReply.Call call : turn.toolCalls) if (!results.contains(call.id)) {
                missing.add(ConversationTurn.toolResult(call.id, call.name,
                        "Tool execution was interrupted before a result was recorded; it was not automatically replayed."));
            }
        }
        snapshot.addAll(missing);
        return Collections.unmodifiableList(snapshot);
    }

    private void updateTranscriptSnapshot(List<ConversationTurn> transcript) {
        transcriptSnapshot = Collections.unmodifiableList(new ArrayList<>(transcript));
    }

    public CoreAgentLoop(CoreAgentModel model, CoreToolRegistry tools, String instructions, String sessionId) {
        this(model, tools, instructions, sessionId, CorePromptBudget.standard());
    }

    public CoreAgentLoop(CoreAgentModel model, CoreToolRegistry tools, String instructions, String sessionId,
                         CorePromptBudget budget) {
        this(model, tools, instructions, sessionId, budget, null);
    }

    public CoreAgentLoop(CoreAgentModel model, CoreToolRegistry tools, String instructions, String sessionId,
                         CorePromptBudget budget, ConversationCompactor compactor) {
        this(new Model() {
            @Override public ModelReply complete(List<ConversationTurn> transcript, String prompt,
                                                List<ToolSpec> declarations, CancellationToken token) {
                return model.complete(instructions, transcript, prompt, declarations, token);
            }
            @Override public int contextWindow(CancellationToken token) { return model.contextWindow(token); }
        }, tools, instructions, sessionId, budget, compactor);
    }

    public CoreAgentLoop(Model model, CoreToolRegistry tools, String instructions, String sessionId) {
        this(model, tools, instructions, sessionId, CorePromptBudget.standard());
    }

    public CoreAgentLoop(Model model, CoreToolRegistry tools, String instructions, String sessionId,
                         CorePromptBudget budget) {
        this(model, tools, instructions, sessionId, budget, null);
    }

    public CoreAgentLoop(Model model, CoreToolRegistry tools, String instructions, String sessionId,
                         CorePromptBudget budget, ConversationCompactor compactor) {
        this(model, tools, instructions, sessionId, budget, compactor, 128);
    }

    public CoreAgentLoop(Model model, CoreToolRegistry tools, String instructions, String sessionId,
                         CorePromptBudget budget, ConversationCompactor compactor, int maxModelTurns) {
        if (maxModelTurns < 1) throw new IllegalArgumentException("Model turn limit must be positive");
        this.model = model;
        this.tools = tools;
        this.budget = budget;
        this.instructions = instructions == null ? "" : instructions;
        this.compactor = compactor;
        this.maxModelTurns = maxModelTurns;
        this.limits = new Limits(maxModelTurns, MAX_TOOL_CALLS_PER_TURN);
        this.turnContextProvider = null;
        this.rateLimitWaiter = null;
    }

    public CoreAgentLoop(Model model, CoreToolRegistry tools, String instructions, String sessionId,
                         CorePromptBudget budget, ConversationCompactor compactor, Limits limits,
                         TurnContextProvider turnContextProvider, RateLimitWaiter rateLimitWaiter) {
        this.model = model;
        this.tools = tools;
        this.budget = budget;
        this.instructions = instructions == null ? "" : instructions;
        this.compactor = compactor;
        this.limits = limits == null ? Limits.ORDINARY : limits;
        this.maxModelTurns = this.limits.maxModelTurns;
        this.turnContextProvider = turnContextProvider;
        this.rateLimitWaiter = rateLimitWaiter;
    }

    public Result run(String request, List<ConversationTurn> previousTranscript,
                      CancellationToken token, ProgressListener listener) {
        if (request == null || request.trim().isEmpty()) throw new IllegalArgumentException("A user request is required");
        String runId = UUID.randomUUID().toString();
        List<ConversationTurn> transcript = new ArrayList<>(previousTranscript == null
                ? Collections.emptyList() : previousTranscript);
        updateTranscriptSnapshot(transcript);
        String prompt = request.trim();
        boolean requestInTranscript = false;
        boolean loopRecoveryUsed = false;
        int modelTurns = 0;
        long startedAtMs = System.currentTimeMillis();
        int overflowCompactions = 0;
        ToolLoopDetector loopDetector = new ToolLoopDetector();
        try {
            while (true) {
                token.throwIfCancelled();
                if (limits.maxModelTurns > 0 && modelTurns >= limits.maxModelTurns) throw new IllegalStateException("Agent reached its configured model-turn limit");
                List<ToolSpec> declarations = tools.declarations();
                if (turnContextProvider != null) {
                    List<String> trusted = turnContextProvider.takeTrustedUserMessages();
                    if (trusted != null) for (String message : trusted) {
                        if (message != null && !message.isEmpty()) transcript.add(new ConversationTurn("user", message));
                    }
                }
                updateTranscriptSnapshot(transcript);
                boolean safetyBounded = false;
                if (compactor != null) {
                    // One preflight per provider call, matching pi-stream-adapter.ts:535-571,795-813.
                    try {
                        int window = model.contextWindow(token);
                        int estimate = estimateContext(transcript, prompt, declarations);
                        if (ConversationCompactionPolicy.shouldCompact(estimate, window)) {
                            ConversationCompactor.Outcome outcome = compact(transcript, window,
                                    "auto_preflight", ConversationCompactionPolicy.Mode.SLIDING_WINDOW, token, listener);
                            if (outcome != null) {
                                transcript = new ArrayList<>(outcome.context);
                                updateTranscriptSnapshot(transcript);
                            }
                        }
                    } catch (RuntimeException failure) {
                        if (token.isCancelled()) throw failure;
                        safetyBounded = true;
                        compactionFailed(listener, "No se pudo compactar el contexto; se usará un recorte de seguridad para continuar.");
                    }
                }
                List<ConversationTurn> requestTranscript = safetyBounded
                        ? bounded(transcript, budget.transcriptChars) : transcript;
                String turnPrompt = prompt;
                if (turnContextProvider != null) {
                    String incoming = turnContextProvider.takeUntrustedContext();
                    if (incoming != null && !incoming.isEmpty()) turnPrompt += "\n\n" + incoming;
                }
                ModelReply reply;
                try {
                    long retryNumber = 0;
                    while (true) {
                        try {
                            reply = model.complete(requestTranscript, turnPrompt, declarations, token);
                            break;
                        } catch (ProviderHttpException limited) {
                            if (!limited.isRateLimit()) throw limited;
                            if (rateLimitWaiter == null) throw limited;
                            if (turnContextProvider != null) turnContextProvider.onRateLimit(true);
                            long currentRetry = retryNumber;
                            if (retryNumber < Long.MAX_VALUE) retryNumber++;
                            try { rateLimitWaiter.await(limited, currentRetry, token); }
                            finally { if (turnContextProvider != null) turnContextProvider.onRateLimit(false); }
                        }
                    }
                } catch (RuntimeException failure) {
                    if (compactor == null || !ConversationCompactionPolicy.isContextOverflow(failure)
                            || overflowCompactions >= 3) throw failure;
                    token.throwIfCancelled();
                    int window = model.contextWindow(token);
                    try {
                        ConversationCompactor.Outcome outcome = compact(transcript, window,
                                "overflow", ConversationCompactionPolicy.Mode.SLIDING_WINDOW, token, listener);
                        if (outcome == null) throw failure;
                        transcript = new ArrayList<>(outcome.context);
                        updateTranscriptSnapshot(transcript);
                    } catch (RuntimeException compactionFailure) {
                        if (token.isCancelled()) throw compactionFailure;
                        compactionFailed(listener, "No se pudo resumir el historial tras el límite del proveedor; reintentaré con un recorte de seguridad.");
                        transcript = bounded(transcript, budget.transcriptChars);
                        updateTranscriptSnapshot(transcript);
                    }
                    overflowCompactions++;
                    continue;
                }
                modelTurns++;
                overflowCompactions = 0;
                token.throwIfCancelled();
                if (reply == null) throw new IllegalStateException("Provider returned no response");

                boolean replyToolCallAlreadyRecorded = false;
                if (compactor != null) {
                    // Post-call usage check mirrors pi-stream-adapter.ts:751-779.
                    List<ConversationTurn> postTurn = new ArrayList<>(transcript);
                    if (!requestInTranscript) {
                        postTurn.add(new ConversationTurn("user", request.trim(), compactor.currentUserMessageIndex()));
                    }
                    if (reply.calls.isEmpty()) postTurn.add(new ConversationTurn("assistant", reply.text));
                    else postTurn.add(ConversationTurn.toolCalls(reply.text, reply.calls));
                    Integer usageTokens = model.usageTokens(reply);
                    int contextTokens = usageTokens == null
                            ? estimateContext(postTurn, "", declarations)
                            : usageTokens;
                    int window = model.contextWindow(token);
                    if (ConversationCompactionPolicy.shouldCompact(contextTokens, window)) {
                        try {
                            ConversationCompactor.Outcome outcome = compact(postTurn, window,
                                    "auto_post_turn", ConversationCompactionPolicy.Mode.SLIDING_WINDOW, token, listener);
                            if (outcome != null) {
                                transcript = new ArrayList<>(outcome.context);
                                updateTranscriptSnapshot(transcript);
                                requestInTranscript = true;
                                replyToolCallAlreadyRecorded = !reply.calls.isEmpty();
                            }
                        } catch (RuntimeException failure) {
                            if (token.isCancelled()) throw failure;
                            compactionFailed(listener, "No se pudo resumir este turno; continuaré con el historial disponible.");
                        }
                    }
                }
                if (reply.calls.isEmpty()) {
                    String answer = reply.text == null ? "" : reply.text.trim();
                    if (answer.isEmpty()) throw new IllegalStateException("Provider returned neither an answer nor a tool call");
                    if (turnContextProvider != null) {
                        if (!requestInTranscript) transcript.add(new ConversationTurn("user", request.trim()));
                        transcript.add(new ConversationTurn("assistant", answer));
                        updateTranscriptSnapshot(transcript);
                    }
                    emit(listener, "answer", answer);
                    return new Result(runId, answer, modelTurns, "COMPLETED",
                            Math.max(0L, System.currentTimeMillis() - startedAtMs));
                }

                if (!requestInTranscript) {
                    transcript.add(new ConversationTurn("user", request.trim(),
                            compactor == null ? -1 : compactor.currentUserMessageIndex()));
                    requestInTranscript = true;
                }
                if (!replyToolCallAlreadyRecorded) transcript.add(ConversationTurn.toolCalls(reply.text, reply.calls));
                updateTranscriptSnapshot(transcript);

                ModelReply.Call repeatedCall = null;
                for (ModelReply.Call call : reply.calls) {
                    if (loopDetector.noProgressStreak(call.name, loopDetector.argumentsKey(call))
                            >= ToolLoopDetector.CRITICAL_THRESHOLD) {
                        repeatedCall = call;
                        break;
                    }
                }
                if (repeatedCall != null) {
                    String blockMessage = "Repeated " + repeatedCall.name + " calls with identical arguments and unchanged results were blocked to prevent a no-progress loop.";
                    for (ModelReply.Call call : reply.calls) {
                        transcript.add(ConversationTurn.toolResult(call.id, call.name, "Tool error: " + blockMessage));
                    }
                    updateTranscriptSnapshot(transcript);
                    if (loopRecoveryUsed) return new Result(runId, LOOP_MESSAGE, modelTurns, "PARTIAL");
                    loopRecoveryUsed = true;
                    prompt = "Continue.";
                    continue;
                }

                int remainingResultChars = limits.isUnbounded() ? Integer.MAX_VALUE : budget.toolResultsPerTurnChars;
                int callCount = limits.maxToolCallsPerTurn == 0 ? reply.calls.size()
                        : Math.min(reply.calls.size(), limits.maxToolCallsPerTurn);
                String terminalText = null;
                for (int index = 0; index < callCount; index++) {
                    token.throwIfCancelled();
                    ModelReply.Call call = reply.calls.get(index);
                    String displayName = WebSearchTools.displayLabel(call.name, call.arguments);
                    if (displayName.equals(call.name)) displayName = tools.displayName(call.name);
                    String argumentsKey = loopDetector.argumentsKey(call);
                    int recentCalls = loopDetector.recentCallCount(call.name, argumentsKey);
                    String reflectionSource = tools.reflectionSource(call.name);
                    if (listener != null) listener.onToolProgress("tool_call", call.id, displayName, null, null, reflectionSource);
                    CoreToolResult result = tools.invoke(call.name, call.arguments, token, message -> {
                        if (listener != null && message != null && !message.isEmpty()) {
                            listener.onToolProgress("tool_progress", call.id, message, null, null, reflectionSource);
                        }
                    });
                    token.throwIfCancelled();
                    String rawContent = result.content;
                    loopDetector.record(call.name, argumentsKey, result.success, rawContent);
                    String content = rawContent;
                    if (result.completeContentRequired && content.length() > remainingResultChars) {
                        result = CoreToolResult.failure("Complete tool output exceeds the remaining per-turn context budget; "
                                + "request this large result by itself. No partial output was added.");
                        content = result.content;
                    } else if (content.length() > remainingResultChars) {
                        content = remainingResultChars > 0
                                ? content.substring(0, remainingResultChars) + "…[truncated by per-turn budget]"
                                : "Tool output omitted because the per-turn result budget is exhausted";
                    }
                    if (recentCalls >= ToolLoopDetector.WARNING_THRESHOLD) {
                        content += "\n\nLoop warning: this tool has been called " + recentCalls
                                + " times with identical arguments. If this is not making progress, stop retrying and report the task status.";
                    }
                    remainingResultChars = Math.max(0, remainingResultChars - content.length());
                    if (!result.success) content = "Tool error: " + content;
                    transcript.add(ConversationTurn.toolResult(call.id, call.name, content));
                    updateTranscriptSnapshot(transcript);
                    if (result.finishRun && result.success) terminalText = rawContent;
                    if (listener != null) listener.onToolProgress(
                            result.success ? "tool_result" : "tool_error", call.id, displayName,
                            content, result.previewId, reflectionSource);
                }
                if (terminalText != null) {
                    emit(listener, "answer", terminalText);
                    return new Result(runId, terminalText, modelTurns, "COMPLETED",
                            Math.max(0L, System.currentTimeMillis() - startedAtMs));
                }
                for (int index = callCount; index < reply.calls.size(); index++) {
                    ModelReply.Call call = reply.calls.get(index);
                    transcript.add(ConversationTurn.toolResult(call.id, call.name,
                            "Tool error: too many tool calls in one model turn"));
                }
                updateTranscriptSnapshot(transcript);
                prompt = "Continue.";
            }
        } catch (RuntimeException failure) {
            if (token.isStoppedByUser()) throw failure;
            if (token.isTimedOut()) {
                Thread.interrupted();
                return new Result(runId, TIMEOUT_MESSAGE, modelTurns, "PARTIAL");
            }
            throw failure;
        }
    }

    private int estimateContext(List<ConversationTurn> transcript, String prompt, List<ToolSpec> declarations) {
        StringBuilder toolPayload = new StringBuilder();
        for (ToolSpec tool : declarations) {
            toolPayload.append(tool.name).append('\n').append(tool.description).append('\n')
                    .append(tool.jsonSchema()).append('\n');
        }
        return ConversationCompactionPolicy.estimateTokens(instructions, toolPayload.toString(), transcript, prompt);
    }

    private ConversationCompactor.Outcome compact(List<ConversationTurn> transcript, int contextWindow,
                                                   String trigger,
                                                   ConversationCompactionPolicy.Mode mode,
                                                   CancellationToken token, ProgressListener listener) {
        return compactor.compact(transcript, contextWindow, trigger, mode, token,
                new ConversationCompactor.Listener() {
                    @Override public void onStarted(String actualTrigger) {
                        if (listener != null) listener.onCompactionStarted(actualTrigger);
                    }
                    @Override public void onCompleted(String summary, int summarizedMessages, String actualMode) {
                        if (listener != null) listener.onCompactionCompleted(summary, summarizedMessages, actualMode);
                    }
                });
    }

    private static void compactionFailed(ProgressListener listener, String message) {
        if (listener != null) listener.onCompactionFailed(message);
    }

    private static List<ConversationTurn> bounded(List<ConversationTurn> source, int maxChars) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        for (int start = 0; start <= source.size(); start++) {
            int count = source.size() - start;
            if (count > MAX_TRANSCRIPT_MESSAGES || !safeToolPairBoundary(source, start)) continue;
            int chars = 0;
            for (int index = start; index < source.size(); index++) {
                ConversationTurn turn = source.get(index);
                chars += turn.content.length() + turn.thinking.length() + turn.imageCount * 24
                        + turn.toolCallId.length() + turn.toolName.length();
                for (ModelReply.Call call : turn.toolCalls) chars += call.name.length() + call.arguments.toString().length();
            }
            if (chars <= maxChars) return new ArrayList<>(source.subList(start, source.size()));
        }
        return Collections.emptyList();
    }

    private static boolean safeToolPairBoundary(List<ConversationTurn> source, int start) {
        for (int index = start; index < source.size(); index++) {
            ConversationTurn turn = source.get(index);
            if (turn.kind != ConversationTurn.Kind.TOOL_RESULT) continue;
            boolean hasPriorCall = false;
            for (int callIndex = 0; callIndex < index; callIndex++) {
                ConversationTurn candidate = source.get(callIndex);
                if (candidate.kind == ConversationTurn.Kind.TOOL_CALLS
                        && candidate.toolCalls.stream().anyMatch(call -> call.id.equals(turn.toolCallId))) {
                    hasPriorCall = true;
                    if (callIndex < start) return false;
                }
            }
            if (hasPriorCall) continue;
        }
        return true;
    }

    private static void emit(ProgressListener listener, String stage, String message) {
        if (listener != null) listener.onProgress(stage, message);
    }
}
