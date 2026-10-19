package com.jarvys.agent;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Best-effort, content-free diagnostics in private no-backup storage. There is no network or
 * logcat path. Caller strings, messages, suppressed exceptions, filenames and thread identity
 * are never inspected. A crash may be dropped on contention, exhaustion or damaged storage.
 *
 * This is a single-process bounded store, not a crash-delivery guarantee: disk sync, VM
 * exhaustion, reflective platform access and Throwable implementations can fail. Native
 * crashes, ANRs and OS process kills do not pass through the Java uncaught handler. Expired
 * records are hidden on reads and reclaimed on subsequent writes. Eligibility is seven days;
 * this is not a real-time deletion guarantee, and reads never modify storage.
 */
public final class LocalDiagnostics {
    public enum Origin { UNCAUGHT, PROVIDER, TOOL, FACTORY, SERVICE, ACTIVITY, RUNTIME }

    /** Legacy categories retained for API compatibility; old observations are ignored and never exposed. */
    public enum AnimationStage { INDEX, DOWNLOAD, INTEGRITY, CACHE, INIT, IMPORT, DECODE, ATTACH, SURFACE, FIRST_FRAME, TIMEOUT, CONTEXT_LOSS }
    public enum AnimationStatus { STARTED, READY, FAILED, UNAVAILABLE }
    public enum AnimationId { IDLE, SAD, THINKING, WORKING, WAITING, INTERRUPTED, TAUNT, CATALOG }

    static final int SLOT_COUNT = 32;
    static final int MAX_RECORD_BYTES = 16 * 1024;
    static final int MAX_CAUSES = 8;
    static final int MAX_FRAMES = 12;
    static final int MAX_RESULTS = 10;
    private static final long MAX_FUTURE_SKEW_MILLIS = 5 * 60_000L;
    static final long RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    static final String DIRECTORY_NAME = "local-diagnostics-v1";
    private static final String STAGING_NAME = "record.pending";
    private static final ReentrantLock STORAGE_LOCK = new ReentrantLock();
    private static final ThreadLocal<Boolean> CAPTURING = new ThreadLocal<>();
    private static volatile LocalDiagnostics instance;
    private static final Object INSTALL_LOCK = new Object();
    private static boolean handlerInstalled;
    private final File noBackup;
    private final File directory;
    private final String expectedBase;
    private final String expectedDirectory;
    private final LongSupplier clock;

    public static LocalDiagnostics get(Context context) {
        LocalDiagnostics current = instance;
        if (current == null) {
            synchronized (INSTALL_LOCK) {
                current = instance;
                if (current == null) {
                    Context app = context.getApplicationContext();
                    current = new LocalDiagnostics((app == null ? context : app).getNoBackupFilesDir(),
                            System::currentTimeMillis);
                    instance = current;
                }
            }
        }
        return current;
    }

    /** Installs at most one wrapper without replacing the platform's existing crash policy. */
    public static void install(Context context) {
        try {
            LocalDiagnostics store = get(context);
            synchronized (INSTALL_LOCK) {
                handlerInstalled = installOnce(handlerInstalled, Thread::getDefaultUncaughtExceptionHandler,
                        Thread::setDefaultUncaughtExceptionHandler, store::record, () -> {
                            Process.killProcess(Process.myPid());
                            System.exit(10);
                        });
            }
        } catch (Throwable ignored) {
            // Diagnostics must not prevent Application startup or alter the original handler.
        }
    }

    public static void recoverable(Origin origin, Throwable failure) {
        try {
            LocalDiagnostics current = instance;
            if (current != null) current.record(origin, failure, false);
        } catch (Throwable ignored) { }
    }

    public static void recoverable(Context context, Origin origin, Throwable failure) {
        try {
            if (context != null && !AgentErrorReporter.attachmentReportsSuppressed()) get(context).record(origin, failure, false);
        } catch (Throwable ignored) { }
    }

    /** Animation observations are intentionally no longer retained. */
    public static void animation(Context context, AnimationStage stage, AnimationStatus status, AnimationId id) { }

    boolean animationRecord(AnimationStage stage, AnimationStatus status, AnimationId id) {
        return false;
    }

    /** Stores only a validated structural projection for an empty Codex response. */
    static void responseShape(Context context, ResponseDiagnostics.CodexShape shape, int textLength) {
        try {
            if (context == null || shape == null || AgentErrorReporter.attachmentReportsSuppressed()) return;
            Context app = context.getApplicationContext();
            if (app != null) get(app).responseShapeRecord(shape, textLength);
        } catch (Throwable ignored) { }
    }

    /** Stores only the effective, content-free parameters of one prepared Codex Responses request. */
    static void modelRequest(Context context, JSONObject request, List<ToolSpec> declaredTools) {
        try {
            if (context == null || request == null) return;
            Context app = context.getApplicationContext();
            if (app != null) get(app).modelRequestRecord(request, declaredTools);
        } catch (Throwable ignored) { }
    }

