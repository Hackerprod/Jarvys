package com.jarvys.agent;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;

/** SAF/share intent construction centralized for image actions and deterministic tests. */
public final class GeneratedImageIntents {
    private GeneratedImageIntents() { }

    public static Intent shareIntent(Context context, File file) {
        GeneratedImageStore store = new GeneratedImageStore(context);
        if (!store.isGeneratedFile(file)) throw new IllegalArgumentException("Only private generated images can be shared");
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".generated-images", file);
        return new android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
}
