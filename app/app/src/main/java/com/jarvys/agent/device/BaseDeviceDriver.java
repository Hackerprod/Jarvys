package com.jarvys.agent.device;

import com.jarvys.agent.CancellationToken;

import java.util.List;
import java.util.Map;

/** Complete Android-independent action contract ported from Artemis BaseDeviceDriver. */
public abstract class BaseDeviceDriver implements AutoCloseable {
    public abstract String deviceId();
    public abstract ScreenSize screenSize();
    public abstract void connect();
    public abstract void disconnect();
    public abstract ScreenData getScreenData(boolean skipSettling, CancellationToken token);
    public abstract boolean tap(int x, int y, int durationMs, int times, int delayMs,
                                CancellationToken token);
    public abstract boolean longPress(int x, int y, int durationMs, CancellationToken token);
    public abstract boolean swipe(int startX, int startY, int endX, int endY, int durationMs,
                                  CancellationToken token);
    public abstract boolean swipeDirection(SwipeDirection direction, int durationMs,
                                           CancellationToken token);
    public abstract boolean inputText(String text, boolean clearExisting, CancellationToken token);
    public abstract boolean pressKey(Object key, CancellationToken token);
    public abstract boolean launchApp(String packageName, CancellationToken token);
    public abstract boolean stopApp(String packageName, CancellationToken token);
    public abstract String getCurrentPackage();
    public abstract String executeShell(String command, double timeoutSeconds);
    public abstract void startVideoRecording(String outputDirectory, CancellationToken token);
    public abstract String stopVideoRecording(CancellationToken token);
    public abstract boolean waitForDelay(double seconds, CancellationToken token);

    public ScreenData getScreenData(CancellationToken token) {
        return getScreenData(false, token);
    }

    public boolean tap(int x, int y, CancellationToken token) {
        return tap(x, y, 100, 1, 100, token);
    }

    public boolean longPress(int x, int y, CancellationToken token) {
        return longPress(x, y, 1000, token);
    }

    public boolean swipe(int startX, int startY, int endX, int endY, CancellationToken token) {
        return swipe(startX, startY, endX, endY, 800, token);
    }

    public boolean swipeDirection(SwipeDirection direction, CancellationToken token) {
        return swipeDirection(direction, 800, token);
    }

    public boolean inputText(String text, CancellationToken token) {
        return inputText(text, true, token);
    }

    public String executeShell(String command) {
        return executeShell(command, 15.0);
    }

    public void startVideoRecording(CancellationToken token) {
        startVideoRecording(null, token);
    }

    public boolean waitForDelay(CancellationToken token) {
        return waitForDelay(1.0, token);
    }

    public boolean tapNormalized(int normX, int normY, boolean longPress, int durationMs,
                                 int times, int delayMs, CancellationToken token) {
        ScreenSize size = screenSize();
        int x = toAbsolute(normX, size.width);
        int y = toAbsolute(normY, size.height);
        return longPress
                ? longPress(x, y, durationMs, token)
                : tap(x, y, 100, times, delayMs, token);
    }

    public boolean swipeNormalized(int[] startNorm, int[] endNorm, int durationMs,
                                  CancellationToken token) {
        if (startNorm == null || endNorm == null || startNorm.length != 2 || endNorm.length != 2) {
            throw new IllegalArgumentException("Normalized swipe points must each contain [x, y]");
        }
        ScreenSize size = screenSize();
        return swipe(toAbsolute(startNorm[0], size.width), toAbsolute(startNorm[1], size.height),
                toAbsolute(endNorm[0], size.width), toAbsolute(endNorm[1], size.height),
                durationMs, token);
    }

    public boolean swipeNormalized(int[] startNorm, int[] endNorm, CancellationToken token) {
        return swipeNormalized(startNorm, endNorm, 800, token);
    }

    public boolean tapNormalized(int normX, int normY, CancellationToken token) {
        return tapNormalized(normX, normY, false, 1000, 1, 100, token);
    }

