package com.jarvys.agent;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only, per-receipt sharing surface. Never exposes a directory or arbitrary source URI. */
public final class DownloadContentProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Downloads are read-only");
        DownloadStore.Journal entry = requireSaved(uri);
        try {
            if (entry.media) {
                ParcelFileDescriptor result = attachedContext().getContentResolver()
                        .openFileDescriptor(Uri.parse(entry.mediaUri), "r");
                if (result == null) throw new IOException("Download unavailable");
                return result;
            }
            File file = DownloadStore.checkedLegacyFile(entry, false);
            if (!file.isFile() || file.length() != entry.bytes) throw new IOException("Download changed");
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception error) { throw missing(); }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        DownloadStore.Journal entry;
        try { entry = requireSaved(uri); } catch (FileNotFoundException error) { return null; }
        String[] requested = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        java.util.ArrayList<String> columns = new java.util.ArrayList<>();
        java.util.ArrayList<Object> values = new java.util.ArrayList<>();
        for (String name : requested) {
            if (OpenableColumns.DISPLAY_NAME.equals(name)) { columns.add(name); values.add(entry.displayName); }
            else if (OpenableColumns.SIZE.equals(name)) { columns.add(name); values.add(entry.bytes); }
        }
        MatrixCursor rows = new MatrixCursor(columns.toArray(new String[0]), 1);
        rows.addRow(values);
        return rows;
    }

    @Override public String getType(Uri uri) {
        try { return requireSaved(uri).mimeType; } catch (FileNotFoundException error) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read-only"); }

    private DownloadStore.Journal requireSaved(Uri uri) throws FileNotFoundException {
        try {
            if (uri == null || !"content".equals(uri.getScheme())
                    || !(attachedContext().getPackageName() + ".downloads").equals(uri.getAuthority())
                    || uri.getPathSegments().size() != 1 || uri.getQuery() != null || uri.getFragment() != null)
                throw missing();
            DownloadStore.Journal entry = DownloadStore.readJournal(attachedContext(), uri.getLastPathSegment());
            if (entry == null || !entry.saved()) throw missing();
            return entry;
        } catch (Exception error) { throw missing(); }
    }
    private android.content.Context attachedContext() {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider not attached");
        return context;
    }
    private static FileNotFoundException missing() { return new FileNotFoundException("Download unavailable"); }
}
