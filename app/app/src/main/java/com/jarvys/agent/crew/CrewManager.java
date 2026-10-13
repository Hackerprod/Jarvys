package com.jarvys.agent.crew;

import com.jarvys.agent.CancellationToken;
import com.jarvys.agent.CoreAgentLoop;
import com.jarvys.agent.CoreToolAccessPolicy;
import com.jarvys.agent.CoreToolRegistry;
import com.jarvys.agent.ConversationTurn;
import com.jarvys.agent.CrewContextArtifacts;
import java.util.Collection;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;

/**
 * In-memory, unbounded-concurrency manager for one conversation's Crew workspace.
 */
public final class CrewManager implements AutoCloseable {

    public enum Status {
        /*public static final*/ QUEUED /* = new Status() */ /*enum*/ ,
        /*public static final*/ RUNNING /* = new Status() */ /*enum*/ ,
        /*public static final*/ WAITING /* = new Status() */ /*enum*/ ,
        /*public static final*/ DONE /* = new Status() */ /*enum*/ ,
        PARTIAL,
        /*public static final*/ FAILED /* = new Status() */ /*enum*/ ,
        /*public static final*/ STOPPED /* = new Status() */ /*enum*/ ,
        /*public static final*/ INTERRUPTED /* = new Status() */ /*enum*/ ;
    }

    public enum WaitMode {
        /*public static final*/ ANY /* = new WaitMode() */ /*enum*/ ,
        /*public static final*/ ALL /* = new WaitMode() */ /*enum*/ ;
    }

    public interface ToolFactory {

        CoreToolRegistry create(Bot bot, CrewManager manager);
    }

    public interface LoopFactory {

        CoreAgentLoop create(Bot bot, CoreToolRegistry tools, CoreAgentLoop.TurnContextProvider incoming);
    }

    public interface WorkerLifecycle {

        void start(Bot bot);

        void end(Bot bot);
    }

    public interface ProgressFactory {

        CoreAgentLoop.ProgressListener create(Bot bot);
    }

    public interface SnapshotListener {

        void onChanged(CrewMissionSnapshot snapshot);
    }

    /** Exact once-per-run capture, never sourced from coalesced UI snapshots. */
    public interface ResultEvidenceListener {
        void onResult(CrewResultEvidence evidence);

        /** Persistence failure is diagnostic; it must not change or replay worker execution. */
        default void onDeliveryFailure(CrewResultEvidence evidence, RuntimeException failure) { }
    }

    /** Configured only for canonical workspace managers. Return only after exact durable acceptance. */
    public interface CoordinationEvidenceListener {
        void onCoordinationEvidence(CrewCoordinationEvidence evidence);
        default void onDeliveryFailure(CrewCoordinationEvidence evidence, RuntimeException failure) { }
    }

    public interface CheckpointSupport {

        default boolean canPersist(Bot bot) { return true; }

        void persist(Bot bot, List<CrewMessage> messages, List<CrewMessage> pending);

        ResumePlan reconcile(Bot bot);
    }

    public interface OwnedWork {

        void cancelAndAwait();

        boolean pending();
    }

    public static final class ResumePlan {
        public volatile CrewRole role;
        public final String observation;

        public ResumePlan(CrewRole role, String observation) {
            this.role = role;
            this.observation = observation;
        }
    }

    public interface Clock {

        void sleep(long millis, CancellationToken token);
    }

    public static final class Bot {
        public final String id;
        public final String name;
        public volatile CrewRole role;
        public final String mission;
        public final String missionId;
        private final MissionState missionState;
        public volatile CancellationToken token = CancellationToken.crewChild();
        private volatile Status status = Status.QUEUED;
        private volatile String result = "";
        private volatile String error = "";
        private volatile String waitingReason = "";
        private volatile String phase = "";
        private volatile String lastProgress = "";
        private volatile long lastProgressAtMillis;
        private volatile Thread thread;
        private volatile boolean reported;
        private volatile long startedAtMillis;
        private volatile long finishedAtMillis;
        private volatile java.util.concurrent.CountDownLatch terminated = new java.util.concurrent.CountDownLatch(1);
        private final Object cycleLock = new Object();
        private volatile boolean cycleScheduled;
        // In-process capabilities only; never populated from snapshots, checkpoints, or source journals.
        private final Map<String, CoordinationRequest> coordinationRequests = new LinkedHashMap<>();
        // A single immutable reference keeps lock-free snapshot identity/evidence reads coherent.
        private volatile CycleMetadata cycleMetadata = CycleMetadata.fresh();
        // Output produced by this run only. A restored/resumed bot.result may belong to older work.
        private String cycleResult = "";
        private volatile boolean reanimationRequested;
        private final AtomicInteger incomingDispatches = new AtomicInteger();
        private volatile long completedCycles;
        private volatile CoreToolRegistry workerTools;
        private volatile CoreAgentLoop loop;
        private volatile List<ConversationTurn> transcript = Collections.emptyList();
        private volatile CoreAgentLoop.Checkpoint checkpoint = CoreAgentLoop.Checkpoint.empty();
        private volatile JSONObject artifactOwnership = new JSONObject();
        private volatile Supplier<JSONObject> artifactProvider;
        private volatile CrewContextArtifacts contextArtifacts;
        private volatile String scopeIdentity = "";
        private volatile String recoveryNote = "";
        private volatile boolean checkpointAvailable;
        private volatile boolean requiresExplicitResume;
        private volatile boolean stopping;
        private volatile boolean definitionDisabled;
        private volatile Runnable checkpointChanged = ()->{
        };
        private final Set<String> jobOwners = ConcurrentHashMap.newKeySet();
        private final CopyOnWriteArrayList<OwnedWork> ownedWork = new CopyOnWriteArrayList<>();
        private final Set<String> pendingAnswerIds = ConcurrentHashMap.newKeySet();
        private final Set<String> observedWork = ConcurrentHashMap.newKeySet();
        private String ownerPrefix = "";

        private Bot(String id, String name, CrewRole role, String mission, MissionState missionState) {
            this.id = id;
            this.name = name;
            this.role = role;
            this.mission = mission;
            this.missionState = missionState;
            this.missionId = missionState.id;
        }

        /** Immutable title value and full request, read without acquiring manager/worker locks. */
        public CrewMissionTitle missionTitle() { return missionState.title.get(); }

        public String originalInstructions() { return missionState.originalInstructions; }

        public Status status() {
            return status;
        }

        public String result() {
            return result;
        }

        public String cycleId() { return cycleMetadata.cycleId; }

        public CrewResultEvidence resultEvidence() { return cycleMetadata.evidence; }

        public String error() {
            return error;
        }

        public String waitingReason() {
            return waitingReason;
        }

        public void awaitTermination() throws InterruptedException {
            while (true) {
                java.util.concurrent.CountDownLatch observed = terminated;
                observed.await();
                if (observed == terminated && !cycleScheduled) return;
            }
        }

        /** Observable cleanup completion, not merely a terminal display status. */
        public boolean isQuiescent() { return terminated.getCount() == 0 && !cycleScheduled; }

        public long completedCycles() {
            return completedCycles;
        }

        public CoreAgentLoop.Checkpoint checkpoint() {
            return checkpoint;
        }

        public JSONObject artifactOwnership() {
            return artifactProvider == null ? artifactOwnership : artifactProvider.get();
        }

        public void setArtifactProvider(Supplier<JSONObject> provider) {
            artifactProvider = provider;
        }

        public CrewContextArtifacts contextArtifacts() {
            return contextArtifacts;
        }

        public void attachContextArtifacts(CrewContextArtifacts artifacts) {
            contextArtifacts = artifacts;
            java.util.Objects.requireNonNull(artifacts);
            artifactProvider = artifacts::snapshotOwnership;
        }

        public long startedAtMillis() {
            return startedAtMillis;
        }

        public long finishedAtMillis() {
            return finishedAtMillis;
        }

        public String scopeIdentity() {
            return scopeIdentity;
        }

        public void setScopeIdentity(String value) {
            scopeIdentity = value;
        }

        public List<String> recoveredJobOwners() {
            return Collections.unmodifiableList(new ArrayList<>(jobOwners));
        }

        public void addJobOwner(String value) {
            if (!value.startsWith(ownerPrefix) || !value.substring(ownerPrefix.length()).matches("-?[0-9]+")) {
                throw new IllegalArgumentException("Invalid checkpoint job owner");
            }
            Long.parseLong(value.substring(ownerPrefix.length()));
            if (jobOwners.add(value)) checkpointChanged.run();
        }

        public CancellationToken freshToken() {
            CancellationToken next;
            do {
                next = CancellationToken.crewChild();
            }             while (jobOwners.contains(ownerPrefix + next.generation()));
            return next;
        }

        public boolean requiresExplicitResume() {
            return requiresExplicitResume;
        }

        public boolean canResume() {
            return checkpointAvailable && !definitionDisabled && requiresExplicitResume && !cycleScheduled && (status == Status.INTERRUPTED || status == Status.STOPPED || status == Status.FAILED || status == Status.PARTIAL);
        }

        public String recoveryNote() {
            return recoveryNote;
        }

        void statusForTool(Status value) {
            status = value;
        }
    }

    private static final class CoordinationRequest {
        final CrewCoordinationEvidence evidence;
        final CancellationToken token;
        volatile boolean valid = true;
        boolean active;
        boolean claimed;
        CoordinationRequest(CrewCoordinationEvidence evidence, CancellationToken token) {
            this.evidence = evidence; this.token = token;
        }
    }

    private static final class CycleMetadata {
        final String cycleId;
        final CrewResultEvidence evidence;
        CycleMetadata(String cycleId, CrewResultEvidence evidence) {
            this.cycleId = cycleId;
            this.evidence = evidence;
        }
        static CycleMetadata fresh() { return new CycleMetadata(UUID.randomUUID().toString(), null); }
    }

    /** Detached at the terminal decision; later dispatches may already change bot.status. */
    private static final class TerminalReport {
        final String status;
        final String text;
        final long producedAtMillis;
        TerminalReport(Status status, String text, long producedAtMillis) {
            this.status = status.name();
            this.text = text == null ? "" : text;
            this.producedAtMillis = producedAtMillis;
        }
    }

    private static final class MissionState {
        final String id;
        final AtomicReference<CrewMissionTitle> title = new AtomicReference<>(CrewMissionTitle.fallback());
        final AtomicLong presentationGeneration = new AtomicLong();
        final AtomicBoolean presenting = new AtomicBoolean();
        volatile String originalInstructions;
        boolean explicitAdmission;
        long startedAtMillis = System.currentTimeMillis();
        volatile String status = "RUNNING";
        volatile String synthesis = "";
        volatile long finishedAtMillis;

