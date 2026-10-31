package com.jarvys.agent;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Filesystem-backed mock for the public MediaStore contract, shared with Activity tests. */
public final class FakeDownloadsProvider extends ContentProvider {
    public final Map<Long, ContentValues> rows = new LinkedHashMap<>();
    public int inserts, publishes, deletes, outputOpens;
    public boolean failOpen, failPublish, failDelete, failQuery, collideNext;
    public Runnable beforePublish, beforeOutputOpen;
    private long nextId = 1;

    public static FakeDownloadsProvider install() {
        FakeDownloadsProvider provider = new FakeDownloadsProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = "media";
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
        ShadowContentResolver.registerProviderInternal("media", provider);
        return provider;
    }
    @Override public boolean onCreate() { return true; }
    public File file(long id) { return new File(getContext().getCacheDir(), "fake-download-" + id); }
    public synchronized Uri firstUri() { return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, rows.keySet().iterator().next()); }
    @Override public synchronized Uri insert(Uri uri, ContentValues values) {
        if (!uri.getPath().endsWith("/downloads")) throw new IllegalArgumentException("Wrong collection");
        long id = nextId++;
        ContentValues row = new ContentValues(values);
        row.put(MediaStore.MediaColumns._ID, id);
        rows.put(id, row);
        inserts++;
        return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
    }
    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (failOpen) throw new FileNotFoundException("ENOSPC: No space left on device");
        long id = ContentUris.parseId(uri);
        if (!rows.containsKey(id)) throw new FileNotFoundException("Missing download");
        boolean write = mode.contains("w");
        if (write) {
            if (beforeOutputOpen != null) beforeOutputOpen.run();
            outputOpens++;
        }
        return ParcelFileDescriptor.open(file(id), write ? ParcelFileDescriptor.MODE_CREATE
                | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE : ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public synchronized Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        if (failQuery) throw new IllegalStateException("Provider unavailable");
        String[] columns = projection == null ? new String[]{MediaStore.MediaColumns._ID} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        boolean collection = uri.getPathSegments().size() == 2;
        if (collection && collideNext) {
            collideNext = false;
            cursor.addRow(new Object[]{999L});
            return cursor;
        }
        for (Map.Entry<Long, ContentValues> value : rows.entrySet()) {
            if (!collection && ContentUris.parseId(uri) != value.getKey()) continue;
            ContentValues data = value.getValue();
            if (args != null && args.length >= 2 && (!args[0].equals(data.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
                    || !args[1].equals(data.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)))) continue;
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) row[i] = MediaStore.MediaColumns.SIZE.equals(columns[i])
                    ? file(value.getKey()).length() : data.get(columns[i]);
            cursor.addRow(row);
        }
        return cursor;
    }
    @Override public synchronized int update(Uri uri, ContentValues values, String selection, String[] args) {
        if (beforePublish != null) beforePublish.run();
        if (failPublish) throw new IllegalStateException("Publish failed");
        ContentValues row = rows.get(ContentUris.parseId(uri));
        if (row == null) return 0;
        row.putAll(values);
        publishes++;
        return 1;
    }
    @Override public synchronized int delete(Uri uri, String selection, String[] args) {
        if (failDelete) return 0;
        long id = ContentUris.parseId(uri);
        if (rows.remove(id) == null) return 0;
        File file = file(id);
        if (file.exists() && !file.delete()) throw new IllegalStateException("Could not delete mock output");
        deletes++;
        return 1;
    }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
}
