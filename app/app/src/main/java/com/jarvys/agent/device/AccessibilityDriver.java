package com.jarvys.agent.device;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;

import com.artemis.helper.ArtemisAccessibilityService;
import com.artemis.helper.DisplayUtils;
import com.artemis.helper.GestureController;
import com.artemis.helper.HierarchyDumper;
import com.jarvys.agent.CancellationToken;

/** Device driver that invokes the enabled AccessibilityService directly in this app process. */
public final class AccessibilityDriver extends BaseDeviceDriver {
    private ArtemisAccessibilityService accessibility;
    private PerceptionEngine perception;

    @Override
    public String deviceId() {
        ArtemisAccessibilityService service = requireConnected();
        String androidId = Settings.Secure.getString(service.getContentResolver(), Settings.Secure.ANDROID_ID);
        return "android:" + (androidId == null ? service.getPackageName() : androidId);
    }

    @Override
    public ScreenSize screenSize() {
        ArtemisAccessibilityService service = requireConnected();
        DisplayUtils.DisplayInfo display = DisplayUtils.getDisplayInfo(service);
        return new ScreenSize(display.width, display.height);
    }

    @Override
    public void connect() {
        accessibility = ArtemisAccessibilityService.getInstance();
        if (accessibility == null) {
            throw new IllegalStateException("Enable Jarvys in Settings > Accessibility before running device actions");
        }
        perception = new PerceptionEngine(accessibility);
    }

    @Override
    public void disconnect() {
        // In-process driver owns no socket, subprocess, or external device session.
        perception = null;
        accessibility = null;
    }

    @Override
    public ScreenData getScreenData(boolean skipSettling, CancellationToken token) {
        token.throwIfCancelled();
        return requirePerception().getScreenData(skipSettling, token);
    }

    public String getHierarchyXml() {
        JSONObject result = HierarchyDumper.dump(requireConnected(), HierarchyDumper.DumpOptions.forSnapshot());
        if (!result.optBoolean("success", false)) {
            throw new IllegalStateException("UI hierarchy capture failed: " + result.optString("error", "unknown error"));
        }
        return result.optString("xml", "");
    }

    @Override
    public boolean tap(int x, int y, int durationMs, int times, int delayMs, CancellationToken token) {
        requirePositive(durationMs, "tap duration");
        requireGestureDuration(durationMs, "tap duration");
        if (times < 1) throw new IllegalArgumentException("tap times must be at least 1");
        if (delayMs < 0) throw new IllegalArgumentException("tap delay cannot be negative");
        GestureController.ActionGate gate = action -> token.runIfActive(action);
        for (int i = 0; i < times; i++) {
            token.throwIfCancelled();
            boolean success = GestureController.tap(requireConnected(), x, y, durationMs,
                    durationMs + 5000L, gate);
            if (!success) return false;
            if (i + 1 < times && !waitForDelay(delayMs / 1000.0, token)) return false;
        }
        return true;
    }

    @Override
    public boolean longPress(int x, int y, int durationMs, CancellationToken token) {
        requirePositive(durationMs, "long press duration");
        requireGestureDuration(durationMs, "long press duration");
        token.throwIfCancelled();
        return GestureController.longPress(requireConnected(), x, y, durationMs,
                durationMs + 5000L, action -> token.runIfActive(action));
    }

    @Override
    public boolean swipe(int startX, int startY, int endX, int endY, int durationMs,
                         CancellationToken token) {
        requirePositive(durationMs, "swipe duration");
        requireGestureDuration(durationMs, "swipe duration");
        token.throwIfCancelled();
        return GestureController.swipe(requireConnected(), startX, startY, endX, endY,
                durationMs, durationMs + 5000L, action -> token.runIfActive(action));
    }

    @Override
    public boolean swipeDirection(SwipeDirection direction, int durationMs, CancellationToken token) {
        if (direction == null) throw new IllegalArgumentException("Swipe direction is required");
        ScreenSize screen = screenSize();
        int cx = screen.width / 2;
        int cy = screen.height / 2;
        int dx = Math.max(1, screen.width * 4 / 10);
        int dy = Math.max(1, screen.height * 4 / 10);
        switch (direction) {
            case UP: return swipe(cx, cy + dy, cx, cy - dy, durationMs, token);
            case DOWN: return swipe(cx, cy - dy, cx, cy + dy, durationMs, token);
            case LEFT: return swipe(cx + dx, cy, cx - dx, cy, durationMs, token);
            case RIGHT: return swipe(cx - dx, cy, cx + dx, cy, durationMs, token);
            default: throw new IllegalArgumentException("Unsupported swipe direction: " + direction);
        }
    }

