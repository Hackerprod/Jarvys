package com.jarvys.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;
import com.artemis.helper.ArtemisAccessibilityService;
import com.jarvys.agent.crew.CrewNotificationPolicy;
import com.jarvys.agent.device.AccessibilityDriver;
import com.jarvys.agent.device.ScreenData;
import com.jarvys.agent.device.SwipeDirection;
import com.jarvys.agent.skills.SkillEntry;
import com.jarvys.agent.skills.SkillRepository;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Foreground owner of the Etapa A cancellable test loop and future agent runs. */
public final class AgentForegroundService extends Service {
  public static final String ACTION_START_STOP_TEST = "com.jarvys.agent.START_STOP_TEST";
  public static final String ACTION_MANUAL_DEVICE_TEST = "com.jarvys.agent.MANUAL_DEVICE_TEST";
  public static final String ACTION_REAL_AGENT = "com.jarvys.agent.REAL_AGENT";
  public static final String ACTION_COMPACT_CONVERSATION = "com.jarvys.agent.COMPACT_CONVERSATION";
  public static final String ACTION_REFLECT_MEMORY = "com.jarvys.agent.REFLECT_MEMORY";
  public static final String ACTION_RECOVER_PENDING_INPUTS = "com.jarvys.agent.RECOVER_PENDING_INPUTS";
  public static final String ACTION_STOP = "com.jarvys.agent.STOP";
  public static final String ACTION_CREW_KEEPALIVE = "com.jarvys.agent.CREW_KEEPALIVE";

  private static final String CHANNEL_ID = "jarvys_agent_run";
  private static final int NOTIFICATION_ID = 31001;
  private static final Map<String, Object> INTERRUPT_ADMISSION_LOCKS = new ConcurrentHashMap<>();
  private static final Map<String, Set<String>> FOLLOW_UP_CLAIMS = new ConcurrentHashMap<>();
  private static final int TEST_STEPS = 120;
  private static volatile String lastRunReport = "Etapa B: sin ejecuciones todavía.";
  private static volatile AgentForegroundService currentInstance;

  private final ExecutorService worker =
      Executors.newCachedThreadPool(
          r -> {
            Thread thread = new Thread(r, "JarvysAgentRun");
            thread.setDaemon(true);
            return thread;
          });
  /** Instance seam for testing the real service lifecycle without external provider calls. */
  interface MainChatRunner {
    CoreAgentLoop.Result run(CoreAgentRuntime runtime, String goal, List<ConversationTurn> history,
        List<ChatAttachment> attachments, CancellationToken token, CoreAgentLoop.ProgressListener progress);
  }
  MainChatRunner mainChatRunner = (runtime, goal, history, attachments, token, progress) ->
      runtime.runMainChat(goal, history, attachments, token, progress);
  interface RuntimeFactory {
    CoreAgentRuntime create(Context context, String sessionId, List<SkillEntry> skills, boolean memoryDisabled);
  }
  RuntimeFactory runtimeFactory = (context, session, skills, memoryDisabled) ->
      new CoreAgentRuntime(context, session, skills, CorePromptBudget.standard(), memoryDisabled);
  volatile Runnable beforeInterruptAdmissionForTesting;
  volatile Runnable beforeGenerationBindForTesting;
  volatile Runnable afterPendingInputRecoveryScanForTesting;

  private final AtomicInteger pendingStopTasks = new AtomicInteger();
  private final Set<java.util.concurrent.CompletableFuture<Void>> pendingStopCompletions =
      ConcurrentHashMap.newKeySet();
  private final Set<String> pendingRejectedSessionResolution = ConcurrentHashMap.newKeySet();
  private final Map<String, SessionLane> sessionLanes = new ConcurrentHashMap<>();
  private final Object sessionLaneLock = new Object();
  private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();
  private final Map<String, MainChatTurnContextProvider> steeringProviders = new ConcurrentHashMap<>();
  private final Map<String, CancellationToken> activeRunTokens = new ConcurrentHashMap<>();
  /** Compatibility seam for older service tests; run ownership lives in activeRunTokens. */
  private volatile CancellationToken activeRunToken;
  /** Compatibility alias; production steering ownership is session keyed in steeringProviders. */
  private volatile MainChatTurnContextProvider activeSteeringProvider;

  private interface SessionWork { void run(ActiveRun active); }
  private static final class SessionRequest {
    final SessionWork work;
    final boolean interactive;
    final Runnable onRejected;
    final Runnable onStopped;
    final String followUpClaimId;
    SessionRequest(SessionWork work, boolean interactive) {
      this(work, interactive, null, "", null);
    }
    SessionRequest(SessionWork work, boolean interactive, Runnable onRejected) {
      this(work, interactive, onRejected, "", null);
    }
    SessionRequest(SessionWork work, boolean interactive, Runnable onRejected, String followUpClaimId) {
      this(work, interactive, onRejected, followUpClaimId, null);
    }
    SessionRequest(SessionWork work, boolean interactive, Runnable onRejected, String followUpClaimId,
        Runnable onStopped) {
      this.work = work;
      this.interactive = interactive;
      this.onRejected = onRejected;
      this.followUpClaimId = followUpClaimId == null ? "" : followUpClaimId;
      this.onStopped = onStopped;
    }
  }
  private static final class SessionLane {
    final ArrayDeque<SessionRequest> queued = new ArrayDeque<>();
    final List<java.util.concurrent.CompletableFuture<Void>> idleWaiters = new ArrayList<>();
    boolean draining;
    ActiveRun active;
  }
  private static final class ActiveRun {
    final String sessionId;
    final StopController controller;
    final CancellationToken token;
    final long generation;
    volatile Future<?> future;
    ActiveRun(String sessionId, StopController controller, CancellationToken token) {
      this.sessionId = sessionId;
      this.controller = controller;
      this.token = token;
      this.generation = token.generation();
    }
  }

  private void enqueueSessionRun(String sessionId, SessionRequest request) {
    final SessionLane lane;
    boolean startDrain = false;
    synchronized (interruptAdmissionLock(sessionId)) {
      synchronized (sessionLaneLock) {
        lane = sessionLanes.computeIfAbsent(sessionId, ignored -> new SessionLane());
        synchronized (lane) {
          lane.queued.addLast(request);
          if (!lane.draining) {
            lane.draining = true;
            startDrain = true;
          }
        }
      }
    }
    if (startDrain) {
      try {
        worker.execute(() -> drainSession(sessionId, lane));
      } catch (RuntimeException rejected) {
        final List<java.util.concurrent.CompletableFuture<Void>> idleWaiters;
        final List<SessionRequest> rejectedRequests;
        synchronized (interruptAdmissionLock(sessionId)) {
          synchronized (sessionLaneLock) {
            synchronized (lane) {
              lane.draining = false;
              rejectedRequests = new ArrayList<>(lane.queued);
              lane.queued.clear();
              idleWaiters = new ArrayList<>(lane.idleWaiters);
              lane.idleWaiters.clear();
            }
            sessionLanes.remove(sessionId, lane);
          }
          pendingRejectedSessionResolution.add(sessionId);
        }
        idleWaiters.forEach(waiter -> waiter.complete(null));
        if (!enqueueStopTask(() -> resolveRejectedSessionRequests(sessionId, rejectedRequests))) {
          synchronized (interruptAdmissionLock(sessionId)) {
            pendingRejectedSessionResolution.remove(sessionId);
          }
        }
      }
    }
    refreshForegroundNotification();
  }

