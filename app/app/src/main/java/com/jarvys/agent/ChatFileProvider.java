package com.jarvys.agent;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/** Narrow read-only, single-URI grants for transcript-owned files. No caller-provided filesystem paths. */
public final class ChatFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    /** generated IDs are exact UUID.png event paths; delivered/attachment IDs are ChatAttachment UUIDs. */
    public static Uri uri(Context context, String session, String kind, String id) {
        Uri result = new Uri.Builder().scheme("content").authority(context.getPackageName() + ".chat-files")
                .appendPath(kind).appendPath(session).appendPath(id).build();
        resolve(context, result); return result;
    }

    private static Entry resolve(Context context, Uri uri) {
        if (!"content".equals(uri.getScheme()) || !(context.getPackageName() + ".chat-files").equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException("Invalid file URI");
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 3) throw new IllegalArgumentException("Invalid file URI");
        String kind = parts.get(0), session = parts.get(1), id = parts.get(2);
        LocalRunStore conversations = new LocalRunStore(context);
        if ("generated".equals(kind)) {
            if (!conversations.ownsGeneratedFile(session, id)) throw new IllegalArgumentException("Image does not belong to this chat");
            File file = new GeneratedImageStore(context).resolve(session, id);
            return new Entry(file, id, "image/png", file.length());
        }
        if (!kind.equals("delivered") && !kind.equals("attachment")) throw new IllegalArgumentException("Unknown attachment kind");
        ChatAttachment attachment = conversations.findChatFile(session, kind, id);
        if (attachment == null) throw new IllegalArgumentException("File does not belong to this chat");
        File file = kind.equals("delivered") ? new DeliveredArtifactStore(context).resolve(session, attachment)
                : new AttachmentStore(context).resolve(session, attachment);
        if (file.length() != attachment.sizeBytes) throw new IllegalArgumentException("Attachment size changed");
        return new Entry(file, attachment.name, attachment.mimeType, attachment.sizeBytes);
    }
    private Entry resolve(Uri uri) { return resolve(getContext(), uri); }
    @Override public String getType(Uri uri) { return resolve(uri).mime; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        Entry entry = resolve(uri);
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns); Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = entry.name;
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = entry.bytes;
        }
        cursor.addRow(row); return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Chat files are read-only");
        try { return ParcelFileDescriptor.open(resolve(uri).file, ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (IllegalArgumentException unavailable) { throw new FileNotFoundException("Chat file is unavailable"); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read-only"); }
    private static final class Entry {
        final File file; final String name, mime; final long bytes;
        Entry(File file, String name, String mime, long bytes) { this.file = file; this.name = name; this.mime = mime; this.bytes = bytes; }
    }
}
