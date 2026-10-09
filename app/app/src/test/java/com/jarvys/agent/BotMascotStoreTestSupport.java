package com.jarvys.agent;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.function.Supplier;

/** Real host directory fsync, because Robolectric's Os.open uses RandomAccessFile and rejects directories.
 * This does not substitute for Android durability/device validation; production still uses Os.fstat/fsync.
 */
public final class BotMascotStoreTestSupport {
    private BotMascotStoreTestSupport() { }
    public static BotMascotStore store(File filesDirectory) {
        return store(filesDirectory, () -> UUID.randomUUID().toString(), BotMascotStore.MAX_TOTAL_BYTES, BotMascotStore.MAX_TOTAL_PACKAGES);
    }
    public static BotMascotStore store(File filesDirectory, Supplier<String> ids, long bytes, int packages) {
        return new BotMascotStore(filesDirectory, ids, bytes, packages, BotMascotStoreTestSupport::syncDirectory);
    }
    static void syncDirectory(File directory) throws IOException {
        if (Files.isSymbolicLink(directory.toPath()) || !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Host test sync requires a real private directory");
        try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) { channel.force(true); }
    }
}
