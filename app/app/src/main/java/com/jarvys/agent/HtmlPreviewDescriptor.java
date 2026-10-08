package com.jarvys.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A persisted, conversation-scoped presentation token; it never contains a filesystem path. */
public final class HtmlPreviewDescriptor {
    public static final String TOKEN_PREFIX = "html-snapshot:";
    public final String token;
    public final String artifactId;
    public final String entryPath;
    /** SHA-256 of the validated immutable bundle (or saved single-file bytes). */
    public final String contentFingerprint;
    public final int fileCount;
    public final long totalBytes;
    public final List<String> warnings;

    HtmlPreviewDescriptor(String artifactId, String entryPath, int fileCount, long totalBytes,
            List<String> warnings, String contentFingerprint) {
        this.artifactId = artifactId;
        this.token = TOKEN_PREFIX + artifactId;
        this.entryPath = entryPath;
        if (contentFingerprint == null || !contentFingerprint.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid preview content fingerprint");
        this.contentFingerprint = contentFingerprint;
        this.fileCount = fileCount;
        this.totalBytes = totalBytes;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public static boolean isSnapshotToken(String token) {
        return token != null && token.matches("html-snapshot:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
