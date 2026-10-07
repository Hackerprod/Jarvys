package com.jarvys.agent;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.artemis.helper.ArtemisAccessibilityService;
import com.jarvys.agent.device.AccessibilityDriver;
import com.jarvys.agent.device.ScreenData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.io.PrintWriter;
import java.io.StringWriter;

/** LangGraph-shaped observe/decide/validate/act loop, driven by the configured provider model. */
public final class AgentLoop {
    public interface ProgressListener {
        void onProgress(String node, String message);
    }

    private static final String TAG = "JarvysAgentLoop";
    private static final int MAX_TURNS = 12;

    private final Context appContext;
    private final AgentModel model;
    private final AccessibilityDriver driver;
    private final ToolRegistry registry;
    private final AsyncHistorySummarizer summarizer = new AsyncHistorySummarizer();

    public AgentLoop(Context context, AgentModel model) {
        this(context, model, new ToolRegistry());
    }

    public AgentLoop(Context context, AgentModel model, ToolRegistry registry) {
        appContext = context.getApplicationContext();
        this.model = model;
        driver = new AccessibilityDriver();
        this.registry = registry;
    }

    public ToolRegistry toolRegistry() {
        return registry;
    }

    public AgentRunResult run(String goal, CancellationToken token, ProgressListener progress) {
        return run(goal, "jarvys-session-" + java.util.UUID.randomUUID(), "", java.util.Collections.emptyList(), token, progress);
    }

    public AgentRunResult run(String goal, String sessionId, String conversationContext,
                              CancellationToken token, ProgressListener progress) {
        return run(goal, sessionId, conversationContext, java.util.Collections.emptyList(), token, progress);
    }

    public AgentRunResult run(String goal, String sessionId, List<ConversationTurn> conversationHistory,
                              CancellationToken token, ProgressListener progress) {
        return run(goal, sessionId, "", conversationHistory, token, progress);
    }

