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
import com.jarvys.agent.device.AccessibilityDriver;
import com.jarvys.agent.device.ScreenData;
import com.jarvys.agent.device.SwipeDirection;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;

import com.jarvys.agent.skills.SkillEntry;
import com.jarvys.agent.skills.SkillRepository;
import com.jarvys.agent.crew.CrewNotificationPolicy;

/** Foreground owner of the Etapa A cancellable test loop and future agent runs. */
public final class AgentForegroundService extends Service {
    public static final String ACTION_START_STOP_TEST = "com.jarvys.agent.START_STOP_TEST";
    public static final String ACTION_MANUAL_DEVICE_TEST = "com.jarvys.agent.MANUAL_DEVICE_TEST";
    public static final String ACTION_REAL_AGENT = "com.jarvys.agent.REAL_AGENT";
    public static final String ACTION_COMPACT_CONVERSATION = "com.jarvys.agent.COMPACT_CONVERSATION";
    public static final String ACTION_REFLECT_MEMORY = "com.jarvys.agent.REFLECT_MEMORY";
    public static final String ACTION_STOP = "com.jarvys.agent.STOP";
    public static final String ACTION_CREW_KEEPALIVE = "com.jarvys.agent.CREW_KEEPALIVE";

    private static final String CHANNEL_ID = "jarvys_agent_run";
    private static final int NOTIFICATION_ID = 31001;
    private static final int TEST_STEPS = 120;
    private static volatile String lastRunReport = "Etapa B: sin ejecuciones todavía.";
    private static volatile AgentForegroundService currentInstance;
    private static final ScheduledExecutorService runTimeouts = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "JarvysRunTimeouts");
        thread.setDaemon(true);
        return thread;
    });

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "JarvysAgentTestLoop");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService reflectionWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "JarvysMemoryReflection");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger activeReflectionTasks = new AtomicInteger();

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
        Intent intent = new Intent(context, AgentForegroundService.class)
                .setAction(ACTION_COMPACT_CONVERSATION).putExtra("chat_session_id", sessionId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void startMemoryReflection(Context context, String sessionId, String trigger, boolean manual) {
        Intent intent = new Intent(context, AgentForegroundService.class)
                .setAction(ACTION_REFLECT_MEMORY)
                .putExtra("chat_session_id", sessionId)
                .putExtra("reflection_trigger", trigger == null ? "post_turn" : trigger)
                .putExtra("reflection_manual", manual);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void ensureCrewKeepalive(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, AgentForegroundService.class).setAction(ACTION_CREW_KEEPALIVE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException denied) {
            android.util.Log.w("JarvysCrew", "Could not start foreground service for Crew background work", denied);
        }
    }

    public static void onCrewSnapshot(Context context) {
        if (context == null) return;
        CrewNotificationPolicy.State state = CrewNotificationPolicy.evaluate(CoreAgentRuntime.crewSnapshots());
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

    public static void startRealAgent(Context context, String goal, ArrayList<String> skillIds, String sessionId) {
        startRealAgentInternal(context, goal, skillIds, sessionId, goal, false, false);
    }

    public static void startRealAgent(Context context, String goal, ArrayList<String> skillIds,
                                      String sessionId, boolean memoryDisabledForConversation) {
        startRealAgentInternal(context, goal, skillIds, sessionId, goal, false, memoryDisabledForConversation);
    }

    public static void startRealAgentFromChat(Context context, String taskGoal, ArrayList<String> skillIds,
                                              String sessionId, String originalUserMessage) {
        startRealAgentInternal(context, taskGoal, skillIds, sessionId, originalUserMessage, true, false);
    }

    /** Starts a normal turn from a durable Proactive user-message row; message text is not put in Intent extras. */
    public static void startRealAgentFromStoredChatMessage(Context context, String sessionId, String userMessageId) {
        Intent intent = storedChatMessageIntent(context, sessionId, userMessageId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    /** Builds an ID-only service request; the service loads the user text from the conversation ledger. */
    public static Intent storedChatMessageIntent(Context context, String sessionId, String userMessageId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_.-]{1,100}")
                || userMessageId == null || !userMessageId.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("A valid stored chat message is required");
        }
        return new Intent(context, AgentForegroundService.class)
                .setAction(ACTION_REAL_AGENT)
                .putExtra("chat_session_id", sessionId)
                .putExtra("chat_user_message_recorded", true)
                .putExtra("chat_user_message_id", userMessageId);
    }

    public static void startRegenerateAssistant(Context context, String prompt, ArrayList<String> skillIds,
                                                String sessionId, String userMessageId,
                                                boolean memoryDisabledForConversation) {
        startRealAgentInternal(context, prompt, skillIds, sessionId, prompt, true,
                memoryDisabledForConversation, true, userMessageId);
    }

    private static void startRealAgentInternal(Context context, String goal, ArrayList<String> skillIds,
                                               String sessionId, String displayGoal,
                                               boolean userMessageAlreadyRecorded,
                                               boolean memoryDisabledForConversation) {
        startRealAgentInternal(context, goal, skillIds, sessionId, displayGoal,
                userMessageAlreadyRecorded, memoryDisabledForConversation, false, "");
    }

    private static void startRealAgentInternal(Context context, String goal, ArrayList<String> skillIds,
                                               String sessionId, String displayGoal,
                                               boolean userMessageAlreadyRecorded,
                                               boolean memoryDisabledForConversation,
                                               boolean regeneration, String userMessageId) {
        Intent intent = new Intent(context, AgentForegroundService.class)
                .setAction(ACTION_REAL_AGENT)
                .putExtra("agent_goal", goal == null ? "" : goal)
                .putExtra("chat_session_id", sessionId)
                .putExtra("agent_display_goal", displayGoal == null ? "" : displayGoal)
                .putExtra("chat_user_message_recorded", userMessageAlreadyRecorded)
                .putExtra("chat_memory_disabled", memoryDisabledForConversation)
                .putExtra("chat_regeneration", regeneration)
                .putExtra("chat_user_message_id", userMessageId == null ? "" : userMessageId)
                .putStringArrayListExtra("agent_skill_ids", skillIds == null ? new ArrayList<>() : new ArrayList<>(skillIds));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static String getLastRunReport() {
        return lastRunReport;
    }

    private static void start(Context context, String action) {
        Intent intent = new Intent(context, AgentForegroundService.class)
                .setAction(action);
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
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRunAndServices();
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
            CancellationToken reflectionToken = MemoryReflectionRuntime.begin(session);
            if (reflectionToken == null) return START_NOT_STICKY;
            String trigger = intent.getStringExtra("reflection_trigger");
            boolean manual = intent.getBooleanExtra("reflection_manual", false);
            enqueueMemoryReflection(session, trigger == null ? "post_turn" : trigger, manual, null, reflectionToken);
            return START_NOT_STICKY;
        }
        boolean manualDeviceTest = intent != null && ACTION_MANUAL_DEVICE_TEST.equals(intent.getAction());
        boolean realAgent = intent != null && ACTION_REAL_AGENT.equals(intent.getAction());
        if (realAgent) com.jarvys.agent.proactive.BackgroundRunController.INSTANCE.cancelAll();
        boolean compactConversation = intent != null && ACTION_COMPACT_CONVERSATION.equals(intent.getAction());
        String agentGoal = intent == null ? "" : intent.getStringExtra("agent_goal");
        String displayGoal = intent == null ? agentGoal : intent.getStringExtra("agent_display_goal");
        if (displayGoal == null || displayGoal.isEmpty()) displayGoal = agentGoal;
        boolean chatMessageRecorded = intent != null && intent.getBooleanExtra("chat_user_message_recorded", false);
        boolean chatMemoryDisabled = intent != null && intent.getBooleanExtra("chat_memory_disabled", false);
        boolean chatRegeneration = intent != null && intent.getBooleanExtra("chat_regeneration", false);
        String chatUserMessageId = intent == null ? "" : intent.getStringExtra("chat_user_message_id");
        String sessionId = intent == null ? null : intent.getStringExtra("chat_session_id");
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_.-]{1,100}")) sessionId = java.util.UUID.randomUUID().toString();
        final String chatSessionId = sessionId;
        if (realAgent && chatMessageRecorded && (agentGoal == null || agentGoal.trim().isEmpty())
                && chatUserMessageId != null && !chatUserMessageId.isEmpty()) {
            String storedMessage = new LocalRunStore(this).readConversationMessage(chatSessionId, chatUserMessageId);
            if (storedMessage != null) { agentGoal = storedMessage; displayGoal = storedMessage; }
        }
        final String chatAgentGoal = agentGoal == null ? "" : agentGoal;
        final String chatDisplayGoal = displayGoal == null ? "" : displayGoal;
        final boolean userMessageAlreadyRecorded = chatMessageRecorded;
        final boolean memoryDisabledForConversation = chatMemoryDisabled;
        final boolean regeneration = chatRegeneration;
        final String existingUserMessageId = chatUserMessageId == null ? "" : chatUserMessageId;
        ArrayList<String> selectedSkillIds = intent == null ? new ArrayList<>()
                : intent.getStringArrayListExtra("agent_skill_ids");
        if (selectedSkillIds == null) selectedSkillIds = new ArrayList<>();
        final ArrayList<String> runSkillIds = selectedSkillIds;
        if (manualDeviceTest && ArtemisAccessibilityService.getInstance() == null) {
            lastRunReport = "No se inició: activa Jarvys en Ajustes > Accesibilidad.";
            if (realAgent) AgentRunUiState.fail(chatSessionId, "No pude iniciar la tarea. Activa Jarvys en Ajustes > Accesibilidad e inténtalo de nuevo.");
            Toast.makeText(this, lastRunReport, Toast.LENGTH_LONG).show();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (StopController.getInstance().isStopped()) {
            CancellationToken token = StopController.getInstance().beginRun();
            if (token == null) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (realAgent) com.jarvys.agent.proactive.BackgroundRunController.INSTANCE.interactiveStarted();
            try {
                Future<?> future = worker.submit(() -> {
                    if (compactConversation) runManualConversationCompaction(token, chatSessionId);
                    else if (manualDeviceTest) runManualDeviceTest(token);
                    else if (realAgent) runRealAgent(token, chatAgentGoal, runSkillIds, chatSessionId,
                            chatDisplayGoal, userMessageAlreadyRecorded, memoryDisabledForConversation,
                            regeneration, existingUserMessageId);
                    else runStopTest(token);
                });
                StopController.getInstance().attachFuture(token, future);
            } catch (RuntimeException failure) {
                if (realAgent) com.jarvys.agent.proactive.BackgroundRunController.INSTANCE.interactiveFinished();
                throw failure;
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
            if (StopController.getInstance().completeRun(token)) {
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
            if (!swiped) throw new IllegalStateException("AccessibilityService rechazó el gesto swipe UP");
            if (!driver.waitForDelay(0.5, token)) throw new CancellationException("Run stopped during wait");
            ScreenData after = driver.getScreenData(true, token);
            String report = String.format(java.util.Locale.ROOT,
                    "Etapa B OK — captura takeScreenshot %dx%d (%d elementos); swipe UP OK; captura 2 %dx%d (%d elementos).",
                    before.width, before.height, before.uiElements.size(),
                    after.width, after.height, after.uiElements.size());
            finishManualRun(token, report);
        } catch (CancellationException stopped) {
            lastRunReport = "Prueba manual detenida por STOP.";
            StopController.getInstance().completeRun(token);
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            finishManualRun(token, "Etapa B falló: " + message);
        } finally {
            driver.disconnect();
            stopServiceIfIdle();
        }
    }

    private void finishManualRun(CancellationToken token, String report) {
        if (StopController.getInstance().completeRun(token)) {
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
            boolean memoryDisabled = getSharedPreferences("jarvys_chat", MODE_PRIVATE)
                    .getBoolean(MemoryUiLogic.INSTANCE.sessionMemoryDisabledKey(sessionId), false);
            CoreAgentRuntime runtime = new CoreAgentRuntime(this, sessionId, skills,
                    CorePromptBudget.standard(), memoryDisabled);
            int contextWindow = model.contextWindow(token);
            ConversationCompactor compactor = new ConversationCompactor(sessionId, model, store);
            ConversationCompactor.Outcome outcome = compactor.compact(transcript, contextWindow,
                    "manual", ConversationCompactionPolicy.Mode.SLIDING_WINDOW, token,
                    new ConversationCompactor.Listener() {
                        @Override public void onStarted(String trigger) {
                            AgentRunUiState.compactionStarted(sessionId, getString(R.string.compaction_running));
                        }
                        @Override public void onCompleted(String summary, int count, String mode) {
                            AgentRunUiState.compactionFinished(sessionId, summary, count, mode);
                        }
                    });
            if (outcome == null) AgentRunUiState.compactionCancelled(sessionId);
            StopController.getInstance().completeRun(token);
        } catch (RuntimeException failure) {
            if (token.isStoppedByUser()) {
                AgentRunUiState.compactionCancelled(sessionId);
            } else {
                String message = failure.getMessage() == null ? getString(R.string.compaction_error) : failure.getMessage();
                AgentRunUiState.compactionFailed(sessionId, getString(R.string.compaction_error));
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
                StopController.getInstance().completeRun(token);
            }
        } finally {
            stopServiceIfIdle();
        }
    }

    private void runRealAgent(CancellationToken token, String goal, List<String> selectedSkillIds, String sessionId,
                              String displayGoal, boolean userMessageAlreadyRecorded,
                              boolean memoryDisabledForConversation, boolean regeneration,
                              String existingUserMessageId) {
        int timeoutSeconds = new JarvysUiPreferences(this).agentTimeoutSeconds();
        Thread runThread = Thread.currentThread();
        ScheduledFuture<?> timeoutTask = timeoutSeconds == 0 ? null : runTimeouts.schedule(() -> {
            if (token.cancelForTimeout()) runThread.interrupt();
        }, timeoutSeconds, TimeUnit.SECONDS);
        LocalRunStore conversationStore = new LocalRunStore(this);
        String chatMessage = "No pude completar la tarea. Verifica la conexión del proveedor y vuelve a intentarlo.";
        boolean userMessageRecorded = false;
        boolean diagnosticReported = false;
        String userMessageId = existingUserMessageId;
        try {
            token.throwIfCancelled();
            if (goal == null || goal.trim().isEmpty()) throw new IllegalArgumentException("Escribe el objetivo antes de iniciar el agente.");
            if (regeneration) AgentRunUiState.beginRegenerationRun(sessionId, displayGoal.trim());
            else AgentRunUiState.beginRun(sessionId, displayGoal.trim());
            List<ConversationTurn> conversationHistory = conversationStore.loadConversationContext(sessionId);
            if (userMessageAlreadyRecorded && !conversationHistory.isEmpty()) {
                ConversationTurn last = conversationHistory.get(conversationHistory.size() - 1);
                if ("user".equals(last.role) && displayGoal.trim().equals(last.content)) {
                    conversationHistory.remove(conversationHistory.size() - 1);
                }
            }
            if (!userMessageAlreadyRecorded) {
                userMessageId = conversationStore.appendConversationMessage(sessionId, "user", displayGoal.trim());
            } else if (userMessageId == null || userMessageId.isEmpty()) {
                userMessageId = conversationStore.latestUserMessageId(sessionId);
            }
            userMessageRecorded = true;
            SkillRepository skills = SkillRepository.Companion.get(this);
            List<SkillEntry> enabledSkills = selectedSkillIds == null || selectedSkillIds.isEmpty()
                    ? skills.enabledForRun() : skills.selectedForRun(selectedSkillIds);
            List<String> usedSkillIds = new ArrayList<>();
            for (SkillEntry skill : enabledSkills) usedSkillIds.add(skill.getMetadata().getId());
            CoreAgentRuntime runtime = new CoreAgentRuntime(this, sessionId, enabledSkills,
                    CorePromptBudget.standard(), memoryDisabledForConversation);
            skills.markUsed(usedSkillIds);
            final String reflectionUserMessageId = userMessageId == null ? "" : userMessageId;
            CoreAgentLoop.ProgressListener progress = new CoreAgentLoop.ProgressListener() {
                @Override public void onProgress(String node, String message) {
                    AgentRunUiState.onProgress(node, message);
                }

                @Override public void onToolProgress(String stage, String callId, String displayName,
                                                     String detail, String previewId) {
                    AgentRunUiState.onToolProgress(stage, callId, displayName, detail, previewId);
                }

                @Override public void onToolProgress(String stage, String callId, String displayName,
                                                     String detail, String previewId, String reflectionSource) {
                    AgentRunUiState.onToolProgress(stage, callId, displayName, detail, previewId);
                    if ("tool_result".equals(stage) || "tool_error".equals(stage)) {
                        try {
                            String storedToolName = "web".equals(reflectionSource) ? "web_search" : displayName;
                            conversationStore.appendReflectionToolEvent(sessionId, reflectionUserMessageId,
                                    storedToolName, reflectionSource, stage, callId);
                        } catch (RuntimeException ignored) { }
                    }
                }

                @Override public void onCompactionStarted(String trigger) {
                    AgentRunUiState.compactionStarted(sessionId, getString(R.string.compaction_running));
                }

                @Override public void onCompactionCompleted(String summary, int summarizedMessages, String mode) {
                    AgentRunUiState.compactionFinished(sessionId, summary, summarizedMessages, mode);
                }

                @Override public void onCompactionFailed(String message) {
                    AgentRunUiState.compactionFailed(sessionId, getString(R.string.compaction_error));
                }
            };
            CoreAgentLoop.Result result = runtime.run(goal.trim(), conversationHistory, token, progress);
            boolean timedOut = token.isTimedOut() && !token.isStoppedByUser();
            if (timedOut) chatMessage = CoreAgentLoop.TIMEOUT_MESSAGE;
            else {
                if (timeoutTask != null) timeoutTask.cancel(false);
                chatMessage = result.text;
            }
            String proactiveThreadKey = conversationStore.proactiveThreadKeyForUserMessage(sessionId, userMessageId);
            String assistantMessageId = conversationStore.appendConversationMessage(sessionId, "assistant", chatMessage,
                    result.durationMs, result.runId, userMessageId, timedOut ? "PARTIAL" : result.outcome,
                    proactiveThreadKey);
            String responseOutcome = timedOut ? "PARTIAL" : result.outcome;
            AgentRunUiState.complete(result.runId, responseOutcome, chatMessage,
                    assistantMessageId, result.durationMs);
            if (proactiveThreadKey != null && !timedOut) {
                com.jarvys.agent.proactive.ProactiveInteractionDispatcher.INSTANCE.refreshNotification(
                    this, proactiveThreadKey, assistantMessageId);
            }
            finishCoreRun(token, chatMessage);
            if (!timedOut && "COMPLETED".equals(result.outcome)) {
                enqueueAutomaticReflection(sessionId, assistantMessageId);
            }
            if ("STOPPED".equals(result.outcome)) lastRunReport = chatMessage;
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            if (!diagnosticReported && !token.isCancelled()) {
                reportAgentFailure(null, null, message, failure.getClass().getName(),
                        android.util.Log.getStackTraceString(failure), "");
            }
            boolean stopped = token.isStoppedByUser();
            boolean timedOut = token.isTimedOut() && !stopped;
            chatMessage = stopped ? "Tarea detenida." : timedOut ? CoreAgentLoop.TIMEOUT_MESSAGE : userFacingFailure(message);
            if (userMessageRecorded) {
                try {
                    conversationStore.appendConversationMessage(sessionId, "assistant", chatMessage,
                            null, "", userMessageId, stopped ? "STOPPED" : timedOut ? "PARTIAL" : "FAILED");
                }
                catch (RuntimeException ignored) { }
            }
            if (stopped) {
                AgentRunUiState.complete("stopped-" + sessionId, "STOPPED", chatMessage);
                lastRunReport = chatMessage;
            } else if (timedOut) {
                AgentRunUiState.complete("timeout-" + sessionId, "PARTIAL", chatMessage);
                finishCoreRun(token, chatMessage);
            } else {
                AgentRunUiState.fail(sessionId, chatMessage);
                finishCoreRun(token, chatMessage);
            }
        } finally {
            if (timeoutTask != null) timeoutTask.cancel(false);
            if (token.isTimedOut()) Thread.interrupted();
            com.jarvys.agent.proactive.BackgroundRunController.INSTANCE.interactiveFinished();
            try { com.jarvys.agent.tasks.TaskScheduler.INSTANCE.rearm(this); }
            catch (RuntimeException ignored) { }
            stopServiceIfIdle();
        }
    }

    private void finishCoreRun(CancellationToken token, String report) {
        if (StopController.getInstance().completeRun(token)) {
            lastRunReport = report;
        }
    }

    private void reportAgentFailure(Integer httpStatus, String responseBody, String message,
                                    String exceptionType, String stackTrace, String failedModel) {
        try {
            ProviderSettings settings = new ProviderSettings(this);
            String model = failedModel == null || failedModel.isEmpty() ? settings.getModel() : failedModel;
            AgentErrorReporter.report(this, settings.getProvider().name(), model, httpStatus,
                    responseBody, message, exceptionType, stackTrace);
        } catch (RuntimeException ignored) {
            // The reporter is best-effort and must not change the failed run's result.
        }
    }

    private String userFacingResult(AgentRunResult result) {
        Context localized = AppLanguageRuntime.localizedContext(this);
        if ("STOPPED".equals(result.outcome)) return localized.getString(R.string.agent_run_stopped);
        if ("PARTIAL".equals(result.outcome)) {
            String partialMessage = result.message == null ? "" : result.message.trim();
            if (!partialMessage.isEmpty() && result.failureType.isEmpty()
                    && !partialMessage.startsWith("Findings:")) return partialMessage;
            return localized.getString(R.string.agent_run_partial);
        }
        if ("FAILED".equals(result.outcome)) {
            String failureMessage = result.message == null ? "" : result.message.trim();
            if (!failureMessage.isEmpty() && result.failureType.isEmpty()
                    && !failureMessage.startsWith("Findings:")) return failureMessage;
            return userFacingFailure(result.message);
        }
        String answer = result.message == null ? "" : result.message.trim();
        if (answer.isEmpty() || answer.startsWith("Findings:") || answer.contains("Local run record failed:")) {
            return localized.getString(R.string.agent_run_unconfirmed);
        }
        return answer;
    }

    static String userFacingFailure(String details) {
        String value = details == null ? "" : details;
        java.util.regex.Matcher http = java.util.regex.Pattern.compile("HTTP (4\\d\\d|5\\d\\d)").matcher(value);
        if (http.find()) return "No pude completar la tarea: el proveedor rechazó la solicitud (HTTP " + http.group(1) + "). Revisa el modelo seleccionado y vuelve a intentarlo.";
        if (value.toLowerCase(java.util.Locale.ROOT).contains("network")
                || value.toLowerCase(java.util.Locale.ROOT).contains("connect")) {
            return "No pude conectar con el proveedor. Comprueba internet y vuelve a intentarlo.";
        }
        return "No pude completar la tarea. Verifica la conexión y la configuración del proveedor, y vuelve a intentarlo.";
    }

    private void stopRunAndServices() {
        com.jarvys.agent.proactive.BackgroundRunController.INSTANCE.cancelAll();
        AgentStopActions.stopAll(StopController.getInstance()::stopRun,
                MemoryReflectionRuntime::cancelAll, CoreAgentRuntime::stopAllCrews);
        stopSelf();
    }

    private void stopServiceIfIdle() {
        if (StopController.getInstance().isStopped() && activeReflectionTasks.get() == 0
                && !CoreAgentRuntime.hasActiveCrewBots()) stopSelf();
    }

    private boolean enqueueMemoryReflection(String sessionId, String trigger, boolean manual,
                                            String throughMessageId, CancellationToken token) {
        activeReflectionTasks.incrementAndGet();
        try {
            reflectionWorker.execute(() -> runMemoryReflection(sessionId, trigger, manual, throughMessageId, token));
            return true;
        } catch (RuntimeException rejected) {
            MemoryReflectionRuntime.finish(sessionId, token);
            activeReflectionTasks.decrementAndGet();
            stopServiceIfIdle();
            return false;
        }
    }

    private void enqueueAutomaticReflection(String sessionId, String throughMessageId) {
        activeReflectionTasks.incrementAndGet();
        try {
            reflectionWorker.execute(() -> {
                boolean transferred = false;
                try {
                    if (!MemoryReflectionCoordinator.shouldScheduleAutomatic(this, sessionId, throughMessageId)) return;
                    CancellationToken token = MemoryReflectionRuntime.begin(sessionId);
                    if (token == null) return;
                    AgentRunUiState.reflectionStarted(sessionId, getString(R.string.reflection_status_running));
                    transferred = true;
                    runMemoryReflection(sessionId, "post_turn", false, throughMessageId, token);
                } finally {
                    if (!transferred) {
                        activeReflectionTasks.decrementAndGet();
                        stopServiceIfIdle();
                    }
                }
            });
        } catch (RuntimeException rejected) {
            activeReflectionTasks.decrementAndGet();
            stopServiceIfIdle();
        }
    }

    private void runMemoryReflection(String sessionId, String trigger, boolean manual,
                                     String throughMessageId, CancellationToken token) {
        AgentRunUiState.reflectionStarted(sessionId, getString(R.string.reflection_status_running));
        try {
            MemoryReflectionCoordinator.Outcome outcome = MemoryReflectionCoordinator.run(
                    this, sessionId, trigger, manual, throughMessageId, token);
            AgentRunUiState.reflectionFinished(sessionId, outcome.reflectionId,
                    outcome.summary.isEmpty() ? outcome.status : outcome.summary,
                    outcome.revisionIds, outcome.partial ? "partial" : outcome.status, outcome.successful);
        } catch (RuntimeException failure) {
            if (token.isCancelled()) {
                new MemoryReflectionPreferences(this).cancelled(sessionId, getString(R.string.reflection_cancelled));
                try { AgentRunUiState.refreshPersistedSession(sessionId,
                        new LocalRunStore(this).readConversationTimeline(sessionId)); }
                catch (RuntimeException ignored) { }
                AgentRunUiState.reflectionCancelled(sessionId, getString(R.string.reflection_cancelled));
            } else {
                String status = getString(R.string.reflection_failed_backoff);
                new MemoryReflectionPreferences(this).failed(sessionId, System.currentTimeMillis(), status);
                AgentRunUiState.reflectionFinished(sessionId, "", status,
                        java.util.Collections.emptyList(), status, false);
            }
        } finally {
            MemoryReflectionRuntime.finish(sessionId, token);
            activeReflectionTasks.decrementAndGet();
            stopServiceIfIdle();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.agent_notification_channel),
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
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildForegroundNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Intent stopIntent = new Intent(this, AgentForegroundService.class).setAction(ACTION_STOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopAction = PendingIntent.getService(this, 31002, stopIntent, pendingFlags);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Jarvys")
                .setContentText(CrewNotificationPolicy.evaluate(CoreAgentRuntime.crewSnapshots()).notificationText(this))
                .addAction(new Notification.Action.Builder(0, getString(R.string.agent_notification_stop), stopAction).build())
                .setOngoing(true)
                .build();
        return notification;
    }

    private void refreshCrewNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildForegroundNotification());
    }

    @Override
    public void onDestroy() {
        if (currentInstance == this) currentInstance = null;
        StopController.getInstance().stopRun();
        MemoryReflectionRuntime.cancelAll();
        reflectionWorker.shutdownNow();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
