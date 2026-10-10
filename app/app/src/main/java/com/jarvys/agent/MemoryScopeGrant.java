package com.jarvys.agent;

/** Native user-facing provenance. Do not send this metadata to an agent in another conversation. */
public final class MemoryScopeGrant {
    public final String id;
    public final String path;
    public final String sourcePath;
    public final String sourceConversationId;
    public final String sha256;
    public final String approvedAt;
    public final String status;
    public final long revisionId;
    public final boolean legacy;
    public final boolean active;

    MemoryScopeGrant(String id, String sourcePath, String sourceConversationId, String sha256,
                     String approvedAt, long revisionId, String status) {
        this.id = id;
        this.path = "shared/" + id + ".md";
        this.sourcePath = sourcePath;
        this.sourceConversationId = sourceConversationId;
        this.sha256 = sha256;
        this.approvedAt = approvedAt;
        this.revisionId = revisionId;
        this.status = status;
        this.legacy = sourceConversationId == null;
        this.active = "ACTIVE".equals(status);
    }
}
