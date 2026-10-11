package com.jarvys.factory.runtime;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Release-pinned, runtime-owned resources. No dynamic paths, compiler or capability grant. */
public final class FactoryUiAssets {
    public static final String LIBRARY = "purecss";
    public static final String VERSION = "3.0.0";
    public static final String ROOT = "factory-ui/pure-3.0.0/";
    public static final int MAX_FILE_BYTES = 32 * 1024;
    public static final int MAX_TOTAL_BYTES = 64 * 1024;
    private static final Map<String, Entry> CATALOG;
    public static final int TOTAL_BYTES;
    static {
        Map<String, Entry> entries = new LinkedHashMap<>();
        entries.put(ROOT + "LICENSE.txt", new Entry(1548, "66fe80191874ef13229c0f0f87d28ac25f0942b32c002a1fe49d9cd331e6b3dc"));
        entries.put(ROOT + "PROVENANCE.json", new Entry(2288, "b06b401792a94d9c50a90872ed040a12a57b8b6bfd0ddc7b646e7ee06a7b2bdd"));
        entries.put(ROOT + "grids-responsive-min.css", new Entry(14282, "e980b19ca1e76eb125e894643fa43503b966933d2184df8b7d57de32c29ab441"));
        entries.put(ROOT + "normalize-LICENSE.txt", new Entry(1098, "89a9a9ace8be9e79bdc962823479bf980e4a14777ebe51b37e669f658e6f25ef"));
        entries.put(ROOT + "pure-min.css", new Entry(15721, "01757dff5e174cd963090a7bbb79f4d95222600202abca1c5e4735d6bbcc952c"));
        int total = 0;
        for (Entry entry : entries.values()) {
            if (entry.bytes > MAX_FILE_BYTES) throw new IllegalStateException("UI resource exceeds bound");
            total += entry.bytes;
        }
        if (total > MAX_TOTAL_BYTES) throw new IllegalStateException("UI catalog exceeds bound");
        TOTAL_BYTES = total;
        CATALOG = Collections.unmodifiableMap(entries);
    }
    private FactoryUiAssets() { }
    public static Set<String> paths() { return CATALOG.keySet(); }
    public static boolean contains(String path) { return CATALOG.containsKey(path); }
    public static boolean isNamespace(String path) {
        return path != null && (path.equals("factory-ui") || path.startsWith("factory-ui/"));
    }
    public static int size(String path) { return entry(path).bytes; }
    public static String sha256(String path) { return entry(path).sha256; }
    private static Entry entry(String path) {
        Entry value = CATALOG.get(path);
        if (value == null) throw new IllegalArgumentException("Unknown runtime UI resource");
        return value;
    }
    public static void verify(String path, byte[] bytes) {
        Entry expected = entry(path);
        if (bytes == null || bytes.length != expected.bytes || !digest(bytes).equals(expected.sha256))
            throw new IllegalArgumentException("Runtime UI resource differs from pinned catalog");
    }
    public static void verifyComplete(Map<String, byte[]> assets) {
        if (!assets.keySet().equals(paths())) throw new IllegalArgumentException("Incomplete or unknown runtime UI catalog");
        for (String path : paths()) verify(path, assets.get(path));
    }
    /** Reads at most the exact pinned length plus one byte; does not close the caller's stream. */
    public static byte[] read(String path, InputStream input) throws IOException {
        int size = size(path);
        ByteArrayOutputStream output = new ByteArrayOutputStream(size);
        byte[] buffer = new byte[4096];
        while (true) {
            int count = input.read(buffer, 0, Math.min(buffer.length, size + 1 - output.size()));
            if (count < 0) break;
            if (count == 0) throw new IOException("UI resource stream made no progress");
            output.write(buffer, 0, count);
            if (output.size() > size) throw new IOException("UI resource exceeds pinned length");
        }
        byte[] bytes = output.toByteArray();
        verify(path, bytes);
        return bytes;
    }
    /** Duplicate/extra/wrong-version namespace entries fail before map construction can hide them. */
    public static Map<String, byte[]> fromApk(byte[] apk) throws IOException {
        Map<String, byte[]> assets = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(apk))) {
            ZipEntry item;
            while ((item = zip.getNextEntry()) != null) {
                String name = item.getName();
                if (name.startsWith("assets/") && isNamespace(name.substring(7))) {
                    String path = name.substring(7);
                    if (!contains(path) || item.isDirectory() || assets.containsKey(path))
                        throw new IOException("Unknown or duplicate runtime UI resource");
                    assets.put(path, read(path, zip));
                }
                zip.closeEntry();
            }
        }
        verifyComplete(assets);
        return assets;
    }
    private static String digest(byte[] bytes) {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte b : value) {
                result.append(Character.forDigit((b >>> 4) & 15, 16));
                result.append(Character.forDigit(b & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static final class Entry {
        final int bytes;
        final String sha256;
        Entry(int bytes, String sha256) { this.bytes = bytes; this.sha256 = sha256; }
    }
}
