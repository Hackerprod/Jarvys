package com.jarvys.factory.runtime;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installed-app secret effects. Each Prepared input remains bound to its current operation lease.
 * This adapter does not request another approval or transport values through the host.
 * Native capture is optional input UI; put/get deliberately allow authorized programmatic values.
 */
public final class SecretEffects implements AutoCloseable {
    public interface Foreground { boolean active(); }
    public interface Callback {
        /** Must bind this exact initiating page/request, not just any current WebView. */
        boolean current();
        boolean claimUi();
        void releaseUi();
        /** Invoked on main after live access/page/foreground checks; caller must recheck bridge delivery. */
        void complete(Object result);
        void error(FactoryException failure);
    }
    interface SecretsFactory { NativeSecrets open() throws FactoryException; }
    private interface Work { Object run(Pending pending) throws Exception; }

    /** Owns a single immutable parse through execution. Close it when admission fails. */
    public static final class Prepared implements AutoCloseable {
        public final String method;
        private final SecretRequest.Put put;
        private final SecretRequest.Capture capture;
        private final String id;
        private final NetworkRequest request;
        private final AtomicBoolean claimed = new AtomicBoolean(), released = new AtomicBoolean();
        private Prepared(String method, SecretRequest.Put put, SecretRequest.Capture capture,
                         String id, NetworkRequest request) {
            this.method = method; this.put = put; this.capture = capture; this.id = id; this.request = request;
        }
        public JSONObject authorizationDetails() throws FactoryException {
            if (released.get()) throw cancelled();
            if (put != null) return put.authorizationDetails();
            if (capture != null) return capture.authorizationDetails();
            if (request != null) {
                // The full normal network binding and opaque ID share ONE secrets.request grant.
                try { return SecretRequest.idAuthorizationDetails(id).put("request", request.authorizationDetails()); }
                catch (FactoryException failure) { throw failure; }
                catch (Exception failure) { throw unavailable(); }
            }
            return id == null ? new JSONObject() : SecretRequest.idAuthorizationDetails(id);
        }
        private void claim() throws FactoryException {
            if (released.get() || !claimed.compareAndSet(false, true)) throw cancelled();
            if (released.get()) throw cancelled();
        }
        @Override public void close() {
            released.set(true);
            if (put != null) put.close();
        }
    }

    public static Prepared prepare(String method, JSONObject args) throws FactoryException {
        if (method == null || args == null) throw SecretRequest.invalid();
        switch (method) {
            case "secrets.put": return new Prepared(method, SecretRequest.parsePut(args), null, null, null);
            case "secrets.capture": return new Prepared(method, null, SecretRequest.parseCapture(args), null, null);
            case "secrets.get": case "secrets.describe": case "secrets.remove":
                return new Prepared(method, null, null, SecretRequest.parseId(args), null);
            case "secrets.list": case "secrets.reset":
                SecretRequest.parseEmpty(args); return new Prepared(method, null, null, null, null);
            case "secrets.request":
                try {
                    FactoryConfig.exactKeys(args, "id", "request");
                    if (!(args.get("id") instanceof String) || !(args.get("request") instanceof JSONObject))
                        throw SecretRequest.invalid();
                    String id = SecretRequest.id((String) args.get("id"));
                    NetworkRequest request = NetworkRequest.fromRequest((JSONObject) args.get("request"));
                    // Validate initial HTTPS destination without opening the vault or reading a value.
                    SecretRequest.injectionAuthorizationDetails(id, request.url, request.method);
                    return new Prepared(method, null, null, id, request);
                } catch (FactoryException failure) { throw failure; }
                catch (Exception failure) { throw SecretRequest.invalid(); }
            default: throw SecretRequest.invalid();
        }
    }

