package com.jarvys.factory.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One process-wide runtime owner; never deletes default, foreign or live profiles. */
final class PreviewProfiles {
    interface Provider { List<String> names(); boolean delete(String name); }
    static final String PREFIX = "jarvys_factory_preview_";
    private final Set<String> live = new HashSet<>();
    static boolean owned(String name) {
        return name != null && name.matches(PREFIX + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    synchronized String reserve() {
        String name = PREFIX + UUID.randomUUID(); live.add(name); return name;
    }
    /** Retries only unreferenced names from this runtime namespace, including previous crashes. */
    synchronized boolean cleanup(Provider provider) {
        boolean complete = true;
        try {
            for (String name : provider.names()) {
                if (!owned(name) || live.contains(name)) continue;
                try { if (!provider.delete(name)) complete = false; }
                catch (RuntimeException ignored) { complete = false; }
            }
        } catch (RuntimeException ignored) { complete = false; }
        return complete;
    }
    /** Destroy first. Failed destruction retains the live lease until process termination. */
    synchronized boolean retire(String name, Runnable destroy, Provider provider) {
        try { destroy.run(); }
        catch (RuntimeException ignored) { return false; }
        if (name == null) return true;
        if (!owned(name) || !live.remove(name)) return false;
        try { return provider.delete(name); }
        catch (RuntimeException ignored) { return false; }
    }
}
