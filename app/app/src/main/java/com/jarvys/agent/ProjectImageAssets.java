package com.jarvys.agent;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.jarvys.agent.coding.ArtifactSnapshotIO;
import com.jarvys.agent.coding.ProjectScope;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import org.json.JSONObject;

/** Bounded immutable image snapshots. Paths never become provider URLs. */
final class ProjectImageAssets {
    static final int MAX_BYTES = 32 * 1024 * 1024;
    static final int PREVIEW_BYTES = 1024 * 1024;
    static final long MAX_PIXELS = 16L * 1024 * 1024;
    private ProjectImageAssets() { }

    static final class Image {
        final byte[] bytes;
        final int width, height;
        final String mime;
        final boolean resized;
        Image(byte[] bytes, int width, int height, String mime, boolean resized) {
            this.bytes = bytes; this.width = width; this.height = height; this.mime = mime; this.resized = resized;
        }
        JSONObject metadata() throws org.json.JSONException {
            return new JSONObject().put("mime_type", mime).put("size_bytes", bytes.length)
                    .put("width", width).put("height", height).put("sha256", ProjectScope.sha256(bytes))
                    .put("resized", resized).put("local_preview_asset_size_compatible", bytes.length <= PREVIEW_BYTES)
                    .put("appearance_verified", false);
        }
    }

    static Image inspect(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Image exceeds the asset size limit");
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || (long) bounds.outWidth * bounds.outHeight > MAX_PIXELS)
            throw new IOException("Image dimensions are invalid or exceed the pixel limit");
        if (!"image/png".equals(bounds.outMimeType) && !"image/jpeg".equals(bounds.outMimeType)
                && !"image/webp".equals(bounds.outMimeType)) throw new IOException("Use a PNG, JPEG or WebP project image");
        return new Image(bytes, bounds.outWidth, bounds.outHeight, bounds.outMimeType, false);
    }

    static Image generated(byte[] bytes, int maxBytes, CancellationToken token) throws IOException {
        Image original = inspect(bytes);
        if (!"image/png".equals(original.mime) || !CodexImageGenerationClient.isCompletePng(bytes))
            throw new IOException("Provider output is not a complete PNG image");
        if (bytes.length <= maxBytes) return original;
        Bitmap current = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (current == null) throw new IOException("Provider PNG could not be decoded");
        try {
            while (true) {
                token.throwIfCancelled();
                int width = Math.max(1, current.getWidth() * 3 / 4), height = Math.max(1, current.getHeight() * 3 / 4);
                Bitmap next = Bitmap.createScaledBitmap(current, width, height, true);
                if (next != current) { current.recycle(); current = next; }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!current.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Could not encode bounded PNG");
                byte[] encoded = output.toByteArray();
                if (encoded.length <= maxBytes) return new Image(encoded, width, height, "image/png", true);
                if (width == 1 && height == 1) throw new IOException("Image cannot fit the requested output budget");
            }
        } finally { current.recycle(); }
    }

    static byte[] read(File file, CancellationToken token) throws IOException {
        if (!file.isFile() || file.length() > MAX_BYTES) throw new IOException("Image file is unavailable or too large");
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer)) != -1) {
                token.throwIfCancelled();
                if (count > MAX_BYTES - output.size()) throw new IOException("Image grew beyond the asset limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    static File snapshot(ProjectScope scope, String path, String expectedSha, File cache, CancellationToken token) throws IOException {
        File temporary = File.createTempFile("project-image-", ".snapshot", cache);
        boolean success = false;
        try {
            ArtifactSnapshotIO.Snapshot snapshot = ArtifactSnapshotIO.copy(scope.rootDirectory(), scope.normalizePath(path), temporary, MAX_BYTES, token);
            if (expectedSha != null && !expectedSha.equals(snapshot.sha256)) throw new ProjectScope.ConflictException("Reference image changed; inspect its current hash");
            inspect(read(temporary, token));
            success = true;
            return temporary;
        } finally { if (!success) temporary.delete(); }
    }
}
