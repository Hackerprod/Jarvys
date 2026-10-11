package com.jarvys.factory.runtime;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * App-private encrypted credential storage. Invoke on the runtime's bounded worker.
 * All operations require their own live unified-policy Access; this helper never prompts.
 * Native capture/injection avoids intentional readback. Explicit get returns plaintext.
 */
public final class NativeSecrets {
    private static final int MAGIC = 0x4a535631, VERSION = 1;
    public static final int MAX_ENCRYPTED_BYTES = 4 * 1024 * 1024 + 64;
    // Serializes every production adapter instance in one process. Generated apps have their own UID.
    private static final Object STORAGE_LOCK = new Object();
    public interface Backend {
        /** null means absent; all returned bytes are ciphertext, never plaintext. */
        byte[] read() throws IOException;
        /** Atomic old-or-new ciphertext; a post-commit verification failure may be indeterminate. */
        void write(byte[] ciphertext) throws IOException;
        /** Returns only after absence is verified. */
        void delete() throws IOException;
    }
    public interface Crypto {
        byte[] encrypt(byte[] plaintext, byte[] aad, boolean createIfMissing) throws FactoryException;
        byte[] decrypt(byte[] ciphertext, byte[] aad) throws FactoryException;
        void deleteKey() throws FactoryException;
    }
    public interface HeaderSink { void set(String name, String value) throws Exception; }
    public static final class Limits {
        public final int entries, valueBytes, vaultBytes;
        public Limits(int entries, int valueBytes, int vaultBytes) {
            if (entries < 1 || entries > 256 || valueBytes < 1 || valueBytes > SecretRequest.MAX_VALUE_BYTES
                    || vaultBytes < valueBytes + 8192 || vaultBytes > 4 * 1024 * 1024) throw new IllegalArgumentException("Invalid secret resource limits");
            this.entries = entries; this.valueBytes = valueBytes; this.vaultBytes = vaultBytes;
        }
        public static Limits defaults() { return new Limits(32, 8192, 512 * 1024); }
    }
    private final Backend backend;
    private final Crypto crypto;
    private final Limits limits;
    private final SecureRandom random;
    private final byte[] aad;

