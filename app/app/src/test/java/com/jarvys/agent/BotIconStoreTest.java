package com.jarvys.agent;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BotIconStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void storesSmallValidatedPngInPrivateBotScope() {
        BotIconStore store = new BotIconStore(temporary.getRoot());
        String ref = store.save("custom-orchid", png(1024, 1536), CancellationToken.uncancellable());
        File saved = store.resolve("custom-orchid", ref);
        assertTrue(ref.matches("[0-9a-f-]{36}\\.png"));
        assertEquals(new File(temporary.getRoot(), "bot_icons/custom-orchid"), saved.getParentFile());
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(saved.getPath(), bounds);
        assertTrue(bounds.outWidth > 0 && bounds.outWidth <= BotIconStore.ICON_EDGE);
        assertTrue(bounds.outHeight > 0 && bounds.outHeight <= BotIconStore.ICON_EDGE);
        assertEquals("image/png", bounds.outMimeType);
        assertEquals(1, saved.getParentFile().list().length);
        assertThrows(IllegalArgumentException.class, () -> store.resolve("custom-other", ref));
    }

    @Test public void rejectsPathsUrisAttachmentReferencesAndUnsafeIds() {
        BotIconStore store = new BotIconStore(temporary.getRoot());
        String ref = store.save("custom-orchid", png(8, 8), CancellationToken.uncancellable());
        for (String invalid : Arrays.asList("../" + ref, "/tmp/" + ref, "https://site/" + ref,
                "file:///" + ref, "content://" + ref, "generated:" + ref, "attachment:1", "", "logo.svg")) {
            assertThrows(IllegalArgumentException.class, () -> store.resolve("custom-orchid", invalid));
        }
        for (String invalid : Arrays.asList("../other", "..", ".", "/other", "custom/x", "https://x")) {
            assertThrows(IllegalArgumentException.class, () -> store.save(invalid, png(1, 1), CancellationToken.uncancellable()));
        }
        assertTrue(store.resolve("custom-orchid", ref).isFile());
    }

    @Test public void rejectsTruncatedTrailingCorruptAndNonBitmapDataBeforePersistence() {
        BotIconStore store = new BotIconStore(temporary.getRoot());
        byte[] valid = png(8, 8);
        byte[] corrupt = valid.clone();
        corrupt[20] ^= 1;
        for (byte[] invalid : Arrays.asList(new byte[0], new byte[]{1, 2, 3},
                Arrays.copyOf(valid, valid.length - 8), Arrays.copyOf(valid, valid.length + 1), corrupt,
                "<svg><script>bad</script></svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            assertThrows(IllegalArgumentException.class, () -> store.save("custom-orchid", invalid, CancellationToken.uncancellable()));
        }
        assertFalse(new File(temporary.getRoot(), "bot_icons").exists());
    }

    @Test public void cancellationDoesNotCreateAnIcon() {
        BotIconStore store = new BotIconStore(temporary.getRoot());
        CancellationToken token = CancellationToken.cancellable();
        token.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, () -> store.save("custom-orchid", png(8, 8), token));
        assertFalse(new File(temporary.getRoot(), "bot_icons").exists());
    }

    @Test public void hostileHeaderDimensionsAreRejectedBeforeDecoding() {
        BotIconStore store = new BotIconStore(temporary.getRoot());
        byte[] oversized = png(8, 8);
        java.nio.ByteBuffer.wrap(oversized, 16, 4).putInt(BotIconStore.MAX_SOURCE_EDGE + 1);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(oversized, 12, 17);
        java.nio.ByteBuffer.wrap(oversized, 29, 4).putInt((int) crc.getValue());
        assertThrows(IllegalArgumentException.class,
                () -> store.save("custom-orchid", oversized, CancellationToken.uncancellable()));
        assertFalse(new File(temporary.getRoot(), "bot_icons").exists());
    }

    @Test public void platformAliasIsAllowedButRootBotAndFileSymlinksAreRejected() throws Exception {
        File realFiles = temporary.newFolder("real-files");
        File alias = new File(temporary.getRoot(), "platform-files");
        Files.createSymbolicLink(alias.toPath(), realFiles.toPath());
        BotIconStore store = new BotIconStore(alias);
        String ref = store.save("custom-orchid", png(8, 8), CancellationToken.uncancellable());
        assertEquals(new File(realFiles, "bot_icons/custom-orchid/" + ref), store.resolve("custom-orchid", ref));
        File outside = temporary.newFolder("outside");
        File linkedBot = new File(realFiles, "bot_icons/custom-linked");
        Files.createSymbolicLink(linkedBot.toPath(), outside.toPath());
        assertThrows(IllegalArgumentException.class, () -> store.resolve("custom-linked", ref));
        File linkedFile = new File(realFiles, "bot_icons/custom-orchid/00000000-0000-0000-0000-000000000000.png");
        Files.createSymbolicLink(linkedFile.toPath(), new File(outside, "missing.png").toPath());
        assertThrows(IllegalArgumentException.class, () -> store.resolve("custom-orchid", linkedFile.getName()));
        assertThrows(IllegalArgumentException.class, () -> store.delete("custom-orchid", linkedFile.getName()));
        assertTrue(Files.isSymbolicLink(linkedFile.toPath()));
        File danglingBot = new File(realFiles, "bot_icons/custom-dangling");
        Files.createSymbolicLink(danglingBot.toPath(), new File(outside, "missing-bot").toPath());
        assertThrows(IllegalArgumentException.class, () -> store.delete("custom-dangling", ref));
        File otherFiles = temporary.newFolder("other-files");
        Files.createSymbolicLink(new File(otherFiles, "bot_icons").toPath(), outside.toPath());
        assertThrows(IllegalArgumentException.class, () -> new BotIconStore(otherFiles));
        File danglingRootFiles = temporary.newFolder("dangling-root-files");
        Files.createSymbolicLink(new File(danglingRootFiles, "bot_icons").toPath(), new File(outside, "missing-root").toPath());
        assertThrows(IllegalArgumentException.class, () -> new BotIconStore(danglingRootFiles));
    }

    static byte[] png(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff236cb4);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        bitmap.recycle();
        return output.toByteArray();
    }
}
