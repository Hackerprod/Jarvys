package com.jarvys.factory.runtime;

import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One native-owned next-tag session. Tests replace the backend, clock, queues and admission ledger.
 * All state transitions synchronize on this instance; no blocking tag I/O holds its monitor.
 * The hardware slot survives cancellation until unregister, final close and worker exit are known.
 */
public final class NfcSessionMachine implements AutoCloseable {
    public interface Authority { void check(); }
    public interface Callback { void complete(JSONObject result); void error(FactoryException failure); }
    public interface Handle { void cancel(); }
    public interface Ticket { void cancel(); }
    public interface Loop { void post(Runnable work); Ticket later(Runnable work, long delayMs); long nowMs(); }
    public interface Reader { void enable(); void disable(); }
    public interface Discovery { void found(Tag tag); }
    public interface Backend {
        /** Pure discovery/state only. No RF probe, enable, permission prompt, or settings launch. */
        Readiness readiness();
        /** Allocate cleanup ownership before enable can register or throw. */
        Reader create(Discovery callback);
    }
    public static final class Readiness {
        public final boolean available, enabled, permission, foreground;
        public Readiness(boolean available, boolean enabled, boolean permission, boolean foreground) {
            this.available = available; this.enabled = enabled; this.permission = permission; this.foreground = foreground;
        }
    }
    public interface WriteBoundary { void beforeWrite() throws FactoryException; }
    public interface Tag {
        void connect(boolean write, boolean allowFormat) throws FactoryException;
        NfcRequest.Message read(NfcRequest.Limits limits) throws FactoryException;
        /** Must invoke boundary immediately before the one irreversible write/format call. */
        boolean write(NfcRequest.Message message, boolean allowFormat, WriteBoundary boundary) throws FactoryException;
        /** Must abort blocked I/O; may be invoked again after the I/O worker exits. */
        void close() throws FactoryException;
    }
    private final Backend backend;
    private final Loop loop;
    private final Executor io, cleanup;
    private final Semaphore admission;
    private final NfcRequest.Limits limits;
    private Session active;
    private boolean closed, cleanupFailed;
    private String state = "idle", lastError = "", lastWriteOutcome = "not_started";

