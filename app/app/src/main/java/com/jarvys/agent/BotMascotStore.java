package com.jarvys.agent;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.jarvys.agent.crew.BotMascotDescriptor;
import com.jarvys.agent.crew.CrewProfileRepository;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/** Private immutable source + .riv packages. No public raw-byte publish API exists. */
public final class BotMascotStore {
    public static final int MAX_SOURCE_BYTES = 131072;
    public static final int MAX_RIV_BYTES = 65536;
    private static final int MAX_MANIFEST_BYTES = 16384;
    public static final int MAX_PACKAGES_PER_BOT = BotMascotDescriptor.MAX_RECEIPTS + 4;
    public static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    public static final int MAX_TOTAL_PACKAGES = 256;
    private static final int MAX_BOT_DIRECTORIES = 256;
    private static final String SOURCE = "source.json", ASSET = "asset.riv", MANIFEST = "manifest.json";
    private static final Object STAGING_LOCK = new Object();
    private final File filesDirectory;
    private final File root;
    private final java.util.function.Supplier<String> packageIds;
    private final long maxTotalBytes;
    private final int maxTotalPackages;
    private final DirectorySync directorySync;

    /** Injectable only in trusted package/test code; production always uses Android descriptor fsync. */
    interface DirectorySync { void sync(File directory) throws IOException; }

    /**
     * Trusted integration seam, deliberately package-private. The compiler must validate this exact
     * bounded scene and emit the bot-mascot-v1 contract. The tool supplies JSON, never binary bytes.
     * There is no default or pass-through compiler. LOCAL_COMPILED means source compilation and
     * package integrity only; Android import, behavior and playback acceptance remain separate gates.
     */
    interface CompilerGate {
        byte[] compile(byte[] source, CancellationToken token);
    }

    public BotMascotStore(Context context) { this(context.getApplicationContext().getFilesDir()); }
    BotMascotStore(File filesDirectory) { this(filesDirectory, () -> UUID.randomUUID().toString()); }
    /** Package-private deterministic ID seam exercises collision safety without changing production IDs. */
    BotMascotStore(File filesDirectory, java.util.function.Supplier<String> packageIds) {
        this(filesDirectory, packageIds, MAX_TOTAL_BYTES, MAX_TOTAL_PACKAGES, BotMascotStore::syncAndroidDirectory);
    }
    BotMascotStore(File filesDirectory, java.util.function.Supplier<String> packageIds,
            long maxTotalBytes, int maxTotalPackages, DirectorySync directorySync) {
        this.packageIds = java.util.Objects.requireNonNull(packageIds);
        this.directorySync = java.util.Objects.requireNonNull(directorySync);
        if (maxTotalBytes < 1 || maxTotalBytes > MAX_TOTAL_BYTES || maxTotalPackages < 1 || maxTotalPackages > MAX_TOTAL_PACKAGES)
            throw new IllegalArgumentException("Invalid private mascot storage quota");
        this.maxTotalBytes = maxTotalBytes;
        this.maxTotalPackages = maxTotalPackages;
        try {
            this.filesDirectory = filesDirectory.getCanonicalFile();
            this.root = new File(this.filesDirectory, "bot_mascots");
            verify(root);
        } catch (IOException error) { throw storageFailure(); }
    }

    /** Unforgeable handle proving local compilation and package integrity, NOT Android playback readiness. */
    public static final class ValidatedPackage {
        private final BotMascotStore store;
        private final String botId;
        private final BotMascotDescriptor descriptor;
        private ValidatedPackage(BotMascotStore store, String botId, BotMascotDescriptor descriptor) {
            this.store = store; this.botId = botId; this.descriptor = descriptor;
        }
        public BotMascotDescriptor descriptor() { return descriptor; }
        public String botId() { return botId; }
        /** Re-read hashes/manifest under repository CAS; a forged descriptor alone cannot pass. */
        public void verifyForAssignment(File appFilesDirectory, String expectedBotId) {
            try {
                if (!botId.equals(expectedBotId) || !store.filesDirectory.equals(appFilesDirectory.getCanonicalFile()))
                    throw new IllegalArgumentException("Mascot package belongs to a different private bot scope");
                store.read(botId, descriptor);
            } catch (IOException error) { throw storageFailure(); }
        }
    }

    public static final class PackageBytes {
        private final byte[] source, riv;
        private PackageBytes(byte[] source, byte[] riv) { this.source = source; this.riv = riv; }
        public byte[] source() { return source.clone(); }
        public byte[] riv() { return riv.clone(); }
    }