    public ElementMatch findElement(String resourceId, String text, int index,
                                    ScreenData screenData, CancellationToken token) {
        ScreenData data = screenData != null ? screenData : getScreenData(false, token);
        List<Map<String, Object>> elements = data.uiElements;
        java.util.ArrayList<Map<String, Object>> matched = new java.util.ArrayList<>();
        for (Map<String, Object> element : elements) {
            String id = stringValue(element.get("resource_id"));
            if (id.isEmpty()) id = stringValue(element.get("resource-id"));
            String value = stringValue(element.get("text"));
            if (value.isEmpty()) value = stringValue(element.get("content-desc"));
            if ((resourceId != null && !resourceId.isEmpty() && id.contains(resourceId))
                    || (text != null && !text.isEmpty()
                    && value.toLowerCase(java.util.Locale.ROOT)
                    .contains(text.toLowerCase(java.util.Locale.ROOT)))) {
                matched.add(element);
            }
        }
        if (matched.isEmpty()) {
            return new ElementMatch(null, null,
                    "Element not found (resource_id=" + resourceId + ", text=" + text + ")");
        }
        int resolved = index < 0 ? matched.size() + index : index;
        if (resolved < 0 || resolved >= matched.size()) {
            return new ElementMatch(null, null,
                    "Element index " + index + " out of range (matched " + matched.size() + ")");
        }
        Map<String, Object> element = matched.get(resolved);
        int[] bounds = parseBounds(element);
        int[] center;
        if (bounds != null) {
            center = new int[]{(bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2};
        } else {
            center = intPair(element.get("center"));
        }
        return center == null
                ? new ElementMatch(element, null, "Element has no usable bounds or center")
                : new ElementMatch(element, center, null);
    }

    public ElementMatch findElement(String resourceId, String text, int index,
                                    CancellationToken token) {
        return findElement(resourceId, text, index, null, token);
    }

    public ElementMatch findElement(String resourceId, String text, CancellationToken token) {
        return findElement(resourceId, text, 0, null, token);
    }

    public boolean tapElement(String resourceId, String text, int index, boolean longPress,
                              int durationMs, CancellationToken token) {
        ElementMatch match = findElement(resourceId, text, index, null, token);
        if (!match.isFound()) return false;
        token.throwIfCancelled();
        return longPress
                ? longPress(match.center[0], match.center[1], durationMs, token)
                : tap(match.center[0], match.center[1], 100, 1, 100, token);
    }

    public boolean tapElement(String resourceId, String text, CancellationToken token) {
        return tapElement(resourceId, text, 0, false, 1000, token);
    }

    @Override
    public void close() {
        disconnect();
    }

    protected static int toAbsolute(int normalized, int dimension) {
        double pixels = normalized * dimension / 1000.0;
        return Math.max(0, Math.min(dimension - 1, (int) pixels));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static int[] parseBounds(Map<String, Object> element) {
        Object parsed = element.get("parsed_bounds");
        if (parsed instanceof Map) {
            Map<String, Object> box = (Map<String, Object>) parsed;
            Object l = box.get("left"), t = box.get("top"), r = box.get("right"), b = box.get("bottom");
            if (l instanceof Number && t instanceof Number && r instanceof Number && b instanceof Number) {
                return new int[]{((Number) l).intValue(), ((Number) t).intValue(),
                        ((Number) r).intValue(), ((Number) b).intValue()};
            }
        }
        Object bounds = element.get("bounds");
        int[] pair = intQuad(bounds);
        if (pair != null) return pair;
        if (bounds instanceof String) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]\\s*\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]")
                    .matcher((String) bounds);
            if (matcher.matches()) {
                return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4))};
            }
        }
        return null;
    }

    private static int[] intQuad(Object value) {
        if (value instanceof int[] && ((int[]) value).length == 4) return (int[]) value;
        if (value instanceof List && ((List<?>) value).size() == 4) {
            List<?> items = (List<?>) value;
            int[] result = new int[4];
            for (int i = 0; i < 4; i++) {
                if (!(items.get(i) instanceof Number)) return null;
                result[i] = ((Number) items.get(i)).intValue();
            }
            return result;
        }
        return null;
    }

    private static int[] intPair(Object value) {
        if (value instanceof int[] && ((int[]) value).length == 2) return (int[]) value;
        if (value instanceof List && ((List<?>) value).size() == 2) {
            List<?> items = (List<?>) value;
            if (items.get(0) instanceof Number && items.get(1) instanceof Number) {
                return new int[]{((Number) items.get(0)).intValue(), ((Number) items.get(1)).intValue()};
            }
        }
        return null;
    }
}
