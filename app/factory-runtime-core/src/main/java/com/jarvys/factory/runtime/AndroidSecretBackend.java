package com.jarvys.factory.runtime;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import androidx.core.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Production-only AndroidKeyStore AES key and app-private, backup-excluded ciphertext adapter. */
final class AndroidSecretBackend implements NativeSecrets.Backend, NativeSecrets.Crypto {
    private static final int NONCE_BYTES = 12, TAG_BITS = 128;
    private final AtomicFile file;
    private final String alias;

    static AndroidSecretBackend create(Context context, String installedAppId) throws FactoryException {
        if (context == null || installedAppId == null || !installedAppId.equals(context.getPackageName())
                || !installedAppId.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*){1,15}")
                || installedAppId.length() > 160)
            throw new FactoryException("INVALID_CONFIG", "Installed secret storage identity is required.");
        try {
            File root = context.getNoBackupFilesDir();
            if (root == null) throw new IOException();
            File directory = new File(root, "factory-secrets-v1");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException();
            // No asset, release-signing key, Jarvys vault or public/external storage is ever consulted.
            String alias = "jfsec.v1." + hex(MessageDigest.getInstance("SHA-256")
                    .digest(installedAppId.getBytes(StandardCharsets.UTF_8)));
            return new AndroidSecretBackend(new AtomicFile(new File(directory, "vault.bin")), alias);
        } catch (Exception failure) {
            throw new FactoryException("SECRET_IO", "Private secret storage is unavailable.");
        }
    }
    private AndroidSecretBackend(AtomicFile file, String alias) { this.file = file; this.alias = alias; }

    @Override public byte[] read() throws IOException {
        try (FileInputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192]; int count;
            while ((count = input.read(chunk)) != -1) {
                if (output.size() > NativeSecrets.MAX_ENCRYPTED_BYTES - count) throw new IOException("Secret storage size limit");
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        } catch (FileNotFoundException absent) {
            File base = file.getBaseFile();
            if (base.exists() || new File(base.getPath() + ".bak").exists()) throw new IOException("Secret storage cannot be read");
            return null;
        }
    }
    @Override public void write(byte[] ciphertext) throws IOException {
        if (ciphertext == null || ciphertext.length > NativeSecrets.MAX_ENCRYPTED_BYTES) throw new IOException("Invalid secret ciphertext");
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(ciphertext);
            // Detect write/sync failure while rollback is still possible. AtomicFile also syncs on finish.
            stream.getFD().sync();
            file.finishWrite(stream); stream = null;
            byte[] verified = read();
            try {
                if (verified == null || !MessageDigest.isEqual(ciphertext, verified)) throw new IOException("Secret commit could not be verified");
            } finally { if (verified != null) Arrays.fill(verified, (byte) 0); }
        } finally { if (stream != null) file.failWrite(stream); }
    }
    @Override public void delete() throws IOException {
        file.delete();
        File base = file.getBaseFile();
        if (base.exists() || new File(base.getPath() + ".bak").exists() || new File(base.getPath() + ".new").exists())
            throw new IOException("Secret reset could not be verified");
    }

    @Override public byte[] encrypt(byte[] plaintext, byte[] aad, boolean createIfMissing) throws FactoryException {
        try {
            SecretKey key = key(createIfMissing);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // Provider generates a fresh randomized nonce; callers cannot supply/reuse one.
            cipher.init(Cipher.ENCRYPT_MODE, key);
            cipher.updateAAD(aad);
            byte[] nonce = cipher.getIV();
            if (nonce == null || nonce.length != NONCE_BYTES) throw new GeneralSecurityException();
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] envelope = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
            Arrays.fill(encrypted, (byte) 0);
            return envelope;
        } catch (FactoryException failure) { throw failure; }
        catch (KeyPermanentlyInvalidatedException invalidated) { throw invalidated(); }
        catch (Exception failure) { throw keyUnavailable(); }
    }
    @Override public byte[] decrypt(byte[] envelope, byte[] aad) throws FactoryException {
        try {
            if (envelope.length < NONCE_BYTES + TAG_BITS / 8) throw new GeneralSecurityException();
            SecretKey key = key(false);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, envelope, 0, NONCE_BYTES));
            cipher.updateAAD(aad);
            return cipher.doFinal(envelope, NONCE_BYTES, envelope.length - NONCE_BYTES);
        } catch (FactoryException failure) { throw failure; }
        catch (KeyPermanentlyInvalidatedException invalidated) { throw invalidated(); }
        catch (AEADBadTagException tampered) {
            throw new FactoryException("SECRET_CORRUPT", "Secret storage authentication failed; it has not been reset.");
        } catch (Exception failure) { throw keyUnavailable(); }
    }
    private SecretKey key(boolean createIfMissing) throws FactoryException {
        try {
            KeyStore store = store();
            if (store.containsAlias(alias)) {
                Key key = store.getKey(alias, null);
                if (!(key instanceof SecretKey) || !"AES".equalsIgnoreCase(key.getAlgorithm())) throw new GeneralSecurityException();
                return (SecretKey) key;
            }
            if (!createIfMissing) throw new FactoryException("SECRET_KEY_LOST", "Secret encryption key is missing; storage has not been reset.");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false).build());
            return generator.generateKey();
        } catch (FactoryException failure) { throw failure; }
        catch (Exception failure) { throw keyUnavailable(); }
    }
    @Override public void deleteKey() throws FactoryException {
        try { store().deleteEntry(alias); }
        catch (Exception failure) { throw new FactoryException("SECRET_KEY_UNAVAILABLE", "Secret ciphertext was discarded; key cleanup could not be confirmed."); }
    }
    private static KeyStore store() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); return store;
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(Character.forDigit((item >>> 4) & 15, 16)).append(Character.forDigit(item & 15, 16));
        return value.toString();
    }
    private static FactoryException keyUnavailable() {
        return new FactoryException("SECRET_KEY_UNAVAILABLE", "Android secret encryption key is unavailable; storage has not been reset.");
    }
    private static FactoryException invalidated() {
        return new FactoryException("SECRET_KEY_INVALIDATED", "Android secret encryption key is invalidated; storage has not been reset.");
    }
}