  /** A test synchronization seam for the physical per-session drain, including run finalization. */
  Future<?> awaitSessionIdleForTesting(String sessionId) {
    synchronized (sessionLaneLock) {
      SessionLane lane = sessionLanes.get(sessionId);
      if (lane == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
      synchronized (lane) {
        if (!lane.draining && lane.active == null && lane.queued.isEmpty())
          return java.util.concurrent.CompletableFuture.completedFuture(null);
        java.util.concurrent.CompletableFuture<Void> idle = new java.util.concurrent.CompletableFuture<>();
        lane.idleWaiters.add(idle);
        return idle;
      }
    }
  }

  Future<?> awaitStopTasksForTesting() {
    java.util.concurrent.CompletableFuture<?>[] pending = pendingStopCompletions.toArray(
        new java.util.concurrent.CompletableFuture<?>[0]);
    return java.util.concurrent.CompletableFuture.allOf(pending);
  }

  private void drainSession(String sessionId, SessionLane lane) {
    while (!worker.isShutdown()) {
      final SessionRequest request;
      final List<java.util.concurrent.CompletableFuture<Void>> idleWaiters;
      ActiveRun active = null;
      FutureTask<Void> future = null;
      boolean skipResolvedFollowUp = false;
      boolean controllerRejected = false;
      synchronized (interruptAdmissionLock(sessionId)) {
        synchronized (sessionLaneLock) {
          synchronized (lane) {
            request = lane.queued.pollFirst();
            if (request == null) {
              lane.active = null;
              lane.draining = false;
              sessionLanes.remove(sessionId, lane);
              idleWaiters = new ArrayList<>(lane.idleWaiters);
              lane.idleWaiters.clear();
            } else {
              idleWaiters = java.util.Collections.emptyList();
            }
          }
        }
        if (request != null) {
          if (!request.followUpClaimId.isEmpty()
              && (!hasFollowUpClaim(sessionId, request.followUpClaimId)
                  || !isPendingFollowUp(sessionId, request.followUpClaimId))) {
            // STOP or another terminal recovery resolved this request while it was queued.
            releaseFollowUpClaim(sessionId, request.followUpClaimId);
            skipResolvedFollowUp = true;
          }
          if (!skipResolvedFollowUp) {
            StopController controller = StopController.forRun();
            CancellationToken token = controller.beginRun();
            if (token == null) {
              controllerRejected = true;
            } else {
              active = new ActiveRun(sessionId, controller, token);
              activeRuns.put(sessionId, active);
              activeRunTokens.put(sessionId, token);
              activeRunToken = token;
              synchronized (lane) { lane.active = active; }
              if (request.interactive) {
                com.jarvys.agent.proactive.BackgroundRunController.INSTANCE.interactiveStarted(active.generation);
                Runnable hook = beforeGenerationBindForTesting;
                if (hook != null) hook.run();
                AgentRunUiState.bindGeneration(sessionId, active.generation);
              }
              ActiveRun run = active;
              future = new FutureTask<>(() -> {
                AgentRunUiState.withSession(sessionId, () -> request.work.run(run));
                return null;
              });
              active.future = future;
              controller.attachFuture(token, future);
            }
          }
        }
      }
      if (request == null) {
        idleWaiters.forEach(waiter -> waiter.complete(null));
        break;
      }
      if (skipResolvedFollowUp) continue;
      if (controllerRejected) {
        if (request.onRejected != null) {
          try { request.onRejected.run(); } catch (RuntimeException failure) { reportStopPersistenceFailure(failure); }
        }
        if (!request.followUpClaimId.isEmpty())
          failPendingInput(sessionId, request.followUpClaimId);
        releaseFollowUpClaim(sessionId, request.followUpClaimId);
        continue;
      }
      refreshForegroundNotification();
      try {
        future.run();
      } finally {
        // FutureTask.cancel(true) can mark the Future done before a non-interruptible tool returns.
        // The lane is released here, after the physical call to FutureTask.run() has unwound.
        boolean stoppedByUser = active.token.wasStoppedByUser();
        if (stoppedByUser) {
          try { failPendingInputRecovery(sessionId); }
          catch (RuntimeException failure) { reportStopPersistenceFailure(failure); }
          finally { releaseFollowUpClaim(sessionId, request.followUpClaimId); }
        }
        Thread.interrupted();
        synchronized (interruptAdmissionLock(sessionId)) {
          active.token.completeRun();
          activeRuns.remove(sessionId, active);
          activeRunTokens.remove(sessionId, active.token);
          if (activeRunToken == active.token)
            activeRunToken = activeRunTokens.values().stream().findFirst().orElse(null);
          synchronized (lane) { if (lane.active == active) lane.active = null; }
          if (request.interactive && !stoppedByUser) {
            releaseFollowUpClaim(sessionId, request.followUpClaimId);
            enqueuePendingInputRecovery(null);
          }
        }
        if (request.interactive) {
          com.jarvys.agent.proactive.BackgroundRunController.INSTANCE.interactiveFinished(active.generation);
        }
        releaseFollowUpClaim(sessionId, request.followUpClaimId);
        AgentRunUiState.finishPendingGeneration(sessionId, active.generation);
        refreshForegroundNotification();
        stopServiceIfIdle();
      }
    }
    stopServiceIfIdle();
  }

  public static void stopRun(String sessionId) {
    AgentForegroundService service = currentInstance;
    if (service != null) service.enqueueStopTask(() -> service.stopSessionRun(sessionId));
  }

  private boolean enqueueStopTask(Runnable task) {
    java.util.concurrent.CompletableFuture<Void> completion = new java.util.concurrent.CompletableFuture<>();
    pendingStopCompletions.add(completion);
    pendingStopTasks.incrementAndGet();
    Runnable trackedTask = () -> {
      try {
        task.run();
      } catch (RuntimeException failure) {
        reportStopPersistenceFailure(failure);
      } finally {
        pendingStopTasks.decrementAndGet();
        completion.complete(null);
        pendingStopCompletions.remove(completion);
        stopServiceIfIdle();
      }
    };
    if (worker.isShutdown()) {
      return enqueueStopFallback(trackedTask, completion);
    }
    try {
      worker.execute(trackedTask);
      return true;
    } catch (RuntimeException rejected) {
      return enqueueStopFallback(trackedTask, completion);
    }
  }

  private boolean enqueueStopFallback(Runnable trackedTask,
      java.util.concurrent.CompletableFuture<Void> completion) {
    try {
      java.util.concurrent.CompletableFuture.runAsync(trackedTask);
      return true;
    } catch (RuntimeException rejected) {
      pendingStopTasks.decrementAndGet();
      completion.complete(null);
      pendingStopCompletions.remove(completion);
      reportStopPersistenceFailure(rejected);
      return false;
    }
  }

  private void resolveRejectedSessionRequests(String sessionId, List<SessionRequest> rejectedRequests) {
    synchronized (interruptAdmissionLock(sessionId)) {
      try {
        for (SessionRequest request : rejectedRequests) {
          try {
            if (request.onRejected != null) request.onRejected.run();
            if (!request.followUpClaimId.isEmpty()) failPendingInput(sessionId, request.followUpClaimId);
          } catch (RuntimeException failure) {
            reportStopPersistenceFailure(failure);
          } finally {
            releaseFollowUpClaim(sessionId, request.followUpClaimId);
          }
        }
        try {
          failPendingInputRecovery(sessionId);
        } catch (RuntimeException failure) {
          reportStopPersistenceFailure(failure);
        }
      } finally {
        pendingRejectedSessionResolution.remove(sessionId);
      }
    }
  }

  private void reportStopPersistenceFailure(Throwable failure) {
    AgentErrorReporter.reportChatDatabaseFailure(this, failure);
  }

  public static boolean hasActiveRun(String sessionId) {
    AgentForegroundService service = currentInstance;
    return service != null && (service.activeRuns.containsKey(sessionId)
        || service.sessionLanes.containsKey(sessionId));
  }

  private void stopSessionRun(String sessionId) {
    if (sessionId == null) return;
    synchronized (interruptAdmissionLock(sessionId)) {
      List<SessionRequest> stoppedRequests = java.util.Collections.emptyList();
      SessionLane lane = sessionLanes.get(sessionId);
      if (lane != null) {
        synchronized (sessionLaneLock) {
          synchronized (lane) {
            stoppedRequests = new ArrayList<>(lane.queued);
            lane.queued.clear();
          }
        }
      }
      ActiveRun active = activeRuns.get(sessionId);
      if (active != null) {
        AgentRunUiState.stopPendingGeneration(sessionId, active.generation);
        active.controller.stopRunAsync();
      } else {
        // Retain the legacy single-run seam for older in-process callers and test harnesses.
        CancellationToken legacy = activeRunTokens.get(sessionId);
        if (legacy == null) legacy = activeRunToken;
        AgentRunUiSnapshot state = AgentRunUiState.INSTANCE.snapshotForSession(sessionId);
        if (legacy != null && !legacy.isCancellationRequested()
            && sessionId.equals(state.getInteractiveOwnerSessionId())) {
          AgentRunUiState.stopPendingGeneration(sessionId, legacy.generation());
          legacy.stopRunAsync();
        }
      }
      for (SessionRequest request : stoppedRequests) {
        try {
          if (request.onStopped != null) {
            request.onStopped.run();
          } else if (!request.followUpClaimId.isEmpty()) {
            stopPendingInput(sessionId, request.followUpClaimId);
          }
        } catch (RuntimeException failure) {
          reportStopPersistenceFailure(failure);
        } finally {
          releaseFollowUpClaim(sessionId, request.followUpClaimId);
        }
      }
    }
  }

  @Override
  protected void attachBaseContext(Context base) {
    super.attachBaseContext(AppLanguageRuntime.localizedContext(base));
  }

  public static void startStopTest(Context context) {
    start(context, ACTION_START_STOP_TEST);
  }

  public static void startManualDeviceTest(Context context) {
    start(context, ACTION_MANUAL_DEVICE_TEST);
  }

  public static void startManualConversationCompaction(Context context, String sessionId) {
    Intent intent =
        new Intent(context, AgentForegroundService.class)
            .setAction(ACTION_COMPACT_CONVERSATION)
            .putExtra("chat_session_id", sessionId);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
    else context.startService(intent);
  }

  public static void startMemoryReflection(
      Context context, String sessionId, String trigger, boolean manual) {
    PersonalMemoryExtractionWorker.enqueue(context, sessionId, false);
  }

  public static void ensureCrewKeepalive(Context context) {
    if (context == null) return;
    Intent intent =
        new Intent(context, AgentForegroundService.class).setAction(ACTION_CREW_KEEPALIVE);
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
      else context.startService(intent);
    } catch (RuntimeException denied) {
      android.util.Log.w(
          "JarvysCrew", "Could not start foreground service for Crew background work", denied);
    }
  }

