package com.jarvys.factory.runtime;

import com.jarvys.factory.contract.ResourceBudgets;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.json.JSONException;
import org.json.JSONObject;

/** Owns recording admission and verified cleanup until an opaque read handle is delivered. */
final class RecordingEffects implements AutoCloseable {
    interface Callback {
        boolean current();
        /** Delivery must run exactly one continuation, even when the original page disappeared. */
        void complete(JSONObject result, Runnable delivered, Runnable rejected);
        void error(FactoryException failure);
    }
    private static final Executor CLEANUP = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(128), task -> {
                Thread thread = new Thread(task, "factory-recording-discard"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final NativeAudioRecording nativeRecording;
    private final Supplier<DocumentHandles> documents;
    private final ResourceBudgets.Documents budget;
    private final Executor cleanup;
    private volatile Pending active;
    private volatile boolean closed, cleanupFailed;

    RecordingEffects(NativeAudioRecording nativeRecording, Supplier<DocumentHandles> documents,
                     ResourceBudgets budgets) {
        this(nativeRecording, documents, budgets, CLEANUP);
    }
    /** Cleanup executor must enqueue asynchronously, never run inline on a main/Binder caller. */
    RecordingEffects(NativeAudioRecording nativeRecording, Supplier<DocumentHandles> documents,
                     ResourceBudgets budgets, Executor cleanup) {
        if (nativeRecording == null || documents == null || budgets == null || cleanup == null)
            throw new IllegalArgumentException("Recording dependencies are required.");
        this.nativeRecording = nativeRecording; this.documents = documents;
        this.budget = budgets.documents; this.cleanup = cleanup;
    }

    JSONObject status() {
        JSONObject result = nativeRecording.status();
        try {
            result.put("busy", active != null || result.optBoolean("busy"));
            result.put("cleanupConfirmed", !cleanupFailed && result.optBoolean("cleanupConfirmed"));
            if (closed) result.put("status", "closed").put("available", false);
            if (cleanupFailed) result.put("status", "error").put("available", false).put("error", "CLEANUP_FAILED");
            return result;
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    Handle record(RecordingRequest.Record request, NativeOperationAuthorization.Access access,
                  NativeAudioRecording.Foreground foreground, Callback callback) throws FactoryException {
        if (request == null || access == null || foreground == null || callback == null)
            throw new IllegalArgumentException("Recording request and live authority are required.");
        // Never acquire document capacity before the unified policy has actually allowed this job.
        access.check();
        if (!foreground.active() || !callback.current()) throw cancelled();
        if (request.durationMs >= budget.leaseMs)
            throw new FactoryException("DOCUMENT_QUOTA", "Recording duration must fit within the document lease.");
        DocumentHandles owner = documents.get();
        if (owner == null) throw new FactoryException("UNAVAILABLE", "Recording document storage is unavailable.");
        Pending pending = new Pending(owner, access, foreground, callback);
        synchronized (this) {
            if (closed) throw cancelled();
            if (cleanupFailed) throw cleanupError();
            if (active != null) throw new FactoryException("BUSY", "Another recording is active or awaiting cleanup.");
            active = pending;
        }
        try {
            pending.check();
            pending.slot = owner.reserveRead();
            pending.slot.beginOpen();
            pending.bytes = owner.reserveNativeBytes(request.maximumFileBytes());
            pending.check();
            NativeAudioRecording.Handle handle = nativeRecording.record(request, access, pending::current,
                    new NativeAudioRecording.Callback() {
                        public boolean complete(OwnedRecording output) {
                            pending.accept(output);
                            // Ownership always transfers, including stale results. Only our explicit
                            // verified close may refund the admission/native-byte reservations.
                            return true;
                        }
                        public void error(FactoryException failure) { pending.failed(failure); }
                    });
            pending.nativeHandle = handle;
            if (pending.cancelled.get()) handle.cancel();
            return new Handle(pending);
        } catch (FactoryException | RuntimeException failure) {
            // record() did not accept capture; there is no provider/file to wait for in this path.
            pending.releaseUnstarted();
            throw failure;
        }
    }

    final class Handle {
        private final Pending pending;
        private Handle(Pending pending) { this.pending = pending; }
        boolean stop() {
            NativeAudioRecording.Handle handle = pending.nativeHandle;
            return !pending.cancelled.get() && !pending.transferred && handle != null && handle.stop();
        }
        boolean cancel() { return pending.cancel(); }
    }
    boolean stop() {
        Pending pending = active;
        return pending != null && new Handle(pending).stop();
    }
    boolean cancel() {
        Pending pending = active;
        return pending != null && pending.cancel();
    }
    @Override public void close() {
        closed = true;
        cancel();
        nativeRecording.close();
    }
    private void retire(Pending pending) {
        synchronized (this) { if (active == pending) active = null; }
    }
    private final class Pending {
        final DocumentHandles owner;
        final NativeOperationAuthorization.Access access;
        final NativeAudioRecording.Foreground foreground;
        final Callback callback;
        final AtomicBoolean cancelled = new AtomicBoolean(), cleanupStarted = new AtomicBoolean();
        volatile DocumentHandles.Reservation slot;
        volatile DocumentHandles.NativeByteReservation bytes;
        volatile NativeAudioRecording.Handle nativeHandle;
        volatile OwnedRecording output;
        volatile String token;
        volatile boolean transferred, terminal;
        Pending(DocumentHandles owner, NativeOperationAuthorization.Access access,
                NativeAudioRecording.Foreground foreground, Callback callback) {
            this.owner = owner; this.access = access; this.foreground = foreground; this.callback = callback;
        }
        boolean current() {
            return !closed && !cancelled.get() && !terminal && !transferred && active == this
                    && foreground.active() && callback.current() && documents.get() == owner;
        }
        void check() throws FactoryException {
            if (!current()) throw cancelled();
            access.check();
            if (!current()) throw cancelled();
        }
        void releaseUnstarted() {
            if (bytes != null) bytes.close();
            if (slot != null) { slot.abortOpen(); slot.cancel(); }
            terminal = true; retire(this);
        }
        /** Called by the recording worker, never by main/Binder cancellation. */
        void accept(OwnedRecording artifact) {
            output = artifact;
            try {
                check();
                JSONObject metadata = artifact.metadata();
                final InputStream input = artifact.openReadOnce();
                InputStream owned = new FilterInputStream(input) {
                    @Override public void close() throws IOException {
                        try {
                            artifact.close();
                            bytes.close(); // A committed acquisition remains charged permanently.
                        } catch (IOException | RuntimeException failure) {
                            cleanupFailed = true;
                            throw new IOException("Recording cleanup is unconfirmed.");
                        }
                    }
                };
                check();
                token = slot.grantRead(owned);
                bytes.commit(metadata.getLong("bytes"));
                check();
                callback.complete(metadata.put("handle", token), this::delivered, this::cancel);
            } catch (Exception failure) {
                // The adapter accepted ownership before checking page/document freshness. Even a
                // stale artifact must be explicitly discarded and confirmed on this worker.
                discard();
                notifyError(cleanupFailed ? cleanupError() : asFactory(failure));
            }
        }
        /** Called only after the native helper has attempted driver/file cleanup. */
        void failed(FactoryException failure) {
            if ("CLEANUP_FAILED".equals(failure.code)) {
                cleanupFailed = true; // Retain both claims. No verified cleanup receipt will follow.
            } else {
                if (bytes != null) bytes.close();
                if (slot != null) { slot.abortOpen(); slot.cancel(); }
                terminal = true; retire(this);
            }
            notifyError(failure);
        }
        void notifyError(FactoryException failure) {
            try { callback.error(failure); } catch (RuntimeException ignored) { }
        }
        void delivered() {
            synchronized (this) {
                if (cancelled.get() || terminal) return;
                transferred = true;
            }
            // The document authority is now the sole owner of the stream. It closes asynchronously
            // on explicit close, lease expiry or page revocation; no recording hook may recall it.
            retire(this);
        }
        boolean cancel() {
            synchronized (this) {
                if (transferred || terminal || !cancelled.compareAndSet(false, true)) return false;
            }
            NativeAudioRecording.Handle handle = nativeHandle;
            if (handle != null) handle.cancel();
            if (output != null) {
                try { cleanup.execute(this::discard); }
                catch (RuntimeException rejected) { cleanupFailed = true; notifyError(cleanupError()); }
            }
            return true;
        }
        /** Worker-only: retain the slot and uncommitted bytes until deletion actually succeeds. */
        void discard() {
            if (transferred || !cleanupStarted.compareAndSet(false, true)) return;
            try {
                OwnedRecording artifact = output;
                if (artifact == null) return; // Native callback still owns cleanup and its receipt.
                artifact.close();
                if (bytes != null) bytes.close();
                if (token != null) {
                    try { owner.close(token); } catch (FactoryException alreadyRetired) { /* Expiry/revocation queues close. */ }
                } else if (slot != null) {
                    // A stale grant may already have handed its wrapped stream to document cleanup.
                    slot.abortOpen(); slot.cancel();
                }
                terminal = true; retire(this);
            } catch (IOException | RuntimeException unconfirmed) {
                cleanupFailed = true;
                notifyError(cleanupError());
            }
        }
    }
    private static FactoryException asFactory(Exception failure) {
        if (failure instanceof FactoryException) return (FactoryException) failure;
        if (failure instanceof SecurityException)
            return new FactoryException("PERMISSION_DENIED", "Recording authorization ended.");
        return new FactoryException("RECORDING_IO", "Recording could not be delivered.");
    }
    private static FactoryException cancelled() {
        return new FactoryException("CANCELLED", "Recording requires its current foreground page.");
    }
    private static FactoryException cleanupError() {
        return new FactoryException("CLEANUP_FAILED", "Recording cleanup is unconfirmed; further captures are disabled.");
    }
}
