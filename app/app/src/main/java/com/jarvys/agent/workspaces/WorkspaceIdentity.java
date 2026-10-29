package com.jarvys.agent.workspaces;

/** Reserved coordination identities. Existing conversation identifiers are never rewritten. */
public final class WorkspaceIdentity {
    public static final String CENTRAL_SESSION = "jarvys-central";
    private static final String PREFIX = "jarvys-workspace-";
    private WorkspaceIdentity() { }

    public static String requireWorkspaceId(String id) {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("Invalid workspace identity");
        }
        return id;
    }

    public static String executionSessionId(String workspaceId) {
        return PREFIX + requireWorkspaceId(workspaceId);
    }

    public static boolean isExecutionSession(String sessionId) {
        if (sessionId == null || !sessionId.startsWith(PREFIX)) return false;
        try { requireWorkspaceId(sessionId.substring(PREFIX.length())); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public static String workspaceId(String sessionId) {
        if (!isExecutionSession(sessionId)) throw new IllegalArgumentException("Invalid workspace execution identity");
        return sessionId.substring(PREFIX.length());
    }

    /** Ordinary conversation service only. Workspace workers use their supervisor/Crew route. */
    public static String serviceSession(String requested) {
        if (requested == null || requested.isEmpty() || isCentral(requested)) return CENTRAL_SESSION;
        throw new IllegalArgumentException("The conversation service requires Central");
    }

    public static boolean isCentral(String sessionId) { return CENTRAL_SESSION.equals(sessionId); }
}