    boolean modelRequestRecord(JSONObject request, List<ToolSpec> declaredTools) {
        if (request == null || Boolean.TRUE.equals(CAPTURING.get())) return false;
        try {
            JSONObject reasoning = request.optJSONObject("reasoning");
            JSONObject text = request.optJSONObject("text");
            JSONArray tools = request.optJSONArray("tools");
            int toolCount = tools == null ? 0 : tools.length();
            boolean webAvailable = declaredTools != null && declaredTools.stream()
                    .anyMatch(tool -> tool != null && "web".equals(tool.category));
            JSONObject value = new JSONObject()
                    .put("model", request.optString("model", ""))
                    .put("reasoning_effort", reasoning == null ? "" : reasoning.optString("effort", ""))
                    .put("reasoning_summary", reasoning == null ? "" : reasoning.optString("summary", ""))
                    .put("text_verbosity", text == null ? "" : text.optString("verbosity", ""))
                    .put("declared_tool_count", toolCount)
                    .put("web_available", webAvailable);
            if (validateModelRequest(value) == null) return false;
            CAPTURING.set(true);
            long now = clock.getAsLong();
            JSONObject row = header(Origin.PROVIDER, "recoverable", "model_request", now, null);
            row.put("model_request", value);
            return persist(row, now);
        } catch (Throwable ignored) { return false; }
        finally { CAPTURING.remove(); }
    }

    boolean responseShapeRecord(ResponseDiagnostics.CodexShape shape, int textLength) {
        if (shape == null || textLength < 0 || textLength > ResponseDiagnosticsParser.MAX_BODY_CHARACTERS
                || Boolean.TRUE.equals(CAPTURING.get())) return false;
        try {
            if (AgentErrorReporter.attachmentReportsSuppressed()) return false;
            JSONObject value = responseShapeJson(shape, textLength);
            if (value == null) return false;
            CAPTURING.set(true);
            long now = clock.getAsLong();
            JSONObject row = header(Origin.PROVIDER, "recoverable", "response_shape", now, null);
            row.put("response_shape", value);
            return persist(row, now);
        } catch (Throwable ignored) { return false; }
        finally { CAPTURING.remove(); }
    }

    private static JSONObject responseShapeJson(ResponseDiagnostics.CodexShape shape, int textLength)
            throws JSONException {
        if (shape.completedOutputCount < 0 || shape.completedOutputCount > ResponseDiagnosticsParser.MAX_ITEMS)
            return null;
        JSONObject events = new JSONObject();
        for (Map.Entry<String, Integer> entry : shape.sseEventCounts.entrySet()) {
            String type = fixedEventType(entry.getKey());
            Integer count = entry.getValue();
            if (type == null || count == null || count < 1 || count > ResponseDiagnosticsParser.MAX_ITEMS)
                return null;
            events.put(type, count);
        }
        JSONArray itemTypes = new JSONArray();
        Set<String> seenTypes = new HashSet<>();
        for (String itemType : shape.outputItemTypes) {
            String type = fixedOutputType(itemType);
            if (type == null || !seenTypes.add(type)) return null;
            itemTypes.put(type);
        }
        String status = fixed(shape.responseStatus, "completed", "incomplete", "failed", "cancelled",
                "in_progress", "queued", "other");
        String reason = fixed(shape.incompleteReason, "max_output_tokens", "content_filter",
                "tool_call_error", "safety_violation", "other");
        if (shape.responseStatus != null && status == null || shape.incompleteReason != null && reason == null)
            return null;
        return new JSONObject()
                .put("sse_event_counts", events)
                .put("output_item_types", itemTypes)
                .put("status", status == null ? JSONObject.NULL : status)
                .put("incomplete_reason", reason == null ? JSONObject.NULL : reason)
                .put("text_length", textLength)
                .put("completed_output_count", shape.completedOutputCount);
    }

    /** Legacy reporting retains only a validated HTTP status; every legacy text argument is discarded. */
    public static void compatibility(Context context, Integer httpStatus) {
        try {
            if (context != null && !AgentErrorReporter.attachmentReportsSuppressed()) get(context).compatibilityRecord(httpStatus);
        } catch (Throwable ignored) { }
    }

