package com.jarvys.agent;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Worker-thread-only, bounded local stream export to the public Downloads folder.
 * Callers must resolve the opener through their scoped local artifact/attachment store. This class
 * deliberately accepts no source URI or source path. The stable key must identify immutable bytes.
 * No permissions, activities, installers or network requests are started by an export.
 */
public final class DownloadStore {
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final int BUFFER_BYTES = 32 * 1024;
    private static final Object PROCESS_LOCK = new Object();
    private static final java.util.concurrent.ExecutorService CANCEL_IO = java.util.concurrent.Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "jarvys-download-cancel");
        thread.setDaemon(true);
        return thread;
    });
    static final String RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/";
    private static final String PREPARED = "prepared", COPYING = "copying", READY = "ready", SAVED = "saved";

    public interface Opener { InputStream open() throws IOException; }
    public enum Status { SAVED, ALREADY_SAVED, PERMISSION_REQUIRED, CANCELLED, ERROR }

    public static final class Source {
        public final String scopeKey, artifactKey, displayName, mimeType;
        public final long expectedBytes;
        public final Opener opener;

        public Source(String scopeKey, String artifactKey, String displayName, String mimeType,
                      long expectedBytes, Opener opener) {
            if (scopeKey == null || scopeKey.trim().isEmpty() || scopeKey.length() > 512
                    || artifactKey == null || artifactKey.trim().isEmpty() || artifactKey.length() > 512)
                throw new IllegalArgumentException("A stable scoped artifact key is required");
            if (expectedBytes < 0 || expectedBytes > MAX_BYTES)
                throw new IllegalArgumentException("Download size must be known and at most 256 MiB");
            if (opener == null) throw new IllegalArgumentException("A local source opener is required");
            this.scopeKey = scopeKey;
            this.artifactKey = artifactKey;
            this.displayName = sanitizeDisplayName(displayName);
            this.mimeType = normalizeMime(mimeType);
            this.expectedBytes = expectedBytes;
            this.opener = opener;
        }
    }

    public static final class Result {
        public final Status status;
        public final Uri uri;
        public final String displayName, mimeType;
        public final long bytes;
        /** A bounded, non-sensitive diagnostic code, never an exception message or local path. */
        public final String error;

        private Result(Status status, Uri uri, String displayName, String mimeType, long bytes, String error) {
            this.status = status;
            this.uri = uri;
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.bytes = bytes;
            this.error = error;
        }
        public boolean isSuccess() { return status == Status.SAVED || status == Status.ALREADY_SAVED; }
    }

    private final Context context;
    private final ContentResolver resolver;
    private final File journalRoot;
    private final File legacyRoot;

    public DownloadStore(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.journalRoot = journalRoot(this.context);
        this.legacyRoot = legacyRoot();
    }

    /** IO must be dispatched by the caller (for example withContext(Dispatchers.IO)). */
    public Result export(Source source, CancellationToken cancellation) {
        if (Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("DownloadStore.export must run off the main thread");
        if (source == null) throw new IllegalArgumentException("A source is required");
        CancellationToken token = cancellation == null ? CancellationToken.uncancellable() : cancellation;
        if (token.isCancelled()) return failure(Status.CANCELLED, source, "cancelled");
        if (Build.VERSION.SDK_INT < 29 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)
            return failure(Status.PERMISSION_REQUIRED, source, "legacy_storage_permission_required");
        synchronized (PROCESS_LOCK) {
            try {
                token.throwIfCancelled();
                ensurePrivateRoot();
                // Serialize even independently recreated stores/processes. No unbounded key-lock map.
                try (RandomAccessFile coordination = new RandomAccessFile(new File(journalRoot, "export.lock"), "rw");
                     FileLock ignored = coordination.getChannel().lock()) {
                    token.throwIfCancelled();
                    return exportLocked(source, token);
                }
            } catch (CancellationException error) {
                return failure(Status.CANCELLED, source, "cancelled");
            } catch (Exception error) {
                return failure(token.isCancelled() ? Status.CANCELLED : Status.ERROR, source,
                        token.isCancelled() ? "cancelled" : "download_storage_unavailable");
            }
        }
    }

    private Result exportLocked(Source source, CancellationToken token) throws Exception {
        String key = key(source.scopeKey, source.artifactKey);
        Journal entry = readJournal(context, key);
        if (entry != null) {
            if (!entry.fingerprint.equals(fingerprint(source)))
                return failure(Status.ERROR, source, "artifact_key_reused");
            // Any inaccessible/indeterminate row fails closed, rather than creating a duplicate.
            if (SAVED.equals(entry.stage) || READY.equals(entry.stage)) {
                if (isComplete(entry)) {
                    if (READY.equals(entry.stage)) {
                        publish(entry, token);
                        entry.stage = SAVED;
                        writeJournal(key, entry);
                    }
                    return result(Status.ALREADY_SAVED, key, entry);
                }
            }
            if (SAVED.equals(entry.stage)) {
                // Never delete a completed file that its owner may have edited, renamed or moved.
                if (exists(entry)) return failure(Status.ERROR, source, "saved_download_changed");
                new AtomicFile(new File(journalRoot, key + ".json")).delete();
            } else cleanup(key, entry);
        }
        token.throwIfCancelled();
        Journal fresh = prepare(source);
        writeJournal(key, fresh); // Intent is durable before any public storage mutation.
        boolean published = false;
        try {
            token.throwIfCancelled();
            if (fresh.media) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fresh.displayName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, fresh.mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri inserted = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (inserted == null) throw new IOException("Insert returned no row");
                if (!isMediaUri(inserted)) throw new IOException("Unexpected Downloads row");
                fresh.mediaUri = inserted.toString();
            } else {
                ensureLegacyRoot();
                // createNewFile never truncates an existing download or follows a preexisting link.
                if (!legacyFile(fresh, true).createNewFile()) throw new IOException("Temporary name collision");
            }
            fresh.stage = COPYING;
            writeJournal(key, fresh);
            copy(source, fresh, token);
            fresh.stage = READY;
            writeJournal(key, fresh); // Recovery may publish only bytes whose close/fsync succeeded.
            publish(fresh, token);
            published = true;
            fresh.stage = SAVED;
            writeJournal(key, fresh);
            return result(Status.SAVED, key, fresh);
        } catch (Exception error) {
            // If publication succeeded but receipt sync failed, retain READY for reconciliation.
            // Deleting that record would turn a harmless retry into a duplicate public file.
            if (!published) {
                try { cleanup(key, fresh); }
                catch (Exception cleanupError) {
                    return failure(Status.ERROR, source, "download_cleanup_pending");
                }
            }
            if (!published && (token.isCancelled() || error instanceof CancellationException))
                return failure(Status.CANCELLED, source, "cancelled");
            return failure(Status.ERROR, source, published ? "download_receipt_pending" : "download_write_failed");
        }
    }

    private Journal prepare(Source source) throws Exception {
        Journal entry = new Journal();
        entry.media = Build.VERSION.SDK_INT >= 29;
        entry.stage = PREPARED;
        entry.fingerprint = fingerprint(source);
        entry.mimeType = source.mimeType;
        entry.bytes = source.expectedBytes;
        for (int attempt = 0; attempt < 20; attempt++) {
            entry.nonce = UUID.randomUUID().toString().replace("-", "");
            entry.displayName = uniqueName(source.displayName, entry.nonce.substring(0, 12));
            if (entry.media) {
                if (findMedia(entry) == null) return entry;
            } else if (!legacyFile(entry, false).exists() && !legacyFile(entry, true).exists()) return entry;
        }
        throw new IOException("Could not allocate a unique download name");
    }

    private void copy(Source source, Journal entry, CancellationToken token) throws Exception {
        token.throwIfCancelled();
        try (InputStream input = source.opener.open()) {
            if (input == null) throw new IOException("Source unavailable");
            Runnable removeInput = token.registerCancelAction(() -> CANCEL_IO.execute(() -> closeQuietly(input)));
            try {
                token.throwIfCancelled();
                OutputStream opened = entry.media ? resolver.openOutputStream(Uri.parse(entry.mediaUri), "w")
                        : new FileOutputStream(legacyFile(entry, true), false);
                if (opened == null) throw new IOException("Output unavailable");
                try (OutputStream output = opened) {
                    Runnable removeOutput = token.registerCancelAction(() -> CANCEL_IO.execute(() -> closeQuietly(output)));
                    try {
                        byte[] buffer = new byte[BUFFER_BYTES];
                        long written = 0;
                        while (true) {
                            token.throwIfCancelled();
                            // Read at most one byte beyond the declared bound, never materialize the file.
                            int request = (int) Math.min(buffer.length, source.expectedBytes - written + 1);
                            int count = input.read(buffer, 0, request);
                            if (count == -1) break;
                            if (count == 0) {
                                int one = input.read();
                                if (one == -1) break;
                                buffer[0] = (byte) one;
                                count = 1;
                            }
                            if (count > source.expectedBytes - written || written + count > MAX_BYTES)
                                throw new IOException("Source exceeded declared size");
                            token.throwIfCancelled();
                            output.write(buffer, 0, count);
                            written += count;
                        }
                        if (written != source.expectedBytes) throw new IOException("Source length changed");
                        token.throwIfCancelled();
                        output.flush();
                        if (output instanceof FileOutputStream) ((FileOutputStream) output).getFD().sync();
                    } finally { removeOutput.run(); }
                }
            } finally { removeInput.run(); }
        }
        token.throwIfCancelled();
    }

    private void publish(Journal entry, CancellationToken token) throws Exception {
        // The cancellation gate only marks the commit boundary. Never hold the token's lock across
        // provider/disk IO: cancel() may be called by the Activity on its main thread.
        if (!token.runIfActive(() -> { })) throw new CancellationException("Download cancelled");
        if (entry.media) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(Uri.parse(entry.mediaUri), values, null, null) != 1)
                throw new IOException("Could not publish download");
        } else {
            File target = legacyFile(entry, false);
            File partial = legacyFile(entry, true);
            if (!partial.exists() && target.isFile() && target.length() == entry.bytes) return;
            // UUID-suffixed destination + no existing target avoids overwriting user files.
            if (target.exists() || !partial.renameTo(target)) throw new IOException("Could not publish download");
        }
    }

    private boolean isComplete(Journal entry) throws Exception {
        if (entry.media) {
            Uri uri = entry.mediaUri.isEmpty() ? findMedia(entry) : Uri.parse(entry.mediaUri);
            if (uri == null) return false;
            try (Cursor rows = resolver.query(includePending(uri), new String[]{MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH}, null, null, null)) {
                if (rows == null) throw new IOException("Could not inspect saved download");
                if (!rows.moveToFirst()) return false;
                if (rows.getLong(0) != entry.bytes || !entry.displayName.equals(rows.getString(2))
                        || !RELATIVE_PATH.equals(rows.getString(3))) return false;
                if (SAVED.equals(entry.stage) && rows.getInt(1) != 0) return false;
                entry.mediaUri = uri.toString();
                return true;
            }
        }
        File target = legacyFile(entry, false);
        if (target.isFile() && target.length() == entry.bytes) return true;
        File partial = legacyFile(entry, true);
        return READY.equals(entry.stage) && partial.isFile() && partial.length() == entry.bytes;
    }

    private boolean exists(Journal entry) throws IOException {
        if (!entry.media) return legacyFile(entry, false).exists();
        if (entry.mediaUri.isEmpty()) throw new IOException("Missing download row");
        try (Cursor row = resolver.query(includePending(Uri.parse(entry.mediaUri)),
                new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
            if (row == null) throw new IOException("Could not inspect saved download");
            return row.moveToFirst();
        }
    }

    @SuppressWarnings("deprecation")
    private static Uri includePending(Uri uri) {
        return MediaStore.setIncludePending(uri);
    }

    private Uri findMedia(Journal entry) throws IOException {
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        try (Cursor rows = resolver.query(includePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI),
                new String[]{MediaStore.MediaColumns._ID}, selection,
                new String[]{entry.displayName, RELATIVE_PATH}, null)) {
            if (rows == null) throw new IOException("Could not inspect Downloads");
            if (!rows.moveToFirst()) return null;
            Uri result = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, rows.getLong(0));
            if (rows.moveToNext()) throw new IOException("Ambiguous download recovery");
            return result;
        }
    }

    private void cleanup(String key, Journal entry) throws Exception {
        if (entry.media) {
            Uri uri = entry.mediaUri.isEmpty() ? findMedia(entry) : Uri.parse(entry.mediaUri);
            if (uri != null) {
                // Journal state can lag a committed publish after process death or a provider error.
                // The actual pending bit is authoritative: never delete a user-visible download.
                try (Cursor existing = resolver.query(includePending(uri),
                        new String[]{MediaStore.MediaColumns.IS_PENDING}, null, null, null)) {
                    if (existing == null) throw new IOException("Could not inspect cleanup target");
                    if (existing.moveToFirst() && existing.getInt(0) != 1)
                        throw new IOException("Published download cannot be removed");
                }
                resolver.delete(uri, null, null);
                // A provider that fails deletion must not let the next retry insert another copy.
                try (Cursor remaining = resolver.query(includePending(uri), new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
                    if (remaining == null || remaining.moveToFirst()) throw new IOException("Cleanup incomplete");
                }
            }
        } else {
            deleteFile(legacyFile(entry, true));
            // Never remove a visible legacy target: a collision or an owner edit can replace it.
            // Partial files alone are owned unambiguously until the atomic rename publishes them.
        }
        new AtomicFile(new File(journalRoot, key + ".json")).delete();
    }

    private Result result(Status status, String key, Journal entry) {
        Uri uri = new Uri.Builder().scheme("content").authority(context.getPackageName() + ".downloads")
                .appendPath(key).build();
        return new Result(status, uri, entry.displayName, entry.mimeType, entry.bytes, null);
    }

    private static Result failure(Status status, Source source, String code) {
        return new Result(status, null, source.displayName, source.mimeType, 0, code);
    }

    private void ensurePrivateRoot() throws IOException {
        if (!journalRoot.isDirectory() && !journalRoot.mkdirs()) throw new IOException("Private storage unavailable");
        File expected = new File(context.getFilesDir().getCanonicalFile(), "jarvys/download_results");
        if (!journalRoot.getCanonicalFile().equals(expected))
            throw new IOException("Unexpected journal path");
    }

    private void ensureLegacyRoot() throws IOException {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()))
            throw new IOException("Public storage unavailable");
        if (!legacyRoot.isDirectory() && !legacyRoot.mkdirs()) throw new IOException("Downloads unavailable");
        File parent = legacyRoot.getParentFile().getCanonicalFile();
        if (!legacyRoot.getCanonicalFile().equals(new File(parent, legacyRoot.getName())))
            throw new IOException("Unexpected Downloads directory");
    }

    private File legacyFile(Journal entry, boolean partial) throws IOException {
        return checkedLegacyFile(entry, partial);
    }

    static File checkedLegacyFile(Journal entry, boolean partial) throws IOException {
        File root = legacyRoot();
        File file = new File(root, partial ? ".jarvys-" + entry.nonce + ".partial" : entry.displayName);
        File canonical = file.getCanonicalFile();
        // Platform storage aliases are valid; child-directory/file symlinks are not.
        File expectedRoot = new File(root.getParentFile().getCanonicalFile(), root.getName());
        if (!root.getCanonicalFile().equals(expectedRoot)
                || !canonical.equals(new File(expectedRoot, file.getName())))
            throw new IOException("Unsafe Downloads path");
        return file;
    }

    static File journalRoot(Context context) { return new File(context.getFilesDir(), "jarvys/download_results"); }
    private static File legacyRoot() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    private void writeJournal(String key, Journal entry) throws Exception {
        AtomicFile file = new AtomicFile(new File(journalRoot, key + ".json"));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(entry.json().toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
            file.finishWrite(output);
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            throw error;
        }
    }

    static Journal readJournal(Context context, String key) throws IOException {
        if (key == null || !key.matches("[a-f0-9]{64}")) throw new IOException("Invalid download key");
        AtomicFile file = new AtomicFile(new File(journalRoot(context), key + ".json"));
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return null;
        try (InputStream input = file.openRead(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[2048];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (buffer.size() + count > 16 * 1024) throw new IOException("Download receipt too large");
                buffer.write(chunk, 0, count);
            }
            return Journal.parse(new JSONObject(buffer.toString("UTF-8")));
        } catch (Exception error) { throw new IOException("Invalid download receipt", error); }
    }

    static boolean isMediaUri(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null) return false;
        java.util.List<String> parts = uri.getPathSegments();
        return parts.size() == 3 && ("external".equals(parts.get(0)) || "external_primary".equals(parts.get(0)))
                && "downloads".equals(parts.get(1)) && parts.get(2).matches("[0-9]+");
    }

    static final class Journal {
        String stage, fingerprint, displayName, mimeType, nonce, mediaUri = "";
        boolean media;
        long bytes;
        JSONObject json() throws Exception {
            return new JSONObject().put("version", 1).put("stage", stage).put("fingerprint", fingerprint)
                    .put("displayName", displayName).put("mimeType", mimeType).put("nonce", nonce)
                    .put("media", media).put("mediaUri", mediaUri).put("bytes", bytes);
        }
        static Journal parse(JSONObject data) throws Exception {
            if (data.getInt("version") != 1) throw new IOException("Unknown receipt version");
            Journal value = new Journal();
            value.stage = data.getString("stage");
            value.fingerprint = data.getString("fingerprint");
            value.displayName = data.getString("displayName");
            value.mimeType = data.getString("mimeType");
            value.nonce = data.getString("nonce");
            value.media = data.getBoolean("media");
            value.mediaUri = data.getString("mediaUri");
            value.bytes = data.getLong("bytes");
            if ((!PREPARED.equals(value.stage) && !COPYING.equals(value.stage) && !READY.equals(value.stage)
                    && !SAVED.equals(value.stage)) || !value.fingerprint.matches("[a-f0-9]{64}")
                    || !value.nonce.matches("[a-f0-9]{32}") || value.bytes < 0 || value.bytes > MAX_BYTES
                    || !value.displayName.equals(sanitizeDisplayName(value.displayName))
                    || !value.mimeType.equals(normalizeMime(value.mimeType))
                    || (!value.mediaUri.isEmpty() && !isMediaUri(Uri.parse(value.mediaUri)))
                    || (!value.media && !value.mediaUri.isEmpty())) throw new IOException("Unsafe receipt");
            return value;
        }
        boolean saved() { return SAVED.equals(stage); }
    }

    static String key(String scope, String artifact) { return sha256(scope.length() + ":" + scope + artifact); }
    private static String fingerprint(Source source) {
        return sha256(source.displayName + "\n" + source.mimeType + "\n" + source.expectedBytes);
    }
    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder();
            for (byte part : hash) encoded.append(String.format(Locale.ROOT, "%02x", part & 255));
            return encoded.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    public static String sanitizeDisplayName(String raw) {
        String input = raw == null ? "file" : raw;
        StringBuilder name = new StringBuilder();
        for (int offset = 0; offset < input.length() && name.length() < 120;) {
            int point = input.codePointAt(offset);
            offset += Character.charCount(point);
            if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT) continue;
            if (point == '/' || point == '\\' || point == ':' || point == '*' || point == '?' || point == '"'
                    || point == '<' || point == '>' || point == '|') name.append('_');
            else name.appendCodePoint(point);
        }
        String result = name.toString().trim().replaceAll("^[. ]+|[. ]+$", "");
        if (result.isEmpty()) result = "file";
        while (result.getBytes(StandardCharsets.UTF_8).length > 180)
            result = result.substring(0, result.offsetByCodePoints(result.length(), -1));
        return result;
    }

    private static String uniqueName(String name, String nonce) {
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 20 ? name.substring(dot) : "";
        String stem = extension.isEmpty() ? name : name.substring(0, dot);
        String suffix = " (" + nonce + ")" + extension;
        while (stem.length() + suffix.length() > 120
                || (stem + suffix).getBytes(StandardCharsets.UTF_8).length > 180)
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
        return stem + suffix;
    }

    private static String normalizeMime(String mime) {
        String result = mime == null ? "application/octet-stream" : mime.trim().toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z0-9!#$&^_.+-]{1,80}/[a-z0-9!#$&^_.+-]{1,80}"))
            return "application/octet-stream";
        return result;
    }
    private static void closeQuietly(Closeable closeable) {
        try { closeable.close(); } catch (IOException ignored) { }
    }
    private static void deleteFile(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Could not clean partial download");
    }
}
