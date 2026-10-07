package com.jarvys.agent;

/** Single source of truth for the on-device MemFS v2 port and revision journal limits. */
public final class MemoryConstants {
    public static final String PREFERENCES_FILE = "jarvys_memory";
    public static final String ENABLED_KEY = "memory_enabled";
    public static final String DISCLOSURE_SHOWN_KEY = "memory_disclosure_shown";
    public static final String ROOT_INDEX = "MEMORY.md";
    public static final String MEMORY_DIRECTORY = "memory";
    public static final String JOURNAL_FILE = ".memory-revisions.json";
    public static final String INITIALIZED_FILE = ".memory-initialized";
    public static final int MAX_DEPTH = 2;
    public static final int MAX_FILE_CHARACTERS = 20_000;
    public static final int MAX_CORE_MEMORY_CHARACTERS = 65_536;
    public static final int MAX_PROMPT_CHARACTERS = 80 * 1024;
    public static final int MAX_TREE_LINES = 120;
    public static final int MAX_TREE_CHARACTERS = 8 * 1024;
    public static final int MAX_TREE_CHILDREN_PER_DIRECTORY = 100;
    public static final int MAX_REVISIONS = 500;
    public static final long MAX_JOURNAL_BYTES = 8L * 1024L * 1024L;
    public static final int MAX_PATH_SEGMENT_CHARACTERS = 64;

    private MemoryConstants() { }
}
