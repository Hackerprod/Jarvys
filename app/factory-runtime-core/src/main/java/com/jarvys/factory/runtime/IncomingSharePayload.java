package com.jarvys.factory.runtime;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Bounded untrusted values copied from an Android SEND Intent. Never retained Intent authority. */
public final class IncomingSharePayload {
    public static final String SEND = "android.intent.action.SEND", SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE";
    public static final int MAX_URI_CHARS = 8192, MAX_SUBJECT_BYTES = 1024, MAX_MIME_TYPES = 32;
    public static final long LIFETIME_MILLIS = 300_000L;
    public static final class Limits {
        public final int queuedEnvelopes, attachmentsPerEnvelope, textBytes, queuedBytes;
        public Limits(int queuedEnvelopes, int attachmentsPerEnvelope, int textBytes) { this(queuedEnvelopes,attachmentsPerEnvelope,textBytes,1024*1024); }
        public Limits(int queuedEnvelopes, int attachmentsPerEnvelope, int textBytes,int queuedBytes) {
            if (queuedEnvelopes < 1 || queuedEnvelopes > 64 || attachmentsPerEnvelope < 1 || attachmentsPerEnvelope > 32
                    || textBytes < 1 || textBytes > 256 * 1024 || queuedBytes<1 || queuedBytes>8*1024*1024) throw new IllegalArgumentException("Invalid incoming share budget");
            this.queuedEnvelopes=queuedEnvelopes; this.attachmentsPerEnvelope=attachmentsPerEnvelope; this.textBytes=textBytes;this.queuedBytes=queuedBytes;
        }
        public static Limits defaults() { return new Limits(16,16,64*1024); }
    }
    public static final class Policy {
        public final Set<String> mimeTypes;
        public final boolean multiple;
        public final Limits limits;
        public Policy(Iterable<String> mimeTypes, boolean multiple, Limits limits) {
            if (mimeTypes==null || limits==null) throw new IllegalArgumentException("Missing incoming share policy");
            TreeSet<String> values=new TreeSet<>();
            for(String mime:mimeTypes) if(!mime(mime,true) || !values.add(mime) || values.size()>MAX_MIME_TYPES)
                throw new IllegalArgumentException("Invalid or duplicate incoming MIME type");
            if(values.isEmpty()) throw new IllegalArgumentException("Missing incoming MIME types");
            this.mimeTypes=Collections.unmodifiableSet(values); this.multiple=multiple; this.limits=limits;
        }
        public boolean accepts(String declared) {
            if(!mime(declared,true)) return false;
            return mimeTypes.contains(declared) || mimeTypes.contains("*/*") || mimeTypes.contains(declared.substring(0,declared.indexOf('/'))+"/*");
        }
    }
    public final String action, declaredMimeType;
    final String text, subject;
    final List<String> contentUris;
    public final int textBytes;
    private IncomingSharePayload(String action,String mime,String text,String subject,List<String> uris,int bytes) {
        this.action=action; declaredMimeType=mime; this.text=text; this.subject=subject;
        contentUris=Collections.unmodifiableList(uris); textBytes=bytes;
    }
    public static IncomingSharePayload parse(Policy policy,String action,String declaredMimeType,Object text,Object subject,List<?> contentUris) throws FactoryException {
        if(policy==null || (!SEND.equals(action) && !SEND_MULTIPLE.equals(action)) || (SEND_MULTIPLE.equals(action) && !policy.multiple)
                || !policy.accepts(declaredMimeType) || contentUris==null || contentUris.size()>policy.limits.attachmentsPerEnvelope
                || (SEND.equals(action) && contentUris.size()>1)) throw invalid();
        String plain=text(text,policy.limits.textBytes), heading=text(subject,MAX_SUBJECT_BYTES);
        if(heading!=null && containsControl(heading)) throw invalid();
        ArrayList<String> uris=new ArrayList<>();
        for(Object raw:contentUris) {
            if(!(raw instanceof String)) throw invalid();
            String value=(String)raw;
            if(!contentUri(value) || uris.contains(value)) throw invalid();
            uris.add(value);
        }
        if((plain==null || plain.isEmpty()) && uris.isEmpty()) throw invalid();
        return new IncomingSharePayload(action,declaredMimeType,plain,heading,uris,plain==null?0:plain.getBytes(StandardCharsets.UTF_8).length);
    }
    static String text(Object raw,int maxBytes) throws FactoryException {
        if(raw==null) return null;
        // Reject spans, arbitrary CharSequence implementations and all Parcelable objects.
        if(!(raw instanceof String)) throw invalid();
        String value=(String)raw;
        if(value.length()>maxBytes) throw invalid();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c==0) throw invalid();
            if(Character.isHighSurrogate(c)) { if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid(); }
            else if(Character.isLowSurrogate(c)) throw invalid();
        }
        if(value.getBytes(StandardCharsets.UTF_8).length>maxBytes) throw invalid();
        return value;
    }
    public static boolean contentUri(String value) {
        if(value==null || value.length()>MAX_URI_CHARS || containsControl(value) || value.indexOf('\\')>=0) return false;
        try {
            URI uri=new URI(value);
            return "content".equals(uri.getScheme()) && !uri.isOpaque() && uri.getRawAuthority()!=null && !uri.getRawAuthority().isEmpty()
                    && uri.getRawAuthority().matches("[A-Za-z0-9._-]+") && uri.getUserInfo()==null && uri.getPort()==-1
                    && uri.getRawFragment()==null;
        } catch(Exception invalid) { return false; }
    }
    private static boolean mime(String value,boolean pattern) {
        if(value==null || value.length()>127) return false;
        if(pattern && value.equals("*/*")) return true;
        return value.matches("[a-z0-9][a-z0-9!#$&^_.+-]{0,62}/"+(pattern?"(\\*|[a-z0-9][a-z0-9!#$&^_.+-]{0,62})":"[a-z0-9][a-z0-9!#$&^_.+-]{0,62}"));
    }
    static boolean containsControl(String value) { for(int i=0;i<value.length();i++) if(Character.isISOControl(value.charAt(i))) return true; return false; }
    static FactoryException invalid() { return new FactoryException("INVALID_SHARE","Incoming sharing payload is malformed or outside the declared policy."); }
}
