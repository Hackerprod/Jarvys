package com.jarvys.factory.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import static org.junit.Assert.*;

/** Offline fakes only: these tests do not certify AndroidKeyStore or disk durability on a device. */
public final class NativeSecretsTest {
    private static final String APP = "com.example.generated", VALUE = "private-fixture-token";
    private static final NativeOperationAuthorization.Access ALLOW = () -> { };
    private interface Action { void run() throws Exception; }
    private static void code(String expected, Action action) throws Exception {
        try { action.run(); fail("Expected " + expected); }
        catch (FactoryException failure) {
            assertEquals(expected, failure.code); assertFalse(failure.getMessage().contains(VALUE)); assertNull(failure.getCause());
        }
    }
    private static final class Backend implements NativeSecrets.Backend {
        byte[] saved;
        boolean failWrite, failRead, failDelete;
        int writes, reads, deletes;
        Runnable afterWrite;
        @Override public byte[] read() throws IOException {
            reads++; if (failRead) throw new IOException(VALUE); return saved == null ? null : saved.clone();
        }
        @Override public void write(byte[] ciphertext) throws IOException {
            if (failWrite) throw new IOException(VALUE);
            writes++; saved = ciphertext.clone(); if (afterWrite != null) afterWrite.run();
        }
        @Override public void delete() throws IOException {
            if (failDelete) throw new IOException(VALUE); deletes++; saved = null;
        }
    }
    private static final class FakeKeystore implements NativeSecrets.Crypto {
        SecretKey key;
        boolean invalidated;
        int creations, deletions;
        private void usable() throws FactoryException {
            if (invalidated) throw new FactoryException("SECRET_KEY_INVALIDATED", "Synthetic invalidated key.");
            if (key == null) throw new FactoryException("SECRET_KEY_LOST", "Synthetic missing key.");
        }
        @Override public byte[] encrypt(byte[] plaintext, byte[] aad, boolean create) throws FactoryException {
            try {
                if (key == null && create && !invalidated) {
                    KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
                    key = generator.generateKey(); creations++;
                }
                usable();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad);
                byte[] encrypted = cipher.doFinal(plaintext), result = Arrays.copyOf(cipher.getIV(), 12 + encrypted.length);
                System.arraycopy(encrypted, 0, result, 12, encrypted.length); return result;
            } catch (FactoryException failure) { throw failure; }
            catch (Exception failure) { throw new FactoryException("SECRET_KEY_UNAVAILABLE", "Synthetic crypto unavailable."); }
        }
        @Override public byte[] decrypt(byte[] encrypted, byte[] aad) throws FactoryException {
            usable();
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, encrypted, 0, 12)); cipher.updateAAD(aad);
                return cipher.doFinal(encrypted, 12, encrypted.length - 12);
            } catch (Exception failure) { throw new FactoryException("SECRET_CORRUPT", "Synthetic authentication failure."); }
        }
        @Override public void deleteKey() { deletions++; key = null; invalidated = false; }
    }
    private static final class Rig {
        final Backend backend = new Backend();
        final FakeKeystore crypto = new FakeKeystore();
        final NativeSecrets secrets;
        Rig() { this(NativeSecrets.Limits.defaults()); }
        Rig(NativeSecrets.Limits limits) { secrets = new NativeSecrets(backend, crypto, APP, limits, new SecureRandom()); }
        String put() throws Exception { return secrets.put(SecretRequest.parsePut(SecretRequestTest.put()), ALLOW).getString("id"); }
    }

    @Test public void emptyReadsDoNotCreateKeysOrFiles() throws Exception {
        Rig rig = new Rig(); assertEquals(0, rig.secrets.list(ALLOW).length());
        assertEquals(0, rig.crypto.creations); assertNull(rig.backend.saved);
    }
    @Test public void putProducesOpaqueReceiptAndCiphertextOnlyPersistence() throws Exception {
        Rig rig = new Rig(); String id = rig.put();
        assertTrue(id.matches("sec_[a-f0-9]{32}")); assertEquals(1, rig.crypto.creations);
        assertFalse(new String(rig.backend.saved, StandardCharsets.ISO_8859_1).contains(VALUE));
        assertFalse(new String(rig.backend.saved, StandardCharsets.ISO_8859_1).contains("Example token"));
        JSONObject item = rig.secrets.describe(id, ALLOW);
        assertEquals(3, item.length()); assertTrue(item.has("id")); assertTrue(item.has("label")); assertTrue(item.has("scope"));
        assertFalse(item.toString().contains(VALUE)); assertFalse(rig.secrets.list(ALLOW).toString().contains(VALUE));
    }
    @Test public void explicitGetReturnsPlaintextAndDoesNotPretendToHideItFromJs() throws Exception {
        Rig rig = new Rig(); String id = rig.put(); assertEquals(VALUE, rig.secrets.get(id, ALLOW));
    }
    @Test public void unicodeAndNulCanBeStoredReadBackButNotInjectedAsHttpHeader() throws Exception {
        Rig rig = new Rig(); String secret = "contraseña🔑\0";
        String id = rig.secrets.put(SecretRequest.parsePut(SecretRequestTest.put().put("value", secret)), ALLOW).getString("id");
        assertEquals(secret, rig.secrets.get(id, ALLOW));
        code("SECRET_HEADER_INVALID", () -> rig.secrets.inject(id, new URL("https://api.example.test/"), "GET", ALLOW, (n, v) -> fail()));
    }
    @Test public void capturedCharArraysAreClearedAndReceiptNeverReturnsValue() throws Exception {
        Rig rig = new Rig(); char[] input = VALUE.toCharArray();
        JSONObject result = rig.secrets.capture(SecretRequest.parseCapture(SecretRequestTest.capture()), input, ALLOW);
        assertArrayEquals(new char[input.length], input); assertFalse(result.toString().contains(VALUE));
        assertEquals(VALUE, rig.secrets.get(result.getString("id"), ALLOW));
    }
    @Test public void captureClearsCallerInputOnInvalidRequestAndRevocation() throws Exception {
        Rig rig = new Rig(); char[] input = VALUE.toCharArray();
        code("INVALID_ARGUMENT", () -> rig.secrets.capture(null, input, ALLOW)); assertArrayEquals(new char[input.length], input);
        char[] denied = VALUE.toCharArray();
        try { rig.secrets.capture(SecretRequest.parseCapture(SecretRequestTest.capture()), denied, () -> { throw new SecurityException(VALUE); }); fail(); }
        catch (SecurityException expected) { assertFalse(expected.getMessage().contains(VALUE)); }
        assertArrayEquals(new char[denied.length], denied); assertNull(rig.backend.saved);
    }
    @Test public void putClearsParsedCopyOnDeniedAccess() throws Exception {
        Rig rig = new Rig(); SecretRequest.Put request = SecretRequest.parsePut(SecretRequestTest.put());
        try { rig.secrets.put(request, null); fail(); } catch (SecurityException expected) { }
        code("CANCELLED", request::copyValue); assertNull(rig.backend.saved);
    }
    @Test public void updatesKeepOpaqueIdAndRandomizeCiphertextWithSameKey() throws Exception {
        Rig rig = new Rig(); String id = rig.put(); byte[] old = rig.backend.saved.clone();
        JSONObject next = rig.secrets.put(SecretRequest.parsePut(SecretRequestTest.put().put("replaceId", id)), ALLOW);
        assertEquals(id, next.getString("id")); assertEquals(1, rig.crypto.creations);
        assertFalse(Arrays.equals(old, rig.backend.saved)); assertEquals(1, rig.secrets.list(ALLOW).length());
    }
    @Test public void replacementRequiresExistingIdAndNewValue() throws Exception {
        Rig rig = new Rig();
        code("SECRET_NOT_FOUND", () -> rig.secrets.put(SecretRequest.parsePut(SecretRequestTest.put()
                .put("replaceId", "sec_0123456789abcdef0123456789abcdef")), ALLOW));
        assertNull(rig.backend.saved); assertEquals(0, rig.crypto.creations);
    }
    @Test public void helperRecreationReadsSameVaultWithoutCachingPlaintext() throws Exception {
        Rig rig = new Rig(); String id = rig.put();
        NativeSecrets reopened = new NativeSecrets(rig.backend, rig.crypto, APP, NativeSecrets.Limits.defaults(), new SecureRandom());
        assertEquals(VALUE, reopened.get(id, ALLOW));
        reopened.remove(id, ALLOW); code("SECRET_NOT_FOUND", () -> rig.secrets.get(id, ALLOW));
    }
    @Test public void changedAppIdentityCannotDecryptCopiedVaultEvenWithSameFakeKey() throws Exception {
        Rig rig = new Rig(); String id = rig.put(); byte[] before = rig.backend.saved.clone();
        NativeSecrets other = new NativeSecrets(rig.backend, rig.crypto, "com.example.other", NativeSecrets.Limits.defaults(), new SecureRandom());
        code("SECRET_CORRUPT", () -> other.get(id, ALLOW)); assertArrayEquals(before, rig.backend.saved);
    }
    @Test public void separateAppBackendsCannotReadEachOthersOpaqueIds() throws Exception {
        Rig one = new Rig(), two = new Rig(); String id = one.put();
        code("SECRET_NOT_FOUND", () -> two.secrets.get(id, ALLOW)); assertNull(two.backend.saved);
    }
    @Test public void ciphertextAuthenticationRejectsTamperingWithoutReset() throws Exception {
        Rig rig = new Rig(); String id = rig.put(); rig.backend.saved[rig.backend.saved.length - 1] ^= 1;
        byte[] before = rig.backend.saved.clone(); code("SECRET_CORRUPT", () -> rig.secrets.get(id, ALLOW));
        assertArrayEquals(before, rig.backend.saved); assertEquals(1, rig.crypto.creations); assertEquals(0, rig.crypto.deletions);
    }
    @Test public void missingKeyNeverReplacedWhenCiphertextExists() throws Exception {
        Rig rig = new Rig(); String id = rig.put(); byte[] before = rig.backend.saved.clone(); rig.crypto.key = null;
        code("SECRET_KEY_LOST", () -> rig.secrets.get(id, ALLOW)); code("SECRET_KEY_LOST", rig::put);
        assertEquals(1, rig.crypto.creations); assertArrayEquals(before, rig.backend.saved);
    }
    @Test public void invalidatedKeyFailsClosedUntilExplicitReset() throws Exception {
        Rig rig = new Rig(); String id = rig.put(); byte[] before = rig.backend.saved.clone(); rig.crypto.invalidated = true;
        code("SECRET_KEY_INVALIDATED", () -> rig.secrets.get(id, ALLOW)); code("SECRET_KEY_INVALIDATED", rig::put);
        assertArrayEquals(before, rig.backend.saved); rig.secrets.reset(ALLOW);
        assertNull(rig.backend.saved); assertNull(rig.crypto.key);
        String next = rig.put(); assertNotEquals(id, next); assertEquals(2, rig.crypto.creations);
    }
    @Test public void unreadableCiphertextIsNotTreatedAsAnEmptyVault() throws Exception {
        Rig rig = new Rig(); rig.backend.saved = new byte[3]; byte[] before = rig.backend.saved.clone();
        code("SECRET_CORRUPT", rig::put); assertArrayEquals(before, rig.backend.saved); assertEquals(0, rig.crypto.creations);
    }
    @Test public void failedAtomicWritePreservesPreviousFakeSnapshotAndNoValueLeaks() throws Exception {
        Rig rig = new Rig(); String id = rig.put(); byte[] before = rig.backend.saved.clone(); rig.backend.failWrite = true;
        code("SECRET_IO", () -> rig.secrets.put(SecretRequest.parsePut(SecretRequestTest.put().put("replaceId", id).put("value", "new")), ALLOW));
        assertArrayEquals(before, rig.backend.saved); assertEquals(VALUE, rig.secrets.get(id, ALLOW));
    }
    @Test public void failedReadDoesNotOverwriteStorageOrLeakBackendException() throws Exception {
        Rig rig = new Rig(); rig.put(); byte[] before = rig.backend.saved.clone(); rig.backend.failRead = true;
        code("SECRET_IO", rig::put); assertArrayEquals(before, rig.backend.saved);
    }
    @Test public void failedResetDoesNotDeleteTheKeyBeforeCiphertextAbsence() throws Exception {
        Rig rig = new Rig(); String id = rig.put(); rig.backend.failDelete = true;
        code("SECRET_IO", () -> rig.secrets.reset(ALLOW)); assertEquals(0, rig.crypto.deletions);
        assertEquals(VALUE, rig.secrets.get(id, ALLOW));
    }
    @Test public void removalIsPersistentAndCannotBecomeReadback() throws Exception {
        Rig rig = new Rig(); String id = rig.put(); rig.secrets.remove(id, ALLOW);
        assertEquals(0, rig.secrets.list(ALLOW).length()); code("SECRET_NOT_FOUND", () -> rig.secrets.get(id, ALLOW));
        code("SECRET_NOT_FOUND", () -> rig.secrets.remove(id, ALLOW));
    }
    @Test public void entryValueAndAggregateLimitsRejectWithoutMutatingOldVault() throws Exception {
        Rig one = new Rig(new NativeSecrets.Limits(1, 32, 16384)); one.put(); byte[] before = one.backend.saved.clone();
        code("SECRET_LIMIT", one::put); assertArrayEquals(before, one.backend.saved);
        Rig tiny = new Rig(new NativeSecrets.Limits(2, 8, 16384)); code("SECRET_LIMIT", tiny::put); assertNull(tiny.backend.saved);
        Rig bytes = new Rig(new NativeSecrets.Limits(2, 32768, 40960));
        JSONObject input = SecretRequestTest.put().put("value", repeat('x', 32768));
        bytes.secrets.put(SecretRequest.parsePut(input), ALLOW); byte[] snapshot = bytes.backend.saved.clone();
        code("SECRET_LIMIT", () -> bytes.secrets.put(SecretRequest.parsePut(input), ALLOW)); assertArrayEquals(snapshot, bytes.backend.saved);
    }
    @Test public void liveAuthorityIsCheckedAgainBeforePersistence() throws Exception {
        Rig rig = new Rig(); int[] checks = {0};
        NativeOperationAuthorization.Access access = () -> { if (++checks[0] >= 4) throw new SecurityException(VALUE); };
        try { rig.secrets.put(SecretRequest.parsePut(SecretRequestTest.put()), access); fail(); }
        catch (SecurityException expected) { assertFalse(expected.getMessage().contains(VALUE)); }
        assertNull(rig.backend.saved);
    }
    @Test public void revocationAfterCommitWithholdsReceiptWithoutPretendingRollback() throws Exception {
        Rig rig = new Rig(); boolean[] revoked = {false}; rig.backend.afterWrite = () -> revoked[0] = true;
        try { rig.secrets.put(SecretRequest.parsePut(SecretRequestTest.put()), () -> { if (revoked[0]) throw new SecurityException(); }); fail(); }
        catch (SecurityException expected) { }
        assertEquals(1, rig.secrets.list(ALLOW).length()); assertNotNull(rig.backend.saved);
    }
    @Test public void getAndMetadataRequireLiveAccessAndNeverUseAStaleCache() throws Exception {
        Rig rig = new Rig(); String id = rig.put(); int[] checks = {0};
        try { rig.secrets.get(id, () -> { if (++checks[0] == 2) throw new SecurityException(); }); fail(); }
        catch (SecurityException expected) { }
        for (Action action : new Action[]{() -> rig.secrets.get(id, null), () -> rig.secrets.list(null),
                () -> rig.secrets.describe(id, null), () -> rig.secrets.remove(id, null), () -> rig.secrets.reset(null)}) {
            try { action.run(); fail(); } catch (SecurityException expected) { }
        }
        assertEquals(VALUE, rig.secrets.get(id, ALLOW));
    }
    @Test public void exactMatchingInjectionOnlyPassesHeaderToNativeSink() throws Exception {
        Rig rig = new Rig(); String id = rig.put(); String[] received = new String[2];
        rig.secrets.inject(id, new URL("https://api.example.test/v1"), "POST", ALLOW, (name, value) -> { received[0] = name; received[1] = value; });
        assertEquals("authorization", received[0]); assertEquals("Bearer " + VALUE, received[1]);
        assertFalse(rig.secrets.describe(id, ALLOW).toString().contains(VALUE));
    }
    @Test public void redirectsCannotForwardCredentialToWrongOriginMethodOrCleartext() throws Exception {
        Rig rig = new Rig(); String id = rig.put(); int[] calls = {0};
        NativeSecrets.HeaderSink sink = (name, value) -> calls[0]++;
        rig.secrets.inject(id, new URL("https://api.example.test/first"), "POST", ALLOW, sink);
        rig.secrets.inject(id, new URL("https://api.example.test/second"), "GET", ALLOW, sink);
        for (String url : new String[]{"https://other.example.test/", "https://api.example.test:8443/", "http://api.example.test/"})
            code("SECRET_SCOPE_MISMATCH", () -> rig.secrets.inject(id, new URL(url), "GET", ALLOW, sink));
        code("SECRET_SCOPE_MISMATCH", () -> rig.secrets.inject(id, new URL("https://api.example.test/"), "DELETE", ALLOW, sink));
        assertEquals(2, calls[0]);
    }
    @Test public void redirectPostToGetRewritesNeedScopeForActualNewMethod() throws Exception {
        Rig rig = new Rig(); JSONObject source = SecretRequestTest.put();
        source.getJSONObject("scope").put("methods", new JSONArray().put("POST"));
        String id = rig.secrets.put(SecretRequest.parsePut(source), ALLOW).getString("id");
        code("SECRET_SCOPE_MISMATCH", () -> rig.secrets.inject(id, new URL("https://api.example.test/redirected"), "GET", ALLOW, (n, v) -> fail()));
    }
    @Test public void revokedInjectionNeverInvokesSinkAndSinkErrorNeverEchoesValue() throws Exception {
        Rig rig = new Rig(); String id = rig.put(); int[] checks = {0};
        try { rig.secrets.inject(id, new URL("https://api.example.test/"), "GET", () -> { if (++checks[0] == 2) throw new SecurityException(); }, (n, v) -> fail()); fail(); }
        catch (SecurityException expected) { }
        code("SECRET_INJECTION_FAILED", () -> rig.secrets.inject(id, new URL("https://api.example.test/"), "GET", ALLOW,
                (n, v) -> { throw new IOException(v); }));
    }
    @Test public void injectionRejectsControlCharactersWithoutTryingToCleanThem() throws Exception {
        Rig rig = new Rig(); String id = rig.secrets.put(SecretRequest.parsePut(SecretRequestTest.put().put("value", "token\r\nInjected: yes")), ALLOW).getString("id");
        code("SECRET_HEADER_INVALID", () -> rig.secrets.inject(id, new URL("https://api.example.test/"), "GET", ALLOW, (n, v) -> fail()));
    }
    private static String repeat(char character, int count) { char[] chars = new char[count]; Arrays.fill(chars, character); return new String(chars); }
}
