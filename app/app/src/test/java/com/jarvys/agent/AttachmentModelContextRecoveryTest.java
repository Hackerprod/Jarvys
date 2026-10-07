package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.core.app.ApplicationProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;
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
public class AttachmentModelContextRecoveryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Context context() {
        Context base = ApplicationProvider.getApplicationContext();
        return new ContextWrapper(base) {
            @Override public File getFilesDir() { return temp.getRoot(); }
            @Override public Context getApplicationContext() { return this; }
        };
    }
    private byte[] png() {
        Bitmap bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        bitmap.recycle();
        return output.toByteArray();
    }

    @Test public void modelPreparationAddsExactReferencesAndWorkspaceMetadataWithoutChangingUserText() throws Exception {
        Context context = context();
        new ProviderSettings(context).setProvider(ProviderSettings.Provider.CUSTOM);
        AttachmentStore store = new AttachmentStore(context);
        ChatAttachment image = store.copyFromStream("chat", "input.png", "image/png", null, new ByteArrayInputStream(png()));
        ChatAttachment file = store.copyFromStream("chat", "document.txt", "text/plain", null,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        ConversationTurn original = ConversationTurn.messageWithAttachments("user", "exact original text", 7, Arrays.asList(image, file));
        ConversationTurn prepared = AttachmentModelContext.prepareTurn(context, "chat", original, CancellationToken.uncancellable());
        assertEquals(original.content, prepared.content);
        assertEquals("exact original text", original.modelContent);
        assertEquals(1, prepared.images.size());
        assertEquals(4, prepared.images.get(0).width);
        assertTrue(prepared.modelContent.contains("attachment:" + image.id));
        assertTrue(prepared.modelContent.contains("attachments/" + file.relativePath));
        assertTrue(prepared.modelContent.contains("untrusted data"));
        assertFalse(prepared.modelContent.contains("base64"));
        assertEquals(original.attachments, prepared.attachments);
        assertEquals(original.content, prepared.withoutAttachments().modelContent);
        assertTrue(prepared.withoutAttachments().images.isEmpty());
        assertTrue(AttachmentModelContext.withoutAttachments(Collections.singletonList(prepared)).get(0).attachments.isEmpty());
    }

    @Test public void unavailableAttachmentStopsPreparationWithoutDroppingOriginal() throws Exception {
        Context context = context();
        AttachmentStore store = new AttachmentStore(context);
        ChatAttachment item = store.copyFromStream("chat", "lost.txt", "text/plain", null, new ByteArrayInputStream(new byte[]{1}));
        ConversationTurn turn = ConversationTurn.messageWithAttachments("user", "keep me", 0, Collections.singletonList(item));
        assertTrue(store.deleteAttachment("chat", item));
        assertThrows(IllegalStateException.class, () -> AttachmentModelContext.prepareTurn(context, "chat", turn, CancellationToken.uncancellable()));
        assertEquals("keep me", turn.content);
        assertEquals(1, turn.attachments.size());
    }

    @Test public void listingReturnsOnlyCurrentConversationMetadataAndPagesWithExactOffset() throws Exception {
        Context context = context();
        LocalRunStore conversations = new LocalRunStore(context);
        conversations.appendConversationMessage("chat", "user", "make images");
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        String id3 = UUID.randomUUID().toString();
        GeneratedImageStore images = new GeneratedImageStore(context);
        images.save("chat", id1, png());
        images.save("chat", id2, png());
        images.save("other", id3, png());
        conversations.appendGeneratedImageEvent("chat", id1 + ".png", "first matching description", "", "", "image/png");
        conversations.appendGeneratedImageEvent("chat", id2 + ".png", "second matching description", "", "", "image/png");
        conversations.appendConversationMessage("other", "user", "other images");
        conversations.appendGeneratedImageEvent("other", id3 + ".png", "private other image", "", "", "image/png");
        ImageReferenceListingTool tool = new ImageReferenceListingTool(context, "chat", 255);
        CoreToolResult first = tool.execute(Collections.emptyMap(), CancellationToken.uncancellable());
        assertTrue(first.content, first.success);
        JSONObject page = new JSONObject(first.content);
        assertEquals(1, page.getJSONArray("images").length());
        assertEquals("generated:" + id1, page.getJSONArray("images").getJSONObject(0).getString("image_ref"));
        assertTrue(page.getBoolean("metadata_only"));
        assertEquals(1, page.getInt("next_offset"));
        assertTrue(first.content.length() <= 255);
        CoreToolResult second = tool.execute(Collections.singletonMap("offset", 1), CancellationToken.uncancellable());
        page = new JSONObject(second.content);
        assertEquals("generated:" + id2, page.getJSONArray("images").getJSONObject(0).getString("image_ref"));
        assertTrue(page.isNull("next_offset"));
        assertFalse(first.content.contains(id3));
        assertFalse(second.content.contains(id3));
        assertTrue(new JSONObject(tool.execute(Collections.singletonMap("query", "missing"), CancellationToken.uncancellable()).content)
                .getJSONArray("images").length() == 0);
    }

    @Test public void listingTruncatesDescriptionsWithoutSplittingUnicodeAndRejectsInvalidArguments() throws Exception {
        Context context = context();
        LocalRunStore conversations = new LocalRunStore(context);
        conversations.appendConversationMessage("chat", "user", "generate");
        String prompt = String.join("", Collections.nCopies(200, "😀"));
        String imageId = UUID.randomUUID().toString();
        new GeneratedImageStore(context).save("chat", imageId, png());
        conversations.appendGeneratedImageEvent("chat", imageId + ".png", prompt, "", "", "image/png");
        ImageReferenceListingTool tool = new ImageReferenceListingTool(context, "chat", 255);
        CoreToolResult result = tool.execute(Collections.emptyMap(), CancellationToken.uncancellable());
        assertTrue(result.content, result.success);
        JSONObject item = new JSONObject(result.content).getJSONArray("images").getJSONObject(0);
        assertTrue(item.getBoolean("description_truncated"));
        String shortened = item.getString("image_prompt");
        assertTrue(shortened.isEmpty() || !Character.isHighSurrogate(shortened.charAt(shortened.length() - 1)));
        assertTrue(result.content.length() <= 255);
        for (Object offset : new Object[]{-1, 0.5, Double.NaN, "0", Long.MAX_VALUE}) {
            assertFalse(tool.execute(Collections.singletonMap("offset", offset), CancellationToken.uncancellable()).success);
        }
        assertFalse(tool.execute(Collections.singletonMap("query", 1), CancellationToken.uncancellable()).success);
    }

    @Test public void resolverRequiresPersistedOwnershipAndDoesNotGuessNearbyImages() throws Exception {
        Context context = context();
        LocalRunStore conversations = new LocalRunStore(context);
        GeneratedImageStore images = new GeneratedImageStore(context);
        String id = UUID.randomUUID().toString();
        String path = images.save("chat", id, png());
        conversations.appendConversationMessage("chat", "user", "generate");
        conversations.appendGeneratedImageEvent("chat", path, "red image", "", "", "image/png");
        ImageReferenceResolver resolver = new ImageReferenceResolver(context, "chat");
        List<ImageEditInput> resolved = resolver.resolve(Collections.singletonList("generated:" + id), CancellationToken.uncancellable());
        assertEquals(1, resolved.size());
        assertEquals("image/png", resolved.get(0).mimeType);
        assertThrows(IllegalArgumentException.class, () -> new ImageReferenceResolver(context, "other")
                .resolve(Collections.singletonList("generated:" + id), CancellationToken.uncancellable()));
        String orphanId = UUID.randomUUID().toString();
        images.save("chat", orphanId, png());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(Collections.singletonList("generated:" + orphanId), CancellationToken.uncancellable()));
        images.deleteImage("chat", path);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(Collections.singletonList("generated:" + id), CancellationToken.uncancellable()));
    }
}