    private AgentRunResult run(String goal, String sessionId, String conversationContext,
                               List<ConversationTurn> conversationHistory,
                               CancellationToken token, ProgressListener progress) {
        LocalRunStore store = new LocalRunStore(appContext);
        String runId = store.beginRun(goal, sessionId);
        AgentState state = new AgentState(runId, sessionId, goal, java.util.Collections.emptyList(),
                conversationContext, conversationHistory);
        state.enter("conversation");
        String outcome = "PARTIAL";
        String terminalOutcome = null;
        String message = "";
        Integer failureHttpStatus = null;
        String failureResponseBody = "";
        String failureType = "";
        String failureStackTrace = "";
        String failureModel = "";
        int totalCalls = 0;
        final int totalGoals = 0;
        boolean driverConnected = false;
        UnifiedController controller = new UnifiedController(driver);
        ActionValidator validator = new ActionValidator(registry, controller, driver, store, model);
        try {
            token.throwIfCancelled();
            while (state.turn < MAX_TURNS && !state.done) {
                token.throwIfCancelled();
                state.turn++;
                if (driverConnected) {
                    state.enter("perception");
                    emit(progress, "perception", "Refreshing the Android screen before the next tool decision");
                    state.observation = controller.capture(true, token);
                    state.indexedElements = UnifiedController.buildIndexedElements(state.observation);
                }

                state.enter("operator");
                emit(progress, "operator", "Reasoning over the current message, session transcript, and available tools");
                OperatorDecision decision = model.decide(state, token);
                token.throwIfCancelled();
                if (decision == null) throw new IllegalStateException("Operator model returned no decision");

                state.enter("execution_check");
                if (decision.finish) {
                    String finalText = decision.message == null ? "" : decision.message.trim();
                    if (!finalText.isEmpty()) {
                        message = finalText;
                        state.completedSubgoals = state.plan.size();
                        terminalOutcome = "COMPLETED";
                        state.done = true;
                        break;
                    }
                    state.findings.add("Operator returned finish without a user-facing answer; completion was not confirmed");
                    continue;
                }
                if (decision.toolCalls.isEmpty()) {
                    state.findings.add("Operator returned neither a final text answer nor a tool call");
                    continue;
                }
                totalCalls += decision.toolCalls.size();
                for (Map<String, Object> call : decision.toolCalls) {
                    emit(progress, "tool_call", String.valueOf(call.get("name")));
                }

                boolean needsObservation = requiresDeviceObservation(decision.toolCalls);
                if (needsObservation && state.observation == null) {
                    String blocker = deviceAccessBlocker();
                    if (blocker != null) {
                        for (Map<String, Object> call : decision.toolCalls) {
                            String name = String.valueOf(call.get("name"));
                            state.findings.add("Device tool " + name + " was not executed: " + blocker);
                            emit(progress, "tool_error", name + " · " + blocker);
                        }
                        continue;
                    }
                    try {
                        emit(progress, "device_use", "A device tool was requested; capturing a live screen before any action");
                        driver.connect();
                        driverConnected = true;
                        state.enter("perception");
                        state.observation = controller.capture(false, token);
                        state.indexedElements = UnifiedController.buildIndexedElements(state.observation);
                        state.findings.add("The initial device call was not executed because no live screen had been shown to the model. "
                                + "A current screenshot and accessibility hierarchy are now available; re-evaluate the request "
                                + "and issue the appropriate tool call using this observation: " + decision.toolCalls);
                    } catch (RuntimeException observationFailure) {
                        String detail = observationFailure.getMessage() == null
                                ? observationFailure.getClass().getSimpleName() : observationFailure.getMessage();
                        state.findings.add("No device action was executed because screen observation failed: " + detail);
                    }
                    continue;
                }

                List<ToolResult> results;
                boolean callsSucceeded;
                if (needsObservation) {
                    StepRecord step = new StepRecord(state.steps.size() + 1, state.observation, decision.toolCalls);
                    state.steps.add(step);
                    store.beginStep(step);
                    state.enter("validator");
                    emit(progress, "validator", "Checking preconditions and executing " + decision.toolCalls.size() + " tool call(s)");
                    ActionValidator.ValidationResult validation = validator.validateAndExecute(state, decision.toolCalls, token);
                    token.throwIfCancelled();
                    results = validation.results;
                    callsSucceeded = validation.success;
                    step.success = validation.success;
                    step.result = validation.message;
                    step.after = validation.after;
                    state.successfulActions += successfulActionCount(validation.results, registry);
                    for (ToolResult result : results) {
                        emit(progress, result.success ? "tool_result" : "tool_error", toolEventSummary(result));
                    }
                    if (!validation.success) {
                        state.findings.add("[validator] " + validation.message);
                        emit(progress, "validator", "Action rejected/failed: " + validation.message);
                        try {
                            state.observation = controller.capture(true, token);
                            state.indexedElements = UnifiedController.buildIndexedElements(state.observation);
                        } catch (RuntimeException refreshFailure) {
                            state.findings.add("Could not refresh the current screen after the failed action: " + refreshFailure.getMessage());
                        }
                    } else if (validation.after != null) {
                        state.observation = validation.after;
                        state.indexedElements = UnifiedController.buildIndexedElements(validation.after);
                    }
                    store.finishStep(step);
                    state.enter("summarizer");
                    emit(progress, "summarizer", "Persisting a factual device-action summary");
                    summarizer.dispatch(runId, step, store, model, sessionId, token);
                } else {
                    results = executeNonDeviceCalls(state, decision.toolCalls, controller, store, token);
                    callsSucceeded = results.stream().allMatch(result -> result.success);
                    for (ToolResult result : results) {
                        emit(progress, result.success ? "tool_result" : "tool_error", toolEventSummary(result));
                        state.findings.add("[tool result] " + toolEventSummary(result));
                    }
                }

                if (callsSucceeded) {
                    String reportedStatus = reportedTaskStatus(decision.toolCalls);
                    if (reportedStatus != null) {
                        String explanation = reportedTaskExplanation(decision.toolCalls, reportedStatus).trim();
                        if (explanation.isEmpty()) {
                            state.findings.add("report_task_status " + reportedStatus + " omitted an explanation; completion was not confirmed");
                        } else {
                            message = explanation;
                            terminalOutcome = "completed".equals(reportedStatus) ? "COMPLETED"
                                    : "failed".equals(reportedStatus) ? "FAILED" : "PARTIAL";
                            if ("COMPLETED".equals(terminalOutcome)) state.completedSubgoals = state.plan.size();
                            state.done = true;
                        }
                    }
                }
            }
            if (terminalOutcome != null) {
                outcome = terminalOutcome;
            } else if (state.turn >= MAX_TURNS) {
                outcome = "PARTIAL";
                message = "The assistant reached the conversation turn limit without confirming the result. "
                        + executionSummary(state.steps);
            } else {
                outcome = "PARTIAL";
                message = "The assistant ended without confirming a final answer. " + executionSummary(state.steps);
            }
            if (!state.findings.isEmpty() && message.isEmpty()) {
                message = "Findings: " + join(state.findings, " | ");
            }
        } catch (CancellationException stopped) {
            outcome = "STOPPED";
            message = "STOP latch cancelled the active graph loop.";
        } catch (RuntimeException failure) {
            if (token.isCancelled()) {
                outcome = "STOPPED";
                message = "STOP latch cancelled the active graph loop.";
            } else {
                outcome = "FAILED";
                message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                failureType = failure.getClass().getName();
                if (failure instanceof CodexHttpException) {
                    CodexHttpException httpFailure = (CodexHttpException) failure;
                    failureHttpStatus = httpFailure.statusCode;
                    failureResponseBody = httpFailure.responseBody;
                    failureModel = httpFailure.model;
                }
                StringWriter stack = new StringWriter();
                failure.printStackTrace(new PrintWriter(stack));
                failureStackTrace = stack.toString();
                Log.e(TAG, "Agent graph execution failed", failure);
            }
        } finally {
            driver.disconnect();
            try {
                store.finishRun(state, outcome);
            } catch (RuntimeException persistenceFailure) {
                Log.e(TAG, "Could not finalize the local run ledger", persistenceFailure);
                message = (message.isEmpty() ? "" : message + " ")
                        + "Local run record failed: " + persistenceFailure.getMessage();
            }
        }

        int implemented = 0;
        int partial = 0;
        int unsupported = 0;
        for (ToolSpec spec : registry.all()) {
            if (spec.status == ToolSpec.Status.IMPLEMENTED) implemented++;
            else if (spec.status == ToolSpec.Status.PARTIAL) partial++;
            else unsupported++;
        }
        List<String> trace = state == null ? new ArrayList<>() : new ArrayList<>(state.nodeTrace);
        return new AgentRunResult(runId, outcome, state == null ? 0 : state.completedSubgoals,
                totalGoals, totalCalls, trace, registry.all().size(), implemented, partial, unsupported, message,
                failureHttpStatus, failureResponseBody, failureType, failureStackTrace, failureModel);
    }

