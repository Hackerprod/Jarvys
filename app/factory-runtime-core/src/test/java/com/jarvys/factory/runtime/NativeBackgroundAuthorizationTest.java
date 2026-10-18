package com.jarvys.factory.runtime;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Canonical source-identity grammar consumed by the host-side operation provider. */
public final class NativeBackgroundAuthorizationTest {
    private static final String APP = "org.example.generated";
    private static String hash(char value) { return new String(new char[64]).replace('\0', value); }
    private static NativeBackgroundAuthorization.SourceIdentity source() {
        return new NativeBackgroundAuthorization.SourceIdentity(APP, hash('a'), hash('b'), hash('c'), 42L);
    }

    @Test public void everySourceIdentityFieldChangesTheReceiptBindingAndKeyOrderDoesNot() throws Exception {
        NativeBackgroundAuthorization.SourceIdentity source = source();
        NativeOperationRequest operation = NativeOperationRequest.parse("notifications.cancel", "{\"id\":1}");
        String nonce = hash('f'), binding = operation.binding(nonce, source.bindingEvidence());
        String[] keys = {"appId", "certificateSha256", "apkSha256", "recordSha256", "versionCode"};
        Object[] values = {"example.other", hash('d'), hash('d'), hash('d'), 43L};
        for (int index = 0; index < keys.length; index++) {
            JSONObject changed = new JSONObject(source.canonicalJson()).put(keys[index], values[index]);
            NativeBackgroundAuthorization.SourceIdentity parsed = NativeBackgroundAuthorization.SourceIdentity.parse(changed.toString());
            assertNotEquals(keys[index], binding, operation.binding(nonce, parsed.bindingEvidence()));
        }
        JSONObject reordered = new JSONObject().put("versionCode", 42).put("recordSha256", hash('c')).put("apkSha256", hash('b'))
                .put("certificateSha256", hash('a')).put("appId", APP);
        assertEquals(source.bindingEvidence(), NativeBackgroundAuthorization.SourceIdentity.parse(reordered.toString()).bindingEvidence());
        assertNotEquals(binding, operation.binding(nonce));
    }

    @Test public void expectedSourceGrammarIsClosedBoundedAndTyped() throws Exception {
        NativeBackgroundAuthorization.SourceIdentity source = source();
        String[] invalid = {"{}", source.canonicalJson().replace("42", "42.5"), source.canonicalJson().replace("42", "\"42\""),
                new JSONObject(source.canonicalJson()).put("extra", true).toString(), source.canonicalJson().replace("42", "0"),
                source.canonicalJson().replace(hash('a'), hash('A')),
                source.canonicalJson().replace("\"appId\":", "\"appId\":\"example.duplicate\",\"appId\":"),
                " ".repeat(1025) + source.canonicalJson()};
        for (String raw : invalid)
            assertThrows(FactoryException.class, () -> NativeBackgroundAuthorization.SourceIdentity.parse(raw));
    }
}
