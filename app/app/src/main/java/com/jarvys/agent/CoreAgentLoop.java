package com.jarvys.agent;

import com.jarvys.agent.crew.CrewInputBinding;
import com.jarvys.agent.crew.CrewInputIncorporation;
import com.jarvys.agent.crew.CrewMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One ReAct loop: model calls and tool results share a local transcript until a text answer ends the run.
 */
public final class CoreAgentLoop {
    public static final String INBOX_TOOL = "crew_inbox";
    private static final AtomicInteger TOOL_THREAD_ID = new AtomicInteger();
    private static final ExecutorService PARALLEL_TOOL_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "jarvys-tool-" + TOOL_THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    public interface CheckpointListener {

        void persist(Checkpoint checkpoint);
        /** One checkpoint write, with the exact live prepared invocation (never restored from JSON). */
        default void persistStarted(Checkpoint checkpoint, PreparedInvocationSnapshot prepared) { persist(checkpoint); }
        default boolean requiresPreparedInvocationSnapshot() { return false; }
    }

    /** Runtime-only identity. Raw values are never exposed or hashed; effective values are executed unchanged. */
    public static final class PreparedInvocationSnapshot {
        private final String callId;
        private final String toolName;
        private final CoreTool tool;
        private Map<String, Object> raw;
        private Map<String, Object> effective;
        private boolean disposed;
        private PreparedInvocationSnapshot(ModelReply.Call call, CoreTool tool, Map<String, Object> raw,
                                           Map<String, Object> effective) {
            this.callId = call.id; this.toolName = call.name; this.tool = tool;
            this.raw = freezeMap(raw); this.effective = freezeMap(effective);
        }
        public String callId() { return callId; }
        public String toolName() { return toolName; }
        synchronized Map<String, Object> arguments() {
            if (disposed) throw new IllegalStateException("Prepared invocation expired");
            return effective;
        }
        synchronized void requireDispatch(CoreTool actual, Map<String, Object> arguments) {
            if (disposed || actual != tool || arguments != effective)
                throw new IllegalStateException("Prepared invocation identity changed");
        }
        synchronized void requireConfirmed(CrewCheckpointStore.ConfirmedSnapshot confirmed) {
            CoreAgentLoop.Checkpoint verified = confirmed.loopForEvidence();
            if (disposed || !"STARTED".equals(verified.toolLifecycle.get(callId)))
                throw new IllegalStateException("Prepared invocation is not durably started");
            ModelReply.Call matched = null;
            for (ConversationTurn turn : verified.transcript) {
                if (turn.kind == ConversationTurn.Kind.TOOL_RESULT && callId.equals(turn.toolCallId))
                    throw new IllegalStateException("Prepared invocation already has a result");
                for (ModelReply.Call call : turn.toolCalls) if (callId.equals(call.id)) {
                    if (matched != null) throw new IllegalStateException("Ambiguous prepared invocation");
                    matched = call;
                }
            }
            if (matched == null || !toolName().equals(matched.name))
                throw new IllegalStateException("Prepared invocation missing from checkpoint");
            try {
                Object sanitized = CrewCheckpointStore.sanitizeJson(new org.json.JSONObject(raw));
                if (!jsonValuesEqual(freezeValue(sanitized), freezeMap(matched.arguments)))
                    throw new IllegalStateException("Prepared checkpoint arguments changed");
            } catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("Cannot verify prepared arguments", failure); }
        }
        public synchronized void discard() { disposed = true; raw = null; effective = null; }
        private static Map<String, Object> freezeMap(Map<String, Object> map) {
            @SuppressWarnings("unchecked") Map<String,Object> frozen = (Map<String,Object>) freezeValue(map);
            return frozen;
        }
        private static boolean jsonValuesEqual(Object left, Object right) {
            if (left instanceof Number && right instanceof Number)
                return new java.math.BigDecimal(left.toString()).compareTo(new java.math.BigDecimal(right.toString())) == 0;
            if (left instanceof Map && right instanceof Map) {
                Map<?,?> a = (Map<?,?>)left, b = (Map<?,?>)right;
                if (!a.keySet().equals(b.keySet())) return false;
                for (Object key : a.keySet()) if (!jsonValuesEqual(a.get(key), b.get(key))) return false;
                return true;
            }
            if (left instanceof List && right instanceof List) {
                List<?> a = (List<?>)left, b = (List<?>)right;
                if (a.size()!=b.size()) return false;
                for (int i=0;i<a.size();i++) if (!jsonValuesEqual(a.get(i),b.get(i))) return false;
                return true;
            }
            return java.util.Objects.equals(left,right);
        }
        private static Object freezeValue(Object value) {
            if (value == org.json.JSONObject.NULL) return null;
            if (value == null || value instanceof String || value instanceof Boolean
                    || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                    || value instanceof Float || value instanceof Double || value instanceof java.math.BigDecimal
                    || value instanceof java.math.BigInteger) return value;
            if (value instanceof org.json.JSONObject) {
                Map<String,Object> copy = new LinkedHashMap<>();
                org.json.JSONObject json = (org.json.JSONObject)value;
                java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) { String key = keys.next(); copy.put(key, freezeValue(json.opt(key))); }
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof Map) {
                Map<String,Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?,?> entry : ((Map<?,?>)value).entrySet()) {
                    if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("Non-string argument key");
                    copy.put((String)entry.getKey(), freezeValue(entry.getValue()));
                }
                return Collections.unmodifiableMap(copy);
            }
            List<Object> copy = new ArrayList<>();
            if (value instanceof List) for (Object item : (List<?>)value) copy.add(freezeValue(item));
            else if (value instanceof org.json.JSONArray) {
                org.json.JSONArray array = (org.json.JSONArray)value;
                for (int i=0;i<array.length();i++) copy.add(freezeValue(array.opt(i)));
            } else throw new IllegalArgumentException("Unsupported mutable argument value");
            return Collections.unmodifiableList(copy);
        }
    }

    public interface ProgressListener {

        void onProgress(String stage, String message);

        /** User-facing assistant text only, after durable tool intent and before any tool effect. */
        default void onAssistantProgress(String callId, String message) { }

        /** Carries the provider-loop identity so a visible stream can become this durable preamble. */
        default void onAssistantProgress(String runId, String callId, String message) {
            onAssistantProgress(callId, message);
        }

        /** Starts or resets this run's transient answer row before an individual provider attempt. */
        default void onAssistantTextStreamStarted(String runId) { }

        /** Incremental user-facing text only; tool arguments and reasoning are excluded upstream. */
        default void onAssistantTextDelta(String runId, String delta) { }

        /** Ends the transient stream after a tool-call preamble had a chance to promote it. */
        default void onAssistantTextStreamEnded(String runId) { }

        /** A completed assistant turn that precedes a newly incorporated durable user message. */
        default void onAssistantAnswerBeforeInput(String runId, String message, IncomingMessage nextInput) { }

        /** Called after the initial run request has entered the transcript. */
        default void onInitialUserMessageIncorporated() { }

        /** Acknowledges durable AGENT/SYSTEM rows restored into this run's initial transcript. */
        default void onInitialInternalInputsIncorporated(List<IncomingMessage> messages) { }

        /** Called after newly collected durable user messages have entered and checkpointed in the transcript. */
        default void onDurableMessagesIncorporated(List<IncomingMessage> messages) { }

        /** Called when a durable message is discarded before transcript insertion. */
        default void onDurableMessagesDiscarded(List<IncomingMessage> messages) { }


        default void onToolProgress(String stage, String callId, String displayName, String detail) {
            onProgress(stage, displayName);
        }

        default void onToolProgress(String stage, String callId, String displayName, String detail, String previewId) {
            onToolProgress(stage, callId, displayName, detail);
        }

        default void onToolProgress(String stage, String callId, String displayName, String detail, String previewId, String reflectionSource) {
            onToolProgress(stage, callId, displayName, detail, previewId);
        }

        default void onToolProgress(String stage, String callId, String displayName, String detail, String previewId, String reflectionSource, String auditDetail) {
            onToolProgress(stage, callId, displayName, detail, previewId, reflectionSource);
        }

        default void onToolActivity(ToolActivity activity) {
            onToolProgress("tool_not_started".equals(activity.stage) ? "tool_error" : activity.stage, activity.callId,
                    "tool_progress".equals(activity.stage) ? activity.detail : activity.displayName, activity.detail,
                    activity.previewId.isEmpty() ? null : activity.previewId, activity.reflectionSource, activity.auditDetail);
        }

        default void onCompactionStarted(String trigger) {
            onProgress("compacting", "Compactando conversación…");
        }

        default void onCompactionCompleted(String summary, int summarizedMessages, String mode) {
        }

        default void onCompactionFailed(String message) {
            onProgress("compaction_error", message);
        }
    }

    public enum InterruptionReason { NONE, DEADLINE, NO_PROGRESS, PROVIDER_UNAVAILABLE }

    public static final class Result {
        public final String runId;
        public final String text;
        public final int turns;
        public final String outcome;
        public final long durationMs;
        public final InterruptionReason interruptionReason;
        // In-memory classification only: never a transcript, persisted payload or model instruction.
        private final transient RuntimeException providerInterruptionCause;

        Result(String runId, String text, int turns, String outcome) {
            this(runId, text, turns, outcome, 0L);
        }

        Result(String runId, String text, int turns, String outcome, long durationMs) {
            this(runId, text, turns, outcome, durationMs, InterruptionReason.NONE);
        }

        Result(String runId, String text, int turns, String outcome, long durationMs, InterruptionReason reason) {
            this(runId, text, turns, outcome, durationMs, reason, null);
        }

        private Result(String runId, String text, int turns, String outcome, long durationMs,
                       InterruptionReason reason, RuntimeException providerInterruptionCause) {
            this.runId = runId;
            this.text = text;
            this.turns = turns;
            this.outcome = outcome;
            this.durationMs = durationMs;
            this.interruptionReason = reason;
            this.providerInterruptionCause = providerInterruptionCause;
        }

        public Result withText(String replacement) {
            return new Result(runId, replacement, turns, outcome, durationMs, interruptionReason, providerInterruptionCause);
        }

        /** Existing caller failure policies can retain their typed provider classification. */
        public void throwIfProviderUnavailable() {
            if (providerInterruptionCause != null) throw providerInterruptionCause;
        }
    }

    public interface Model {

        ModelReply complete(List<ConversationTurn> transcript, String prompt, List<ToolSpec> tools, CancellationToken token);

        default ModelReply complete(List<ConversationTurn> transcript, String prompt, List<ToolSpec> tools,
                                    CancellationToken token, ModelProviderClient.TextDeltaListener listener) {
            return complete(transcript, prompt, tools, token);
        }

        default boolean usesPreparedRequests() { return false; }

        /** Called only after an acknowledged successful compaction, before preparing its next request. */
        default void onCompactionCompleted() {}

        default PreparedModelRequest prepareRequest(List<ConversationTurn> transcript, String prompt,
                List<ToolSpec> tools, CancellationToken token) {
            throw new IllegalStateException("prepared_model_request_unavailable");
        }

        default PreparedModelRequest prepareRequest(List<ConversationTurn> transcript, String prompt,
                List<ToolSpec> tools, CancellationToken token, boolean activityAttempt) {
            return prepareRequest(transcript,prompt,tools,token);
        }

        default ModelReply completePrepared(PreparedModelRequest request, CancellationToken token,
                ModelProviderClient.TextDeltaListener listener) {
            throw new IllegalStateException("prepared_model_request_unavailable");
        }

        default int contextWindow(CancellationToken token) {
            return ProviderContextWindowResolver.FALLBACK_CONTEXT_WINDOW;
        }

        default Integer usageTokens(ModelReply reply) {
            return reply == null ? null : reply.contextTokensUsed;
        }

        /** Best-effort diagnostic hook when a completed model turn has no final text or tool calls. */
        default void onEmptyReply(ModelReply reply) { }
    }

    public static final class IncomingMessage {
        public final String id;
        public final String source;
        public final String sourceKind;
        public final String messageType;
        public final String content;
        public final boolean genuineUser;
        public final CrewInputBinding inputBinding;
        public final List<String> references;
        public final ConversationInput.Origin origin;
        public final ConversationInput.Provenance provenance;

        public IncomingMessage(String id, String source, String content, boolean genuineUser) {
            this(id, source, content, genuineUser, null,
                    "legacy".equals(source) ? "legacy_context" : "message",
                    Collections.emptyList(), genuineUser ? "user" : ("legacy".equals(source) ? "legacy" : "crew"));
        }

        private IncomingMessage(String id, String source, String content, boolean genuineUser,
                                CrewInputBinding inputBinding, String messageType, List<String> references,
                                String sourceKind) {
            this(id, source, content, genuineUser, inputBinding, messageType, references, sourceKind,
                    genuineUser ? ConversationInput.Origin.USER
                            : ("legacy".equals(sourceKind) ? ConversationInput.Origin.SYSTEM : ConversationInput.Origin.AGENT),
                    null);
        }

        private IncomingMessage(String id, String source, String content, boolean genuineUser,
                                CrewInputBinding inputBinding, String messageType, List<String> references,
                                String sourceKind, ConversationInput.Origin origin,
                                ConversationInput.Provenance provenance) {
            this.inputBinding = inputBinding;
            if (id == null || id.isEmpty()) throw new IllegalArgumentException("Incoming message id is required");
            this.id = id;
            this.source = source == null ? "unknown" : source;
            this.sourceKind = sourceKind == null ? "unknown" : sourceKind;
            this.messageType = messageType == null ? "message" : messageType;
            this.references = references == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(references));
            this.content = content == null ? "" : content;
            this.genuineUser = genuineUser;
            this.origin = origin == null ? ConversationInput.Origin.SYSTEM : origin;
            this.provenance = provenance;
        }

        public static IncomingMessage fromCrewMessage(CrewMessage message) {
            boolean user = "user".equals(message.from) && message.type == CrewMessage.Type.USER;
            CrewInputBinding binding = message.inputBinding != null && message.inputBinding.matchesDelivery(message)
                    ? message.inputBinding : null;
            String sourceSession = "chief".equals(message.from)
                    ? com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION : message.conversationId;
            ConversationInput.Provenance provenance = binding == null ? null : new ConversationInput.Provenance(
                    sourceSession, binding.workspaceId, binding.assignmentId, binding.botId,
                    message.id, kindFor(message.type), binding.sourceKind == CrewInputBinding.SourceKind.WORKSPACE_REPLY
                        ? ConversationInput.Provenance.PrincipalRole.SUPERVISOR
                        : ConversationInput.Provenance.PrincipalRole.PEER);
            return new IncomingMessage(message.id, message.from, message.text, user, binding,
                    message.type.name(), message.refs, "crew",
                    user ? ConversationInput.Origin.USER : ConversationInput.Origin.AGENT, provenance);
        }

        public static IncomingMessage mainChat(String id, String content) {
            return new IncomingMessage(id, "user", content, true, null, "main_chat_steering",
                    Collections.emptyList(), "main_chat");
        }

        public static IncomingMessage mainChat(ConversationInput input) {
            if (input == null) throw new IllegalArgumentException("Conversation input is required");
            String source = input.origin == ConversationInput.Origin.USER ? "user" : "agent";
            String type = input.provenance == null ? "message" : input.provenance.kind.name();
            return new IncomingMessage(input.messageId, source, input.content,
                    input.origin == ConversationInput.Origin.USER, null, type,
                    Collections.emptyList(), "main_chat", input.origin, input.provenance);
        }

        /** Content-bearing internal input offered directly to an active worker loop. */
        public static IncomingMessage agentInput(ConversationInput input) {
            if (input == null || input.origin == ConversationInput.Origin.USER)
                throw new IllegalArgumentException("Worker agent input requires AGENT or SYSTEM origin");
            String source = input.provenance == null ? "system" : input.provenance.sessionId;
            String kind = input.provenance == null ? "message" : input.provenance.kind.name();
            return new IncomingMessage(input.messageId, source, input.content, false, null, kind,
                    Collections.emptyList(), "crew", input.origin, input.provenance);
        }

        private static ConversationInput.Kind kindFor(CrewMessage.Type type) {
            if (type == null) return ConversationInput.Kind.MESSAGE;
            try { return ConversationInput.Kind.valueOf(type.name()); }
            catch (RuntimeException unknown) { return ConversationInput.Kind.OTHER; }
        }
    }

    public interface TurnContextProvider {

        /** Starts one model turn. Durable recovery sources may drain their pending set once here. */
        default void onRunStarted() { }

        /** Legacy provider data is committed as a real untrusted user message before a model turn. */
        default String takeUntrustedContext() { return ""; }

        default List<IncomingMessage> takeDurableMessages() {
            return Collections.emptyList();
        }

        /** Final control point: read pending durable input and close admission if none is waiting. */
        default List<IncomingMessage> takeDurableMessagesAtFinalCheckpoint() {
            List<IncomingMessage> incoming = takeDurableMessages();
            if (incoming == null || incoming.isEmpty()) closeForRunExit();
            return incoming;
        }

        /** Stops accepting run-scoped input when a run exits without a final model checkpoint. */
        default void closeForRunExit() { }

        /** Acknowledges only durable messages that were added to the loop transcript. */
        default void durableMessagesIncorporated(List<IncomingMessage> messages) { }

        default List<String> completedToolIncomingIds(String toolName) {
            return Collections.emptyList();
        }

        default List<String> takeTrustedUserMessages() {
            return Collections.emptyList();
        }

        default void onRateLimit(boolean waiting) {
        }
    }

    public interface RateLimitWaiter {

        void await(Throwable failure, long retryNumber, CancellationToken token);
    }

    /** Result-budget mode retained for Crew's complete tool outputs. The ReAct loop is unbounded. */
    public static final class ToolResultBudget {
        public static final ToolResultBudget BOUNDED = new ToolResultBudget(false);
        public static final ToolResultBudget UNBOUNDED = new ToolResultBudget(true);
        private final boolean completeToolResults;

        private ToolResultBudget(boolean completeToolResults) {
            this.completeToolResults = completeToolResults;
        }

        public boolean allowsCompleteToolResults() {
            return completeToolResults;
        }
    }

    private static final class PreparedToolCall {
        final int index;
        final ModelReply.Call call;
        final CoreTool tool;
        volatile Map<String, Object> arguments;
        final PreparedInvocationSnapshot sourceSnapshot;
        final String argumentsKey;
        final ToolActivity activity;
        final boolean hideFromProgress;
        final String preflightError;
        final String admissionReason;
        final AtomicReference<ExecutionState> executionState = new AtomicReference<>(ExecutionState.NEW);
        volatile boolean activityStarted;
        volatile CoreToolResult returnedResult;
        volatile Thread executionThread;
        volatile boolean cancelAfterResult;
        CoreToolResult result;
        String skippedReason;
        volatile Future<CoreToolResult> future;
        int repeatedFailures;
        boolean loopRecorded;
        boolean finalized;

        void discardSourceArguments() {
            if (sourceSnapshot != null) { sourceSnapshot.discard(); arguments = null; }
        }
        boolean markRunning() { return executionState.compareAndSet(ExecutionState.NEW, ExecutionState.RUNNING); }
        boolean markCancelledBeforeStart() {
            boolean cancelled = executionState.compareAndSet(ExecutionState.NEW, ExecutionState.CANCELLED);
            if (cancelled) discardSourceArguments();
            return cancelled;
        }
        boolean executionStarted() { return executionState.get() == ExecutionState.RUNNING; }

        PreparedToolCall(int index, ModelReply.Call call, CoreTool tool, Map<String, Object> arguments,
                         String argumentsKey, ToolActivity activity, boolean hideFromProgress,
                         String preflightError, String admissionReason, boolean captureSource, Map<String,Object> rawSnapshot) {
            this.index = index;
            this.call = call;
            this.tool = tool;
            this.sourceSnapshot = captureSource && preflightError == null && admissionReason == null && tool != null
                    ? new PreparedInvocationSnapshot(call, tool, rawSnapshot, arguments) : null;
            this.arguments = sourceSnapshot == null ? arguments : sourceSnapshot.arguments();
            this.argumentsKey = argumentsKey;
            this.activity = activity;
            this.hideFromProgress = hideFromProgress;
            this.preflightError = preflightError;
            this.admissionReason = admissionReason;
        }
    }

    private enum ExecutionState { NEW, RUNNING, CANCELLED }

    private static final class ToolBatchOutcome {
        int remainingResultChars;
        int executedCalls;
        String terminalText;
        boolean allCallsTerminal;
        boolean noProgress;
        boolean steeringReceived;
    }
    static final String TIMEOUT_MESSAGE = "The requested deadline was reached. Progress may be partial; inspect existing results before continuing.";
    static final String LOOP_MESSAGE = "Jarvys paused after repeated calls without progress. Inspect existing results before continuing.";
    static final String PROVIDER_MESSAGE = "The provider request ended without a complete response. Progress is retained. Inspect existing results before continuing.";
    private final Model model;
    private final CoreToolRegistry tools;
    /** Read-only actual dispatch declaration, never a model-supplied source or a permission. */
    boolean registeredToolHasSource(String name,String source) {
        CoreTool tool=tools.get(name);
        return tool!=null && name.equals(tool.declaration().name) && source.equals(tool.declaration().source);
    }
    private final CorePromptBudget budget;
    private final String instructions;
    private final ConversationCompactor compactor;
    private final ToolResultBudget toolResultBudget;
    private final TurnContextProvider turnContextProvider;
    private final RateLimitWaiter rateLimitWaiter;
    private volatile List<ConversationTurn> transcriptSnapshot = Collections.emptyList();
    private final Set<String> appliedIncomingIds = new LinkedHashSet<>();
    private final Map<String, String> toolLifecycle = new LinkedHashMap<>();
    private final Map<String, CrewInputIncorporation> incorporations = new LinkedHashMap<>();
    private final List<IncomingMessage> pendingDurableMessages = new ArrayList<>();
    private final Set<String> pendingDurableIds = new HashSet<>();
    private final ConcurrentLinkedQueue<IncomingMessage> offeredDurableMessages = new ConcurrentLinkedQueue<>();
    private final Set<String> offeredDurableIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean runActive = new AtomicBoolean(false);
    private final List<String> pendingTrustedMessages = new ArrayList<>();
    private final List<String> pendingLegacyMessages = new ArrayList<>();
    private volatile String checkpointCycleId = "";
    private ConversationTurn protectedGenuineUser;
    private volatile Checkpoint checkpoint = Checkpoint.empty();
    private volatile CheckpointListener checkpointListener;

    /**
     * A persistence failure is fatal even if the run is concurrently cancelled or times out.
     */
    public static final class CheckpointFailure extends IllegalStateException {

        CheckpointFailure(RuntimeException cause) {
            super("Tool transcript checkpoint could not be saved; execution stopped before further effects", cause);
        }
    }

    public static final class Checkpoint {
        public final List<ConversationTurn> transcript;
        public final Set<String> appliedIncomingIds;
        public final Map<String, String> toolLifecycle;
        public final int genuineUserIndex;
        public final Map<String, CrewInputIncorporation> incorporations;
        /** Complete tool outputs supplied only to checkpoint persistence before transcript budgeting. */
        public final Map<String, String> completedToolResults;

        public Checkpoint(List<ConversationTurn> transcript, Set<String> appliedIncomingIds, Map<String, String> toolLifecycle, int genuineUserIndex) {
            this(transcript, appliedIncomingIds, toolLifecycle, genuineUserIndex, Collections.emptyMap());
        }

        public Checkpoint(List<ConversationTurn> transcript, Set<String> appliedIncomingIds, Map<String, String> toolLifecycle,
                int genuineUserIndex, Map<String, CrewInputIncorporation> incorporations) {
            this(transcript, appliedIncomingIds, toolLifecycle, genuineUserIndex, incorporations, Collections.emptyMap());
        }

        Checkpoint(List<ConversationTurn> transcript, Set<String> appliedIncomingIds, Map<String, String> toolLifecycle,
                int genuineUserIndex, Map<String, CrewInputIncorporation> incorporations,
                Map<String, String> completedToolResults) {
            Map<String, CrewInputIncorporation> copy = new LinkedHashMap<>();
            for (Map.Entry<String, CrewInputIncorporation> entry : incorporations.entrySet()) {
                if (entry.getValue() == null || !entry.getValue().binding.mailboxMessageId.equals(entry.getKey())
                        || !appliedIncomingIds.contains(entry.getKey()))
                    throw new IllegalArgumentException("Input incorporation is not an applied mailbox message");
                copy.put(entry.getKey(), entry.getValue());
            }
            this.incorporations = Collections.unmodifiableMap(copy);
            this.completedToolResults = Collections.unmodifiableMap(new LinkedHashMap<>(completedToolResults));
            this.transcript = Collections.unmodifiableList(new ArrayList<>(transcript));
            this.appliedIncomingIds = Collections.unmodifiableSet(new LinkedHashSet<>(appliedIncomingIds));
            this.toolLifecycle = Collections.unmodifiableMap(new LinkedHashMap<>(toolLifecycle));
            if (genuineUserIndex < -1 || genuineUserIndex >= transcript.size()) {
                throw new IllegalArgumentException("Invalid protected user checkpoint index");
            }
            if (genuineUserIndex >= 0 && (transcript.get(genuineUserIndex).kind != ConversationTurn.Kind.MESSAGE || !"user".equals(transcript.get(genuineUserIndex).role))) {
                throw new IllegalArgumentException("Protected checkpoint content must be a genuine user message");
            }
            this.genuineUserIndex = genuineUserIndex;
        }

        public static Checkpoint empty() {
            return new Checkpoint(Collections.emptyList(), Collections.emptySet(), Collections.emptyMap(), -1);
        }

        /**
         * Reconcile missing results as observations, never replay pending or uncertain effects.
         */
        public Checkpoint reconciled() {
            List<ConversationTurn> restored = new ArrayList<>();
            Map<String, String> states = new LinkedHashMap<>(toolLifecycle);
            Set<String> seen = new HashSet<>();
            ConversationTurn genuine = genuineUserIndex < 0 ? null : transcript.get(genuineUserIndex);
            for (int index = 0; index < transcript.size(); index++) {
                ConversationTurn turn = transcript.get(index);
                if (turn.kind == ConversationTurn.Kind.TOOL_RESULT) {
                    throw new IllegalStateException("Checkpoint has an orphan tool result");
                }
                restored.add(turn);
                if (turn.kind != ConversationTurn.Kind.TOOL_CALLS) continue;
                Map<String, ModelReply.Call> missing = new LinkedHashMap<>();
                for (ModelReply.Call call : turn.toolCalls) {
                    if (call.id == null || call.id.isEmpty() || !seen.add(call.id)) {
                        throw new IllegalStateException("Checkpoint has duplicate or missing tool call identity");
                    }
                    missing.put(call.id, call);
                }
                while (index + 1 < transcript.size() && transcript.get(index + 1).kind == ConversationTurn.Kind.TOOL_RESULT) {
                    ConversationTurn result = transcript.get(++index);
                    ModelReply.Call call = missing.remove(result.toolCallId);
                    if (call == null || !call.name.equals(result.toolName)) {
                        throw new IllegalStateException("Checkpoint has an unmatched tool result");
                    }
                    restored.add(result);
                    String state = states.get(call.id);
                    if (!"NEVER_LAUNCHED".equals(state) && !"INTERRUPTED_UNCERTAIN".equals(state)) states.put(call.id, "RESULT");
                }
                for (ModelReply.Call call : missing.values()) {
                    String state = states.get(call.id);
                    String recovery = "INTENT".equals(state) || "NEVER_LAUNCHED".equals(state) ? "NEVER_LAUNCHED" : "INTERRUPTED_UNCERTAIN";
                    states.put(call.id, recovery);
                    restored.add(ConversationTurn.toolResult(call.id, call.name, recovery + ": " + ("NEVER_LAUNCHED".equals(recovery) ? "The recorded tool intent was never launched." : "Tool execution was interrupted before its result was durably recorded; effects may have occurred.") + " It was not automatically replayed. Inspect current evidence before any explicit continuation."));
                }
            }
            return new Checkpoint(restored, appliedIncomingIds, states, genuine == null ? -1 : restored.indexOf(genuine),
                    incorporations, completedToolResults);
        }
    }

    /** Reinstalled for every Crew run invocation, including reused loops. Empty is generic/no proof. */
    public void setCheckpointScope(String cycleId) {
        if (cycleId == null || (!cycleId.isEmpty() && !UUID.fromString(cycleId).toString().equals(cycleId)))
            throw new IllegalArgumentException("Invalid checkpoint cycle identity");
        checkpointCycleId = cycleId;
    }

    public void setCheckpointListener(CheckpointListener listener) {
        checkpointListener = listener;
    }

    public Checkpoint checkpointSnapshot() {
        return checkpoint;
    }

    public void restoreCheckpoint(Checkpoint restored) {
        if (restored == null) throw new IllegalArgumentException("A checkpoint is required");
        Checkpoint safe = restored.reconciled();
        appliedIncomingIds.clear();
        appliedIncomingIds.addAll(safe.appliedIncomingIds);
        toolLifecycle.clear();
        toolLifecycle.putAll(safe.toolLifecycle);
        incorporations.clear();
        incorporations.putAll(safe.incorporations);
        protectedGenuineUser = safe.genuineUserIndex < 0 ? null : safe.transcript.get(safe.genuineUserIndex);
        if (compactor != null) {
            if (protectedGenuineUser != null) compactor.protectGenuineUser(protectedGenuineUser);
            for (ConversationTurn turn : safe.transcript) {
                if (turn.kind == ConversationTurn.Kind.COMPACTION_SUMMARY) compactor.restoreSummaryCount(turn.summarizedMessageCount);
            }
        }
        transcriptSnapshot = safe.transcript;
        checkpoint = safe;
    }

    public List<ConversationTurn> transcriptSnapshot() {
        return checkpoint.reconciled().transcript;
    }

    private void updateTranscriptSnapshot(List<ConversationTurn> transcript) {
        updateTranscriptSnapshot(transcript, Collections.emptyMap());
    }

    private void updateTranscriptSnapshot(List<ConversationTurn> transcript, Map<String, String> completedToolResults) {
        updateTranscriptSnapshot(transcript, completedToolResults, null);
    }

    private void updateTranscriptSnapshot(List<ConversationTurn> transcript, Map<String, String> completedToolResults,
                                          PreparedInvocationSnapshot prepared) {
        for (ConversationTurn turn : transcript) {
            if (turn.kind == ConversationTurn.Kind.TOOL_CALLS) {
                for (ModelReply.Call call : turn.toolCalls) {
                    if (!toolLifecycle.containsKey(call.id)) toolLifecycle.put(call.id, "INTENT");
                }
            }
            if (turn.kind == ConversationTurn.Kind.TOOL_RESULT) {
                String state = toolLifecycle.get(turn.toolCallId);
                if (!"NEVER_LAUNCHED".equals(state) && !"INTERRUPTED_UNCERTAIN".equals(state)) toolLifecycle.put(turn.toolCallId, "RESULT");
            }
        }
        transcriptSnapshot = Collections.unmodifiableList(new ArrayList<>(transcript));
        checkpoint = new Checkpoint(transcriptSnapshot, appliedIncomingIds, toolLifecycle,
                protectedGenuineUser == null ? -1 : transcript.indexOf(protectedGenuineUser), incorporations,
                completedToolResults);
        CheckpointListener listener = checkpointListener;
        if (listener != null) {
            try {
                if (prepared == null) listener.persist(checkpoint);
                else listener.persistStarted(checkpoint, prepared);
            } catch (CheckpointFailure failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new CheckpointFailure(failure);
            }
        }
    }

    public CoreAgentLoop(CoreAgentModel model, CoreToolRegistry tools, String instructions, String sessionId) {
        this(model, tools, instructions, sessionId, CorePromptBudget.standard());
    }

    public CoreAgentLoop(CoreAgentModel model, CoreToolRegistry tools, String instructions, String sessionId, CorePromptBudget budget) {
        this(model, tools, instructions, sessionId, budget, null);
    }

    public CoreAgentLoop(CoreAgentModel model, CoreToolRegistry tools, String instructions, String sessionId, CorePromptBudget budget, ConversationCompactor compactor) {
        this(model, tools, instructions, sessionId, budget, compactor, null);
    }

    public CoreAgentLoop(CoreAgentModel model, CoreToolRegistry tools, String instructions, String sessionId,
                         CorePromptBudget budget, ConversationCompactor compactor,
                         RateLimitWaiter rateLimitWaiter) {
        this(new Model(){

            @Override
            public ModelReply complete(List<ConversationTurn> transcript, String prompt, List<ToolSpec> declarations, CancellationToken token) {
                return model.complete(instructions, transcript, prompt, declarations, token);
            }

            @Override
            public void onEmptyReply(ModelReply reply) {
                model.recordEmptyReply(reply);
            }

            @Override
            public int contextWindow(CancellationToken token) {
                return model.contextWindow(token);
            }
        }, tools, instructions, sessionId, budget, compactor, ToolResultBudget.BOUNDED, null,
                rateLimitWaiter);
    }

    public CoreAgentLoop(Model model, CoreToolRegistry tools, String instructions, String sessionId) {
        this(model, tools, instructions, sessionId, CorePromptBudget.standard());
    }

    public CoreAgentLoop(Model model, CoreToolRegistry tools, String instructions, String sessionId, CorePromptBudget budget) {
        this(model, tools, instructions, sessionId, budget, null);
    }

    public CoreAgentLoop(Model model, CoreToolRegistry tools, String instructions, String sessionId, CorePromptBudget budget, ConversationCompactor compactor) {
        this.model = model;
        this.tools = tools;
        this.budget = budget;
        this.instructions = instructions == null ? "" : instructions;
        this.compactor = compactor;
        this.toolResultBudget = ToolResultBudget.BOUNDED;
        this.turnContextProvider = null;
        this.rateLimitWaiter = null;
    }

    public CoreAgentLoop(Model model, CoreToolRegistry tools, String instructions, String sessionId,
                         CorePromptBudget budget, ConversationCompactor compactor,
                         RateLimitWaiter rateLimitWaiter) {
        this(model, tools, instructions, sessionId, budget, compactor, ToolResultBudget.BOUNDED,
                null, rateLimitWaiter);
    }

    public CoreAgentLoop(Model model, CoreToolRegistry tools, String instructions, String sessionId, CorePromptBudget budget, ConversationCompactor compactor, ToolResultBudget toolResultBudget, TurnContextProvider turnContextProvider, RateLimitWaiter rateLimitWaiter) {
        this.model = model;
        this.tools = tools;
        this.budget = budget;
        this.instructions = instructions == null ? "" : instructions;
        this.compactor = compactor;
        this.toolResultBudget = toolResultBudget == null ? ToolResultBudget.BOUNDED : toolResultBudget;
        this.turnContextProvider = turnContextProvider;
        this.rateLimitWaiter = rateLimitWaiter;
    }

    public Result run(String request, List<ConversationTurn> previousTranscript, CancellationToken token, ProgressListener listener) {
        return run(request, previousTranscript, Collections.emptyList(), token, listener, null);
    }

    public Result run(String request, List<ConversationTurn> previousTranscript, List<ChatAttachment> attachments, CancellationToken token, ProgressListener listener) {
        return run(request, previousTranscript, attachments, token, listener, null);
    }

    public Result run(String request, List<ConversationTurn> previousTranscript, List<ChatAttachment> attachments,
                      CancellationToken token, ProgressListener listener, String currentUserMessageId) {
        return run(request, previousTranscript, attachments, token, listener, currentUserMessageId,
                UUID.randomUUID().toString());
    }

    public Result run(String request, List<ConversationTurn> previousTranscript, List<ChatAttachment> attachments,
                      CancellationToken token, ProgressListener listener, String currentUserMessageId, String runId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("Run identity is required");
        if (!runActive.compareAndSet(false, true))
            throw new IllegalStateException("This agent loop is already running");
        try {
            if (turnContextProvider != null) turnContextProvider.onRunStarted();
            return runActive(request, previousTranscript, attachments, token, listener, currentUserMessageId, runId);
        } finally {
            runActive.set(false);
        }
    }

    /**
     * Offers a durable input directly to this running loop. The caller persists the source first;
     * acceptance only means this loop can incorporate it at its next model boundary.
     */
    public boolean offerIncoming(IncomingMessage message) {
        if (message == null || message.id == null || message.id.isEmpty() || !runActive.get()) return false;
        if (!offeredDurableIds.add(message.id)) return true;
        offeredDurableMessages.add(message);
        return true;
    }

    private Result runActive(String request, List<ConversationTurn> previousTranscript,
            List<ChatAttachment> attachments, CancellationToken token, ProgressListener listener,
            String currentUserMessageId, String runId) {
        List<ChatAttachment> currentAttachments = attachments == null ? Collections.emptyList() : attachments;
        boolean transcriptHasPendingInput = ConversationTurn.hasCurrentUserMessage(previousTranscript)
                || ConversationTurn.hasInternalConversationInput(previousTranscript)
                || ConversationTurn.endsWithToolResult(previousTranscript)
                || containsSystemEvent(previousTranscript);
        if ((request == null || request.trim().isEmpty()) && currentAttachments.isEmpty()
                && turnContextProvider == null && !transcriptHasPendingInput) {
            throw new IllegalArgumentException("A user request is required");
        }
        request = request == null ? "" : request;
        Map<String, ToolActivity> activeActivities = new java.util.concurrent.ConcurrentHashMap<>();
        List<ConversationTurn> transcript = new ArrayList<>(previousTranscript == null ? Collections.emptyList() : previousTranscript);
        boolean hasRunRequest = !request.trim().isEmpty() || !currentAttachments.isEmpty();
        boolean systemEventInput = containsSystemEvent(transcript);
        boolean currentRequestAlreadyInTranscript = currentUserMessageId != null && !currentUserMessageId.isEmpty()
                && transcript.stream().anyMatch(turn -> turn.kind == ConversationTurn.Kind.MESSAGE
                        && "user".equals(turn.role) && currentUserMessageId.equals(turn.durableMessageId));
        boolean internalInput = ConversationTurn.hasInternalConversationInput(transcript);
        boolean requestInTranscript = currentRequestAlreadyInTranscript || systemEventInput || internalInput || !currentAttachments.isEmpty()
                || (turnContextProvider != null && !request.trim().isEmpty());
        if (requestInTranscript && hasRunRequest && !currentRequestAlreadyInTranscript)
            transcript.add(ConversationTurn.messageWithAttachments("user", request,
                    compactor == null ? -1 : compactor.currentUserMessageIndex(), currentAttachments));
        updateTranscriptSnapshot(transcript);
        String prompt = requestInTranscript ? "" : request.trim();
        boolean modelHasInput = hasRunRequest || ConversationTurn.hasCurrentUserMessage(transcript)
                || internalInput
                || ConversationTurn.endsWithToolResult(transcript) || systemEventInput;
        int modelTurns = 0;
        long startedAtMs = System.currentTimeMillis();
        int overflowCompactions = 0;
        boolean truncateToolResultsForProvider = false;
        ToolLoopDetector loopDetector = new ToolLoopDetector();
        try {
            boolean initialUserMessageReported = false;
            boolean initialInternalInputsReported = false;
            while (true) {
                token.throwIfCancelled();
                List<ToolSpec> declarations = tools.declarations();
                if (drainTurnContext(transcript, false, listener, null, "")) {
                    requestInTranscript = true;
                    modelHasInput = true;
                }
                updateTranscriptSnapshot(transcript);
                if (!initialUserMessageReported && requestInTranscript && hasRunRequest && listener != null) {
                    listener.onInitialUserMessageIncorporated();
                    initialUserMessageReported = true;
                }
                if (!modelHasInput) {
                    if (drainTurnContext(transcript, true, listener, null, "")) {
                        requestInTranscript = true;
                        modelHasInput = true;
                        prompt = "";
                        continue;
                    }
                    return new Result(runId, "", modelTurns, "COMPLETED", Math.max(0L, System.currentTimeMillis() - startedAtMs));
                }
                if (compactor != null && !truncateToolResultsForProvider && !model.usesPreparedRequests()) {
                    try {
                        int window = model.contextWindow(token);
                        int estimate = estimateContext(transcript, prompt, declarations);
                        if (ConversationCompactionPolicy.shouldCompact(estimate, window)) {
                            ConversationCompactor.Outcome outcome = compact(transcript, window, "auto_preflight", ConversationCompactionPolicy.Mode.SLIDING_WINDOW, prompt, token, listener);
                            if (outcome != null) {
                                transcript = new ArrayList<>(outcome.context);
                                truncateToolResultsForProvider = false;
                                updateTranscriptSnapshot(transcript);
                            }
                        }
                    } catch (RuntimeException failure) {
                        if (failure instanceof CheckpointFailure || token.isCancelled()) throw failure;
                        if (compactor.isCrew()) throw new IllegalStateException("Crew context could not be compacted; its retained transcript has not been discarded", failure);
                        truncateToolResultsForProvider = true;
                        compactionFailed(listener, "No se pudo compactar; se reducirán los resultados de herramientas según el presupuesto existente.");
                    }
                }
                List<ConversationTurn> requestTranscript = truncateToolResultsForProvider && !model.usesPreparedRequests()
                        ? truncateToolResultsOnly(transcript, budget, toolResultBudget) : transcript;
                if (!initialInternalInputsReported && listener != null) {
                    List<IncomingMessage> initialInternal = internalMessagesIn(requestTranscript);
                    if (!initialInternal.isEmpty()) listener.onInitialInternalInputsIncorporated(initialInternal);
                    initialInternalInputsReported = true;
                }
                String turnPrompt = prompt;
                ModelReply reply;
                try {
                    long retryNumber = 0;
                    while (true) {
                        try {
                            emit(listener, "model_wait", "Esperando respuesta del modelo");
                            if (listener != null) listener.onAssistantTextStreamStarted(runId);
                            ModelProviderClient.TextDeltaListener stream = delta -> {
                                if (listener != null && delta != null && !delta.isEmpty())
                                    listener.onAssistantTextDelta(runId, delta);
                            };
                            if (model.usesPreparedRequests()) {
                                PreparedModelRequest prepared = model.prepareRequest(requestTranscript, turnPrompt, declarations, token, true);
                                if (ConversationCompactionPolicy.shouldCompact(prepared.estimateTokens(), prepared.contextWindow)
                                        && compactor != null) {
                                    ConversationCompactor.Outcome compacted = compactPrepared(transcript, prepared, token, listener);
                                    if (compacted != null) {
                                        transcript = new ArrayList<>(compacted.context);
                                        requestTranscript = transcript;
                                        updateTranscriptSnapshot(transcript);
                                        prepared = model.prepareRequest(requestTranscript, turnPrompt, declarations, token);
                                    }
                                }
                                prepared.requireFits(token);
                                reply = model.completePrepared(prepared, token, stream);
                            } else {
                                reply = model.complete(requestTranscript, turnPrompt, declarations, token, stream);
                            }
                            emit(listener, "model_response", "Respuesta del modelo recibida");
                            break;
                        } catch (RuntimeException failure) {
                            if (failure instanceof CheckpointFailure) throw failure;
                            token.throwIfCancelled();
                            if (!retryableProviderFailure(failure)) throw failure;
                            transcript.add(ConversationTurn.modelError(providerErrorCode(failure)));
                            updateTranscriptSnapshot(transcript);
                            if (rateLimitWaiter == null) throw failure;
                            boolean rateLimited = hasRateLimitCause(failure);
                            if (rateLimited && turnContextProvider != null) turnContextProvider.onRateLimit(true);
                            long currentRetry = retryNumber;
                            if (retryNumber < Long.MAX_VALUE) retryNumber++;
                            try {
                                rateLimitWaiter.await(failure, currentRetry, token);
                            } finally {
                                if (rateLimited && turnContextProvider != null) turnContextProvider.onRateLimit(false);
                            }
                        }
                    }
                } catch (RuntimeException failure) {
                    if (failure instanceof CheckpointFailure) throw failure;
                    token.throwIfCancelled();
                    if (recoverableProviderFailure(failure)) {
                        // Never replay an ambiguous request or cancel independent work merely because
                        // one bounded provider operation failed. Retain this turn for explicit recovery.
                        closeTurnContextProvider();
                        return new Result(runId, PROVIDER_MESSAGE, modelTurns, "PARTIAL",
                                Math.max(0L, System.currentTimeMillis() - startedAtMs), InterruptionReason.PROVIDER_UNAVAILABLE, failure);
                    }
                    if (compactor == null || !ConversationCompactionPolicy.isContextOverflow(failure) || overflowCompactions >= 3) throw failure;
                    token.throwIfCancelled();
                    if (model.usesPreparedRequests()) {
                        PreparedModelRequest overflowRequest = model.prepareRequest(transcript, prompt, declarations, token);
                        ConversationCompactor.Outcome safe = compactPrepared(transcript, overflowRequest, token, listener);
                        if (safe == null) throw failure;
                        transcript = new ArrayList<>(safe.context);
                        updateTranscriptSnapshot(transcript);
                        overflowCompactions++;
                        continue;
                    }
                    int window = model.contextWindow(token);
                    try {
                        ConversationCompactor.Outcome outcome = compact(transcript, window, "overflow", ConversationCompactionPolicy.Mode.SLIDING_WINDOW, prompt, token, listener);
                        if (outcome == null) throw failure;
                        transcript = new ArrayList<>(outcome.context);
                        updateTranscriptSnapshot(transcript);
                    } catch (RuntimeException compactionFailure) {
                        if (compactionFailure instanceof CheckpointFailure || token.isCancelled()) throw compactionFailure;
                        if (compactor.isCrew()) throw new IllegalStateException("Crew context overflow could not be compacted; its retained transcript has not been discarded", compactionFailure);
                        compactionFailed(listener, "No se pudo resumir el historial; se reducirán los resultados de herramientas según el presupuesto existente.");
                        truncateToolResultsForProvider = true;
                    }
                    overflowCompactions++;
                    continue;
                }
                modelTurns++;
                overflowCompactions = 0;
                token.throwIfCancelled();
                if (reply == null) throw new IllegalStateException("Provider returned no response");
                if (reply.calls.isEmpty()) {
                    String answer = reply.text == null ? "" : reply.text.trim();
                    if (answer.isEmpty()) {
                        try { model.onEmptyReply(reply); } catch (Throwable ignored) { }
                        if (!requestInTranscript && !request.trim().isEmpty()) {
                            transcript.add(new ConversationTurn("user", request.trim(), compactor == null ? -1 : compactor.currentUserMessageIndex()));
                        }
                        transcript.add(ConversationTurn.assistantReply("", reply.providerOutputItems));
                        updateTranscriptSnapshot(transcript, Collections.emptyMap());
                        if (drainTurnContext(transcript, true, listener, null, "")) {
                            requestInTranscript = true;
                            modelHasInput = true;
                            prompt = "";
                            continue;
                        }
                        return new Result(runId, "", modelTurns, "COMPLETED", Math.max(0L, System.currentTimeMillis() - startedAtMs));
                    }
                    if (turnContextProvider != null || checkpointListener != null) {
                        if (!requestInTranscript && !request.trim().isEmpty()) transcript.add(new ConversationTurn("user", request.trim()));
                        transcript.add(ConversationTurn.assistantReply(answer, reply.providerOutputItems));
                        updateTranscriptSnapshot(transcript, Collections.emptyMap());
                    }
                    if (drainTurnContext(transcript, true, listener, answer, runId)) {
                        requestInTranscript = true;
                        modelHasInput = true;
                        prompt = "";
                        continue;
                    }
                    emit(listener, "answer", answer);
                    return new Result(runId, answer, modelTurns, "COMPLETED", Math.max(0L, System.currentTimeMillis() - startedAtMs));
                }
                if (!requestInTranscript && !request.trim().isEmpty()) {
                    transcript.add(new ConversationTurn("user", request.trim(), compactor == null ? -1 : compactor.currentUserMessageIndex()));
                    requestInTranscript = true;
                }
                transcript.add(ConversationTurn.toolCalls(reply.text, reply.calls, reply.providerOutputItems));
                updateTranscriptSnapshot(transcript, Collections.emptyMap());
                if (listener != null && reply.text != null && !reply.text.trim().isEmpty()) {
                    token.throwIfCancelled();
                    listener.onAssistantProgress(runId, reply.calls.get(0).id, reply.text.trim());
                }
                if (listener != null) listener.onAssistantTextStreamEnded(runId);
                ToolBatchOutcome batch = executeToolBatch(reply.calls, reply, runId, modelTurns, tools,
                        transcript, requestTranscript, token, listener, loopDetector, activeActivities);
                if (batch.noProgress) {
                    closeTurnContextProvider();
                    return new Result(runId, LOOP_MESSAGE, modelTurns, "PARTIAL",
                            Math.max(0L, System.currentTimeMillis() - startedAtMs), InterruptionReason.NO_PROGRESS);
                }
                if (batch.executedCalls > 0 && batch.allCallsTerminal && batch.terminalText != null
                        && !batch.steeringReceived) {
                    if (drainTurnContext(transcript, true, listener, batch.terminalText, runId)) {
                        requestInTranscript = true;
                        modelHasInput = true;
                        prompt = "";
                        continue;
                    }
                    emit(listener, "answer", batch.terminalText);
                    return new Result(runId, batch.terminalText, modelTurns, "COMPLETED", Math.max(0L, System.currentTimeMillis() - startedAtMs));
                }
                prompt = "";
            }
        } catch (RuntimeException failure) {
            closeTurnContextProvider();
            if (listener != null) for (ToolActivity activeActivity : activeActivities.values()) {
                try { listener.onToolActivity(activeActivity.event("tool_interrupted", activeActivity.detail, activeActivity.previewId)); }
                catch (RuntimeException presentationFailure) { failure.addSuppressed(presentationFailure); }
            }
            if (failure instanceof CheckpointFailure) throw failure;
            if (token.isStoppedByUser()) throw failure;
            if (token.isTimedOut()) {
                Thread.interrupted();
                return new Result(runId, TIMEOUT_MESSAGE, modelTurns, "PARTIAL", Math.max(0L, System.currentTimeMillis() - startedAtMs), InterruptionReason.DEADLINE);
            }
            if (recoverableProviderFailure(failure)) {
                return new Result(runId, PROVIDER_MESSAGE, modelTurns, "PARTIAL",
                        Math.max(0L, System.currentTimeMillis() - startedAtMs), InterruptionReason.PROVIDER_UNAVAILABLE, failure);
            }
            throw failure;
        }
    }

    private static boolean containsSystemEvent(List<ConversationTurn> transcript) {
        if (transcript == null) return false;
        for (ConversationTurn turn : transcript) {
            if (turn != null && turn.kind == ConversationTurn.Kind.SYSTEM_EVENT && "system".equals(turn.role)) return true;
        }
        return false;
    }

    private ToolBatchOutcome executeToolBatch(List<ModelReply.Call> calls, ModelReply reply,
            String runId, int modelTurns, CoreToolRegistry registry, List<ConversationTurn> transcript,
            List<ConversationTurn> sentTranscript, CancellationToken token, ProgressListener listener, ToolLoopDetector loopDetector,
            Map<String, ToolActivity> activeActivities) {
        List<PreparedToolCall> prepared = new ArrayList<>();
        boolean sequential = false;
        Set<CoreTool> oneCallPerResponse = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int index = 0; index < calls.size(); index++) {
            ModelReply.Call call = calls.get(index);
            CoreTool tool = registry.get(call.name);
            boolean hideFromProgress = tool != null && tool.hideFromProgress();
            ToolActivity activity = toolActivity(runId, modelTurns, index, call, registry);
            String preflightError = call.invalidArgumentsReason;
            String admissionReason = null;
            boolean captureSource = checkpointListener != null && checkpointListener.requiresPreparedInvocationSnapshot();
            Map<String,Object> rawArguments = captureSource ? PreparedInvocationSnapshot.freezeMap(call.arguments) : call.arguments;
            Map<String, Object> invokeArguments = rawArguments;
            if (admissionReason == null && preflightError == null && tool == null) {
                preflightError = "Tool is not available in this agent scope: " + call.name;
            } else if (admissionReason == null && preflightError == null) {
                // Native memory project presence must be checked before generic optional-null/string coercion.
                preflightError = PersonalMemoryTools.rawSaveProjectError(tool, rawArguments);
                if (preflightError == null) preflightError = PersonalMemoryTools.rawPromptSearchError(tool, rawArguments);
                if (preflightError == null) preflightError = PersonalMemoryTools.rawReviewError(tool, rawArguments);
                if (preflightError == null) preflightError = PersonalMemoryTools.rawJudgmentError(tool, rawArguments);
                if (preflightError == null) preflightError = MemoryConsolidationTool.rawError(tool, rawArguments);
                if (preflightError == null && (PersonalMemoryTools.isJudgment(tool) || MemoryConsolidationTool.matches(tool))) {
                    // Native judgment parser preserves per-item errors and raw semantics before generic coercion.
                    invokeArguments = rawArguments;
                } else if (preflightError == null) {
                    ToolArgumentValidator.Result validation = ToolArgumentValidator.coerceAndValidate(
                            PersonalMemoryTools.reviewArguments(tool, PersonalMemoryTools.contextArguments(tool, PersonalMemoryTools.promptSearchArguments(tool, rawArguments))), tool.declaration().jsonSchema());
                    invokeArguments = validation.arguments;
                    preflightError = validation.error == null ? null
                            : tool.preflightErrorMessage(call.arguments, validation.error);
                }
                if (preflightError == null && tool.responseCallPolicy() == CoreTool.ResponseCallPolicy.ONE_PER_RESPONSE) {
                    if (completeResultAlreadyVisible(tool, invokeArguments, sentTranscript)) {
                        admissionReason = tool.completeResultAlreadyVisibleMessage();
                    } else if (!oneCallPerResponse.add(tool)) {
                        admissionReason = tool.additionalCallNotStartedMessage();
                    }
                }
            }
            if (preflightError == null && tool.executionMode() == CoreTool.ExecutionMode.SEQUENTIAL)
                sequential = true;
            prepared.add(new PreparedToolCall(index, call, tool, invokeArguments,
                    loopDetector.argumentsKey(call), activity, hideFromProgress, preflightError, admissionReason,
                    captureSource, rawArguments));
        }

        try {
        ToolBatchOutcome outcome = new ToolBatchOutcome();
        outcome.allCallsTerminal = !prepared.isEmpty();
        // Results spend the shared turn budget in provider call order, never completion order.
        outcome.remainingResultChars = toolResultBudget.allowsCompleteToolResults()
                ? Integer.MAX_VALUE : budget.toolResultsPerTurnChars;
        if (sequential) {
            try {
            boolean executionStarted = false;
            for (int index = 0; index < prepared.size(); index++) {
                token.throwIfCancelled();
                PreparedToolCall item = prepared.get(index);
                if (item.admissionReason != null) {
                    appendNotStarted(item, item.admissionReason, transcript, listener);
                    continue;
                }
                if (item.preflightError != null) {
                    appendPreflightError(item, transcript, listener, loopDetector, outcome);
                    if (item.repeatedFailures >= ToolLoopDetector.FAILURE_THRESHOLD) {
                        outcome.noProgress = true;
                        skipUnlaunched(prepared, index + 1, "Not started after repeated identical tool errors.");
                        break;
                    }
                    continue;
                }
                if (executionStarted && collectTurnContext()) {
                    outcome.steeringReceived = true;
                    skipUnlaunched(prepared, index, "Skipped because new user input arrived before launch.");
                    break;
                }
                token.throwIfCancelled();
                if (!startPreparedTool(item, transcript, token, listener, activeActivities)) {
                    token.throwIfCancelled();
                    continue;
                }
                token.throwIfCancelled();
                executionStarted = true;
                try {
                    item.result = invokePreparedTool(item, registry, token, listener);
                } catch (CancellationException cancelled) {
                    if (token.isCancellationRequested()) throw cancelled;
                    item.result = cancelledToolResult(cancelled);
                } catch (RuntimeException failure) {
                    throw failure;
                }
                try {
                    appendExecutedResult(item, transcript, token, listener, loopDetector, outcome);
                } finally {
                    activeActivities.remove(item.call.id);
                }
                token.throwIfCancelled();
                outcome.executedCalls++;
                item.repeatedFailures = loopDetector.recordOutcome(item.call.name, item.argumentsKey,
                        item.result.success, item.result.content);
                item.loopRecorded = true;
                if (item.result.finishRun && item.result.success) outcome.terminalText = item.result.content;
                if (item.repeatedFailures >= ToolLoopDetector.FAILURE_THRESHOLD) {
                    outcome.noProgress = true;
                    skipUnlaunched(prepared, index + 1, "Not started after repeated identical tool errors.");
                    break;
                }
            }
            } catch (RuntimeException failure) {
                if (failure instanceof CheckpointFailure) throw failure;
                try { finalizeInterruptedBatch(prepared, transcript, token, listener, loopDetector,
                        outcome, activeActivities); }
                catch (RuntimeException recoveryFailure) {
                    if (recoveryFailure instanceof CheckpointFailure) throw recoveryFailure;
                    failure.addSuppressed(recoveryFailure);
                }
                throw failure;
            }
        } else {
            Runnable cancelLaunched = () -> {
                for (PreparedToolCall item : prepared) cancelPreparedTool(item);
            };
            Runnable unregisterCancellation = token.registerCancelAction(cancelLaunched);
            try {
                for (int index = 0; index < prepared.size(); index++) {
                    token.throwIfCancelled();
                    PreparedToolCall item = prepared.get(index);
                    if (item.admissionReason != null) continue;
                    if (item.preflightError != null) {
                        item.result = CoreToolResult.failure(item.preflightError);
                        toolLifecycle.put(item.call.id, "NEVER_LAUNCHED");
                        continue;
                    }
                    token.throwIfCancelled();
                    if (!startPreparedTool(item, transcript, token, listener, activeActivities)) {
                        token.throwIfCancelled();
                        continue;
                    }
                    token.throwIfCancelled();
                    try {
                        Runnable nativeAdmission = NativeEffectDispatchScope.captureAdmission();
                        item.future = PARALLEL_TOOL_EXECUTOR.submit(() -> {
                            item.executionThread = Thread.currentThread();
                            try (NativeEffectDispatchScope dispatch = NativeEffectDispatchScope.enter(nativeAdmission)) {
                                token.throwIfCancelled();
                                CoreToolResult result = invokePreparedTool(item, registry, token, listener);
                                item.returnedResult = result;
                                if (item.cancelAfterResult && item.future != null) item.future.cancel(true);
                                return result;
                            } finally {
                                item.executionThread = null;
                            }
                        });
                        if (token.isCancellationRequested()) cancelPreparedTool(item);
                    } catch (RuntimeException submissionFailure) {
                        toolLifecycle.put(item.call.id, "NEVER_LAUNCHED");
                        activeActivities.remove(item.call.id);
                        throw submissionFailure;
                    }
                }
                for (PreparedToolCall item : prepared) {
                    if (item.future == null) continue;
                    try {
                        item.result = item.future.get();
                    } catch (CancellationException cancelled) {
                        if (item.returnedResult != null) item.result = item.returnedResult;
                        else if (token.isCancellationRequested()) throw cancelled;
                        else item.result = cancelledToolResult(cancelled);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        token.throwIfCancelled();
                        throw new CancellationException("Tool batch wait was interrupted");
                    } catch (ExecutionException failed) {
                        Throwable cause = failed.getCause();
                        if (cause instanceof CancellationException) {
                            if (token.isCancellationRequested()) throw (CancellationException) cause;
                            item.result = cancelledToolResult((CancellationException) cause);
                        } else if (cause instanceof RuntimeException) {
                            throw (RuntimeException) cause;
                        } else {
                            throw new IllegalStateException("Parallel tool execution failed", cause);
                        }
                    }
                }
                token.throwIfCancelled();
            } catch (RuntimeException failure) {
                cancelLaunched.run();
                if (failure instanceof CheckpointFailure) throw failure;
                try { finalizeInterruptedBatch(prepared, transcript, token, listener, loopDetector,
                        outcome, activeActivities); }
                catch (RuntimeException recoveryFailure) {
                    if (recoveryFailure instanceof CheckpointFailure) throw recoveryFailure;
                    failure.addSuppressed(recoveryFailure);
                }
                throw failure;
            } finally {
                unregisterCancellation.run();
            }
            for (PreparedToolCall item : prepared) {
                if (item.preflightError != null) {
                    item.repeatedFailures = loopDetector.recordOutcome(item.call.name, item.argumentsKey,
                            false, item.preflightError);
                    item.loopRecorded = true;
                } else if (item.result != null && item.executionStarted()) {
                    outcome.executedCalls++;
                    item.repeatedFailures = loopDetector.recordOutcome(item.call.name, item.argumentsKey,
                            item.result.success, item.result.content);
                    item.loopRecorded = true;
                    if (item.result.finishRun && item.result.success) outcome.terminalText = item.result.content;
                }
                if (item.repeatedFailures >= ToolLoopDetector.FAILURE_THRESHOLD) outcome.noProgress = true;
            }
        }

        try {
        // appendExecutedResult consumes the shared output budget while traversing provider call order.
        for (PreparedToolCall item : prepared) {
            if (item.finalized) continue;
            if (item.admissionReason != null) {
                appendNotStarted(item, item.admissionReason, transcript, listener);
                continue;
            }
            if (item.skippedReason != null) {
                toolLifecycle.put(item.call.id, "NEVER_LAUNCHED");
                transcript.add(ConversationTurn.toolResult(item.call.id, item.call.name, item.skippedReason));
                item.finalized = true;
                updateTranscriptSnapshot(transcript);
                if (listener != null) listener.onToolActivity(item.activity.event(
                        "tool_not_started", item.skippedReason, null));
                continue;
            }
            if (item.preflightError != null) {
                toolLifecycle.put(item.call.id, "NEVER_LAUNCHED");
                String content = "Tool error: " + item.preflightError;
                transcript.add(ConversationTurn.toolResult(item.call.id, item.call.name, content));
                item.finalized = true;
                updateTranscriptSnapshot(transcript);
                if (listener != null) listener.onToolActivity(item.activity.event("tool_not_started", content, null));
                if (!item.loopRecorded && loopDetector.recordOutcome(item.call.name, item.argumentsKey,
                        false, item.preflightError) >= ToolLoopDetector.FAILURE_THRESHOLD) outcome.noProgress = true;
                continue;
            }
            if (item.result == null || !item.executionStarted()) continue;
            appendExecutedResult(item, transcript, token, listener, loopDetector, outcome);
            activeActivities.remove(item.call.id);
        }
        if (!token.isCancellationRequested() && collectTurnContext()) outcome.steeringReceived = true;
        } catch (RuntimeException failure) {
            if (failure instanceof CheckpointFailure) throw failure;
            try { finalizeInterruptedBatch(prepared, transcript, token, listener, loopDetector,
                    outcome, activeActivities); }
            catch (RuntimeException recoveryFailure) {
                if (recoveryFailure instanceof CheckpointFailure) throw recoveryFailure;
                failure.addSuppressed(recoveryFailure);
            }
            throw failure;
        }
        for (PreparedToolCall item : prepared) {
            outcome.allCallsTerminal &= item.finalized && item.result != null
                    && item.result.success && item.result.finishRun;
        }
        return outcome;
        } finally {
            for (PreparedToolCall item : prepared) item.discardSourceArguments();
        }
    }

    private static CoreToolResult cancelledToolResult(CancellationException cancelled) {
        String detail = cancelled.getMessage();
        return CoreToolResult.failure(detail == null || detail.isEmpty()
                ? cancelled.getClass().getSimpleName() : detail);
    }

    static boolean completeResultAlreadyVisible(CoreTool tool, Map<String, Object> arguments,
            List<ConversationTurn> sentTranscript) {
        String identity = tool.completeResultIdentity(arguments);
        if (identity == null || identity.isEmpty() || sentTranscript == null || sentTranscript.isEmpty()) return false;
        Map<String, ModelReply.Call> priorCalls = new LinkedHashMap<>();
        Map<String, String> priorResults = new LinkedHashMap<>();
        String toolName = tool.declaration().name;
        for (ConversationTurn turn : sentTranscript) {
            if (turn.kind == ConversationTurn.Kind.TOOL_CALLS) {
                for (ModelReply.Call call : turn.toolCalls) {
                    if (toolName.equals(call.name)
                            && identity.equals(tool.completeResultIdentity(call.arguments))) {
                        priorCalls.put(call.id, call);
                    }
                }
            } else if (turn.kind == ConversationTurn.Kind.TOOL_RESULT
                    && toolName.equals(turn.toolName) && priorCalls.containsKey(turn.toolCallId)) {
                priorResults.putIfAbsent(turn.toolCallId, turn.content);
            }
        }
        for (Map.Entry<String, String> result : priorResults.entrySet()) {
            ModelReply.Call prior = priorCalls.get(result.getKey());
            if (prior != null && tool.isCompleteResult(prior.arguments, result.getValue())) return true;
        }
        return false;
    }

    private void appendNotStarted(PreparedToolCall item, String reason, List<ConversationTurn> transcript,
            ProgressListener listener) {
        toolLifecycle.put(item.call.id, "NEVER_LAUNCHED");
        transcript.add(ConversationTurn.toolResult(item.call.id, item.call.name, reason));
        item.finalized = true;
        updateTranscriptSnapshot(transcript);
        if (listener != null) listener.onToolActivity(item.activity.event("tool_not_started", reason, null));
    }

    private void appendPreflightError(PreparedToolCall item, List<ConversationTurn> transcript,
            ProgressListener listener, ToolLoopDetector loopDetector, ToolBatchOutcome outcome) {
        item.result = CoreToolResult.failure(item.preflightError);
        toolLifecycle.put(item.call.id, "NEVER_LAUNCHED");
        item.repeatedFailures = loopDetector.recordOutcome(item.call.name, item.argumentsKey,
                false, item.preflightError);
        item.loopRecorded = true;
        String content = "Tool error: " + item.preflightError;
        transcript.add(ConversationTurn.toolResult(item.call.id, item.call.name, content));
        item.finalized = true;
        updateTranscriptSnapshot(transcript);
        if (listener != null) listener.onToolActivity(item.activity.event("tool_not_started", content, null));
        if (item.repeatedFailures >= ToolLoopDetector.FAILURE_THRESHOLD) outcome.noProgress = true;
    }

    private boolean startPreparedTool(PreparedToolCall item, List<ConversationTurn> transcript,
            CancellationToken token, ProgressListener listener,
            Map<String, ToolActivity> activeActivities) {
        token.throwIfCancelled();
        if (!item.markRunning()) {
            toolLifecycle.put(item.call.id, "NEVER_LAUNCHED");
            return false;
        }
        toolLifecycle.put(item.call.id, "STARTED");
        try { updateTranscriptSnapshot(transcript, Collections.emptyMap(), item.sourceSnapshot); }
        catch (RuntimeException failure) { item.discardSourceArguments(); throw failure; }
        if (!item.hideFromProgress) {
            activeActivities.put(item.call.id, item.activity);
            // Mark before notifying the listener: it may cancel synchronously from tool_call.
            item.activityStarted = true;
            if (listener != null) listener.onToolActivity(item.activity);
        }
        return true;
    }

    private CoreToolResult invokePreparedTool(PreparedToolCall item, CoreToolRegistry registry,
            CancellationToken token, ProgressListener listener) {
        try { return registry.invoke(item.call.name, item.arguments, token, message -> {
            if (listener != null && !item.hideFromProgress && message != null && !message.isEmpty())
                listener.onToolActivity(item.activity.event("tool_progress", message, null));
        }, item.call.id, null, item.sourceSnapshot);
        } finally { item.discardSourceArguments(); }
    }

    private static void cancelPreparedTool(PreparedToolCall item) {
        item.discardSourceArguments();
        // A self-cancelling tool may call this before submit() has published its Future.
        // Let its runner publish the returned result before cancelling that FutureTask.
        if (item.cancelAfterResult) return;
        item.markCancelledBeforeStart();
        Future<CoreToolResult> future = item.future;
        if (future == null) {
            if (item.executionThread == Thread.currentThread()) {
                item.cancelAfterResult = true;
                Thread.currentThread().interrupt();
            }
            return;
        }
        if (item.executionThread == Thread.currentThread() && item.returnedResult == null) {
            // FutureTask.cancel(true) from its own tool would discard that tool's return value.
            item.cancelAfterResult = true;
            Thread.currentThread().interrupt();
            return;
        }
        future.cancel(true);
    }

    private void appendExecutedResult(PreparedToolCall item, List<ConversationTurn> transcript,
            CancellationToken token, ProgressListener listener, ToolLoopDetector loopDetector,
            ToolBatchOutcome outcome) {
        CoreToolResult result = item.result;
        String rawContent = result.content;
        RuntimeException invalidCompletedIds = null;
        List<String> completedIds = Collections.emptyList();
        if (turnContextProvider != null && result.success) {
            List<String> reportedIds = turnContextProvider.completedToolIncomingIds(item.call.name);
            if (reportedIds != null) {
                completedIds = reportedIds;
                for (String id : completedIds) {
                    if (id == null || id.isEmpty()) {
                        invalidCompletedIds = new IllegalStateException("Invalid completed tool inbox ID");
                        completedIds = Collections.emptyList();
                        break;
                    }
                }
            }
        }
        for (String id : completedIds) appliedIncomingIds.add(id);
        String content = rawContent;
        boolean crewCompaction = compactor != null && compactor.isCrew();
        boolean completeTooLargeForCrew = false;
        if (crewCompaction) {
            try {
                int contextWindow = model.contextWindow(token);
                completeTooLargeForCrew = result.completeContentRequired
                        && !CrewConversationCompaction.completeOutputFits(rawContent, contextWindow);
                if (!result.completeContentRequired)
                    content = compactor.retainToolOutput(rawContent, contextWindow, token);
            } catch (RuntimeException cancellationDuringCompaction) {
                if (!token.isCancelled()) throw cancellationDuringCompaction;
                content = rawContent;
            }
        }
        if (result.completeContentRequired
                && (content.length() > outcome.remainingResultChars || completeTooLargeForCrew)) {
            result = CoreToolResult.failure("Complete tool output exceeds the remaining per-turn context budget or model whole-output budget; request this large result by itself. No partial output was added.");
            content = result.content;
        } else if (!crewCompaction && content.length() > outcome.remainingResultChars) {
            content = outcome.remainingResultChars > 0
                    ? content.substring(0, outcome.remainingResultChars) + "…[truncated by per-turn budget]"
                    : "Tool output omitted because the per-turn result budget is exhausted";
        }
        outcome.remainingResultChars = Math.max(0, outcome.remainingResultChars - content.length());
        if (!result.success) content = "Tool error: " + content;
        transcript.add(ConversationTurn.toolResult(item.call.id, item.call.name, content));
        item.finalized = true;
        String durableContent = item.result.success ? rawContent : "Tool error: " + rawContent;
        updateTranscriptSnapshot(transcript, Collections.singletonMap(item.call.id, durableContent));
        if (listener != null && (!item.hideFromProgress || !result.success))
            listener.onToolActivity(item.activity.event(result.success ? "tool_result" : "tool_error", content, result.previewId));
        if (invalidCompletedIds != null) throw invalidCompletedIds;
    }

    private void skipUnlaunched(List<PreparedToolCall> calls, int start, String reason) {
        for (int index = start; index < calls.size(); index++) {
            PreparedToolCall item = calls.get(index);
            if (item.preflightError != null || item.future != null || item.executionStarted()) continue;
            item.skippedReason = reason;
            toolLifecycle.put(item.call.id, "NEVER_LAUNCHED");
        }
    }

    private void finalizeInterruptedBatch(List<PreparedToolCall> calls, List<ConversationTurn> transcript,
            CancellationToken token, ProgressListener listener, ToolLoopDetector loopDetector,
            ToolBatchOutcome outcome, Map<String, ToolActivity> activeActivities) {
        String uncertain = "INTERRUPTED_UNCERTAIN: Tool execution was interrupted before its result was durably recorded; effects may have occurred.";
        RuntimeException firstFailure = null;
        for (PreparedToolCall item : calls) {
            if (item.finalized) continue;
            if (item.result == null && item.returnedResult != null) item.result = item.returnedResult;
            boolean started = item.executionStarted();
            if (item.admissionReason != null) {
                try {
                    appendNotStarted(item, item.admissionReason, transcript, listener);
                } catch (RuntimeException failure) {
                    if (failure instanceof CheckpointFailure) throw failure;
                    firstFailure = accumulateFailure(firstFailure, failure);
                } finally {
                    activeActivities.remove(item.call.id);
                }
                continue;
            }
            if (item.result != null && item.executionStarted()) {
                try {
                    appendExecutedResult(item, transcript, token, listener, loopDetector, outcome);
                } catch (RuntimeException failure) {
                    if (failure instanceof CheckpointFailure) throw failure;
                    if (firstFailure == null) firstFailure = failure;
                    else if (failure != firstFailure) firstFailure.addSuppressed(failure);
                } finally {
                    activeActivities.remove(item.call.id);
                }
                if (item.finalized) continue;
            }
            if (item.preflightError != null) {
                toolLifecycle.put(item.call.id, "NEVER_LAUNCHED");
                activeActivities.remove(item.call.id);
                transcript.add(ConversationTurn.toolResult(item.call.id, item.call.name,
                        "NEVER_LAUNCHED: Tool error: " + item.preflightError));
                try {
                    if (listener != null) listener.onToolActivity(item.activity.event(
                            started ? "tool_interrupted" : "tool_not_started", item.preflightError, null));
                } catch (RuntimeException failure) {
                    if (failure instanceof CheckpointFailure) throw failure;
                    firstFailure = accumulateFailure(firstFailure, failure);
                }
            } else if (item.skippedReason != null || !item.executionStarted()) {
                toolLifecycle.put(item.call.id, "NEVER_LAUNCHED");
                activeActivities.remove(item.call.id);
                String skipped = item.skippedReason == null ? "Tool call was not launched before cancellation." : item.skippedReason;
                if (!skipped.startsWith("NEVER_LAUNCHED:")) skipped = "NEVER_LAUNCHED: " + skipped;
                transcript.add(ConversationTurn.toolResult(item.call.id, item.call.name, skipped));
                try {
                    if (listener != null) listener.onToolActivity(item.activity.event(
                            started ? "tool_interrupted" : "tool_not_started", skipped, null));
                } catch (RuntimeException failure) {
                    if (failure instanceof CheckpointFailure) throw failure;
                    firstFailure = accumulateFailure(firstFailure, failure);
                }
            } else {
                toolLifecycle.put(item.call.id, "INTERRUPTED_UNCERTAIN");
                transcript.add(ConversationTurn.toolResult(item.call.id, item.call.name, uncertain));
                try {
                    if (listener != null) listener.onToolActivity(item.activity.event(
                            "tool_interrupted", uncertain, item.activity.previewId));
                } catch (RuntimeException failure) {
                    if (failure instanceof CheckpointFailure) throw failure;
                    firstFailure = accumulateFailure(firstFailure, failure);
                }
            }
            item.finalized = true;
            activeActivities.remove(item.call.id);
            try {
                updateTranscriptSnapshot(transcript);
            } catch (RuntimeException failure) {
                if (failure instanceof CheckpointFailure) throw failure;
                if (firstFailure == null) firstFailure = failure;
                else if (failure != firstFailure) firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure != null) throw firstFailure;
    }

    private static RuntimeException accumulateFailure(RuntimeException firstFailure, RuntimeException nextFailure) {
        if (firstFailure == null) return nextFailure;
        if (firstFailure != nextFailure) firstFailure.addSuppressed(nextFailure);
        return firstFailure;
    }

    private static boolean recoverableProviderFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof ProviderTransportException || cause instanceof java.io.IOException
                    || cause instanceof CodexResponseException) return true;
            if (cause instanceof ProviderHttpException) {
                int status = ((ProviderHttpException) cause).httpStatus;
                return status == 408 || status == 429 || status >= 500 && status <= 599;
            }
        }
        return false;
    }

    private static ToolActivity toolActivity(String runId, int modelTurns, int index, ModelReply.Call call,
                                             CoreToolRegistry tools) {
        CoreTool tool = tools.get(call.name);
        String displayName = tool == null ? null : tool.activityDisplayName(call.arguments);
        if (displayName == null || displayName.isEmpty()) displayName = tools.displayName(call.name);
        String skillId = tool == null ? "" : tool.activitySkillId(call.arguments);
        String skillName = tool == null ? "" : tool.activitySkillName(call.arguments);
        return new ToolActivity(UUID.randomUUID().toString(), runId + ":" + modelTurns + ":" + index,
                call.id, call.name, displayName, skillId, skillName, "tool_call", "", null,
                tools.auditDetail(call.name, call.arguments), tools.reflectionSource(call.name), System.currentTimeMillis());
    }

    private void markRemainingCallsNotLaunched(List<ModelReply.Call> calls, int start, String runId, int modelTurns,
                                                CoreToolRegistry tools, List<ConversationTurn> transcript,
                                                ProgressListener listener, String content) {
        for (int index = start; index < calls.size(); index++) {
            ModelReply.Call call = calls.get(index);
            ToolActivity activity = toolActivity(runId, modelTurns, index, call, tools);
            toolLifecycle.put(call.id, "NEVER_LAUNCHED");
            transcript.add(ConversationTurn.toolResult(call.id, call.name, content));
            updateTranscriptSnapshot(transcript);
            if (listener != null) listener.onToolActivity(activity.event("tool_not_started", content, null));
        }
    }

    private boolean drainTurnContext(List<ConversationTurn> transcript) {
        return drainTurnContext(transcript, false);
    }

    private boolean drainTurnContext(List<ConversationTurn> transcript, boolean finalCheckpoint) {
        return drainTurnContext(transcript, finalCheckpoint, null, null, "");
    }

    private boolean drainTurnContext(List<ConversationTurn> transcript, boolean finalCheckpoint,
            ProgressListener listener, String assistantAnswer, String runId) {
        collectTurnContext(finalCheckpoint);
        boolean appended = false;
        List<IncomingMessage> incorporated = new ArrayList<>();
        List<IncomingMessage> discarded = new ArrayList<>();
        if (assistantAnswer != null && listener != null) {
            IncomingMessage nextInput = null;
            for (IncomingMessage message : pendingDurableMessages) {
                if (message != null && !message.content.isEmpty() && !appliedIncomingIds.contains(message.id)
                        && "main_chat".equals(message.sourceKind)) {
                    nextInput = message;
                    break;
                }
            }
            if (nextInput != null) listener.onAssistantAnswerBeforeInput(runId, assistantAnswer, nextInput);
        }
        for (IncomingMessage message : new ArrayList<>(pendingDurableMessages)) {
            pendingDurableMessages.remove(message);
            pendingDurableIds.remove(message.id);
            if (message.content.isEmpty()) {
                discarded.add(message);
                continue;
            }
            if (!appliedIncomingIds.add(message.id)) continue;
            if (transcriptAlreadyContainsIncoming(transcript, message.id)) {
                // A durable input can be present in the ledger snapshot and also be returned by
                // steering after a restart/race. Commit the exact ID without duplicating content.
                incorporated.add(message);
                continue;
            }
            if (message.genuineUser) {
                ConversationTurn userTurn = new ConversationTurn("user", message.content);
                transcript.add(userTurn);
                protectedGenuineUser = userTurn;
                if (compactor != null) compactor.protectGenuineUser(userTurn);
                recordIncorporation(message.inputBinding, CrewInputIncorporation.TransportKind.USER, userTurn.content);
            } else {
                ConversationTurn incomingTurn = appendIncoming(transcript, message);
                recordIncorporation(message.inputBinding, CrewInputIncorporation.TransportKind.INBOX,
                        incomingTurn.content);
            }
            appended = true;
            incorporated.add(message);
        }
        List<String> trusted = new ArrayList<>(pendingTrustedMessages);
        pendingTrustedMessages.clear();
        for (String message : trusted) {
            if (message == null || message.isEmpty()) continue;
            ConversationTurn userTurn = new ConversationTurn("user", message);
            transcript.add(userTurn);
            protectedGenuineUser = userTurn;
            if (compactor != null) compactor.protectGenuineUser(userTurn);
            appended = true;
        }
        List<String> legacyMessages = new ArrayList<>(pendingLegacyMessages);
        pendingLegacyMessages.clear();
        for (String legacy : legacyMessages) {
            if (legacy == null || legacy.isEmpty()) continue;
            IncomingMessage message = new IncomingMessage(UUID.randomUUID().toString(), "legacy", legacy, false);
            appliedIncomingIds.add(message.id);
            appendIncoming(transcript, message);
            appended = true;
        }
        if (appended || !incorporated.isEmpty()) updateTranscriptSnapshot(transcript);
        if (!incorporated.isEmpty()) {
            List<IncomingMessage> confirmed = Collections.unmodifiableList(incorporated);
            if (turnContextProvider != null) turnContextProvider.durableMessagesIncorporated(confirmed);
            if (listener != null) listener.onDurableMessagesIncorporated(confirmed);
        }
        if (!discarded.isEmpty() && listener != null)
            listener.onDurableMessagesDiscarded(Collections.unmodifiableList(discarded));
        return appended;
    }

    private static boolean transcriptAlreadyContainsIncoming(List<ConversationTurn> transcript, String id) {
        if (id == null || id.isEmpty() || transcript == null) return false;
        for (ConversationTurn turn : transcript) {
            if (turn != null && turn.kind == ConversationTurn.Kind.MESSAGE && id.equals(turn.durableMessageId))
                return true;
        }
        return false;
    }

    /** Pull at a control point but defer transcript insertion until the current tool-call group closes. */
    private boolean collectTurnContext() {
        return collectTurnContext(false);
    }

    private boolean collectTurnContext(boolean finalCheckpoint) {
        boolean collected = false;
        IncomingMessage offered;
        while ((offered = offeredDurableMessages.poll()) != null) {
            if (appliedIncomingIds.contains(offered.id) || !pendingDurableIds.add(offered.id)) continue;
            pendingDurableMessages.add(offered);
            collected = true;
        }
        List<IncomingMessage> incoming = turnContextProvider == null ? Collections.emptyList()
                : finalCheckpoint ? turnContextProvider.takeDurableMessagesAtFinalCheckpoint()
                : turnContextProvider.takeDurableMessages();
        if (incoming != null) for (IncomingMessage message : incoming) {
            if (message == null || appliedIncomingIds.contains(message.id)
                    || !pendingDurableIds.add(message.id)) continue;
            pendingDurableMessages.add(message);
            collected = true;
        }
        List<String> trusted = turnContextProvider == null ? Collections.emptyList()
                : turnContextProvider.takeTrustedUserMessages();
        if (trusted != null) for (String message : trusted) {
            if (message != null && !message.isEmpty()) {
                pendingTrustedMessages.add(message);
                collected = true;
            }
        }
        String legacy = turnContextProvider == null ? "" : turnContextProvider.takeUntrustedContext();
        if (legacy != null && !legacy.isEmpty()) {
            pendingLegacyMessages.add(legacy);
            collected = true;
        }
        return collected;
    }

    private void closeTurnContextProvider() {
        if (turnContextProvider != null) turnContextProvider.closeForRunExit();
    }

    private void recordIncorporation(CrewInputBinding binding, CrewInputIncorporation.TransportKind kind, String content) {
        String cycleId = checkpointCycleId;
        if (binding == null || cycleId.isEmpty() || incorporations.containsKey(binding.mailboxMessageId)) return;
        incorporations.put(binding.mailboxMessageId, new CrewInputIncorporation(binding, cycleId, kind,
                CrewInputIncorporation.transportSha256(content)));
    }

    private static ConversationTurn appendIncoming(List<ConversationTurn> transcript, IncomingMessage message) {
        ConversationTurn.IncomingMetadata provenance = new ConversationTurn.IncomingMetadata(
                message.origin, message.id, message.sourceKind, message.source, message.messageType,
                message.references, true, message.provenance);
        ConversationTurn turn = message.origin == ConversationInput.Origin.USER
                ? new ConversationTurn("user", message.content, -1, provenance)
                : ConversationTurn.internalInput(message.content, -1, provenance, message.id);
        transcript.add(turn);
        return turn;
    }

    private static List<IncomingMessage> internalMessagesIn(List<ConversationTurn> transcript) {
        List<IncomingMessage> result = new ArrayList<>();
        for (ConversationTurn turn : ConversationTurn.internalConversationInputs(transcript)) {
            ConversationTurn.IncomingMetadata metadata = turn.incomingMetadata;
            result.add(new IncomingMessage(turn.durableMessageId, metadata.source, turn.content, false,
                    null, metadata.type, metadata.references, "main_chat", metadata.origin,
                    metadata.provenance));
        }
        return Collections.unmodifiableList(result);
    }

    private int estimateContext(List<ConversationTurn> transcript, String prompt, List<ToolSpec> declarations) {
        StringBuilder toolPayload = new StringBuilder();
        for (ToolSpec tool : declarations) {
            toolPayload.append(tool.name).append('\n').append(tool.description).append('\n').append(tool.jsonSchema()).append('\n');
        }
        return ConversationCompactionPolicy.estimateTokens(instructions, toolPayload.toString(), transcript, prompt);
    }

    private ConversationCompactor.Outcome compact(List<ConversationTurn> transcript, int contextWindow, String trigger, ConversationCompactionPolicy.Mode mode, String prompt, CancellationToken token, ProgressListener listener) {
        updateTranscriptSnapshot(transcript);
        return compactor.compact(transcript, contextWindow, estimateContext(Collections.emptyList(), prompt, tools.declarations()), trigger, mode, token, new ConversationCompactor.Listener(){

            @Override
            public void onStarted(String actualTrigger) {
                if (listener != null) listener.onCompactionStarted(actualTrigger);
            }

            @Override
            public void onCompleted(String summary, int summarizedMessages, String actualMode) {
                if (listener != null) listener.onCompactionCompleted(summary, summarizedMessages, actualMode);
            }
        });
    }

    private ConversationCompactor.Outcome compactPrepared(List<ConversationTurn> transcript,
            PreparedModelRequest request, CancellationToken token, ProgressListener listener) {
        updateTranscriptSnapshot(transcript);
        ConversationCompactor.Outcome outcome = compactor.compact(transcript, request.contextWindow, request.overheadTokens(),
                "auto_preflight", ConversationCompactionPolicy.Mode.SLIDING_WINDOW, token,
                new ConversationCompactor.Listener() {
                    @Override public void onStarted(String trigger) {
                        if (listener != null) listener.onCompactionStarted(trigger);
                    }
                    @Override public void onCompleted(String summary, int count, String mode) {
                        if (listener != null) listener.onCompactionCompleted(summary, count, mode);
                    }
                });
        if (outcome != null) model.onCompactionCompleted();
        return outcome;
    }

    private static void compactionFailed(ProgressListener listener, String message) {
        if (listener != null) listener.onCompactionFailed(message);
    }

    private static List<ConversationTurn> truncateToolResultsOnly(
            List<ConversationTurn> source, CorePromptBudget budget, ToolResultBudget resultBudget) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        long nonResultChars = 0;
        for (ConversationTurn turn : source) {
            if (turn.kind == ConversationTurn.Kind.MODEL_ERROR) continue;
            if (turn.kind != ConversationTurn.Kind.TOOL_RESULT) {
                nonResultChars += turn.content.length() + turn.thinking.length()
                        + turn.toolCallId.length() + turn.toolName.length();
                for (ModelReply.Call call : turn.toolCalls)
                    nonResultChars += call.name.length() + call.arguments.toString().length();
                for (ModelReply.ProviderOutputItem item : turn.providerOutputItems)
                    if (item.kind == ModelReply.ProviderOutputItem.Kind.REASONING) nonResultChars += item.value.length();
            }
        }
        int configuredResultBudget = resultBudget.allowsCompleteToolResults()
                ? budget.transcriptChars : Math.min(budget.transcriptChars, budget.toolResultsPerTurnChars);
        int remaining = Math.max(0, Math.min(configuredResultBudget,
                (int) Math.max(0L, (long) budget.transcriptChars - nonResultChars)));
        List<ConversationTurn> result = new ArrayList<>(source.size());
        for (ConversationTurn turn : source) {
            if (turn.kind != ConversationTurn.Kind.TOOL_RESULT || turn.content.length() <= remaining) {
                result.add(turn);
                if (turn.kind == ConversationTurn.Kind.TOOL_RESULT) remaining -= turn.content.length();
            } else {
                String value = turn.content.substring(0, Math.min(remaining, turn.content.length()));
                result.add(ConversationTurn.toolResult(turn.toolCallId, turn.toolName, value));
                remaining = 0;
            }
        }
        return result;
    }

    private static boolean retryableProviderFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof ProviderTransportException || cause instanceof java.io.IOException) return true;
            if (cause instanceof CodexResponseException)
                return ((CodexResponseException) cause).kind == CodexResponseException.Kind.INCOMPLETE;
            if (cause instanceof ProviderHttpException) {
                int status = ((ProviderHttpException) cause).httpStatus;
                return status == 408 || status == 429 || status >= 500 && status <= 599;
            }
        }
        return false;
    }

    private static boolean hasRateLimitCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof ProviderHttpException && ((ProviderHttpException) cause).isRateLimit()) return true;
        return false;
    }

    private static String providerErrorCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ProviderHttpException) return "http_" + ((ProviderHttpException) cause).httpStatus;
            if (cause instanceof CodexResponseException) return "incomplete_response";
            if (cause instanceof ProviderTransportException || cause instanceof java.io.IOException) return "transport";
        }
        return "provider_retry";
    }

    private static void emit(ProgressListener listener, String stage, String message) {
        if (listener != null) listener.onProgress(stage, message);
    }
}
