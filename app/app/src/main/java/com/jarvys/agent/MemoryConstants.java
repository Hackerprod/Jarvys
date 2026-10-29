package com.jarvys.agent;

/** Shared preferences and prompt/display budgets for current personal memory. */
public final class MemoryConstants {
    public static final String PREFERENCES_FILE = "jarvys_memory";
    public static final String PROMPT_CAPTURE_ENABLED_KEY = "capture_user_prompts";
    public static final String ENABLED_KEY = "memory_enabled";
    public static final String DISCLOSURE_SHOWN_KEY = "memory_disclosure_shown";
    public static final int MAX_CORE_MEMORY_CHARACTERS = 65_536;
    public static final int MAX_PROMPT_CHARACTERS = 80 * 1024;

    private MemoryConstants() { }
}