    /** No invocation from a tool may supply raw .riv bytes or bypass the trusted compiler. */
    ValidatedPackage prepare(String botId, String visualDescription, byte[] sourceInput, CompilerGate gate, CancellationToken token) {
        requireBotId(botId);
        BotMascotDescriptor.requireVisualDescription(visualDescription);
        if (gate == null || token == null) throw new IllegalArgumentException("Mascot compiler and cancellation gates are required");
        token.throwIfCancelled();
        if (sourceInput == null || sourceInput.length < 1 || sourceInput.length > MAX_SOURCE_BYTES)
            throw new IllegalArgumentException("Mascot source exceeds its byte budget");
        byte[] source = sourceInput.clone();
        jsonObject(source);
        // The original editable source is immutable even if a trusted implementation mutates its input.
        byte[] compiled = gate.compile(source.clone(), token);
        token.throwIfCancelled();
        if (compiled == null || compiled.length < 8 || compiled.length > MAX_RIV_BYTES)
            throw new IllegalArgumentException("Mascot binary exceeds its byte budget");
        byte[] riv = compiled.clone();
        if (riv[0] != 'R' || riv[1] != 'I' || riv[2] != 'V' || riv[3] != 'E')
            throw new IllegalArgumentException("Mascot binary has an invalid header");
        BotMascotDescriptor descriptor = new BotMascotDescriptor(packageIds.get(), sha256(source), sha256(riv), visualDescription);
        synchronized (STAGING_LOCK) {
            File stage = null, destination = null;
            boolean handedOff = false, ownsStage = false, published = false;
            try {
                File directory = directory(botId);
                byte[] manifest = new JSONObject().put("schemaVersion", 1).put("botId", botId)
                        .put("descriptor", descriptor.toJson()).toString().getBytes(StandardCharsets.UTF_8);
                if (manifest.length > MAX_MANIFEST_BYTES) throw new IllegalArgumentException("Mascot manifest exceeds its byte budget");
                enforceTotalQuota(directory, (long) source.length + riv.length + manifest.length);
                createDirectory(root); createDirectory(directory);
                String[] existing = directory.list();
                if (existing == null) throw storageFailure();
                if (existing.length >= MAX_PACKAGES_PER_BOT)
                    throw new IllegalArgumentException("Private mascot package limit reached; recover interrupted operations and retry the same request");
                destination = packageDirectory(botId, descriptor.packageRef);
                stage = new File(directory, ".stage-" + descriptor.packageRef);
                verify(stage);
                if (stage.exists() || !stage.mkdir()) throw storageFailure();
                ownsStage = true;
                write(stage, SOURCE, source, token);
                write(stage, ASSET, riv, token);
                write(stage, MANIFEST, manifest, token);
                syncDirectory(stage);
                token.throwIfCancelled();
                verify(directory); verify(stage); verify(destination);
                if (destination.exists() || !stage.renameTo(destination)) throw storageFailure();
                published = true;
                stage = null;
                syncDirectory(directory);
                read(botId, descriptor);
                token.throwIfCancelled();
                handedOff = true;
                return new ValidatedPackage(this, botId, descriptor);
            } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
            catch (IllegalArgumentException invalid) { throw invalid; }
            catch (Exception failure) { throw storageFailure(failure); }
            finally {
                if (ownsStage && stage != null) deleteQuietly(stage);
                // Only our own rename proves ownership; collision failure must preserve existing bytes.
                // No handle escaped, therefore no repository mutation could reference our new package.
                if (!handedOff && published && destination != null) deleteQuietly(destination);
            }
        }
    }

    /** Defensive read for rendering. Descriptor metadata alone is never proof the bytes still exist. */
    public PackageBytes read(String botId, BotMascotDescriptor descriptor) {
        if (descriptor == null) throw new IllegalArgumentException("Mascot descriptor is required");
        try {
            File directory = packageDirectory(botId, descriptor.packageRef);
            requirePackageMembers(directory);
            byte[] manifest = readBounded(new File(directory, MANIFEST), MAX_MANIFEST_BYTES);
            JSONObject object = BotMascotSceneCompiler.parseBoundedJsonObject(manifest, BotMascotDescriptor.MAX_DESCRIPTION_CHARS);
            if (object.length() != 3 || !(object.opt("schemaVersion") instanceof Number)
                    || new java.math.BigDecimal(object.opt("schemaVersion").toString()).intValueExact() != 1
                    || !botId.equals(object.opt("botId")) || !(object.opt("descriptor") instanceof JSONObject)
                    || !descriptor.equals(BotMascotDescriptor.fromJson((JSONObject) object.opt("descriptor"))))
                throw new IllegalArgumentException("Mascot package manifest does not match its descriptor");
            byte[] source = readBounded(new File(directory, SOURCE), MAX_SOURCE_BYTES);
            byte[] riv = readBounded(new File(directory, ASSET), MAX_RIV_BYTES);
            if (!descriptor.sourceHash.equals(sha256(source)) || !descriptor.assetHash.equals(sha256(riv)))
                throw new IllegalArgumentException("Mascot package integrity check failed");
            return new PackageBytes(source, riv);
        } catch (IOException error) { throw storageFailure(); }
    }

