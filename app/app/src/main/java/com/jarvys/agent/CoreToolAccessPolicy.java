package com.jarvys.agent;

import java.util.Collection;

/** Matches a parent-provided allow-list for capability-scoped child-agent runs. */
public final class CoreToolAccessPolicy {
    private CoreToolAccessPolicy() {}

    public static boolean matches(String toolName, String groupName, Collection<String> allowed) {
        return allowed == null || allowed.contains("*") || allowed.contains(toolName)
                || (groupName != null && allowed.contains(groupName));
    }
}
