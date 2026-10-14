package com.jarvys.factory.runtime;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Process-local, Activity-instance inbox. Payload reads require the current authenticated caller session. */
public final class IncomingShareInbox implements AutoCloseable {
    public interface Clock { long millis(); }
    /** Native page/session identity. current() must be nonblocking and must not perform provider I/O. */
    public interface Owner { boolean current(); }
    /** A checked authenticated operation session, not a renderer-controlled Boolean. */
    public interface Access { void check(); }
    public interface Opener { InputStream open(String contentUri) throws Exception; }
    private final Object lock=new Object();
    private final IncomingSharePayload.Limits limits;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Envelope> envelopes=new LinkedHashMap<>();
    private final List<Claim> claims=new ArrayList<>();
    private boolean closed;
    private long queuedBytes;
    private static final class Envelope {
        final String id,action,mime; final Owner owner; final long created;
        final ArrayList<String> uris; final boolean[] consumed;
        String text,subject; int textBytes; boolean textConsumed; Claim pending;
        long charged;
        Envelope(String id,IncomingSharePayload payload,Owner owner,long created) {
            this.id=id;this.owner=owner;this.created=created;action=payload.action;mime=payload.declaredMimeType;
            text=payload.text;subject=payload.subject;textBytes=payload.textBytes;uris=new ArrayList<>(payload.contentUris);
            consumed=new boolean[uris.size()];textConsumed=text==null || text.isEmpty();charged=retainedBytes();
        }
        long retainedBytes() {
            // UTF-8 payload plus bounded per-envelope/per-attachment metadata allowance, not a heap guarantee.
            long bytes=1024L+utf8(action)+utf8(mime)+utf8(text)+utf8(subject)+64L*uris.size();
            for(String uri:uris)bytes+=utf8(uri);return bytes;
        }
        void clearPayload() { text=null;subject=null;java.util.Collections.fill(uris,null); }
    }
    public IncomingShareInbox(IncomingSharePayload.Limits limits,Clock clock) {
        if(limits==null || clock==null) throw new IllegalArgumentException(); this.limits=limits; this.clock=clock;
    }
    public IncomingShareInbox(IncomingSharePayload.Limits limits) { this(limits,()->System.nanoTime()/1_000_000L); }
    /** Does not evict older envelopes silently. The caller must report a bounded queue rejection. */
    public String capture(Owner owner,IncomingSharePayload payload) throws FactoryException {
        synchronized(lock) {
            expireLocked(); requireOwner(owner);
            if(payload==null || payload.contentUris.size()>limits.attachmentsPerEnvelope || payload.textBytes>limits.textBytes) throw IncomingSharePayload.invalid();
            if(envelopes.size()>=limits.queuedEnvelopes) throw error("SHARE_QUEUE_FULL");
            String id; do { byte[] bytes=new byte[32];random.nextBytes(bytes);id=hex(bytes); } while(envelopes.containsKey(id));
            Envelope value=new Envelope(id,payload,owner,clock.millis());
            if(value.charged>limits.queuedBytes-queuedBytes)throw error("SHARE_QUEUE_FULL");
            queuedBytes+=value.charged;envelopes.put(id,value);return id;
        }
    }
    public JSONArray list(Owner owner) throws FactoryException {
        synchronized(lock) {
            expireLocked(); requireOwner(owner); JSONArray result=new JSONArray();
            try {
                for(Envelope value:envelopes.values()) if(value.owner==owner) {
                    JSONArray indices=new JSONArray();
                    for(int i=0;i<value.consumed.length;i++) if(!value.consumed[i]) indices.put(i);
                    result.put(new JSONObject().put("id",value.id).put("action",value.action).put("declaredMimeType",value.mime)
                            .put("textBytes",value.textConsumed?0:value.textBytes).put("attachmentIndices",indices)
                            .put("busy",value.pending!=null).put("expiresAfterMs",Math.max(0,IncomingSharePayload.LIFETIME_MILLIS-(clock.millis()-value.created)))
                            .put("processLocal",true));
                }
                return result;
            } catch(org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }
    public boolean discard(Owner owner,String id) throws FactoryException {
        synchronized(lock) {
            expireLocked();requireOwner(owner); Envelope value=envelopes.get(id);
            if(value==null || value.owner!=owner) throw error("INVALID_SHARE");
            forget(value);return true;
        }
    }
    /** Native-only claim freezes exact selection before authorization. No provider work occurs here. */
    public Claim claim(Owner owner,String id,int[] attachmentIndices) throws FactoryException {
        synchronized(lock) {
            expireLocked();requireOwner(owner); Envelope value=envelopes.get(id);
            if(value==null || value.owner!=owner) throw error("INVALID_SHARE");
            if(value.pending!=null) throw error("SHARE_BUSY");
            ArrayList<Integer> selected=new ArrayList<>();
            if(attachmentIndices==null) {
                for(int i=0;i<value.consumed.length;i++) if(!value.consumed[i]) selected.add(i);
            } else {
                if(attachmentIndices.length>limits.attachmentsPerEnvelope)throw error("INVALID_ARGUMENT");
                int previous=-1;
                for(int index:attachmentIndices) {
                    if(index<0 || index>=value.consumed.length || index<=previous || value.consumed[index]) throw error("INVALID_ARGUMENT");
                    selected.add(index);previous=index;
                }
            }
            if(selected.isEmpty() && value.textConsumed) throw error("EMPTY_SELECTION");
            Claim claim=new Claim(value,selected,!value.textConsumed);value.pending=claim;claims.add(claim);return claim;
        }
    }
    public void endOwner(Owner owner) {
        synchronized(lock) {
            for(Envelope value:new ArrayList<>(envelopes.values())) if(value.owner==owner) forget(value);
            for(Claim claim:new ArrayList<>(claims)) if(claim.envelope.owner==owner) claim.cancelLocked();
        }
    }
    public void expire() { synchronized(lock) { expireLocked(); } }
    @Override public void close() {
        synchronized(lock) { closed=true;for(Envelope value:new ArrayList<>(envelopes.values()))forget(value);for(Claim claim:new ArrayList<>(claims))claim.cancelLocked(); }
    }
    private void requireOwner(Owner owner) throws FactoryException { if(closed || owner==null || !owner.current())throw error("CANCELLED"); }
    private void expireLocked() {
        for(Envelope value:new ArrayList<>(envelopes.values())) if(!value.owner.current() || elapsed(value)) {
            forget(value);
        }
        for(Claim claim:new ArrayList<>(claims)) if(!claim.envelope.owner.current() || elapsed(claim.envelope))claim.cancelLocked();
    }
    private void forget(Envelope value) {
        if(envelopes.remove(value.id)==value) { queuedBytes-=value.charged;value.charged=0; }
        if(value.pending!=null)value.pending.cancelLocked();value.clearPayload();
    }
    private void accountConsumed(Envelope value) {
        if(envelopes.get(value.id)==value) { long now=value.retainedBytes();queuedBytes-=value.charged-now;value.charged=now; }
    }
    private boolean elapsed(Envelope value) { return clock.millis()-value.created>=IncomingSharePayload.LIFETIME_MILLIS; }
    private boolean complete(Envelope value) { if(!value.textConsumed)return false;for(boolean consumed:value.consumed)if(!consumed)return false;return true; }
    public final class Claim implements AutoCloseable {
        private final Envelope envelope;
        private final List<Integer> indices;
        private final boolean includesText;
        private final List<DocumentHandles.Reservation> reservations=new ArrayList<>();
        private final List<String> handles=new ArrayList<>();
        private boolean cancelled,preparing,prepared,committed;
        private int openedStreams,closedStreams;
        private Runnable finished; private boolean finishNotified;
                private Access access;
        private Claim(Envelope envelope,List<Integer> indices,boolean includesText) {
            this.envelope=envelope;this.indices=Collections.unmodifiableList(indices);this.includesText=includesText;
        }
        /** Tightly bounded untrusted preview. Never a provider name, inferred MIME, size, path or URI. */
        public JSONObject authorizationDetails() throws FactoryException {
            synchronized(lock) {
                checkLocked(false);
                try {
                    String text=includesText?envelope.text:"";
                    String preview=preview(text,2048);
                    JSONArray selection=new JSONArray(); for(int index:indices)selection.put(index);
                    return new JSONObject().put("envelopeId",envelope.id).put("action",envelope.action)
                            .put("declaredMimeType",envelope.mime).put("subject",envelope.subject==null?"":envelope.subject)
                            .put("textPreview",preview).put("textPreviewTruncated",!preview.equals(text)).put("textBytes",includesText?envelope.textBytes:0)
                            .put("attachmentIndices",selection).put("attachmentCount",indices.size()).put("contentSha256",binding());
                } catch(org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
            }
        }
        /** Run on bounded I/O executor only, while the authenticated caller session remains current. */
        public void prepare(DocumentHandles documents,Access access,Opener opener) throws Exception {
            if(documents==null || access==null || opener==null)throw new IllegalArgumentException();
            try { access.check(); } catch(RuntimeException denied) { close();throw denied; }
            synchronized(lock) {
                checkLocked(false);if(preparing || prepared)throw error("SHARE_BUSY");
                preparing=true;this.access=access;
                try {
                    // All slot admissions before any provider open. Failure leaves payload unconsumed.
                    for(int ignored:indices)reservations.add(documents.reserveRead());
                } catch(Exception failure) { cancelLocked();throw failure; }
            }
            try {
                for(int i=0;i<indices.size();i++) {
                    checkAccess(); DocumentHandles.Reservation reservation=reservations.get(i);
                    reservation.beginOpen(); boolean returnedStream=false;
                    try {
                        InputStream stream=opener.open(envelope.uris.get(indices.get(i)));
                        if(stream==null)throw new IOException("Incoming content stream unavailable");
                        returnedStream=true;
                        synchronized(lock) { openedStreams++; }
                        InputStream guarded=new FilterInputStream(stream) {
                            private boolean closedConfirmed;
                            @Override public void close() throws IOException {
                                in.close();
                                synchronized(lock) { if(!closedConfirmed) { closedConfirmed=true;closedStreams++;releaseCompletedClaim(); } }
                            }
                            private void allowed() throws IOException {
                                try { checkAccess(); } catch(Exception denied) { throw new IOException("Incoming share authority ended",denied); }
                            }
                            @Override public int read() throws IOException { allowed();return in.read(); }
                            @Override public int read(byte[] data,int offset,int count) throws IOException { allowed();return in.read(data,offset,count); }
                            @Override public long skip(long count) throws IOException { allowed();return in.skip(count); }
                        };
                        // After beginOpen, grantRead assumes stale-stream cleanup ownership as well.
                        String handle=reservation.grantRead(guarded);
                        synchronized(lock) { handles.add(handle); }
                        checkAccess();
                    } catch(Exception failure) {
                        // Never abort a provider call that has not returned: its admission stays claimed.
                        reservation.cancel();if(!returnedStream)reservation.abortOpen();throw failure;
                    }
                }
                checkAccess();synchronized(lock) { checkLocked(false);prepared=true; }
            } catch(Exception failure) { synchronized(lock) { cancelLocked(); }throw failure; }
        }
        /** Build and commit atomically immediately before the originating bridge accepts its response.
         * At-most-once: a later lost reply does not restore already-committed text/attachments.
         * If reply posting fails, close() immediately to revoke the undisclosed handles.
         */
        public JSONObject commit() throws FactoryException {
            checkAccess();
            synchronized(lock) {
                checkLocked(false);if(!prepared || committed)throw error("INVALID_SHARE");
                try {
                    JSONArray attachments=new JSONArray();
                    for(int i=0;i<indices.size();i++)attachments.put(new JSONObject().put("index",indices.get(i)).put("handle",handles.get(i))
                            .put("mode","read").put("declaredMimeType",envelope.mime));
                    JSONObject result=new JSONObject().put("id",envelope.id).put("action",envelope.action).put("declaredMimeType",envelope.mime)
                            .put("text",includesText?envelope.text:JSONObject.NULL).put("subject",envelope.subject==null?JSONObject.NULL:envelope.subject)
                            .put("attachments",attachments).put("processLocal",true).put("contentTypeVerified",false)
                            .put("expiresAfterMs",Math.max(0,IncomingSharePayload.LIFETIME_MILLIS-(clock.millis()-envelope.created)));
                    // Native bridge framing also has a fixed ceiling; escaped text can be larger
                    // than its UTF-8 source budget. Fail before consumption, never truncate payload.
                    if(result.toString().getBytes(StandardCharsets.UTF_8).length>com.jarvys.factory.contract.ResourceBudgets.BRIDGE_MESSAGE_BYTES-4096)
                        throw error("SHARE_RESPONSE_TOO_LARGE");
                    committed=true;for(int index:indices) { envelope.consumed[index]=true;envelope.uris.set(index,null); }
                    if(includesText) { envelope.textConsumed=true;envelope.text=null;envelope.textBytes=0; }
                    envelope.pending=null;accountConsumed(envelope);if(complete(envelope))forget(envelope);
                    releaseCompletedClaim();
                    return result;
                } catch(org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
            }
        }
        /** Callback must be nonblocking, e.g. main.post(lease::close). It never means cleanup succeeded. */
        public void onFinished(Runnable callback) {
            synchronized(lock) { if(finished!=null)throw new IllegalStateException("Finish callback already set");finished=callback;notifyFinished(); }
        }
        private void releaseCompletedClaim() { if(committed && openedStreams==closedStreams) { claims.remove(this);notifyFinished(); } }
        private void notifyFinished() {
            if(!finishNotified && finished!=null && (cancelled || (committed && openedStreams==closedStreams))) {
                finishNotified=true;try { finished.run(); } catch(RuntimeException ignored) { /* Notification cannot undo confirmed stream closure. */ }
            }
        }
        /** Native lifecycle deadline only; never renews the original envelope lifetime. */
        public long expiresAfterMs() {
            synchronized(lock) { return Math.max(0,IncomingSharePayload.LIFETIME_MILLIS-Math.max(0,clock.millis()-envelope.created)); }
        }
        public boolean committed() { synchronized(lock) { return committed; } }
        public boolean current() { synchronized(lock) { return !cancelled && !closed && envelope.owner.current() && !elapsed(envelope); } }
        private void checkAccess() throws FactoryException {
            Access value; synchronized(lock) { checkLocked(true);value=access; }
            if(value==null)throw error("PERMISSION_DENIED");
            value.check();synchronized(lock) { checkLocked(true); }
        }
        private void checkLocked(boolean allowCommitted) throws FactoryException {
            if(cancelled || closed || !envelope.owner.current() || elapsed(envelope) || (!allowCommitted && committed))throw error("CANCELLED");
        }
        @Override public void close() { synchronized(lock) { cancelLocked(); } }
        private void cancelLocked() {
            if(cancelled)return;cancelled=true;
            for(DocumentHandles.Reservation reservation:reservations)reservation.cancel();
            if(envelope.pending==this)envelope.pending=null;claims.remove(this);notifyFinished();
        }
        private String binding() {
            try {
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                for(String value:new String[]{"factory-incoming-share-v1",envelope.id,envelope.action,envelope.mime,
                        envelope.subject==null?"":envelope.subject,includesText?envelope.text:""})hash(digest,value);
                for(int index:indices) { hash(digest,Integer.toString(index));hash(digest,envelope.uris.get(index)); }
                return hex(digest.digest());
            } catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
    }
    private static int utf8(String value) { return value==null?0:value.getBytes(StandardCharsets.UTF_8).length; }
    private static void hash(MessageDigest digest,String value) { byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes); }
    private static String preview(String value,int limit) {
        int end=Math.min(value.length(),limit);
        if(end>0 && end<value.length() && Character.isHighSurrogate(value.charAt(end-1)))end--;
        while(end>0 && value.substring(0,end).getBytes(StandardCharsets.UTF_8).length>limit) { end--;if(end>0 && Character.isHighSurrogate(value.charAt(end-1)))end--; }
        return value.substring(0,end);
    }
    private static String hex(byte[] bytes) { StringBuilder value=new StringBuilder(bytes.length*2);for(byte b:bytes)value.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return value.toString(); }
    private static FactoryException error(String code) { return new FactoryException(code,"Incoming share authority is unavailable or its bounded operation cannot be admitted."); }
}
