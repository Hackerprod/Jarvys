package com.jarvys.factory.runtime;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** No-UI, deadline-aware acquisition of an authenticated installed-source operation session. */
public final class NativeBackgroundAuthorization {
    public enum Decision { ACTIVE }
    /** Expected descriptor identity; a caller-supplied value is never authority until the host compares fresh proof. */
    public static final class SourceIdentity {
        public final String appId, certificateSha256, apkSha256, recordSha256;
        public final long versionCode;
        public SourceIdentity(String appId, String certificate, String apk, String record, long version) {
            if (appId == null || !appId.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") ||
                    !hash(certificate) || !hash(apk) || !hash(record) || version < 1) throw new IllegalArgumentException("Exact source identity required");
            this.appId=appId; certificateSha256=certificate; apkSha256=apk; recordSha256=record; versionCode=version;
        }
        public String canonicalJson() {
            try { return new org.json.JSONObject().put("appId",appId).put("certificateSha256",certificateSha256)
                    .put("apkSha256",apkSha256).put("recordSha256",recordSha256).put("versionCode",versionCode).toString(); }
            catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        public String bindingEvidence() { return "factory-native-background-source-v1:" + canonicalJson(); }
        public static SourceIdentity parse(String raw) throws FactoryException {
            try {
                org.json.JSONObject value=StrictJson.object(raw,1024);
                FactoryConfig.exactKeys(value,"appId","certificateSha256","apkSha256","recordSha256","versionCode");
                if(value.length()!=5) throw new IllegalArgumentException();
                Object version=value.get("versionCode"); if(!(version instanceof Long) && !(version instanceof Integer)) throw new IllegalArgumentException();
                return new SourceIdentity(FactoryConfig.string(value,"appId"),FactoryConfig.string(value,"certificateSha256"),
                        FactoryConfig.string(value,"apkSha256"),FactoryConfig.string(value,"recordSha256"),((Number)version).longValue());
            } catch(Exception invalid) { throw new FactoryException("INVALID_ARGUMENT","Invalid expected installed source identity."); }
        }
        private static boolean hash(String value) { return value!=null && value.matches("[a-f0-9]{64}"); }
    }
    public static final class Result implements NativeOperationAuthorization.Access, AutoCloseable {
        public final Decision decision;
        private final NativeOperationAuthorization.Client client;
        private final AtomicBoolean ended;
        private final BooleanSupplier current;
        private Result(Decision decision, NativeOperationAuthorization.Client client, AtomicBoolean ended, BooleanSupplier current) {
            this.decision=decision; this.client=client; this.ended=ended; this.current=current;
        }
        public void check() {
            if (decision != Decision.ACTIVE || client == null || ended.get() || !current.getAsBoolean()) throw new SecurityException("Background operation session ended");
            client.check();
            if (ended.get() || !current.getAsBoolean()) throw new SecurityException("Background authority ended");
        }
        @Override public void close() { ended.set(true); if (client != null) client.close(); }
    }
    private NativeBackgroundAuthorization() { }
    /** Call from a bounded receiver worker. current must independently expire the receiver session. */
    public static Result acquire(Context context, FactoryConfig config, NativeOperationRequest request, SourceIdentity expectedSource,
                                 long receiverDeadlineElapsedMs, BooleanSupplier current) throws FactoryException {
        return acquireInternal(context,config,request,expectedSource,receiverDeadlineElapsedMs,current,false);
    }
    /** Finite scheduled HTTP only; receiver leases retain their separate eight-second limit. */
    public static Result acquireFiniteHttpJob(Context context, FactoryConfig config, NativeOperationRequest request,
                                             SourceIdentity expectedSource, long deadlineElapsedMs, BooleanSupplier current) throws FactoryException {
        if (config == null || request == null || !"jobs.enqueueHttp".equals(request.operation) ||
                !"scheduled".equals(request.details().optString("executionMode")) || !config.capabilities.contains("network"))
            throw new IllegalArgumentException("Finite scheduled HTTP request required");
        return acquireInternal(context,config,request,expectedSource,deadlineElapsedMs,current,true);
    }
    private static Result acquireInternal(Context context, FactoryConfig config, NativeOperationRequest request, SourceIdentity expectedSource,
                                          long receiverDeadlineElapsedMs, BooleanSupplier current, boolean finiteHttpJob) throws FactoryException {
        if (context == null || config == null || request == null || expectedSource == null || !config.appId.equals(expectedSource.appId) || current == null || !config.appId.equals(context.getPackageName()))
            throw new IllegalArgumentException("Installed background source dependencies required");
        NativeOperationAuthorization.Client client=null;
        AtomicBoolean ended=new AtomicBoolean();
        try {
            long deadline=Math.min(receiverDeadlineElapsedMs,SystemClock.elapsedRealtime()+2000L);
            requireCurrent(current,deadline);
            int hostUid=NativeOperationHostIdentity.verify(context,config.documentBroker);
            if (!config.capabilities.contains(request.capability)) throw new SecurityException();
            if ("secrets.request".equals(request.operation) && !config.capabilities.contains("network")) throw new SecurityException();
            byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); StringBuilder nonceValue=new StringBuilder(64);
            for(byte value:bytes) nonceValue.append(Character.forDigit((value>>>4)&15,16)).append(Character.forDigit(value&15,16));
            String nonce=nonceValue.toString(),binding=request.binding(nonce,expectedSource.bindingEvidence());
            Bundle input=new Bundle(); input.putInt("protocolVersion",1);input.putString("operation",request.operation);
            input.putString("nonce",nonce);input.putString("details",request.canonicalJson());input.putLong("deadlineElapsedMs",receiverDeadlineElapsedMs);input.putString("expectedSource",expectedSource.canonicalJson());
            if(finiteHttpJob) input.putString("leaseKind","finite_http_job");
            requireCurrent(current,deadline);
            Bundle result=context.getContentResolver().call(Uri.parse("content://"+config.documentBroker.packageName+".factory.operations"),"acquire_background",null,input);
            // Even a late reply must be connected only for immediate cleanup, not abandoned.
            if(result==null || result.hasFileDescriptors() || !Integer.valueOf(1).equals(result.get("protocolVersion")) ||
                    !nonce.equals(result.get("nonce")) || !binding.equals(result.get("binding")) ||
                    !NativeOperationHostIdentity.verifiedUid(context,config.documentBroker,hostUid)) throw new SecurityException();
            exact(result,"protocolVersion","nonce","binding","status","lease");
            if(!Integer.valueOf(NativeOperationAuthorization.ACTIVE).equals(result.get("status"))) throw new SecurityException();
            IBinder endpoint=result.getBinder("lease"); if(endpoint==null) throw new SecurityException();
            client=NativeOperationAuthorization.connect(endpoint,nonce,binding,
                    uid->uid==hostUid && NativeOperationHostIdentity.verifiedUid(context,config.documentBroker,hostUid),()->ended.set(true));
            requireCurrent(current,deadline);
            Result active=new Result(Decision.ACTIVE,client,ended,current); active.check(); return active;
        } catch(Exception unavailable) {
            ended.set(true); if(client!=null) client.close();
            throw new FactoryException("SOURCE_UNAVAILABLE","Current installed source session is unavailable.");
        }
    }
    private static void requireCurrent(BooleanSupplier current,long deadline) {
        if(!current.getAsBoolean() || SystemClock.elapsedRealtime()>=deadline) throw new SecurityException("Background acquisition deadline ended");
    }
    private static void exact(Bundle bundle,String... keys) {
        if(!bundle.keySet().equals(new HashSet<>(Arrays.asList(keys)))) throw new SecurityException("Unexpected background policy reply");
    }
}
