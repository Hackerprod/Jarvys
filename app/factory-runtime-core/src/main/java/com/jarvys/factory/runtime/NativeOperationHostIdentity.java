package com.jarvys.factory.runtime;

import android.content.Context;
import android.content.pm.ProviderInfo;

/** Shared exact pinned-host verification for foreground and cold background policy clients. */
final class NativeOperationHostIdentity {
    private NativeOperationHostIdentity() { }
    static int verify(Context context, FactoryConfig.DocumentBroker broker) throws Exception {
        if (broker == null) throw new SecurityException("No pinned policy host");
        DocumentBrokerIdentity.verify(context, broker);
        String name = broker.packageName;
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(name + ".factory.operations", 0);
        int uid = context.getPackageManager().getApplicationInfo(name, 0).uid;
        if (provider == null || !name.equals(provider.packageName) || !provider.exported || !provider.enabled ||
                provider.applicationInfo == null || provider.applicationInfo.uid != uid || !provider.applicationInfo.enabled ||
                !"com.jarvys.agent.apkfactory.FactoryOperationAuthorizationProvider".equals(provider.name) || !verifiedUid(context, broker, uid))
            throw new SecurityException("Invalid pinned policy host");
        return uid;
    }
    static boolean verifiedUid(Context context, FactoryConfig.DocumentBroker broker, int uid) {
        try {
            if (broker == null) return false;
            DocumentBrokerIdentity.verify(context, broker);
            String[] names = context.getPackageManager().getPackagesForUid(uid);
            return names != null && names.length == 1 && names[0].equals(broker.packageName) &&
                    context.getPackageManager().getApplicationInfo(names[0], 0).uid == uid;
        } catch (Exception unavailable) { return false; }
    }
}
