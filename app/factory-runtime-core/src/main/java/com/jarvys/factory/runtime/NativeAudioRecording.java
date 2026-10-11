package com.jarvys.factory.runtime;

import android.Manifest;
import androidx.annotation.RequiresPermission;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Foreground/current-page finite microphone capture into a private canonical PCM16 WAV file.
 * The worker thread is only for bounded local I/O, never a background recording service.
 * Android RECORD_AUDIO, the unified live lease and current foreground page must all remain valid.
 * Caller must cancel on pause, page replacement and revocation; polling is only a fallback.
 * No permission request, network, arbitrary path, engine fallback, or whole-recording byte array.
 */
public final class NativeAudioRecording implements AutoCloseable {
    @FunctionalInterface public interface Foreground { boolean active(); }
    public interface Callback {
        /** True transfers artifact ownership; false or an exception auto-discards it. */
        boolean complete(OwnedRecording recording);
        void error(FactoryException error);
    }
    interface Clock { long millis(); }
    interface Permission { boolean granted(); boolean available(); }
    interface Scheduler {
        /** Must enqueue, never invoke inline. */
        void post(Runnable task, long delayMs);
        void remove(Runnable task);
        void close();
    }
    interface DriverFactory { Driver create(RecordingRequest.Record request) throws Exception; }
    interface Driver {
        boolean initialized();
        int sampleRate();
        int channels();
        void start();
        boolean recording();
        /** Nonblocking PCM16 samples; negative Android error codes must be preserved. */
        int read(short[] samples, int maximumSamples);
        void stop();
        void release();
    }
    static final int CHUNK_SAMPLES = 4096;
    static final long POLL_MS = 20;
    private final File directory;
    private final DriverFactory drivers;
    private final Permission permission;
    private final Scheduler scheduler;
    private final Clock clock;
    /** Only short state transitions use this lock; never a native call, disk I/O or callback. */
    private final Object lock = new Object();
    private volatile Work active;
    private volatile boolean closed, cleanupFailed;
    private volatile String state = "idle", lastError = "";

    /** Installed application only. Construction does not access or open the microphone. */
    public NativeAudioRecording(Context context, String appId) {
        this(privateDirectory(context, appId), PlatformDriver::new, platformPermission(context),
                new HandlerScheduler(), SystemClock::elapsedRealtime);
    }
    /** All tests inject a fake driver/permission/scheduler; no real microphone is touched. */
    NativeAudioRecording(File directory, DriverFactory drivers, Permission permission, Scheduler scheduler, Clock clock) {
        if (directory == null || drivers == null || permission == null || scheduler == null || clock == null)
            throw new IllegalArgumentException("Recording dependencies are required.");
        this.directory = directory; this.drivers = drivers; this.permission = permission;
        this.scheduler = scheduler; this.clock = clock;
    }