  public static void onCrewSnapshot(Context context) {
    if (context == null) return;
    CrewNotificationPolicy.State state =
        CrewNotificationPolicy.evaluate(CoreAgentRuntime.crewSnapshots());
    AgentForegroundService service = currentInstance;
    if (state.keepService) {
      if (service != null) service.refreshCrewNotification();
      else ensureCrewKeepalive(context);
    } else if (service != null) {
      service.refreshCrewNotification();
      service.stopServiceIfIdle();
    }
  }

  public static void startRealAgent(Context context) {
    startRealAgent(context, "");
  }

  public static void startRealAgent(Context context, String goal) {
    startRealAgent(context, goal, new ArrayList<>());
  }

  public static void startRealAgent(Context context, String goal, ArrayList<String> skillIds) {
    startRealAgent(context, goal, skillIds, java.util.UUID.randomUUID().toString());
  }

  public static void startRealAgent(
      Context context, String goal, ArrayList<String> skillIds, String sessionId) {
    startRealAgentInternal(context, goal, skillIds, sessionId, goal, false, false);
  }

  public static void startRealAgent(
      Context context,
      String goal,
      ArrayList<String> skillIds,
      String sessionId,
      boolean memoryDisabledForConversation) {
    startRealAgentInternal(
        context, goal, skillIds, sessionId, goal, false, memoryDisabledForConversation);
  }

  public static void startRealAgentFromChat(
      Context context,
      String taskGoal,
      ArrayList<String> skillIds,
      String sessionId,
      String originalUserMessage) {
    startRealAgentInternal(
        context, taskGoal, skillIds, sessionId, originalUserMessage, true, false);
  }

  /**
   * Starts a normal turn from a durable Proactive user-message row; message text is not put in
   * Intent extras.
   */
  public static void startRealAgentFromStoredChatMessage(
      Context context, String sessionId, String userMessageId) {
    Intent intent = storedChatMessageIntent(context, sessionId, userMessageId);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
    else context.startService(intent);
  }

  public static void startRealAgentFromStoredChatMessage(
      Context context,
      String sessionId,
      String userMessageId,
      ArrayList<String> skillIds,
      boolean memoryDisabled) {
    Intent intent = storedChatMessageIntent(context, sessionId, userMessageId);
    intent.putStringArrayListExtra("agent_skill_ids", skillIds);
    intent.putExtra("chat_memory_disabled", memoryDisabled);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
    else context.startService(intent);
  }

  /**
   * Builds an ID-only service request; the service loads the user text from the conversation
   * ledger.
   */
  public static Intent storedChatMessageIntent(
      Context context, String sessionId, String userMessageId) {
    if (sessionId == null
        || !sessionId.matches("[A-Za-z0-9_.-]{1,100}")
        || userMessageId == null
        || !userMessageId.matches("[A-Za-z0-9_-]{1,100}")) {
      throw new IllegalArgumentException("A valid stored chat message is required");
    }
    return new Intent(context, AgentForegroundService.class)
        .setAction(ACTION_REAL_AGENT)
        .putExtra("chat_session_id", sessionId)
        .putExtra("chat_user_message_recorded", true)
        .putExtra("chat_user_message_id", userMessageId);
  }

  public static void startRegenerateAssistant(
      Context context,
      String prompt,
      ArrayList<String> skillIds,
      String sessionId,
      String userMessageId,
      boolean memoryDisabledForConversation) {
    startRealAgentInternal(
        context,
        prompt,
        skillIds,
        sessionId,
        prompt,
        true,
        memoryDisabledForConversation,
        true,
        userMessageId);
  }

  private static void startRealAgentInternal(
      Context context,
      String goal,
      ArrayList<String> skillIds,
      String sessionId,
      String displayGoal,
      boolean userMessageAlreadyRecorded,
      boolean memoryDisabledForConversation) {
    startRealAgentInternal(
        context,
        goal,
        skillIds,
        sessionId,
        displayGoal,
        userMessageAlreadyRecorded,
        memoryDisabledForConversation,
        false,
        "");
  }

  private static void startRealAgentInternal(
      Context context,
      String goal,
      ArrayList<String> skillIds,
      String sessionId,
      String displayGoal,
      boolean userMessageAlreadyRecorded,
      boolean memoryDisabledForConversation,
      boolean regeneration,
      String userMessageId) {
    startRealAgentInternal(context, goal, skillIds, sessionId, displayGoal,
        userMessageAlreadyRecorded, memoryDisabledForConversation, regeneration, userMessageId, "");
  }

  private static void startRealAgentInternal(
      Context context,
      String goal,
      ArrayList<String> skillIds,
      String sessionId,
      String displayGoal,
      boolean userMessageAlreadyRecorded,
      boolean memoryDisabledForConversation,
      boolean regeneration,
      String userMessageId,
      String followUpRequestId) {
    Intent intent =
        new Intent(context, AgentForegroundService.class)
            .setAction(ACTION_REAL_AGENT)
            .putExtra("agent_goal", goal == null ? "" : goal)
            .putExtra("chat_session_id", sessionId)
            .putExtra("agent_display_goal", displayGoal == null ? "" : displayGoal)
            .putExtra("chat_user_message_recorded", userMessageAlreadyRecorded)
            .putExtra("chat_memory_disabled", memoryDisabledForConversation)
            .putExtra("chat_regeneration", regeneration)
            .putExtra("chat_user_message_id", userMessageId == null ? "" : userMessageId)
            .putExtra("chat_follow_up_request_id", followUpRequestId == null ? "" : followUpRequestId)
            .putStringArrayListExtra(
                "agent_skill_ids",
                skillIds == null ? new ArrayList<>() : new ArrayList<>(skillIds));
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
    else context.startService(intent);
  }

  /** Persist a steering message for the active main-chat loop to consume at its next checkpoint. */
  public static boolean interruptAndSend(Context context, String sessionId, String text,
      ArrayList<String> skills, boolean memoryDisabled) {
    AgentForegroundService service = currentInstance;
    if (sessionId == null || !MessageReactionTool.isOrdinaryChat(sessionId)
        || text == null || text.trim().isEmpty()) return false;
    Runnable hook = service == null ? null : service.beforeInterruptAdmissionForTesting;
    if (hook != null) hook.run();

    synchronized (interruptAdmissionLock(sessionId)) {
      service = currentInstance;
      AgentRunUiSnapshot state = AgentRunUiState.INSTANCE.snapshotForSession(sessionId);
      if (!sessionId.equals(state.getSessionId()) || state.getReflecting()
          || state.getInteractiveOwnerSessionId() != null
              && !sessionId.equals(state.getInteractiveOwnerSessionId())) return false;
      CancellationToken ownerToken = service == null ? null : service.activeRunTokens.get(sessionId);
      if (ownerToken == null && service != null) {
        CancellationToken legacy = service.activeRunToken;
        if (legacy != null && !legacy.isCancellationRequested()
            && sessionId.equals(state.getInteractiveOwnerSessionId())) ownerToken = legacy;
      }
      if (state.getRunning() && (!sessionId.equals(state.getInteractiveOwnerSessionId())
          || service == null || ownerToken == null)) return false;

      LocalRunStore store = new LocalRunStore(context);
      final String requestId;
      try {
        requestId = store.appendAndDispatchInterruptRequest(sessionId, text, skills, memoryDisabled);
      } catch (RuntimeException failure) {
        AgentErrorReporter.reportChatDatabaseFailure(context, failure);
        return false;
      }
      // Re-read after durable admission. The session lock also serializes this decision with
      // provider closure, active-run removal, STOP, and pending-input recovery.
      state = AgentRunUiState.INSTANCE.snapshotForSession(sessionId);
      ownerToken = service == null ? null : service.activeRunTokens.get(sessionId);
      if (ownerToken == null && service != null) {
        CancellationToken legacy = service.activeRunToken;
        if (legacy != null && !legacy.isCancellationRequested()
            && sessionId.equals(state.getInteractiveOwnerSessionId())) ownerToken = legacy;
      }
      boolean activeInteractiveRun = state.getRunning()
          && sessionId.equals(state.getInteractiveOwnerSessionId())
          && service != null && ownerToken != null && !ownerToken.isCancellationRequested();
      MainChatTurnContextProvider provider = service == null ? null : service.steeringProviders.get(sessionId);
      if (provider == null && service != null && ownerToken != null
          && ownerToken == service.activeRunToken) provider = service.activeSteeringProvider;
      if (activeInteractiveRun && provider != null && provider.dispatchPendingRequest(requestId)) {
        AgentRunUiState.refreshPersistedSession(sessionId, store.readRecentConversationTimeline(sessionId));
        return true;
      }
      if (ownerToken != null && ownerToken.wasStoppedByUser()) {
        service.failPendingInputRecovery(sessionId);
        AgentRunUiState.refreshPersistedSession(sessionId, store.readRecentConversationTimeline(sessionId));
        return true;
      }
      if (service == null) {
        try {
          start(context, ACTION_RECOVER_PENDING_INPUTS);
        } catch (RuntimeException startFailure) {
          try {
            store.persistStoppedSubmission(sessionId, requestId, requestId, null,
                AppLanguageRuntime.localizedContext(context).getString(R.string.chat_interrupt_start_failed));
            AgentRunUiState.refreshPersistedSession(sessionId, store.readRecentConversationTimeline(sessionId));
          } catch (RuntimeException persistenceFailure) {
            AgentErrorReporter.reportChatDatabaseFailure(context, persistenceFailure);
          }
        }
        return true;
      }
      if (activeInteractiveRun && !service.worker.isShutdown()) {
        // The active worker's finally block owns the ordered follow-up scan (or STOP failure).
          AgentRunUiState.refreshPersistedSession(sessionId, store.readRecentConversationTimeline(sessionId));
        return true;
      }
      if (service.enqueuePendingInputRecovery(sessionId)) {
        AgentRunUiState.refreshPersistedSession(sessionId, store.readRecentConversationTimeline(sessionId));
        return true;
      }
      service.failPendingInput(sessionId, requestId);
      AgentRunUiState.refreshPersistedSession(sessionId, store.readRecentConversationTimeline(sessionId));
      return true;
    }
  }