    LocalDiagnostics(File noBackup, LongSupplier clock) {
        this.noBackup = noBackup.getAbsoluteFile();
        this.directory = new File(this.noBackup, DIRECTORY_NAME);
        this.clock = clock;
        try {
            // Canonicalize trusted platform ancestors, but never accept a symlink at the no-backup
            // root itself or anywhere below it. Android can alias ancestors such as /data/user/0.
            this.expectedBase = new File(this.noBackup.getParentFile().getCanonicalFile(),
                    this.noBackup.getName()).getPath();
            this.expectedDirectory = new File(expectedBase, DIRECTORY_NAME).getPath();
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid diagnostic root"); }
    }

    interface Recorder { boolean record(Origin origin, Throwable failure, boolean fatal); }
    interface Terminator { void terminate(); }

    /** Injectable accessors let tests verify installation without changing the JVM's real handler. */
    static boolean installOnce(boolean alreadyInstalled, Supplier<Thread.UncaughtExceptionHandler> read,
            Consumer<Thread.UncaughtExceptionHandler> write, Recorder recorder, Terminator terminator) {
        if (alreadyInstalled) return true;
        Thread.UncaughtExceptionHandler prior = read.get();
        Thread.UncaughtExceptionHandler next = handlerFor(prior, recorder, terminator);
        if (next != prior) write.accept(next);
        return true;
    }

    static Thread.UncaughtExceptionHandler handlerFor(Thread.UncaughtExceptionHandler prior,
            Recorder recorder, Terminator terminator) {
        return prior instanceof Handler ? prior : new Handler(prior, recorder, terminator);
    }

    private static final class Handler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler prior;
        private final Recorder recorder;
        private final Terminator terminator;
        Handler(Thread.UncaughtExceptionHandler prior, Recorder recorder, Terminator terminator) {
            this.prior = prior; this.recorder = recorder; this.terminator = terminator;
        }
        @Override public void uncaughtException(Thread thread, Throwable failure) {
            failure.printStackTrace(System.err);
            System.err.flush();
            try { recorder.record(Origin.UNCAUGHT, failure, true); }
            catch (Throwable ignored) { }
            finally {
                if (prior != null) prior.uncaughtException(thread, failure);
                else terminator.terminate();
            }
        }
    }

    boolean record(Origin origin, Throwable failure, boolean fatal) {
        if (origin == null || failure == null || Boolean.TRUE.equals(CAPTURING.get())) return false;
        try {
            if (AgentErrorReporter.attachmentReportsSuppressed()) return false;
            CAPTURING.set(true);
            long now = clock.getAsLong();
            JSONObject row = header(origin, fatal ? "fatal" : "recoverable", "exception", now, null);
            JSONArray causes = row.getJSONArray("causes");
            IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
            Symbols symbols = new Symbols();
            Throwable cause = failure;
            for (int index = 0; cause != null && index < MAX_CAUSES; index++) {
                if (!fatal && (cause instanceof CancellationException || cause instanceof InterruptedException)) return false;
                if (seen.put(cause, Boolean.TRUE) != null) { row.put("truncated", true); break; }
                if (row.isNull("http_status") && cause instanceof ProviderHttpException) {
                    int status = ((ProviderHttpException) cause).httpStatus;
                    if (status >= 100 && status <= 599) row.put("http_status", status);
                }
                JSONObject item = new JSONObject();
                Class<?> actual = cause.getClass();
                String type = actual.getName();
                item.put("type", symbols.throwable(type) == actual ? type : "other");
                JSONArray frames = new JSONArray(); item.put("frames", frames);
                causes.put(item);
                if (!fits(row)) { causes.remove(causes.length() - 1); row.put("truncated", true); break; }
                try {
                    StackTraceElement[] trace = cause.getStackTrace();
                    if (trace != null) {
                        for (int frame = 0; frame < Math.min(trace.length, MAX_FRAMES); frame++) {
                            frames.put(symbols.frame(trace[frame]));
                            if (!fits(row)) {
                                frames.remove(frames.length() - 1); row.put("truncated", true); break;
                            }
                        }
                        if (trace.length > MAX_FRAMES) row.put("truncated", true);
                    } else row.put("truncated", true);
                } catch (Throwable inaccessible) { row.put("truncated", true); }
                try { cause = cause.getCause(); }
                catch (Throwable inaccessible) { cause = null; row.put("truncated", true); }
                if (index == MAX_CAUSES - 1 && cause != null) row.put("truncated", true);
            }
            return persist(row, now);
        } catch (Throwable ignored) { return false; }
        finally { CAPTURING.remove(); }
    }

    boolean compatibilityRecord(Integer status) {
        if (Boolean.TRUE.equals(CAPTURING.get())) return false;
        try {
            if (AgentErrorReporter.attachmentReportsSuppressed()) return false;
            CAPTURING.set(true);
            long now = clock.getAsLong();
            return persist(header(Origin.RUNTIME, "recoverable", "compatibility", now, status), now);
        } catch (Throwable ignored) { return false; }
        finally { CAPTURING.remove(); }
    }

    private static JSONObject header(Origin origin, String severity, String kind, long now,
            Integer status) throws JSONException {
        JSONObject row = new JSONObject();
        row.put("schema", 1); row.put("id", UUID.randomUUID().toString());
        row.put("time_ms", now); row.put("version_code", BuildConfig.VERSION_CODE);
        row.put("flavor", BuildConfig.FLAVOR); row.put("sdk", Build.VERSION.SDK_INT);
        row.put("origin", origin.name()); row.put("severity", severity); row.put("kind", kind);
        row.put("http_status", status != null && status >= 100 && status <= 599 ? status : JSONObject.NULL);
        row.put("causes", new JSONArray()); row.put("truncated", false);
        return row;
    }