    public JSONObject status() {
        try {
            return new JSONObject().put("status", closed ? "closed" : state).put("busy", active != null)
                    .put("available", !closed && !cleanupFailed && permission.available())
                    .put("permissionGranted", permission.granted()).put("cleanupConfirmed", !cleanupFailed)
                    .put("format", "wav-pcm16").put("foregroundOnly", true).put("networkUsed", false)
                    .put("contentVerified", false).put("error", lastError);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    public Handle record(RecordingRequest.Record request, NativeOperationAuthorization.Access authorization,
                                      Foreground foreground, Callback callback) throws FactoryException {
        if (request == null || callback == null || foreground == null)
            throw new IllegalArgumentException("Recording request, foreground guard and callback are required.");
        require(authorization, foreground);
        if (!permission.available()) throw unavailable();
        synchronized (lock) {
            if (closed) throw error("CANCELLED", "Recording lifecycle has ended.");
            if (cleanupFailed) throw cleanupError();
            if (active != null) throw error("BUSY", "Another recording is active.");
            Work work = new Work(request, authorization, foreground, callback);
            active = work; state = "starting"; lastError = "";
            try { scheduler.post(() -> guarded(work, () -> initialize(work)), 0); }
            catch (RuntimeException failed) {
                active = null; state = "error"; lastError = "RECORDING_UNAVAILABLE";
                throw unavailable();
            }
            return new Handle(work);
        }
    }

    /** Request-scoped control. An old handle never stops/cancels a newer recording. */
    public final class Handle {
        private final Work work;
        private Handle(Work work) { this.work = work; }
        /** Ends early and retains valid captured PCM. Existing authority is rechecked at delivery. */
        public boolean stop() {
            synchronized (lock) {
                if (active != work || work.finishing || work.stopRequested || work.cancelled || closed) return false;
                work.stopRequested = true;
                try { scheduler.post(() -> guarded(work, () -> finalizeRecording(work, "stopped")), 0); }
                catch (RuntimeException failed) { workerUnavailable(); }
                return true;
            }
        }
        /** Cleanup is allowed after revocation; there is no new lease/approval to cancel own work. */
        public boolean cancel() {
            synchronized (lock) {
                if (active != work || work.cancelled) return false;
                work.cancelled = true;
                try { scheduler.post(() -> fail(work, error("CANCELLED", "Recording was cancelled.")), 0); }
                catch (RuntimeException failed) { workerUnavailable(); }
                return true;
            }
        }
    }

    /** Terminal, idempotent lifecycle cleanup, independent of permission or lease state. */
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            Work work = active;
            if (work != null) work.cancelled = true;
            try { scheduler.post(() -> {
                if (work != null) fail(work, error("CANCELLED", "Recording lifecycle ended."));
                scheduler.close();
            }, 0); }
            catch (RuntimeException failed) { workerUnavailable(); }
        }
    }

    private void initialize(Work work) throws Exception {
        if (work.stopRequested) { finalizeRecording(work, "stopped"); return; }
        check(work);
        if (!permission.available()) throw unavailable();
        if (!directory.isDirectory() && !directory.mkdirs())
            throw error("RECORDING_IO", "Private recording storage is unavailable.");
        check(work);
        work.file = File.createTempFile("capture-", ".wav", directory);
        work.output = new RandomAccessFile(work.file, "rw");
        // A fixed 44-byte header is replaced only after recording stops cleanly.
        writeHeader(work.output, work.request, 0);
        check(work);
        if (work.stopRequested) { finalizeRecording(work, "stopped"); return; }
        work.driver = drivers.create(work.request);
        if (work.driver == null || !work.driver.initialized() ||
                work.driver.sampleRate() != work.request.sampleRate || work.driver.channels() != work.request.channels)
            throw unavailable();
        check(work);
        if (work.stopRequested) { finalizeRecording(work, "stopped"); return; }
        work.startAttempted = true;
        work.driver.start();
        if (!work.driver.recording()) throw unavailable();
        work.startedAt = clock.millis(); work.started = true;
        state = "recording";
        check(work);
        if (work.stopRequested) { finalizeRecording(work, "stopped"); return; }
        scheduler.post(work.tick, 0);
    }

