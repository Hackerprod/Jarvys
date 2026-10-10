package com.jarvys.agent.apkfactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Optional public test artifacts only. Callers must never supply keys or user data. */
final class FactoryApkEvidence {
    private FactoryApkEvidence() {}

    static void write(String name, byte[] bytes) throws IOException {
        String configured = System.getProperty("jarvys.factory.evidenceDir");
        if (configured == null) return;
        if (name == null || !name.matches("[a-z0-9][a-z0-9-]*\\.(apk|json)"))
            throw new IOException("Invalid factory evidence name");
        int limit = name.endsWith(".apk") ? TemplateApk.MAX_APK_BYTES : 64 * 1024;
        if (bytes == null || bytes.length == 0 || bytes.length > limit)
            throw new IOException("Factory evidence exceeds its bound");
        Path directory = Paths.get(configured).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Factory evidence directory must not be a symlink");
        Path destination = directory.resolve(name);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Factory evidence destination must be a regular file");
        Path temporary = Files.createTempFile(directory, ".factory-evidence-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer source = ByteBuffer.wrap(bytes);
                while (source.hasRemaining()) output.write(source);
                output.force(true);
            }
            if (Files.size(temporary) != bytes.length) throw new IOException("Incomplete factory evidence");
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(destination) != bytes.length || !Arrays.equals(bytes, Files.readAllBytes(destination)))
                throw new IOException("Factory evidence readback differs");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
