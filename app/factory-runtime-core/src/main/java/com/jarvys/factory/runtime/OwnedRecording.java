package com.jarvys.factory.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.json.JSONException;
import org.json.JSONObject;

/** Native-only ownership of one private capture. Never serialize this object's file or a URI. */
public final class OwnedRecording implements AutoCloseable {
    private final File file;
    private final long bytes, frames, elapsedMs;
    private final int sampleRate, channels;
    private final String reason;
    private InputStream source;
    private boolean opened, closed;

    OwnedRecording(File file, long bytes, long frames, long elapsedMs, int sampleRate, int channels, String reason) {
        this.file = file; this.bytes = bytes; this.frames = frames; this.elapsedMs = elapsedMs;
        this.sampleRate = sampleRate; this.channels = channels; this.reason = reason;
    }
    /** Safe metadata only. PCM data does not establish speech, audibility, or microphone fidelity. */
    public JSONObject metadata() {
        try {
            return new JSONObject().put("format", "wav-pcm16").put("mimeType", "audio/wav")
                    .put("bytes", bytes).put("durationMs", (frames * 1000L + sampleRate - 1) / sampleRate)
                    .put("elapsedMs", elapsedMs).put("sampleRate", sampleRate).put("channels", channels)
                    .put("reason", reason).put("simulated", false).put("contentVerified", false)
                    .put("networkUsed", false);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    /**
     * Transfers sequential read ownership once. Register this stream as a native safe document
     * handle. If registration fails, close it. Closing the stream closes/discards the artifact.
     * No authority is checked here: the caller must perform its live delivery/grant check.
     */
    public synchronized InputStream openReadOnce() throws IOException {
        if (closed || opened) throw new IOException("Recording is no longer available for transfer.");
        final FileInputStream input;
        try { input = new FileInputStream(file); }
        catch (IOException | SecurityException unavailable) { throw new IOException("Private recording stream is unavailable."); }
        opened = true;
        // Keep the raw input for close(), avoiding recursive FilterInputStream.close().
        source = input;
        return new FilterInputStream(input) {
            @Override public void close() throws IOException { OwnedRecording.this.close(); }
        };
    }
    /** Also safe before a stream was requested, after a rejected grant, or during page teardown. */
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        IOException failure = null;
        if (source != null) {
            try { source.close(); source = null; }
            catch (IOException | RuntimeException problem) { failure = new IOException("Private recording stream cleanup is unconfirmed."); }
        }
        try {
            if (file.exists() && !file.delete()) failure = new IOException("Private recording cleanup is unconfirmed.");
        } catch (SecurityException denied) { failure = new IOException("Private recording cleanup is unconfirmed."); }
        if (failure != null) throw failure;
        closed = true;
    }
}
