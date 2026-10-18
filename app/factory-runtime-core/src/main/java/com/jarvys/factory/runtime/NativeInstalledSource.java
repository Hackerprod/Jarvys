package com.jarvys.factory.runtime;

import android.content.Context;
import android.net.Uri;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import com.jarvys.factory.contract.ResourceBudgets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.function.BooleanSupplier;

/** Authenticated point-in-time source data, never an operation authorization or effect lease. */
public final class NativeInstalledSource {
    public final String appId, certificateSha256, apkSha256, recordSha256;
    public final long versionCode;
    public final ResourceBudgets resources;

    private NativeInstalledSource(String appId, String certificateSha256, String apkSha256,
                                  String recordSha256, long versionCode, ResourceBudgets resources) {
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
        return describeInternal(context,config,capability,receiverDeadlineElapsedMs,current);
    }

    /**
     * Local identity for work owned entirely by this generated application. Android verifies the
     * installed package signature; capabilities and budgets come from its packaged configuration.
     * The derived digests scope private ledgers to this package/version/config and are not a Jarvys
     * artifact receipt or authorization.
     */
    @SuppressWarnings("deprecation")
    public static NativeInstalledSource describeLocal(Context context, FactoryConfig config, String capability) throws FactoryException {
        try {
            String packaged = FactoryRuntime.readConfiguration(context.getAssets().open("factory-app.json"));
            return describeLocal(context, config, capability, packaged);
        } catch (FactoryException failure) {
            throw failure;
        } catch (Exception unavailable) {
            throw new FactoryException("SOURCE_UNAVAILABLE", "This app's installed identity or packaged capability budgets are unavailable.");
        }
    }

    @SuppressWarnings("deprecation")
    static NativeInstalledSource describeLocal(Context context, FactoryConfig config, String capability,
                                               String packaged) throws FactoryException {
        if (context == null || config == null || capability == null ||
                !config.appId.equals(context.getPackageName()) || !config.capabilities.contains(capability))
            throw new FactoryException("CAPABILITY_REQUIRED", "The installed application must declare this capability.");
        try {
            if (packaged == null || packaged.getBytes(StandardCharsets.UTF_8).length > 8192)
                throw new SecurityException("Packaged configuration is unavailable");
            FactoryConfig installed = FactoryConfig.parse(packaged, context.getPackageName());
            if (!installed.appId.equals(config.appId) || !installed.capabilities.equals(config.capabilities) ||
                    !installed.capabilities.contains(capability) ||
                    !ResourceBudgetsJson.toJson(installed.resources).toString().equals(ResourceBudgetsJson.toJson(config.resources).toString()))
                throw new SecurityException("Packaged configuration identity changed");
            PackageInfo own = context.getPackageManager().getPackageInfo(context.getPackageName(),
                    Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
            Signature[] signers = Build.VERSION.SDK_INT >= 28
                    ? own.signingInfo == null ? null : own.signingInfo.getApkContentsSigners() : own.signatures;
            if (!context.getPackageName().equals(own.packageName) || signers == null || signers.length != 1)
                throw new SecurityException("Installed package signer is unavailable");
            String certificate = digest(signers[0].toByteArray());
            long version = Build.VERSION.SDK_INT >= 28 ? own.getLongVersionCode() : own.versionCode;
            if (version < 1) throw new SecurityException("Installed package version is unavailable");
            String configDigest = digest(packaged.getBytes(StandardCharsets.UTF_8));
            String artifact = digest(("factory-local-app-v1\n" + own.packageName + "\n" + certificate + "\n" + version + "\n" + configDigest).getBytes(StandardCharsets.UTF_8));
            String record = digest(("factory-local-policy-v1\n" + capability + "\n" + configDigest + "\n" +
                    ResourceBudgetsJson.toJson(installed.resources)).getBytes(StandardCharsets.UTF_8));
            return new NativeInstalledSource(installed.appId, certificate, artifact, record, version, installed.resources);
        } catch (FactoryException failure) {
            throw failure;
        } catch (Exception unavailable) {
            throw new FactoryException("SOURCE_UNAVAILABLE", "This app's installed identity or packaged capability budgets are unavailable.");
        }
    }

    private static String digest(byte[] value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(64);
            for (byte b : bytes) result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return result.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static NativeInstalledSource describeInternal(Context context,FactoryConfig config,String capability,
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
            requireCurrent(current, deadline);
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://" + config.documentBroker.packageName + ".factory.operations"),
                    "describe_installed", null, input);
            requireCurrent(current, deadline);
            HashSet<String> expectedKeys=new HashSet<>(Arrays.asList("protocolVersion", "nonce", "capability", "deadlineElapsedMs",
                    "appId", "certificateSha256", "apkSha256", "recordSha256", "versionCode", "resourceBudgets"));
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
            return new NativeInstalledSource(config.appId, certificate, apk, record, (Long) version, resources);
        } catch (Exception unavailable) {
            throw new FactoryException("SOURCE_UNAVAILABLE", "This operation requires Jarvys installed and active; signed installed-source authority is unavailable.");
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