    private final Activity activity;
    private final SecretsFactory factory;
    private final NativeNetwork network;
    private final Executor io;
    private final ThreadPoolExecutor ownedExecutor;
    private final Handler main;
    private final int maximumInputChars;
    // Includes capture waiting for input, queued work, running work and pending main-thread delivery.
    private final Semaphore admission = new Semaphore(18);
    private final Set<Pending> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public SecretEffects(Activity activity, NativeSecrets.Limits limits) {
        this(activity, limits, new NativeNetwork());
    }
    public SecretEffects(Activity activity, NativeSecrets.Limits limits, NativeNetwork network) {
        if (activity == null || limits == null || network == null) throw new IllegalArgumentException();
        this.activity = activity; this.network = network; this.maximumInputChars = limits.valueBytes;
        this.main = new Handler(Looper.getMainLooper());
        // Do not acquire a storage directory/key/vault until a granted operation reaches its worker.
        this.factory = () -> new NativeSecrets(activity.getApplicationContext(), activity.getPackageName(), limits);
        this.ownedExecutor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(16), runnable -> {
                    Thread thread = new Thread(runnable, "factory-secrets"); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.ownedExecutor.allowCoreThreadTimeOut(true);
        this.io = ownedExecutor;
    }
    /** Offline tests only. Executor must queue work rather than performing cryptography on main. */
    SecretEffects(Activity activity, NativeSecrets.Limits limits, SecretsFactory factory,
                  NativeNetwork network, Executor io, Handler main) {
        if (activity == null || limits == null || factory == null || network == null || io == null || main == null)
            throw new IllegalArgumentException();
        this.activity = activity; this.factory = factory; this.network = network; this.io = io;
        this.main = main; this.maximumInputChars = limits.valueBytes; this.ownedExecutor = null;
    }

    /** Takes ownership of Prepared on every path. Caller supplies the already-approved live grant. */
    public Handle execute(Prepared prepared, NativeOperationAuthorization.Access access,
                          Foreground foreground, Callback callback) throws FactoryException {
        if (prepared == null) throw SecretRequest.invalid();
        Pending operation = null;
        boolean reserved = false;
        try {
            if (access == null || foreground == null || callback == null) throw SecretRequest.invalid();
            prepared.claim();
            access.check();
            if (closed || !foreground.active() || !callback.current()) throw cancelled();
            if (!admission.tryAcquire()) throw new FactoryException("BUSY", "Secret operation worker is busy.");
            reserved = true;
            operation = new Pending(prepared, access, foreground, callback);
            pending.add(operation);
            operation.check();
            if (prepared.capture != null) operation.postCapture();
            else operation.enqueue(value -> value.runOperation());
            return new Handle(operation);
        } catch (FactoryException | RuntimeException failure) {
            if (operation != null) operation.abandon();
            else { if (reserved) admission.release(); prepared.close(); }
            throw failure;
        }
    }

    public final class Handle implements AutoCloseable {
        private final Pending operation;
        private Handle(Pending operation) { this.operation = operation; }
        public void cancel() { operation.cancel(); }
        @Override public void close() { cancel(); }
    }
    /** Call on pause, page replacement and any UI/session ownership loss. */
    public void cancelAll() { for (Pending operation : new ArrayList<>(pending)) operation.cancel(); }
    @Override public void close() {
        closed = true; cancelAll();
        // Queued tasks still drain through their cancelled guards and cleanup; no abandoned values.
        if (ownedExecutor != null) ownedExecutor.shutdown();
    }

    private final class Pending {
        final Prepared prepared;
        final NativeOperationAuthorization.Access access;
        final Foreground foreground;
        final Callback callback;
        final AtomicBoolean cancelled = new AtomicBoolean(), terminal = new AtomicBoolean();
        final NativeNetwork.Cancellation networkCancellation = new NativeNetwork.Cancellation();
        // The guarded Access is used at every NativeSecrets mutation/read/injection checkpoint.
        final NativeOperationAuthorization.Access guarded = this::check;
        char[] input;
        // The following UI members are main-thread-only.
        AlertDialog dialog;
        EditText editor;
        Window activityWindow;
        boolean uiClaimed, addedSecureFlag, oversizedInput;
        final Runnable watch = new Runnable() {
            @Override public void run() {
                if (dialog == null || terminal.get()) return;
                try { check(); if (!main.postDelayed(this, 250)) fail(cancelled()); }
                catch (Exception failure) { fail(asFactory(failure)); }
            }
        };
        Pending(Prepared prepared, NativeOperationAuthorization.Access access, Foreground foreground, Callback callback) {
            this.prepared = prepared; this.access = access; this.foreground = foreground; this.callback = callback;
        }
        boolean current() {
            return !closed && !cancelled.get() && !terminal.get() && !prepared.released.get()
                    && pending.contains(this) && foreground.active() && callback.current();
        }
        void check() {
            if (!current()) throw new SecurityException("Secret operation authority ended.");
            access.check();
            if (!current()) throw new SecurityException("Secret operation authority ended.");
        }
        NativeSecrets storage() throws FactoryException {
            check(); NativeSecrets storage = factory.open(); check(); return storage;
        }
        Object runOperation() throws Exception {
            check();
            NativeSecrets secrets = storage();
            switch (prepared.method) {
                case "secrets.put": return secrets.put(prepared.put, guarded);
                case "secrets.get": return secrets.get(prepared.id, guarded);
                case "secrets.list": return secrets.list(guarded);
                case "secrets.describe": return secrets.describe(prepared.id, guarded);
                case "secrets.remove":
                    secrets.remove(prepared.id, guarded);
                    return new JSONObject().put("id", prepared.id).put("removed", true);
                case "secrets.reset":
                    secrets.reset(guarded); return new JSONObject().put("reset", true);
                case "secrets.request":
                    return network.execute(prepared.request, guarded, networkCancellation,
                            (url, method, sink) -> secrets.inject(prepared.id, url, method, guarded, sink::set));
                default: throw SecretRequest.invalid();
            }
        }
        void enqueue(Work work) {
            try {
                check();
                io.execute(() -> {
                    try {
                        check();
                        // Injection constructors cannot accidentally make disk/Keystore calls on main.
                        if (Looper.myLooper() == Looper.getMainLooper()) throw unavailable();
                        Object result = work.run(this);
                        check();
                        complete(result);
                    } catch (Exception failure) { fail(asFactory(failure)); }
                    finally { prepared.close(); clearInput(); }
                });
            } catch (Exception failure) { fail(asFactory(failure)); }
        }
        void complete(Object result) {
            // Do not retain a readback/network result in logs, a reusable cache or policy metadata.
            if (!main.post(() -> {
                try {
                    // Prepared's sensitive input may already be cleared by the worker's finally.
                    checkDelivery();
                    if (!terminal.compareAndSet(false, true)) return;
                    cleanupUi(); retire();
                    callback.complete(result);
                } catch (Exception failure) { fail(asFactory(failure)); }
            })) abandon();
        }
        void checkDelivery() {
            if (closed || cancelled.get() || terminal.get() || !pending.contains(this)
                    || !foreground.active() || !callback.current()) throw new SecurityException();
            access.check();
            if (closed || cancelled.get() || terminal.get() || !foreground.active() || !callback.current())
                throw new SecurityException();
        }
        void fail(FactoryException failure) {
            if (!terminal.compareAndSet(false, true)) return;
            cancelled.set(true); networkCancellation.cancel(); prepared.close(); clearInput();
            if (!main.post(() -> {
                cleanupUi(); retire();
                // Report only to the initiating foreground page. The parent retires its own job on loss.
                if (foreground.active() && callback.current()) callback.error(failure);
            })) retire();
        }
        void cancel() { fail(cancelled()); }
        void abandon() {
            terminal.set(true); cancelled.set(true); networkCancellation.cancel(); prepared.close(); clearInput();
            if (Looper.myLooper() == Looper.getMainLooper()) cleanupUi();
            else main.post(this::cleanupUi);
            retire();
        }
        void retire() { if (pending.remove(this)) admission.release(); }
        synchronized void clearInput() { if (input != null) Arrays.fill(input, '\0'); input = null; }
        synchronized char[] takeInput() { char[] result = input; input = null; return result; }
        synchronized void acceptInput(char[] value) {
            if (cancelled.get() || terminal.get()) Arrays.fill(value, '\0');
            else input = value;
        }
        void postCapture() {
            if (!main.post(this::showCapture)) fail(cancelled());
        }
        void showCapture() {
            try {
                check();
                if (activity.isFinishing() || activity.isDestroyed()) throw cancelled();
                if (!callback.claimUi()) throw new FactoryException("BUSY", "Another native input interaction is open.");
                uiClaimed = true;
                check();
                editor = new EditText(activity);
                editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                editor.setSingleLine(true);
                editor.setSaveEnabled(false); editor.setSaveFromParentEnabled(false);
                // Reject the entire oversized edit and block Save; never silently keep a truncated credential.
                editor.setFilters(new InputFilter[] { (source, start, end, dest, dstart, dend) -> {
                    oversizedInput = dest.length() - (dend - dstart) + (end - start) > maximumInputChars;
                    EditText target = editor;
                    // TextView may clear an error while it finishes applying a replacement edit.
                    main.post(() -> {
                        if (editor == target && !terminal.get()) target.setError(oversizedInput
                                ? "Credential exceeds the configured input limit." : null);
                    });
                    return oversizedInput ? dest.subSequence(dstart, dend) : null;
                } });
                editor.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
                if (Build.VERSION.SDK_INT >= 26) {
                    editor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                    editor.setImeOptions(editor.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
                }
                // Both windows are protected before the password view is displayed. Restore only our bit.
                activityWindow = activity.getWindow();
                if (activityWindow == null) throw unavailable();
                addedSecureFlag = (activityWindow.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0;
                if (addedSecureFlag) activityWindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                String summary = activity.getPackageName() + "\n" + prepared.capture.scope.origin + "\n"
                        + android.text.TextUtils.join(", ", prepared.capture.scope.methods) + "\nHeader: " + prepared.capture.scope.header;
                dialog = new AlertDialog.Builder(activity).setTitle("Save credential")
                        .setMessage(summary).setView(editor).setPositiveButton("Save", null)
                        .setNegativeButton("Cancel", (ignored, which) -> cancel()).create();
                dialog.setOnCancelListener(ignored -> cancel());
                dialog.setOnDismissListener(ignored -> {
                    if (dialog != null) { cleanupUi(); if (!terminal.get()) cancel(); }
                });
                Window window = dialog.getWindow();
                if (window == null) throw unavailable();
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                check(); dialog.show(); check();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> submitCapture());
                editor.setOnEditorActionListener((view, action, event) -> {
                    if (action == EditorInfo.IME_ACTION_DONE) { submitCapture(); return true; }
                    return false;
                });
                editor.requestFocus();
                if (!main.postDelayed(watch, 250)) throw cancelled();
            } catch (Exception failure) { fail(asFactory(failure)); }
        }
        void submitCapture() {
            char[] value = null;
            try {
                check();
                if (editor == null || dialog == null) throw cancelled();
                Editable text = editor.getText();
                if (oversizedInput || text == null || text.length() == 0 || text.length() > maximumInputChars) throw SecretRequest.invalid();
                value = new char[text.length()]; text.getChars(0, text.length(), value, 0);
                text.clear();
                check(); acceptInput(value); value = null;
                cleanupUi();
                enqueue(operation -> {
                    char[] captured = operation.takeInput();
                    try { operation.check(); return operation.storage().capture(prepared.capture, captured, guarded); }
                    finally { if (captured != null) Arrays.fill(captured, '\0'); }
                });
            } catch (Exception failure) { fail(asFactory(failure)); }
            finally { if (value != null) Arrays.fill(value, '\0'); }
        }
        void cleanupUi() {
            main.removeCallbacks(watch);
            AlertDialog oldDialog = dialog; dialog = null;
            EditText oldEditor = editor; editor = null;
            try { if (oldEditor != null) { oldEditor.setFilters(new InputFilter[0]); Editable text = oldEditor.getText(); if (text != null) text.clear(); oldEditor.clearFocus(); } }
            finally {
                try { if (oldDialog != null) oldDialog.dismiss(); }
                finally {
                    try { if (addedSecureFlag && activityWindow != null) activityWindow.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); }
                    finally {
                        activityWindow = null; addedSecureFlag = false;
                        if (uiClaimed) { uiClaimed = false; callback.releaseUi(); }
                    }
                }
            }
        }
    }
    private static FactoryException cancelled() {
        return new FactoryException("CANCELLED", "Secret operation ended; earlier storage or network effects are not undone.");
    }
    private static FactoryException unavailable() {
        return new FactoryException("UNAVAILABLE", "Native secret operation is unavailable.");
    }
    private static FactoryException asFactory(Exception failure) {
        if (failure instanceof FactoryException) return (FactoryException) failure;
        if (failure instanceof java.util.concurrent.RejectedExecutionException)
            return new FactoryException("BUSY", "Secret operation worker is busy.");
        if (failure instanceof SecurityException)
            return new FactoryException("PERMISSION_DENIED", "Secret operation authority ended; earlier storage or network effects are not undone.");
        return new FactoryException("NATIVE_ERROR", "Native secret operation could not complete.");
    }
}
