package com.jarvys.factory.runtime;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import com.jarvys.factory.contract.ResourceBudgets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.function.BooleanSupplier;

/** Authenticated point-in-time source data, never an operation authorization or effect lease. */
public final class NativeInstalledSource {
    public enum PolicyMode { ALLOW_ALWAYS, ASK, DENY }
    /** Optional point-in-time mode only. Never replaces an effect grant. */
    public final PolicyMode policyMode;
    public final String appId, certificateSha256, apkSha256, recordSha256;
    public final long versionCode;
    public final ResourceBudgets resources;

    private NativeInstalledSource(String appId, String certificateSha256, String apkSha256,
                                  String recordSha256, long versionCode, ResourceBudgets resources) {
        this(appId,certificateSha256,apkSha256,recordSha256,versionCode,resources,null);
    }
    private NativeInstalledSource(String appId,String certificateSha256,String apkSha256,String recordSha256,
                                  long versionCode,ResourceBudgets resources,PolicyMode policyMode) {
        this.policyMode=policyMode;
        this.appId = appId;
        this.certificateSha256 = certificateSha256;
        this.apkSha256 = apkSha256;
        this.recordSha256 = recordSha256;
        this.versionCode = versionCode;
        this.resources = resources;
    }

    /**
     * Call only from a bounded background worker. The caller's current callback must independently
     * expire its receiver/session. A successful description does not authorize any native effect:
     * obtain and check a fresh NativeBackgroundAuthorization lease at the actual effect boundary.
     */
    public static NativeInstalledSource describe(Context context, FactoryConfig config, String capability,
                                                long receiverDeadlineElapsedMs, BooleanSupplier current) throws FactoryException {
        return describeInternal(context,config,capability,null,receiverDeadlineElapsedMs,current);
    }
    /** No UI, grant, policy mutation or provider-content access. DENY can stop expensive preparation. */
    public static NativeInstalledSource describeOperation(Context context,FactoryConfig config,String operation,
                                                         long deadlineElapsedMs,BooleanSupplier current) throws FactoryException {
        com.jarvys.factory.contract.CapabilityCatalog.Method method=com.jarvys.factory.contract.CapabilityCatalog.METHODS.get(operation);
        if(method==null || method.capability==null) throw new IllegalArgumentException("Known installed operation required");
        return describeInternal(context,config,method.capability,operation,deadlineElapsedMs,current);
    }
    private static NativeInstalledSource describeInternal(Context context,FactoryConfig config,String capability,String operation,
                                                         long receiverDeadlineElapsedMs,BooleanSupplier current) throws FactoryException {
        if (context == null || config == null || current == null || capability == null ||
                !config.appId.equals(context.getPackageName()))
            throw new IllegalArgumentException("Installed source dependencies required");
        try {
            long deadline = Math.min(receiverDeadlineElapsedMs, SystemClock.elapsedRealtime() + 2000L);
            requireCurrent(current, deadline);
            if (!FactoryConfig.SUPPORTED.contains(capability) || !config.capabilities.contains(capability))
                throw new SecurityException("Undeclared installed source capability");
            int hostUid = NativeOperationHostIdentity.verify(context, config.documentBroker);
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            StringBuilder token = new StringBuilder(64);
            for (byte value : bytes) token.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            String nonce = token.toString();
            Bundle input = new Bundle();
            input.putInt("protocolVersion", 1); input.putString("nonce", nonce); input.putString("capability", capability);
            input.putLong("deadlineElapsedMs", deadline);
            if(operation!=null) input.putString("operation",operation);
            requireCurrent(current, deadline);
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://" + config.documentBroker.packageName + ".factory.operations"),
                    "describe_installed", null, input);
            requireCurrent(current, deadline);
            HashSet<String> expectedKeys=new HashSet<>(Arrays.asList("protocolVersion", "nonce", "capability", "deadlineElapsedMs",
                    "appId", "certificateSha256", "apkSha256", "recordSha256", "versionCode", "resourceBudgets"));
            if(operation!=null) { expectedKeys.add("operation"); expectedKeys.add("policyMode"); }
            if (result == null || result.hasFileDescriptors() ||
                    !result.keySet().equals(expectedKeys) ||
                    !Integer.valueOf(1).equals(result.get("protocolVersion")) || !nonce.equals(result.get("nonce")) ||
                    !capability.equals(result.get("capability")) || !Long.valueOf(deadline).equals(result.get("deadlineElapsedMs")) ||
                    !config.appId.equals(result.get("appId")))
                throw new SecurityException("Invalid installed source reply");
            String certificate = hash(result, "certificateSha256"), apk = hash(result, "apkSha256"), record = hash(result, "recordSha256");
            Object version = result.get("versionCode"), budgets = result.get("resourceBudgets");
            if (!(version instanceof Long) || (Long) version <= 0 || !(budgets instanceof String))
                throw new SecurityException("Invalid installed source fields");
            ResourceBudgets resources = ResourceBudgetsJson.parse(StrictJson.object((String) budgets, 8192));
            if (NativeOperationHostIdentity.verify(context, config.documentBroker) != hostUid)
                throw new SecurityException("Installed source host changed");
            requireCurrent(current, deadline);
            PolicyMode mode=null;
            if(operation!=null) {
                if(!operation.equals(result.get("operation")) || !(result.get("policyMode") instanceof String)) throw new SecurityException("Policy query reply differs");
                mode=PolicyMode.valueOf((String)result.get("policyMode"));
            }
            return new NativeInstalledSource(config.appId, certificate, apk, record, (Long) version, resources,mode);
        } catch (Exception unavailable) {
            throw new FactoryException("SOURCE_UNAVAILABLE", "Current authenticated installed source is unavailable; no authority was assumed.");
        }
    }

    private static String hash(Bundle result, String key) {
        Object value = result.get(key);
        if (!(value instanceof String) || !((String) value).matches("[a-f0-9]{64}"))
            throw new SecurityException("Invalid installed source digest");
        return (String) value;
    }

    private static void requireCurrent(BooleanSupplier current, long deadline) {
        if (Thread.currentThread().isInterrupted() || !current.getAsBoolean() || SystemClock.elapsedRealtime() >= deadline)
            throw new SecurityException("Installed source acquisition ended");
    }
}
