package com.jarvys.agent;

/** Independent pre-write gates. Stored metadata never constitutes execution permission. */
public final class CentralAvailabilityPolicy {
    private CentralAvailabilityPolicy() {}
    public static boolean roomMigrationEnabled() { return false; }
    public static boolean registryMigrationEnabled() { return false; }
    public static boolean admissionEnabled() { return false; }
}
