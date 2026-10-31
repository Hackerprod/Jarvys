package com.jarvys.agent;

import static org.junit.Assert.*;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** API 24 coverage for the production lstat path; no NIO calls are added to the API 24 app path. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 24, shadows = BotIconLegacyPathTest.NoFollowOsShadow.class)
public class BotIconLegacyPathTest {
    private static final String REF = "00000000-0000-0000-0000-000000000000.png";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void legacyNoFollowStatRejectsDanglingRootBotAndIconEntries() throws Exception {
        NoFollowOsShadow.calls.set(0);
        File files = temporary.newFolder("files");
        File root = new File(files, "bot_icons");
        File bot = new File(root, "custom-orchid");
        assertTrue(bot.mkdirs());
        BotIconStore store = new BotIconStore(files);
        File icon = new File(bot, REF);
        Files.createSymbolicLink(icon.toPath(), new File(temporary.getRoot(), "missing-icon").toPath());
        assertThrows(IllegalArgumentException.class, () -> store.resolve("custom-orchid", REF));
        assertThrows(IllegalArgumentException.class, () -> store.delete("custom-orchid", REF));
        assertTrue(Files.isSymbolicLink(icon.toPath()));

        File linkedBot = new File(root, "custom-dangling");
        Files.createSymbolicLink(linkedBot.toPath(), new File(temporary.getRoot(), "missing-bot").toPath());
        assertThrows(IllegalArgumentException.class, () -> store.delete("custom-dangling", REF));
        assertTrue(Files.isSymbolicLink(linkedBot.toPath()));

        File otherFiles = temporary.newFolder("other-files");
        File linkedRoot = new File(otherFiles, "bot_icons");
        Files.createSymbolicLink(linkedRoot.toPath(), new File(temporary.getRoot(), "missing-root").toPath());
        assertThrows(IllegalArgumentException.class, () -> new BotIconStore(otherFiles));
        assertTrue(Files.isSymbolicLink(linkedRoot.toPath()));
        assertTrue("API 24 must exercise Os.lstat", NoFollowOsShadow.calls.get() > 0);
    }

    @Test public void legacyStatAllowsPlatformAncestorAliasAndOrdinaryMissingCleanup() throws Exception {
        File files = temporary.newFolder("real-files");
        File alias = new File(temporary.getRoot(), "platform-alias");
        Files.createSymbolicLink(alias.toPath(), files.toPath());
        BotIconStore store = new BotIconStore(alias);
        assertTrue(store.delete("custom-orchid", REF));
        assertFalse(new File(files, "bot_icons").exists());
        File bot = new File(files, "bot_icons/custom-orchid");
        assertTrue(bot.mkdirs());
        File regular = new File(bot, REF);
        Files.write(regular.toPath(), new byte[]{1});
        assertEquals(regular, store.resolve("custom-orchid", REF));
        assertTrue(store.delete("custom-orchid", REF));
        assertFalse(regular.exists());
    }

    /**
     * Robolectric 4.16 ShadowLinux.lstat incorrectly delegates to stat and follows links. Bridge
     * only that Android syscall to the host's real NOFOLLOW attributes; all path validation and
     * deletion decisions still execute the unchanged production API 24 implementation.
     */
    @Implements(Os.class)
    public static class NoFollowOsShadow {
        static final AtomicInteger calls = new AtomicInteger();

        @Implementation protected static StructStat lstat(String path) throws ErrnoException {
            calls.incrementAndGet();
            try {
                BasicFileAttributes attributes = Files.readAttributes(new File(path).toPath(),
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                int mode = attributes.isSymbolicLink() ? OsConstants.S_IFLNK
                        : attributes.isDirectory() ? OsConstants.S_IFDIR : OsConstants.S_IFREG;
                return new StructStat(1L, 1L, mode, 1L, 0, 0, 0L, attributes.size(),
                        0L, 0L, 0L, 0L, 0L);
            } catch (NoSuchFileException absent) {
                throw new ErrnoException("lstat", OsConstants.ENOENT, absent);
            } catch (IOException failure) {
                throw new ErrnoException("lstat", OsConstants.EIO, failure);
            }
        }
    }
}
