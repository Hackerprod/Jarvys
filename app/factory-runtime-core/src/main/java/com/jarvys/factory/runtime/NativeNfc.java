package com.jarvys.factory.runtime;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TagLostException;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.nfc.tech.TagTechnology;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Direct Android framework adapter. Installed foreground Activity only; no .so, intent dispatch, or HCE. */
public final class NativeNfc implements AutoCloseable {
    private static final Semaphore PROCESS_READER = new Semaphore(1);
    private static final ThreadPoolExecutor IO = pool("factory-nfc-io"), CLEANUP = pool("factory-nfc-close");
    private final NfcSessionMachine machine;
    public NativeNfc(Activity activity, String appId, BooleanSupplier foreground, NfcRequest.Limits limits) {
        if (activity == null || appId == null || !appId.equals(activity.getPackageName()) || foreground == null || limits == null)
            throw new IllegalArgumentException("Installed package identity and lifecycle required.");
        Handler main = new Handler(Looper.getMainLooper());
        machine = new NfcSessionMachine(new PlatformBackend(activity, foreground), new NfcSessionMachine.Loop() {
            public void post(Runnable work) { if (!main.post(work)) throw new IllegalStateException("Main loop ended."); }
            public NfcSessionMachine.Ticket later(Runnable work, long delayMs) {
                if (!main.postDelayed(work, Math.max(0, delayMs))) throw new IllegalStateException("Main loop ended.");
                return () -> main.removeCallbacks(work);
            }
            public long nowMs() { return SystemClock.elapsedRealtime(); }
        }, IO, CLEANUP, PROCESS_READER, limits);
    }
    public JSONObject status() { return machine.status(); }
    public NfcSessionMachine.Handle execute(NfcRequest.Operation request, NativeOperationAuthorization.Access authority,
                                            NfcSessionMachine.Callback callback) throws FactoryException {
        if (authority == null) throw new SecurityException("NFC operation-specific live authorization required.");
        return machine.start(request, authority::check, callback);
    }
    public JSONObject cancel() { return machine.cancel(); }
    @Override public void close() { machine.close(); }
    private static ThreadPoolExecutor pool(String name) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(2),
                runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true); return executor;
    }
    private static final class PlatformBackend implements NfcSessionMachine.Backend {
        final Activity activity; final BooleanSupplier foreground;
        PlatformBackend(Activity activity, BooleanSupplier foreground) { this.activity = activity; this.foreground = foreground; }
        private NfcAdapter adapter() { return NfcAdapter.getDefaultAdapter(activity); }
        public NfcSessionMachine.Readiness readiness() {
            boolean permission = activity.checkSelfPermission(Manifest.permission.NFC) == PackageManager.PERMISSION_GRANTED;
            NfcAdapter adapter = adapter();
            return new NfcSessionMachine.Readiness(adapter != null, adapter != null && adapter.isEnabled(), permission,
                    foreground.getAsBoolean() && !activity.isFinishing() && !activity.isDestroyed());
        }
        public NfcSessionMachine.Reader create(NfcSessionMachine.Discovery callback) {
            NfcAdapter adapter = adapter();
            if (adapter == null) throw new IllegalStateException("NFC unavailable.");
            return new NfcSessionMachine.Reader() {
                public void enable() {
                    requireMain();
                    // Do not set SKIP_NDEF_CHECK: it prevents Ndef technology enumeration.
                    adapter.enableReaderMode(activity, tag -> callback.found(new PlatformTag(tag)),
                            NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                                    | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V, null);
                }
                public void disable() { requireMain(); adapter.disableReaderMode(activity); }
            };
        }
        private static void requireMain() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("NFC reader mode requires main loop."); }
    }
    private static final class PlatformTag implements NfcSessionMachine.Tag {
        final Ndef ndef; final NdefFormatable formatable;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile TagTechnology technology;
        PlatformTag(Tag tag) { ndef = Ndef.get(tag); formatable = NdefFormatable.get(tag); }
        public void connect(boolean write, boolean allowFormat) throws FactoryException {
            if (ndef != null) technology = ndef;
            else if (write && allowFormat && formatable != null) technology = formatable;
            else throw NfcSessionMachine.error("NDEF_UNSUPPORTED");
            if (cancelled.get()) throw NfcSessionMachine.error("CANCELLED");
            try {
                technology.connect();
                // close may race with connect before Android installs the connection; close again.
                if (cancelled.get()) { close(); throw NfcSessionMachine.error("CANCELLED"); }
            } catch (TagLostException lost) { throw NfcSessionMachine.error("TAG_LOST"); }
            catch (IOException failure) { throw NfcSessionMachine.error("NFC_IO_ERROR"); }
            catch (SecurityException invalid) { throw NfcSessionMachine.error("TAG_INVALIDATED"); }
        }
        public NfcRequest.Message read(NfcRequest.Limits limits) throws FactoryException {
            if (ndef == null) throw NfcSessionMachine.error("NDEF_UNSUPPORTED");
            try {
                NdefMessage value = ndef.getNdefMessage(); // Current RF read, never discovery cache.
                if (value == null) return NfcRequest.emptyMessage();
                if (value.getByteArrayLength() > limits.messageBytes) throw NfcRequest.tooLarge();
                NdefRecord[] records = value.getRecords();
                if (records.length > limits.records) throw NfcRequest.tooLarge();
                List<NfcRequest.Record> output = new ArrayList<>();
                for (NdefRecord record : records) output.add(NfcRequest.record(record.getTnf(), record.getType(), record.getId(), record.getPayload(), limits));
                return NfcRequest.records(output, limits);
            } catch (TagLostException lost) { throw NfcSessionMachine.error("TAG_LOST"); }
            catch (FormatException malformed) { throw NfcSessionMachine.error("MALFORMED_NDEF"); }
            catch (IOException failure) { throw NfcSessionMachine.error("NFC_IO_ERROR"); }
            catch (SecurityException invalid) { throw NfcSessionMachine.error("TAG_INVALIDATED"); }
        }
        public boolean write(NfcRequest.Message message, boolean allowFormat, NfcSessionMachine.WriteBoundary boundary) throws FactoryException {
            try {
                NdefRecord[] records = new NdefRecord[message.records.size()];
                for (int i = 0; i < records.length; i++) {
                    NfcRequest.Record record = message.records.get(i);
                    records[i] = new NdefRecord((short) record.tnf, record.type(), record.id(), record.payload());
                }
                NdefMessage output = new NdefMessage(records);
                if (output.getByteArrayLength() != message.byteLength) throw NfcSessionMachine.error("MALFORMED_NDEF");
                if (ndef != null) {
                    if (!ndef.isWritable()) throw NfcSessionMachine.error("TAG_READ_ONLY");
                    if (output.getByteArrayLength() > ndef.getMaxSize()) throw NfcSessionMachine.error("TAG_TOO_SMALL");
                    if (cancelled.get()) throw NfcSessionMachine.error("CANCELLED");
                    boundary.beforeWrite(); ndef.writeNdefMessage(output); return false;
                }
                if (!allowFormat || formatable == null) throw NfcSessionMachine.error("NDEF_UNSUPPORTED");
                if (cancelled.get()) throw NfcSessionMachine.error("CANCELLED");
                // Capacity is not exposed by NdefFormatable. Android/vendor support is conditional.
                boundary.beforeWrite(); formatable.format(output); return true;
            } catch (TagLostException lost) { throw NfcSessionMachine.error("TAG_LOST"); }
            catch (FormatException | IllegalArgumentException malformed) { throw NfcSessionMachine.error("MALFORMED_NDEF"); }
            catch (IOException failure) { throw NfcSessionMachine.error("NFC_IO_ERROR"); }
            catch (SecurityException invalid) { throw NfcSessionMachine.error("TAG_INVALIDATED"); }
        }
        public synchronized void close() throws FactoryException {
            cancelled.set(true); TagTechnology selected = technology;
            if (selected != null) try { selected.close(); }
            catch (IOException | RuntimeException failure) { throw NfcSessionMachine.error("CLEANUP_FAILED"); }
        }
    }
}