    private void read(Work work) throws Exception {
        if (work.finishing) return;
        if (work.stopRequested) { finalizeRecording(work, "stopped"); return; }
        check(work);
        long elapsed = elapsed(work);
        if (elapsed >= work.request.durationMs) { finalizeRecording(work, "duration"); return; }
        if (!work.driver.recording()) throw error("RECORDING_ERROR", "Android stopped microphone capture.");
        long remaining = work.request.maximumFileBytes() - RecordingRequest.WAV_HEADER_BYTES - work.pcmBytes;
        if (remaining < work.request.channels * 2L) { finalizeRecording(work, limitReason(work)); return; }
        int samples = (int) Math.min(work.samples.length, remaining / 2L);
        samples -= samples % work.request.channels;
        check(work);
        int count = work.driver.read(work.samples, samples);
        if (count < 0 || count > samples || count % work.request.channels != 0)
            throw error("RECORDING_ERROR", "Android could not provide aligned PCM microphone data.");
        // Recheck after the platform call: revoked/stale samples must never be written or delivered.
        check(work);
        if (count > 0) {
            for (int i = 0; i < count; i++) {
                work.bytes[i * 2] = (byte) work.samples[i];
                work.bytes[i * 2 + 1] = (byte) (work.samples[i] >>> 8);
            }
            work.output.write(work.bytes, 0, count * 2);
            work.pcmBytes += count * 2L;
            Arrays.fill(work.samples, (short) 0); Arrays.fill(work.bytes, (byte) 0);
            if (work.pcmBytes + RecordingRequest.WAV_HEADER_BYTES >= work.request.maximumFileBytes()) {
                finalizeRecording(work, limitReason(work)); return;
            }
        }
        long untilDeadline = work.request.durationMs - elapsed(work);
        if (untilDeadline <= 0) finalizeRecording(work, "duration");
        else scheduler.post(work.tick, Math.min(POLL_MS, untilDeadline));
    }

    private void finalizeRecording(Work work, String reason) throws Exception {
        if (work.finishing) return;
        check(work);
        work.finishing = true;
        scheduler.remove(work.tick);
        RuntimeException shutdownError = releaseDriver(work);
        if (cleanupFailed) throw cleanupError();
        if (shutdownError != null) throw error("RECORDING_ERROR", "Android recording completion could not be confirmed.");
        if (!work.started || work.pcmBytes == 0)
            throw error("RECORDING_EMPTY", "No PCM microphone data was captured.");
        check(work);
        long expected = RecordingRequest.WAV_HEADER_BYTES + work.pcmBytes;
        if (expected > work.request.maximumFileBytes() || work.output.length() != expected)
            throw error("RECORDING_ERROR", "Private recording size did not match its declared bounds.");
        writeHeader(work.output, work.request, work.pcmBytes);
        work.output.close(); work.output = null;
        check(work);
        work.result = new OwnedRecording(work.file, expected, work.pcmBytes / (work.request.channels * 2L),
                elapsed(work), work.request.sampleRate, work.request.channels, reason);
        work.file = null; // OwnedRecording is now the only owner of that private path.
        state = "finalizing";
        scheduler.post(work.delivery, 0);
    }

    /** Still owned by this operation until the live checks and callback return true. */
    private void deliver(Work work) throws Exception {
        check(work);
        OwnedRecording result = work.result;
        if (result == null) throw error("RECORDING_ERROR", "Recording result is unavailable.");
        synchronized (lock) {
            // Atomically retire this request against nonblocking lifecycle/control cancellation.
            if (closed || work.cancelled || active != work) throw error("CANCELLED", "Recording lifecycle ended.");
            work.result = null;
            state = "completed"; lastError = "";
            active = null;
        }
        clearBuffers(work);
        boolean accepted = false;
        try { accepted = work.callback.complete(result); }
        catch (RuntimeException rejected) {
            // A completion callback already ran; never invoke it or error() a second time.
        } finally {
            if (!accepted) {
                try { result.close(); }
                catch (IOException failed) { cleanupFailed = true; }
                synchronized (lock) {
                    if (active == null) {
                        state = "error"; lastError = cleanupFailed ? "CLEANUP_FAILED" : "DELIVERY_REJECTED";
                    }
                }
            }
        }
    }