    /** References and deletion share the repository lock, so assignment cannot race this cleanup. */
    public boolean cleanupUnassigned(CrewProfileRepository repository, String botId, String packageRef) {
        if (repository == null) throw new IllegalArgumentException("Repository is required");
        File target = packageDirectory(botId, packageRef);
        return repository.cleanupUnassignedMascotPackage(filesDirectory, botId, packageRef, () -> deletePackage(target));
    }

    /** Only abandoned staging directories are eligible; immutable package directories are untouched. */
    public int cleanupInterruptedStaging(String botId) {
        synchronized (STAGING_LOCK) {
            File directory = directory(botId);
            if (!directory.exists()) return 0;
            File[] entries = directory.listFiles();
            if (entries == null) throw storageFailure();
            int removed = 0;
            for (File entry : entries) {
                if (!entry.getName().startsWith(".stage-")) continue;
                String suffix = entry.getName().substring(7);
                if (!suffix.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) continue;
                if (deletePackage(entry)) removed++;
            }
            return removed;
        }
    }

    /** Encoded private-file bytes only, not a claim about filesystem blocks or runtime memory. */
    private void enforceTotalQuota(File proposedBotDirectory, long candidateBytes) throws IOException {
        verify(root);
        if (candidateBytes > maxTotalBytes) throw quotaFailure();
        if (!root.exists()) return;
        if (!root.isDirectory()) throw new IllegalArgumentException("Invalid private mascot storage root");
        File[] bots = root.listFiles();
        if (bots == null) throw storageFailure();
        if (bots.length > MAX_BOT_DIRECTORIES || !proposedBotDirectory.exists() && bots.length >= MAX_BOT_DIRECTORIES)
            throw quotaFailure();
        long bytes = 0;
        int packages = 0;
        // Fixed depth and bounded entries. No recursive traversal and no links at any level.
        for (File bot : bots) {
            requireBotId(bot.getName()); verify(bot);
            if (!bot.isDirectory()) throw new IllegalArgumentException("Invalid private mascot bot directory");
            File[] entries = bot.listFiles();
            if (entries == null) throw storageFailure();
            if (entries.length > MAX_PACKAGES_PER_BOT) throw quotaFailure();
            for (File entry : entries) {
                String ref = entry.getName().startsWith(".stage-") ? entry.getName().substring(7) : entry.getName();
                BotMascotDescriptor.requirePackageRef(ref); verify(entry);
                if (!entry.isDirectory()) throw new IllegalArgumentException("Invalid private mascot package directory");
                if (++packages >= maxTotalPackages) throw quotaFailure(); // One more package is the candidate.
                File[] members = entry.listFiles();
                if (members == null) throw storageFailure();
                if (members.length > 3) throw new IllegalArgumentException("Unexpected private mascot package members");
                for (File member : members) {
                    verify(member);
                    String name = member.getName();
                    int memberLimit = SOURCE.equals(name) ? MAX_SOURCE_BYTES : ASSET.equals(name) ? MAX_RIV_BYTES
                            : MANIFEST.equals(name) ? MAX_MANIFEST_BYTES : -1;
                    if (memberLimit < 0 || !member.isFile()) throw new IllegalArgumentException("Unexpected private mascot package member");
                    long length = member.length();
                    if (length < 0 || length > memberLimit) throw new IllegalArgumentException("Invalid private mascot member budget");
                    if (length > maxTotalBytes - bytes) throw quotaFailure();
                    bytes += length;
                }
            }
        }
        if (candidateBytes > maxTotalBytes - bytes) throw quotaFailure();
    }
    private static IllegalArgumentException quotaFailure() {
        return new IllegalArgumentException("Private mascot storage quota reached; the current mascot was kept. Recover interrupted operations before retrying the same request; assigned and historical assets were not deleted");
    }