        MissionState(String id, String originalInstructions) {
            this.id = id;
            this.originalInstructions = originalInstructions == null ? "" : originalInstructions;
        }

        void acceptTitle(CrewMissionTitle candidate) {
            if (!CrewMissionTitle.FALLBACK.equals(candidate.source))
                title.compareAndSet(CrewMissionTitle.fallback(), candidate);
        }
    }
    private final String conversationId;
    private volatile CoreToolRegistry captainTools;
    private volatile ToolFactory toolFactory;
    private volatile LoopFactory loopFactory;
    private volatile WorkerLifecycle lifecycle = new WorkerLifecycle(){

        @Override
        public void start(Bot bot) {
        }

        @Override
        public void end(Bot bot) {
        }
    };
    private volatile ProgressFactory progressFactory;
    private volatile SnapshotListener snapshotListener;
    private volatile ResultEvidenceListener resultEvidenceListener;
    private volatile CoordinationEvidenceListener coordinationEvidenceListener;
    private final Map<String, String> coordinationEvidenceDeliveryFailures = new ConcurrentHashMap<>();
    private final Map<String, String> resultEvidenceDeliveryFailures = new ConcurrentHashMap<>();
    private volatile Function<CoreAgentLoop.Result, CoreAgentLoop.Result> resultPresentation = result -> result;
    private volatile CheckpointSupport checkpointSupport;
    private volatile Runnable checkpointCommittedListener = () -> { };
    private volatile Function<String, CrewRole> profileResolver = (id)->null;
    private volatile List<String> profileCapabilityCeiling = Collections.emptyList();
    private volatile boolean profilesConfigured;
    private final AtomicReference<Runnable> unregisterProfiles = new AtomicReference<>(() -> { });
    private final CrewMessageBus bus;
    private final Map<String, Bot> bots = new ConcurrentHashMap<>();
    private final Map<String, Integer> observedDefinitionRevisions = new ConcurrentHashMap<>();
    private final Map<String, MissionState> missions = new ConcurrentHashMap<>();
    private final Object missionAdmissionLock = new Object();
    private volatile String currentMissionId;
    private final ExecutorService executor;
    private final AtomicReference<Runnable> unregisterCaptain = new AtomicReference<>(()->{
    });
    private final CoreAgentLoop.RateLimitWaiter rateLimitWaiter;

