package com.jarvys.factory.runtime;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import java.util.Arrays;
import java.util.HashSet;

/** Strict native-only broker result. Never serialize this object into the JavaScript bridge. */
final class PhotoResult {
    final IBinder transfer;
    final int size, width, height;
    final String sha256, mimeType;
    PhotoResult(Intent result, String nonce) throws FactoryException {
        try {
            if (result == null || result.getData() != null || result.getClipData() != null
                    || result.getSelector() != null || result.getFlags() != 0) throw new IllegalArgumentException();
            Bundle extras=result.getExtras();
            if (extras == null || !extras.keySet().equals(new HashSet<>(Arrays.asList(
                    "nonce","transfer","size","sha256","mimeType","width","height")))
                    || !nonce.equals(extras.get("nonce")) || !(extras.get("transfer") instanceof IBinder)
                    || !(extras.get("size") instanceof Integer) || !(extras.get("width") instanceof Integer)
                    || !(extras.get("height") instanceof Integer) || !(extras.get("sha256") instanceof String)
                    || !(extras.get("mimeType") instanceof String)) throw new IllegalArgumentException();
            transfer=extras.getBinder("transfer"); size=extras.getInt("size");
            width=extras.getInt("width"); height=extras.getInt("height");
            sha256=extras.getString("sha256"); mimeType=extras.getString("mimeType");
            if (size<1 || size>FileShareTransfer.MAX_BYTES || width<1 || width>4096 || height<1 || height>4096
                    || (long)width*height>12000000L || !sha256.matches("[a-f0-9]{64}")
                    || !("image/jpeg".equals(mimeType) || "image/png".equals(mimeType))) throw new IllegalArgumentException();
        } catch (RuntimeException malformed) {
            throw new FactoryException("INVALID_PHOTO","The photo broker returned an invalid result.");
        }
    }
}
