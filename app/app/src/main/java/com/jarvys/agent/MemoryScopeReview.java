package com.jarvys.agent;

/** Exact, transient user-review material. This is never an agent-selected scope or grant. */
public final class MemoryScopeReview {
    public final String sourcePath;
    public final String sourceConversationId;
    public final String content;
    public final String sha256;
    public final long revisionId;
    public final long modifiedAtMillis;
    public final boolean legacy;
    final String ownerRoot;
    final String sourceKey;
    final String auditFingerprint;
    final long byteLength;

    MemoryScopeReview(String ownerRoot, String sourceKey, String sourcePath, String sourceConversationId,
                      String content, String sha256, long revisionId, long modifiedAtMillis,
                      String auditFingerprint, long byteLength) {
        this.ownerRoot = ownerRoot;
        this.sourceKey = sourceKey;
        this.sourcePath = sourcePath;
        this.sourceConversationId = sourceConversationId;
        this.content = content;
        this.sha256 = sha256;
        this.revisionId = revisionId;
        this.modifiedAtMillis = modifiedAtMillis;
        this.auditFingerprint = auditFingerprint;
        this.byteLength = byteLength;
        this.legacy = sourceConversationId == null;
    }
}
