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
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/** Immutable per-chat delivered copies outside every agent-editable workspace/mount. */
public final class DeliveredArtifactStore {
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final Map<String, Long> THUMBNAIL_GENERATIONS = new ConcurrentHashMap<>();
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
            if (workspace == null || session == null || !session.equals(workspace.deliveryConversationId()))
                throw new IOException("The source belongs to another conversation");
            File directory = directory(session, true);
            cleanupStaging(directory);
            File staged = File.createTempFile(".delivery-", ".tmp", directory);
            File previewStage = null;
            try {
                WorkspaceStore.DeliverySource source = workspace.deliverySource(path);
                ArtifactSnapshotIO.Snapshot copied = ArtifactSnapshotIO.copy(source.root, source.relative, staged, MAX_BYTES, token);
                token.throwIfCancelled();
                String filename = ChatAttachment.sanitizeName(name == null ? path.substring(path.lastIndexOf('/') + 1) : name);
                String mime = mime(filename);
                HtmlPreviewCapture.Capture preview = null;
                String unavailable = null;
                if ("text/html".equals(mime) && HtmlPreviewCapture.htmlName(filename)) {
                    if (HtmlPreviewCapture.htmlName(source.relative)) {
                        previewStage = new File(directory, ".preview-" + UUID.randomUUID() + ".tmp");
                        if (!previewStage.mkdir()) throw new IOException("Could not stage HTML preview");
                        try { preview = HtmlPreviewCapture.capture(source, staged, copied, previewStage, token); }
                        catch (IllegalArgumentException | IOException invalidPreview) {
                            unavailable = "HTML preview could not be captured safely; the original file is still attached.";
                        }
                    }
                    if (preview == null && unavailable == null)
                        unavailable = "HTML preview requires a UTF-8 HTML source within the preview limits; the original file is still attached.";
                }
                String identity = session + "\0" + filename + "\0" + copied.sha256;
                if (preview != null) identity += "\0" + preview.fingerprint();
                String id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
                ChatAttachment attachment = new ChatAttachment(id, filename, mime, copied.bytes,
                        mime.startsWith("image/") ? ChatAttachment.Kind.IMAGE : ChatAttachment.Kind.FILE, id + "-" + filename);
                File target = path(session, attachment.relativePath);
                File manifest = path(session, id + ".json");
                if (manifest.exists()) {
                    resolve(session, attachment);
                    if (preview != null) resolvePreview(session, HtmlPreviewDescriptor.TOKEN_PREFIX + id);
                    return attachment;
                }
                // An interrupted delivery's existing immutable bytes may be completed, never overwritten.
                if (target.exists()) {
                    if (target.length() != copied.bytes || !hash(target).equals(copied.sha256))
                        throw new IOException("An interrupted attachment must be inspected before retrying");
                } else if (!staged.renameTo(target)) throw new IOException("Could not save attachment copy");
                verify(target);
                JSONObject record = new JSONObject().put("attachment", attachment.toJson()).put("sha256", copied.sha256)
                        .put("conversation", session);
                if (preview != null) {
                    File previewTarget = path(session, id + ".preview");
                    JSONObject previewRecord = preview.json();
                    if (previewTarget.exists()) verifyCapturedFiles(previewTarget, previewRecord);
                    else {
                        syncPreviewDirectories(previewStage);
                        if (!previewStage.renameTo(previewTarget)) throw new IOException("Could not save immutable HTML preview");
                    }
                    verifyCapturedFiles(previewTarget, previewRecord);
                    record.put("preview", previewRecord);
                } else if (unavailable != null) record.put("previewUnavailable", unavailable);
                if ("text/html".equals(mime) && !HtmlPreviewCapture.htmlName(source.relative)) record.put("previewDenied", true);
                ArtifactSnapshotIO.syncDirectory(directory);
                writeManifest(manifest, record);
                return attachment;
            } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
            catch (IOException invalid) { throw invalid; }
            catch (Exception invalid) { throw new IOException("Could not persist artifact metadata", invalid); }
            finally {
                if (staged.exists()) staged.delete();
                if (previewStage != null && previewStage.exists()) delete(previewStage);
            }
        }
    }

    /** Bounded native connector download; no intermediate agent-editable workspace file. */
    ChatAttachment snapshotBytes(String session, byte[] bytes, String name, String requestedMime,
            CancellationToken token) throws IOException {
        if (bytes == null || bytes.length > ConnectorArtifactAccess.MAX_BYTES)
            throw new IOException("Connector attachment exceeds the byte limit");
        synchronized (lock) {
            token.throwIfCancelled();
            File directory = directory(session, true);
            cleanupStaging(directory);
            File staged = File.createTempFile(".delivery-", ".tmp", directory);
            try {
                byte[] immutable = bytes.clone();
                String filename = ChatAttachment.sanitizeName(name);
                String mime = ChatAttachment.normalizeMimeType(requestedMime);
                String checksum;
                try { checksum = ArtifactSnapshotIO.hex(MessageDigest.getInstance("SHA-256").digest(immutable)); }
                catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
                String id = UUID.nameUUIDFromBytes((session + "\0" + filename + "\0" + mime + "\0" + checksum)
                        .getBytes(StandardCharsets.UTF_8)).toString();
                ChatAttachment attachment = new ChatAttachment(id, filename, mime, immutable.length,
                        mime.startsWith("image/") ? ChatAttachment.Kind.IMAGE : ChatAttachment.Kind.FILE, id + "-" + filename);
                File target = path(session, attachment.relativePath);
                File manifest = path(session, id + ".json");
                if (manifest.exists()) { resolve(session, attachment); return attachment; }
                try (FileOutputStream output = new FileOutputStream(staged)) {
                    for (int offset = 0; offset < immutable.length; offset += 8192) {
                        token.throwIfCancelled();
                        output.write(immutable, offset, Math.min(8192, immutable.length - offset));
                    }
                    output.getFD().sync();
                }
                token.throwIfCancelled();
                verify(staged); verify(target);
                if (target.exists()) {
                    if (target.length() != immutable.length || !hash(target).equals(checksum))
                        throw new IOException("An interrupted attachment must be inspected before retrying");
                } else if (!staged.renameTo(target)) throw new IOException("Could not save attachment copy");
                verify(target);
                ArtifactSnapshotIO.syncDirectory(directory);
                writeManifest(manifest, new JSONObject().put("attachment", attachment.toJson()).put("sha256", checksum));
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

    /** Existing single-file/connector HTML can show only its verified saved original, never mutable workspace assets. */
    public HtmlPreviewDescriptor previewForAttachment(String session, ChatAttachment attachment) {
        synchronized (lock) {
            try {
                if (attachment == null) throw new IOException("Attachment metadata is missing");
                JSONObject record = readManifest(path(session, attachment.id + ".json"));
                if (!attachment.equals(ChatAttachment.fromJson(record.optJSONObject("attachment"))))
                    throw new IOException("Attachment metadata changed");
                if (!record.has("preview")) return singleFilePreview(session, attachment, record);
                return resolvePreview(session, HtmlPreviewDescriptor.TOKEN_PREFIX + attachment.id);
            } catch (Exception invalid) { throw new IllegalArgumentException("Preview is unavailable in this conversation", invalid); }
        }
    }

    /** Fast timeline/card projection. Opening the preview still verifies every immutable asset. */
    public HtmlPreviewDescriptor previewMetadataForAttachment(String session, ChatAttachment attachment) {
        synchronized (lock) {
            try {
                if (attachment == null) throw new IOException("Attachment metadata is missing");
                JSONObject record = readManifest(path(session, attachment.id + ".json"));
                if (!attachment.equals(ChatAttachment.fromJson(record.optJSONObject("attachment"))))
                    throw new IOException("Attachment metadata changed");
                if (!record.has("preview")) return singleFilePreview(session, attachment, record);
                return descriptor(previewRecord(session, HtmlPreviewDescriptor.TOKEN_PREFIX + attachment.id), attachment.id);
            } catch (Exception invalid) { throw new IllegalArgumentException("Preview is unavailable in this conversation", invalid); }
        }
    }

    private HtmlPreviewDescriptor descriptor(JSONObject record, String id) throws Exception {
        JSONObject preview = record.getJSONObject("preview");
        List<String> warnings = new ArrayList<>();
        JSONArray array = preview.getJSONArray("warnings");
        for (int i = 0; i < array.length(); i++) warnings.add(array.getString(i));
        return new HtmlPreviewDescriptor(id, preview.getString("entryPath"), preview.getJSONArray("files").length(),
                preview.getLong("totalBytes"), warnings, preview.getString("fingerprint"));
    }

    public HtmlPreviewDescriptor resolvePreview(String session, String token) {
        synchronized (lock) {
            try {
                JSONObject record = previewRecord(session, token);
                if (!record.has("preview")) {
                    HtmlPreviewDescriptor single = singleFilePreview(session, ChatAttachment.fromJson(record.optJSONObject("attachment")), record);
                    if (single == null) throw new IOException("Saved file cannot be previewed as HTML");
                    return single;
                }
                JSONObject preview = record.getJSONObject("preview");
                String id = token.substring(HtmlPreviewDescriptor.TOKEN_PREFIX.length());
                verifyCapturedFiles(path(session, id + ".preview"), preview);
                return descriptor(record, id);
            } catch (Exception invalid) { throw new IllegalArgumentException("Preview is unavailable in this conversation", invalid); }
        }
    }

    /** Read-only lookup accepts exact manifest members, never an arbitrary disk path or URL. */
    public InputStream openPreview(String session, String token, String relativePath) throws IOException {
        synchronized (lock) {
            try {
                HtmlPreviewCapture.requireOrdinaryPath(relativePath);
                JSONObject record = previewRecord(session, token);
                if (!record.has("preview")) {
                    ChatAttachment attachment = ChatAttachment.fromJson(record.optJSONObject("attachment"));
                    HtmlPreviewDescriptor single = singleFilePreview(session, attachment, record);
                    if (single == null || !single.entryPath.equals(relativePath)) throw new IOException("Only the saved HTML entry is available");
                    return new FileInputStream(resolve(session, attachment));
                }
                String id = token.substring(HtmlPreviewDescriptor.TOKEN_PREFIX.length());
                JSONArray assets = record.getJSONObject("preview").getJSONArray("files");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (!relativePath.equals(asset.getString("path"))) continue;
                    File file = new File(path(session, id + ".preview"), relativePath);
                    verifyAsset(file, asset);
                    return new FileInputStream(file);
                }
                throw new IOException("The asset was not captured in this preview");
            } catch (IOException invalid) { throw invalid; }
            catch (Exception invalid) { throw new IOException("Preview is unavailable in this conversation", invalid); }
        }
    }

    String previewUnavailable(String session, ChatAttachment attachment) throws IOException {
        synchronized (lock) {
            try {
                JSONObject record = readManifest(path(session, attachment.id + ".json"));
                if (!attachment.equals(ChatAttachment.fromJson(record.optJSONObject("attachment")))) throw new IOException("Attachment metadata changed");
                return record.optString("previewUnavailable", "");
            } catch (Exception invalid) { throw new IOException("Could not read preview status", invalid); }
        }
    }

    /** Compatibility projection from verified immutable bytes only; no metadata writes or source reads. */
    private HtmlPreviewDescriptor singleFilePreview(String session, ChatAttachment attachment, JSONObject record) throws IOException {
        if (attachment == null || !"text/html".equals(attachment.mimeType) || !HtmlPreviewCapture.htmlName(attachment.name)
                || attachment.sizeBytes > HtmlPreviewCapture.MAX_FILE_BYTES || record.optBoolean("previewDenied", false)) return null;
        if (record.has("conversation") && !session.equals(record.optString("conversation"))) throw new IOException("Invalid preview ownership");
        try { HtmlPreviewCapture.requireOrdinaryPath(attachment.name); }
        catch (IOException unsupportedName) { return null; }
        File saved = resolve(session, attachment);
        if (!HtmlPreviewCapture.isHtmlFile(saved)) return null;
        return new HtmlPreviewDescriptor(attachment.id, attachment.name, 1, attachment.sizeBytes,
                java.util.Collections.singletonList("Only the saved HTML file is available; linked assets were not captured."), record.optString("sha256"));
    }

    private JSONObject previewRecord(String session, String token) throws Exception {
        if (!HtmlPreviewDescriptor.isSnapshotToken(token)) throw new IOException("Invalid preview token");
        String id = token.substring(HtmlPreviewDescriptor.TOKEN_PREFIX.length());
        JSONObject record = readManifest(path(session, id + ".json"));
        ChatAttachment attachment = ChatAttachment.fromJson(record.optJSONObject("attachment"));
        if ((record.has("conversation") && !session.equals(record.optString("conversation"))) || attachment == null || !id.equals(attachment.id)
                || !"text/html".equals(attachment.mimeType) || !HtmlPreviewCapture.htmlName(attachment.name)
                || attachment.sizeBytes > HtmlPreviewCapture.MAX_FILE_BYTES) throw new IOException("Invalid preview ownership");
        if (!record.has("preview")) {
            if (singleFilePreview(session, attachment, record) == null) throw new IOException("Saved file cannot be previewed as HTML");
            return record;
        }
        if (!session.equals(record.optString("conversation"))) throw new IOException("Invalid preview ownership");
        JSONObject preview = record.getJSONObject("preview");
        if (preview.getInt("schema") != 1) throw new IOException("Unsupported preview manifest");
        String entry = preview.getString("entryPath"); HtmlPreviewCapture.requireOrdinaryPath(entry);
        if (!HtmlPreviewCapture.htmlName(entry)) throw new IOException("Invalid preview entry");
        JSONArray assets = preview.getJSONArray("files"), warnings = preview.getJSONArray("warnings");
        if (assets.length() < 1 || assets.length() > HtmlPreviewCapture.MAX_FILES || warnings.length() > 16)
            throw new IOException("Preview manifest exceeds limits");
        HtmlPreviewCapture.Capture checked = new HtmlPreviewCapture.Capture(null, entry, preview.getString("provenance"));
        if (checked.provenance.length() > 256) throw new IOException("Invalid preview provenance");
        boolean hasEntry = false;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String relative = asset.getString("path"), sha = asset.getString("sha256");
            long bytes = asset.getLong("bytes"); HtmlPreviewCapture.requireOrdinaryPath(relative);
            if (bytes < 0 || bytes > HtmlPreviewCapture.MAX_FILE_BYTES || !sha.matches("[a-f0-9]{64}")
                    || checked.assets.containsKey(relative)) throw new IOException("Invalid preview asset");
            checked.assets.put(relative, new HtmlPreviewCapture.Asset(relative, bytes, sha)); checked.bytes += bytes;
            if (entry.equals(relative)) {
                hasEntry = bytes == attachment.sizeBytes && sha.equals(record.getString("sha256"));
            }
        }
        for (int i = 0; i < warnings.length(); i++) {
            String warning = warnings.getString(i);
            if (warning.length() > 256) throw new IOException("Invalid preview warning");
            checked.warnings.add(warning);
        }
        if (!hasEntry || checked.bytes > HtmlPreviewCapture.MAX_TOTAL_BYTES || checked.bytes != preview.getLong("totalBytes")
                || !checked.fingerprint().equals(preview.getString("fingerprint"))) throw new IOException("Preview manifest changed");
        return record;
    }

    private void verifyCapturedFiles(File directory, JSONObject preview) throws Exception {
        verify(directory);
        if (!directory.isDirectory()) throw new IOException("Preview files are missing");
        JSONArray assets = preview.getJSONArray("files");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String relative = asset.getString("path"); HtmlPreviewCapture.requireOrdinaryPath(relative);
            verifyAsset(new File(directory, relative), asset);
        }
    }

    private void verifyAsset(File file, JSONObject asset) throws Exception {
        verify(file);
        if (!file.isFile() || file.length() != asset.getLong("bytes") || file.length() > HtmlPreviewCapture.MAX_FILE_BYTES
                || !hash(file).equals(asset.getString("sha256"))) throw new IOException("Preview asset is missing or changed");
    }

    private void syncPreviewDirectories(File directory) throws IOException {
        verify(directory);
        File[] children = directory.listFiles(); if (children == null) throw new IOException("Preview directory is unavailable");
        for (File child : children) { verify(child); if (child.isDirectory()) syncPreviewDirectories(child); }
        ArtifactSnapshotIO.syncDirectory(directory);
    }

    public InputStream open(String session, ChatAttachment attachment) throws IOException {
        synchronized (lock) { return new FileInputStream(resolve(session, attachment)); }
    }

    /**
     * Derived thumbnails share the delivered-store commit/delete lock, but never modify a bundle.
     * Callers MUST resolve transcript ownership before entering: LocalRunStore takes its transcript
     * lock before this lock, so calling it from the callback would invert deletion's lock order.
     */
    interface ThumbnailOperation<T> { T run(ThumbnailStorage storage) throws IOException; }

    final class ThumbnailStorage {
        final File deliveredRoot, sessionDirectory;
        private final String session;
        private ThumbnailStorage(String session, File directory) {
            this.session = session; deliveredRoot = root; sessionDirectory = directory;
        }
        void verifyPath(File file) throws IOException { verify(file); }
        void requireLiveSession() throws IOException {
            verify(sessionDirectory);
            // Do not recreate a session directory removed by deleteSession, even without a ledger.
            if (!sessionDirectory.isDirectory()) throw new IOException("The delivered conversation is unavailable");
            try {
                if (new ConversationMetadataStore(root.getParentFile().getParentFile()).read(session).deleted)
                    throw new IOException("The conversation has been deleted");
            } catch (IllegalStateException invalid) { throw new IOException("Conversation ownership is unavailable", invalid); }
        }
    }

    long thumbnailGeneration(String session) throws IOException {
        synchronized (lock) {
            directory(session, false);
            return THUMBNAIL_GENERATIONS.getOrDefault(expected + "\0" + session, 0L);
        }
    }

    <T> T withThumbnailStorage(String session, ChatAttachment owner, String fingerprint, long generation,
            ThumbnailOperation<T> operation) throws IOException {
        synchronized (lock) {
            if (thumbnailGeneration(session) != generation) throw new IOException("Thumbnail request was revoked by deletion");
            ThumbnailStorage storage = new ThumbnailStorage(session, directory(session, false));
            storage.requireLiveSession();
            try {
                HtmlPreviewDescriptor preview = previewForAttachment(session, owner);
                if (preview == null || !preview.contentFingerprint.equals(fingerprint))
                    throw new IOException("The immutable preview content changed");
                return operation.run(storage);
            } catch (IllegalArgumentException invalid) { throw new IOException("Preview is unavailable", invalid); }
        }
    }

    public boolean deleteSession(String session) {
        synchronized (lock) {
            try {
                File directory = directory(session, false);
                String key = expected + "\0" + session;
                THUMBNAIL_GENERATIONS.put(key, THUMBNAIL_GENERATIONS.getOrDefault(key, 0L) + 1);
                return delete(directory);
            }
            catch (IOException | RuntimeException unavailable) { return false; }
        }
    }

    private void cleanupStaging(File directory) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Could not inspect attachment storage");
        // Snapshot writers are serialized; these private names cannot be created by project tools.
        for (File file : files) {
            String name = file.getName();
            if ((name.startsWith(".delivery-") || name.startsWith(".metadata-") || name.startsWith(".preview-")) && name.endsWith(".tmp")) {
                verify(file);
                if (!delete(file)) throw new IOException("Could not remove interrupted attachment copy");
            }
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
        byte[] encoded = record.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_MANIFEST_BYTES) throw new IOException("Artifact manifest exceeds its metadata limit");
        File staged = File.createTempFile(".metadata-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(staged)) {
                output.write(encoded); output.getFD().sync();
            }
            verify(staged); verify(target);
            if (target.exists() || !staged.renameTo(target)) throw new IOException("Could not persist attachment manifest");
            ArtifactSnapshotIO.syncDirectory(target.getParentFile());
        } finally { if (staged.exists()) staged.delete(); }
    }

    private JSONObject readManifest(File file) throws Exception {
        verify(file);
        if (!file.isFile() || file.length() > MAX_MANIFEST_BYTES) throw new IOException("Artifact manifest missing or oversized");
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_MANIFEST_BYTES) throw new IOException("Artifact manifest oversized");
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
        if (extension.equals("html") || extension.equals("htm")) return "text/html";
        if (extension.equals("apk")) return "application/vnd.android.package-archive";
        if (extension.equals("md")) return "text/markdown";
        if (extension.equals("json")) return "application/json";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return ChatAttachment.normalizeMimeType(type);
    }
}