    private interface Action { void run() throws Exception; }
    private void guarded(Work work, Action action) {
        if (active != work) return;
        try { check(work); action.run(); }
        catch (SecurityException denied) { fail(work, error("PERMISSION_DENIED", "Recording authorization ended.")); }
        catch (FactoryException failure) { fail(work, failure); }
        catch (IOException failure) { fail(work, error("RECORDING_IO", "Private recording storage failed.")); }
        catch (Exception failed) { fail(work, unavailable()); }
    }
    private void require(NativeOperationAuthorization.Access authorization, Foreground foreground) throws FactoryException {
        if (authorization == null) throw new SecurityException("Live recording authorization is required.");
        authorization.check();
        if (!foreground.active()) throw error("CANCELLED", "Recording requires the current foreground page.");
        if (!permission.granted()) throw error("PERMISSION_DENIED", "Android microphone permission is not granted.");
    }
    private void check(Work work) throws FactoryException {
        if (closed || work.cancelled || active != work) throw error("CANCELLED", "Recording lifecycle has ended.");
        require(work.authorization, work.foreground);
        if (cleanupFailed) throw cleanupError();
        if (closed || work.cancelled || active != work) throw error("CANCELLED", "Recording lifecycle has ended.");
    }
    private long elapsed(Work work) throws FactoryException {
        long now = clock.millis();
        if (now < work.startedAt) throw error("RECORDING_ERROR", "Recording clock is unavailable.");
        return now - work.startedAt;
    }
    private static String limitReason(Work work) {
        long framesByBytes = (work.request.maxBytes - RecordingRequest.WAV_HEADER_BYTES) / (work.request.channels * 2L);
        long framesByDuration = work.request.durationMs * work.request.sampleRate / 1000L;
        return framesByBytes < framesByDuration ? "byte_limit" : "duration";
    }

    /** No authority check is allowed along cancellation/release/discard paths. */
    private void fail(Work work, FactoryException failure) {
        if (active != work) return;
        work.finishing = true;
        state = "cleaning";
        scheduler.remove(work.tick); scheduler.remove(work.delivery);
        releaseDriver(work);
        if (work.output != null) {
            try { work.output.close(); } catch (IOException | RuntimeException failed) { cleanupFailed = true; }
            work.output = null;
        }
        if (work.result != null) {
            try { work.result.close(); } catch (IOException failed) { cleanupFailed = true; }
            work.result = null;
        }
        if (work.file != null) {
            try { if (work.file.exists() && !work.file.delete()) cleanupFailed = true; }
            catch (SecurityException failed) { cleanupFailed = true; }
            work.file = null;
        }
        clearBuffers(work);
        FactoryException result = cleanupFailed ? cleanupError() : failure;
        synchronized (lock) {
            if (active != work) return;
            active = null; // Admission remains busy until release/discard has actually returned.
            state = "CANCELLED".equals(result.code) ? "cancelled" : "error"; lastError = result.code;
        }
        Runnable deliverError = () -> { try { work.callback.error(result); } catch (RuntimeException ignored) { } };
        try { scheduler.post(deliverError, 0); }
        catch (RuntimeException schedulerEnded) { deliverError.run(); }
    }
    private void workerUnavailable() {
        // Never perform platform/file cleanup on a main/Binder control thread as a fallback.
        // Preserve the busy slot and report uncertainty rather than permitting another capture.
        cleanupFailed = true; state = "error"; lastError = "CLEANUP_FAILED";
    }
    private RuntimeException releaseDriver(Work work) {
        Driver driver = work.driver;
        if (driver == null) return null;
        work.driver = null;
        RuntimeException failure = null;
        if (work.startAttempted) try { driver.stop(); } catch (RuntimeException failed) { failure = failed; }
        try { driver.release(); }
        catch (RuntimeException failed) { cleanupFailed = true; failure = failed; }
        return failure;
    }
    private static void clearBuffers(Work work) {
        Arrays.fill(work.samples, (short) 0); Arrays.fill(work.bytes, (byte) 0);
    }
    private static void writeHeader(RandomAccessFile out, RecordingRequest.Record request, long pcmBytes) throws IOException {
        out.seek(0);
        out.writeBytes("RIFF"); little(out, 36 + pcmBytes, 4); out.writeBytes("WAVEfmt "); little(out, 16, 4);
        little(out, 1, 2); little(out, request.channels, 2); little(out, request.sampleRate, 4);
        little(out, request.sampleRate * request.channels * 2L, 4); little(out, request.channels * 2, 2);
        little(out, 16, 2); out.writeBytes("data"); little(out, pcmBytes, 4);
    }
    private static void little(RandomAccessFile out, long value, int bytes) throws IOException {
        for (int i = 0; i < bytes; i++) out.write((int) (value >>> (i * 8)) & 255);
    }
    private static FactoryException error(String code, String message) { return new FactoryException(code, message); }
    private static FactoryException unavailable() { return error("RECORDING_UNAVAILABLE", "The requested Android microphone format is unavailable."); }
    private static FactoryException cleanupError() {
        return error("CLEANUP_FAILED", "Recording cleanup is unconfirmed; further captures are disabled.");
    }
    private final class Work {
        final RecordingRequest.Record request;
        final NativeOperationAuthorization.Access authorization;
        final Foreground foreground;
        final Callback callback;
        final short[] samples = new short[CHUNK_SAMPLES];
        final byte[] bytes = new byte[CHUNK_SAMPLES * 2];
        final Runnable tick = () -> guarded(this, () -> read(this));
        final Runnable delivery = () -> guarded(this, () -> deliver(this));
        Driver driver;
        File file;
        RandomAccessFile output;
        OwnedRecording result;
        long startedAt, pcmBytes;
        boolean started, startAttempted;
        volatile boolean finishing, stopRequested, cancelled;
        Work(RecordingRequest.Record request, NativeOperationAuthorization.Access authorization, Foreground foreground, Callback callback) {
            this.request = request; this.authorization = authorization; this.foreground = foreground; this.callback = callback;
        }
    }