    private static boolean fits(JSONObject value) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_RECORD_BYTES;
    }

    private boolean persist(JSONObject row, long now) throws Exception {
        byte[] bytes = row.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RECORD_BYTES || now < 0 || !STORAGE_LOCK.tryLock()) return false;
        try {
            checkDirectory(true);
            File available = null, oldestFile = null;
            long oldest = Long.MAX_VALUE;
            for (int index = 0; index < SLOT_COUNT; index++) {
                File candidate = slot(index);
                verifyChild(candidate);
                StructStat attributes = statOrNull(candidate);
                if (attributes == null) { if (available == null) available = candidate; continue; }
                if (!ordinary(attributes)) continue; // Never follow or delete an alien file type/hard link.
                JSONObject existing = parse(readBounded(candidate));
                long time = existing == null ? -1 : integer(existing.opt("time_ms"), -1, Long.MAX_VALUE, -1);
                if (time < 0 || expired(time, now) || future(time, now)) {
                    verifyChild(candidate);
                    if (candidate.delete() && available == null) available = candidate;
                } else if (oldestFile == null || time < oldest) { oldestFile = candidate; oldest = time; }
            }
            File target = available == null ? oldestFile : available;
            if (target == null) return false;
            File stage = new File(directory, STAGING_NAME);
            verifyChild(stage);
            StructStat leftover = statOrNull(stage);
            if (leftover != null && (!ordinary(leftover) || !stage.delete())) return false;
            try {
                writeStaged(stage, bytes);
                verifyChild(stage); verifyChild(target);
                StructStat finalTarget = statOrNull(target);
                if (finalTarget != null && !ordinary(finalTarget)) return false;
                // Same-directory rename is atomic on Android's POSIX private filesystem. Never
                // delete a live target first: an interrupted write leaves the prior slot intact.
                if (!stage.renameTo(target)) return false;
                syncDirectory();
                return true;
            } finally {
                verifyChild(stage);
                StructStat remaining = statOrNull(stage);
                if (remaining != null && ordinary(remaining)) stage.delete();
            }
        } finally { STORAGE_LOCK.unlock(); }
    }

    /**
     * A null/empty ID lists newest records; an opaque ID selects exactly one. Results are
     * rebuilt from a strict schema, never returned as raw disk bytes. No caller path is used.
     * Structural symbol resolution happens after releasing the process-wide storage lock.
     */
    public JSONObject snapshot(String id, int limit, boolean includeFrames) {
        boolean selected = id != null && !id.isEmpty();
        if (selected && !validId(id)) return envelope("invalid_id", new JSONArray(), false);
        int count = selected ? 1 : Math.max(1, Math.min(limit, MAX_RESULTS));
        List<byte[]> captured = new ArrayList<>(SLOT_COUNT);
        long now;
        if (!STORAGE_LOCK.tryLock()) return envelope("busy", new JSONArray(), false);
        try {
            now = clock.getAsLong();
            if (!checkDirectory(false)) return envelope(selected ? "not_found" : "ok", new JSONArray(), false);
            for (int index = 0; index < SLOT_COUNT; index++) {
                byte[] bytes = readBounded(slot(index));
                if (bytes != null) captured.add(bytes);
            }
        } catch (Throwable unavailable) { return envelope("unavailable", new JSONArray(), false); }
        finally { STORAGE_LOCK.unlock(); }
        try {
            List<JSONObject> rows = new ArrayList<>(SLOT_COUNT);
            for (byte[] bytes : captured) {
                JSONObject raw = parse(bytes);
                if (raw == null || (selected && !id.equals(raw.opt("id")))) continue;
                JSONObject row = validate(raw, now, includeFrames);
                if (row != null) rows.add(row);
            }
            rows.sort((a, b) -> Long.compare(b.optLong("time_ms"), a.optLong("time_ms")));
            JSONArray records = new JSONArray();
            for (int index = 0; index < Math.min(count, rows.size()); index++) records.put(rows.get(index));
            return envelope(selected && rows.isEmpty() ? "not_found" : "ok", records, rows.size() > count);
        } catch (Throwable unavailable) { return envelope("unavailable", new JSONArray(), false); }
    }

    private static JSONObject envelope(String status, JSONArray rows, boolean truncated) {
        JSONObject result = new JSONObject();
        try {
            result.put("schema", 1); result.put("status", status); result.put("records", rows);
            result.put("truncated", truncated);
            result.put("current_version_name", BuildConfig.VERSION_NAME);
            result.put("max_records", SLOT_COUNT); result.put("eligibility_days", 7);
            result.put("max_causes", MAX_CAUSES); result.put("max_frames_per_cause", MAX_FRAMES);
            result.put("max_record_bytes", MAX_RECORD_BYTES);
            result.put("content", "structural_only");
        } catch (JSONException ignored) { }
        return result;
    }

    private static JSONObject validate(JSONObject raw, long now, boolean includeFrames) {
        try {
            // Animation observations are no longer retained. Ignore old slots as well as new writes.
            if ("animation".equals(raw.opt("kind"))) return null;
            boolean responseShape = "response_shape".equals(raw.opt("kind"));
            boolean modelRequest = "model_request".equals(raw.opt("kind"));
            if (responseShape) {
                if (!keys(raw, "schema", "id", "time_ms", "version_code", "flavor", "sdk", "origin",
                        "severity", "kind", "http_status", "causes", "truncated", "response_shape")) return null;
            } else if (modelRequest) {
                if (!keys(raw, "schema", "id", "time_ms", "version_code", "flavor", "sdk", "origin",
                        "severity", "kind", "http_status", "causes", "truncated", "model_request")) return null;
            } else if (!keys(raw, "schema", "id", "time_ms", "version_code", "flavor", "sdk", "origin",
                    "severity", "kind", "http_status", "causes", "truncated")) return null;
            if (integer(raw.opt("schema"), 1, 1, -1) != 1 || !(raw.opt("id") instanceof String)
                    || !validId((String) raw.opt("id"))) return null;
            long time = integer(raw.opt("time_ms"), 0, Long.MAX_VALUE, -1);
            long version = integer(raw.opt("version_code"), 1, Integer.MAX_VALUE, -1);
            long sdk = integer(raw.opt("sdk"), 1, 1000, -1);
            if (time < 0 || expired(time, now) || future(time, now) || version < 0 || sdk < 0) return null;
            String flavor = fixed(raw.opt("flavor"), "play", "full");
            String severity = fixed(raw.opt("severity"), "recoverable", "fatal");
            if (responseShape) severity = fixed(raw.opt("severity"), "recoverable");
            String kind = fixed(raw.opt("kind"), "exception", "compatibility", "response_shape", "model_request");
            String origin = raw.opt("origin") instanceof String ? (String) raw.opt("origin") : "";
            try { Origin.valueOf(origin); } catch (IllegalArgumentException invalid) { return null; }
            if (flavor == null || severity == null || kind == null || !(raw.opt("truncated") instanceof Boolean)) return null;
            Object status = raw.opt("http_status");
            if (status != JSONObject.NULL && integer(status, 100, 599, -1) < 0) return null;
            if (!(raw.opt("causes") instanceof JSONArray)) return null;
            JSONArray inputCauses = raw.getJSONArray("causes");
            boolean invalidCompatibility = "compatibility".equals(kind)
                    && (inputCauses.length() != 0 || !"RUNTIME".equals(origin)
                    || !"recoverable".equals(severity));
            boolean invalidResponseShape = "response_shape".equals(kind)
                    && (inputCauses.length() != 0 || !"PROVIDER".equals(origin)
                    || !"recoverable".equals(severity));
            boolean invalidModelRequest = "model_request".equals(kind)
                    && (inputCauses.length() != 0 || !"PROVIDER".equals(origin)
                    || !"recoverable".equals(severity));
            if (inputCauses.length() > MAX_CAUSES || ("exception".equals(kind) && inputCauses.length() == 0)
                    || invalidCompatibility || invalidResponseShape || invalidModelRequest) return null;
            JSONObject cleanResponseShape = null;
            if (responseShape) {
                if (!"PROVIDER".equals(origin) || status != JSONObject.NULL || inputCauses.length() != 0
                        || raw.getBoolean("truncated")) return null;
                cleanResponseShape = validateResponseShape(raw.optJSONObject("response_shape"));
                if (cleanResponseShape == null) return null;
            }
            JSONObject cleanModelRequest = null;
            if (modelRequest) {
                if (!"PROVIDER".equals(origin) || status != JSONObject.NULL || inputCauses.length() != 0
                        || raw.getBoolean("truncated")) return null;
                cleanModelRequest = validateModelRequest(raw.optJSONObject("model_request"));
                if (cleanModelRequest == null) return null;
            }
            JSONObject result = new JSONObject();
            result.put("schema", 1); result.put("id", raw.getString("id")); result.put("time_ms", time);
            result.put("version_code", version); result.put("flavor", flavor); result.put("sdk", sdk);
            result.put("origin", origin); result.put("severity", severity); result.put("kind", kind);
            result.put("http_status", status); result.put("truncated", raw.getBoolean("truncated"));
            if (responseShape) result.put("response_shape", cleanResponseShape);
            if (modelRequest) result.put("model_request", cleanModelRequest);
            JSONArray causes = new JSONArray(); Symbols symbols = new Symbols();
            for (int index = 0; index < inputCauses.length(); index++) {
                Object object = inputCauses.opt(index);
                if (!(object instanceof JSONObject)) return null;
                JSONObject input = (JSONObject) object;
                if (!keys(input, "type", "frames") || !(input.opt("type") instanceof String)
                        || !(input.opt("frames") instanceof JSONArray)) return null;
                String type = (String) input.opt("type");
                if (!"other".equals(type) && symbols.throwable(type) == null) {
                    if (version == BuildConfig.VERSION_CODE || !Symbols.token(type)) return null;
                    type = "other"; // A previously shipped symbol may no longer exist after an upgrade.
                }
                JSONArray inputFrames = input.getJSONArray("frames"), frames = new JSONArray();
                if (inputFrames.length() > MAX_FRAMES) return null;
                for (int frame = 0; frame < inputFrames.length(); frame++) {
                    JSONObject clean = symbols.validateFrame(inputFrames.opt(frame), version != BuildConfig.VERSION_CODE);
                    if (clean == null) return null;
                    if (includeFrames) frames.put(clean);
                }
                JSONObject item = new JSONObject(); item.put("type", type);
                if (includeFrames) item.put("frames", frames);
                else item.put("frame_count", inputFrames.length());
                causes.put(item);
            }
            result.put("causes", causes);
            return result;
        } catch (Throwable invalid) { return null; }
    }

    private static boolean future(long timestamp, long now) {
        return timestamp >= 0 && now >= 0 && timestamp > now
                && timestamp - now > MAX_FUTURE_SKEW_MILLIS;
    }

    private static boolean expired(long timestamp, long now) {
        return timestamp < 0 || now < 0 || timestamp < Math.max(0, now - RETENTION_MILLIS);
    }
    private static boolean validId(String id) { return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
    private static long integer(Object value, long low, long high, long invalid) {
        if (!(value instanceof Integer) && !(value instanceof Long)) return invalid;
        long number = ((Number) value).longValue();
        return number >= low && number <= high ? number : invalid;
    }
    private static String fixed(Object value, String... allowed) {
        if (!(value instanceof String)) return null;
        for (String item : allowed) if (item.equals(value)) return item;
        return null;
    }

    private static JSONObject validateResponseShape(JSONObject input) throws JSONException {
        if (input == null || !keys(input, "sse_event_counts", "output_item_types", "status",
                "incomplete_reason", "text_length", "completed_output_count")) return null;
        Object rawEvents = input.opt("sse_event_counts");
        Object rawTypes = input.opt("output_item_types");
        if (!(rawEvents instanceof JSONObject) || !(rawTypes instanceof JSONArray)) return null;
        JSONObject eventCounts = (JSONObject) rawEvents;
        if (eventCounts.length() > ResponseDiagnosticsParser.MAX_ITEMS) return null;
        JSONObject cleanEvents = new JSONObject();
        java.util.Iterator<String> names = eventCounts.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (fixedEventType(name) == null) return null;
            long count = integer(eventCounts.opt(name), 1, ResponseDiagnosticsParser.MAX_ITEMS, -1);
            if (count < 0) return null;
            cleanEvents.put(name, count);
        }
        JSONArray types = (JSONArray) rawTypes;
        if (types.length() > ResponseDiagnosticsParser.MAX_ITEMS) return null;
        JSONArray cleanTypes = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < types.length(); i++) {
            Object value = types.opt(i);
            if (!(value instanceof String) || fixedOutputType((String) value) == null
                    || !seen.add((String) value)) return null;
            cleanTypes.put(value);
        }
        Object rawStatus = input.opt("status"), rawReason = input.opt("incomplete_reason");
        String responseStatus = rawStatus == JSONObject.NULL ? null
                : fixed(rawStatus, "completed", "incomplete", "failed", "cancelled", "in_progress", "queued", "other");
        String incompleteReason = rawReason == JSONObject.NULL ? null
                : fixed(rawReason, "max_output_tokens", "content_filter", "tool_call_error", "safety_violation", "other");
        if (rawStatus != JSONObject.NULL && responseStatus == null
                || rawReason != JSONObject.NULL && incompleteReason == null) return null;
        long textLength = integer(input.opt("text_length"), 0,
                ResponseDiagnosticsParser.MAX_BODY_CHARACTERS, -1);
        long outputCount = integer(input.opt("completed_output_count"), 0,
                ResponseDiagnosticsParser.MAX_ITEMS, -1);
        if (textLength < 0 || outputCount < 0) return null;
        return new JSONObject().put("sse_event_counts", cleanEvents)
                .put("output_item_types", cleanTypes)
                .put("status", responseStatus == null ? JSONObject.NULL : responseStatus)
                .put("incomplete_reason", incompleteReason == null ? JSONObject.NULL : incompleteReason)
                .put("text_length", textLength)
                .put("completed_output_count", outputCount);
    }

    private static JSONObject validateModelRequest(JSONObject input) throws JSONException {
        if (input == null || !keys(input, "model", "reasoning_effort", "reasoning_summary",
                "text_verbosity", "declared_tool_count", "web_available")) return null;
        String model = diagnosticLabel(input.opt("model"));
        String effort = diagnosticLabel(input.opt("reasoning_effort"));
        String summary = diagnosticLabel(input.opt("reasoning_summary"));
        String verbosity = diagnosticLabel(input.opt("text_verbosity"));
        long toolCount = integer(input.opt("declared_tool_count"), 0, Integer.MAX_VALUE, -1);
        Object web = input.opt("web_available");
        if (model == null || effort == null || summary == null || verbosity == null || toolCount < 0
                || !(web instanceof Boolean)) return null;
        return new JSONObject().put("model", model).put("reasoning_effort", effort)
                .put("reasoning_summary", summary).put("text_verbosity", verbosity)
                .put("declared_tool_count", toolCount).put("web_available", web);
    }

    private static String diagnosticLabel(Object value) {
        if (!(value instanceof String)) return null;
        String text = (String) value;
        if (text.isEmpty()) return null;
        for (int index = 0; index < text.length(); index++) if (Character.isISOControl(text.charAt(index))) return null;
        return text;
    }

    private static String fixedEventType(String value) {
        return fixed(value, "other", "error", "response.created", "response.in_progress",
                "response.completed", "response.incomplete", "response.failed", "response.cancelled",
                "response.output_item.added", "response.output_item.done", "response.content_part.added",
                "response.content_part.done", "response.output_text.delta", "response.output_text.done",
                "response.refusal.delta", "response.refusal.done", "response.function_call_arguments.delta",
                "response.function_call_arguments.done", "response.reasoning_summary_text.delta",
                "response.reasoning_summary_text.done", "response.reasoning_summary_part.added",
                "response.reasoning_summary_part.done");
    }

    private static String fixedOutputType(String value) {
        return fixed(value, "other", "message", "function_call", "reasoning", "image_generation_call",
                "code_interpreter_call", "file_search_call", "mcp_call", "computer_call",
                "web_search_call", "tool_search_call");
    }
    private static boolean keys(JSONObject value, String... names) {
        if (value.length() != names.length) return false;
        for (String name : names) if (!value.has(name)) return false;
        return true;
    }

    /** Reject excessive nesting before calling the platform's recursive JSON parser. */
    private static JSONObject parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_RECORD_BYTES) return null;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            boolean string = false, escape = false; int depth = 0;
            for (int index = 0; index < text.length(); index++) {
                char next = text.charAt(index);
                if (string) {
                    if (escape) escape = false;
                    else if (next == '\\') escape = true;
                    else if (next == '"') string = false;
                    else if (next < 0x20) return null;
                } else if (next == '"') string = true;
                else if (next == '{' || next == '[') { if (++depth > 8) return null; }
                else if (next == '}' || next == ']') { if (--depth < 0) return null; }
            }
            if (depth != 0 || string || escape) return null;
            JSONObject parsed = new JSONObject(text);
            // Our own writer uses canonical compact JSON. Reject permissive parser extensions,
            // duplicate keys and appended bytes without ever returning the original text.
            if (!parsed.toString().equals(text)) return null;
            return parsed;
        } catch (Throwable invalid) { return null; }
    }

    private File slot(int index) { return new File(directory, "slot-" + (index < 10 ? "0" : "") + index + ".json"); }

    private boolean checkDirectory(boolean create) throws IOException, ErrnoException {
        if (!noBackup.getCanonicalPath().equals(expectedBase)) throw new IOException("Diagnostic root changed");
        StructStat base = Os.lstat(noBackup.getPath());
        if (!OsConstants.S_ISDIR(base.st_mode)) throw new IOException("Invalid diagnostic root");
        if (!directory.getCanonicalPath().equals(expectedDirectory)) throw new IOException("Diagnostic directory changed");
        StructStat stored = statOrNull(directory);
        if (stored == null) {
            if (!create) return false;
            if (!directory.mkdir()) throw new IOException("Cannot create diagnostic directory");
            stored = Os.lstat(directory.getPath());
        }
        if (!OsConstants.S_ISDIR(stored.st_mode)) throw new IOException("Invalid diagnostic directory");
        return true;
    }

    private void verifyChild(File child) throws IOException, ErrnoException {
        checkDirectory(false);
        if (!child.getParentFile().equals(directory)
                || !child.getCanonicalPath().equals(new File(expectedDirectory, child.getName()).getPath()))
            throw new IOException("Diagnostic path changed");
    }
    private static StructStat statOrNull(File file) throws ErrnoException {
        try { return Os.lstat(file.getPath()); }
        catch (ErrnoException missing) { if (missing.errno == OsConstants.ENOENT) return null; throw missing; }
    }
    private static boolean ordinary(StructStat value) {
        return OsConstants.S_ISREG(value.st_mode) && value.st_nlink == 1;
    }

    private byte[] readBounded(File file) throws IOException, ErrnoException {
        verifyChild(file);
        StructStat before = statOrNull(file);
        if (before == null || !ordinary(before) || before.st_size < 1 || before.st_size > MAX_RECORD_BYTES) return null;
        FileDescriptor fd = null;
        try {
            fd = Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
            verifyOpened(file, fd, before);
            try (FileInputStream input = new FileInputStream(fd)) {
                fd = null;
                byte[] bytes = new byte[(int) before.st_size];
                int offset = 0;
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count < 0) return null;
                    if (count == 0) return null;
                    offset += count;
                }
                if (input.read() != -1) return null;
                verifyOpened(file, input.getFD(), before);
                return bytes;
            }
        } finally { close(fd); }
    }

    private void writeStaged(File file, byte[] bytes) throws IOException, ErrnoException {
        FileDescriptor fd = null;
        try {
            verifyChild(file);
            fd = Os.open(file.getPath(), OsConstants.O_WRONLY | OsConstants.O_CREAT
                    | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0600);
            verifyOpened(file, fd, null);
            try (FileOutputStream output = new FileOutputStream(fd)) {
                fd = null;
                output.write(bytes); output.getFD().sync();
                verifyOpened(file, output.getFD(), null);
            }
        } finally { close(fd); }
    }

    private void verifyOpened(File file, FileDescriptor fd, StructStat before) throws IOException, ErrnoException {
        verifyChild(file);
        StructStat opened = Os.fstat(fd), named = Os.lstat(file.getPath());
        if (!ordinary(opened) || !ordinary(named) || opened.st_dev != named.st_dev
                || opened.st_ino != named.st_ino || (before != null && (opened.st_ino != before.st_ino
                || opened.st_dev != before.st_dev || opened.st_size != before.st_size)))
            throw new IOException("Diagnostic file changed");
    }
    private void syncDirectory() throws IOException, ErrnoException {
        FileDescriptor fd = null;
        try {
            checkDirectory(false);
            StructStat before = Os.lstat(directory.getPath());
            fd = Os.open(directory.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
            StructStat opened = Os.fstat(fd);
            if (!OsConstants.S_ISDIR(opened.st_mode) || opened.st_dev != before.st_dev || opened.st_ino != before.st_ino)
                throw new IOException("Diagnostic directory changed");
            Os.fsync(fd);
        } finally { close(fd); }
    }
    private static void close(FileDescriptor fd) {
        if (fd != null) try { Os.close(fd); } catch (Throwable ignored) { }
    }

    /** Input syntax is only a work bound; actual loaded symbols determine which names may escape. */
    private static final class Symbols {
        private static final ClassLoader TRUSTED_LOADER = LocalDiagnostics.class.getClassLoader();
        private static final int MAX_LOOKUPS = MAX_CAUSES * (MAX_FRAMES + 1);
        private final Map<String, Class<?>> classes = new HashMap<>();
        private final Set<String> unavailable = new HashSet<>();
        private int lookups;
        private static String family(String name) {
            if (name != null && name.startsWith("com.jarvys.agent.")) return "app";
            if (name != null && (name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("android.") || name.startsWith("androidx.")
                    || name.startsWith("kotlin.") || name.startsWith("kotlinx."))) return "framework";
            return "other";
        }
        private static boolean token(String name) {
            return name != null && name.length() <= 240
                    && name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
        }
        private Class<?> resolve(String name) {
            if (!token(name) || "other".equals(family(name))) return null;
            if (classes.containsKey(name)) return classes.get(name);
            if (unavailable.contains(name) || lookups++ >= MAX_LOOKUPS) return null;
            try {
                Class<?> type = Class.forName(name, false, TRUSTED_LOADER);
                classes.put(name, type); return type;
            } catch (Throwable failure) { unavailable.add(name); return null; }
        }
        Class<?> throwable(String name) {
            Class<?> type = resolve(name);
            return type != null && Throwable.class.isAssignableFrom(type) ? type : null;
        }
        private boolean method(String name, String method) {
            if (method == null || method.length() > 120) return false;
            Class<?> type = resolve(name);
            if (type == null) return false;
            try {
                if ("<init>".equals(method)) return type.getDeclaredConstructors().length > 0;
                if (!method.matches("[A-Za-z_$][A-Za-z0-9_$]*")) return false;
                Method[] methods = type.getDeclaredMethods();
                for (int index = 0; index < Math.min(methods.length, 512); index++)
                    if (method.equals(methods[index].getName())) return true;
            } catch (Throwable failure) { return false; }
            return false;
        }
        JSONObject frame(StackTraceElement input) throws JSONException {
            JSONObject result = new JSONObject();
            String name = input == null ? null : input.getClassName();
            String method = input == null ? null : input.getMethodName();
            if (method(name, method)) {
                result.put("class", name); result.put("method", method);
                int line = input.getLineNumber();
                result.put("line", line >= -2 && line <= 1_000_000 ? line : -1);
            } else result.put("family", family(name));
            return result;
        }
        JSONObject validateFrame(Object value, boolean historical) throws JSONException {
            if (!(value instanceof JSONObject)) return null;
            JSONObject raw = (JSONObject) value, result = new JSONObject();
            if (keys(raw, "family")) {
                String family = fixed(raw.opt("family"), "app", "framework", "other");
                if (family == null) return null;
                result.put("family", family); return result;
            }
            if (!keys(raw, "class", "method", "line") || !(raw.opt("class") instanceof String)
                    || !(raw.opt("method") instanceof String)) return null;
            String name = (String) raw.opt("class"), method = (String) raw.opt("method");
            long line = integer(raw.opt("line"), -2, 1_000_000, -3);
            if (line == -3) return null;
            if (!method(name, method)) {
                if (!historical || !token(name) || method.length() > 120
                        || !(method.matches("[A-Za-z_$][A-Za-z0-9_$]*") || "<init>".equals(method))) return null;
                result.put("family", family(name)); return result;
            }
            result.put("class", name); result.put("method", method); result.put("line", line);
            return result;
        }
    }
}