  void registerMainChatSteeringProvider(String sessionId, MainChatTurnContextProvider provider) {
    synchronized (interruptAdmissionLock(sessionId)) {
      if (provider == null) {
        MainChatTurnContextProvider removed = steeringProviders.remove(sessionId);
        if (activeSteeringProvider == removed) activeSteeringProvider = null;
      } else {
        steeringProviders.put(sessionId, provider);
        activeSteeringProvider = provider;
      }
    }
  }

  private void clearMainChatSteeringProvider(MainChatTurnContextProvider provider) {
    steeringProviders.values().removeIf(current -> current == provider);
    if (activeSteeringProvider == provider) activeSteeringProvider = null;
  }

  private boolean enqueuePendingInputRecovery(String sessionId) {
    if (worker.isShutdown()) return false;
    if (sessionId == null) {
      try {
        worker.execute(() -> runPendingInputRecoverySafely(null));
        return true;
      } catch (java.util.concurrent.RejectedExecutionException rejected) {
        return false;
      }
    }
    synchronized (interruptAdmissionLock(sessionId)) {
      CancellationToken ownerToken = activeRunTokens.get(sessionId);
      try {
        worker.execute(() -> {
          if (ownerToken != null && ownerToken.wasStoppedByUser()) {
            failPendingInputRecovery(sessionId);
            return;
          }
          runPendingInputRecoverySafely(sessionId);
        });
        return true;
      } catch (java.util.concurrent.RejectedExecutionException rejected) {
        return false;
      }
    }
  }

  /** Recovery is retryable work: teardown cancellation leaves durable requests for the next scan. */
  void runPendingInputRecoverySafely(String onlySessionId) {
    try {
      recoverPendingInputs(onlySessionId);
    } catch (CancellationException cancelled) {
      // ChatDatabaseBridge reports an interrupted wait as cancellation. Keep the durable request
      // pending so a later service instance can retry it.
    } catch (RuntimeException failure) {
      reportStopPersistenceFailure(failure);
    } finally {
      // Do not carry a cancelled Room wait's interrupt flag into the next pooled service task.
      Thread.interrupted();
    }
  }

  private void failPendingInputRecovery(String onlySessionId) {
    try {
      LocalRunStore store = new LocalRunStore(this);
      if (onlySessionId == null) {
        Set<String> pendingSessions = new java.util.LinkedHashSet<>();
        for (org.json.JSONObject request : store.readPendingInterruptRequests()) {
          String sessionId = request.optString("sessionId", "");
          if (!sessionId.isEmpty()) pendingSessions.add(sessionId);
        }
        for (String sessionId : pendingSessions) failPendingInputRecovery(sessionId);
        return;
      }
      synchronized (interruptAdmissionLock(onlySessionId)) {
        List<org.json.JSONObject> pending = store.readPendingInterruptRequests(onlySessionId);
        for (org.json.JSONObject request : pending) {
          String sessionId = request.optString("sessionId", onlySessionId);
          String requestId = request.optString("requestId", "");
          if (!onlySessionId.equals(sessionId) || requestId.isEmpty()) continue;
          failPendingInput(sessionId, requestId);
        }
      }
    } catch (CancellationException cancelled) {
      // A service teardown is not a user STOP. Leave any unprocessed request available to retry.
    } catch (RuntimeException failure) {
      reportStopPersistenceFailure(failure);
    }
  }

  private void recoverPendingInputs(String onlySessionId) {
    LocalRunStore store = new LocalRunStore(this);
    if (onlySessionId == null) {
      Set<String> pendingSessions = new java.util.LinkedHashSet<>();
      for (org.json.JSONObject request : store.readPendingInterruptRequests()) {
        String session = request.optString("sessionId", "");
        if (!session.isEmpty()) pendingSessions.add(session);
      }
      Runnable hook = afterPendingInputRecoveryScanForTesting;
      if (hook != null) hook.run();
      for (String session : pendingSessions) recoverPendingInputs(session);
      return;
    }
    String sessionId = onlySessionId;
    org.json.JSONObject latest = null;
    synchronized (interruptAdmissionLock(sessionId)) {
      if (pendingRejectedSessionResolution.contains(sessionId)) return;
      List<org.json.JSONObject> currentPending = store.readPendingInterruptRequests(sessionId);
      if (currentPending.isEmpty()) return;
      if (activeRunTokens.containsKey(sessionId)) return;
      for (org.json.JSONObject request : currentPending) {
        if (!sessionId.equals(request.optString("sessionId", sessionId))) continue;
        org.json.JSONObject dispatched = store.dispatchInterruptRequest(sessionId, request.optString("requestId", ""));
        if (dispatched != null) latest = dispatched;
        else if (request.optBoolean("alreadyDispatched")) latest = request;
      }
      if (latest == null || !claimFollowUp(sessionId, latest.optString("requestId", ""))) return;
    }
    ArrayList<String> skills = new ArrayList<>();
    org.json.JSONArray savedSkills = latest.optJSONArray("skills");
    if (savedSkills != null) for (int i = 0; i < savedSkills.length(); i++) {
      String skill = savedSkills.optString(i, "");
      if (!skill.isEmpty()) skills.add(skill);
    }
    String messageId = latest.optString("requestId", "");
    String text = latest.optString("content", "");
    try {
      startRealAgentInternal(this, "", skills, sessionId, text, true,
          latest.optBoolean("memoryDisabled", false), false, messageId, messageId);
    } catch (CancellationException cancelled) {
      releaseFollowUpClaim(sessionId, messageId);
      throw cancelled;
    } catch (RuntimeException startFailure) {
      try { failPendingInputRecovery(sessionId); }
      catch (RuntimeException persistenceFailure) { reportStopPersistenceFailure(persistenceFailure); }
      finally { releaseFollowUpClaim(sessionId, messageId); }
    }
  }

  private static Object interruptAdmissionLock(String sessionId) {
    return INTERRUPT_ADMISSION_LOCKS.computeIfAbsent(sessionId, ignored -> new Object());
  }

