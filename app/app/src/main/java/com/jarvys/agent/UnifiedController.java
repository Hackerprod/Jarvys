package com.jarvys.agent;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.jarvys.agent.device.AccessibilityDriver;
import com.jarvys.agent.device.ElementMatch;
import com.jarvys.agent.device.ScreenData;
import com.jarvys.agent.device.ScreenSize;
import com.jarvys.agent.device.SwipeDirection;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** Coordinate/action adapter between the agent dialect and AccessibilityDriver. */
public final class UnifiedController {
    private final AccessibilityDriver driver;

    public UnifiedController(AccessibilityDriver driver) {
        this.driver = driver;
    }

    public AccessibilityDriver driver() {
        return driver;
    }

    public ScreenData capture(boolean skipSettling, CancellationToken token) {
        return driver.getScreenData(skipSettling, token);
    }

    public String currentHierarchyXml() {
        return driver.getHierarchyXml();
    }

    /** Operator-style 1-based Visible UI Elements index (text/hint + live physical bounds). */
    public static List<Map<String, Object>> buildIndexedElements(ScreenData screen) {
        List<Map<String, Object>> indexed = new ArrayList<>();
        List<String> seenText = new ArrayList<>();
        List<int[]> seenCenters = new ArrayList<>();
        for (Map<String, Object> node : screen.uiElements) {
            String text = string(node.get("text"));
            String label = text.trim().isEmpty() ? string(node.get("content-desc")).trim() : text.trim();
            if (label.isEmpty()) label = string(node.get("hint")).trim();
            if (label.isEmpty()) continue;
            int[] bounds = rawPhysicalBounds(node);
            if (bounds == null || bounds[2] <= bounds[0] || bounds[3] <= bounds[1]) continue;
            int[] center = new int[]{(bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2};
            boolean duplicate = false;
            for (int i = 0; i < seenText.size(); i++) {
                if (seenText.get(i).equals(label)) {
                    double dx = center[0] - seenCenters.get(i)[0];
                    double dy = center[1] - seenCenters.get(i)[1];
                    if (Math.sqrt(dx * dx + dy * dy) < 8.0) {
                        duplicate = true;
                        break;
                    }
                }
            }
            if (duplicate) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", indexed.size() + 1);
            item.put("text", label);
            item.put("center", center);
            item.put("bounds", bounds);
            item.put("resource_id", string(node.get("resource-id")));
            item.put("class", string(node.get("class")));
            item.put("editable", Boolean.TRUE.equals(node.get("editable")));
            indexed.add(item);
            seenText.add(label);
            seenCenters.add(center);
        }
        return Collections.unmodifiableList(indexed);
    }

    public Object executeDeviceAction(String actionName, AgentState state, CancellationToken token,
                                      Map<String, Object> args) {
        token.throwIfCancelled();
        switch (actionName) {
            case "click":
                return click(args, state.observation, state.indexedElements, token);
            case "click_sequence":
                return clickSequence(args, token);
            case "long_press":
                return longPress(args, state.observation, state.indexedElements, token);
            case "input_text":
                return inputText(args, state.observation, state.indexedElements, token);
            case "swipe":
                return swipe(args, state.observation, state.indexedElements, token);
            case "press_key":
                return driver.pressKey(string(args.get("key")), token);
            case "manage_app":
                if ("launch".equalsIgnoreCase(string(args.get("action")))) {
                    return driver.launchAppByLabel(string(args.get("app_name")), token);
                }
                if ("stop".equalsIgnoreCase(string(args.get("action")))) {
                    return driver.stopApp(string(args.get("app_name")), token);
                }
                throw new IllegalArgumentException("manage_app.action must be launch or stop");
            case "wait_for_delay":
                return driver.waitForDelay(number(args.get("time_in_ms"), 0) / 1000.0, token);
            case "wait_for_text":
                return waitForText(args, token);
            case "open_link":
                return driver.openLink(string(args.get("url")), token);
            case "erase_one_char":
                return driver.pressKey("delete", token);
            case "focus_and_clear_text":
                int[] point = normalizedPoint(args.get("target"));
                ScreenSize size = driver.screenSize();
                boolean focused = driver.tapNormalized(point[0], point[1], false, 1000, 1, 100, token);
                if (!focused) return false;
                if (!driver.waitForDelay(0.12, token)) return false;
                return driver.inputText("", true, token);
            default:
                throw new UnsupportedOperationException("No device-action adapter for " + actionName);
        }
    }

    public Object inspectRegion(Map<String, Object> args, CancellationToken token) {
        ScreenData screen = capture(true, token);
        int left = normalized(number(args.get("x_min"), -1), screen.width);
        int top = normalized(number(args.get("y_min"), -1), screen.height);
        int right = normalized(number(args.get("x_max"), -1), screen.width);
        int bottom = normalized(number(args.get("y_max"), -1), screen.height);
        double zoom = decimal(args.get("zoom_factor"), 1.0);
        if (left < 0 || top < 0 || right <= left || bottom <= top) {
            throw new IllegalArgumentException("inspect_region bounds must be ordered normalized coordinates");
        }
        if (zoom < 1.0 || zoom > 4.0) throw new IllegalArgumentException("zoom_factor must be 1.0-4.0");
        Bitmap source = BitmapFactory.decodeByteArray(screen.screenshotBytes, 0, screen.screenshotBytes.length);
        if (source == null) throw new IllegalStateException("Could not decode the captured JPEG");
        try {
            int x = Math.max(0, Math.min(source.getWidth() - 1, left));
            int y = Math.max(0, Math.min(source.getHeight() - 1, top));
            int w = Math.max(1, Math.min(source.getWidth() - x, right - left));
            int h = Math.max(1, Math.min(source.getHeight() - y, bottom - top));
            Bitmap crop = Bitmap.createBitmap(source, x, y, w, h);
            Bitmap output = crop;
            if (zoom > 1.0) {
                output = Bitmap.createScaledBitmap(crop, Math.max(1, (int) (w * zoom)),
                        Math.max(1, (int) (h * zoom)), true);
                if (output != crop) crop.recycle();
            }
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (!output.compress(Bitmap.CompressFormat.JPEG, 90, bytes)) {
                    throw new IllegalStateException("Could not encode cropped region");
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("mime_type", "image/jpeg");
                result.put("base64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
                result.put("width", output.getWidth());
                result.put("height", output.getHeight());
                return result;
            } finally {
                output.recycle();
            }
        } finally {
            source.recycle();
        }
    }

    public String formatCandidates(Map<String, Object> args) {
        Object raw = args.get("candidates");
        if (!(raw instanceof List)) throw new IllegalArgumentException("candidates must be a list");
        List<?> candidates = (List<?>) raw;
        if (candidates.isEmpty()) {
            String fallback = string(args.get("fallback_message"));
            if (fallback.trim().isEmpty()) throw new IllegalArgumentException("Empty candidates require a fallback_message");
            return fallback;
        }
        if (candidates.size() > 10) throw new IllegalArgumentException("At most 10 ranked candidates are accepted");
        List<String> output = new ArrayList<>();
        for (Object candidate : candidates) {
            if (!(candidate instanceof Map)) throw new IllegalArgumentException("Each candidate must be an object");
            Map<?, ?> map = (Map<?, ?>) candidate;
            String label = string(map.get("label"));
            if (label.trim().isEmpty()) throw new IllegalArgumentException("Candidate label is required");
            Object coords = map.get("coords");
            if (!(coords instanceof List) || ((List<?>) coords).size() != 2) {
                throw new IllegalArgumentException("Candidate coords must be exactly [x,y]");
            }
            int x = number(((List<?>) coords).get(0), -1);
            int y = number(((List<?>) coords).get(1), -1);
            if (x < 0 || x > 1000 || y < 0 || y > 1000) {
                throw new IllegalArgumentException("Candidate coordinates must be in normalized range 0-1000");
            }
            output.add(label + " " + string(map.get("description")) + " @ [" + x + "," + y + "]");
        }
        return join(output, "\n");
    }

    public String launchAppByLabel(String appName, CancellationToken token) {
        boolean launched = driver.launchAppByLabel(appName, token);
        if (!launched) throw new IllegalArgumentException("No launchable installed app matched '" + appName + "'");
        return "Launched " + appName;
    }

    private boolean click(Map<String, Object> args, ScreenData observation,
                          List<Map<String, Object>> indexed, CancellationToken token) {
        Object target = args.get("target");
        int times = number(args.get("times"), 1);
        int delay = number(args.get("delay_ms"), 100);
        if (target instanceof Number) {
            int[] point = elementCenter(indexed, ((Number) target).intValue());
            return driver.tap(point[0], point[1], 100, times, delay, token);
        }
        int[] normalized = normalizedPoint(target);
        return driver.tapNormalized(normalized[0], normalized[1], false, 1000, times, delay, token);
    }

    private boolean clickSequence(Map<String, Object> args, CancellationToken token) {
        Object raw = args.get("sequence");
        Object descriptions = args.get("target_descriptions");
        if (!(raw instanceof List) || !(descriptions instanceof List)) {
            throw new IllegalArgumentException("click_sequence requires sequence and target_descriptions lists");
        }
        List<?> points = (List<?>) raw;
        List<?> labels = (List<?>) descriptions;
        if (points.size() != labels.size()) throw new IllegalArgumentException("target_descriptions must have one entry per tap");
        int delay = number(args.get("delay_ms"), 50);
        for (int i = 0; i < points.size(); i++) {
            token.throwIfCancelled();
            if (labels.get(i) == null || String.valueOf(labels.get(i)).trim().isEmpty()) {
                throw new IllegalArgumentException("Each coordinate tap requires a non-empty target description");
            }
            int[] normalized = normalizedPoint(points.get(i));
            if (!driver.tapNormalized(normalized[0], normalized[1], false, 1000, 1, delay, token)) return false;
            if (i + 1 < points.size() && delay > 0 && !driver.waitForDelay(delay / 1000.0, token)) return false;
        }
        return true;
    }

    private boolean longPress(Map<String, Object> args, ScreenData observation,
                              List<Map<String, Object>> indexed, CancellationToken token) {
        int duration = number(args.get("duration"), 1000);
        Object target = args.get("target");
        if (target instanceof Number) {
            int[] point = elementCenter(indexed, ((Number) target).intValue());
            return driver.longPress(point[0], point[1], duration, token);
        }
        int[] normalized = normalizedPoint(target);
        return driver.tapNormalized(normalized[0], normalized[1], true, duration, 1, 100, token);
    }

    private boolean inputText(Map<String, Object> args, ScreenData observation,
                              List<Map<String, Object>> indexed, CancellationToken token) {
        Object target = args.get("target");
        int[] point;
        if (target instanceof Number) point = elementCenter(indexed, ((Number) target).intValue());
        else point = normalizedPoint(target);
        boolean tapped = target instanceof Number
                ? driver.tap(point[0], point[1], 100, 1, 100, token)
                : driver.tapNormalized(point[0], point[1], false, 1000, 1, 100, token);
        if (!tapped || !driver.waitForDelay(0.12, token)) return false;
        boolean clear = booleanValue(args.get("clear_exist"), true);
        return driver.inputText(string(args.get("text")), clear, token);
    }

    private boolean swipe(Map<String, Object> args, ScreenData observation,
                          List<Map<String, Object>> indexed, CancellationToken token) {
        int duration = number(args.get("duration"), 800);
        Object start = args.get("start");
        Object end = args.get("end");
        if (start != null || end != null) {
            int[] a = normalizedPoint(start);
            int[] b = normalizedPoint(end);
            return driver.swipeNormalized(a, b, duration, token);
        }
        Object gesture = args.get("gesture");
        Object direction = args.get("direction");
        if (gesture instanceof String) direction = gesture;
        if (gesture instanceof List) {
            List<?> coordinates = (List<?>) gesture;
            if (coordinates.size() != 4) throw new IllegalArgumentException("Coordinate gesture must have [x1,y1,x2,y2]");
            return driver.swipeNormalized(new int[]{number(coordinates.get(0), -1), number(coordinates.get(1), -1)},
                    new int[]{number(coordinates.get(2), -1), number(coordinates.get(3), -1)}, duration, token);
        }
        if (direction == null) throw new IllegalArgumentException("swipe requires direction or start/end coordinates");
        SwipeDirection dir = SwipeDirection.parse(string(direction));
        Object target = args.get("target");
        if (target == null) return driver.swipeDirection(dir, duration, token);
        int[] bounds = targetBounds(observation, indexed, target);
        int cx = (bounds[0] + bounds[2]) / 2;
        int cy = (bounds[1] + bounds[3]) / 2;
        int dx = Math.max(1, (bounds[2] - bounds[0]) * 4 / 10);
        int dy = Math.max(1, (bounds[3] - bounds[1]) * 4 / 10);
        switch (dir) {
            case UP: return driver.swipe(cx, cy + dy, cx, cy - dy, duration, token);
            case DOWN: return driver.swipe(cx, cy - dy, cx, cy + dy, duration, token);
            case LEFT: return driver.swipe(cx + dx, cy, cx - dx, cy, duration, token);
            case RIGHT: return driver.swipe(cx - dx, cy, cx + dx, cy, duration, token);
            default: throw new IllegalArgumentException("Unsupported direction " + dir);
        }
    }

    private boolean waitForText(Map<String, Object> args, CancellationToken token) {
        String query = string(args.get("text"));
        String mode = stringOr(args.get("wait_state"), "appear");
        long deadline = System.currentTimeMillis() + Math.max(0, number(args.get("timeout_ms"), 5000));
        while (true) {
            token.throwIfCancelled();
            ScreenData screen = capture(true, token);
            boolean found = containsText(screen, query);
            if (("disappear".equalsIgnoreCase(mode) && !found)
                    || (!"disappear".equalsIgnoreCase(mode) && found)) return true;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return false;
            if (!driver.waitForDelay(Math.min(remaining, 300L) / 1000.0, token)) return false;
        }
    }

    private static boolean containsText(ScreenData data, String query) {
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        for (Map<String, Object> element : data.uiElements) {
            String text = string(element.get("text")) + " " + string(element.get("content-desc"));
            if (text.toLowerCase(java.util.Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }

    private static int[] elementCenter(List<Map<String, Object>> indexed, int index) {
        int resolved = index - 1;
        if (resolved < 0 || resolved >= indexed.size()) {
            throw new IllegalArgumentException("Operator element index must be 1-" + indexed.size() + ", got " + index);
        }
        Map<String, Object> element = indexed.get(resolved);
        int[] bounds = (int[]) element.get("bounds");
        return new int[]{(bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2};
    }

    private int[] targetBounds(ScreenData observation, List<Map<String, Object>> indexed, Object target) {
        if (observation == null) throw new IllegalStateException("No current observed screen for swipe target");
        if (target instanceof Number) {
            int index = ((Number) target).intValue();
            int resolved = index - 1;
            if (resolved < 0 || resolved >= indexed.size()) throw new IllegalArgumentException("Swipe target index out of range");
            return (int[]) indexed.get(resolved).get("bounds");
        }
        if (target instanceof List && ((List<?>) target).size() == 4) {
            List<?> raw = (List<?>) target;
            int[] bounds = new int[4];
            for (int i = 0; i < 4; i++) bounds[i] = number(raw.get(i), -1);
            boolean normalizedBounds = true;
            for (int value : bounds) normalizedBounds &= value >= 0 && value <= 1000;
            if (normalizedBounds) {
                bounds = new int[]{(int) (bounds[0] * observation.width / 1000.0),
                        (int) (bounds[1] * observation.height / 1000.0),
                        (int) (bounds[2] * observation.width / 1000.0),
                        (int) (bounds[3] * observation.height / 1000.0)};
            }
            return validateBounds(bounds, observation.width, observation.height);
        }
        if (target instanceof String) {
            ElementMatch match = driver.findElement(string(target), null, 0, observation, null);
            if (!match.isFound()) throw new IllegalArgumentException("No element matches swipe target '" + target + "'");
            for (Map<String, Object> element : observation.uiElements) {
                if (element == match.element) return physicalBounds(element, observation.width, observation.height);
            }
        }
        throw new IllegalArgumentException("Swipe target must be an element index or [left,top,right,bottom] bounds");
    }

    private static int[] physicalBounds(Map<String, Object> element, int width, int height) {
        Object parsed = element.get("parsed_bounds");
        if (parsed instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) parsed;
            int[] bounds = new int[]{number(map.get("left"), -1), number(map.get("top"), -1),
                    number(map.get("right"), -1), number(map.get("bottom"), -1)};
            return validateBounds(bounds, width, height);
        }
        Object raw = element.get("bounds");
        if (raw instanceof List && ((List<?>) raw).size() == 4) {
            List<?> list = (List<?>) raw;
            return validateBounds(new int[]{number(list.get(0), -1), number(list.get(1), -1),
                    number(list.get(2), -1), number(list.get(3), -1)}, width, height);
        }
        throw new IllegalArgumentException("Indexed target does not contain bounds");
    }

    private static int[] rawPhysicalBounds(Map<String, Object> element) {
        Object parsed = element.get("parsed_bounds");
        if (parsed instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) parsed;
            return new int[]{number(map.get("left"), -1), number(map.get("top"), -1),
                    number(map.get("right"), -1), number(map.get("bottom"), -1)};
        }
        Object value = element.get("bounds");
        if (value instanceof List && ((List<?>) value).size() == 4) {
            List<?> bounds = (List<?>) value;
            return new int[]{number(bounds.get(0), -1), number(bounds.get(1), -1),
                    number(bounds.get(2), -1), number(bounds.get(3), -1)};
        }
        if (value instanceof String) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]\\s*\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]")
                    .matcher((String) value);
            if (matcher.matches()) return new int[]{Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4))};
        }
        return null;
    }

    private static int[] validateBounds(int[] bounds, int width, int height) {
        if (bounds[0] < 0 || bounds[1] < 0 || bounds[2] <= bounds[0] || bounds[3] <= bounds[1]) {
            throw new IllegalArgumentException("Target bounds are empty or invalid");
        }
        return new int[]{Math.min(width, bounds[0]), Math.min(height, bounds[1]),
                Math.min(width, bounds[2]), Math.min(height, bounds[3])};
    }

    private static int[] normalizedPoint(Object target) {
        if (!(target instanceof List) || ((List<?>) target).size() != 2) {
            throw new IllegalArgumentException("Coordinate target must be normalized [x,y]");
        }
        int x = number(((List<?>) target).get(0), -1);
        int y = number(((List<?>) target).get(1), -1);
        if (x < 0 || x > 1000 || y < 0 || y > 1000) {
            throw new IllegalArgumentException("Normalized target coordinates must be 0-1000");
        }
        return new int[]{x, y};
    }

    private static int normalized(int value, int dimension) {
        if (value < 0 || value > 1000) throw new IllegalArgumentException("Region coordinates must be 0-1000");
        return (int) (value * dimension / 1000.0);
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static double decimal(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String stringOr(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder text = new StringBuilder();
        for (String value : values) {
            if (text.length() > 0) text.append(separator);
            text.append(value);
        }
        return text.toString();
    }
}