    public NativeSecrets(Context context, String installedAppId) throws FactoryException {
        this(context, installedAppId, Limits.defaults());
    }
    public NativeSecrets(Context context, String installedAppId, Limits limits) throws FactoryException {
        this(AndroidSecretBackend.create(context, installedAppId), installedAppId, limits);
    }
    private NativeSecrets(AndroidSecretBackend adapter, String appId, Limits limits) {
        this(adapter, adapter, appId, limits, new SecureRandom());
    }
    /** Dependency injection for offline tests; production must use the Context constructor. */
    NativeSecrets(Backend backend, Crypto crypto, String appId, Limits limits, SecureRandom random) {
        if (backend == null || crypto == null || limits == null || random == null
                || appId == null || !appId.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*){1,15}")
                || appId.length() > 160) throw new IllegalArgumentException("Invalid secret storage identity");
        this.backend = backend; this.crypto = crypto; this.limits = limits; this.random = random;
        aad = ("Jarvys.Factory.GeneratedApp.Secrets.v1\0" + appId).getBytes(StandardCharsets.UTF_8);
    }

    /** Consumes and clears the parsed native copy even on failure. JSON/JS strings cannot be erased here. */
    public JSONObject put(SecretRequest.Put request, NativeOperationAuthorization.Access access) throws FactoryException {
        if (request == null) throw SecretRequest.invalid();
        byte[] value = null;
        try { check(access); value = request.copyValue(); return save(request, value, access); }
        finally { wipe(value); request.close(); }
    }
    /** Consumes and clears the caller-owned native char array. No value is returned in the receipt. */
    public JSONObject capture(SecretRequest.Capture request, char[] input, NativeOperationAuthorization.Access access) throws FactoryException {
        byte[] value = null;
        try {
            check(access);
            if (request == null) throw SecretRequest.invalid();
            value = SecretRequest.encode(input); return save(request, value, access);
        } finally { wipe(value); if (input != null) Arrays.fill(input, '\0'); }
    }
    private JSONObject save(SecretRequest.Capture request, byte[] value, NativeOperationAuthorization.Access access) throws FactoryException {
        if (value.length > limits.valueBytes) throw tooLarge();
        synchronized (STORAGE_LOCK) {
            check(access);
            try (Vault vault = load()) {
                String id = request.replaceId;
                if (id == null) {
                    if (vault.entries.size() >= limits.entries) throw tooLarge();
                    id = newId(vault);
                } else if (!vault.entries.containsKey(id)) throw missing();
                Entry entry = new Entry(id, request.label, request.scope, value.clone());
                Entry previous = vault.entries.put(id, entry);
                if (previous != null) previous.close();
                persist(vault, access);
                return entry.metadata();
            }
        }
    }
    /** Explicit plaintext readback. Integration must use secrets.get's own policy operation. */
    public String get(String id, NativeOperationAuthorization.Access access) throws FactoryException {
        SecretRequest.id(id);
        synchronized (STORAGE_LOCK) {
            check(access);
            try (Vault vault = load()) {
                Entry entry = find(vault, id); check(access);
                String result = SecretRequest.decode(entry.value); check(access); return result;
            }
        }
    }
    public JSONArray list(NativeOperationAuthorization.Access access) throws FactoryException {
        synchronized (STORAGE_LOCK) {
            check(access);
            try (Vault vault = load()) {
                JSONArray result = new JSONArray();
                for (Entry entry : vault.entries.values()) result.put(entry.metadata());
                check(access); return result;
            }
        }
    }
    public JSONObject describe(String id, NativeOperationAuthorization.Access access) throws FactoryException {
        SecretRequest.id(id);
        synchronized (STORAGE_LOCK) {
            check(access);
            try (Vault vault = load()) { JSONObject result = find(vault, id).metadata(); check(access); return result; }
        }
    }
    public void remove(String id, NativeOperationAuthorization.Access access) throws FactoryException {
        SecretRequest.id(id);
        synchronized (STORAGE_LOCK) {
            check(access);
            try (Vault vault = load()) {
                Entry entry = vault.entries.remove(id);
                if (entry == null) throw missing();
                entry.close(); persist(vault, access);
            }
        }
    }
    /** App-requested recovery/discard. Loss is explicit; nothing is automatically reset on read failure. */
    public void reset(NativeOperationAuthorization.Access access) throws FactoryException {
        synchronized (STORAGE_LOCK) {
            check(access);
            try { backend.delete(); }
            catch (IOException failure) { throw io(); }
            // This is completion of the same reset, not a new user operation. Once ciphertext is
            // deleted, finish key cleanup even if the lease ends; it cannot expose or recover data.
            crypto.deleteKey();
        }
    }
    /**
     * Inject ONLY into a freshly created native connection; never add to the JS request/header map.
     * For redirects, discard the old connection/header and call again with the actual new method/URL.
     * Mismatched redirects fail closed. A new explicit request can use its own scoped credential.
     */
    public void inject(String id, URL destination, String method, NativeOperationAuthorization.Access access,
                       HeaderSink sink) throws FactoryException {
        SecretRequest.id(id);
        if (sink == null) throw SecretRequest.invalid();
        synchronized (STORAGE_LOCK) {
            check(access);
            try (Vault vault = load()) {
                Entry entry = find(vault, id);
                if (!entry.scope.permits(destination, method))
                    throw new FactoryException("SECRET_SCOPE_MISMATCH", "Credential is not configured for this destination and method.");
                String value = SecretRequest.decode(entry.value);
                if (!SecretRequest.asciiField(value))
                    throw new FactoryException("SECRET_HEADER_INVALID", "Credential cannot be represented as an HTTP field value.");
                check(access);
                try { sink.set(entry.scope.header, entry.scope.prefix + value); }
                catch (Exception failure) { throw new FactoryException("SECRET_INJECTION_FAILED", "Native credential injection could not complete."); }
                check(access);
            }
        }
    }

    private Vault load() throws FactoryException {
        final byte[] ciphertext;
        try { ciphertext = backend.read(); }
        catch (IOException failure) { throw io(); }
        if (ciphertext == null) return new Vault(true);
        if (ciphertext.length < 28 || ciphertext.length > MAX_ENCRYPTED_BYTES) throw corrupt();
        byte[] plain = null;
        try {
            plain = crypto.decrypt(ciphertext, aad);
            if (plain == null || plain.length > limits.vaultBytes) throw corrupt();
            return deserialize(plain);
        } finally { wipe(plain); wipe(ciphertext); }
    }
    private void persist(Vault vault, NativeOperationAuthorization.Access access) throws FactoryException {
        byte[] plain = null, ciphertext = null;
        try {
            plain = serialize(vault); check(access);
            ciphertext = crypto.encrypt(plain, aad, vault.fresh);
            if (ciphertext == null || ciphertext.length < 28 || ciphertext.length > MAX_ENCRYPTED_BYTES) throw corrupt();
            check(access);
            backend.write(ciphertext);
            check(access);
        } catch (IOException failure) { throw io(); }
        finally { wipe(plain); wipe(ciphertext); }
    }
    private Vault deserialize(byte[] plain) throws FactoryException {
        Vault vault = new Vault(false);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plain))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw corrupt();
            int count = input.readInt();
            if (count < 0 || count > limits.entries) throw corrupt();
            for (int i = 0; i < count; i++) {
                String id = SecretRequest.id(readText(input, 64));
                String label = readText(input, 320);
                SecretRequest.Scope scope = SecretRequest.Scope.parse(StrictJson.object(readText(input, 8192), 8192));
                // Validate labels through the same public grammar, without ever materializing the value as JSON.
                SecretRequest.parseCapture(new JSONObject().put("label", label).put("scope", scope.metadata()));
                byte[] value = readBytes(input, limits.valueBytes);
                if (value.length == 0 || vault.entries.containsKey(id)) { wipe(value); throw corrupt(); }
                vault.entries.put(id, new Entry(id, label, scope, value));
            }
            if (input.read() != -1) throw corrupt();
            return vault;
        } catch (Exception failure) { vault.close(); throw corrupt(); }
    }
    private byte[] serialize(Vault vault) throws FactoryException {
        try (ClearingBuffer buffer = new ClearingBuffer(limits.vaultBytes);
             DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeInt(MAGIC); output.writeInt(VERSION); output.writeInt(vault.entries.size());
            for (Entry entry : vault.entries.values()) {
                writeText(output, entry.id); writeText(output, entry.label);
                writeText(output, entry.scope.metadata().toString());
                output.writeInt(entry.value.length); output.write(entry.value);
            }
            output.flush();
            return buffer.toByteArray();
        } catch (CapacityExceeded failure) { throw tooLarge(); }
        catch (IOException failure) { throw io(); }
    }
    private String newId(Vault vault) throws FactoryException {
        byte[] bytes = new byte[16];
        try {
            for (int attempt = 0; attempt < 8; attempt++) {
                random.nextBytes(bytes);
                StringBuilder id = new StringBuilder("sec_");
                for (byte item : bytes) id.append(Character.forDigit((item >>> 4) & 15, 16)).append(Character.forDigit(item & 15, 16));
                if (!vault.entries.containsKey(id.toString())) return id.toString();
            }
            throw new FactoryException("SECRET_UNAVAILABLE", "Secret identifier could not be allocated.");
        } finally { wipe(bytes); }
    }
    private static Entry find(Vault vault, String id) throws FactoryException {
        Entry entry = vault.entries.get(id); if (entry == null) throw missing(); return entry;
    }
    private static void writeText(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try { output.writeInt(bytes.length); output.write(bytes); } finally { wipe(bytes); }
    }
    private static byte[] readBytes(DataInputStream input, int max) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > max || length > input.available()) throw new IOException();
        byte[] bytes = new byte[length]; input.readFully(bytes); return bytes;
    }
    private static String readText(DataInputStream input, int max) throws IOException, FactoryException {
        byte[] bytes = readBytes(input, max);
        try { return SecretRequest.decode(bytes); } finally { wipe(bytes); }
    }
    private static void check(NativeOperationAuthorization.Access access) {
        if (access == null) throw new SecurityException("Secret operation authority is required");
        try { access.check(); }
        catch (RuntimeException revoked) { throw new SecurityException("Secret operation authority ended"); }
    }
    private static final class Entry implements AutoCloseable {
        final String id, label;
        final SecretRequest.Scope scope;
        final byte[] value;
        Entry(String id, String label, SecretRequest.Scope scope, byte[] value) {
            this.id = id; this.label = label; this.scope = scope; this.value = value;
        }
        JSONObject metadata() throws FactoryException {
            try { return new JSONObject().put("id", id).put("label", label).put("scope", scope.metadata()); }
            catch (Exception failure) { throw new FactoryException("UNAVAILABLE", "Secret metadata unavailable."); }
        }
        @Override public void close() { wipe(value); }
    }
    private static final class Vault implements AutoCloseable {
        final boolean fresh;
        final Map<String, Entry> entries = new LinkedHashMap<>();
        Vault(boolean fresh) { this.fresh = fresh; }
        @Override public void close() { for (Entry entry : entries.values()) entry.close(); entries.clear(); }
    }
    private static final class CapacityExceeded extends RuntimeException { }
    private static final class ClearingBuffer extends ByteArrayOutputStream {
        private final int limit;
        // Avoid resize copies of plaintext. The configured ceiling is also the allocation bound.
        ClearingBuffer(int limit) { super(limit); this.limit = limit; }
        @Override public synchronized void write(int value) { if (count >= limit) throw new CapacityExceeded(); super.write(value); }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            if (length < 0 || length > limit - count) throw new CapacityExceeded(); super.write(bytes, offset, length);
        }
        @Override public void close() { Arrays.fill(buf, (byte) 0); reset(); }
    }
    private static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
    private static FactoryException missing() { return new FactoryException("SECRET_NOT_FOUND", "Secret identifier is unavailable."); }
    private static FactoryException corrupt() { return new FactoryException("SECRET_CORRUPT", "Secret storage is unreadable; it has not been reset."); }
    private static FactoryException tooLarge() { return new FactoryException("SECRET_LIMIT", "Secret storage exceeds the configured resource limits."); }
    private static FactoryException io() { return new FactoryException("SECRET_IO", "Secret storage could not complete."); }
}