    private File directory(String botId) {
        requireBotId(botId);
        File directory = new File(root, botId);
        try { verify(root); verify(directory); } catch (IOException error) { throw storageFailure(); }
        return directory;
    }
    private File packageDirectory(String botId, String packageRef) {
        BotMascotDescriptor.requirePackageRef(packageRef);
        File directory = new File(directory(botId), packageRef);
        try { verify(directory); } catch (IOException error) { throw storageFailure(); }
        return directory;
    }
    private static void requireBotId(String botId) {
        if (botId == null || !botId.matches("custom-[a-z0-9][a-z0-9._-]*"))
            throw new IllegalArgumentException("Mascots require a stable custom bot identifier");
    }
    private void createDirectory(File directory) throws IOException {
        verify(directory);
        if (!directory.isDirectory()) {
            if (directory.exists() || !directory.mkdir()) throw storageFailure();
            syncDirectory(directory.getParentFile());
        }
        verify(directory);
    }
    private static void write(File directory, String name, byte[] bytes, CancellationToken token) throws IOException {
        token.throwIfCancelled();
        File target = new File(directory, name);
        verify(directory); verify(target);
        if (!target.createNewFile()) throw storageFailure();
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(bytes); output.flush(); output.getFD().sync();
        }
    }
    private static byte[] readBounded(File file, int max) throws IOException {
        verify(file);
        if (!file.isFile() || file.length() < 1 || file.length() > max) throw new IllegalArgumentException("Invalid mascot package member size");
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > max - count) throw new IllegalArgumentException("Mascot package member exceeds byte budget");
                output.write(buffer, 0, count);
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length == 0) throw new IllegalArgumentException("Empty mascot package member");
            return bytes;
        }
    }
    private static JSONObject jsonObject(byte[] bytes) {
        return BotMascotSceneCompiler.parseBoundedJsonObject(bytes);
    }
    private static void requirePackageMembers(File directory) throws IOException {
        verify(directory);
        if (!directory.isDirectory()) throw new IllegalArgumentException("Mascot package is unavailable");
        String[] members = directory.list();
        Set<String> expected = new HashSet<>(Arrays.asList(SOURCE, ASSET, MANIFEST));
        if (members == null || members.length != 3 || !expected.equals(new HashSet<>(Arrays.asList(members))))
            throw new IllegalArgumentException("Unexpected mascot package members");
    }
    private boolean deletePackage(File directory) {
        try {
            verify(directory);
            if (!directory.exists()) return true;
            if (!directory.isDirectory()) throw new IllegalArgumentException("Invalid mascot package directory");
            File[] children = directory.listFiles();
            if (children == null) throw storageFailure();
            Set<String> expected = new HashSet<>(Arrays.asList(SOURCE, ASSET, MANIFEST));
            // Inspect every member before deleting any; never traverse an unexpected path or link.
            for (File child : children) {
                verify(child);
                if (!expected.contains(child.getName()) || !child.isFile()) throw new IllegalArgumentException("Unsafe mascot cleanup target");
            }
            for (File child : children) if (!child.delete()) throw storageFailure();
            if (!directory.delete()) throw storageFailure();
            syncDirectory(directory.getParentFile());
            return true;
        } catch (IOException error) { throw storageFailure(); }
    }
    private void deleteQuietly(File directory) { try { deletePackage(directory); } catch (RuntimeException ignored) { } }
    private void syncDirectory(File directory) throws IOException {
        verify(directory);
        if (!directory.isDirectory()) throw new IOException("Private mascot storage is not a directory");
        directorySync.sync(directory);
    }
    private static void syncAndroidDirectory(File directory) throws IOException {
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))
                throw new IOException("Private mascot storage descriptor is not a directory");
            Os.fsync(descriptor);
        } catch (ErrnoException failure) { throw new IOException("Could not sync private mascot storage", failure); }
        finally { if (descriptor != null) try { Os.close(descriptor); } catch (ErrnoException ignored) { } }
    }
    private static void verify(File file) throws IOException {
        try {
            if (OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode))
                throw new IllegalArgumentException("Mascot storage symlinks are not allowed");
        } catch (ErrnoException failure) {
            if (failure.errno != OsConstants.ENOENT) throw new IOException("Could not inspect private mascot storage", failure);
        }
        if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new IllegalArgumentException("Mascot storage symlinks are not allowed");
    }
    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static IllegalStateException storageFailure(Throwable cause) { return new IllegalStateException("Could not verify private mascot storage; reopen the bot before retrying", cause); }
    private static IllegalStateException storageFailure() { return new IllegalStateException("Could not verify private mascot storage; reopen the bot before retrying"); }
}
