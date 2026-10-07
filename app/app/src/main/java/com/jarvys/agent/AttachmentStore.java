package com.jarvys.agent;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Private, conversation-isolated attachment copies. Only metadata is persisted in the chat log. */
public final class AttachmentStore {
    private static final Map<String, Object> ROOT_LOCKS = new ConcurrentHashMap<>();
    private final File attachmentsRoot;
    private final String canonicalRoot;
    private final ContentResolver resolver;
    private final Object rootLock;

    public AttachmentStore(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), "jarvys"),
                context.getApplicationContext().getContentResolver());
    }

    AttachmentStore(File jarvysRoot) { this(jarvysRoot, null); }

    AttachmentStore(File jarvysRoot, ContentResolver resolver) {
        this.resolver = resolver;
        File absolute = jarvysRoot.getAbsoluteFile();
        File parent = absolute.getParentFile();
        if (parent == null) throw new IllegalArgumentException("Attachment root needs a parent");
        attachmentsRoot = new File(absolute, "attachments");
        canonicalRoot = canonical(attachmentsRoot);
        String expected = new File(new File(canonical(parent), absolute.getName()), "attachments").getPath();
        if (!expected.equals(canonicalRoot)) {
            throw new IllegalArgumentException("Attachment root cannot be a symbolic link");
        }
        rootLock = ROOT_LOCKS.computeIfAbsent(canonicalRoot, ignored -> new Object());
    }

    public ChatAttachment copyFromUri(String sessionId, Uri uri) throws IOException {
        return copyFromUri(sessionId, uri, null);
    }

    public ChatAttachment copyFromUri(String sessionId, Uri uri, ChatAttachment.Kind kind) throws IOException {
        if (resolver == null) throw new IllegalStateException("Content resolver is unavailable");
        if (uri == null || !"content".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Attachments require a content URI");
        }
        String name = null;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !cursor.isNull(column)) name = cursor.getString(column);
            }
        } catch (RuntimeException ignored) {
            // Providers are allowed to omit the display name or reject the projection.
        }
        String mime = null;
        try { mime = resolver.getType(uri); } catch (RuntimeException ignored) { }
        if (kind == ChatAttachment.Kind.IMAGE && (mime == null || !mime.startsWith("image/"))) mime = "image/jpeg";
        InputStream input = resolver.openInputStream(uri);
        if (input == null) throw new IOException("The selected attachment could not be opened");
        return copyFromStream(sessionId, name, mime, kind, input);
    }

    /** Takes ownership of input, including failures before copying. Publishes only after both streams close. */
    public ChatAttachment copyFromStream(String sessionId, String name, String mimeType,
                                         ChatAttachment.Kind requestedKind, InputStream input) throws IOException {
        if (input == null) throw new IOException("Attachment input is missing");
        synchronized (rootLock) {
            File temporary = null;
            try {
                File target;
                ChatAttachment attachment;
                try (InputStream source = input) {
                    File directory = sessionDirectory(sessionId);
                    if (!directory.isDirectory() && !directory.mkdirs()) {
                        throw new IOException("Could not create attachment storage");
                    }
                    rejectSymlink(directory);
                    String safeName = ChatAttachment.sanitizeName(name);
                    String mime = ChatAttachment.normalizeMimeType(mimeType);
                    ChatAttachment.Kind kind = requestedKind == null
                            ? (mime.startsWith("image/") ? ChatAttachment.Kind.IMAGE : ChatAttachment.Kind.FILE)
                            : requestedKind;
                    String id;
                    do {
                        id = UUID.randomUUID().toString();
                        target = new File(directory, id + "-" + safeName);
                        rejectSymlink(target);
                    } while (target.exists());
                    temporary = File.createTempFile(".attachment-", ".tmp", directory);
                    rejectSymlink(temporary);
                    long size;
                    try (FileOutputStream output = new FileOutputStream(temporary)) {
                        size = copyStream(source, output);
                        output.flush();
                        output.getFD().sync();
                    }
                    attachment = new ChatAttachment(id, safeName, mime, size, kind, target.getName());
                }
                rejectSymlink(target);
                rejectSymlink(temporary);
                if (target.exists() || !temporary.renameTo(target)) throw new IOException("Could not save attachment");
                return attachment;
            } finally {
                if (temporary != null) deleteRegularFileBestEffort(temporary);
            }
        }
    }

    static long copyStream(InputStream input, FileOutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        long size = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) {
                int one = input.read();
                if (one < 0) break;
                output.write(one);
                size++;
            } else {
                output.write(buffer, 0, count);
                size += count;
            }
        }
        return size;
    }

    public File resolve(String sessionId, ChatAttachment attachment) {
        if (attachment == null) throw new IllegalArgumentException("Attachment is missing");
        return resolve(sessionId, attachment.relativePath);
    }

    public File resolve(String sessionId, String relativePath) {
        synchronized (rootLock) {
            File target = attachmentPath(sessionId, relativePath);
            if (!target.isFile()) throw new IllegalArgumentException("Attachment file is unavailable");
            return target;
        }
    }

    public File sessionDirectory(String sessionId) {
        validateSession(sessionId);
        rejectSymlink(attachmentsRoot);
        File session = new File(attachmentsRoot, sessionId);
        rejectSymlink(session);
        return session;
    }

    public File rootDirectory() { return attachmentsRoot; }

    public boolean deleteAttachment(String sessionId, ChatAttachment attachment) {
        if (attachment == null) return true;
        synchronized (rootLock) {
            File target = attachmentPath(sessionId, attachment.relativePath);
            return !target.exists() || (target.isFile() && target.delete());
        }
    }

    public boolean deleteSession(String sessionId) {
        synchronized (rootLock) { return deleteTreeBestEffort(sessionDirectory(sessionId)); }
    }

    public void cleanupOrphans(Map<String, Set<String>> persistedReferences) {
        if (persistedReferences == null) throw new IllegalArgumentException("Persisted references are required");
        synchronized (rootLock) {
            rejectSymlink(attachmentsRoot);
            File[] sessions = attachmentsRoot.listFiles();
            if (sessions == null) return;
            for (File session : sessions) {
                try {
                    rejectSymlink(session);
                    if (session.isDirectory()) {
                        Set<String> keep = persistedReferences.getOrDefault(session.getName(), Collections.emptySet());
                        File[] files = session.listFiles();
                        if (files != null) {
                            for (File file : files) if (!keep.contains(file.getName())) deleteTreeBestEffort(file);
                            File[] remaining = session.listFiles();
                            if (remaining != null && remaining.length == 0) session.delete();
                        }
                    } else {
                        deleteRegularFileBestEffort(session);
                    }
                } catch (RuntimeException ignored) { }
            }
        }
    }

    private File attachmentPath(String sessionId, String relativePath) {
        if (relativePath == null || relativePath.length() < 38 || relativePath.charAt(36) != '-') {
            throw new IllegalArgumentException("Invalid attachment path");
        }
        new ChatAttachment(relativePath.substring(0, 36), relativePath.substring(37),
                "application/octet-stream", 0, ChatAttachment.Kind.FILE, relativePath);
        File file = new File(sessionDirectory(sessionId), relativePath);
        rejectSymlink(file);
        return file;
    }

    private boolean deleteTreeBestEffort(File file) {
        try {
            rejectSymlink(file);
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children == null) return false;
                boolean allDeleted = true;
                for (File child : children) allDeleted &= deleteTreeBestEffort(child);
                if (!allDeleted) return false;
            }
            return !file.exists() || file.delete();
        } catch (RuntimeException ignored) { return false; }
    }

    private void deleteRegularFileBestEffort(File file) {
        try { rejectSymlink(file); if (file.isFile()) file.delete(); }
        catch (RuntimeException ignored) { }
    }

    private void rejectSymlink(File file) {
        String logicalRoot = attachmentsRoot.getAbsolutePath();
        String logical = file.getAbsolutePath();
        if (!logical.equals(logicalRoot) && !logical.startsWith(logicalRoot + File.separator)) {
            throw new IllegalArgumentException("Attachment path escapes private storage");
        }
        String relative = logical.equals(logicalRoot) ? "" : logical.substring(logicalRoot.length() + 1);
        String expected = relative.isEmpty() ? canonicalRoot : new File(canonicalRoot, relative).getPath();
        if (!canonical(file).equals(expected) || !canonical(attachmentsRoot).equals(canonicalRoot)) {
            throw new IllegalArgumentException("Attachment paths cannot use symbolic links");
        }
    }

    private static void validateSession(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_.-]{1,100}")
                || sessionId.equals(".") || sessionId.equals("..")) {
            throw new IllegalArgumentException("Invalid attachment conversation id");
        }
    }

    private static String canonical(File file) {
        try { return file.getCanonicalPath(); }
        catch (IOException error) { throw new IllegalArgumentException("Could not resolve attachment path", error); }
    }
}
