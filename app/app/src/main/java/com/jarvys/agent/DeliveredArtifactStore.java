package com.jarvys.agent;

import android.content.Context;
import android.webkit.MimeTypeMap;
import com.jarvys.agent.coding.ArtifactSnapshotIO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/** Immutable per-chat delivered copies outside every agent-editable workspace/mount. */
public final class DeliveredArtifactStore {
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private final File root;
    private final String expected;
    private final Object lock;

    public DeliveredArtifactStore(Context context) { this(new File(context.getApplicationContext().getFilesDir(), "jarvys")); }
    DeliveredArtifactStore(File jarvysRoot) {
        root = new File(jarvysRoot.getAbsoluteFile(), "delivered");
        try {
            expected = new File(jarvysRoot.getParentFile().getCanonicalFile(), jarvysRoot.getName() + "/delivered").getPath();
            verify(root);
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid artifact storage", invalid); }
        lock = LOCKS.computeIfAbsent(expected, ignored -> new Object());
    }

    ChatAttachment snapshot(String session, WorkspaceStore workspace, String path, String name,
            CancellationToken token) throws IOException {
        synchronized (lock) {
            File directory = directory(session, true);
            File staged = File.createTempFile(".delivery-", ".tmp", directory);
            try {
                ArtifactSnapshotIO.Snapshot copied = workspace.snapshotForDelivery(path, staged, MAX_BYTES, token);
                token.throwIfCancelled();
                String filename = ChatAttachment.sanitizeName(name == null ? path.substring(path.lastIndexOf('/') + 1) : name);
                String mime = mime(filename);
                String id = UUID.nameUUIDFromBytes((session + "\0" + filename + "\0" + copied.sha256).getBytes(StandardCharsets.UTF_8)).toString();
                ChatAttachment attachment = new ChatAttachment(id, filename, mime, copied.bytes,
                        mime.startsWith("image/") ? ChatAttachment.Kind.IMAGE : ChatAttachment.Kind.FILE, id + "-" + filename);
                File target = path(session, attachment.relativePath);
                File manifest = path(session, id + ".json");
                if (manifest.exists()) {
                    resolve(session, attachment);
                    return attachment;
                }
                // An interrupted delivery's existing immutable bytes may be completed, never overwritten.
                if (target.exists()) {
                    if (target.length() != copied.bytes || !hash(target).equals(copied.sha256))
                        throw new IOException("An interrupted attachment must be inspected before retrying");
                } else if (!staged.renameTo(target)) throw new IOException("Could not save attachment copy");
                verify(target);
                ArtifactSnapshotIO.syncDirectory(directory);
                JSONObject record = new JSONObject().put("attachment", attachment.toJson()).put("sha256", copied.sha256);
                writeManifest(manifest, record);
                return attachment;
            } catch (org.json.JSONException invalid) { throw new IOException("Could not persist artifact metadata", invalid); }
            finally { if (staged.exists()) staged.delete(); }
        }
    }

    public File resolve(String session, ChatAttachment attachment) {
        synchronized (lock) {
            try {
                if (attachment == null) throw new IOException("Attachment metadata is missing");
                File manifest = path(session, attachment.id + ".json");
                JSONObject record = readManifest(manifest);
                ChatAttachment known = ChatAttachment.fromJson(record.optJSONObject("attachment"));
                if (!attachment.equals(known)) throw new IOException("Attachment metadata changed");
                File target = path(session, attachment.relativePath);
                if (!target.isFile() || target.length() != attachment.sizeBytes || target.length() > MAX_BYTES
                        || !hash(target).equals(record.getString("sha256"))) throw new IOException("Attachment copy is missing or changed");
                verify(target);
                return target;
            } catch (Exception invalid) { throw new IllegalArgumentException("Attachment is unavailable in this conversation", invalid); }
        }
    }

    String sha256(String session, ChatAttachment attachment) throws IOException {
        synchronized (lock) {
            try {
                JSONObject record = readManifest(path(session, attachment.id + ".json"));
                if (!attachment.equals(ChatAttachment.fromJson(record.optJSONObject("attachment")))) throw new IOException("Artifact metadata differs");
                String sha = record.getString("sha256");
                if (!sha.matches("[a-f0-9]{64}")) throw new IOException("Artifact checksum is invalid");
                return sha;
            } catch (Exception invalid) { throw new IOException("Could not read artifact checksum", invalid); }
        }
    }

    public InputStream open(String session, ChatAttachment attachment) throws IOException {
        synchronized (lock) { return new FileInputStream(resolve(session, attachment)); }
    }

    public boolean deleteSession(String session) {
        synchronized (lock) {
            try { return delete(directory(session, false)); }
            catch (IOException | RuntimeException unavailable) { return false; }
        }
    }

    private boolean delete(File file) throws IOException {
        verify(file);
        if (file.isDirectory()) {
            File[] children = file.listFiles(); if (children == null) return false;
            for (File child : children) if (!delete(child)) return false;
        }
        return !file.exists() || file.delete();
    }

    private File directory(String session, boolean create) throws IOException {
        if (session == null || !session.matches("[A-Za-z0-9_.-]{1,100}") || session.equals(".") || session.equals(".."))
            throw new IOException("Invalid attachment conversation");
        verify(root);
        File directory = new File(root, session); verify(directory);
        if (create && !directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create attachment storage");
        verify(directory); return directory;
    }

    private File path(String session, String name) throws IOException {
        if (name == null || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals(".."))
            throw new IOException("Invalid artifact filename");
        File file = new File(directory(session, false), name); verify(file); return file;
    }

    private void verify(File file) throws IOException {
        String logical = file.getAbsolutePath();
        String base = root.getAbsolutePath();
        if (!logical.equals(base) && !logical.startsWith(base + File.separator)) throw new IOException("Artifact path escaped storage");
        String wanted = expected + logical.substring(base.length());
        if (!file.getCanonicalPath().equals(wanted) || !root.getCanonicalPath().equals(expected))
            throw new IOException("Artifact paths cannot use symbolic links");
    }

    private void writeManifest(File target, JSONObject record) throws IOException {
        File staged = File.createTempFile(".metadata-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(staged)) {
                output.write(record.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
            }
            verify(staged); verify(target);
            if (target.exists() || !staged.renameTo(target)) throw new IOException("Could not persist attachment manifest");
            ArtifactSnapshotIO.syncDirectory(target.getParentFile());
        } finally { if (staged.exists()) staged.delete(); }
    }

    private JSONObject readManifest(File file) throws Exception {
        verify(file);
        if (!file.isFile() || file.length() > 4096) throw new IOException("Artifact manifest missing or oversized");
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 4096) throw new IOException("Artifact manifest oversized");
                bytes.write(buffer, 0, count);
            }
            return new JSONObject(bytes.toString("UTF-8"));
        }
    }

    private String hash(File file) throws IOException {
        verify(file);
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32768]; long bytes = 0; int count;
            while ((count = input.read(buffer)) != -1) {
                bytes += count; if (bytes > MAX_BYTES) throw new IOException("Artifact exceeds size limit"); digest.update(buffer, 0, count);
            }
            verify(file); return ArtifactSnapshotIO.hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String mime(String name) {
        int dot = name.lastIndexOf('.'); String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (extension.equals("apk")) return "application/vnd.android.package-archive";
        if (extension.equals("md")) return "text/markdown";
        if (extension.equals("json")) return "application/json";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return ChatAttachment.normalizeMimeType(type);
    }
}
