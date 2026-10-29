package com.jarvys.agent.linux

import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CodingJobConversationGuard
import com.jarvys.agent.EffectInvocation
import com.jarvys.agent.MemoryWriteGate
import com.jarvys.agent.CrewCheckpointStore
import com.jarvys.agent.coding.CodingCancellation
import com.jarvys.agent.chatdb.CodingCancellationCheckpoint
import com.jarvys.agent.coding.CodingJobEvidence
import com.jarvys.agent.coding.CodingJobJournalCodec
import com.jarvys.agent.coding.CodingJobJournalStore
import com.jarvys.agent.coding.CodingJobOwnerLease
import com.jarvys.agent.coding.CodingJobSnapshot
import com.jarvys.agent.coding.CodingJobSubmission
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.CodingJobReservation
import com.jarvys.agent.coding.ProjectScope
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Durable, owner-scoped jobs. Restoring a record never launches or signals a process. */
class CodingJobManager(
    filesDir: File,
    private val backend: Backend,
    private val redactor: (String) -> String = { it },
    private val executor: Executor = Executor { work -> Thread(work, "jarvys-project-job").apply { isDaemon = true; start() } },
    private val redactionBufferChars: Int = 65_536,
) {
    fun interface Backend {
        fun execute(scope: ProjectScope, command: String, cwd: String, timeoutMillis: Long?,
                    token: CancellationToken, callback: LinuxOutputCallback, beforeLaunch: () -> Unit): LinuxExecResult
    }
    enum class State(val terminal: Boolean) {
        LAUNCH_INTENT(false), RUNNING(false), CANCELLING(false), SUCCEEDED(true), FAILED(true),
        NOT_STARTED(true), CANCELLED(true), TIMED_OUT(true), INTERRUPTED(true), UNCERTAIN(true)
    }
    /** Executor reported failure after the worker claimed ownership; observed facts are historical, not a receipt. */
    class EnqueueOutcomeUncertain internal constructor(val jobId: String, val observedState: State,
        val observedExitCode: Int?, cause: Exception) : IOException(
        "Job delivery outcome is uncertain; inspect project_jobs and do not repeat the command", cause)
    /** Technical projection is separate from the observed command state and never permits dispatch. */
    enum class TechnicalProjection { NOT_APPLICABLE, PENDING, CONFIRMED }
    /** Factual cancellation-request result, never process cleanup or command completion. */
    enum class CancellationKind { REQUESTED, ALREADY_REQUESTED, ALREADY_TERMINAL }
    sealed interface CancellationDisposition {
        val snapshot: Snapshot
        val kind: CancellationKind
        val journalConfirmed: Boolean
        val admitted: Boolean
        val ledgerConfirmed: Boolean
    }
    private data class CancelDisposition(override val snapshot: Snapshot,
        override val kind: CancellationKind, override val journalConfirmed: Boolean,
        override val admitted: Boolean, override val ledgerConfirmed: Boolean = false,
        val cancellingCommit: CodingJobJournalStore.Commit? = null) : CancellationDisposition
    private enum class WorkerClaim { RESERVED, CLAIMED, REJECTED, FINALIZING, FINISHED }
    data class Snapshot(val id: String, val owner: String, val scopeId: String, val cwd: String,
        val redactedCommand: String, val state: State, val createdAt: Long,
        val startedAt: Long? = null, val finishedAt: Long? = null, val exitCode: Int? = null,
        val logArtifact: String = "coding-job:$id/log", val error: String? = null,
        val logBytes: Long = 0L, val logComplete: Boolean = false)
    data class RecoverySummary(val id: String, val state: State, val exitCode: Int? = null)
    data class RecoveryEvidence(val id: String, val state: State, val exitCode: Int?, val logArtifact: String,
        val logBytes: Long, val logComplete: Boolean, val legacyIdentity: Boolean, val detail: String?)
    data class LogPage(val snapshot: Snapshot, val text: String, val offset: Long, val nextOffset: Long, val hasMore: Boolean)
    private data class RecoveredAccess(val scopeId: String, val projectRoot: String, val durableIdentity: String, val owners: Set<String>)
    private class Job(var snapshot: Snapshot, val projectRoot: String, val conversationId: String, val durableIdentity: String?) {
        val lock = Any()
        val initialSnapshot = snapshot
        var desiredTerminal: Snapshot? = null
        @Volatile var pendingTechnical: PendingTechnical? = null
        @Volatile var projection = TechnicalProjection.NOT_APPLICABLE
        val repairRunning = java.util.concurrent.atomic.AtomicBoolean()
        val claim = AtomicReference(WorkerClaim.RESERVED)
        val finished = CountDownLatch(if (snapshot.state.terminal) 0 else 1)
        val token = CancellationToken.cancellable()
        var unregister = Runnable { }
        var launchAttempted = false
        var recovered = false
        @Volatile var recoveryScopeBlocked = false
        var recoveryRecord: CodingJobEvidence.Record? = null
        var expectedRecoveryBinding: CodingJobEvidence.Binding? = null
        var recoveryVersionInvalidated=false
        var needsReconciliation = false
        var technical: Technical? = null
    }
    private class PendingTechnical(val submission: CodingJobSubmission, val bridge: ChatDatabaseBridge,
        val pin: CodingJobOwnerLease.Pin, val authorization: CodingJobEvidence.AuthorizationSnapshot)
    private class Technical(val submission: CodingJobSubmission, val reservation: CodingJobReservation,
        val bridge: ChatDatabaseBridge, val pin: CodingJobOwnerLease.Pin,
        val authorization: CodingJobEvidence.AuthorizationSnapshot) {
        var phase = CodingJobEvidence.Phase.SUBMISSION_INTENT
        var latest: CodingJobJournalStore.Commit? = null
        @Volatile var abandonedAcceptance = false
        fun persist(job: Job, value: Snapshot = job.snapshot) {
            val pair = record(job,value,latest?.record()?.revision ?: 0L)
            latest = submission.journal().write(reservation, pair.first.revision - 1, pair.first, pair.second)
        }
        fun record(job: Job, s: Snapshot, revision: Long): Pair<CodingJobEvidence.Record,CodingJobSnapshot> {
            val facts = if (!s.state.terminal) null else CodingJobEvidence.TerminalFacts(
                CodingJobEvidence.TerminalState.valueOf(s.state.name), s.exitCode,
                s.state == State.CANCELLED, s.state == State.TIMED_OUT, s.state == State.UNCERTAIN,
                CodingJobEvidence.AbsenceCoverage.NOT_PROVEN)
            val record = CodingJobEvidence.Record(reservation.binding(), Math.addExact(revision, 1L), phase,
                true, authorization, facts)
            val snapshot = CodingJobSnapshot(s.id,s.owner,s.scopeId,s.cwd,s.redactedCommand,job.projectRoot,
                job.conversationId, CodingJobSnapshot.State.valueOf(s.state.name),s.createdAt,s.startedAt,s.finishedAt,
                s.exitCode,s.logArtifact,s.error,s.logBytes,s.logComplete)
            return record to snapshot
        }
    }
    private data class InstrumentedStart(val invocation: EffectInvocation, val bridge: ChatDatabaseBridge,
        val authorization: CodingJobEvidence.AuthorizationSnapshot, val ticket: MemoryWriteGate.Ticket)
    private val applicationFiles = filesDir.canonicalFile
    private val ownerLifecycle = Any()
    private var managerQuiescing = false
    private val liveOwner = lazy { CodingJobOwnerLease.create(applicationFiles) }
    private fun pinSubmission(): CodingJobOwnerLease.Pin = synchronized(ownerLifecycle) {
        check(!managerQuiescing) { "Coding manager is quiescing" }
        liveOwner.value.pinSubmission()
    }
    /** Process cache stays quiescing until this future completes; no blocking close from a worker callback. */
    fun requestQuiesce(): java.util.concurrent.CompletableFuture<Void> {
        val owner = synchronized(ownerLifecycle) {
            managerQuiescing = true
            if (liveOwner.isInitialized()) liveOwner.value else null
        }
        // Completion callbacks are never run under the manager lifecycle monitor.
        return owner?.requestQuiesce() ?: java.util.concurrent.CompletableFuture.completedFuture(null)
    }
    /** One pass per explicit lifecycle event; concurrent events share only the in-flight pass.
     * Never invoked by list/status/getters or while holding a caller's cache/job/Room lock.
     */
    fun initializeRecoveryOnce(bridge: ChatDatabaseBridge) {
        val created=java.util.concurrent.CompletableFuture<Void>()
        while(true) {
            val running=recoveryInitialization.get()
            if(running!=null) {
                try { running.get(); return }
                catch(interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Recovery observation interrupted",interrupted) }
                catch(failure: java.util.concurrent.ExecutionException) {
                    if(failure.cause is Error)throw failure.cause as Error
                    throw IOException("Recovery initialization failed",failure.cause)
                }
            }
            if(recoveryInitialization.compareAndSet(null,created))break
        }
            try {
                val store=CodingJobJournalStore.forFilesRoot(applicationFiles)
                for((jobId,path) in recoveryCandidates.toMap()) {
                    val job=jobs[jobId] ?: continue
                    try {
                        // A later event never reuses a candidate from a previous observation.
                        var candidate=store.inspectExisting(path,MAX_JOURNAL_BYTES)
                        check(candidate.record().binding.jobId==jobId && candidate.record().binding==job.expectedRecoveryBinding) { "Recovery journal identity changed" }
                        conversations.active(candidate.snapshot().conversationId) { }
                        val identity=candidate.record().binding
                        CodingJobOwnerLease.probe(applicationFiles,identity.ownerProcessId,identity.managerInstanceId).use { probe ->
                            candidate=store.inspectExisting(path,MAX_JOURNAL_BYTES)
                            check(candidate.record().binding==job.expectedRecoveryBinding) { "Recovery binding changed" }
                            if(probe.liveness()==CodingJobOwnerLease.Liveness.DEAD) {
                                probe.requireDeadOwner(applicationFiles,identity.ownerProcessId,identity.managerInstanceId)
                                val uncertain=candidate.record().terminal==null ||
                                    candidate.record().terminal?.state==CodingJobEvidence.TerminalState.UNCERTAIN
                                if(!job.recoveryVersionInvalidated && uncertain && (candidate.record().highestEffectPhase==CodingJobEvidence.Phase.LAUNCH_INTENT ||
                                            candidate.snapshot().startedAt!=null)) {
                                    job.needsReconciliation=true
                                    // Existing writer/version invalidation, before allowing a new deliberate action.
                                    reconcile(com.jarvys.agent.coding.ProjectScopeStore(applicationFiles)
                                        .openExisting(candidate.snapshot().conversationId))
                                    job.recoveryVersionInvalidated=true
                                }
                                if(candidate.record().terminal!=null)job.recoveryScopeBlocked=false
                            }
                            val binding=bridge.verifyExistingCodingJobSync(candidate)
                            job.recoveryRecord=candidate.record() // Historical source match only, not settled execution.
                            if(candidate.record().terminal==null && probe.liveness()!=CodingJobOwnerLease.Liveness.DEAD) {
                                synchronized(job.lock) { job.snapshot=job.snapshot.copy(error="Recorded job owner is live or unknown; no takeover, replay or signal was attempted") }
                                return@use
                            }
                            val verified=if(candidate.record().terminal!=null) store.recertifyTerminal(candidate,binding)
                                else store.recoverDead(candidate,binding,probe)
                            // A genuinely dead owner permits new deliberate actions, but unprojected history stays UNKNOWN.
                            if(probe.liveness()==CodingJobOwnerLease.Liveness.DEAD) {
                                probe.requireDeadOwner(applicationFiles,identity.ownerProcessId,identity.managerInstanceId)
                                job.recoveryScopeBlocked=false
                            }
                            bridge.projectRecoveredCodingJobSync(verified)
                            synchronized(job.lock) {
                                job.snapshot=operationalSnapshot(verified.snapshot())
                                job.recoveryRecord=verified.record()
                                job.projection=TechnicalProjection.CONFIRMED
                                job.recoveryScopeBlocked=false
                            }
                            job.finished.countDown()
                            recoveryCandidates.remove(jobId)
                        }
                    } catch (_: Exception) {
                        synchronized(job.lock) {
                            job.snapshot=job.snapshot.copy(state=State.UNCERTAIN,exitCode=null,finishedAt=null,logComplete=false,
                                error="Recovered journal is unverified or inconsistent; original evidence retained and no command replayed")
                        }
                        recordProblem(hash(job.conversationId))
                    }
                }
                recoveryObserved=true
                created.complete(null)
            } catch(failure: Throwable) { created.completeExceptionally(failure); throw failure }
            finally { recoveryInitialization.compareAndSet(created,null) }
    }
    /** Pure readiness check for callers such as Resume that already hold a Crew cycle monitor. */
    fun requireRecoveryInitialized() {
        val observation=recoveryInitialization.get()
        check(recoveryObserved && (observation==null || (observation.isDone && !observation.isCompletedExceptionally))) {
            "Coding recovery lifecycle observation has not completed"
        }
    }
    fun requireNoRecoveredScopeTakeover(scope: ProjectScope) {
        check(jobs.values.none { it.recovered && it.recoveryScopeBlocked && matchesScope(it,scope) }) {
            "A recorded job still has live or unknown ownership; no scope takeover is permitted"
        }
    }
    /** Adds historical read ownership only for the exact RAW STARTED checkpoint that supplied the job. */
    fun correlatedCheckpointOwners(scope: ProjectScope, checkpoint: CrewCheckpointStore.ConfirmedSnapshot): List<String> = active(scope) {
        jobs.values.filter { job -> job.recovered && matchesScope(job,scope) &&
            job.recoveryRecord?.let {
                val source=it.binding.source.crew
                checkpoint.matchesCodingSource(source) && job.snapshot.owner==it.binding.owner &&
                    ownerBot(job.snapshot.owner,source.executionSessionId)==source.botId
            } == true }
            .map { it.snapshot.owner }.distinct()
    }
    private fun operationalSnapshot(s: CodingJobSnapshot) = Snapshot(s.id,s.owner,s.scopeId,s.cwd,s.redactedCommand,
        State.valueOf(s.state.name),s.createdAt,s.startedAt,s.finishedAt,s.exitCode,s.logArtifact,s.error,s.logBytes,s.logComplete)

    private val conversations = CodingJobConversationGuard(filesDir)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val recoveredAccess = ConcurrentHashMap<String, RecoveredAccess>()
    private val recoveryLock = Any()
    private val recoveryInitialization = AtomicReference<java.util.concurrent.CompletableFuture<Void>>()
    @Volatile private var recoveryObserved=false
    private val recoveryCandidates = LinkedHashMap<String,File>()
    private val restoreProblems = mutableListOf<String>()
    private val restoreProblemScopes = mutableMapOf<String, Int>()
    private val journalWriter: CodingJobJournalWriter
    private val root: File

    init {
        require(redactionBufferChars > 0) { "Pending-output resource budget must be positive" }
        val anchor = filesDir.canonicalFile
        require(anchor.isDirectory) { "App files directory is unavailable" }
        root = com.jarvys.agent.StorageGeneration(anchor).directory("crew-context")
        verifyPath(root)
        require(!root.exists() || root.isDirectory) { "Coding job storage is not a directory" }
        journalWriter = CodingJobJournalWriter(root)
        restore()
    }

    fun start(scope: ProjectScope, owner: String, command: String, cwd: String, expectedVersion: Long,
              timeoutMillis: Long?, token: CancellationToken, preflight: () -> Unit = { },
              completion: (Snapshot) -> Unit = { }): Snapshot =
        startInternal(scope,owner,command,cwd,expectedVersion,timeoutMillis,token,preflight,completion,null)

    /** Explicit integrated path; catalogue ASYNC opt-in remains gated separately. */
    fun startInstrumented(scope: ProjectScope, owner: String, command: String, cwd: String, expectedVersion: Long,
        timeoutMillis: Long?, token: CancellationToken, invocation: EffectInvocation, bridge: ChatDatabaseBridge,
        authorization: CodingJobEvidence.AuthorizationSnapshot, ticket: MemoryWriteGate.Ticket,
        preflight: () -> Unit = { }, completion: (Snapshot) -> Unit = { }): Snapshot =
        startInternal(scope,owner,command,cwd,expectedVersion,timeoutMillis,token,preflight,completion,
            InstrumentedStart(invocation,bridge,authorization,ticket))

    private fun startInternal(scope: ProjectScope, owner: String, command: String, cwd: String, expectedVersion: Long,
        timeoutMillis: Long?, token: CancellationToken, preflight: () -> Unit, completion: (Snapshot) -> Unit,
        instrumented: InstrumentedStart?): Snapshot {
        require(owner.isNotBlank()) { "A mission owner is required" }
        require(command.isNotBlank() && '\u0000' !in command) { "A nonempty command without NUL is required" }
        require(timeoutMillis == null || timeoutMillis > 0) { "Timeout must be positive" }
        token.throwIfCancelled()
        active(scope) { }
        val relative = scope.normalizePath(cwd)
        validateCwd(scope, relative)
        val project = scope.rootDirectory().canonicalPath
        check(root.path != project && !root.path.startsWith(project + File.separator)) { "Coding job journals must be outside the editable project" }
        reconcile(scope)
        requireNoRecoveredScopeTakeover(scope)
        val identity = scope.durableIdentity()
        val lease = scope.acquireWriter(owner, expectedVersion)
        val id = UUID.randomUUID().toString()
        val job = Job(Snapshot(id, owner, scope.id(), relative, sanitize(command), State.LAUNCH_INTENT,
            System.currentTimeMillis()), project, scope.conversationId(), identity)
        var pendingPin: CodingJobOwnerLease.Pin? = null
        var pendingSubmission: CodingJobSubmission? = null
        try {
            if (instrumented != null) {
                preflight(); token.throwIfCancelled()
                val pin = pinSubmission(); pendingPin = pin
                val submission = CodingJobSubmission.capture(instrumented.invocation,id,UUID.randomUUID().toString(),
                    owner,scope.id(),requireNotNull(identity),pin,instrumented.ticket,CodingJobJournalStore.forFilesRoot(applicationFiles))
                pendingSubmission = submission
                job.pendingTechnical = PendingTechnical(submission,instrumented.bridge,pin,instrumented.authorization)
                job.projection = TechnicalProjection.PENDING
                jobs[id] = job
                job.unregister = token.registerCancelAction { requestCancel(job) }
                val reservation = instrumented.bridge.reserveCodingJobSync(submission)
                submission.requireDispatchLive(); submission.requireFor(instrumented.invocation)
                job.technical = Technical(submission,reservation,instrumented.bridge,pin,instrumented.authorization)
            }
            val submissionCommit = synchronized(job.lock) {
                if (job.technical != null) job.technical!!.persist(job,job.initialSnapshot) else persist(job)
                job.technical?.latest
            }
            // A pending instrumented job is observable before reserve/journal IO, never a legacy job.
            if (jobs[id] !== job) {
                jobs[id] = job
                job.unregister = token.registerCancelAction { requestCancel(job) }
            }
            job.technical?.let { technical ->
                val acceptance = technical.bridge.acceptCodingJobSync(requireNotNull(submissionCommit))
                technical.submission.requireDispatchLive(); token.throwIfCancelled()
                instrumented!!.invocation.bindAcceptedSubmission(acceptance)
            }
            executor.execute {
                if (job.claim.compareAndSet(WorkerClaim.RESERVED, WorkerClaim.CLAIMED))
                    run(job, scope, command, timeoutMillis, lease, preflight, completion)
            }
            return snapshot(job)
        } catch (failure: Throwable) {
            if (jobs[id] !== job) {
                pendingSubmission?.abandonDispatch()
                try { lease.close() } finally { pendingPin?.close() }
            } else if (job.claim.compareAndSet(WorkerClaim.RESERVED, WorkerClaim.REJECTED)) {
                job.pendingTechnical?.submission?.abandonBeforeWorkerClaim()
                job.technical?.abandonedAcceptance = true
                // Only absence of this manager's worker claim, not absence of all earlier preparation.
                finish(job, lease, State.NOT_STARTED, null, "Job worker could not start", completion, false,
                    WorkerClaim.REJECTED)
            } else {
                // A running/finished worker owns finalization, even if execute ran inline and then threw.
                if (failure is Exception) {
                    val observed = snapshot(job)
                    throw EnqueueOutcomeUncertain(id, observed.state, observed.exitCode, failure)
                }
            }
            throw failure
        }
    }

    fun list(owner: String, scope: ProjectScope): List<Snapshot> {
        active(scope) { }
        reconcile(scope)
        val result = jobs.values.filter { matchesScope(it, scope) && canRead(owner, scope, it) }
            .map(::snapshot).sortedWith(compareBy<Snapshot> { it.createdAt }.thenBy { it.id })
        return active(scope) { result }
    }

    fun bindRecoveredOwners(owner: String, scope: ProjectScope, previousOwners: Collection<String>) {
        active(scope) { }
        val bot = ownerBot(owner, scope.conversationId())
        val allowed = previousOwners.filter { it != owner }.toSet()
        require(allowed.all { ownerBot(it, scope.conversationId()) == bot }) { "Recovered jobs must belong to the same conversation and bot" }
        val identity = scope.durableIdentity()
        check(jobs.values.none { it.snapshot.owner in allowed && matchesScope(it, scope) && (!snapshot(it).state.terminal || it.recoveryScopeBlocked) }) {
            "Previous jobs are still pending; stop and await the original owner before resuming"
        }
        recoveredAccess[owner] = RecoveredAccess(scope.id(), scope.rootDirectory().canonicalPath, identity, allowed)
    }

    fun checkpointEvidence(scope: ProjectScope, previousOwners: Collection<String>): List<RecoveryEvidence> {
        active(scope) { }
        previousOwners.forEach { ownerBot(it, scope.conversationId()) }
        val allowed = previousOwners.toSet()
        val result = jobs.values.filter { it.snapshot.owner in allowed && matchesScope(it, scope) }
            .sortedWith(compareBy<Job> { it.snapshot.createdAt }.thenBy { it.snapshot.id }).map { job ->
                val s = snapshot(job)
                RecoveryEvidence(s.id, s.state, s.exitCode, s.logArtifact, s.logBytes, s.logComplete,
                    job.durableIdentity == null, s.error)
            }
        return active(scope) { result }
    }

    fun hasPending(owner: String): Boolean = jobs.values.any { snapshot(it).let { s -> s.owner == owner && !s.state.terminal } }
    fun recoveryIssues(): List<String> = synchronized(recoveryLock) { restoreProblems.toList() }
    fun recoverySummaries(scope: ProjectScope): List<RecoverySummary> {
        active(scope) { }
        reconcile(scope)
        return active(scope) { jobs.values.filter { matchesScope(it, scope) && snapshot(it).state in setOf(State.UNCERTAIN, State.INTERRUPTED) }
            .map(::snapshot).sortedWith(compareBy<Snapshot> { it.createdAt }.thenBy { it.id })
            .map { RecoverySummary(it.id, it.state) } }
    }
    fun recoveryIssueCount(scope: ProjectScope): Int = active(scope) {
        val mismatched = jobs.values.count { it.conversationId == scope.conversationId() && !matchesScope(it, scope) }
        (restoreProblemScopes[hash(scope.conversationId())] ?: 0) + mismatched
    }

    fun read(owner: String, scope: ProjectScope, id: String, offset: Long, budget: Int): LogPage {
        require(offset >= 0 && budget >= 4) { "Nonnegative offset and at least four output bytes are required" }
        val job = owned(owner, scope, id)
        return synchronized(job.lock) { active(scope) {
            val file = logFile(job)
            val size = if (file.exists()) file.length() else 0L
            require(offset <= size) { "Log offset is beyond the available output" }
            if (offset == size) return@active LogPage(snapshot(job), "", offset, offset, false)
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                require(input.read() and 0xc0 != 0x80) { "Log offset must be on a UTF-8 boundary" }
                input.seek(offset)
                val bytes = ByteArray(minOf(budget.toLong(), 1_048_576L, size - offset).toInt())
                input.readFully(bytes)
                var count = bytes.size
                if (offset + count < size) {
                    var start = count - 1
                    while (start > 0 && bytes[start].toInt() and 0xc0 == 0x80) start--
                    val first = bytes[start].toInt() and 0xff
                    val width = when { first < 128 -> 1; first < 224 -> 2; first < 240 -> 3; else -> 4 }
                    if (count - start < width) count = start
                }
                LogPage(snapshot(job), String(bytes, 0, count, Charsets.UTF_8), offset, offset + count, offset + count < size)
            }
        } }
    }

    fun await(owner: String, scope: ProjectScope, id: String, waitMillis: Long, token: CancellationToken): Snapshot {
        require(waitMillis >= 0) { "Wait must be nonnegative" }
        val job = owned(owner, scope, id)
        val started = System.nanoTime()
        while (!snapshot(job).state.terminal) {
            token.throwIfCancelled()
            val remaining = waitMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            if (remaining <= 0) break
            try { job.finished.await(minOf(remaining, 100), TimeUnit.MILLISECONDS) }
            catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); token.throwIfCancelled(); throw interrupted }
        }
        token.throwIfCancelled()
        return active(scope) { snapshot(job) }
    }

    fun cancel(owner: String, scope: ProjectScope, id: String): Snapshot {
        val job = owned(owner, scope, id, allowRecoveredAccess = false)
        // A restored journal is evidence, never a process handle.
        if (!job.recovered) requestCancel(job)
        return active(scope) { snapshot(job) }
    }

    /** Admission and profile checks run outside job/conversation locks. Profile revalidation is
     * a preflight, not an atomic fence against subsequent profile changes. */
    fun cancelInstrumented(owner: String, scope: ProjectScope, id: String,
        callerToken: CancellationToken, validateCurrent: () -> Unit,
        admit: () -> Unit, invocation: EffectInvocation? = null,
        bridge: ChatDatabaseBridge? = null, semanticTicket: MemoryWriteGate.Ticket? = null,
        semanticSource: CodingJobEvidence.Source? = null): CancellationDisposition {
        validateCurrent()
        callerToken.throwIfCancelled()
        val job = owned(owner, scope, id, allowRecoveredAccess = false)
        var noOp: CancelDisposition? = null
        val technical = synchronized(job.lock) {
            check(jobs[id] === job && job.snapshot.owner == owner &&
                job.snapshot.scopeId == scope.id() && job.conversationId == scope.conversationId() && !job.recovered) {
                "Coding job is not a live cancellation target for this owner"
            }
            callerToken.throwIfCancelled()
            val targetTechnical = checkNotNull(job.technical) { "Legacy jobs have no technical cancellation reservation" }
            if (job.snapshot.state.terminal)
                noOp = CancelDisposition(job.snapshot, CancellationKind.ALREADY_TERMINAL, false, false)
            else if (job.token.isCancellationRequested)
                noOp = CancelDisposition(job.snapshot, CancellationKind.ALREADY_REQUESTED, false, false)
            targetTechnical
        }
        noOp?.let { observed ->
            // This immutable fact was captured under the job lock. Audit failure never requests cancellation.
            val confirmed = try {
                val currentInvocation = requireNotNull(invocation)
                val destination = requireNotNull(bridge)
                validateCurrent()
                callerToken.throwIfCancelled()
                val pin = pinSubmission()
                val issuer = liveOwner.value.cancellationIssuer()
                val observation = try { issuer.observe(currentInvocation, technical.reservation, pin,
                    if(observed.kind == CancellationKind.ALREADY_TERMINAL) CodingCancellation.Disposition.ALREADY_TERMINAL
                    else CodingCancellation.Disposition.ALREADY_REQUESTED,
                    CodingJobSnapshot.State.valueOf(observed.snapshot.state.name), observed.snapshot.exitCode,
                    requireNotNull(semanticTicket), semanticSource) }
                    catch (failure: Throwable) { pin.close(); throw failure }
                observation.use {
                    val checkpoint = destination.observeCodingCancellationSync(observation)
                    checkpoint.requireFor(observation)
                    try { destination.linkCodingCancelSemanticSync(checkpoint) } catch (_: Exception) {
                        // Optional semantics cannot change the exact technical observation or create a retry.
                    }
                }
                true
            } catch (_: Exception) { false }
            return observed.copy(ledgerConfirmed = confirmed)
        }
        val currentInvocation = requireNotNull(invocation) { "Cancellation requires its live invocation" }
        val destination = requireNotNull(bridge) { "Cancellation requires its technical destination" }
        val pin = pinSubmission()
        val issuer = liveOwner.value.cancellationIssuer()
        val request = try { issuer.capture(currentInvocation, technical.reservation, pin,
            requireNotNull(semanticTicket), semanticSource) }
            catch (failure: Throwable) { pin.close(); throw failure }
        request.use {
            var intent: CodingCancellationCheckpoint? = null
            var resultCaptured = false
            currentInvocation.addAdmissionWriter { observed ->
                intent = destination.admitCodingCancelSync(request, observed)
            }
            try {
                admit()
                val confirmedIntent = checkNotNull(intent) { "Cancellation intent was not confirmed" }
                confirmedIntent.requireIntent(request)
                request.requireFor(currentInvocation)
                request.requireDispatchLive()
                validateCurrent()
                callerToken.throwIfCancelled()
                // No profile/Room callback under this lock. Journal IO keeps its existing lock order.
                val disposition = synchronized(job.lock) {
                    check(jobs[id] === job && job.snapshot.owner == owner &&
                        job.snapshot.scopeId == scope.id() && job.conversationId == scope.conversationId() && !job.recovered) {
                        "Coding job identity changed before cancellation"
                    }
                    callerToken.throwIfCancelled()
                    request.requireDispatchLive()
                    requestCancel(job, admitted = true)
                }
                val kind = when(disposition.kind) {
                    CancellationKind.REQUESTED -> CodingCancellation.Disposition.NEW_REQUEST
                    CancellationKind.ALREADY_REQUESTED -> CodingCancellation.Disposition.ALREADY_REQUESTED
                    CancellationKind.ALREADY_TERMINAL -> CodingCancellation.Disposition.ALREADY_TERMINAL
                }
                val result = issuer.result(request, confirmedIntent, kind,
                    if(disposition.kind == CancellationKind.REQUESTED) disposition.cancellingCommit else null)
                resultCaptured = true
                val ledgerConfirmed = try {
                    val checkpoint = destination.finishCodingCancelSync(result)
                    checkpoint.requireResult(result)
                    try { destination.linkCodingCancelSemanticSync(checkpoint) } catch (_: Exception) {
                        // No ticket refresh, backfill, cancellation replay or rewrite of command facts.
                    }
                    true
                } catch (_: Exception) { false }
                return disposition.copy(ledgerConfirmed = ledgerConfirmed)
            } catch (failure: Exception) {
                request.abandonDispatch()
                // This is audit-only. A late intent ACK never resumes the cancellation operation.
                val confirmedIntent = intent
                if (confirmedIntent != null && !resultCaptured) {
                    try {
                        val result = issuer.result(request, confirmedIntent,
                            CodingCancellation.Disposition.REQUEST_UNCONFIRMED, null)
                        resultCaptured = true
                        val checkpoint = destination.finishCodingCancelSync(result)
                        checkpoint.requireResult(result)
                        try { destination.linkCodingCancelSemanticSync(checkpoint) } catch (_: Exception) { }
                    } catch (_: Exception) { /* Exact intent/result remains observable; never replay a request. */ }
                }
                throw failure
            }
        }
    }

    fun cancelAllAndAwait(owner: String) {
        val owned = jobs.values.filter { it.snapshot.owner == owner && !it.recovered }
        owned.forEach { requestCancel(it) }
        var interrupted = false
        try { owned.forEach { job -> while (true) {
            try { job.finished.await(); break } catch (_: InterruptedException) { interrupted = true }
        } } } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private fun run(job: Job, scope: ProjectScope, command: String, timeout: Long?, lease: ProjectScope.WriterLease,
                    preflight: () -> Unit, completion: (Snapshot) -> Unit) {
        var output: CodingJobLog? = null
        var state = State.NOT_STARTED
        var exit: Int? = null
        var error: String? = null
        var complete = true
        try {
            val activeOutput = CodingJobLog(::sanitize, redactionBufferChars) { append(job, it) }
            output = activeOutput
            job.token.throwIfCancelled(); validateCwd(scope, job.snapshot.cwd); lease.validate(); preflight()
            job.token.throwIfCancelled()
            job.technical?.let { technical ->
                technical.submission.requireDispatchLive()
                val preparation = synchronized(job.lock) {
                    technical.phase = CodingJobEvidence.Phase.PREPARATION_INTENT
                    persist(job); requireNotNull(technical.latest)
                }
                technical.bridge.admitCodingPreparationSync(preparation)
                technical.submission.requireDispatchLive(); preflight(); job.token.throwIfCancelled()
            }
            val result = backend.execute(scope, command, job.snapshot.cwd, timeout, job.token,
                LinuxOutputCallback { stream, text -> activeOutput.accept(stream, text) }) {
                preflight(); validateCwd(scope, job.snapshot.cwd); lease.validate()
                synchronized(job.lock) {
                    job.token.throwIfCancelled()
                    check(!job.launchAttempted) { "A coding job cannot launch twice" }
                    val previous = job.snapshot
                    job.snapshot = previous.copy(state = State.RUNNING, startedAt = System.currentTimeMillis())
                    job.technical?.phase = CodingJobEvidence.Phase.LAUNCH_INTENT
                    try {
                        // This fsynced intent precedes ProcessBuilder.start, not merely dispatch to a worker.
                        persist(job)
                        job.launchAttempted = true
                        preflight(); validateCwd(scope, job.snapshot.cwd); lease.validate(); job.token.throwIfCancelled()
                    } catch (failure: Exception) { job.snapshot = previous; throw failure }
                }
            }
            exit = result.exitCode
            state = when {
                job.token.isCancellationRequested || result.cancelled -> State.CANCELLED
                result.timedOut -> State.TIMED_OUT
                !job.launchAttempted -> State.NOT_STARTED
                result.exitCode == 0 -> State.SUCCEEDED
                else -> State.FAILED
            }
            if (!job.launchAttempted) { exit = null; error = "Backend did not confirm a launch" }
        } catch (_: CancellationException) { state = State.CANCELLED }
        catch (_: Exception) {
            state = if (job.launchAttempted) State.UNCERTAIN else State.NOT_STARTED
            if (job.launchAttempted) complete = false
            error = if (job.launchAttempted) "Execution result is unknown; inspect project and log before any retry"
                    else "Command was not launched; environment, policy, or project validation failed"
        } catch (fatal: Error) {
            state = State.UNCERTAIN; exit = null; complete = false
            error = "Execution interrupted by a fatal worker failure; do not repeat the command"
            throw fatal
        } finally {
            try { output?.finish() } catch (_: Exception) {
                if (job.launchAttempted && state != State.CANCELLED) state = State.UNCERTAIN
                error = "Output could not be fully persisted; inspect the project before any retry"; complete = false
            } catch (fatal: Error) {
                state = State.UNCERTAIN; exit = null; complete = false
                error = "Output interrupted by a fatal worker failure; do not repeat the command"
                throw fatal
            } finally {
                finish(job, lease, state, exit, error, completion, complete)
            }
        }
    }

    private fun finish(job: Job, lease: ProjectScope.WriterLease, result: State, exit: Int?, error: String?,
                       completion: (Snapshot) -> Unit, logComplete: Boolean,
                       expectedClaim: WorkerClaim = WorkerClaim.CLAIMED) {
        if (!job.claim.compareAndSet(expectedClaim, WorkerClaim.FINALIZING)) return
        var terminal: Snapshot? = null
        try { synchronized(job.lock) {
            if (job.snapshot.state.terminal) return
            var state = if (job.token.isCancellationRequested) State.CANCELLED else result
            var detail = error
            if (job.launchAttempted) try { lease.markChanged() } catch (_: Exception) {
                state = State.UNCERTAIN; detail = "Project version could not be reconciled after execution"
            }
            job.snapshot = job.snapshot.copy(state = state, finishedAt = System.currentTimeMillis(), exitCode = exit,
                error = detail, logComplete = logComplete)
            job.desiredTerminal = job.snapshot
            // A lost acceptance ACK restores the observer's interrupt flag. Only this audit-only
            // terminal write consumes that already-observed interrupt; no command/preparation or
            // callback runs with it cleared. A new interrupt during IO still fails conservatively.
            val restoreObserverInterrupt = job.pendingTechnical != null && expectedClaim == WorkerClaim.REJECTED && Thread.interrupted()
            try { persist(job) } catch (_: Exception) {
                job.pendingTechnical?.submission?.abandonDispatch()
                job.snapshot = job.snapshot.copy(state = State.UNCERTAIN, exitCode = null, logComplete = false,
                    error = "Terminal result could not be durably recorded; do not automatically repeat the command")
            } finally {
                if (restoreObserverInterrupt) Thread.currentThread().interrupt()
            }
            terminal = job.snapshot
        }
            // No Room call under job.lock, policy admission, launch lock, or journal writer lock.
            job.technical?.let { technical ->
                technical.latest?.takeIf { !technical.abandonedAcceptance && it.record().terminal != null }?.let { commit ->
                    val projected = try { technical.bridge.projectCodingJobSync(commit); true } catch (_: Exception) {
                        // The original journal remains authoritative; never project into a replacement database.
                        technical.submission.abandonDispatch(); false
                    }
                    if (projected) {
                        job.projection = TechnicalProjection.CONFIRMED
                        try { technical.bridge.linkCodingJobSemanticSync(commit) } catch (_: Exception) {
                            // Semantic eligibility/projection failure must not rewrite the technical process result.
                        }
                    }
                }
            }
        } finally {
            try { lease.close() } finally {
                try { job.unregister.run() } finally {
                    job.unregister = Runnable { }
                    var repairPin: CodingJobOwnerLease.Pin? = null
                    try {
                        if(job.pendingTechnical != null && job.projection == TechnicalProjection.PENDING)
                            repairPin = prepareAutomaticRepair(job)
                    } finally {
                        try { job.pendingTechnical?.pin?.close() } finally {
                            job.claim.set(WorkerClaim.FINISHED)
                            job.finished.countDown()
                            repairPin?.let { scheduleAfterOriginalDrain(job,it) }
                        }
                    }
                }
            }
        }
        terminal?.let { try { completion(it) } catch (_: Exception) { /* Presentation failure is not execution failure. */ } }
    }

    private fun prepareAutomaticRepair(job: Job): CodingJobOwnerLease.Pin? {
        if(!job.repairRunning.compareAndSet(false,true))return null
        return try {
            job.pendingTechnical!!.submission.abandonDispatch()
            val pin=pinSubmission()
            try { job.pendingTechnical!!.submission.requireRepairPin(pin); pin }
            catch(failure: Throwable) { pin.close(); throw failure }
        } catch (failure: Throwable) {
            job.repairRunning.set(false)
            if(failure is Error)throw failure
            null
        }
    }
    private fun scheduleAfterOriginalDrain(job: Job, pin: CodingJobOwnerLease.Pin) {
        val submission = job.pendingTechnical!!.submission
        submission.onOriginalOperationsDrained {
            // This observer can run on the Bridge worker: scheduling only, never blocking Room here.
            try {
                Thread({
                    try { performTechnicalRepair(job,pin) }
                    finally { try { pin.close() } finally { job.repairRunning.set(false) } }
                }, "jarvys-coding-terminal-audit").apply { isDaemon=true; start() }
            } catch (failure: Throwable) {
                try { pin.close() } finally { job.repairRunning.set(false) }
                if(failure is Error)throw failure
            }
        }
    }
    /** One explicit audit-only retry; status/list never trigger repairs or dispatch. */
    internal fun repairTechnical(owner: String, scope: ProjectScope, id: String): TechnicalProjection {
        val job=owned(owner,scope,id,allowRecoveredAccess=false)
        if(job.pendingTechnical==null || job.projection==TechnicalProjection.CONFIRMED)return job.projection
        check(job.claim.get()==WorkerClaim.FINISHED) { "Coding worker has not finalized" }
        check(job.repairRunning.compareAndSet(false,true)) { "Coding repair is already running" }
        try {
            pinSubmission().use { pin -> performTechnicalRepair(job,pin) }
        } finally { job.repairRunning.set(false) }
        return job.projection
    }
    private fun performTechnicalRepair(job: Job, pin: CodingJobOwnerLease.Pin) {
        val pending=job.pendingTechnical ?: return
        try {
            pending.submission.requireRepairPin(pin); pending.submission.requireRepairReady()
            val reservation=pending.submission.reservationOutcome()?.reservation ?: return
            val technical=job.technical ?: Technical(pending.submission,reservation,pending.bridge,pending.pin,pending.authorization)
                .also { job.technical=it }
            val terminal=synchronized(job.lock) {
                val store=pending.submission.journal()
                var head=store.repairHead(reservation,pin)
                if(head==null) {
                    val initial=technical.record(job,job.initialSnapshot,0)
                    head=store.initializeAbandoned(reservation,initial.first,initial.second,pin)
                }
                technical.latest=head
                technical.phase=head.record().highestEffectPhase
                if(head.record().terminal==null) {
                    val desired=job.desiredTerminal ?: return
                    val next=technical.record(job,desired,head.record().revision)
                    head=store.writeTerminalRepair(reservation,head.record().revision,next.first,next.second,pin)
                    technical.latest=head
                }
                // A historical terminal cannot silently replace a different desired terminal.
                job.desiredTerminal?.let { desired ->
                    val expected=technical.record(job,desired,head.record().revision-1)
                    check(java.util.Arrays.equals(head.copyPayload(),CodingJobJournalCodec.encode(expected.second,expected.first))) {
                        "Repaired terminal does not match the exact desired facts"
                    }
                    job.snapshot=desired
                }
                head
            }
            pending.bridge.repairCodingJobTerminalSync(terminal,pin)
            job.projection=TechnicalProjection.CONFIRMED
            try { pending.bridge.linkCodingJobSemanticRepairSync(terminal,pin) } catch (_: Exception) {
                // Original ticket/target only; suppressed semantics do not erase technical completion.
            }
        } catch (_: Exception) {
            // Exact pending candidate/desired terminal remain observable. No loop or command retry.
        }
    }
    fun technicalProjection(owner: String, scope: ProjectScope, id: String): TechnicalProjection =
        owned(owner, scope, id).projection

    private fun requestCancel(job: Job, admitted: Boolean = false): CancelDisposition {
        synchronized(job.lock) {
            if (job.snapshot.state.terminal || job.recovered)
                return CancelDisposition(job.snapshot, CancellationKind.ALREADY_TERMINAL, false, admitted)
            if (job.token.isCancellationRequested)
                return CancelDisposition(job.snapshot, CancellationKind.ALREADY_REQUESTED, false, admitted)
            job.snapshot = job.snapshot.copy(state = State.CANCELLING)
            var confirmed = false
            var requested = false
            var cancellingCommit: CodingJobJournalStore.Commit? = null
            try {
                persist(job)
                confirmed = true
                cancellingCommit = job.technical?.latest
            } catch (_: Exception) {
                // STOP remains effective even when its journal acknowledgement is unknown.
            } finally {
                requested = job.token.cancel()
            }
            return CancelDisposition(job.snapshot,
                if (requested) CancellationKind.REQUESTED else CancellationKind.ALREADY_REQUESTED,
                confirmed, admitted, cancellingCommit = cancellingCommit)
        }
    }

    private fun append(job: Job, text: String) = synchronized(job.lock) {
        conversations.active(job.conversationId) {
            val file = logFile(job)
            FileOutputStream(file, true).use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            syncDirectory(file.parentFile!!)
            job.snapshot = job.snapshot.copy(logBytes = file.length())
        }
        Unit
    }
    internal fun sanitize(text: String): String = redactor(CodingJobRedactor.redact(text))
    private fun snapshot(job: Job) = synchronized(job.lock) { job.snapshot }
    private fun ownerBot(owner: String, conversation: String): String {
        require(owner.startsWith("$conversation/")) { "Invalid checkpoint job owner" }
        val pieces = owner.removePrefix("$conversation/").split('/')
        require(pieces.size == 2 && pieces[0].isNotBlank() && pieces[1].matches(Regex("-?[0-9]+")) && pieces[1].toLongOrNull() != null) { "Invalid checkpoint job owner" }
        return pieces[0]
    }
    private fun matchesScope(job: Job, scope: ProjectScope): Boolean = job.conversationId == scope.conversationId() &&
        job.snapshot.scopeId == scope.id() && job.projectRoot == scope.rootDirectory().canonicalPath &&
        (job.durableIdentity == null || job.durableIdentity == scope.durableIdentity())
    private fun canRead(owner: String, scope: ProjectScope, job: Job): Boolean {
        if (job.snapshot.owner == owner) return true
        val access = recoveredAccess[owner] ?: return false
        return job.snapshot.owner in access.owners && snapshot(job).state.terminal && access.scopeId == scope.id() &&
            access.projectRoot == job.projectRoot && job.durableIdentity != null &&
            access.durableIdentity == job.durableIdentity && access.durableIdentity == scope.durableIdentity()
    }
    private fun owned(owner: String, scope: ProjectScope, id: String, allowRecoveredAccess: Boolean = true): Job {
        active(scope) { }; reconcile(scope)
        val job = jobs[id] ?: throw IOException("Coding job not found for this mission")
        if (!matchesScope(job, scope) || !(if (allowRecoveredAccess) canRead(owner, scope, job) else snapshot(job).owner == owner))
            throw IOException("Coding job not found for this mission")
        return job
    }
    private fun validateCwd(scope: ProjectScope, cwd: String) {
        scope.require(ProjectScope.Capability.WRITE)
        check(scope.resolve(cwd).isDirectory) { "Project cwd is not an ordinary directory" }
        scope.validate()
    }
    private fun reconcile(scope: ProjectScope) = synchronized(recoveryLock) {
        val affected = jobs.values.filter { it.needsReconciliation && matchesScope(it, scope) }
        if (affected.isNotEmpty()) {
            scope.acquireWriter("coding-recovery:${scope.id()}", scope.version()).use { it.validate(); it.markChanged() }
            affected.forEach { it.needsReconciliation = false }
        }
    }
    private fun <T> active(scope: ProjectScope, action: () -> T): T {
        scope.validate()
        return conversations.active(scope.conversationId()) { action() }
    }
    private fun hash(value: String) = ProjectScope.sha256(value.toByteArray(Charsets.UTF_8))
    private fun directory(conversation: String, create: Boolean = false): File {
        var directory = root.parentFile!!
        verifyPath(directory)
        for (part in listOf("crew-context", hash(conversation), "coding-jobs")) {
            if (create && !directory.exists()) {
                check(directory.mkdir()) { "Cannot create private coding job storage" }; syncDirectory(directory.parentFile!!)
            }
            directory = File(directory, part)
            verifyPath(directory)
        }
        if (create && !directory.exists()) { check(directory.mkdir()) { "Could not create private coding job directory" }; syncDirectory(directory.parentFile!!) }
        verifyPath(directory)
        return directory
    }
    private fun logFile(job: Job) = File(directory(job.conversationId), "${job.snapshot.id}.log").also(::verifyPath)
    private fun persist(job: Job) {
        val technical = job.technical
        if (technical != null) { technical.persist(job); return }
        if(job.pendingTechnical!=null)throw IOException("Coding reservation is still unresolved")
        persistLegacy(job)
    }
    private fun persistLegacy(job: Job) = conversations.active(job.conversationId) {
        val destination = directory(job.conversationId, true)
        val s = job.snapshot
        val json = JSONObject().put("schema", if (job.durableIdentity == null) 1 else 2).put("id", s.id).put("owner", s.owner)
            .put("durableIdentity", job.durableIdentity ?: JSONObject.NULL).put("scopeId", s.scopeId)
            .put("conversationId", job.conversationId).put("projectRoot", job.projectRoot).put("cwd", s.cwd)
            .put("command", s.redactedCommand).put("state", s.state.name).put("createdAt", s.createdAt)
            .put("startedAt", s.startedAt ?: JSONObject.NULL).put("finishedAt", s.finishedAt ?: JSONObject.NULL)
            .put("exitCode", s.exitCode ?: JSONObject.NULL).put("error", s.error ?: JSONObject.NULL)
            .put("logBytes", s.logBytes).put("logComplete", s.logComplete)
        val target = File(destination, "${s.id}.json").also(::verifyPath)
        journalWriter.write(target, s.id, if (job.durableIdentity == null) 1 else 2,
            json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun restore() {
        if (!root.exists()) return
        verifyPath(root)
        val conversationDirs = root.listFiles() ?: throw IOException("Cannot inspect coding job storage")
        for (conversationDir in conversationDirs) {
            if (!conversationDir.name.matches(Regex("[a-f0-9]{64}"))) continue
            val directory = File(conversationDir, "coding-jobs")
            if (!directory.exists()) continue
            try {
                verifyPath(directory)
                val entries = directory.listFiles() ?: throw IOException("Cannot inspect coding job journals")
                for (file in entries.filter { it.name.endsWith(".json") }) {
                    try { restoreRecord(file) }
                    catch (_: Exception) { recordProblem(conversationDir.name) }
                    catch (_: StackOverflowError) { recordProblem(conversationDir.name) }
                }
            } catch (_: Exception) { recordProblem(conversationDir.name) }
        }
    }
    private fun recordProblem(scopeHash: String) {
        restoreProblems += "A coding job journal could not be read; original evidence was preserved"
        restoreProblemScopes[scopeHash] = (restoreProblemScopes[scopeHash] ?: 0) + 1
    }
    private fun restoreRecord(file: File) {
        verifyPath(file)
        require(file.isFile && file.length() <= MAX_JOURNAL_BYTES) { "Job journal exceeds current memory headroom" }
        val bytes = file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break
                require(out.size() + count <= MAX_JOURNAL_BYTES) { "Job journal grew beyond current memory headroom" }; out.write(buffer, 0, count) }
            out.toByteArray()
        }
        val schema = CodingJobJournalCodec.schemaVersion(bytes,MAX_JOURNAL_BYTES)
        if(schema==3L) {
            val candidate=CodingJobJournalStore.forFilesRoot(applicationFiles).inspectExisting(file,MAX_JOURNAL_BYTES)
            val original=candidate.snapshot()
            val observed=operationalSnapshot(original).let { value ->
                if(candidate.record().terminal==null)value.copy(error="Recorded job has not yet been reconciled; no runtime handle was restored")
                else value.copy(state=State.UNCERTAIN,exitCode=null,finishedAt=null,logComplete=false,
                    error="Recovered terminal is not yet verified; no execution result is accredited")
            }
            val job=Job(observed,original.projectRoot,original.conversationId,candidate.record().binding.durableScopeIdentity)
            job.recovered=true;job.recoveryScopeBlocked=true;job.projection=TechnicalProjection.PENDING
            job.expectedRecoveryBinding=candidate.record().binding
            require(jobs.putIfAbsent(original.id,job)==null) { "Duplicate recovered job identity" }
            recoveryCandidates[original.id]=candidate.target()
            return
        }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(schema == 1L || schema == 2L)
        val identity = if (schema == 2L) json.getString("durableIdentity").also { require(it.isNotBlank() && it != "null") } else null
        val id = json.getString("id"); require(UUID.fromString(id).toString() == id && file.name == "$id.json")
        val conversation = json.getString("conversationId")
        conversations.active(conversation) { }
        require(file.parentFile == directory(conversation))
        val previous = State.valueOf(json.getString("state"))
        val started = json.longOrNull("startedAt")
        val state = if (previous.terminal) previous else if (started == null) State.INTERRUPTED else State.UNCERTAIN
        val log = File(file.parentFile, "$id.log").also(::verifyPath)
        require(!log.exists() || log.isFile)
        val size = if (log.exists()) log.length() else 0L
        val expected = json.optLong("logBytes", 0); require(expected >= 0)
        val complete = schema == 2L && previous.terminal && json.optBoolean("logComplete", false) && size == expected
        val error = if (previous.terminal) json.optString("error").takeUnless { json.isNull("error") } else
            if (started == null) "Interrupted before launch; no command was replayed" else
                "Execution outcome and process liveness are unknown after restart; no command was replayed"
        val detail = listOfNotNull(error, if (!complete) "Redacted log evidence may be incomplete; retained bytes are available without replay" else null).joinToString("; ").ifEmpty { null }
        val s = Snapshot(id, json.getString("owner"), json.getString("scopeId"), json.getString("cwd"),
            sanitize(json.getString("command")), state, json.getLong("createdAt"), started,
            if (previous.terminal) json.longOrNull("finishedAt") else System.currentTimeMillis(),
            if (previous.terminal && !json.isNull("exitCode")) json.getInt("exitCode") else null,
            error = detail, logBytes = size, logComplete = complete)
        val job = Job(s, json.getString("projectRoot"), conversation, identity)
        job.recovered = true
        job.needsReconciliation = state == State.UNCERTAIN && started != null
        require(jobs.putIfAbsent(id, job) == null) { "Duplicate job identity" }
    }
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)
    private fun verifyPath(file: File) {
        if (file.absoluteFile != file.canonicalFile) throw IOException("Coding job storage cannot use symlinks")
    }
    private fun syncDirectory(directory: File) {
        verifyPath(directory)
        if (Build.VERSION.SDK_INT >= 26 || System.getProperty("java.vm.name")?.contains("Dalvik") != true) {
            DirectorySync.sync(directory)
        } else {
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
        }
    }
    private object DirectorySync {
        fun sync(directory: File) = FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
    private companion object { const val MAX_JOURNAL_BYTES = 4 * 1024 * 1024 }
}

