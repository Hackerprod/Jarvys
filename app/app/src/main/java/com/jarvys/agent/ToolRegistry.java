package com.jarvys.agent;

import com.jarvys.agent.device.ScreenData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Complete auditable registry for graph tools and the actual Artemis action manifest. */
public final class ToolRegistry {
    public interface Handler {
        Object execute(Map<String, Object> arguments, ToolContext context);
    }

    private static final String ACTION_SOURCE = "artemis/mcp/action_specs.py";
    private static final Set<String> EXPECTED_INVENTORY = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "click", "click_sequence", "long_press", "input_text", "swipe", "press_key", "manage_app",
            "wait_for_delay", "wait_for_text", "open_link", "erase_one_char", "focus_and_clear_text",
            "save_note", "append_note", "read_note", "list_notes", "update_note", "search_history",
            "replay_steps", "get_step_screenshot", "report_task_status", "ask_explorer", "ask_diagnoser",
            "ask_committee", "run_adb_command", "manage_task", "analyze_task_output", "video_analyzer",
            "get_ui_hierarchy", "launch_app", "wait", "ask_perception_tool", "detect_objects", "get_ocr_list",
            "ask_image_processor", "inspect_region", "submit_answer", "ocr_recognition", "search_logs",
            "read_logs", "analyze_logs", "object_detection", "observe_screen", "take_screenshot",
            "video_analyzer_pure", "extract_segment_metadata", "spawn_sub_agent", "analyze_audio_only",
            "execute_python", "submit_result", "spawn_log_reader")));
    private final Map<String, ToolSpec> specs = new LinkedHashMap<>();
    private final Map<String, Handler> handlers = new LinkedHashMap<>();

    public ToolRegistry() {
        registerActions();
        registerGraphTools();
        registerExplorerTools();
        registerMobileAndAuxiliaryTools();
        if (!specs.keySet().equals(EXPECTED_INVENTORY)) {
            Set<String> missing = new LinkedHashSet<>(EXPECTED_INVENTORY);
            missing.removeAll(specs.keySet());
            Set<String> unexpected = new LinkedHashSet<>(specs.keySet());
            unexpected.removeAll(EXPECTED_INVENTORY);
            throw new IllegalStateException("Artemis tool inventory mismatch; missing=" + missing
                    + ", unexpected=" + unexpected);
        }
    }

    public synchronized List<ToolSpec> all() {
        return Collections.unmodifiableList(new ArrayList<>(specs.values()));
    }

    public synchronized Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(specs.keySet()));
    }

    /** Operator-facing graph surface; nested Explorer tools remain registered but are not bound at this role. */
    public synchronized List<ToolSpec> toolsForOperator() {
        Set<String> operatorNames = new LinkedHashSet<>(Arrays.asList(
                "click", "long_press", "input_text", "swipe", "press_key", "manage_app", "wait_for_delay",
                "save_note", "read_note", "list_notes", "update_note", "append_note", "ask_explorer",
                "ask_diagnoser", "run_adb_command", "manage_task", "search_history", "replay_steps",
                "get_step_screenshot", "video_analyzer", "analyze_task_output"));
        List<ToolSpec> result = new ArrayList<>();
        for (ToolSpec spec : specs.values()) {
            if (operatorNames.contains(spec.name) && spec.status != ToolSpec.Status.UNSUPPORTED) result.add(spec);
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized ToolSpec get(String name) {
        return specs.get(normalize(name));
    }

    public ToolResult invoke(String name, Map<String, Object> arguments, ToolContext context) {
        String canonical = normalize(name);
        ToolSpec spec;
        Handler handler;
        synchronized (this) {
            spec = specs.get(canonical);
            handler = handlers.get(canonical);
        }
        if (spec == null) return ToolResult.failure(canonical, "Unknown Artemis tool: " + canonical);
        Map<String, Object> args = arguments == null ? Collections.emptyMap() : arguments;
        for (String required : spec.required) {
            if (!args.containsKey(required) || args.get(required) == null) {
                return ToolResult.failure(canonical, "Missing required argument '" + required + "'");
            }
        }
        for (Map.Entry<String, String> field : spec.propertyTypes.entrySet()) {
            Object value = args.get(field.getKey());
            if (value != null && !matchesType(value, field.getValue())) {
                return ToolResult.failure(canonical, "Argument '" + field.getKey() + "' must be " + field.getValue());
            }
        }
        try {
            context.token.throwIfCancelled();
            if (handler == null) {
                throw new UnsupportedOperationException("Tool '" + canonical + "' is registered from "
                        + spec.source + " but is explicitly unavailable in Stage C: "
                        + unsupportedReason(canonical));
            }
            Object result = handler.execute(args, context);
            context.token.throwIfCancelled();
            if (Boolean.FALSE.equals(result)) {
                return ToolResult.failure(canonical, "Tool returned false: the underlying operation did not complete");
            }
            return ToolResult.success(canonical, result);
        } catch (RuntimeException e) {
            return ToolResult.failure(canonical,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void registerActions() {
        add("click", ACTION_SOURCE, "Tap by indexed element or normalized coordinate pair.", "device",
                ToolSpec.Status.IMPLEMENTED, props("target", "integer_or_array", "target_description", "string",
                        "times", "integer", "delay_ms", "integer"), req("target"), action("click"));
        add("click_sequence", ACTION_SOURCE, "Run an ordered sequence of normalized taps.", "device",
                ToolSpec.Status.IMPLEMENTED, props("sequence", "array", "target_descriptions", "array",
                        "delay_ms", "integer"), req("sequence", "target_descriptions"), action("click_sequence"));
        add("long_press", ACTION_SOURCE, "Long press an indexed element or normalized coordinate pair.", "device",
                ToolSpec.Status.IMPLEMENTED, props("target", "integer_or_array", "target_description", "string",
                        "duration", "integer"), req("target"), action("long_press"));
        add("input_text", ACTION_SOURCE, "Replace or append text to an indexed or coordinate-targeted input.", "device",
                ToolSpec.Status.IMPLEMENTED, props("text", "string", "target", "integer_or_array",
                        "target_description", "string", "clear_exist", "boolean"), req("text", "target"), action("input_text"));
        add("swipe", ACTION_SOURCE, "Swipe/drag. Prefer direction='up'/'down' for ordinary scrolling. "
                        + "For a coordinate swipe, start and end must EACH be exactly [x,y] integer coordinates normalized 0-1000 relative to the screenshot; never copy physical-pixel bounds shown in Visible UI Elements. "
                        + "Example: {\"start\":[500,800],\"end\":[500,200],\"target_description\":\"scroll the calendar list upward\"}.", "device",
                ToolSpec.Status.IMPLEMENTED, props("direction", "string", "start", "normalized_point", "end", "normalized_point",
                        "target", "integer_array_or_string", "gesture", "swipe_gesture",
                        "target_description", "string", "duration", "integer"),
                Collections.emptyList(), action("swipe"));
        add("press_key", ACTION_SOURCE, "Press a supported global action or focused-field key action.", "device",
                ToolSpec.Status.PARTIAL, props("key", "string"), req("key"), action("press_key"));
        add("manage_app", ACTION_SOURCE, "Launch an installed app; force-stop is explicitly unsupported.", "device",
                ToolSpec.Status.PARTIAL, props("action", "string", "app_name", "string"),
                req("action", "app_name"), action("manage_app"));
        add("wait_for_delay", ACTION_SOURCE, "Wait for milliseconds with cooperative STOP cancellation.", "device",
                ToolSpec.Status.IMPLEMENTED, props("time_in_ms", "integer"), req("time_in_ms"), action("wait_for_delay"));
        add("wait_for_text", ACTION_SOURCE, "Poll the current accessibility hierarchy for text appearance/disappearance.", "device",
                ToolSpec.Status.IMPLEMENTED, props("text", "string", "wait_state", "string", "timeout_ms", "integer"),
                req("text"), action("wait_for_text"));
        add("open_link", ACTION_SOURCE, "Open a URL through Android ACTION_VIEW.", "device",
                ToolSpec.Status.IMPLEMENTED, props("url", "string"), req("url"), action("open_link"));
        add("erase_one_char", ACTION_SOURCE, "Remove one Unicode code point from the focused editable field.", "device",
                ToolSpec.Status.IMPLEMENTED, props(), req(), action("erase_one_char"));
        add("focus_and_clear_text", ACTION_SOURCE, "Tap a normalized target, then clear the focused text field.", "device",
                ToolSpec.Status.IMPLEMENTED, props("target", "array"), req("target"), action("focus_and_clear_text"));
    }

    private void registerGraphTools() {
        add("save_note", "artemis/tools/scratchpad.py", "Save or replace an app-private note.", "memory",
                ToolSpec.Status.IMPLEMENTED, props("key", "string", "content", "string"), req("key", "content"),
                (a, c) -> { c.store.saveNote(string(a, "key"), string(a, "content")); return "Saved note " + string(a, "key"); });
        add("append_note", "artemis/tools/scratchpad.py", "Append text to an app-private note.", "memory",
                ToolSpec.Status.IMPLEMENTED, props("key", "string", "content", "string"), req("key", "content"),
                (a, c) -> { c.store.appendNote(string(a, "key"), string(a, "content")); return "Appended note " + string(a, "key"); });
        add("read_note", "artemis/tools/scratchpad.py", "Read an app-private note or line range.", "memory",
                ToolSpec.Status.IMPLEMENTED, props("key", "string", "start_line", "integer", "end_line", "integer"), req("key"),
                (a, c) -> c.store.readNote(string(a, "key"), intValue(a, "start_line", 0), intValue(a, "end_line", 0)));
        add("list_notes", "artemis/tools/scratchpad.py", "List app-private note keys.", "memory",
                ToolSpec.Status.IMPLEMENTED, props(), req(), (a, c) -> c.store.listNotes());
        add("update_note", "artemis/tools/scratchpad.py", "Replace the first exact target string in a note.", "memory",
                ToolSpec.Status.IMPLEMENTED, props("key", "string", "target", "string", "replacement", "string"),
                req("key", "target", "replacement"),
                (a, c) -> c.store.updateNote(string(a, "key"), string(a, "target"), string(a, "replacement")));

        add("search_history", "artemis/tools/history/__init__.py", "Keyword or step-range search over locally recorded steps.", "memory",
                ToolSpec.Status.IMPLEMENTED, props("query", "string", "step_range", "array", "max_results", "integer"),
                Collections.emptyList(), (a, c) -> c.store.searchHistory(string(a, "query"), rangeStart(a), rangeEnd(a),
                        intValue(a, "max_results", 5)));
        add("replay_steps", "artemis/tools/history/__init__.py", "Replay stored step records by inclusive range.", "memory",
                ToolSpec.Status.IMPLEMENTED, props("start_step", "integer", "end_step", "integer"), req("start_step"),
                (a, c) -> c.store.replaySteps(intValue(a, "start_step", 1), intValue(a, "end_step", intValue(a, "start_step", 1))));
        add("get_step_screenshot", "artemis/tools/history/__init__.py", "Return Base64 JPEG of a stored pre/post screenshot.", "memory",
                ToolSpec.Status.PARTIAL, props("step_number", "integer", "which", "string"), req("step_number"),
                (a, c) -> c.store.readScreenshotImage(intValue(a, "step_number", 0), stringOr(a, "which", "pre")));

        add("report_task_status", "artemis/agents/validator/tool_declarations.py", "Report final task status and explanation.", "system",
                ToolSpec.Status.IMPLEMENTED, props("status", "string", "explanation", "string"), req("status", "explanation"),
                (a, c) -> { c.state.findings.add(string(a, "status") + ": " + string(a, "explanation")); return "Status recorded"; });

        add("ask_explorer", "artemis/tools/explorer_tool.py", "Structural-first exploration with multimodal provider fallback.", "agent",
                ToolSpec.Status.PARTIAL, props("query", "string", "context_feedback", "string"), req("query"),
                (a, c) -> c.model.explore(string(a, "query"), string(a, "context_feedback"), c.state, c.token));
        add("ask_diagnoser", "artemis/tools/diagnostic_tool.py", "Diagnose from the current screen and recorded run; has no Logcat/video access.", "agent",
                ToolSpec.Status.PARTIAL, props("query", "string"), req("query"),
                (a, c) -> c.model.diagnose(string(a, "query"), c.state, c.token));
        unsupported("ask_committee", "artemis/tools/committee_tool.py", "Delegate a question to the Committee agents.", "agent",
                props("avatar_directive", "string"), req("avatar_directive"));
        unsupported("run_adb_command", "artemis/tools/command_tool.py", "Run a device shell command through the host ADB bridge.", "system",
                props("CommandLine", "string", "Cwd", "string", "RunPersistent", "boolean",
                        "RequestedTerminalID", "string", "WaitMsBeforeAsync", "integer", "Interactive", "boolean"), req("CommandLine"));
        unsupported("manage_task", "artemis/tools/command_tool.py", "Manage a background ADB shell task.", "system",
                props("Action", "string", "TaskId", "string", "Input", "string"), req("Action"));
        unsupported("analyze_task_output", "artemis/tools/command_tool.py", "Analyze output from a background command task.", "system",
                props("task_id", "string"), req("task_id"));
        unsupported("video_analyzer", "artemis/tools/video_tool.py", "Analyze a screen recording and its audio/video events.", "agent",
                props("instruction", "string"), req("instruction"));
    }

    private void registerExplorerTools() {
        add("get_ui_hierarchy", "artemis/tools/mobile/read_hierarchy.py", "Return the current UIAutomator XML hierarchy.", "perception",
                ToolSpec.Status.IMPLEMENTED, props(), req(), (a, c) -> c.controller.currentHierarchyXml());
        add("launch_app", "artemis/tools/mobile/launch_app.py", "Launch an app by installed package or exact launcher label.", "device",
                ToolSpec.Status.IMPLEMENTED, props("app_name", "string"), req("app_name"),
                (a, c) -> c.controller.launchAppByLabel(string(a, "app_name"), c.token));
        add("wait", "artemis/tools/wait_tool.py", "Pause for an integer number of seconds, clamped to 1-60.", "system",
                ToolSpec.Status.IMPLEMENTED, props("seconds", "integer"), req("seconds"),
                (a, c) -> { int seconds = Math.max(1, Math.min(60, intValue(a, "seconds", 1))); return c.driver.waitForDelay(seconds, c.token); });

        add("ask_perception_tool", "artemis/agents/explorer/tool_declarations.py", "Combine UI search, coordinate audit and visual detection.", "perception",
                ToolSpec.Status.PARTIAL, props("search_query", "string", "nx", "integer", "ny", "integer", "detect_queries", "array"),
                req("search_query", "nx", "ny", "detect_queries"),
                (a, c) -> c.model.askPerception(string(a, "search_query"), intValue(a, "nx", -1),
                        intValue(a, "ny", -1), strings(a.get("detect_queries")), c.state, c.token));
        add("detect_objects", "artemis/agents/explorer/tool_declarations.py", "Locate visual objects using the selected vision-capable provider.", "perception",
                ToolSpec.Status.PARTIAL, props("target_image_id", "string", "queries", "array"), req("queries"),
                (a, c) -> {
                    String imageId = stringOr(a, "target_image_id", "img_0");
                    if (!"img_0".equals(imageId)) throw new IllegalArgumentException("Only current-run image img_0 is available to Explorer in Stage D");
                    return c.model.detectObjects(strings(a.get("queries")), c.state, c.token);
                });
        add("get_ocr_list", "artemis/agents/explorer/tool_declarations.py; artemis/tools/mobile/ocr.py",
                "Read visible text and normalized positions with the selected vision-capable provider.", "perception",
                ToolSpec.Status.PARTIAL, props(), req(), (a, c) -> c.model.getOcrList(c.state, c.token));
        add("ask_image_processor", "artemis/tools/image_processor_tool.py", "Run model-directed pixel image processing.", "perception",
                ToolSpec.Status.UNSUPPORTED, props("target_image_id", "string", "instruction", "string"),
                req("target_image_id", "instruction"), null);
        add("inspect_region", "artemis/agents/explorer/tool_declarations.py", "Crop and zoom a screenshot region.", "perception",
                ToolSpec.Status.IMPLEMENTED, props("x_min", "integer", "y_min", "integer", "x_max", "integer",
                        "y_max", "integer", "zoom_factor", "number"),
                req("x_min", "y_min", "x_max", "y_max", "zoom_factor"),
                (a, c) -> c.controller.inspectRegion(a, c.token));
        add("submit_answer", "artemis/agents/explorer/tool_declarations.py; artemis/tools/diagnoser_submit_answer_tool.py; artemis/agents/video_analyzer/universal_tools.py",
                "Validate Explorer candidates or format a Diagnoser final answer.", "perception",
                ToolSpec.Status.IMPLEMENTED, props("candidates", "array", "fallback_message", "string",
                        "analysis", "string", "actionable_steps", "array"), Collections.emptyList(),
                (a, c) -> a.containsKey("analysis")
                        ? "Diagnoser answer submitted: " + String.valueOf(a.get("analysis"))
                            + "\nActionable steps: " + String.valueOf(a.get("actionable_steps"))
                        : c.controller.formatCandidates(a));

        add("ocr_recognition", "artemis/tools/mobile/ocr.py", "OCR current screenshot using the configured multimodal provider.", "perception",
                ToolSpec.Status.PARTIAL, props(), req(), (a, c) -> c.model.getOcrList(c.state, c.token));
        unsupported("search_logs", "artemis/tools/mobile/search_logs.py", "Search device Logcat output.", "diagnostic",
                props("keyword", "string", "is_regex", "boolean", "lines", "integer", "since_time", "string",
                        "until_time", "string", "context_lines", "integer"), req("keyword"));
        unsupported("read_logs", "artemis/tools/mobile/read_logs.py", "Read device Logcat output.", "diagnostic",
                props("lines", "integer", "since_time", "string", "until_time", "string"), Collections.emptyList());
        unsupported("analyze_logs", "artemis/tools/log_tool.py", "Analyze device log output with the Log Analyzer agent.", "diagnostic",
                props("specific_query", "string"), req("specific_query"));
        unsupported("spawn_log_reader", "artemis/agents/log_analyzer/log_analyzer.py", "Delegate a complex log search to the Log Reader sub-agent.", "diagnostic",
                props("specific_query", "string"), req("specific_query"));
        add("object_detection", "artemis/tools/object_detection_tool.py", "Detect queried objects in the current screen using the selected vision provider.", "perception",
                ToolSpec.Status.PARTIAL, props("image_path", "string", "queries", "array"), req("queries"),
                (a, c) -> {
                    if (!string(a, "image_path").isEmpty()) {
                        throw new UnsupportedOperationException("object_detection only accepts the active screen image, not arbitrary filesystem paths");
                    }
                    return c.model.detectObjects(strings(a.get("queries")), c.state, c.token);
                });
    }

    private void registerMobileAndAuxiliaryTools() {
        add("observe_screen", "artemis/mcp/action_manifest.py", "Internal graph observation action; not an Operator-facing tool.", "internal",
                ToolSpec.Status.IMPLEMENTED, props(), req(), (a, c) -> c.controller.capture(false, c.token));
        add("take_screenshot", "artemis/mcp/action_manifest.py", "Internal takeScreenshot image capture; not an Operator-facing tool.", "internal",
                ToolSpec.Status.IMPLEMENTED, props(), req(), (a, c) -> c.controller.capture(true, c.token).screenshotBase64);
        unsupported("video_analyzer_pure", "artemis/tools/video_tool.py", "Analyze video without the standard wrapper path.", "agent",
                props("instruction", "string"), Collections.emptyList());
        unsupported("extract_segment_metadata", "artemis/agents/video_analyzer/video_analyzer.py", "Extract a recording segment and its metadata.", "video",
                props("start_relative_time", "number", "end_relative_time", "number"), req("start_relative_time"));
        unsupported("spawn_sub_agent", "artemis/agents/video_analyzer/video_analyzer.py", "Spawn a video-analysis sub-agent.", "video",
                props("task", "string"), req("task"));
        unsupported("analyze_audio_only", "artemis/agents/video_analyzer/video_analyzer.py", "Analyze an audio-only recording segment.", "video",
                props("instruction", "string"), Collections.emptyList());
        unsupported("execute_python", "artemis/agents/image_processor/image_processor.py", "Execute image processor generated Python code.", "perception",
                props("code", "string"), req("code"));
        unsupported("submit_result", "artemis/agents/image_processor/image_processor.py", "Submit image processor output.", "perception",
                props("result", "string"), req("result"));
    }

    private static String unsupportedReason(String name) {
        if ("ask_image_processor".equals(name) || "execute_python".equals(name) || "submit_result".equals(name)) {
            return "product-approved exclusion: Artemis runs arbitrary generated Python in an isolated Jupyter image sandbox; Jarvys has no equivalent Java sandbox. Use inspect_region for supported crop/zoom operations";
        }
        if ("run_adb_command".equals(name) || "manage_task".equals(name) || "analyze_task_output".equals(name)) {
            return "production runs are in-process and the app has no ADB host or shell permission";
        }
        if ("read_logs".equals(name) || "search_logs".equals(name) || "analyze_logs".equals(name)) {
            return "ordinary Android apps cannot read system Logcat without privileged access";
        }
        if (name.contains("video") || name.contains("segment") || "analyze_audio_only".equals(name)) {
            return "video/audio capture and analysis are outside the approved Stage C scope";
        }
        if (name.contains("ocr") || name.contains("detect") || name.contains("perception")
                || name.contains("image") || "object_detection".equals(name)) {
            return "the required OCR/vision model or image-processing engine is not part of the deterministic Stage C runtime";
        }
        if (name.startsWith("ask_") || "spawn_sub_agent".equals(name)) {
            return "specialized sub-agent execution is deferred; Stage C uses only its fixed local fake model";
        }
        return "the dependent Artemis backend or auxiliary subsystem is deferred beyond this deterministic cycle test";
    }

    private Handler action(String actionName) {
        return (arguments, context) -> context.controller.executeDeviceAction(
                actionName, context.state, context.token, arguments);
    }

    private void unsupported(String name, String source, String description, String category,
                             Map<String, String> properties, List<String> required) {
        add(name, source, description, category, ToolSpec.Status.UNSUPPORTED, properties, required, null);
    }

    private void add(String name, String source, String description, String category,
                     ToolSpec.Status status, Map<String, String> properties, List<String> required,
                     Handler handler) {
        String canonical = normalize(name);
        if (specs.containsKey(canonical)) {
            throw new IllegalStateException("Duplicate tool registration: " + canonical);
        }
        specs.put(canonical, new ToolSpec(canonical, source, description, category, status, properties, required));
        if (handler != null) handlers.put(canonical, handler);
    }

    private static Map<String, String> props(String... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("property list must be key/type pairs");
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    private static List<String> req(String... names) {
        return Arrays.asList(names);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean matchesType(Object value, String type) {
        switch (type) {
            case "string": return value instanceof String;
            case "integer": return integer(value);
            case "number": return value instanceof Number;
            case "boolean": return value instanceof Boolean;
            case "array": return value instanceof List;
            case "object": return value instanceof Map;
            case "integer_or_array": return value instanceof List || integer(value);
            case "string_or_array": return value instanceof List || value instanceof String;
            case "integer_array_or_string": return value instanceof List || integer(value) || value instanceof String;
            case "normalized_point": return normalizedPoint(value);
            case "swipe_gesture": return value instanceof String || normalizedGesture(value);
            default: throw new IllegalStateException("Unknown declared JSON schema type: " + type);
        }
    }

    private static boolean normalizedPoint(Object value) {
        if (!(value instanceof List) || ((List<?>) value).size() != 2) return false;
        for (Object coordinate : (List<?>) value) {
            if (!integer(coordinate)) return false;
            int number = ((Number) coordinate).intValue();
            if (number < 0 || number > 1000) return false;
        }
        return true;
    }

    private static boolean normalizedGesture(Object value) {
        if (!(value instanceof List) || ((List<?>) value).size() != 4) return false;
        for (Object coordinate : (List<?>) value) {
            if (!integer(coordinate)) return false;
            int number = ((Number) coordinate).intValue();
            if (number < 0 || number > 1000) return false;
        }
        return true;
    }

    private static boolean integer(Object value) {
        return value instanceof Number
                && Double.isFinite(((Number) value).doubleValue())
                && ((Number) value).doubleValue() == ((Number) value).intValue();
    }

    private static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String stringOr(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int intValue(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt((String) value); } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    private static int rangeStart(Map<String, Object> args) {
        Object range = args.get("step_range");
        if (range instanceof List && !((List<?>) range).isEmpty() && ((List<?>) range).get(0) instanceof Number) {
            return ((Number) ((List<?>) range).get(0)).intValue();
        }
        return 0;
    }

    private static int rangeEnd(Map<String, Object> args) {
        Object range = args.get("step_range");
        if (range instanceof List && ((List<?>) range).size() > 1 && ((List<?>) range).get(1) instanceof Number) {
            return ((Number) ((List<?>) range).get(1)).intValue();
        }
        return 0;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List)) throw new IllegalArgumentException("Expected an array of strings");
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof String)) throw new IllegalArgumentException("Expected an array of strings");
            result.add((String) item);
        }
        return result;
    }
}
