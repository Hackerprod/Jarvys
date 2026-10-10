package com.jarvys.factory.runtime;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Ephemeral, instance-bound authority over native-selected sequential document streams.
 * No filenames, URIs, paths, durable grants, or serialized authority cross this boundary.
 */
public final class DocumentHandles {
    public static final int MAX_HANDLES = 4, MAX_CHUNK_BYTES = 32 * 1024;
    public static final long MAX_HANDLE_BYTES = 16L * 1024 * 1024;
    public static final long MAX_SESSION_BYTES = 32L * 1024 * 1024;
    public static final long LIFETIME_MILLIS = 5L * 60 * 1000;
    public interface Clock { long millis(); }
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final Semaphore GLOBAL_SLOTS = new Semaphore(128);
    private static final Executor CLEANUP = new ThreadPoolExecutor(MAX_HANDLES, MAX_HANDLES, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), r -> {
        Thread thread = new Thread(r, "factory-document-close"); thread.setDaemon(true); return thread;
    });
    private final Object lock = new Object();
    private final Map<String, Entry> entries = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Executor cleanup;
    private long charged;
    private int closing, retiredOpening;
    private boolean revoked;
    private static final class Entry {
        final String token; final boolean write; final long created;
        Closeable stream; boolean busy, eof, opening, admission=true; long offset, charged;
        Entry(String token, boolean write, long created) { this.token=token; this.write=write; this.created=created; }
    }
    public DocumentHandles() { this(() -> System.nanoTime() / 1000000L, CLEANUP); }
    /** Clock must be monotonic. Executor must enqueue asynchronously, never run inline. */
    public DocumentHandles(Clock clock, Executor cleanupExecutor) {
        if (clock == null || cleanupExecutor == null) throw new IllegalArgumentException();
        this.clock=clock; this.cleanup=cleanupExecutor;
    }
    public final class Reservation {
        private final Entry entry;
        private Reservation(Entry entry) { this.entry=entry; }
        /** Opaque pending identifier; not usable for IO before grant. */
        public String token() { return entry.token; }
        /** Call before provider open; cancellation retains admission while that call is blocked. */
        public void beginOpen() throws FactoryException {
            synchronized(lock) {
                expireLocked();
                if (revoked || entries.get(entry.token) != entry || entry.stream != null || entry.opening) throw error("INVALID_HANDLE");
                entry.opening=true;
            }
        }
        /** Must be called if provider open fails without returning a stream. */
        public void abortOpen() {
            synchronized(lock) {
                if (!entry.opening) return;
                if (entries.get(entry.token) != entry) retiredOpening--;
                entry.opening=false;
                remove(entry); releaseAdmission(entry);
            }
        }
        /** Charge native snapshot acquisition before copying; cancellation never refunds this budget. */
        public void chargeSnapshot(int bytes) throws FactoryException {
            synchronized(lock) {
                expireLocked();
                if (revoked || entries.get(entry.token) != entry || entry.stream != null || entry.opening
                        || bytes < 1 || bytes > 8 * 1024 * 1024) throw error("INVALID_HANDLE");
                if (bytes > MAX_SESSION_BYTES-charged) throw error("DOCUMENT_QUOTA");
                charged+=bytes;
            }
        }
        public String grantRead(InputStream stream) throws FactoryException { return grant(entry, stream, false); }
        public String grantWrite(OutputStream stream) throws FactoryException { return grant(entry, stream, true); }
        public void cancel() { synchronized(lock) { remove(entry); } }
    }
    public Reservation reserveRead() throws FactoryException { return reserve(false); }
    public Reservation reserveWrite() throws FactoryException { return reserve(true); }
    /** Convenience: caller retains stream ownership if admission/grant fails. Native pickers use reservations. */
    public String grantRead(InputStream stream) throws FactoryException {
        Reservation reservation;
        try { reservation=reserveRead(); } catch (FactoryException e) { throw e; }
        try { return reservation.grantRead(stream); }
        catch (FactoryException e) { reservation.cancel(); throw e; }
    }
    public String grantWrite(OutputStream stream) throws FactoryException {
        Reservation reservation;
        try { reservation=reserveWrite(); } catch (FactoryException e) { throw e; }
        try { return reservation.grantWrite(stream); }
        catch (FactoryException e) { reservation.cancel(); throw e; }
    }
    private Reservation reserve(boolean write) throws FactoryException {
        synchronized(lock) {
            expireLocked();
            if (revoked) throw error("SESSION_REVOKED");
            if (entries.size() + closing + retiredOpening >= MAX_HANDLES) throw error("HANDLE_LIMIT");
            if (!GLOBAL_SLOTS.tryAcquire()) throw error("HANDLE_LIMIT");
            byte[] bytes=new byte[32]; String token;
            do {
                random.nextBytes(bytes); StringBuilder out=new StringBuilder(64);
                for (byte b:bytes) { out.append("0123456789abcdef".charAt((b & 255) >>> 4)); out.append("0123456789abcdef".charAt(b & 15)); }
                token=out.toString();
            } while (entries.containsKey(token));
            Entry entry=new Entry(token,write,clock.millis()); entries.put(token,entry);
            return new Reservation(entry);
        }
    }
    private String grant(Entry entry, Closeable stream, boolean write) throws FactoryException {
        synchronized(lock) {
            expireLocked();
            if (stream == null || revoked || entries.get(entry.token) != entry || entry.stream != null || entry.write != write) {
                // Only a native beginOpen transfers stale completion cleanup responsibility.
                // Otherwise the caller retains ownership when a grant is rejected.
                if (entry.opening && entries.get(entry.token) != entry) {
                    retiredOpening--; entry.opening=false;
                    if (stream == null) releaseAdmission(entry); else closeTracked(entry,stream);
                }
                throw error("INVALID_HANDLE");
            }
            entry.opening=false; entry.stream=stream; return entry.token;
        }
    }
    public static final class ReadResult {
        public final String base64; public final long offset, nextOffset; public final boolean eof;
        private ReadResult(String base64,long offset,long nextOffset,boolean eof) { this.base64=base64; this.offset=offset; this.nextOffset=nextOffset; this.eof=eof; }
    }
    public static final class WriteResult {
        public final long offset,nextOffset; public final int bytesWritten;
        private WriteResult(long offset,int bytes) { this.offset=offset; nextOffset=offset+bytes; bytesWritten=bytes; }
    }
    public static final class CloseResult {
        /** Scheduling closure does not imply rollback, flush success, or durable provider commit. */
        public final String status="close_requested";
        public final boolean providerCommitConfirmed=false;
        private CloseResult() {}
    }
    /** The request must fit the remaining quota. At the exact limit, close rather than
     * probing EOF: proving EOF would require an unauthorized extra provider read. */
    public ReadResult read(String token,long offset,int maxBytes) throws FactoryException {
        if (maxBytes < 1 || maxBytes > MAX_CHUNK_BYTES) throw error("INVALID_CHUNK");
        Entry entry;
        synchronized(lock) {
            entry=lookup(token,false,offset);
            if (entry.eof) return new ReadResult("",offset,offset,true);
            begin(entry,maxBytes);
        }
        byte[] buffer=new byte[maxBytes]; int count;
        try { count=((InputStream)entry.stream).read(buffer); }
        catch (Exception e) { poison(entry); throw error("DOCUMENT_IO"); }
        if (count < -1 || count == 0 || count > maxBytes) { poison(entry); throw error("DOCUMENT_IO"); }
        synchronized(lock) {
            finishCheck(entry);
            int actual=Math.max(0,count); charged-=maxBytes-actual; entry.charged-=maxBytes-actual;
            entry.offset+=actual; entry.busy=false; entry.eof=count == -1;
            return new ReadResult(encode(buffer,actual),offset,entry.offset,entry.eof);
        }
    }
    /** Consumes an untouched read handle into a bounded immutable sharing snapshot.
     * The extra byte proves EOF without bypassing either cumulative quota. The handle is
     * retired even after failure; provider close retains the existing bounded async path.
     */
    public byte[] snapshotForShare(String token) throws FactoryException {
        return snapshot(token, 8 * 1024 * 1024, "SHARE_TOO_LARGE");
    }
    /** Consumes an untouched read handle. Format validation belongs to the authenticated host. */
    public byte[] snapshotForAudio(String token) throws FactoryException {
        return snapshot(token, 6 * 1024 * 1024, "AUDIO_TOO_LARGE");
    }
    private byte[] snapshot(String token, int maximum, String tooLarge) throws FactoryException {
        final Entry entry;
        synchronized(lock) { entry=lookup(token,false,0); entry.busy=true; }
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        try {
            while (true) {
                int requested=Math.min(MAX_CHUNK_BYTES,maximum+1-output.size());
                synchronized(lock) { finishCheck(entry); begin(entry,requested); }
                byte[] bytes=new byte[requested]; final int count;
                try { count=((InputStream)entry.stream).read(bytes); }
                catch (Exception failure) { throw error("DOCUMENT_IO"); }
                if (count < -1 || count == 0 || count > requested) throw error("DOCUMENT_IO");
                synchronized(lock) {
                    finishCheck(entry);
                    int actual=Math.max(0,count); charged-=requested-actual; entry.charged-=requested-actual;
                    entry.offset+=actual;
                }
                if (count == -1) {
                    if (output.size() == 0) throw error("EMPTY_FILE");
                    return output.toByteArray();
                }
                if (count > maximum-output.size()) throw error(tooLarge);
                output.write(bytes,0,count);
            }
        } finally { synchronized(lock) { remove(entry); } }
    }
    public WriteResult write(String token,long offset,String base64) throws FactoryException {
        byte[] bytes=decode(base64); Entry entry;
        synchronized(lock) { entry=lookup(token,true,offset); begin(entry,bytes.length); }
        try { ((OutputStream)entry.stream).write(bytes); }
        catch (Exception e) { poison(entry); throw error("DOCUMENT_IO"); }
        synchronized(lock) {
            finishCheck(entry); entry.offset+=bytes.length; entry.busy=false;
            return new WriteResult(offset,bytes.length);
        }
    }
    private Entry lookup(String token,boolean write,long offset) throws FactoryException {
        expireLocked(); Entry entry=entries.get(token);
        if (revoked || entry == null || entry.stream == null) throw error("INVALID_HANDLE");
        if (entry.write != write) throw error("WRONG_MODE");
        if (entry.busy) throw error("HANDLE_BUSY");
        if (offset < 0 || offset != entry.offset) throw error("INVALID_OFFSET");
        return entry;
    }
    private void begin(Entry entry,int bytes) throws FactoryException {
        if (bytes > MAX_HANDLE_BYTES-entry.charged || bytes > MAX_SESSION_BYTES-charged) throw error("DOCUMENT_QUOTA");
        entry.charged+=bytes; charged+=bytes; entry.busy=true;
    }
    private void finishCheck(Entry entry) throws FactoryException {
        expireLocked();
        if (revoked || entries.get(entry.token) != entry) throw error("INVALID_HANDLE");
    }
    private void poison(Entry entry) { synchronized(lock) { remove(entry); } }
    public CloseResult close(String token) throws FactoryException {
        synchronized(lock) {
            expireLocked(); Entry entry=entries.get(token);
            if (entry == null || revoked) throw error("INVALID_HANDLE");
            remove(entry); return new CloseResult();
        }
    }
    /** Cancel current work without resetting cumulative budget or enabling stale picker grants. */
    public void cancelAll() {
        synchronized(lock) { for (Entry entry:new ArrayList<>(entries.values())) remove(entry); }
    }
    /** Irreversible session cancellation: stale picker completions cannot grant new authority. */
    public void revokeAll() {
        synchronized(lock) { revoked=true; for (Entry entry:new ArrayList<>(entries.values())) remove(entry); }
    }
    public void expire() { synchronized(lock) { expireLocked(); } }
    private void expireLocked() {
        long now=clock.millis();
        for (Entry entry:new ArrayList<>(entries.values())) if (now-entry.created >= LIFETIME_MILLIS) remove(entry);
    }
    private void remove(Entry entry) {
        if (entries.get(entry.token) == entry) { entries.remove(entry.token);
            if (entry.stream != null) closeTracked(entry,entry.stream);
            else if (entry.opening) retiredOpening++;
            else releaseAdmission(entry); }
    }
    private void releaseAdmission(Entry entry) {
        if (entry.admission) { entry.admission=false; GLOBAL_SLOTS.release(); }
    }
    private void closeTracked(Entry entry,Closeable stream) {
        if (stream == null) return;
        closing++;
        submitClose(() -> {
            try { stream.close(); } catch (Exception ignored) { /* Best effort only. */ }
            finally { synchronized(lock) { closing--; releaseAdmission(entry); } }
        });
    }
    private void submitClose(Runnable close) {
        try { cleanup.execute(close); }
        catch (RuntimeException rejected) {
            // Bounded fallback: never close inline and never grow an unbounded worker/queue.
            // If even fallback refuses, retain the admission slot and fail future opens closed.
            if (cleanup != CLEANUP) try { CLEANUP.execute(close); } catch (RuntimeException ignored) { }
        }
    }
    private static FactoryException error(String code) { return new FactoryException(code,"Document operation could not be completed."); }
    private static byte[] decode(String value) throws FactoryException {
        if (value == null || value.isEmpty() || value.length() > ((MAX_CHUNK_BYTES+2)/3)*4 || value.length()%4 != 0) throw error("INVALID_CHUNK");
        int len=value.length(), padding=value.charAt(len-1)=='=' ? (value.charAt(len-2)=='=' ? 2:1):0;
        int size=(len/4)*3-padding;
        if (size > MAX_CHUNK_BYTES) throw error("INVALID_CHUNK");
        // Validate alphabet, exact padding, and canonical zero unused bits before allocation.
        for (int i=0;i<len-padding;i++) if (ALPHABET.indexOf(value.charAt(i)) < 0) throw error("INVALID_CHUNK");
        if (padding == 2 && (ALPHABET.indexOf(value.charAt(len-3)) & 15) != 0) throw error("INVALID_CHUNK");
        if (padding == 1 && (ALPHABET.indexOf(value.charAt(len-2)) & 3) != 0) throw error("INVALID_CHUNK");
        byte[] out=new byte[size]; int at=0;
        for (int i=0;i<len;i+=4) {
            int a=ALPHABET.indexOf(value.charAt(i)),b=ALPHABET.indexOf(value.charAt(i+1));
            int c=value.charAt(i+2)=='=' ? 0:ALPHABET.indexOf(value.charAt(i+2));
            int d=value.charAt(i+3)=='=' ? 0:ALPHABET.indexOf(value.charAt(i+3));
            out[at++]=(byte)((a<<2)|(b>>>4));
            if (at<size) out[at++]=(byte)((b<<4)|(c>>>2));
            if (at<size) out[at++]=(byte)((c<<6)|d);
        }
        return out;
    }
    private static String encode(byte[] bytes,int count) {
        StringBuilder out=new StringBuilder(((count+2)/3)*4);
        for (int i=0;i<count;i+=3) {
            int a=bytes[i]&255,b=i+1<count ? bytes[i+1]&255:0,c=i+2<count ? bytes[i+2]&255:0;
            out.append(ALPHABET.charAt(a>>>2)).append(ALPHABET.charAt(((a&3)<<4)|(b>>>4)));
            out.append(i+1<count ? ALPHABET.charAt(((b&15)<<2)|(c>>>6)):'=');
            out.append(i+2<count ? ALPHABET.charAt(c&63):'=');
        }
        return out.toString();
    }
}