internal object CodingJobRedactor {
    private val privateKey = Regex("-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----.*?(?:-----END (?:[A-Z ]+ )?PRIVATE KEY-----|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val bearer = Regex("(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val assignment = Regex("(?i)((?:password|passwd|api[_-]?key|(?:access|refresh)[_-]?token|client[_-]?secret|token|secret)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s\\\"',;]+")
    private val apiToken = Regex("\\b(?:sk-[A-Za-z0-9_-]+|gh[pousr]_[A-Za-z0-9_]+)\\b")
    fun redact(text: String): String = apiToken.replace(assignment.replace(bearer.replace(privateKey.replace(text,
        "[REDACTED PRIVATE KEY]")) { it.groupValues[1] + "[REDACTED]" }) { it.groupValues[1] + "[REDACTED]" }, "[REDACTED]")
}

/** Buffers complete lines per stream so chunk boundaries cannot expose split credentials. */
internal class CodingJobLog(private val redact: (String) -> String, private val pendingBudget: Int,
                            private val sink: (String) -> Unit) {
    private enum class Prefix { NONE, VALUE, SEPARATOR }
    private class Stream {
        val pending = StringBuilder(); val marker = StringBuilder(); val context = StringBuilder()
        var count = 0L; var oversized = false; var privateBlock = false; var sawBegin = false; var sawEnd = false
        var carried = Prefix.NONE
    }
    private val streams = LinuxOutputStream.entries.associateWith { Stream() }
    init { require(pendingBudget > 0) }
    @Synchronized fun accept(kind: LinuxOutputStream, text: String) {
        val state = streams.getValue(kind)
        for (c in text) {
            state.count++
            if (c.isWhitespace()) { if (!state.context.endsWith(" ")) state.context.append(' ') } else state.context.append(c)
            while (state.context.length > 19) state.context.deleteCharAt(0)
            state.marker.append(c.uppercaseChar()); if (state.marker.length > 11) state.marker.deleteCharAt(0)
            if (state.marker.endsWith("-----BEGIN ")) state.sawBegin = true
            if (state.marker.endsWith("-----END ")) state.sawEnd = true
            if (!state.oversized) {
                if (state.pending.length < pendingBudget) state.pending.append(c)
                else { state.pending.setLength(0); state.oversized = true }
            }
            if (c == '\n') flush(kind, state, true)
        }
    }
    @Synchronized fun finish() { streams.forEach { (kind, state) -> if (state.count > 0) flush(kind, state, false) } }
    private fun flush(kind: LinuxOutputStream, state: Stream, newline: Boolean) {
        val ending = if (newline) "\n" else ""
        val text = when {
            state.privateBlock || state.sawBegin -> if (state.sawBegin) "[REDACTED PEM BLOCK]$ending" else ""
            state.oversized -> "[REDACTED LONG LINE: ${state.count} UTF-16 characters exceeded the $pendingBudget-character pending-output buffer]$ending"
            else -> redact(maskCarriedValue(state.pending.toString(), state.carried))
        }
        state.privateBlock = (state.privateBlock || state.sawBegin) && !state.sawEnd
        state.carried = when { VALUE_PREFIX.containsMatchIn(state.context) -> Prefix.VALUE
            LABEL_PREFIX.containsMatchIn(state.context) -> Prefix.SEPARATOR; else -> Prefix.NONE }
        state.pending.setLength(0); state.count = 0; state.oversized = false; state.sawBegin = false; state.sawEnd = false
        if (text.isNotEmpty()) sink("[${kind.name.lowercase(java.util.Locale.ROOT)}] $text")
    }
    private fun maskCarriedValue(line: String, prefix: Prefix): String {
        val start = when (prefix) { Prefix.NONE -> return line; Prefix.VALUE -> 0
            Prefix.SEPARATOR -> (LEADING_SEPARATOR.find(line)?.range?.last ?: return line) + 1 }
        val secret = LEADING_VALUE.find(line.substring(start))?.groups?.get(2) ?: return line
        return line.replaceRange(secret.range.first + start, secret.range.last + start + 1, "[REDACTED]")
    }
    private companion object {
        const val LABEL = "(?:password|passwd|api[_-]?key|(?:access|refresh)[_-]?token|client[_-]?secret|token|secret)"
        val VALUE_PREFIX = Regex("(?i)(?:\\bBearer\\s+|$LABEL[\\\"']?\\s*[:=]\\s*[\\\"']?\\s*)$")
        val LABEL_PREFIX = Regex("(?i)$LABEL[\\\"']?\\s*$")
        val LEADING_SEPARATOR = Regex("^[\\s\\\"']*[:=]")
        val LEADING_VALUE = Regex("^(\\s*[\\\"']*)([^\\s\\\"',;]+)")
    }
}
