package com.jarvys.agent;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import android.os.storage.StorageManager;
import java.io.File;
import java.io.IOException;

/**
 * Whether the device can still take another {@code bytes} of conversation artifacts. The retention policy asks
 * before it writes and expels unreferenced artifacts while the answer is yes; it never invents a size of its own.
 */
interface StoragePressure {
    boolean cannotStore(File directory, long bytes);

    /** Never reports pressure; used where no device storage signal exists. */
    StoragePressure NONE = (directory, bytes) -> false;

    /**
     * The platform's own answer: from Android 8 the bytes an app may allocate without cutting into the system's
     * low-storage reserve, otherwise the free bytes of the volume. A failure to ask is not pressure; a real write
     * failure is handled where the write happens.
     */
    static StoragePressure system(Context context) {
        return (directory, bytes) -> {
            try {
                File existing = directory;
                while (existing != null && !existing.exists()) existing = existing.getParentFile();
                if (existing == null) return false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    StorageManager manager = context.getSystemService(StorageManager.class);
                    if (manager == null) return false;
                    return manager.getAllocatableBytes(manager.getUuidForPath(existing)) < bytes;
                }
                return new StatFs(existing.getPath()).getAvailableBytes() < bytes;
            } catch (IOException | RuntimeException unavailable) {
                return false;
            }
        };
    }

    /** Whether a write failure means the volume is full, as opposed to any other I/O problem. */
    static boolean isNoSpace(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof android.system.ErrnoException
                    && ((android.system.ErrnoException) cause).errno == android.system.OsConstants.ENOSPC) return true;
            String message = cause.getMessage();
            if (message != null && message.contains("ENOSPC")) return true;
        }
        return false;
    }
}