    @Override
    public boolean inputText(String text, boolean clearExisting, CancellationToken token) {
        token.throwIfCancelled();
        AccessibilityNodeInfo input = HierarchyDumper.findInputNode(requireConnected());
        if (input == null) return false;
        try {
            String value = text == null ? "" : text;
            if (!clearExisting) {
                CharSequence current = input.getText();
                boolean hint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && input.isShowingHintText();
                if (current != null && current.length() > 0 && !hint) value = current + value;
            }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            final boolean[] result = {false};
            boolean invoked = token.runIfActive(() -> result[0] = input.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT, args));
            return invoked && result[0];
        } finally {
            HierarchyDumper.safeRecycle(input);
        }
    }

    @Override
    public boolean pressKey(Object key, CancellationToken token) {
        String value = normalizeKey(key);
        token.throwIfCancelled();
        switch (value) {
            case "home": return global(token, AccessibilityService.GLOBAL_ACTION_HOME);
            case "back": return global(token, AccessibilityService.GLOBAL_ACTION_BACK);
            case "app_switch": return global(token, AccessibilityService.GLOBAL_ACTION_RECENTS);
            case "enter": return pressEnter(token);
            case "delete": return deleteCharacter(token);
            case "power":
            case "volume_up":
            case "volume_down":
                throw unsupported("press_key(" + value + ")", "Android's public AccessibilityService API cannot inject that physical key");
            default:
                throw unsupported("press_key(" + value + ")", "no public in-process AccessibilityService equivalent");
        }
    }

    @Override
    public boolean launchApp(String packageName, CancellationToken token) {
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("packageName is required");
        }
        token.throwIfCancelled();
        PackageManager packages = requireConnected().getPackageManager();
        Intent launch = packages.getLaunchIntentForPackage(packageName);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final RuntimeException[] failure = {null};
        boolean dispatched = token.runIfActive(() -> {
            try {
                requireConnected().startActivity(launch);
            } catch (RuntimeException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw failure[0];
        return dispatched;
    }

    public boolean launchAppByLabel(String appName, CancellationToken token) {
        if (appName == null || appName.trim().isEmpty()) {
            throw new IllegalArgumentException("app_name is required");
        }
        ArtemisAccessibilityService service = requireConnected();
        String query = appName.trim();
        if (service.getPackageName().equals(query)) return launchApp(query, token);
        PackageManager manager = service.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<ResolveInfo> activities = manager.queryIntentActivities(launcher, 0);
        for (ResolveInfo info : activities) {
            String packageName = info.activityInfo == null ? "" : info.activityInfo.packageName;
            CharSequence label = info.loadLabel(manager);
            if (packageName.equals(query) || (label != null && label.toString().equalsIgnoreCase(query))) {
                return launchApp(packageName, token);
            }
        }
        return false;
    }

    public boolean openLink(String url, CancellationToken token) {
        if (url == null || url.trim().isEmpty()) throw new IllegalArgumentException("url is required");
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final boolean[] launched = {false};
        boolean dispatched = token.runIfActive(() -> {
            try {
                requireConnected().startActivity(view);
                launched[0] = true;
            } catch (android.content.ActivityNotFoundException ignored) {
                launched[0] = false;
            }
        });
        return dispatched && launched[0];
    }

    @Override
    public boolean stopApp(String packageName, CancellationToken token) {
        token.throwIfCancelled();
        throw unsupported("stop_app(" + packageName + ")",
                "force-stopping another package requires shell, root, or privileged device-owner access");
    }

    @Override
    public String getCurrentPackage() {
        String current = requireConnected().getCurrentPackageName();
        return current == null || current.isEmpty() ? null : current;
    }

    @Override
    public String executeShell(String command, double timeoutSeconds) {
        throw unsupported("execute_shell", "ordinary Android apps have no public API for arbitrary shell commands");
    }

    @Override
    public void startVideoRecording(String outputDirectory, CancellationToken token) {
        token.throwIfCancelled();
        throw unsupported("start_video_recording", "video recording is outside the approved Stage B capture scope");
    }

    @Override
    public String stopVideoRecording(CancellationToken token) {
        token.throwIfCancelled();
        throw unsupported("stop_video_recording", "video recording is outside the approved Stage B capture scope");
    }

    @Override
    public boolean waitForDelay(double seconds, CancellationToken token) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0) {
            throw new IllegalArgumentException("wait duration must be a finite non-negative number");
        }
        long deadline = System.nanoTime() + (long) (seconds * 1_000_000_000L);
        try {
            while (true) {
                if (token.isCancelled()) return false;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return true;
                long sleepMs = Math.min(remaining / 1_000_000L, 50L);
                int sleepNs = (int) Math.min(999_999L, remaining - sleepMs * 1_000_000L);
                Thread.sleep(sleepMs, sleepNs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean pressEnter(CancellationToken token) {
        if (Build.VERSION.SDK_INT < 30) {
            throw unsupported("press_key(enter)", "ACTION_IME_ENTER requires Android 11/API 30+");
        }
        AccessibilityNodeInfo input = HierarchyDumper.findInputNode(requireConnected());
        if (input == null) return false;
        try {
            final boolean[] result = {false};
            boolean invoked = token.runIfActive(() -> result[0] = input.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()));
            return invoked && result[0];
        } finally {
            HierarchyDumper.safeRecycle(input);
        }
    }

    private boolean deleteCharacter(CancellationToken token) {
        AccessibilityNodeInfo input = HierarchyDumper.findInputNode(requireConnected());
        if (input == null) return false;
        try {
            CharSequence existing = input.getText();
            if (existing == null || existing.length() == 0) return true;
            String current = existing.toString();
            int end = current.offsetByCodePoints(current.length(), -1);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    current.substring(0, end));
            final boolean[] result = {false};
            boolean invoked = token.runIfActive(() -> result[0] = input.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT, args));
            return invoked && result[0];
        } finally {
            HierarchyDumper.safeRecycle(input);
        }
    }

    private boolean global(CancellationToken token, int action) {
        final boolean[] result = {false};
        boolean invoked = token.runIfActive(() -> result[0] = requireConnected().performGlobalAction(action));
        return invoked && result[0];
    }

    private static String normalizeKey(Object key) {
        if (key instanceof KeyCode) return ((KeyCode) key).name().toLowerCase(java.util.Locale.ROOT);
        if (key instanceof Number) {
            int code = ((Number) key).intValue();
            switch (code) {
                case KeyEvent.KEYCODE_HOME: return "home";
                case KeyEvent.KEYCODE_BACK: return "back";
                case KeyEvent.KEYCODE_ENTER: return "enter";
                case KeyEvent.KEYCODE_DEL: return "delete";
                case KeyEvent.KEYCODE_APP_SWITCH: return "app_switch";
                case KeyEvent.KEYCODE_POWER: return "power";
                case KeyEvent.KEYCODE_VOLUME_UP: return "volume_up";
                case KeyEvent.KEYCODE_VOLUME_DOWN: return "volume_down";
                default: throw new IllegalArgumentException("Unsupported key code: " + code);
            }
        }
        if (key instanceof String) return ((String) key).trim().toLowerCase(java.util.Locale.ROOT);
        throw new IllegalArgumentException("Key must be KeyCode, a recognized key name, or an Android keycode");
    }

    private ArtemisAccessibilityService requireConnected() {
        if (accessibility == null) connect();
        ArtemisAccessibilityService current = ArtemisAccessibilityService.getInstance();
        if (current == null) throw new IllegalStateException("Jarvys AccessibilityService is disconnected");
        accessibility = current;
        return current;
    }

    private PerceptionEngine requirePerception() {
        requireConnected();
        if (perception == null) perception = new PerceptionEngine(accessibility);
        return perception;
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void requireGestureDuration(int value, String name) {
        if (value > 60000) {
            throw new IllegalArgumentException(name + " exceeds Android's 60000 ms gesture limit");
        }
    }

    private static UnsupportedOperationException unsupported(String operation, String reason) {
        return new UnsupportedOperationException(operation + " is unsupported: " + reason);
    }
}