    private boolean requiresDeviceObservation(List<Map<String, Object>> calls) {
        for (Map<String, Object> call : calls) {
            ToolSpec spec = registry.get(String.valueOf(call.get("name")));
            if (spec != null && ("device".equals(spec.category)
                    || "perception".equals(spec.category) || "agent".equals(spec.category))) return true;
        }
        return false;
    }

    private String deviceAccessBlocker() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Android 11 or later is required for screenshots";
        }
        if (ArtemisAccessibilityService.getInstance() == null) {
            return "enable Jarvys in Android Accessibility settings";
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<ToolResult> executeNonDeviceCalls(AgentState state, List<Map<String, Object>> calls,
                                                   UnifiedController controller, LocalRunStore store,
                                                   CancellationToken token) {
        List<ToolResult> results = new ArrayList<>();
        ToolContext context = new ToolContext(controller, driver, state, token, store, model);
        for (Map<String, Object> call : calls) {
            token.throwIfCancelled();
            String name = String.valueOf(call.get("name"));
            if (!model.isToolAllowed(name)) {
                results.add(ToolResult.failure(name, "Skill allow-tools policy rejected this call"));
                continue;
            }
            Map<String, Object> arguments = call.get("arguments") instanceof Map
                    ? (Map<String, Object>) call.get("arguments") : java.util.Collections.emptyMap();
            ToolResult result = model.invokeDynamicTool(name, arguments, token);
            if (result == null) result = registry.invoke(name, arguments, context);
            results.add(result);
        }
        return results;
    }

    private static String executionSummary(List<StepRecord> steps) {
        if (steps.isEmpty()) return "No device actions were recorded.";
        StringBuilder summary = new StringBuilder("Recorded device actions:");
        int from = Math.max(0, steps.size() - 8);
        for (int index = from; index < steps.size(); index++) {
            StepRecord step = steps.get(index);
            summary.append("\nStep ").append(step.number).append(": ").append(step.decisions)
                    .append(" => ").append(step.success ? "success" : "not completed")
                    .append(step.result == null || step.result.isBlank() ? "" : " (" + step.result + ")");
        }
        return summary.toString();
    }

    private static void emit(ProgressListener listener, String node, String message) {
        if (listener != null) listener.onProgress(node, message);
    }

    private static String toolEventSummary(ToolResult result) {
        String detail;
        if (!result.success) {
            detail = result.error == null ? "failed" : result.error;
        } else if (result.value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) result.value;
            Object text = map.get("text");
            Object images = map.get("images");
            detail = text == null ? "completed" : String.valueOf(text);
            if (images instanceof List && !((List<?>) images).isEmpty()) {
                detail += " · " + ((List<?>) images).size() + " image(s) attached to next model turn";
            }
        } else {
            detail = result.value == null ? "completed" : String.valueOf(result.value);
        }
        if (detail.length() > 1800) detail = detail.substring(0, 1800) + "…[truncated]";
        return result.name + " · " + detail;
    }

    private static String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }

    private static int successfulActionCount(List<ToolResult> results, ToolRegistry registry) {
        int count = 0;
        for (ToolResult result : results) {
            ToolSpec spec = registry.get(result.name);
            if (spec != null && "device".equals(spec.category)) count++;
            else if (spec == null && result.success && result.name.startsWith("mcp_")) count++;
        }
        return count;
    }

    private static String reportedTaskStatus(List<Map<String, Object>> calls) {
        for (Map<String, Object> call : calls) {
            if (!"report_task_status".equals(String.valueOf(call.get("name")))) continue;
            Object raw = call.get("arguments");
            if (!(raw instanceof Map)) continue;
            String status = String.valueOf(((Map<?, ?>) raw).get("status")).trim().toLowerCase(java.util.Locale.ROOT);
            if ("completed".equals(status) || "failed".equals(status) || "partial".equals(status)) return status;
        }
        return null;
    }

    private static String reportedTaskExplanation(List<Map<String, Object>> calls, String status) {
        for (Map<String, Object> call : calls) {
            if (!"report_task_status".equals(String.valueOf(call.get("name")))) continue;
            Object raw = call.get("arguments");
            if (!(raw instanceof Map)) continue;
            Map<?, ?> arguments = (Map<?, ?>) raw;
            if (status.equalsIgnoreCase(String.valueOf(arguments.get("status")))) {
                Object explanation = arguments.get("explanation");
                return explanation == null ? "" : String.valueOf(explanation);
            }
        }
        return "";
    }
}