    private static File privateDirectory(Context context, String appId) {
        if (context == null || appId == null || !appId.equals(context.getPackageName()))
            throw new IllegalArgumentException("Recording identity must match the installed package.");
        return new File(context.getCacheDir(), "factory-recordings");
    }
    private static Permission platformPermission(Context context) {
        Context application = context.getApplicationContext();
        Context owner = application == null ? context : application;
        return new Permission() {
            @Override public boolean granted() {
                return owner.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            }
            @Override public boolean available() {
                return owner.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE);
            }
        };
    }
    private static final class HandlerScheduler implements Scheduler {
        final HandlerThread thread = new HandlerThread("factory-recording");
        final Handler handler;
        HandlerScheduler() { thread.start(); handler = new Handler(thread.getLooper()); }
        @Override public void post(Runnable task, long delayMs) {
            if (!handler.postDelayed(task, delayMs)) throw new IllegalStateException("Recording worker ended.");
        }
        @Override public void remove(Runnable task) { handler.removeCallbacks(task); }
        @Override public void close() { thread.quitSafely(); }
    }
    private static final class PlatformDriver implements Driver {
        final AudioRecord recorder;
        // initialize() rechecks the current platform permission before invoking the delayed factory;
        // guarded() handles SecurityException if Android revokes it between that check and this call.
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        PlatformDriver(RecordingRequest.Record request) throws FactoryException {
            int mask = request.channels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
            int minimum = AudioRecord.getMinBufferSize(request.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
            // Device buffer is bounded too. No fallback silently changes the authorized format.
            if (minimum <= 0 || minimum > 512 * 1024) throw unavailable();
            int buffer = Math.max(CHUNK_SAMPLES * 4, minimum * 2);
            recorder = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(request.sampleRate).setChannelMask(mask).build())
                    .setBufferSizeInBytes(buffer).build();
        }
        @Override public boolean initialized() { return recorder.getState() == AudioRecord.STATE_INITIALIZED; }
        @Override public int sampleRate() { return recorder.getSampleRate(); }
        @Override public int channels() { return recorder.getChannelCount(); }
        @Override public void start() { recorder.startRecording(); }
        @Override public boolean recording() { return recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING; }
        @Override public int read(short[] samples, int maximumSamples) {
            return recorder.read(samples, 0, maximumSamples, AudioRecord.READ_NON_BLOCKING);
        }
        @Override public void stop() { recorder.stop(); }
        @Override public void release() { recorder.release(); }
    }
}
