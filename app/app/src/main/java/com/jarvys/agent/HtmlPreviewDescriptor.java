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
    public final int fileCount;
    public final long totalBytes;
    public final List<String> warnings;

    HtmlPreviewDescriptor(String artifactId, String entryPath, int fileCount, long totalBytes,
            List<String> warnings) {
        this.artifactId = artifactId;
        this.token = TOKEN_PREFIX + artifactId;
        this.entryPath = entryPath;
        this.fileCount = fileCount;
        this.totalBytes = totalBytes;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public static boolean isSnapshotToken(String token) {
        return token != null && token.matches("html-snapshot:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
