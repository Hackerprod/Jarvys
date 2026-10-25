package com.jarvys.agent;

import android.content.Context;
import java.io.File;

/** Keeps application identity separate from conversation file generations. */
public final class StorageGeneration {
    public static final String NAME = "g3";
    private final File filesDir;
    private final File chatRoot;

    public StorageGeneration(File filesDir) {
        SingleSessionReset.awaitReadyBlocking(filesDir);
        this.filesDir = filesDir.getAbsoluteFile();
        this.chatRoot = new File(new File(this.filesDir, "jarvys"), NAME);
        try {
            File expected = new File(new File(this.filesDir.getCanonicalFile(), "jarvys"), NAME);
            if (!chatRoot.getCanonicalFile().equals(expected))
                throw new IllegalArgumentException("Conversation generation cannot be a symbolic link");
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Could not resolve conversation storage", failure);
        }
    }

    public StorageGeneration(Context context) { this(context.getApplicationContext().getFilesDir()); }
    public File filesDir() { return filesDir; }
    public File chatRoot() { return chatRoot; }
    public File directory(String name) { return new File(chatRoot, name); }
}
