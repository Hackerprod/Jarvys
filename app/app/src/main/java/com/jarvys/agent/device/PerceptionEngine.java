package com.jarvys.agent.device;

import android.util.Base64;

import com.artemis.helper.ArtemisAccessibilityService;
import com.artemis.helper.HierarchyDumper;
import com.jarvys.agent.CancellationToken;
import com.jarvys.agent.MemoryUiAutomationGuard;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds screen observations using only AccessibilityService.takeScreenshot() and its UI hierarchy. */
public final class PerceptionEngine {
    private final ArtemisAccessibilityService accessibility;

    public PerceptionEngine(ArtemisAccessibilityService accessibility) {
        if (accessibility == null) throw new IllegalArgumentException("AccessibilityService is required");
        this.accessibility = accessibility;
    }

    public ScreenData getScreenData(boolean skipSettling, CancellationToken token) {
        final long epoch = MemoryUiAutomationGuard.captureAutomationEpoch();
        token.throwIfCancelled();
        if (!skipSettling) cancellableDelay(400L, token);

        token.throwIfCancelled();
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            throw new UnsupportedOperationException(
                    "Screenshot capture requires AccessibilityService.takeScreenshot() (Android 11/API 30+)");
        }
        JSONObject dump = HierarchyDumper.dumpAtomicSnapshot(accessibility,
                HierarchyDumper.DumpOptions.forDump());
        token.throwIfCancelled();
        if (!dump.optBoolean("success", false)) {
            throw new IllegalStateException("Accessibility hierarchy capture failed: "
                    + dump.optString("error", "unknown error"));
        }
        if (!dump.optBoolean("has_screenshot", false)) {
            String reason = dump.optString("screenshot_error", "AccessibilityService.takeScreenshot() returned no frame");
            throw new IllegalStateException("Screenshot capture failed: " + reason
                    + ". No alternate capture source is configured.");
        }
        String base64 = dump.optString("screenshot_base64", "");
        if (base64.isEmpty()) {
            throw new IllegalStateException("Screenshot capture failed: the service returned an empty image payload");
        }
        byte[] jpeg = Base64.decode(base64, Base64.DEFAULT);
        String xml = dump.optString("xml", "");
        JSONArray array = dump.optJSONArray("elements");
        List<Map<String, Object>> elements = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject element = array.optJSONObject(i);
                if (element != null) elements.add(toMap(element));
            }
        }
        int width = dump.optInt("width", 0);
        int height = dump.optInt("height", 0);
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("Screenshot capture returned invalid dimensions: "
                    + width + "x" + height);
        }
        MemoryUiAutomationGuard.requireAutomationEpoch(epoch);
        return new ScreenData(jpeg, base64, xml, elements, width, height,
                System.currentTimeMillis() / 1000.0, "android");
    }

    /** Snapshot is an alias for the same takeScreenshot-backed screen contract. */
    public ScreenData snapshot(CancellationToken token) {
        return getScreenData(true, token);
    }

    private static Map<String, Object> toMap(JSONObject object) {
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            if (value instanceof JSONObject) value = toMap((JSONObject) value);
            else if (value instanceof JSONArray) value = toList((JSONArray) value);
            result.put(key, value == JSONObject.NULL ? null : value);
        }
        return result;
    }

    private static List<Object> toList(JSONArray array) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof JSONObject) value = toMap((JSONObject) value);
            else if (value instanceof JSONArray) value = toList((JSONArray) value);
            values.add(value == JSONObject.NULL ? null : value);
        }
        return values;
    }

    private static void cancellableDelay(long milliseconds, CancellationToken token) {
        long end = System.currentTimeMillis() + milliseconds;
        while (true) {
            token.throwIfCancelled();
            long remaining = end - System.currentTimeMillis();
            if (remaining <= 0) return;
            try {
                Thread.sleep(Math.min(remaining, 50L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                token.throwIfCancelled();
                throw new IllegalStateException("Settling wait interrupted", e);
            }
        }
    }
}