    public NfcSessionMachine(Backend backend, Loop loop, Executor io, Executor cleanup,
                             Semaphore admission, NfcRequest.Limits limits) {
        if (backend == null || loop == null || io == null || cleanup == null || admission == null || limits == null)
            throw new IllegalArgumentException("NFC dependencies are required.");
        this.backend = backend; this.loop = loop; this.io = io; this.cleanup = cleanup;
        this.admission = admission; this.limits = limits;
    }
    public synchronized JSONObject status() {
        Readiness ready;
        boolean known = true;
        try { ready = backend.readiness(); } catch (RuntimeException error) { known = false; ready = new Readiness(false, false, false, false); }
        try { return new JSONObject().put("status", closed ? "closed" : state).put("busy", active != null)
                .put("available", ready.available).put("enabled", ready.enabled).put("nfcPermissionGranted", ready.permission)
                .put("foreground", ready.foreground).put("stateKnown", known).put("foregroundOnly", true)
                .put("cleanupFailed", cleanupFailed).put("error", lastError).put("lastWriteOutcome", lastWriteOutcome)
                .put("limits", new JSONObject().put("messageBytes", limits.messageBytes).put("records", limits.records).put("timeoutMs", limits.timeoutMs)); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    public synchronized Handle start(NfcRequest.Operation request, Authority authority, Callback callback) throws FactoryException {
        NfcRequest.validate(request, limits);
        if (authority == null || callback == null) throw new IllegalArgumentException("Live authorization and callback required.");
        if (closed) throw error("CANCELLED");
        if (active != null || cleanupFailed) throw error(cleanupFailed ? "CLEANUP_FAILED" : "BUSY");
        authority.check(); checkReady();
        if (closed) throw error("CANCELLED");
        if (active != null || cleanupFailed) throw error(cleanupFailed ? "CLEANUP_FAILED" : "BUSY");
        if (!admission.tryAcquire()) throw error("BUSY");
        Session session = new Session(request, authority, callback, loop.nowMs());
        active = session; state = "waiting_for_tag"; lastError = ""; lastWriteOutcome = "not_started";
        try { loop.post(() -> begin(session)); }
        catch (RuntimeException rejected) { terminate(session, error("UNAVAILABLE"), null); }
        return () -> cancel(session);
    }
    public synchronized JSONObject cancel() {
        boolean cancelled = active != null && !active.delivered;
        if (active != null) cancel(active);
        try { return new JSONObject().put("status", "cancelled").put("cancelled", cancelled)
                .put("draining", active != null).put("writeOutcome", lastWriteOutcome).put("rollback", false); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private synchronized void cancel(Session session) { if (active == session && !session.delivered) terminate(session, error("CANCELLED"), null); }
    @Override public synchronized void close() {
        closed = true;
        Session session = active;
        if (session != null) {
            if (!session.delivered) terminate(session, error("CANCELLED"), null);
            // Explicit owner destruction also retries a failed cleanup; never re-enables hardware.
            if (active == session) { requestReaderStop(session); requestTagClose(session); }
        }
    }
    private synchronized void begin(Session session) {
        if (active != session || session.terminal) return;
        try {
            check(session);
            session.timeout = loop.later(() -> timeout(session), session.request.timeoutMs - (loop.nowMs() - session.started));
            session.poll = loop.later(() -> poll(session), 250);
            session.reader = backend.create(tag -> discovered(session, tag));
            if (session.reader == null) throw error("UNAVAILABLE");
            check(session); session.enabling = true;
            try { session.reader.enable(); }
            finally { session.enabling = false; if (session.terminal) requestReaderStop(session); }
            check(session);
        } catch (Exception failure) { terminate(session, failure(failure), null); }
    }
    private synchronized void discovered(Session session, Tag tag) {
        if (active != session || session.terminal || session.tag != null || tag == null) return;
        try {
            check(session); session.tag = tag; session.ioFinished = false; state = "processing_tag";
            try { io.execute(() -> runIo(session)); }
            catch (RuntimeException rejected) { session.ioFinished = true; throw error("BUSY"); }
        } catch (Exception failure) { terminate(session, failure(failure), null); }
    }
    private void runIo(Session session) {
        FactoryException failure = null; JSONObject result = null;
        try {
            check(session); session.tag.connect(session.request.write, session.request.allowFormat); check(session);
            if (session.request.write) {
                boolean formatted = session.tag.write(session.request.message, session.request.allowFormat, () -> {
                    synchronized (NfcSessionMachine.this) {
                        check(session); session.writeAttempted = true; lastWriteOutcome = "unknown";
                    }
                });
                synchronized (this) { session.writeConfirmed = true; lastWriteOutcome = "confirmed"; }
                result = new JSONObject().put("status", "written").put("formatted", formatted)
                        .put("byteLength", session.request.message.byteLength).put("writeOutcome", "confirmed").put("rollback", false);
            } else {
                NfcRequest.Message message = session.tag.read(limits); check(session);
                if (message == null) throw error("MALFORMED_NDEF");
                // Revalidate data from an injected or future backend too.
                NfcRequest.records(message.records, limits);
                result = new JSONObject().put("status", message.records.isEmpty() ? "empty" : "read")
                        .put("records", message.json()).put("byteLength", message.byteLength);
            }
            result.put("foregroundOnly", true); check(session);
        } catch (Exception caught) { failure = failure(caught); }
        finally {
            synchronized (this) {
                session.ioFinished = true;
                if (active == session) {
                    if (!session.terminal) terminate(session, failure, result);
                    else { requestTagClose(session); finishIfClean(session); }
                }
            }
        }
    }
    private synchronized void timeout(Session session) { if (active == session && !session.delivered) terminate(session, error("TIMEOUT"), null); }
    private synchronized void poll(Session session) {
        if (active != session || session.terminal) return;
        try { check(session); session.poll = loop.later(() -> poll(session), 250); }
        catch (Exception failure) { terminate(session, failure(failure), null); }
    }
    private synchronized void check(Session session) throws FactoryException {
        if (active != session || session.terminal || closed) throw error("CANCELLED");
        session.authority.check(); checkReady();
        if (active != session || session.terminal || closed) throw error("CANCELLED");
        long now = loop.nowMs();
        if (now < session.started || now - session.started >= session.request.timeoutMs) throw error("TIMEOUT");
    }
    private void checkReady() throws FactoryException {
        Readiness ready = backend.readiness();
        if (!ready.permission) throw error("PERMISSION_DENIED");
        if (!ready.foreground) throw error("FOREGROUND_REQUIRED");
        if (!ready.available) throw error("UNAVAILABLE");
        if (!ready.enabled) throw error("NFC_DISABLED");
    }
    private void terminate(Session session, FactoryException failure, JSONObject result) {
        if (active != session || session.delivered) return;
        session.terminal = true;
        if (failure != null) session.failure = failure;
        if (result != null && session.failure == null) session.result = result;
        if (failure != null && session.timeout != null) session.timeout.cancel();
        if (session.poll != null) session.poll.cancel();
        state = "draining";
        requestReaderStop(session); requestTagClose(session);
        if (session.failure != null) deliverError(session); // Cancellation must not wait for a wedged driver.
        finishIfClean(session);
    }
    private void requestReaderStop(Session session) {
        if (session.enabling || session.readerStopping || session.readerStopped) return;
        if (session.reader == null) { session.readerStopped = true; return; }
        session.readerStopping = true;
        try { loop.post(() -> {
            synchronized (NfcSessionMachine.this) {
                try { session.reader.disable(); session.readerStopped = true; }
                catch (RuntimeException failure) { cleanupFailure(session); }
                finally { session.readerStopping = false; finishIfClean(session); }
            }
        }); } catch (RuntimeException rejected) { session.readerStopping = false; cleanupFailure(session); }
    }
    private void requestTagClose(Session session) {
        if (session.tag == null) { session.tagClosed = true; return; }
        if (session.closing || session.tagClosed) return;
        // Only one abort attempt while IO is blocked. A final close happens after worker exit.
        if (!session.ioFinished && session.abortAttempted) return;
        boolean finalPass = session.ioFinished; session.closing = true;
        if (!finalPass) session.abortAttempted = true;
        try { cleanup.execute(() -> {
            boolean ok = false;
            try { session.tag.close(); ok = true; } catch (Exception ignored) { }
            synchronized (NfcSessionMachine.this) {
                session.closing = false;
                if (ok && finalPass) session.tagClosed = true;
                if (!ok) cleanupFailure(session);
                if (!finalPass && session.ioFinished) requestTagClose(session);
                finishIfClean(session);
            }
        }); } catch (RuntimeException rejected) { session.closing = false; cleanupFailure(session); }
    }
    private void cleanupFailure(Session session) {
        cleanupFailed = true; lastError = "CLEANUP_FAILED";
        if (!session.delivered) { session.failure = error("CLEANUP_FAILED"); deliverError(session); }
    }
    private void finishIfClean(Session session) {
        if (active != session || !session.terminal || session.enabling || !session.readerStopped
                || !session.ioFinished || !session.tagClosed || session.closing) return;
        if (session.delivered) { release(session); return; }
        if (session.deliveryQueued) return;
        session.deliveryQueued = true;
        // Retain ownership until queued disclosure, so cancel/page changes can suppress it.
        try { loop.post(() -> {
            synchronized (NfcSessionMachine.this) {
                if (active != session) return;
                try {
                    if (session.delivered) return;
                    if (closed) throw error("CANCELLED"); session.authority.check(); checkReady();
                    if (session.failure != null) throw session.failure;
                    long now = loop.nowMs();
                    if (now < session.started || now - session.started >= session.request.timeoutMs) throw error("TIMEOUT");
                    session.delivered = true;
                    JSONObject result = session.result;
                    release(session);
                    session.callback.complete(result);
                } catch (Exception failure) { session.failure = failure(failure); deliverError(session); }
                finally { release(session); }
            }
        }); } catch (RuntimeException rejected) { session.failure = error("UNAVAILABLE"); deliverError(session); release(session); }
    }
    private void release(Session session) {
        if (active != session) return;
        if (session.timeout != null) session.timeout.cancel();
        if (session.poll != null) session.poll.cancel();
        active = null; admission.release(); cleanupFailed = false;
        state = session.failure == null ? "completed" : "CANCELLED".equals(session.failure.code) ? "cancelled" : "error";
    }
    private void deliverError(Session session) {
        if (session.delivered) return;
        FactoryException error = session.failure == null ? error("NFC_ERROR") : session.failure;
        if (session.writeAttempted) error = new FactoryException(error.code,
                "NFC write may already have changed the tag; cancellation does not restore earlier contents.");
        final FactoryException safe = error; session.delivered = true; lastError = safe.code;
        Runnable notify = () -> { try { session.callback.error(safe); } catch (RuntimeException ignored) { /* Observer failures cannot resurrect ownership. */ } };
        try { loop.post(notify); } catch (RuntimeException rejected) { notify.run(); }
    }
    private static FactoryException failure(Exception failure) {
        if (failure instanceof FactoryException) return (FactoryException) failure;
        if (failure instanceof SecurityException) return error("PERMISSION_DENIED");
        return error("NFC_ERROR");
    }
    static FactoryException error(String code) { return new FactoryException(code, "NFC foreground NDEF operation could not complete (" + code + ")."); }
    private static final class Session {
        final NfcRequest.Operation request; final Authority authority; final Callback callback; final long started;
        Reader reader; Tag tag; Ticket timeout, poll; JSONObject result; FactoryException failure;
        boolean terminal, delivered, deliveryQueued, enabling, readerStopping, readerStopped, tagClosed, closing, abortAttempted;
        boolean writeAttempted, writeConfirmed, ioFinished = true;
        Session(NfcRequest.Operation request, Authority authority, Callback callback, long started) {
            this.request = request; this.authority = authority; this.callback = callback; this.started = started;
        }
    }
}
