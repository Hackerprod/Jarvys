package com.jarvys.agent;

import android.content.ClipData;
import android.content.Intent;

/** Explicit UI actions only; constructing an intent never launches an app or an APK installer. */
public final class DownloadIntents {
    private DownloadIntents() { }

    public static Intent open(DownloadStore.Result result) {
        requireSaved(result);
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(result.uri, result.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri(result.displayName, result.uri));
        return intent;
    }

    public static Intent share(DownloadStore.Result result) {
        requireSaved(result);
        Intent intent = new Intent(Intent.ACTION_SEND).setType(result.mimeType)
                .putExtra(Intent.EXTRA_STREAM, result.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri(result.displayName, result.uri));
        return intent;
    }

    private static void requireSaved(DownloadStore.Result result) {
        if (result == null || !result.isSuccess() || result.uri == null
                || !"content".equals(result.uri.getScheme())) throw new IllegalArgumentException("A saved download is required");
    }
}
