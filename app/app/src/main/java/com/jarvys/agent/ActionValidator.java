package com.jarvys.agent;

import com.jarvys.agent.device.AccessibilityDriver;
import com.jarvys.agent.device.ScreenData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Structural and provider-backed visual preconditions before tool dispatch. */
public final class ActionValidator {
    public static final class ValidationResult {
        public final boolean success;
        public final List<ToolResult> results;
        public final String message;
        public final ScreenData after;

        ValidationResult(boolean success, List<ToolResult> results, String message, ScreenData after) {
            this.success = success;
            this.results = results;
            this.message = message;
            this.after = after;
        }
    }

    private final ToolRegistry registry;
    private final UnifiedController controller;
    private final LocalRunStore store;
    private final AccessibilityDriver driver;
    private final AgentModel model;

    public ActionValidator(ToolRegistry registry, UnifiedController controller,
                           AccessibilityDriver driver, LocalRunStore store, AgentModel model) {
        this.registry = registry;
        this.controller = controller;
        this.driver = driver;
        this.store = store;
        this.model = model;
    }

    public ValidationResult validateAndExecute(AgentState state, List<Map<String, Object>> decisions,
                                               CancellationToken token) {
        if (state.observation == null) {
            return new ValidationResult(false, new ArrayList<>(), "Validator has no pre-action screen observation", null);
        }
        List<ToolResult> results = new ArrayList<>();
        for (Map<String, Object> call : decisions) {
            token.throwIfCancelled();
            String name = String.valueOf(call.get("name"));
            if (!model.isToolAllowed(name)) {
                ToolResult blocked = ToolResult.failure(name, "Skill allow-tools policy rejected this call");
                results.add(blocked);
                return new ValidationResult(false, results, blocked.error, null);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> args = call.get("arguments") instanceof Map
                    ? (Map<String, Object>) call.get("arguments") : java.util.Collections.emptyMap();
            String precondition = validatePrecondition(name, args, state);
            if (precondition != null) {
                ToolResult blocked = ToolResult.failure(name, "Precondition rejected action: " + precondition);
                results.add(blocked);
                return new ValidationResult(false, results, blocked.error, null);
            }
            String visualFailure = validateVisualPrecondition(name, args, state, token);
            if (visualFailure != null) {
                ToolResult blocked = ToolResult.failure(name, visualFailure);
                results.add(blocked);
                return new ValidationResult(false, results, blocked.error, null);
            }
            ToolContext context = new ToolContext(controller, driver, state, token, store, model);
            ToolResult result = model.invokeDynamicTool(name, args, token);
            if (result == null) result = registry.invoke(name, args, context);
            results.add(result);
            if (!result.success) return new ValidationResult(false, results, formatResults(results), null);
            attachToolImage(result.value, state);
        }
        token.throwIfCancelled();
        ScreenData post = controller.capture(true, token);
        return new ValidationResult(true, results, formatResults(results), post);
    }

    private String validatePrecondition(String name, Map<String, Object> args, AgentState state) {
        if ("click".equals(name) || "long_press".equals(name) || "input_text".equals(name)) {
            Object target = args.get("target");
            if (target instanceof Number) {
                int index = ((Number) target).intValue();
                if (index < 1 || index > state.indexedElements.size()) {
                    return "1-based visible-element index " + index + " is outside current indexed list (1-"
                            + state.indexedElements.size() + ")";
                }
                if ("input_text".equals(name)
                        && !Boolean.TRUE.equals(state.indexedElements.get(index - 1).get("editable"))) {
                    return "input_text target is not marked editable in the current visible-element index";
                }
            } else if (target instanceof List) {
                if (!validPoint(target)) return "coordinate target must be normalized [x,y] within 0-1000";
                if (empty(args.get("target_description"))) {
                    return "target_description is required when target is a coordinate pair";
                }
            } else {
                return "action requires an observed element index or normalized coordinate pair";
            }
        }
        if ("swipe".equals(name)) {
            boolean directed = args.get("direction") instanceof String || args.get("gesture") instanceof String;
            boolean ranged = args.get("start") instanceof List || args.get("end") instanceof List;
            boolean legacy = args.get("gesture") instanceof List;
            if (!directed && !ranged && !legacy) return "swipe requires direction or a complete coordinate pair";
            if (ranged && (!validPoint(args.get("start")) || !validPoint(args.get("end")))) {
                return "swipe start/end must be normalized [x,y] pairs";
            }
            if (legacy) {
                List<?> points = (List<?>) args.get("gesture");
                if (points.size() != 4 || !allNumbers(points)) return "legacy swipe gesture must be [x1,y1,x2,y2]";
                for (Object point : points) {
                    int coordinate = ((Number) point).intValue();
                    if (coordinate < 0 || coordinate > 1000) return "legacy swipe coordinates must be 0-1000";
                }
            }
            if ((ranged || legacy) && empty(args.get("target_description"))) {
                return "target_description is required for coordinate swipes";
            }
        }
        if ("click_sequence".equals(name)) {
            Object rawPoints = args.get("sequence"), rawLabels = args.get("target_descriptions");
            if (!(rawPoints instanceof List) || !(rawLabels instanceof List)
                    || ((List<?>) rawPoints).size() != ((List<?>) rawLabels).size()) {
                return "click_sequence needs one target_description per coordinate pair";
            }
            List<?> points = (List<?>) rawPoints, labels = (List<?>) rawLabels;
            for (int i = 0; i < points.size(); i++) {
                if (!validPoint(points.get(i)) || empty(labels.get(i))) {
                    return "click_sequence requires valid normalized points and non-empty target descriptions";
                }
            }
        }
        return null;
    }

    private String validateVisualPrecondition(String name, Map<String, Object> args,
                                              AgentState state, CancellationToken token) {
        Object target = args.get("target");
        if (("click".equals(name) || "long_press".equals(name) || "input_text".equals(name))
                && target instanceof List) {
            if (!model.verifyVisualTarget(String.valueOf(args.get("target_description")),
                    pair((List<?>) target), state.observation, token)) {
                return "Visual precondition rejected the coordinate target; no device action was dispatched";
            }
        }
        if ("swipe".equals(name)) {
            if (args.get("start") instanceof List) {
                List<?> start = (List<?>) args.get("start"), end = (List<?>) args.get("end");
                if (!model.verifyVisualTarget(String.valueOf(args.get("target_description"))
                        + " (drag from " + start + " to " + end + ")", pair(start), state.observation, token)) {
                    return "Visual precondition rejected the swipe target; no device action was dispatched";
                }
            } else if (args.get("gesture") instanceof List) {
                List<?> values = (List<?>) args.get("gesture");
                int[] start = new int[]{((Number) values.get(0)).intValue(), ((Number) values.get(1)).intValue()};
                int[] end = new int[]{((Number) values.get(2)).intValue(), ((Number) values.get(3)).intValue()};
                if (!model.verifyVisualTarget(String.valueOf(args.get("target_description"))
                        + " (drag from " + start[0] + "," + start[1] + " to " + end[0] + "," + end[1] + ")",
                        start, state.observation, token)) {
                    return "Visual precondition rejected the legacy swipe target; no device action was dispatched";
                }
            }
        }
        if ("click_sequence".equals(name)) {
            List<?> points = (List<?>) args.get("sequence");
            List<?> descriptions = (List<?>) args.get("target_descriptions");
            for (int i = 0; i < points.size(); i++) {
                if (!model.verifyVisualTarget(String.valueOf(descriptions.get(i)), pair((List<?>) points.get(i)),
                        state.observation, token)) return "Visual precondition rejected click_sequence target " + (i + 1);
            }
        }
        return null;
    }

    private static boolean validPoint(Object value) {
        if (!(value instanceof List) || ((List<?>) value).size() != 2) return false;
        List<?> point = (List<?>) value;
        if (!(point.get(0) instanceof Number) || !(point.get(1) instanceof Number)) return false;
        int x = ((Number) point.get(0)).intValue(), y = ((Number) point.get(1)).intValue();
        return x >= 0 && x <= 1000 && y >= 0 && y <= 1000;
    }

    private static boolean allNumbers(List<?> values) {
        for (Object value : values) if (!(value instanceof Number)) return false;
        return true;
    }

    private static int[] pair(List<?> values) {
        return new int[]{((Number) values.get(0)).intValue(), ((Number) values.get(1)).intValue()};
    }

    private static boolean empty(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static void attachToolImage(Object value, AgentState state) {
        if (!(value instanceof Map)) return;
        Map<String, Object> image = (Map<String, Object>) value;
        Object images = image.get("images");
        if (images instanceof List) {
            for (Object item : (List<?>) images) attachToolImage(item, state);
        }
        attachSingleToolImage(image, state);
    }

    @SuppressWarnings("unchecked")
    private static void attachSingleToolImage(Object value, AgentState state) {
        if (!(value instanceof Map)) return;
        Map<String, Object> image = (Map<String, Object>) value;
        Object base64 = image.get("base64"), widthValue = image.get("width"), heightValue = image.get("height");
        if (!(base64 instanceof String) || !(widthValue instanceof Number) || !(heightValue instanceof Number)) return;
        int width = ((Number) widthValue).intValue(), height = ((Number) heightValue).intValue();
        if (width <= 0 || height <= 0) return;
        byte[] bytes;
        try {
            bytes = android.util.Base64.decode((String) base64, android.util.Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Tool returned malformed Base64 image data", e);
        }
        state.supplementalImages.add(new ScreenData(bytes, (String) base64, null,
                java.util.Collections.emptyList(), width, height, System.currentTimeMillis() / 1000.0, "android"));
        state.supplementalImageLabels.add(String.valueOf(image.getOrDefault("label", "tool image")));
    }

    @SuppressWarnings("unchecked")
    private static String formatResults(List<ToolResult> results) {
        StringBuilder summary = new StringBuilder();
        for (ToolResult result : results) {
            if (summary.length() > 0) summary.append("; ");
            summary.append(result.name).append(result.success ? " => " : " failed: ");
            Object value = result.success ? result.value : result.error;
            if (value instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) value;
                if (map.containsKey("text")) summary.append(map.get("text"));
                else if (map.containsKey("base64")) summary.append("image attached to next model turn");
                else summary.append(map.keySet());
            } else if (value instanceof String && ((String) value).length() > 3000) {
                summary.append(((String) value).substring(0, 3000)).append("…[truncated]");
            } else {
                summary.append(String.valueOf(value));
            }
        }
        return summary.toString();
    }
}