    public CrewManager(String conversationId, CoreToolRegistry captainTools, ToolFactory toolFactory, LoopFactory loopFactory, CoreAgentLoop.RateLimitWaiter rateLimitWaiter) {
        if (conversationId == null || conversationId.trim().isEmpty()) throw new IllegalArgumentException("Conversation id is required");
        this.conversationId = conversationId;
        this.captainTools = captainTools;
        this.toolFactory = toolFactory;
        this.loopFactory = loopFactory;
        this.rateLimitWaiter = rateLimitWaiter;
        this.bus = new CrewMessageBus(conversationId);
        ThreadFactory threads = (task)->{
            Thread thread = new Thread(task, "jarvys-crew-" + UUID.randomUUID());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newCachedThreadPool(threads);
    }

    public String conversationId() {
        return conversationId;
    }

    public CrewMessageBus messageBus() {
        return bus;
    }

    public CoreAgentLoop.RateLimitWaiter rateLimitWaiter() {
        return rateLimitWaiter;
    }

    public void configureResultPresentation(Function<CoreAgentLoop.Result, CoreAgentLoop.Result> presentation) {
        resultPresentation = presentation == null ? result -> result : presentation;
    }

    public void configure(CoreToolRegistry captainTools, ToolFactory toolFactory, LoopFactory loopFactory, WorkerLifecycle lifecycle) {
        configure(captainTools, toolFactory, loopFactory, lifecycle, null);
    }

    public void configure(CoreToolRegistry captainTools, ToolFactory toolFactory, LoopFactory loopFactory, WorkerLifecycle lifecycle, ProgressFactory progressFactory) {
        configure(captainTools, toolFactory, loopFactory, lifecycle, progressFactory, null);
    }

    public void configure(CoreToolRegistry captainTools, ToolFactory toolFactory, LoopFactory loopFactory, WorkerLifecycle lifecycle, ProgressFactory progressFactory, SnapshotListener snapshotListener) {
        this.captainTools = captainTools;
        this.toolFactory = toolFactory;
        this.loopFactory = loopFactory;
        this.lifecycle = lifecycle == null ? this.lifecycle : lifecycle;
        this.progressFactory = progressFactory;
        this.snapshotListener = snapshotListener;
    }

    public void configureResultEvidenceListener(ResultEvidenceListener listener) {
        resultEvidenceListener = listener;
    }

    /** A successful worker outcome is not evidence that its separate durable delivery succeeded. */
    public Map<String, String> resultEvidenceDeliveryFailures() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(resultEvidenceDeliveryFailures));
    }

    private void deliverResultEvidence(CrewResultEvidence evidence) {
        ResultEvidenceListener listener = resultEvidenceListener;
        if (listener == null || evidence == null) return;
        try {
            listener.onResult(evidence);
        } catch (RuntimeException failure) {
            resultEvidenceDeliveryFailures.put(evidence.evidenceId,
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            try { listener.onDeliveryFailure(evidence, failure); }
            catch (RuntimeException diagnosticFailure) {
                // Keep the original failure observable even when the diagnostic sink is unavailable.
            }
        }
    }

    /** Runtime must verify canonical workspace ownership before enabling this producer boundary. */
    public void configureCoordinationEvidenceListener(CoordinationEvidenceListener listener) {
        if (coordinationEvidenceListener != listener) {
            coordinationEvidenceListener = listener;
            for (Bot bot : bots.values()) synchronized (bot.cycleLock) { invalidateCoordinationLocked(bot); }
            bus.signalWaiters();
        }
    }

    public Map<String, String> coordinationEvidenceDeliveryFailures() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(coordinationEvidenceDeliveryFailures));
    }

    private static boolean coordinationKind(CrewMessage.Type type) {
        return type == CrewMessage.Type.QUESTION || type == CrewMessage.Type.BLOCKER || type == CrewMessage.Type.FINDING;
    }

    private boolean liveCoordinationCycle(Bot bot, CancellationToken token, String cycleId) {
        return bots.get(bot.id) == bot && bot.token == token && bot.cycleId().equals(cycleId)
                && (bot.status == Status.RUNNING || bot.status == Status.WAITING)
                && !bot.requiresExplicitResume && !bot.stopping && !bot.definitionDisabled
                && !token.isCancellationRequested();
    }

    /** Must hold cycleLock. Invalidates in-flight persistence as well as already active requests. */
    private static void invalidateCoordinationLocked(Bot bot) {
        for (CoordinationRequest request : bot.coordinationRequests.values()) request.valid = false;
        bot.coordinationRequests.clear();
    }

    private void removeCoordinationLocked(Bot bot, CoordinationRequest request) {
        if (request == null) return;
        request.valid = false;
        bot.coordinationRequests.remove(request.evidence.evidenceId, request);
    }

    private CrewMessage produceCoordination(Bot bot, CancellationToken expectedToken,
            CrewMessage.Type type, String text, List<String> refs, boolean blocking) {
        CoordinationEvidenceListener listener = coordinationEvidenceListener;
        if (listener == null) throw new IllegalStateException("Workspace coordination is not configured");
        CrewMessage source;
        CrewCoordinationEvidence evidence;
        CoordinationRequest request;
        synchronized (bot.cycleLock) {
            if (!liveCoordinationCycle(bot, expectedToken, bot.cycleId()))
                throw new IllegalStateException("Worker coordination cycle is no longer live");
            CrewMessage message = new CrewMessage(conversationId, bot.id, "chief", type, text, refs, System.currentTimeMillis());
            evidence = CrewCoordinationEvidence.create(conversationId, bot.missionId, bot.id, bot.cycleId(),
                    message.id, type.name(), message.text, message.refs, message.timestampMillis);
            source = message.withCoordination(evidence);
            request = evidence.isReplyableKind() ? new CoordinationRequest(evidence, expectedToken) : null;
            if (request != null) bot.coordinationRequests.put(evidence.evidenceId, request);
            if (blocking) { bot.status = Status.WAITING; bot.waitingReason = "respuesta del capitán"; }
        }
        try {
            // Never hold cycleLock across a storage callback: USER/STOP can invalidate this original slot.
            listener.onCoordinationEvidence(evidence);
            synchronized (bot.cycleLock) {
                if (listener != coordinationEvidenceListener || !liveCoordinationCycle(bot, expectedToken, evidence.cycleId)
                        || (request != null && (!request.valid || bot.coordinationRequests.get(evidence.evidenceId) != request)))
                    throw new IllegalStateException("Coordination source persisted, but its live request was superseded");
                if (request != null) request.active = true;
                bus.enqueueFresh(source);
            }
            publishMission(bot.missionId);
            return source;
        } catch (RuntimeException failure) {
            synchronized (bot.cycleLock) { removeCoordinationLocked(bot, request); }
            coordinationEvidenceDeliveryFailures.put(evidence.evidenceId,
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            try { listener.onDeliveryFailure(evidence, failure); } catch (RuntimeException ignored) { }
            bus.signalWaiters();
            throw failure;
        }
    }

    public boolean canReplyToCoordination(CrewCoordinationEvidence evidence) {
        if (evidence == null || coordinationEvidenceListener == null || !conversationId.equals(evidence.conversationId)
                || !evidence.isReplyableKind()) return false;
        Bot bot = bots.get(evidence.botId);
        if (bot == null) return false;
        synchronized (bot.cycleLock) { return replyableCoordinationLocked(bot, evidence) != null; }
    }

    private CoordinationRequest replyableCoordinationLocked(Bot bot, CrewCoordinationEvidence evidence) {
        CoordinationRequest request = bot.coordinationRequests.get(evidence.evidenceId);
        return coordinationEvidenceListener != null && request != null && request.valid && request.active && !request.claimed
                && conversationId.equals(evidence.conversationId) && bot.missionId.equals(evidence.missionId)
                && bot.id.equals(evidence.botId) && evidence.isReplyableKind()
                && request.evidence.contentSha256.equals(evidence.contentSha256)
                && liveCoordinationCycle(bot, request.token, evidence.cycleId) ? request : null;
    }

    /** Exact synchronous mailbox admission. Never launches work, recreates a capability, or replays a receipt. */
    public CrewMessage replyToCoordinationExact(CrewCoordinationEvidence evidence, String deterministicReplyMessageId,
            String text, BooleanSupplier finalAdmission) {
        return replyToCoordinationExact(evidence, deterministicReplyMessageId, text, null, finalAdmission);
    }

    public CrewMessage replyToCoordinationExact(CrewCoordinationEvidence evidence, String deterministicReplyMessageId,
            String text, CrewInputBinding inputBinding, BooleanSupplier finalAdmission) {
        if (evidence == null || deterministicReplyMessageId == null || deterministicReplyMessageId.trim().isEmpty()
                || text == null || text.trim().isEmpty() || finalAdmission == null)
            throw new IllegalArgumentException("Exact coordination reply fields are required");
        Bot bot = bots.get(evidence.botId);
        if (bot == null) throw new IllegalStateException("Original live worker is unavailable");
        validateCurrentProfile(bot.role);
        CrewMessage answer;
        bot.incomingDispatches.incrementAndGet();
        try {
            synchronized (bot.cycleLock) {
                CoordinationRequest request = replyableCoordinationLocked(bot, evidence);
                if (request == null) throw new IllegalStateException("Exact request is no longer replyable");
                if (inputBinding != null) validateInputBinding(bot, text, inputBinding, CrewInputBinding.SourceKind.WORKSPACE_REPLY);
                if (inputBinding != null && !deterministicReplyMessageId.equals(inputBinding.mailboxMessageId))
                    throw new IllegalArgumentException("Reply mailbox identity changed");
                answer = request.token.callIfActive(() -> {
                    if (!finalAdmission.getAsBoolean() || replyableCoordinationLocked(bot, evidence) != request)
                        throw new IllegalStateException("Exact reply admission was revoked");
                    CrewMessage message = new CrewMessage(deterministicReplyMessageId, conversationId, "chief", bot.id,
                            CrewMessage.Type.ANSWER, text, Collections.emptyList(), System.currentTimeMillis()).withCoordination(evidence);
                    if (inputBinding != null) message = message.withInputBinding(inputBinding);
                    // Request-wide claim never rolls back, including ambiguous checkpoint failure after enqueue.
                    request.claimed = true;
                    request.active = false;
                    return bus.enqueueFresh(message);
                }, null);
                if (answer == null) throw new IllegalStateException("Worker token no longer admits the exact reply");
                persistCheckpoint(bot);
            }
        } finally {
            if (bot.incomingDispatches.decrementAndGet() == 0) synchronized (bot.cycleLock) { bot.cycleLock.notifyAll(); }
        }
        publishPresentation(bot.missionId);
        return answer;
    }

    public void configureCheckpoints(CheckpointSupport support) {
        checkpointSupport = support;
    }

    public synchronized Bot restoreBot(CrewMissionSnapshot missionSnapshot, CrewBotSnapshot snapshot, CrewRole role, CoreAgentLoop.Checkpoint checkpoint, JSONObject ownership, String scopeIdentity, List<String> jobOwners, long completedCycles, List<CrewMessage> messages, List<CrewMessage> pending, String issue) {
        if (!conversationId.equals(missionSnapshot.conversationId)) throw new IllegalArgumentException("Wrong checkpoint conversation");
        if (snapshot.resultEvidence != null && (!conversationId.equals(snapshot.resultEvidence.conversationId)
                || !missionSnapshot.missionId.equals(snapshot.resultEvidence.missionId)))
            throw new IllegalArgumentException("Worker result evidence belongs to another checkpoint");
        if (bots.containsKey(snapshot.id)) return bots.get(snapshot.id);
        MissionState restoredMission = missions.computeIfAbsent(missionSnapshot.missionId, (id)->{
            MissionState mission = new MissionState(id, missionSnapshot.originalInstructions);
            mission.startedAtMillis = missionSnapshot.startedAtMillis;
            mission.finishedAtMillis = missionSnapshot.finishedAtMillis;
            mission.status = missionSnapshot.active() ? "INTERRUPTED" : missionSnapshot.status;
            mission.synthesis = missionSnapshot.synthesis;
            return mission;
        });
        restoredMission.acceptTitle(CrewMissionTitle.stored(missionSnapshot.title, missionSnapshot.titleSource));
        // An orphan older checkpoint has no user request. A newer sibling may supply it.
        if (restoredMission.originalInstructions.isEmpty()) restoredMission.originalInstructions = missionSnapshot.originalInstructions;
        Bot bot = new Bot(snapshot.id, snapshot.name, role, snapshot.mission, restoredMission);
        bot.ownerPrefix = conversationId + "/" + bot.id + "/";
        bot.status = snapshot.active() ? Status.INTERRUPTED : Status.valueOf(snapshot.status);
        if (checkpoint != null && bot.status == Status.DONE && pending.stream().anyMatch((message)->!checkpoint.appliedIncomingIds.contains(message.id))) {
            bot.status = Status.INTERRUPTED;
        }
        bot.lastProgress = snapshot.lastProgress;
        bot.lastProgressAtMillis = snapshot.lastProgressAtMillis;
        bot.cycleMetadata = new CycleMetadata(snapshot.cycleId, snapshot.resultEvidence);
        bot.result = snapshot.result;
        bot.error = snapshot.error;
        bot.startedAtMillis = snapshot.startedAtMillis;
        bot.finishedAtMillis = snapshot.finishedAtMillis;
        bot.completedCycles = completedCycles;
        bot.requiresExplicitResume = true;
        bot.checkpointAvailable = checkpoint != null && issue.isEmpty();
        bot.recoveryNote = issue;
        if (checkpoint != null) {
            bot.checkpoint = checkpoint;
            bot.transcript = checkpoint.transcript;
            bot.artifactOwnership = ownership;
            bot.scopeIdentity = scopeIdentity;
            for (String owner : jobOwners) bot.addJobOwner(owner);
        }
        bot.terminated.countDown();
        bots.put(bot.id, bot);
        bot.checkpointChanged = ()->persistCheckpoint(bot);
        bus.restore(CrewActivityTimeline.interruptUnfinished(messages,bot.id), pending);
        publishPresentation(bot.missionId);
        return bot;
    }

    public void setCheckpointCommittedListener(Runnable listener) {
        checkpointCommittedListener = listener == null ? () -> { } : listener;
    }

    private void signalCheckpointCommitted(boolean persisted) {
        if (persisted) try { checkpointCommittedListener.run(); }
        catch (RuntimeException ignored) { /* An accelerator cannot change a committed tool outcome. */ }
    }

    public void retainCheckpoint(Bot bot, CoreAgentLoop.Checkpoint checkpoint, JSONObject ownership) {
        boolean persisted;
        synchronized (bot.cycleLock) {
            if (bots.get(bot.id) != bot) throw new IllegalStateException("Worker identity changed");
            persisted = retainCheckpointLocked(bot, checkpoint, ownership);
        }
        signalCheckpointCommitted(persisted);
    }

    /** Capture each invocation, never consult a later live cycle as historical provenance. */
    private void retainCheckpointForRun(Bot bot, CoreAgentLoop loop, CancellationToken token, String cycleId,
            CoreAgentLoop.Checkpoint checkpoint) {
        boolean persisted;
        synchronized (bot.cycleLock) {
            if (bots.get(bot.id) != bot || bot.loop != loop || bot.token != token
                    || !cycleId.equals(bot.cycleMetadata.cycleId))
                throw new IllegalStateException("Stale worker checkpoint callback");
            // A same-cycle STOP does not erase evidence already appended before cancellation.
            persisted = retainCheckpointLocked(bot, checkpoint, bot.artifactOwnership());
        }
        signalCheckpointCommitted(persisted);
    }

    /** Caller holds cycleLock. Store ordering is cycleLock -> bot -> checkpoint store. */
    private boolean retainCheckpointLocked(Bot bot, CoreAgentLoop.Checkpoint checkpoint, JSONObject ownership) {
        synchronized (bot) {
            bot.checkpoint = checkpoint;
            bot.transcript = checkpoint.transcript;
            bot.artifactOwnership = ownership;
            boolean persisted = persistCheckpoint(bot);
            // Generic nonpersistent Crew still consumes messages; only successful supported saves signal proof work.
            bus.drainMatching(bot.id, (message)->checkpoint.appliedIncomingIds.contains(message.id));
            bot.pendingAnswerIds.removeAll(checkpoint.appliedIncomingIds);
            return persisted;
        }
    }

    private boolean persistCheckpoint(Bot bot) {
        synchronized (bot) {
            CheckpointSupport support = checkpointSupport;
            if (support != null && support.canPersist(bot) && (!bot.requiresExplicitResume || bot.checkpointAvailable)) {
                support.persist(bot, bus.snapshot(), bus.pending(bot.id));
                bot.checkpointAvailable = true;
                return true;
            }
            return false;
        }
    }

    public void resumeExact(String id, CancellationToken expectedToken, String userText) {
        resumeExact(id, expectedToken, userText, null);
    }

    public void resumeExact(String id, CancellationToken expectedToken, String userText, CrewInputBinding inputBinding) {
        Bot bot = bots.get(id);
        if (bot == null) throw new IllegalArgumentException("Unknown worker");
        synchronized (bot.cycleLock) {
            if (bot.token != expectedToken) throw new IllegalStateException("Worker cycle changed");
            if (inputBinding != null) validateInputBinding(bot, userText, inputBinding, CrewInputBinding.SourceKind.NATIVE_CORRECT);
            resumeWithUserMessage(id, userText, inputBinding);
        }
    }

    public void resume(String id) { resumeWithUserMessage(id, null); }

    /** Native user correction is queued durably before any resumed model turn can start. */
    public void resumeWithUserMessage(String id, String userText) {
        resumeWithUserMessage(id, userText, null);
    }

    private void resumeWithUserMessage(String id, String userText, CrewInputBinding inputBinding) {
        if (userText != null && userText.trim().isEmpty())
            throw new IllegalArgumentException("User correction must be non-empty");
        Bot bot = bots.get(id);
        if (bot == null) throw new IllegalArgumentException("Historical bot has no resumable checkpoint");
        synchronized (bot.cycleLock) {
            if (!bot.canResume()) throw new IllegalStateException("This bot is not ready for explicit resume");
            CheckpointSupport support = checkpointSupport;
            if (support == null) throw new IllegalStateException("Resume is unavailable in this runtime");
            try {
                ResumePlan plan = support.reconcile(bot);
                if (bot.role.missionAccess == CrewMissionAccess.READ_ONLY
                        && plan.role.missionAccess != CrewMissionAccess.READ_ONLY) {
                    throw new IllegalStateException("Resume cannot elevate a read-only mission");
                }
                validateCurrentProfile(plan.role);
                bot.role = plan.role;
                invalidateCoordinationLocked(bot);
                bot.token = bot.freshToken();
                bot.cycleMetadata = CycleMetadata.fresh();
                bot.cycleResult = "";
                bot.loop = null;
                bot.workerTools = null;
                bot.requiresExplicitResume = false;
                bot.stopping = false;
                bot.reanimationRequested = false;
                bot.error = "";
                bot.recoveryNote = "";
                bot.status = Status.QUEUED;
                bot.finishedAtMillis = 0;
                bot.completedCycles = Math.max(1L, bot.completedCycles);
                bus.send("recovery", bot.id, CrewMessage.Type.FINDING, plan.observation, Collections.emptyList());
                if (userText != null) {
                    if (inputBinding == null) bus.send("user", bot.id, CrewMessage.Type.USER, userText, Collections.emptyList());
                    else bus.enqueueFresh(new CrewMessage(inputBinding.mailboxMessageId, conversationId, "user", bot.id,
                            CrewMessage.Type.USER, userText, Collections.emptyList(), System.currentTimeMillis()).withInputBinding(inputBinding));
                }
                persistCheckpoint(bot);
                MissionState mission = missions.get(bot.missionId);
                // Recovering one worker does not supply a new captain synthesis. The snapshot
                // derives live activity from its children while retaining the partial outcome.
                if (!"PARTIAL".equals(mission.status)) {
                    mission.status = "RUNNING";
                    mission.finishedAtMillis = 0;
                }
                bot.terminated = new java.util.concurrent.CountDownLatch(1);
                bot.cycleScheduled = true;
                publishPresentation(bot.missionId);
            } catch (RuntimeException failure) {
                bot.requiresExplicitResume = true;
                bot.status = Status.INTERRUPTED;
                bot.recoveryNote = failure.getMessage() == null ? "Checkpoint could not be reconciled" : failure.getMessage();
                publishPresentation(bot.missionId);
                throw failure;
            }
        }
        try {
            executor.execute(()->runBot(bot));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            synchronized (bot.cycleLock) {
                bot.cycleScheduled = false;
                bot.requiresExplicitResume = true;
                bot.status = Status.INTERRUPTED;
                bot.recoveryNote = "Worker could not start; reopen this chat and resume explicitly";
                bot.terminated.countDown();
                publishMission(bot.missionId);
                throw rejected;
            }
        }
    }

    public String beginMission(String missionId, String originalInstructions) {
        String id = missionId == null || missionId.trim().isEmpty() ? UUID.randomUUID().toString() : missionId;
        synchronized (missionAdmissionLock) {
            missions.putIfAbsent(id, new MissionState(id, originalInstructions));
            currentMissionId = id;
        }
        return id;
    }

    public void finishMission(String missionId, String synthesis, String outcome) {
        synchronized (missionAdmissionLock) {
            MissionState mission = missions.get(missionId);
            if (mission == null) return;
            if (bots.values().stream().noneMatch((bot)->missionId.equals(bot.missionId))) {
                // Only explicit workspace admissions need a terminal identity during role resolution.
                if (!mission.explicitAdmission) {
                    missions.remove(missionId, mission);
                    if (missionId.equals(currentMissionId)) currentMissionId = null;
                    return;
                }
                if (missionId.equals(currentMissionId)) currentMissionId = null;
            }
            mission.status = "COMPLETED".equals(outcome) ? "SYNTHESIZED"
                        : "STOPPED".equals(outcome) ? "STOPPED"
                        : "PARTIAL".equals(outcome) ? "PARTIAL"
                        : "TIMED_OUT".equals(outcome) ? "TIMED_OUT"
                        : "FAILED".equals(outcome) ? "FAILED" : "INTERRUPTED";
            mission.synthesis = synthesis == null ? "" : synthesis;
            mission.finishedAtMillis = System.currentTimeMillis();
        }
        publishMission(missionId);
    }

    /**
     * Link current captain STOP to every worker; cancelling an individual child stays local.
     */
    public void attachCaptain(CancellationToken captain) {
        unregisterCaptain.getAndSet(captain.registerCancelAction(this::stopAll)).run();
    }

    public void configureProfiles(Function<String, CrewRole> resolver) {
        configureProfiles(resolver, Collections.emptyList());
    }

    public void configureProfiles(Function<String, CrewRole> resolver, Collection<String> capabilityNames) {
        profilesConfigured = resolver != null;
        profileResolver = resolver == null ? (id)->null : resolver;
        profileCapabilityCeiling = Collections.unmodifiableList(new ArrayList<>(capabilityNames));
    }

    public void configureProfileSubscription(Runnable unregister) {
        unregisterProfiles.getAndSet(unregister == null ? () -> { } : unregister).run();
    }

    /** Cancel every live cycle for an edited/disabled definition, across all its missions. */
    public synchronized void definitionChanged(BotDefinition definition) {
        Integer observed = observedDefinitionRevisions.get(definition.id);
        if (observed != null && observed > definition.revision) return;
        observedDefinitionRevisions.put(definition.id, definition.revision);
        for (Bot bot : bots.values()) {
            if (bot.role.profileVersion <= 0 || !definition.id.equals(bot.role.id)) continue;
            synchronized (bot.cycleLock) {
                bot.definitionDisabled = !definition.enabled;
                if (!definition.enabled || definition.profile.version != bot.role.profileVersion) {
                    bot.requiresExplicitResume = true;
                    bot.reanimationRequested = false;
                    bot.recoveryNote = definition.enabled
                            ? "Bot configuration changed. Review and resume explicitly, or start a new mission."
                            : "This bot definition is disabled. Enable it before starting or resuming work.";
                    // Cancel even a terminal bot's old token so no late owned operation can survive.
                    bot.token.cancel();
                    try { stop(bot.id); } catch (RuntimeException ignored) { }
                }
            }
            try { publishPresentation(bot.missionId); } catch (RuntimeException ignored) { }
        }
    }

    private void validateCurrentProfile(CrewRole role) {
        if (role.profileVersion <= 0 || !profilesConfigured) return;
        CrewRole current = profileResolver.apply(role.id);
        if (current == null) throw new IllegalStateException("Bot definition is missing or disabled");
        if (!current.id.equals(role.id) || current.profileVersion != role.profileVersion
                || current.workspaceMode != role.workspaceMode || !current.tools.containsAll(role.tools)
                || !current.skillIds.containsAll(role.skillIds)) {
            throw new IllegalStateException("Bot configuration changed; review it before continuing");
        }
    }

    private List<String> capabilityNames(CrewRole role) {
        List<String> names = new ArrayList<>(captainTools.names());
        if (role.profileVersion > 0) for (String name : profileCapabilityCeiling) if (!names.contains(name)) names.add(name);
        return names;
    }

    public List<CrewRole> templates() {
        return CrewRoleTemplates.all(captainTools);
    }

    /**
     * Read-only preflight of the existing admission rules. No mission, bot, checkpoint,
     * executor task or model request is created. This is an observation, not a reservation:
     * spawnForMission rechecks the same rules after the caller's durable STARTED boundary.
     */
    public void validateSpawnForMission(String missionId, String originalInstructions, String roleId,
            String mission, List<String> requestedTools, String requestedName,
            CrewMissionAccess missionAccess) {
        if (missionId == null || missionId.trim().isEmpty() || originalInstructions == null)
            throw new IllegalArgumentException("Exact mission identity and original request are required");
        resolveAdmissionRole(roleId, mission, requestedTools, requestedName, missionAccess);
        synchronized (missionAdmissionLock) {
            MissionState state = missions.get(missionId);
            if (state != null && (!state.originalInstructions.equals(originalInstructions)
                    || !"RUNNING".equals(state.status)))
                throw new IllegalStateException("Mission request changed or is no longer accepting assignments");
        }
    }

    private CrewRole resolveAdmissionRole(String roleId, String mission, List<String> requestedTools,
            String requestedName, CrewMissionAccess missionAccess) {
        if (mission == null || mission.trim().isEmpty()) throw new IllegalArgumentException("mission must be a non-empty string");
        CrewRole role;
        if ("custom".equalsIgnoreCase(roleId)) {
            if (requestedName == null || requestedName.trim().isEmpty()) throw new IllegalArgumentException("custom role name is required");
            if (requestedTools == null) throw new IllegalArgumentException("custom role tools are required");
            role = CrewRoleTemplates.custom(requestedName, mission, requestedTools, captainTools);
        } else {
            role = templates().stream().filter((candidate)->candidate.id.equalsIgnoreCase(roleId) || candidate.name.equalsIgnoreCase(roleId)).findFirst().orElse(null);
            if (role == null) role = profileResolver.apply(roleId);
            if (role == null) throw new IllegalArgumentException("Unknown Crew role: " + roleId);
            if (requestedTools != null) {
                List<String> selected = validateRequestedTools(requestedTools, capabilityNames(role));
                if (!role.tools.containsAll(selected)) throw new IllegalArgumentException("Requested tools exceed this role\'s capabilities");
                role = role.withTools(selected);
            }
        }
        if (missionAccess == CrewMissionAccess.READ_ONLY
                && (role.profileVersion <= 0 || role.workspaceMode != CrewProfile.WorkspaceMode.CONVERSATION_PROJECT)) {
            throw new IllegalArgumentException("read_only requires a versioned conversation-project profile");
        }
        role = role.withMissionAccess(missionAccess);
        validateRole(role);
        validateCurrentProfile(role);
        return role;
    }

    /** Explicit mission admission, without changing the legacy captain cursor. */
    public Bot spawnForMission(String missionId, String originalInstructions, String roleId,
            String mission, List<String> requestedTools, String requestedName,
            CrewMissionAccess missionAccess, Object taskTitle) {
        if (missionId == null || missionId.trim().isEmpty() || originalInstructions == null)
            throw new IllegalArgumentException("Exact mission identity and original request are required");
        synchronized (missionAdmissionLock) {
            MissionState state = missions.get(missionId);
            if (state != null && (!state.originalInstructions.equals(originalInstructions)
                    || !"RUNNING".equals(state.status)))
                throw new IllegalStateException("Mission request changed or is no longer accepting assignments");
            if (state == null) {
                state = new MissionState(missionId, originalInstructions);
                missions.put(missionId, state);
            }
            state.explicitAdmission = true;
        }
        return spawnAdmitted(roleId, mission, requestedTools, requestedName, missionAccess, taskTitle,
                missionId, originalInstructions);
    }

    public Bot spawn(String roleId, String mission, List<String> requestedTools, String requestedName) {
        return spawn(roleId, mission, requestedTools, requestedName, CrewMissionAccess.STANDARD);
    }

    public Bot spawn(String roleId, String mission, List<String> requestedTools, String requestedName,
            CrewMissionAccess missionAccess) {
        return spawn(roleId, mission, requestedTools, requestedName, missionAccess, null);
    }

    public Bot spawn(String roleId, String mission, List<String> requestedTools, String requestedName,
            CrewMissionAccess missionAccess, Object taskTitle) {
        return spawnAdmitted(roleId, mission, requestedTools, requestedName, missionAccess, taskTitle, null, null);
    }

    private Bot spawnAdmitted(String roleId, String mission, List<String> requestedTools, String requestedName,
            CrewMissionAccess missionAccess, Object taskTitle, String explicitMissionId, String originalInstructions) {
        CrewRole role = resolveAdmissionRole(roleId, mission, requestedTools, requestedName, missionAccess);
        String id = "bot-" + UUID.randomUUID();
        String name = requestedName == null || requestedName.trim().isEmpty() ? role.name : requestedName.trim();
        MissionState missionState;
        final Bot bot;
        CrewMissionTitle candidate = CrewMissionTitle.agent(taskTitle);
        synchronized (missionAdmissionLock) {
            if (explicitMissionId != null) {
                missionState = missions.get(explicitMissionId);
                if (missionState != null && (!missionState.originalInstructions.equals(originalInstructions)
                        || !"RUNNING".equals(missionState.status)))
                    throw new IllegalStateException("Mission request changed or is no longer accepting assignments");
                if (missionState == null) {
                    missionState = new MissionState(explicitMissionId, originalInstructions);
                    missions.put(explicitMissionId, missionState);
                }
            } else {
                missionState = currentMissionId == null ? null : missions.get(currentMissionId);
                if (missionState == null || !"RUNNING".equals(missionState.status)) {
                    String idForMission = beginMission(UUID.randomUUID().toString(), "");
                    missionState = missions.get(idForMission);
                }
            }
            missionState.acceptTitle(candidate);
            bot = new Bot(id, name, role, mission, missionState);
            bot.ownerPrefix = conversationId + "/" + bot.id + "/";
            bot.cycleScheduled = true;
            bots.put(id, bot);
            bot.checkpointChanged = ()->persistCheckpoint(bot);
        }
        String missionId = missionState.id;
        bus.send("chief", id, CrewMessage.Type.TASK, mission, Collections.emptyList());
        try {
            publishMission(missionId);
            executor.execute(()->runBot(bot));
            return bot;
        } catch (RuntimeException admissionFailure) {
            synchronized (bot.cycleLock) {
                bot.cycleScheduled = false;
                bot.status = Status.FAILED;
                bot.requiresExplicitResume = true;
                bot.error = "Worker was not started because its durable checkpoint or submission failed";
                bot.recoveryNote = bot.error;
                bot.finishedAtMillis = System.currentTimeMillis();
                bot.terminated.countDown();
                publishPresentation(missionId);
                throw admissionFailure;
            }
        }
    }

    private List<String> validateRequestedTools(List<String> requested, List<String> ceiling) {
        List<String> result = new ArrayList<>();
        for (String tool : requested) {
            if (tool == null || tool.trim().isEmpty()) throw new IllegalArgumentException("tools must contain non-empty names");
            String name = tool.trim();
            if ("delete".equals(name)) throw new IllegalArgumentException("Bots cannot receive memory deletion tools");
            if (isBotTool(name) || isCaptainOnly(name)) {
                if (isCaptainOnly(name)) throw new IllegalArgumentException("Bots cannot receive captain-only Crew tools");
                result.add(name);
            } else if (CoreToolAccessPolicy.matches(name, null, ceiling)) result.add(name); else throw new IllegalArgumentException("Role requested a tool outside the captain\'s capability scope: " + name);
        }
        return result;
    }

    private void validateRole(CrewRole role) {
        if (role.tools.contains("project_exec") && !role.tools.contains("project_jobs")) {
            throw new IllegalArgumentException("project_exec requires project_jobs for output and cancellation");
        }
        for (String name : role.tools) {
            if ("delete".equals(name)) throw new IllegalArgumentException("Bots cannot receive memory deletion tools");
            if (isCaptainOnly(name)) throw new IllegalArgumentException("Bots cannot receive captain-only Crew tools");
            if (!isBotTool(name) && !CoreToolAccessPolicy.matches(name, null, capabilityNames(role))) throw new IllegalArgumentException("Role requested a tool outside the captain\'s capability scope: " + name);
        }
    }

    public static boolean isBotTool(String name) {
        return java.util.Arrays.asList("msg_send", "board_post", "board_read", "ask_chief", "report_done").contains(name);
    }

    public static boolean isCaptainOnly(String name) {
        if ("workspaces".equals(name)) return true;
        if ("import_project_image".equals(name)) return true;
        return java.util.Arrays.asList("crew_spawn", "crew_stop", "crew_wait", "crew_list", "crew_send", "generate_bot_icon", "list_bots", "create_bot", "compile_bot_mascot", "deliver_file").contains(name);
    }

    private void runBot(Bot bot) {
        Thread worker = Thread.currentThread();
        bot.thread = worker;
        CancellationToken token = bot.token;
        final String cycleId = bot.cycleMetadata.cycleId;
        TerminalReport terminalReport = null;
        token.registerCancelAction(worker::interrupt);
        CoreAgentLoop loop = bot.loop;
        boolean lifecycleStarted = false;
        try {
            token.throwIfCancelled();
            validateCurrentProfile(bot.role);
            synchronized (bot.cycleLock) {
                bot.status = Status.RUNNING;
                bot.phase = "";
                bot.waitingReason = "";
                if (bot.startedAtMillis == 0) bot.startedAtMillis = System.currentTimeMillis();
            }
            bus.signalWaiters();
            publishMission(bot.missionId);
            lifecycle.start(bot);
            lifecycleStarted = true;
            if (loop == null) {
                CoreToolRegistry tools = toolFactory.create(bot, this);
                bot.workerTools = tools;
                CoreAgentLoop.TurnContextProvider incoming = new CoreAgentLoop.TurnContextProvider(){

                    @Override
                    public synchronized List<CoreAgentLoop.IncomingMessage> takeDurableMessages() {
                        List<CoreAgentLoop.IncomingMessage> incoming = new ArrayList<>();
                        for (CrewMessage message : bus.pending(bot.id)) {
                            incoming.add(CoreAgentLoop.IncomingMessage.fromCrewMessage(message));
                        }
                        return incoming;
                    }

                    @Override
                    public String takeUntrustedContext() {
                        return "";
                    }

                    @Override
                    public void onRateLimit(boolean waiting) {
                        bot.status = waiting ? Status.WAITING : Status.RUNNING;
                        bot.waitingReason = waiting ? "limite del proveedor" : "";
                        bus.signalWaiters();
                        publishMission(bot.missionId);
                    }
                };
                bot.loop = loopFactory.create(bot, tools, incoming);
                loop = bot.loop;
                loop.restoreCheckpoint(bot.checkpoint);
                bot.transcript = loop.transcriptSnapshot();
            }
            final CoreAgentLoop invocationLoop = loop;
            loop.setCheckpointScope(cycleId);
            loop.setCheckpointListener(saved -> retainCheckpointForRun(bot, invocationLoop, token, cycleId, saved));
            while (true) {
                awaitIncomingDispatches(bot, token);
                token.throwIfCancelled();
                validateCurrentProfile(bot.role);
                synchronized (bot.cycleLock) {
                    bot.status = Status.RUNNING;
                    bot.waitingReason = "";
                    bot.reported = false;
                }
                String request = bot.completedCycles == 0 ? bot.mission : "";
                CoreAgentLoop.ProgressListener progress = progressFactory == null ? null : progressFactory.create(bot);
                CoreAgentLoop.Result result = resultPresentation.apply(loop.run(request, bot.transcript, token, progress));
                bot.transcript = loop.transcriptSnapshot();
                boolean didWork = result.turns != 0;
                if (didWork) bot.completedCycles++;
                synchronized (bot.cycleLock) {
                    if (didWork && !bot.reported) bot.cycleResult = result.text == null ? "" : result.text;
                }
                if (!"COMPLETED".equals(result.outcome)) {
                    // A provider failure or partial loop result is not task completion.
                    // Already-started jobs keep ownership and finish normally; explicit STOP
                    // still cancels them. Never launch another inference or replay an effect here.
                    awaitOwnedWorkTerminal(bot, token);
                    token.throwIfCancelled();
                    synchronized (bot.cycleLock) {
                        if (result.turns != 0) {
                            bot.result = result.text;
                            bot.cycleResult = result.text == null ? "" : result.text;
                            bot.reported = true;
                        }
                        invalidateCoordinationLocked(bot);
                        bot.status = "STOPPED".equals(result.outcome) ? Status.STOPPED
                                : "FAILED".equals(result.outcome) ? Status.FAILED : Status.PARTIAL;
                        bot.waitingReason = "";
                        bot.finishedAtMillis = System.currentTimeMillis();
                        if (checkpointSupport != null && bot.role.profileVersion > 0) bot.requiresExplicitResume = true;
                        terminalReport = new TerminalReport(bot.status, bot.cycleResult, bot.finishedAtMillis);
                    }
                    if (result.turns != 0) {
                        bus.send(bot.id, "chief", CrewMessage.Type.RESULT, bot.result, Collections.emptyList());
                    }
                    bus.signalWaiters();
                    publishMission(bot.missionId);
                    return;
                }
                awaitOwnedWork(bot, token);
                token.throwIfCancelled();
                boolean complete;
                synchronized (bot.cycleLock) {
                    complete = bot.incomingDispatches.get() == 0 && !bus.hasMessages(bot.id) && !hasPendingOwnedWork(bot);
                    if (complete) {
                        if (didWork && !bot.reported) {
                            bot.result = result.text;
                            bus.send(bot.id, "chief", CrewMessage.Type.RESULT, result.text, Collections.emptyList());
                        }
                        invalidateCoordinationLocked(bot);
                        bot.status = Status.DONE;
                        bot.waitingReason = "";
                        bot.finishedAtMillis = System.currentTimeMillis();
                        terminalReport = new TerminalReport(Status.DONE, bot.cycleResult, bot.finishedAtMillis);
                    } else bot.status = Status.RUNNING;
                }
                bus.signalWaiters();
                publishMission(bot.missionId);
                if (complete) return;
            }
        } catch (java.util.concurrent.CancellationException stopped) {
            synchronized (bot.cycleLock) {
                terminalReport = new TerminalReport(Status.STOPPED, bot.cycleResult, System.currentTimeMillis());
            }
            cancelOwnedWork(bot);
            if (loop != null) bot.transcript = loop.transcriptSnapshot();
            synchronized (bot.cycleLock) {
                bot.status = Status.STOPPED;
                if (checkpointSupport != null && bot.role.profileVersion > 0) bot.requiresExplicitResume = true;
                bot.waitingReason = "";
                bot.finishedAtMillis = System.currentTimeMillis();
                if (bot.error.isEmpty()) bot.error = "Stopped";
                terminalReport = new TerminalReport(Status.STOPPED, terminalReport.text, bot.finishedAtMillis);
            }
            publishMission(bot.missionId);
        } catch (RuntimeException failure) {
            String failureText = "Bot " + bot.name + " failed: "
                    + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            terminalReport = new TerminalReport(Status.FAILED, failureText, System.currentTimeMillis());
            cancelOwnedWork(bot);
            if (loop != null) bot.transcript = loop.transcriptSnapshot();
            bot.error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            bot.result = failureText;
            synchronized (bot.cycleLock) {
                bot.cycleResult = failureText;
                bot.status = Status.FAILED;
                if (checkpointSupport != null && bot.role.profileVersion > 0) bot.requiresExplicitResume = true;
                bot.waitingReason = "";
                bot.finishedAtMillis = System.currentTimeMillis();
                if (bus.hasMatching(bot.id, (message)->"user".equals(message.from) && message.type == CrewMessage.Type.USER)) bot.reanimationRequested = true;
                terminalReport = new TerminalReport(Status.FAILED, failureText, bot.finishedAtMillis);
            }
            bus.send(bot.id, "chief", CrewMessage.Type.RESULT, bot.result, Collections.emptyList());
            publishMission(bot.missionId);
        } finally {
            if (lifecycleStarted) try {
                lifecycle.end(bot);
            } catch (RuntimeException ignored) {
            }
            boolean restart = false;
            CrewResultEvidence evidence = null;
            java.util.concurrent.CountDownLatch completedLatch = bot.terminated;
            synchronized (bot.cycleLock) {
                invalidateCoordinationLocked(bot);
                // Capture before replacing the generation, but never call persistence/model code here.
                if (terminalReport != null && !cycleId.isEmpty()) {
                    evidence = CrewResultEvidence.create(conversationId, bot.missionId, bot.id, cycleId,
                            terminalReport.status, terminalReport.text, terminalReport.producedAtMillis);
                    bot.cycleMetadata = new CycleMetadata(cycleId, evidence);
                }
                bot.thread = null;
                if (!bot.requiresExplicitResume && bot.reanimationRequested && bus.hasMessages(bot.id)) {
                    bot.reanimationRequested = false;
                    invalidateCoordinationLocked(bot);
                bot.token = bot.freshToken();
                    bot.cycleMetadata = CycleMetadata.fresh();
                    bot.cycleResult = "";
                    bot.stopping = false;
                    bot.status = Status.RUNNING;
                    bot.waitingReason = "";
                    bot.error = "";
                    bot.reported = false;
                    bot.terminated = new java.util.concurrent.CountDownLatch(1);
                    restart = true;
                } else {
                    bot.cycleScheduled = false;
                }
            }
            // Delivery is separate from execution. Its failure cannot become a second FAILED result.
            // It also cannot suppress termination signaling or the already admitted next cycle.
            try { deliverResultEvidence(evidence); }
            finally {
                completedLatch.countDown();
                bus.signalWaiters();
                publishMission(bot.missionId);
                if (restart) executor.execute(()->runBot(bot));
            }
        }
    }

    public void registerOwnedWork(Bot bot, OwnedWork work) {
        if (bot == null || bots.get(bot.id) != bot || work == null) throw new IllegalArgumentException("Owned work requires this manager\'s bot");
        bot.ownedWork.addIfAbsent(work);
    }

    public boolean hasPendingOwnedWork(Bot bot) {
        for (OwnedWork work : bot.ownedWork) if (work.pending()) return true;
        return false;
    }

    public void observeOwnedWork(Bot bot, long tokenGeneration, String eventId, String summary) {
        if (bot == null || bots.get(bot.id) != bot || eventId == null || eventId.trim().isEmpty() || summary == null || summary.trim().isEmpty()) return;
        synchronized (bot.cycleLock) {
            if (bot.token.generation() != tokenGeneration) return;
            try {
            String observationKey = tokenGeneration + ":" + eventId;
            if (bot.observedWork.contains(observationKey)) return;
            String text = "Owned job observation [" + eventId + "]: " + summary;
            String id = "owned-job-" + UUID.nameUUIDFromBytes((bot.id + ":" + observationKey)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            CrewMessage receipt = new CrewMessage(id, conversationId, "owned-work", bot.id,
                    CrewMessage.Type.FINDING, text, Collections.emptyList(), System.currentTimeMillis());
            if ((terminal(bot.status) && bot.status != Status.DONE) || bot.requiresExplicitResume || bot.token.isCancellationRequested() || bot.stopping) {
                // Cleanup can become terminal before its callback arrives. Preserve that receipt
                // for explicit reconciliation, without waking a stopped/partial inference cycle.
                bus.restore(Collections.singletonList(receipt), Collections.singletonList(receipt));
                persistCheckpoint(bot);
            } else {
                dispatch(bot, "owned-work", bot.id, CrewMessage.Type.FINDING, text, Collections.emptyList(), false, receipt);
                bot.reported = false;
            }
            bot.observedWork.add(observationKey);
            publishMission(bot.missionId);
            } finally {
                bot.cycleLock.notifyAll();
                bus.signalWaiters();
            }
        }
    }

    private void awaitOwnedWork(Bot bot, CancellationToken token) {
        Runnable wake = ()->{
            synchronized (bot.cycleLock) {
                bot.cycleLock.notifyAll();
            }
        };
        Runnable unregister = token.registerCancelAction(wake);
        try {
            synchronized (bot.cycleLock) {
                while (hasPendingOwnedWork(bot) && !bus.hasMessages(bot.id) && !token.isCancelled()) {
                    bot.status = Status.WAITING;
                    bot.waitingReason = "trabajo del proyecto";
                    bus.signalWaiters();
                    publishMission(bot.missionId);
                    try {
                        bot.cycleLock.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        token.throwIfCancelled();
                    }
                }
            }
            token.throwIfCancelled();
        } finally {
            unregister.run();
        }
    }

    private void awaitOwnedWorkTerminal(Bot bot, CancellationToken token) {
        Runnable wake = () -> { synchronized (bot.cycleLock) { bot.cycleLock.notifyAll(); } };
        Runnable unregister = token.registerCancelAction(wake);
        try {
            synchronized (bot.cycleLock) {
                while (hasPendingOwnedWork(bot)) {
                    token.throwIfCancelled();
                    bot.status = Status.WAITING;
                    bot.waitingReason = "trabajo del proyecto";
                    publishMission(bot.missionId);
                    try { bot.cycleLock.wait(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); token.throwIfCancelled(); }
                }
            }
            token.throwIfCancelled();
        } finally { unregister.run(); }
    }

    private void cancelOwnedWork(Bot bot) {
        for (OwnedWork work : bot.ownedWork) work.cancelAndAwait();
    }

    private void awaitIncomingDispatches(Bot bot, CancellationToken token) {
        Runnable wake = ()->{
            synchronized (bot.cycleLock) {
                bot.cycleLock.notifyAll();
            }
        };
        Runnable unregister = token.registerCancelAction(wake);
        try {
            synchronized (bot.cycleLock) {
                while (bot.incomingDispatches.get() > 0 && !token.isCancelled()) {
                    try {
                        bot.cycleLock.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        token.throwIfCancelled();
                    }
                }
            }
            token.throwIfCancelled();
        } finally {
            unregister.run();
        }
    }

    public CrewMessage send(String from, String to, CrewMessage.Type type, String text, List<String> refs) {
        Bot sender = "chief".equals(from) ? null : bots.get(from);
        if (!"chief".equals(from) && sender == null) throw new IllegalArgumentException("Unknown Crew sender");
        if (sender != null && terminal(sender.status)) throw new IllegalArgumentException("A stopped or finished bot cannot send messages");
        if (sender != null && sender.role.missionAccess == CrewMissionAccess.READ_ONLY && !"chief".equals(to)) {
            throw new IllegalArgumentException("Read-only missions can message only the captain; they cannot dispatch work to another bot");
        }
        Bot recipient = "chief".equals(to) ? null : bots.get(to);
        if (!"chief".equals(to) && recipient == null) throw new IllegalArgumentException("Unknown Crew recipient");
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("text must be non-empty");
        if (sender != null && recipient == null && coordinationEvidenceListener != null && coordinationKind(type))
            return produceCoordination(sender, sender.token, type, text, refs, false);
        CrewMessage message = dispatch(recipient, from, to, type, text, refs, false);
        if (sender != null) publishMission(sender.missionId);
        if (recipient != null && (sender == null || !recipient.missionId.equals(sender.missionId))) publishMission(recipient.missionId);
        return message;
    }

    public CrewMessage sendFromWorker(Bot bot, CancellationToken expectedToken, String to,
            CrewMessage.Type type, String text, List<String> refs) {
        if (bots.get(bot.id) != bot) throw new IllegalArgumentException("Unknown worker");
        if (coordinationEvidenceListener != null && "chief".equals(to) && coordinationKind(type))
            return produceCoordination(bot, expectedToken, type, text, refs, false);
        synchronized (bot.cycleLock) {
            if (bot.token != expectedToken) throw new IllegalStateException("Worker cycle changed");
            expectedToken.throwIfCancelled();
        }
        return send(bot.id, to, type, text, refs);
    }

    public CrewMessage sendUserMessageExact(String botId, CancellationToken expectedToken, String text) {
        Bot bot = bots.get(botId);
        if (bot == null) throw new IllegalArgumentException("Unknown worker");
        synchronized (bot.cycleLock) {
            if (bot.token != expectedToken) throw new IllegalStateException("Worker cycle changed");
            return sendUserMessage(botId, text);
        }
    }

    /** Native CORRECT only; the source authority is checked by the caller before this exact admission. */
    public CrewMessage sendUserMessageExact(String botId, CancellationToken expectedToken, String text, CrewInputBinding inputBinding) {
        Bot bot = bots.get(botId);
        if (bot == null) throw new IllegalArgumentException("Unknown worker");
        synchronized (bot.cycleLock) {
            if (bot.token != expectedToken) throw new IllegalStateException("Worker cycle changed");
            if (bot.status == Status.INTERRUPTED || bot.requiresExplicitResume)
                throw new IllegalArgumentException("Bot is not available for user messages");
            validateInputBinding(bot, text, inputBinding, CrewInputBinding.SourceKind.NATIVE_CORRECT);
            CrewMessage message = new CrewMessage(inputBinding.mailboxMessageId, conversationId, "user", bot.id,
                    CrewMessage.Type.USER, text, Collections.emptyList(), System.currentTimeMillis()).withInputBinding(inputBinding);
            CrewMessage admitted = dispatch(bot, "user", bot.id, CrewMessage.Type.USER, text, Collections.emptyList(), true, message);
            publishMission(bot.missionId);
            return admitted;
        }
    }

    private void validateInputBinding(Bot bot, String text, CrewInputBinding binding, CrewInputBinding.SourceKind kind) {
        if (binding == null || text == null || text.trim().isEmpty() || binding.sourceKind != kind
                || !binding.executionSessionId.equals(conversationId) || !binding.botId.equals(bot.id)
                || !binding.missionId.equals(bot.missionId) || !binding.admissionCycleId.equals(bot.cycleMetadata.cycleId))
            throw new IllegalArgumentException("Input binding does not match the admitted worker cycle");
        CrewMessage message = new CrewMessage(binding.mailboxMessageId, conversationId,
                kind == CrewInputBinding.SourceKind.NATIVE_CORRECT ? "user" : "chief", bot.id,
                kind == CrewInputBinding.SourceKind.NATIVE_CORRECT ? CrewMessage.Type.USER : CrewMessage.Type.ANSWER,
                text, Collections.emptyList(), System.currentTimeMillis());
        if (!binding.matchesDelivery(message)) throw new IllegalArgumentException("Exact input payload changed");
    }

    public CrewMessage sendUserMessage(String botId, String text) {
        Bot bot = bots.get(botId);
        if (bot == null || bot.status == Status.INTERRUPTED || bot.requiresExplicitResume) throw new IllegalArgumentException("Bot is not available for user messages");
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("text must be non-empty");
        CrewMessage message = dispatch(bot, "user", botId, CrewMessage.Type.USER, text.trim(), Collections.emptyList(), true);
        publishMission(bot.missionId);
        return message;
    }

    private CrewMessage dispatch(Bot recipient, String from, String to, CrewMessage.Type type, String text, List<String> refs, boolean explicitUser) {
        return dispatch(recipient, from, to, type, text, refs, explicitUser, null);
    }

    private CrewMessage dispatch(Bot recipient, String from, String to, CrewMessage.Type type, String text, List<String> refs, boolean explicitUser, CrewMessage receipt) {
        if (recipient == null) return bus.send(from, to, type, text, refs);
        validateCurrentProfile(recipient.role);
        boolean launchWorker = false;
        boolean dispatchPersisted = false;
        recipient.incomingDispatches.incrementAndGet();
        try {
            synchronized (recipient.cycleLock) {
                Status status = recipient.status;
                if (recipient.requiresExplicitResume || status == Status.INTERRUPTED) throw new IllegalArgumentException("Interrupted bots cannot be reanimated");
                if ((status == Status.STOPPED || status == Status.FAILED || status == Status.PARTIAL || recipient.stopping) && !explicitUser) throw new IllegalArgumentException("Only an explicit user message can reanimate a stopped or failed bot");
                if (status == Status.DONE || status == Status.STOPPED || status == Status.FAILED || status == Status.PARTIAL || recipient.stopping) {
                    MissionState mission = missions.get(recipient.missionId);
                    if (mission != null && !"RUNNING".equals(mission.status) && !"PARTIAL".equals(mission.status)) {
                        mission.status = "RUNNING";
                        mission.synthesis = "";
                        mission.finishedAtMillis = 0;
                    }
                    if (recipient.cycleScheduled) {
                        recipient.reanimationRequested = true;
                        recipient.finishedAtMillis = 0;
                    } else {
                        // Every new quiescent cycle has its own cancellation/job identity, including DONE.
                        invalidateCoordinationLocked(recipient);
                        recipient.token = recipient.freshToken();
                        recipient.cycleMetadata = CycleMetadata.fresh();
                        recipient.cycleResult = "";
                        recipient.stopping = false;
                        recipient.waitingReason = "";
                        recipient.error = "";
                        recipient.reported = false;
                        recipient.finishedAtMillis = 0;
                        recipient.terminated = new java.util.concurrent.CountDownLatch(1);
                        recipient.cycleScheduled = true;
                        recipient.status = Status.RUNNING;
                        launchWorker = true;
                    }
                }
                if (explicitUser) invalidateCoordinationLocked(recipient);
                CrewMessage message = receipt;
                if (message == null) message = bus.send(from, to, type, text, refs);
                else if (message.inputBinding != null) bus.enqueueFresh(message);
                else bus.restore(Collections.singletonList(message), Collections.singletonList(message));
                if (recipient.status == Status.DONE || recipient.status == Status.STOPPED || recipient.status == Status.FAILED || recipient.status == Status.PARTIAL) recipient.status = Status.QUEUED;
                persistCheckpoint(recipient);
                dispatchPersisted = true;
                return message;
            }
        } finally {
            if (recipient.incomingDispatches.decrementAndGet() == 0) {
                synchronized (recipient.cycleLock) {
                    recipient.cycleLock.notifyAll();
                }
            }
            if (launchWorker && dispatchPersisted) executor.execute(()->runBot(recipient)); else if (launchWorker) {
                synchronized (recipient.cycleLock) {
                    recipient.cycleScheduled = false;
                    recipient.reanimationRequested = false;
                    recipient.status = Status.FAILED;
                    recipient.error = "Message checkpoint failed; worker was not started";
                    recipient.terminated.countDown();
                }
            }
        }
    }

    /** Presentation only: no messages to the model, deadlines, retries or worker state changes. */
    public void recordPhase(Bot bot, String stage) {
        recordPhase(bot, stage, bot.token, bot.completedCycles);
    }

    public void recordPhase(Bot bot, String stage, CancellationToken cycleToken, long cycle) {
        if (stage == null) return;
        switch (stage) {
            case "model_wait": case "model_response": case "compacting": case "compacted":
            case "compaction_error": case "tool_call": case "tool_result": case "tool_error": break;
            default: return;
        }
        synchronized (bot.cycleLock) {
            if (bots.get(bot.id) != bot || bot.token != cycleToken || bot.completedCycles != cycle
                    || terminal(bot.status) || bot.stopping || bot.token.isCancelled()) return;
            bot.phase = stage;
            // Starting a wait is not new model output. Preserve the last observable event.
            if (!"model_wait".equals(stage) || bot.lastProgressAtMillis == 0L) {
                bot.lastProgress = stage;
                bot.lastProgressAtMillis = System.currentTimeMillis();
            }
        }
        publishPresentation(bot.missionId);
    }

    public void recordToolActivity(Bot bot, com.jarvys.agent.ToolActivity activity) {
        recordToolActivity(bot, activity, bot.token, bot.completedCycles);
    }

    public void recordToolActivity(Bot bot, com.jarvys.agent.ToolActivity activity, CancellationToken cycleToken, long cycle) {
        if (bots.get(bot.id) != bot) return;
        // A terminal observation may arrive after Stop. It resolves this invocation only and never resumes work.
        recordPhase(bot, activity.stage, cycleToken, cycle);
        bus.recordActivity(bot.id, activity);
        publishMission(bot.missionId);
    }

    public void recordActivity(Bot bot, String activity) {
        if (activity == null || activity.trim().isEmpty() || terminal(bot.status)) return;
        bus.record(bot.id, "chief", CrewMessage.Type.STATUS, activity.trim(), Collections.emptyList());
        publishMission(bot.missionId);
    }

    public List<CrewMessage> askChief(Bot bot, String question) {
        return askChief(bot, question, bot.token);
    }

    public List<CrewMessage> askChief(Bot bot, String question, CancellationToken expectedToken) {
        CrewMessage source = null;
        CoordinationRequest request = null;
        boolean exact = coordinationEvidenceListener != null;
        try {
            if (exact) {
                source = produceCoordination(bot, expectedToken, CrewMessage.Type.QUESTION, question, Collections.emptyList(), true);
                synchronized (bot.cycleLock) { request = bot.coordinationRequests.get(source.coordination.evidenceId); }
                if (request == null || !request.valid) return Collections.emptyList();
            } else {
                synchronized (bot.cycleLock) {
                    if (bot.token != expectedToken) throw new IllegalStateException("Worker cycle changed");
                    expectedToken.throwIfCancelled();
                    bot.status = Status.WAITING;
                    bot.waitingReason = "respuesta del capitán";
                    bus.send(bot.id, "chief", CrewMessage.Type.QUESTION, question, Collections.emptyList());
                }
                publishMission(bot.missionId);
            }
            final CrewMessage expectedSource = source;
            final CoordinationRequest pending = request;
            Predicate<CrewMessage> matchingAnswer = message -> "chief".equals(message.from)
                    && message.type == CrewMessage.Type.ANSWER
                    && (!exact || (message.coordination != null
                        && message.coordination.evidenceId.equals(expectedSource.coordination.evidenceId)
                        && message.coordination.contentSha256.equals(expectedSource.coordination.contentSha256)));
            bus.await(bot.id, message -> matchingAnswer.test(message)
                            || ("user".equals(message.from) && message.type == CrewMessage.Type.USER),
                    () -> exact && !pending.valid, expectedToken);
            List<CrewMessage> answer = new ArrayList<>();
            synchronized (bot.cycleLock) {
                if (exact && (!pending.valid || !liveCoordinationCycle(bot, expectedToken, pending.evidence.cycleId)))
                    return Collections.emptyList();
                for (CrewMessage message : bus.pending(bot.id)) if (matchingAnswer.test(message)) {
                    answer.add(message); bot.pendingAnswerIds.add(message.id);
                }
            }
            return answer;
        } finally {
            synchronized (bot.cycleLock) {
                removeCoordinationLocked(bot, request);
                if (bot.token == expectedToken && !expectedToken.isCancellationRequested()
                        && !terminal(bot.status) && !bot.stopping) {
                    bot.status = Status.RUNNING;
                    bot.waitingReason = "";
                }
            }
            bus.signalWaiters();
            publishMission(bot.missionId);
        }
    }

    public void reportDone(Bot bot, String result, List<String> refs) {
        if (result == null || result.trim().isEmpty()) throw new IllegalArgumentException("result must be non-empty");
        if (hasPendingOwnedWork(bot)) throw new IllegalStateException("Owned project jobs are still pending; wait for their terminal result before reporting done");
        synchronized (bot.cycleLock) {
            bot.result = result.trim();
            bot.cycleResult = bot.result;
            bot.reported = true;
        }
        send(bot.id, "chief", CrewMessage.Type.RESULT, bot.result, refs);
    }

    void status(Bot bot, Status status) {
        bot.status = status;
        if (status != Status.WAITING) bot.waitingReason = "";
        bus.signalWaiters();
        publishMission(bot.missionId);
    }

    public List<CrewMessage> waitFor(List<String> ids, WaitMode mode, CancellationToken token) {
        List<String> expected = ids == null ? Collections.emptyList() : new ArrayList<>(ids);
        for (String id : expected) if (!bots.containsKey(id)) throw new IllegalArgumentException("Unknown bot id: " + id);
        Predicate<CrewMessage> messageReady = mode == WaitMode.ALL && !expected.isEmpty() ? (message)->!expected.contains(message.from) : (ignored)->true;
        bus.await("chief", messageReady, ()->terminal(expected, mode), token);
        return bus.drain("chief");
    }

    private boolean terminal(List<String> ids, WaitMode mode) {
        if (ids.isEmpty()) return bots.values().stream().noneMatch((bot)->bot.status == Status.QUEUED || bot.status == Status.RUNNING || bot.status == Status.WAITING);
        if (mode == WaitMode.ALL) return ids.stream().allMatch((id)->terminal(bots.get(id).status));
        return ids.stream().anyMatch((id)->terminal(bots.get(id).status));
    }

    private static boolean terminal(Status status) {
        return status == Status.DONE || status == Status.FAILED || status == Status.STOPPED || status == Status.INTERRUPTED || status == Status.PARTIAL;
    }

    public Bot bot(String id) {
        return bots.get(id);
    }

    public List<Bot> bots() {
        return Collections.unmodifiableList(new ArrayList<>(bots.values()));
    }

    /** A late parent STOP may cancel only the cycle that parent actually admitted. */
    public boolean stopForUserControl(String id, CancellationToken expectedToken) {
        Bot bot = bots.get(id);
        if (bot == null) throw new IllegalArgumentException("Unknown worker");
        synchronized (bot.cycleLock) {
            if (bot.token != expectedToken) throw new IllegalStateException("Worker cycle changed");
            return stop(id);
        }
    }

    public boolean stopExact(String id, CancellationToken expectedToken) {
        Bot bot = bots.get(id);
        if (bot == null) return false;
        synchronized (bot.cycleLock) {
            return bot.token == expectedToken && stop(id);
        }
    }

    public boolean stop(String id) {
        Bot bot = bots.get(id);
        if (bot == null) return false;
        synchronized (bot.cycleLock) {
            if (terminal(bot.status)) return false;
            boolean pending = hasPendingOwnedWork(bot);
            invalidateCoordinationLocked(bot);
            bot.stopping = true;
            if (checkpointSupport != null && bot.role.profileVersion > 0) bot.requiresExplicitResume = true;
            bot.reanimationRequested = false;
            bot.status = pending ? Status.WAITING : Status.STOPPED;
            bot.waitingReason = pending ? "deteniendo trabajo del proyecto" : "";
            CancellationToken token = bot.token;
            Thread thread = bot.thread;
            token.cancel();
            if (thread != null) thread.interrupt();
            bus.signalWaiters();
            publishMission(bot.missionId);
            return true;
        }
    }

    public void stopAll() {
        synchronized (missionAdmissionLock) {
            for (MissionState mission : missions.values()) {
                if (mission.explicitAdmission && "RUNNING".equals(mission.status)) {
                    mission.status = "STOPPED";
                    mission.finishedAtMillis = System.currentTimeMillis();
                }
            }
        }
        Set<String> stoppedMissions = new java.util.HashSet<>();
        for (Bot bot : bots.values()) {
            try {
                if (stop(bot.id)) stoppedMissions.add(bot.missionId);
            } catch (RuntimeException failure) {
                bot.recoveryNote = "Stopped, but the final checkpoint could not be saved; retained evidence may be incomplete";
                publishPresentation(bot.missionId);
            }
        }
        for (MissionState mission : missions.values()) if ("RUNNING".equals(mission.status) || ("PARTIAL".equals(mission.status) && stoppedMissions.contains(mission.id))) {
            mission.status = "STOPPED";
            mission.finishedAtMillis = System.currentTimeMillis();
            try {
                publishMission(mission.id);
            } catch (RuntimeException failure) {
                publishPresentation(mission.id);
            }
        }
    }

    public String describe(List<CrewMessage> messages) {
        StringBuilder output = new StringBuilder();
        for (CrewMessage message : messages) output.append(formatMessages(Collections.singletonList(message)));
        if (messages.isEmpty()) output.append("No new Crew messages.\n");
        output.append("\nBot states:\n");
        for (Bot bot : bots.values()) output.append(bot.id).append(" · ").append(bot.name).append(" · ").append(bot.status).append("\n");
        return output.toString();
    }

    public String describeBots() {
        if (bots.isEmpty()) return "No Crew bots have been spawned.";
        StringBuilder output = new StringBuilder("<crew_bots trust=\"untrusted\">\n");
        for (Bot bot : bots.values()) {
            output.append("<bot id=\"").append(xml(bot.id)).append("\" role=\"")
                    .append(xml(bot.role.name)).append("\" status=\"").append(xml(bot.status.name()))
                    .append("\"><name>").append(xml(bot.name)).append("</name><mission>")
                    .append(xml(bot.mission)).append("</mission><error>").append(xml(bot.error))
                    .append("</error><waiting>").append(xml(bot.waitingReason)).append("</waiting><result>")
                    .append(xml(bot.result)).append("</result><declared_tools>")
                    .append(xml(bot.workerTools == null ? "not initialized" : bot.workerTools.names().toString()))
                    .append("</declared_tools></bot>\n");
        }
        return output.append("</crew_bots>").toString();
    }

    public List<CrewMissionSnapshot> missionSnapshots() {
        List<CrewMissionSnapshot> snapshots = new ArrayList<>();
        for (MissionState mission : missions.values()) snapshots.add(snapshot(mission));
        snapshots.sort(java.util.Comparator.comparingLong((value)->value.startedAtMillis));
        return Collections.unmodifiableList(snapshots);
    }

    private void publishMission(String missionId) {
        for (Bot bot : botsFor(missionId)) persistCheckpoint(bot);
        publishPresentation(missionId);
    }

    private void publishPresentation(String missionId) {
        MissionState mission = missions.get(missionId);
        if (mission == null) return;
        mission.presentationGeneration.incrementAndGet();
        if (!mission.presenting.compareAndSet(false, true)) return;
        RuntimeException deliveryFailure = null;
        for (;;) {
            long observed = mission.presentationGeneration.get();
            try {
                SnapshotListener listener = snapshotListener;
                if (listener != null && !botsFor(missionId).isEmpty()) listener.onChanged(snapshot(mission));
            } catch (RuntimeException failure) {
                if (deliveryFailure == null) deliveryFailure = failure;
            } finally {
                mission.presenting.set(false);
            }
            // One drainer orders snapshots and callbacks. Other publishers only mark dirty and
            // never wait while holding a worker lock. Recheck after release to avoid lost updates.
            if (mission.presentationGeneration.get() == observed || !mission.presenting.compareAndSet(false, true)) {
                if (deliveryFailure != null) throw deliveryFailure;
                return;
            }
        }
    }

    private List<Bot> botsFor(String missionId) {
        List<Bot> result = new ArrayList<>();
        for (Bot bot : bots.values()) if (missionId.equals(bot.missionId)) result.add(bot);
        return result;
    }

    private CrewMissionSnapshot snapshot(MissionState mission) {
        List<Bot> missionBots = botsFor(mission.id);
        java.util.Set<String> ids = new java.util.HashSet<>();
        List<CrewBotSnapshot> botSnapshots = new ArrayList<>();
        for (Bot bot : missionBots) {
            ids.add(bot.id);
            CycleMetadata metadata = bot.cycleMetadata;
            botSnapshots.add(new CrewBotSnapshot(bot.id, bot.role.id, bot.role.name, bot.name, bot.role.colorKey, bot.mission, bot.status.name(), bot.error, bot.result, bot.waitingReason, bot.role.tools, bot.startedAtMillis, bot.finishedAtMillis, bot.canResume(), bot.recoveryNote, bot.requiresExplicitResume, bot.phase, bot.lastProgress, bot.lastProgressAtMillis, metadata.cycleId, metadata.evidence));
        }
        List<CrewMessage> missionMessages = new ArrayList<>();
        for (CrewMessage message : bus.snapshot()) if (ids.contains(message.from) || ids.contains(message.to)) missionMessages.add(message);
        CrewMissionTitle title = mission.title.get();
        boolean workersActive = botSnapshots.stream().anyMatch(CrewBotSnapshot::active);
        long finishedAt = workersActive ? 0L : mission.finishedAtMillis;
        if (!workersActive && finishedAt > 0L) for (CrewBotSnapshot bot : botSnapshots) finishedAt = Math.max(finishedAt, bot.finishedAtMillis);
        return new CrewMissionSnapshot(mission.id, conversationId, CrewProcessIdentity.ID, title.title,
                mission.originalInstructions, title.source, mission.status, mission.synthesis,
                mission.startedAtMillis, finishedAt, botSnapshots, missionMessages);
    }

    public static String formatMessages(List<CrewMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder body = new StringBuilder();
        for (CrewMessage message : messages) {
            if (message.activity != null) continue;
            body.append("<crew_message id=\"").append(xml(message.id)).append("\" from=\"").append(xml(message.from)).append("\" type=\"").append(message.type.name()).append("\" trust=\"untrusted\">\n")
                    .append("<content>").append(xml(message.text)).append("</content>");
            if (!message.refs.isEmpty()) {
                body.append("\n<references>");
                for (String reference : message.refs) body.append("<reference>").append(xml(reference)).append("</reference>");
                body.append("</references>");
            }
            body.append("\n</crew_message>\n");
        }
        return body.toString();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\'", "&apos;");
    }

    @Override
    public void close() {
        stopAll();
        unregisterProfiles.getAndSet(() -> { }).run();
        unregisterCaptain.getAndSet(()->{
        }).run();
        executor.shutdownNow();
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (InterruptedException stopWaiting) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
