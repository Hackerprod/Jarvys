package com.jarvys.factory.runtime;

/**
 * Canonical source identity shared with Jarvys for receipt-bound host operations.
 * Generated-app background work uses local Android identity and local authority.
 */
public final class NativeBackgroundAuthorization {
    /** Expected descriptor identity; the host compares it with fresh installed-artifact proof. */
    public static final class SourceIdentity {
        public final String appId, certificateSha256, apkSha256, recordSha256;
        public final long versionCode;

        public SourceIdentity(String appId, String certificate, String apk, String record, long version) {
            if (appId == null || !appId.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") ||
                    !hash(certificate) || !hash(apk) || !hash(record) || version < 1)
                throw new IllegalArgumentException("Exact source identity required");
            this.appId = appId;
            certificateSha256 = certificate;
            apkSha256 = apk;
            recordSha256 = record;
            versionCode = version;
        }

        public String canonicalJson() {
            try {
                return new org.json.JSONObject().put("appId", appId).put("certificateSha256", certificateSha256)
                        .put("apkSha256", apkSha256).put("recordSha256", recordSha256).put("versionCode", versionCode).toString();
            } catch (org.json.JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        public String bindingEvidence() {
            return "factory-native-background-source-v1:" + canonicalJson();
        }

        public static SourceIdentity parse(String raw) throws FactoryException {
            try {
                org.json.JSONObject value = StrictJson.object(raw, 1024);
                FactoryConfig.exactKeys(value, "appId", "certificateSha256", "apkSha256", "recordSha256", "versionCode");
                if (value.length() != 5) throw new IllegalArgumentException();
                Object version = value.get("versionCode");
                if (!(version instanceof Long) && !(version instanceof Integer)) throw new IllegalArgumentException();
                return new SourceIdentity(FactoryConfig.string(value, "appId"), FactoryConfig.string(value, "certificateSha256"),
                        FactoryConfig.string(value, "apkSha256"), FactoryConfig.string(value, "recordSha256"), ((Number) version).longValue());
            } catch (Exception invalid) {
                throw new FactoryException("INVALID_ARGUMENT", "Invalid expected installed source identity.");
            }
        }

        private static boolean hash(String value) {
            return value != null && value.matches("[a-f0-9]{64}");
        }
    }

    private NativeBackgroundAuthorization() { }
}