  private static boolean claimFollowUp(String sessionId, String messageId) {
    if (messageId == null || messageId.isEmpty()) return false;
    return FOLLOW_UP_CLAIMS.computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet()).add(messageId);
  }

  private static boolean hasFollowUpClaim(String sessionId, String messageId) {
    Set<String> claims = FOLLOW_UP_CLAIMS.get(sessionId);
    return claims != null && claims.contains(messageId);
  }

  private boolean isPendingFollowUp(String sessionId, String messageId) {
    try {
      for (org.json.JSONObject request : new LocalRunStore(this).readPendingInterruptRequests(sessionId)) {
        if (messageId.equals(request.optString("requestId", ""))) return true;
      }
    } catch (RuntimeException unreadable) {
      // The caller already confirmed the in-flight claim; an unreadable ledger must not drop it.
      return true;
    }
    return false;
  }

  private static void releaseFollowUpClaim(String sessionId, String messageId) {
    if (messageId == null || messageId.isEmpty()) return;
    Set<String> claims = FOLLOW_UP_CLAIMS.get(sessionId);
    if (claims != null) {
      claims.remove(messageId);
      if (claims.isEmpty()) FOLLOW_UP_CLAIMS.remove(sessionId, claims);
    }
  }

  private void failPendingInput(String sessionId, String requestId) {
    try {
      LocalRunStore store = new LocalRunStore(this);
      synchronized (interruptAdmissionLock(sessionId)) {
        boolean pendingRequestExists = false;
        for (org.json.JSONObject pending : store.readPendingInterruptRequests(sessionId)) {
          if (requestId.equals(pending.optString("requestId", ""))) {
            pendingRequestExists = true;
            break;
          }
        }
        if (!pendingRequestExists) return;
        store.persistStoppedSubmission(sessionId, requestId, requestId, null,
            getString(R.string.chat_interrupt_start_failed));
        releaseFollowUpClaim(sessionId, requestId);
      }
      AgentRunUiState.refreshPersistedSession(sessionId, store.readRecentConversationTimeline(sessionId));
    } catch (RuntimeException failure) {
      if (!(failure instanceof CancellationException)) reportStopPersistenceFailure(failure);
    } finally {
      releaseFollowUpClaim(sessionId, requestId);
    }
  }

  private void failRejectedInteractiveSubmission(String sessionId, String displayGoal,
      boolean userMessageAlreadyRecorded, String userMessageId) {
    String message = getString(R.string.chat_interrupt_start_failed);
    try {
      LocalRunStore store = new LocalRunStore(this);
      String ownerMessageId = userMessageAlreadyRecorded
          ? (userMessageId.isEmpty() ? store.latestUserMessageId(sessionId) : userMessageId)
          : null;
      String missingUserContent = ownerMessageId == null ? displayGoal : null;
      store.persistStoppedSubmission(sessionId, null, ownerMessageId, missingUserContent, message);
      AgentRunUiState.refreshPersistedSession(sessionId, store.readRecentConversationTimeline(sessionId));
    } catch (RuntimeException failure) {
      reportStopPersistenceFailure(failure);
    }
    AgentRunUiState.fail(sessionId, message);
  }

  private void stopQueuedInteractiveSubmission(String sessionId, String displayGoal,
      boolean userMessageAlreadyRecorded, String userMessageId, String followUpRequestId) {
    String message = AppLanguageRuntime.localizedContext(this).getString(R.string.agent_run_stopped);
    List<AgentRunUiEvent> timeline;
    try {
      LocalRunStore store = new LocalRunStore(this);
      String ownerMessageId = userMessageAlreadyRecorded
          ? (userMessageId == null || userMessageId.isEmpty()
              ? store.latestUserMessageId(sessionId) : userMessageId)
          : null;
      String missingUserContent = ownerMessageId == null ? displayGoal : null;
      store.persistStoppedSubmission(sessionId, followUpRequestId, ownerMessageId, missingUserContent, message);
      timeline = store.readRecentConversationTimeline(sessionId);
    } catch (RuntimeException failure) {
      reportStopPersistenceFailure(failure);
      timeline = AgentRunUiState.INSTANCE.snapshotForSession(sessionId).getEvents();
    }
    AgentRunUiState.stopUnstarted(sessionId, timeline);
  }

  private void stopPendingInput(String sessionId, String requestId) {
    try {
      LocalRunStore store = new LocalRunStore(this);
      synchronized (interruptAdmissionLock(sessionId)) {
        boolean pendingRequestExists = false;
        for (org.json.JSONObject pending : store.readPendingInterruptRequests(sessionId)) {
          if (requestId.equals(pending.optString("requestId", ""))) {
            pendingRequestExists = true;
            break;
          }
        }
        if (!pendingRequestExists) return;
        String message = AppLanguageRuntime.localizedContext(this).getString(R.string.agent_run_stopped);
        store.persistStoppedSubmission(sessionId, requestId, requestId, null, message);
      }
      AgentRunUiState.refreshPersistedSession(sessionId, store.readRecentConversationTimeline(sessionId));
    } catch (RuntimeException failure) {
      reportStopPersistenceFailure(failure);
    }
  }

  private void enqueueStoredMessageFollowUp(String sessionId, String messageId) {
    if (messageId == null || messageId.isEmpty()) return;
    if (worker.isShutdown()) {
      failPendingInputRecovery(sessionId);
      return;
    }
    CancellationToken ownerToken = sessionId == null ? null : activeRunTokens.get(sessionId);
    try {
      worker.execute(() -> {
        if (ownerToken != null && ownerToken.wasStoppedByUser()) {
          failPendingInputRecovery(sessionId);
          return;
        }
        runPendingInputRecoverySafely(sessionId);
      });
    } catch (java.util.concurrent.RejectedExecutionException rejected) {
      failPendingInputRecovery(sessionId);
    }
  }

  public static String getLastRunReport() {
    return lastRunReport;
  }

  private static void start(Context context, String action) {
    Intent intent = new Intent(context, AgentForegroundService.class).setAction(action);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(intent);
    } else {
      context.startService(intent);
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    com.jarvys.agent.connectors.ApprovalNotificationCenter.INSTANCE.attach(this);
    currentInstance = this;
    createNotificationChannel();
    startForegroundCompat();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_RECOVER_PENDING_INPUTS.equals(intent.getAction())) {
      enqueuePendingInputRecovery(null);
      return START_NOT_STICKY;
    }
    if (intent == null) {
      enqueuePendingInputRecovery(null);
      stopServiceIfIdle();
      return START_NOT_STICKY;
    }
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      enqueueStopTask(this::stopRunAndServices);
      return START_NOT_STICKY;
    }
    if (intent != null && ACTION_CREW_KEEPALIVE.equals(intent.getAction())) {
      refreshCrewNotification();
      stopServiceIfIdle();
      return START_NOT_STICKY;
    }
    if (intent != null && ACTION_REFLECT_MEMORY.equals(intent.getAction())) {
      String session = intent.getStringExtra("chat_session_id");
      if (session == null || !session.matches("[A-Za-z0-9_.-]{1,100}")) {
        stopServiceIfIdle();
        return START_NOT_STICKY;
      }
      PersonalMemoryExtractionWorker.enqueue(this, session, false);
      stopServiceIfIdle();
      return START_NOT_STICKY;
    }
    boolean manualDeviceTest =
        intent != null && ACTION_MANUAL_DEVICE_TEST.equals(intent.getAction());
    boolean realAgent = intent != null && ACTION_REAL_AGENT.equals(intent.getAction());
    if (realAgent) com.jarvys.agent.proactive.BackgroundRunController.INSTANCE.cancelAll();
    boolean compactConversation =
        intent != null && ACTION_COMPACT_CONVERSATION.equals(intent.getAction());
    String agentGoal = intent == null ? "" : intent.getStringExtra("agent_goal");
    String displayGoal = intent == null ? agentGoal : intent.getStringExtra("agent_display_goal");
    if (displayGoal == null || displayGoal.isEmpty()) displayGoal = agentGoal;
    boolean chatMessageRecorded =
        intent != null && intent.getBooleanExtra("chat_user_message_recorded", false);
    boolean chatMemoryDisabled =
        intent != null && intent.getBooleanExtra("chat_memory_disabled", false);
    boolean chatRegeneration = intent != null && intent.getBooleanExtra("chat_regeneration", false);
    String chatUserMessageId = intent == null ? "" : intent.getStringExtra("chat_user_message_id");
    String chatFollowUpRequestId = intent == null ? "" : intent.getStringExtra("chat_follow_up_request_id");
    String sessionId = intent == null ? null : intent.getStringExtra("chat_session_id");
    if (sessionId == null || !sessionId.matches("[A-Za-z0-9_.-]{1,100}"))
      sessionId = java.util.UUID.randomUUID().toString();
    final String chatSessionId = sessionId;
    final String chatAgentGoal = agentGoal == null ? "" : agentGoal;
    final String chatDisplayGoal = displayGoal == null ? "" : displayGoal;
    final boolean userMessageAlreadyRecorded = chatMessageRecorded;
    final boolean memoryDisabledForConversation = chatMemoryDisabled;
    final boolean regeneration = chatRegeneration;
    final String existingUserMessageId = chatUserMessageId == null ? "" : chatUserMessageId;
    final String followUpRequestId = chatFollowUpRequestId == null ? "" : chatFollowUpRequestId;
    ArrayList<String> selectedSkillIds =
        intent == null ? new ArrayList<>() : intent.getStringArrayListExtra("agent_skill_ids");
    if (selectedSkillIds == null) selectedSkillIds = new ArrayList<>();
    final ArrayList<String> runSkillIds = selectedSkillIds;
    if (manualDeviceTest && ArtemisAccessibilityService.getInstance() == null) {
      lastRunReport = "No se inició: activa Jarvys en Ajustes > Accesibilidad.";
      if (realAgent)
        AgentRunUiState.fail(
            chatSessionId,
            "No pude iniciar la tarea. Activa Jarvys en Ajustes > Accesibilidad e inténtalo de"
                + " nuevo.");
      Toast.makeText(this, lastRunReport, Toast.LENGTH_LONG).show();
      stopSelf(startId);
      return START_NOT_STICKY;
    }
    if (realAgent || compactConversation) {
      final boolean isInteractive = realAgent;
      enqueueSessionRun(chatSessionId, new SessionRequest(active -> {
        try {
          if (compactConversation) runManualConversationCompaction(active.token, chatSessionId);
          else runRealAgent(active.token, chatAgentGoal, runSkillIds, chatSessionId, chatDisplayGoal,
              userMessageAlreadyRecorded, memoryDisabledForConversation, regeneration, existingUserMessageId);
        } catch (RuntimeException earlyFailure) {
          if (!isInteractive) throw earlyFailure;
          String message = getString(R.string.chat_interrupt_start_failed);
          try {
            LocalRunStore store = new LocalRunStore(this);
            String ownerId = userMessageAlreadyRecorded ? existingUserMessageId : null;
            store.persistStoppedSubmission(chatSessionId, null, ownerId,
                ownerId == null || ownerId.isEmpty() ? chatDisplayGoal : null, message);
          } catch (RuntimeException persistenceFailure) {
            reportStopPersistenceFailure(persistenceFailure);
          }
          AgentRunUiState.completeGeneration(chatSessionId, active.generation, "failed-start", "FAILED", message, "", 0L);
        }
      }, isInteractive, isInteractive ? () -> failRejectedInteractiveSubmission(chatSessionId,
          chatDisplayGoal, userMessageAlreadyRecorded, existingUserMessageId) : null,
          isInteractive ? followUpRequestId : "",
          isInteractive ? () -> stopQueuedInteractiveSubmission(chatSessionId, chatDisplayGoal,
              userMessageAlreadyRecorded, existingUserMessageId, followUpRequestId) : null));
      return START_NOT_STICKY;
    }

    CancellationToken token = StopController.getInstance().beginRun();
    if (token != null) {
      activeRunToken = token;
      try {
        Future<?> future = worker.submit(() -> {
          try {
            if (manualDeviceTest) runManualDeviceTest(token);
            else runStopTest(token);
          } finally {
            token.completeRun();
            if (activeRunToken == token) activeRunToken = null;
            stopServiceIfIdle();
          }
        });
        StopController.getInstance().attachFuture(token, future);
      } catch (RuntimeException rejected) {
        token.completeRun();
        stopServiceIfIdle();
      }
    }
    return START_NOT_STICKY;
  }

  private void runStopTest(CancellationToken token) {
    try {
      for (int step = 1; step <= TEST_STEPS; step++) {
        token.throwIfCancelled();
        Thread.sleep(1000L);
      }
      if (token.completeRun()) {
        stopServiceIfIdle();
      }
    } catch (InterruptedException | CancellationException stopped) {
      Thread.currentThread().interrupt();
    } finally {
      stopServiceIfIdle();
    }
  }

  private void runManualDeviceTest(CancellationToken token) {
    AccessibilityDriver driver = new AccessibilityDriver();
    try {
      driver.connect();
      ScreenData before = driver.getScreenData(false, token);
      token.throwIfCancelled();
      boolean swiped = driver.swipeDirection(SwipeDirection.UP, 600, token);
      if (!swiped)
        throw new IllegalStateException("AccessibilityService rechazó el gesto swipe UP");
      if (!driver.waitForDelay(0.5, token))
        throw new CancellationException("Run stopped during wait");
      ScreenData after = driver.getScreenData(true, token);
      String report =
          String.format(
              java.util.Locale.ROOT,
              "Etapa B OK — captura takeScreenshot %dx%d (%d elementos); swipe UP OK; captura 2"
                  + " %dx%d (%d elementos).",
              before.width,
              before.height,
              before.uiElements.size(),
              after.width,
              after.height,
              after.uiElements.size());
      finishManualRun(token, report);
    } catch (CancellationException stopped) {
      lastRunReport = "Prueba manual detenida por STOP.";
      token.completeRun();
    } catch (RuntimeException failure) {
      String message =
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
      finishManualRun(token, "Etapa B falló: " + message);
    } finally {
      driver.disconnect();
      stopServiceIfIdle();
    }
  }

  private void finishManualRun(CancellationToken token, String report) {
    if (token.completeRun()) {
      lastRunReport = report;
    }
  }

  private void runManualConversationCompaction(CancellationToken token, String sessionId) {
    AgentRunUiState.compactionStarted(sessionId, getString(R.string.compaction_running));
    try {
      token.throwIfCancelled();
      LocalRunStore store = new LocalRunStore(this);
      List<ConversationTurn> transcript = store.loadConversationContext(sessionId);
      if (store.conversationMessageCount(sessionId) < 4) {
        throw new IllegalStateException(getString(R.string.compaction_manual_minimum));
      }
      CoreAgentModel model = new CoreAgentModel(this, sessionId);
      List<SkillEntry> skills = SkillRepository.Companion.get(this).enabledForRun();
      boolean memoryDisabled =
          getSharedPreferences("jarvys_chat", MODE_PRIVATE)
              .getBoolean(MemoryUiLogic.INSTANCE.sessionMemoryDisabledKey(sessionId), false);
      CoreAgentRuntime runtime =
          new CoreAgentRuntime(
              this, sessionId, skills, CorePromptBudget.standard(), memoryDisabled);
      int contextWindow = model.contextWindow(token);
      ConversationCompactor compactor = new ConversationCompactor(
          sessionId, model, store, true, runtime.compactionMemoryLifecycle(model));
      ConversationCompactor.Outcome outcome =
          compactor.compact(
              transcript,
              contextWindow,
              "manual",
              ConversationCompactionPolicy.Mode.SLIDING_WINDOW,
              token,
              new ConversationCompactor.Listener() {
                @Override
                public void onStarted(String trigger) {
                  AgentRunUiState.compactionStarted(
                      sessionId, getString(R.string.compaction_running));
                }

                @Override
                public void onCompleted(String summary, int count, String mode) {
                  AgentRunUiState.compactionFinished(sessionId, summary, count, mode);
                }
              });
      if (outcome == null) AgentRunUiState.compactionCancelled(sessionId);
      token.completeRun();
    } catch (RuntimeException failure) {
      if (token.isStoppedByUser()) {
        AgentRunUiState.compactionCancelled(sessionId);
      } else {
        String message =
            failure.getMessage() == null
                ? getString(R.string.compaction_error)
                : failure.getMessage();
        AgentRunUiState.compactionFailed(sessionId, getString(R.string.compaction_error));
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
        token.completeRun();
      }
    } finally {
      stopServiceIfIdle();
    }
  }

  private void runRealAgent(
      CancellationToken token,
      String goal,
      List<String> selectedSkillIds,
      String sessionId,
      String displayGoal,
      boolean userMessageAlreadyRecorded,
      boolean memoryDisabledForConversation,
      boolean regeneration,
      String existingUserMessageId) {
    // Mission lifetime is independent of elapsed wall time. STOP still owns cancellation.
    new JarvysUiPreferences(this); // Retire restored legacy deadline settings before starting.
    LocalRunStore conversationStore = new LocalRunStore(this);
    String chatMessage = userFacingFailure("");
    boolean userMessageRecorded = userMessageAlreadyRecorded;
    String userMessageId = existingUserMessageId;
    final AtomicReference<String> assistantOwnerMessageId =
        new AtomicReference<>(existingUserMessageId == null ? "" : existingUserMessageId);
    AgentErrorReporter.AttachmentScope privateContentScope = null;
    MainChatTurnContextProvider steeringProvider = null;
    try {
      token.throwIfCancelled();
      if (userMessageAlreadyRecorded
          && (goal == null || goal.trim().isEmpty())
          && userMessageId != null
          && !userMessageId.isEmpty()) {
        String storedMessage = conversationStore.readConversationMessage(sessionId, userMessageId);
        if (storedMessage != null) {
          goal = storedMessage;
          displayGoal = storedMessage;
        }
      }
      if (userMessageAlreadyRecorded && (userMessageId == null || userMessageId.isEmpty())) {
        userMessageId = conversationStore.latestUserMessageId(sessionId);
      }
      List<ChatAttachment> attachments =
          userMessageId == null || userMessageId.isEmpty()
              ? java.util.Collections.emptyList()
              : conversationStore.readConversationAttachments(sessionId, userMessageId);
      if ((goal == null || goal.trim().isEmpty()) && attachments.isEmpty()) {
        throw new IllegalArgumentException(AppLanguageRuntime.localizedContext(this).getString(R.string.agent_goal_required));
      }
      goal = goal == null ? "" : goal;
      displayGoal = displayGoal == null ? "" : displayGoal;
      if (conversationStore.conversationHasPrivateImagesOrAttachments(sessionId)
          || conversationStore.conversationHasPrivateCode(sessionId)) {
        privateContentScope = AgentErrorReporter.suppressForPrivateContent();
      }
      AgentRunUiState.beginServiceRun(sessionId, displayGoal.trim(), attachments, regeneration,
          token.generation(), userMessageId);
      List<ConversationTurn> conversationHistory =
          conversationStore.loadConversationContext(sessionId);
      if (userMessageAlreadyRecorded && !conversationHistory.isEmpty()) {
        ConversationTurn last = conversationHistory.get(conversationHistory.size() - 1);
        if ("user".equals(last.role) && displayGoal.trim().equals(last.content)) {
          conversationHistory.remove(conversationHistory.size() - 1);
        }
      }
      if (!userMessageAlreadyRecorded) {
        userMessageId =
            conversationStore.appendConversationMessage(sessionId, "user", displayGoal.trim());
      } else if (userMessageId == null || userMessageId.isEmpty()) {
        userMessageId = conversationStore.latestUserMessageId(sessionId);
      }
      userMessageRecorded = true;
      assistantOwnerMessageId.set(userMessageId == null ? "" : userMessageId);
      AgentRunUiState.bindCurrentUserMessage(sessionId, userMessageId);
      SkillRepository skills = SkillRepository.Companion.get(this);
      List<SkillEntry> enabledSkills =
          selectedSkillIds == null || selectedSkillIds.isEmpty()
              ? skills.enabledForRun()
              : skills.selectedForRun(selectedSkillIds);
      List<String> usedSkillIds = new ArrayList<>();
      for (SkillEntry skill : enabledSkills) usedSkillIds.add(skill.getMetadata().getId());
      CoreAgentRuntime runtime = runtimeFactory.create(this, sessionId, enabledSkills, memoryDisabledForConversation);
      runtime.setMainChatSteeringCursor(userMessageId);
      steeringProvider = runtime.mainChatSteeringProvider();
      if (steeringProvider != null) registerMainChatSteeringProvider(sessionId, steeringProvider);
      skills.markUsed(usedSkillIds);
      final String reflectionUserMessageId = userMessageId == null ? "" : userMessageId;
      assistantOwnerMessageId.set(reflectionUserMessageId);
      CoreAgentLoop.ProgressListener progress =
          new CoreAgentLoop.ProgressListener() {
            @Override
            public void onInitialUserMessageIncorporated() {
              String incorporatedId = assistantOwnerMessageId.get();
              conversationStore.markConversationMessagesIncorporatedThrough(sessionId, incorporatedId);
            }

            @Override
            public void onAssistantAnswerBeforeInput(String runId, String message,
                CoreAgentLoop.IncomingMessage nextInput) {
              if (nextInput == null || !"main_chat".equals(nextInput.sourceKind)) return;
              conversationStore.appendAssistantMessageBefore(sessionId, message, runId,
                  assistantOwnerMessageId.get(), nextInput.id);
              AgentRunUiState.refreshPersistedSession(sessionId,
                  conversationStore.readRecentConversationTimeline(sessionId));
            }

            @Override
            public void onDurableMessagesIncorporated(List<CoreAgentLoop.IncomingMessage> messages) {
              List<String> incorporatedIds = new ArrayList<>();
              String latestId = "";
              for (CoreAgentLoop.IncomingMessage message : messages) {
                if (message == null || !"main_chat".equals(message.sourceKind)) continue;
                incorporatedIds.add(message.id);
                latestId = message.id;
              }
              if (incorporatedIds.isEmpty()) return;
              conversationStore.markConversationMessagesIncorporated(sessionId, incorporatedIds);
              assistantOwnerMessageId.set(latestId);
              AgentRunUiState.bindCurrentUserMessage(sessionId, latestId);
            }

            @Override
            public void onDurableMessagesDiscarded(List<CoreAgentLoop.IncomingMessage> messages) {
              List<String> resolvedIds = new ArrayList<>();
              for (CoreAgentLoop.IncomingMessage message : messages) {
                if (message != null && "main_chat".equals(message.sourceKind)) resolvedIds.add(message.id);
              }
              if (resolvedIds.isEmpty()) return;
              conversationStore.markConversationMessagesResolved(sessionId, resolvedIds);
              AgentRunUiState.refreshPersistedSession(sessionId,
                  conversationStore.readRecentConversationTimeline(sessionId));
            }

            @Override
            public void onAssistantProgress(String callId, String message) {
              token.runIfActive(() -> {
                AgentRunUiEvent progress = conversationStore.readAssistantProgress(sessionId, callId);
                if (progress != null) AgentRunUiState.assistantProgress(sessionId, progress);
              });
            }

            @Override
            public void onAssistantTextStreamStarted(String runId) {
              token.runIfActive(() -> AgentRunUiState.withGeneration(sessionId, token.generation(),
                  () -> AgentRunUiState.assistantTextStreamStarted(sessionId, token.generation(), runId)));
            }

            @Override
            public void onAssistantTextDelta(String runId, String delta) {
              token.runIfActive(() -> AgentRunUiState.withGeneration(sessionId, token.generation(),
                  () -> AgentRunUiState.assistantTextStreamDelta(sessionId, token.generation(), runId, delta)));
            }

            @Override
            public void onAssistantTextStreamEnded(String runId) {
              AgentRunUiState.assistantTextStreamEnded(sessionId, token.generation(), runId);
            }

            @Override
            public void onProgress(String node, String message) {
              token.runIfActive(() -> {
                AgentRunUiState.withGeneration(sessionId, token.generation(),
                    () -> AgentRunUiState.onProgress(node, message));
              });
            }

            @Override
            public void onToolProgress(
                String stage, String callId, String displayName, String detail, String previewId) {
              token.runIfActive(() -> {
                AgentRunUiState.withGeneration(sessionId, token.generation(),
                    () -> AgentRunUiState.onToolProgress(stage, callId, displayName, detail, previewId));
              });
            }

            @Override
            public void onToolProgress(
                String stage,
                String callId,
                String displayName,
                String detail,
                String previewId,
                String reflectionSource,
                String auditDetail) {
              persistAndShowToolProgress(conversationStore, sessionId, reflectionUserMessageId,
                  stage, callId, displayName, detail, previewId, reflectionSource, auditDetail, token);
            }

            @Override
            public void onToolActivity(ToolActivity activity) {
              conversationStore.appendToolActivity(sessionId,reflectionUserMessageId,activity);
              if (ToolActivity.definitive(activity.stage)) {
                try { conversationStore.appendReflectionToolEvent(sessionId,reflectionUserMessageId,
                    "web".equals(activity.reflectionSource)?"web_search":activity.displayName,
                    activity.reflectionSource,"tool_result".equals(activity.stage)?"tool_result":"tool_error",activity.callId); }
                catch(RuntimeException ignored) { }
              }
              AgentRunUiState.withGeneration(sessionId,token.generation(),()->AgentRunUiState.onToolActivity(activity));
            }

            @Override
            public void onCompactionStarted(String trigger) {
              AgentRunUiState.withGeneration(sessionId, token.generation(),
                  () -> AgentRunUiState.compactionStarted(sessionId, getString(R.string.compaction_running)));
            }

            @Override
            public void onCompactionCompleted(String summary, int summarizedMessages, String mode) {
              AgentRunUiState.withGeneration(sessionId, token.generation(),
                  () -> AgentRunUiState.compactionFinished(sessionId, summary, summarizedMessages, mode));
            }

            @Override
            public void onCompactionFailed(String message) {
              AgentRunUiState.withGeneration(sessionId, token.generation(),
                  () -> AgentRunUiState.compactionFailed(sessionId, getString(R.string.compaction_error)));
            }
          };
      CoreAgentLoop.Result result =
          mainChatRunner.run(runtime, goal.trim(), conversationHistory, attachments, token, progress);
      boolean steeringRemainsUnconsumed = steeringProvider != null && steeringProvider.hasUnconsumedMessages();
      if (steeringRemainsUnconsumed) {
        synchronized (interruptAdmissionLock(sessionId)) {
          steeringProvider.closeForRunExit();
        }
      }
      boolean timedOut = token.isTimedOut() && !token.isStoppedByUser();
      if (timedOut) chatMessage = AgentResultPresentation.timeout(this);
      else {
        chatMessage = AgentResultPresentation.localize(this, result).text;
      }
      if (!timedOut) token.throwIfCancelled();
      String proactiveThreadKey =
          conversationStore.proactiveThreadKeyForUserMessage(sessionId, assistantOwnerMessageId.get());
      String assistantMessageId =
          conversationStore.appendConversationMessage(
              sessionId,
              "assistant",
              chatMessage,
              result.durationMs,
              result.runId,
              assistantOwnerMessageId.get(),
              timedOut ? "PARTIAL" : result.outcome,
              proactiveThreadKey);
      String responseOutcome = timedOut ? "PARTIAL" : result.outcome;
      if (isTerminalFailure(responseOutcome))
        conversationStore.markConversationMessagesResolved(sessionId,
            java.util.Collections.singletonList(assistantOwnerMessageId.get()));
      AgentRunUiState.completeGeneration(sessionId, token.generation(),
          result.runId, responseOutcome, chatMessage, assistantMessageId, result.durationMs);
      if (proactiveThreadKey != null && !timedOut) {
        com.jarvys.agent.proactive.ProactiveInteractionDispatcher.INSTANCE.refreshNotification(
            this, proactiveThreadKey, assistantMessageId);
      }
      finishCoreRun(token, chatMessage);
      if (!token.wasStoppedByUser() && steeringProvider != null && steeringRemainsUnconsumed)
        enqueueStoredMessageFollowUp(sessionId, steeringProvider.latestUnincorporatedSteeringMessageId());
      if (!timedOut && "COMPLETED".equals(result.outcome)) {
        enqueueAutomaticReflection(sessionId, assistantMessageId);
      }
      if ("STOPPED".equals(result.outcome)) lastRunReport = chatMessage;
    } catch (RuntimeException failure) {
      if (!token.isCancelled()) {
        // Preserve bounded structural evidence inside the existing private-content scope.
        // The recorder never retains messages, bodies, filenames or arbitrary stack text.
        LocalDiagnostics.recoverable(this, LocalDiagnostics.Origin.SERVICE, failure);
      }
      boolean stopped = token.isStoppedByUser();
      boolean timedOut = token.isTimedOut() && !stopped;
      chatMessage =
          stopped
              ? AppLanguageRuntime.localizedContext(this).getString(R.string.agent_run_stopped)
              : timedOut ? AgentResultPresentation.timeout(this) : AgentResultPresentation.failure(this, failure);
      if (userMessageRecorded) {
        try {
          conversationStore.appendConversationMessage(
              sessionId,
              "assistant",
              chatMessage,
              null,
              "",
              assistantOwnerMessageId.get(),
              stopped ? "STOPPED" : timedOut ? "PARTIAL" : "FAILED");
          conversationStore.markConversationMessagesResolved(sessionId,
              java.util.Collections.singletonList(assistantOwnerMessageId.get()));
        } catch (RuntimeException persistenceFailure) {
          reportStopPersistenceFailure(persistenceFailure);
        }
      }
      if (stopped) {
        AgentRunUiState.completeGeneration(sessionId, token.generation(), "stopped-" + sessionId,
            "STOPPED", chatMessage, "", 0L);
        lastRunReport = chatMessage;
        failPendingInputRecovery(sessionId);
      } else if (timedOut) {
        AgentRunUiState.completeGeneration(sessionId, token.generation(), "timeout-" + sessionId,
            "PARTIAL", chatMessage, "", 0L);
        finishCoreRun(token, chatMessage);
        if (steeringProvider != null && steeringProvider.hasUnconsumedMessages())
          enqueueStoredMessageFollowUp(sessionId, steeringProvider.latestUnincorporatedSteeringMessageId());
      } else {
        AgentRunUiState.completeGeneration(sessionId, token.generation(), "failed-" + sessionId,
            "FAILED", chatMessage, "", 0L);
        finishCoreRun(token, chatMessage);
        if (steeringProvider != null && steeringProvider.hasUnconsumedMessages())
          enqueueStoredMessageFollowUp(sessionId, steeringProvider.latestUnincorporatedSteeringMessageId());
      }
    } finally {
      AgentRunUiState.clearAssistantTextStreams(sessionId, token.generation());
      if (steeringProvider != null) {
        synchronized (interruptAdmissionLock(sessionId)) {
          steeringProvider.closeForRunExit();
          clearMainChatSteeringProvider(steeringProvider);
        }
      }
      if (privateContentScope != null) privateContentScope.close();
      if (token.isTimedOut()) Thread.interrupted();
      com.jarvys.agent.proactive.BackgroundRunController.INSTANCE.interactiveFinished(token.generation());
      try {
        com.jarvys.agent.tasks.TaskScheduler.INSTANCE.rearm(this);
      } catch (RuntimeException ignored) {
      }
      stopServiceIfIdle();
    }
  }

  /** Shared service projection: durably save terminal UI data before announcing completion. */
  static void persistAndShowToolProgress(LocalRunStore store, String sessionId, String userMessageId,
      String stage, String callId, String displayName, String detail, String previewId,
      String reflectionSource, String auditDetail) {
    persistAndShowToolProgress(store, sessionId, userMessageId, stage, callId, displayName,
        detail, previewId, reflectionSource, auditDetail, null);
  }

  static void persistAndShowToolProgress(LocalRunStore store, String sessionId, String userMessageId,
      String stage, String callId, String displayName, String detail, String previewId,
      String reflectionSource, String auditDetail, CancellationToken token) {
    if ("tool_result".equals(stage) || "tool_error".equals(stage)) {
      store.appendConversationToolPresentation(sessionId, userMessageId, displayName, stage, callId,
          detail, previewId, auditDetail);
      try {
        store.appendReflectionToolEvent(sessionId, userMessageId,
            "web".equals(reflectionSource) ? "web_search" : displayName, reflectionSource, stage, callId);
      } catch (RuntimeException ignored) { }
    }
    Runnable show = () -> AgentRunUiState.onToolProgress(stage, callId, displayName, detail, previewId, auditDetail);
    if (token == null) AgentRunUiState.withSession(sessionId, show);
    else token.runIfActive(() -> AgentRunUiState.withGeneration(sessionId, token.generation(), show));
  }

  private void finishCoreRun(CancellationToken token, String report) {
    if (!token.wasStoppedByUser()) lastRunReport = report;
  }

  private static boolean isTerminalFailure(String outcome) {
    return "FAILED".equals(outcome) || "PARTIAL".equals(outcome) || "STOPPED".equals(outcome);
  }

  private String userFacingResult(AgentRunResult result) {
    Context localized = AppLanguageRuntime.localizedContext(this);
    if ("STOPPED".equals(result.outcome)) return localized.getString(R.string.agent_run_stopped);
    if ("PARTIAL".equals(result.outcome)) {
      String partialMessage = result.message == null ? "" : result.message.trim();
      if (!partialMessage.isEmpty()
          && result.failureType.isEmpty()
          && !partialMessage.startsWith("Findings:")) return partialMessage;
      return localized.getString(R.string.agent_run_partial);
    }
    if ("FAILED".equals(result.outcome)) {
      String failureMessage = result.message == null ? "" : result.message.trim();
      if (!failureMessage.isEmpty()
          && result.failureType.isEmpty()
          && !failureMessage.startsWith("Findings:")) return failureMessage;
      return userFacingFailure(result.message);
    }
    String answer = result.message == null ? "" : result.message.trim();
    if (answer.isEmpty()
        || answer.startsWith("Findings:")
        || answer.contains("Local run record failed:")) {
      return localized.getString(R.string.agent_run_unconfirmed);
    }
    return answer;
  }

  private String userFacingFailure(String details) {
    return AgentResultPresentation.failure(this, details);
  }

  private void stopRunAndServices() {
    com.jarvys.agent.proactive.BackgroundRunController.INSTANCE.cancelAll();
    Set<String> serviceSessions = new java.util.LinkedHashSet<>(sessionLanes.keySet());
    serviceSessions.addAll(activeRuns.keySet());
    for (String sessionId : serviceSessions) stopSessionRun(sessionId);
    AgentStopActions.stopAll(
        StopController::stopAllRuns,
        () -> { },
        CoreAgentRuntime::stopAllCrews);
    for (ActiveRun active : activeRuns.values()) {
      AgentRunUiState.stopPendingGeneration(active.sessionId, active.generation);
    }
    stopServiceIfIdle();
  }

  private void stopServiceIfIdle() {
    boolean noCrew = !CoreAgentRuntime.hasActiveCrewBots();
    if (pendingStopTasks.get() == 0 && activeRuns.isEmpty() && sessionLanes.isEmpty() && !StopController.hasActiveRuns()
        && noCrew) {
      stopSelf();
    }
  }

  private void enqueueAutomaticReflection(String sessionId, String throughMessageId) {
    try { PersonalMemoryExtractionWorker.enqueue(this, sessionId, false); }
    catch (RuntimeException failure) { AgentErrorReporter.reportBackgroundFailure(this, failure); }
  }

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel =
          new NotificationChannel(
              CHANNEL_ID,
              getString(R.string.agent_notification_channel),
              NotificationManager.IMPORTANCE_LOW);
      channel.setShowBadge(false);
      NotificationManager manager = getSystemService(NotificationManager.class);
      if (manager != null) {
        manager.createNotificationChannel(channel);
      }
    }
  }

  private void startForegroundCompat() {
    Notification notification = buildForegroundNotification();
    if (Build.VERSION.SDK_INT >= 34) {
      startForeground(
          NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    } else {
      startForeground(NOTIFICATION_ID, notification);
    }
  }

  private Notification buildForegroundNotification() {
    Notification.Builder builder =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
    Intent stopIntent = new Intent(this, AgentForegroundService.class).setAction(ACTION_STOP);
    int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
      pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
    PendingIntent stopAction = PendingIntent.getService(this, 31002, stopIntent, pendingFlags);
    int activeCount = trackedRunCount();
    String crewText = CrewNotificationPolicy.evaluate(CoreAgentRuntime.crewSnapshots()).notificationText(this);
    String statusText = activeCount == 0 ? crewText
        : getResources().getQuantityString(R.plurals.agent_notification_active_runs, activeCount, activeCount);
    if (activeCount > 0 && CoreAgentRuntime.hasActiveCrewBots()) {
      statusText = getString(R.string.agent_notification_runs_and_crew, statusText, crewText);
    }
    Notification notification =
        builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Jarvys")
            .setContentText(statusText)
            .addAction(
                new Notification.Action.Builder(
                        0, getString(R.string.agent_notification_stop_all), stopAction)
                    .build())
            .setOngoing(true)
            .build();
    return notification;
  }

  private int trackedRunCount() {
    int count = activeRuns.size();
    for (SessionLane lane : sessionLanes.values()) {
      synchronized (lane) { count += lane.queued.size(); }
    }
    CancellationToken legacy = activeRunToken;
    if (!StopController.getInstance().isStopped()
        || legacy != null && !activeRunTokens.containsValue(legacy) && !legacy.isCancellationRequested()) count++;
    return count;
  }

  private void refreshForegroundNotification() {
    NotificationManager manager = getSystemService(NotificationManager.class);
    if (manager != null) manager.notify(NOTIFICATION_ID, buildForegroundNotification());
  }

  private void refreshCrewNotification() { refreshForegroundNotification(); }

  @Override
  public void onDestroy() {
    if (currentInstance == this) currentInstance = null;
    StopController.stopAllRuns();
    if (pendingStopTasks.get() > 0) worker.shutdown();
    else worker.shutdownNow();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
