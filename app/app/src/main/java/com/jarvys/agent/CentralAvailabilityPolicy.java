package com.jarvys.agent;

/** Independent pre-write gates. Stored metadata never constitutes execution permission. */
public final class CentralAvailabilityPolicy {
    private CentralAvailabilityPolicy() {}
    public static boolean roomMigrationEnabled() { return false; }
    public static boolean registryMigrationEnabled() { return false; }
    public static boolean admissionEnabled() { return false; }
    /** Call before acquiring a Room writer/lifecycle lease. Never upgrades or grants permission. */
    public static void requireRegistrySchemaReady(android.content.Context context) {
        if (!admissionEnabled()) throw new IllegalStateException("Central availability admission is not enabled");
        try (com.jarvys.agent.workspaces.WorkspaceRegistry registry =
                     new com.jarvys.agent.workspaces.WorkspaceRegistry(context)) {
            if (registry.schemaVersion() != 3)
                throw new IllegalStateException("Central resume schema is not ready");
        }
    }
}
