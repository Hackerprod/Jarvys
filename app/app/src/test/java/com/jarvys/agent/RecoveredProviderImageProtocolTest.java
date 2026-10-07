package com.jarvys.agent;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.junit.Test;

/** Request-shape checks only: no provider or authentication network requests. */
public class RecoveredProviderImageProtocolTest {
  private ConversationTurn imageTurn() {
    return new ConversationTurn("user", "Original question")
        .withModelParts("Original question\n[image_ref=attached:one]",
            Collections.singletonList(new ConversationTurn.Image("AQID", 1, 1)));
  }

  @Test public void openRouterCarriesPreparedTextAndImageWithoutDuplicatingEmptyPrompt() throws Exception {
    Method method = OpenRouterClient.class.getDeclaredMethod("conversationMessages",
        String.class, List.class, String.class, List.class);
    method.setAccessible(true);
    JSONArray messages = (JSONArray) method.invoke(null, "system",
        Collections.singletonList(imageTurn()), "", Collections.emptyList());
    assertEquals(2, messages.length());
    JSONArray parts = messages.getJSONObject(1).getJSONArray("content");
    assertEquals("text", parts.getJSONObject(0).getString("type"));
    assertTrue(parts.getJSONObject(0).getString("text").contains("image_ref=attached:one"));
    assertEquals("data:image/jpeg;base64,AQID",
        parts.getJSONObject(1).getJSONObject("image_url").getString("url"));
  }

  @Test public void codexUsesInputImageAndKeepsSingleCurrentUserTurn() throws Exception {
    Method method = OpenAICodexResponsesClient.class.getDeclaredMethod("userInput",
        String.class, List.class, List.class);
    method.setAccessible(true);
    JSONArray input = (JSONArray) method.invoke(null, "", Collections.emptyList(),
        Collections.singletonList(imageTurn()));
    assertEquals(1, input.length());
    JSONArray parts = input.getJSONObject(0).getJSONArray("content");
    assertEquals("input_text", parts.getJSONObject(0).getString("type"));
    assertEquals("input_image", parts.getJSONObject(1).getString("type"));
    assertEquals("data:image/jpeg;base64,AQID", parts.getJSONObject(1).getString("image_url"));
  }

  @Test public void delegatedViewPreservesOriginalTextAndRemovesModelImageParts() {
    ConversationTurn original = imageTurn();
    ConversationTurn safe = AttachmentModelContext.withoutAttachments(
        Collections.singletonList(original)).get(0);
    assertEquals("Original question", safe.content);
    assertEquals(safe.content, safe.modelContent);
    assertTrue(safe.images.isEmpty());
    assertTrue(safe.attachments.isEmpty());
    assertEquals(1, original.images.size());
  }

  @Test public void toolResultCannotBeMistakenForCurrentUserImageMessage() {
    assertFalse(ConversationTurn.hasCurrentUserMessage(Collections.singletonList(
        ConversationTurn.toolResult("call", "read", "untrusted output"))));
    assertFalse(ConversationTurn.hasCurrentUserMessage(Collections.singletonList(
        ConversationTurn.compactionSummary("background", 2))));
  }
}
