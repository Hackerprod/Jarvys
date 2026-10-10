package com.jarvys.factory.runtime;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import org.json.JSONObject;
import java.security.MessageDigest;

/** Build-owned pinning for the bounded human document broker. No caller-controlled package names. */
public final class DocumentBrokerIdentity {
    public static final String PRIMARY = "com.jarvys.agent";
    public static final String RECOVERY = "com.jarvys.agent.recoverytest";
    public static final String ACTIVITY = "com.jarvys.agent.apkfactory.FactoryDocumentActivity";
    private DocumentBrokerIdentity() { }
    public static boolean supported(String name) { return PRIMARY.equals(name) || RECOVERY.equals(name); }
    @SuppressWarnings("deprecation")
    public static String certificate(Context context, String name) throws Exception {
        if (!supported(name)) throw new FactoryException("UNAVAILABLE", "Unsupported document broker.");
        PackageInfo info = context.getPackageManager().getPackageInfo(name,
                Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        Signature[] signers = Build.VERSION.SDK_INT >= 28
                ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        if (signers == null || signers.length != 1) throw new FactoryException("UNAVAILABLE", "Document broker signature is unavailable.");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    public static JSONObject buildBinding(Context context) throws Exception {
        return new JSONObject().put("packageName", context.getPackageName())
                .put("certificateSha256", certificate(context, context.getPackageName()));
    }
    public static void verify(Context context, FactoryConfig.DocumentBroker broker) throws FactoryException {
        try {
            if (broker == null || !certificate(context, broker.packageName).equals(broker.certificateSha256))
                throw new FactoryException("UNAVAILABLE", "Install the matching Jarvys document broker before opening documents.");
            String other = PRIMARY.equals(broker.packageName) ? RECOVERY : PRIMARY;
            try {
                context.getPackageManager().getPackageInfo(other, 0);
                throw new FactoryException("UNAVAILABLE", "Documents require a single installed Jarvys host; two hosts cannot share human-only protection.");
            } catch (PackageManager.NameNotFoundException absent) { /* expected single host */ }
        } catch (FactoryException e) { throw e; }
        catch (Exception e) { throw new FactoryException("UNAVAILABLE", "The matching Jarvys document broker could not be verified."); }
    }
}
