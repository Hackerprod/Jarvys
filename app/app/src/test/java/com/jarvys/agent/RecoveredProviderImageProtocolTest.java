package com.jarvys.agent;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
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

  @Test public void providerBuildersLeaveToolResultAsLastInputWhenPromptIsEmpty() throws Exception {
    List<ConversationTurn> history = Arrays.asList(
        new ConversationTurn("user", "Original question"),
        ConversationTurn.toolCalls("", Collections.singletonList(
            new ModelReply.Call("call-1", "lookup", Collections.emptyMap()))),
        ConversationTurn.toolResult("call-1", "lookup", "Factual result"));

    Method codexMethod = OpenAICodexResponsesClient.class.getDeclaredMethod("userInput",
        String.class, List.class, List.class);
    codexMethod.setAccessible(true);
    JSONArray codexInput = (JSONArray) codexMethod.invoke(null, "", Collections.emptyList(), history);
    assertEquals(3, codexInput.length());
    assertEquals("function_call_output", codexInput.getJSONObject(2).getString("type"));

    Method chatMethod = OpenRouterClient.class.getDeclaredMethod("conversationMessages",
        String.class, List.class, String.class, List.class);
    chatMethod.setAccessible(true);
    JSONArray chatMessages = (JSONArray) chatMethod.invoke(null, "system", history, "", Collections.emptyList());
    assertEquals(4, chatMessages.length());
    assertEquals("tool", chatMessages.getJSONObject(3).getString("role"));
    assertEquals("Factual result", chatMessages.getJSONObject(3).getString("content"));
  }

  @Test public void bothProvidersKeepTwoToolResultsWithoutAppendingAnEmptyUserMessage() throws Exception {
    List<ConversationTurn> history = Arrays.asList(
        new ConversationTurn("user", "Original request"),
        ConversationTurn.toolCalls("", Arrays.asList(
            new ModelReply.Call("call-1", "lookup", Collections.emptyMap()),
            new ModelReply.Call("call-2", "inspect", Collections.emptyMap()))),
        ConversationTurn.toolResult("call-1", "lookup", "first result"),
        ConversationTurn.toolResult("call-2", "inspect", "second result"));

    JSONArray codexInput = codexInput("", history);
    assertEquals(5, codexInput.length());
    assertEquals("function_call_output", codexInput.getJSONObject(3).getString("type"));
    assertEquals("function_call_output", codexInput.getJSONObject(4).getString("type"));

    JSONArray chatMessages = chatMessages("", history);
    assertEquals(5, chatMessages.length());
    assertEquals("tool", chatMessages.getJSONObject(3).getString("role"));
    assertEquals("tool", chatMessages.getJSONObject(4).getString("role"));
    assertEquals("first result", chatMessages.getJSONObject(3).getString("content"));
    assertEquals("second result", chatMessages.getJSONObject(4).getString("content"));
  }

  @Test public void compactedSummaryWithEmptyPromptDoesNotCreateAnEmptyUserMessage() throws Exception {
    List<ConversationTurn> compacted = Collections.singletonList(
        ConversationTurn.compactionSummary("retained summary", 7));

    JSONArray codexInput = codexInput("", compacted);
    assertEquals(1, codexInput.length());
    assertTrue(codexInput.getJSONObject(0).getString("content").contains("retained summary"));

    JSONArray chatMessages = chatMessages("", compacted);
    assertEquals(2, chatMessages.length());
    assertTrue(chatMessages.getJSONObject(1).getString("content").contains("retained summary"));
  }

  @Test public void nonEmptyPromptIsSentEvenWhenHistoryEndsInToolResults() throws Exception {
    List<ConversationTurn> history = Arrays.asList(
        new ConversationTurn("user", "Earlier request"),
        ConversationTurn.toolCalls("", Collections.singletonList(
            new ModelReply.Call("call-1", "lookup", Collections.emptyMap()))),
        ConversationTurn.toolResult("call-1", "lookup", "prior result"));

    JSONArray codexInput = codexInput("Current request", history);
    assertEquals(4, codexInput.length());
    assertEquals("user", codexInput.getJSONObject(3).getString("role"));
    assertEquals("Current request", codexInput.getJSONObject(3).getJSONArray("content")
        .getJSONObject(0).getString("text"));

    JSONArray chatMessages = chatMessages("Current request", history);
    assertEquals(5, chatMessages.length());
    assertEquals("user", chatMessages.getJSONObject(4).getString("role"));
    assertEquals("Current request", chatMessages.getJSONObject(4).getString("content"));
  }

  @Test public void firstPromptIsSentWhenThereIsNoTranscript() throws Exception {
    JSONArray codexInput = codexInput("First request", Collections.emptyList());
    assertEquals(1, codexInput.length());
    assertEquals("First request", codexInput.getJSONObject(0).getJSONArray("content")
        .getJSONObject(0).getString("text"));

    JSONArray chatMessages = chatMessages("First request", Collections.emptyList());
    assertEquals(2, chatMessages.length());
    assertEquals("First request", chatMessages.getJSONObject(1).getString("content"));
  }

  @Test public void emptyStandaloneRequestsFailBeforeBuildingAnInvalidProviderPayload() throws Exception {
    try {
      codexInput("", Collections.emptyList());
      fail("Responses must not receive an empty input");
    } catch (InvocationTargetException expected) {
      assertTrue(expected.getCause() instanceof IllegalArgumentException);
    }
    Method method = OpenRouterClient.class.getDeclaredMethod("messages", String.class, String.class, List.class);
    method.setAccessible(true);
    try {
      method.invoke(null, "system", "", Collections.emptyList());
      fail("Chat Completions must not receive an empty user turn");
    } catch (InvocationTargetException expected) {
      assertTrue(expected.getCause() instanceof IllegalArgumentException);
    }
  }

  private static JSONArray codexInput(String prompt, List<ConversationTurn> history) throws Exception {
    Method method = OpenAICodexResponsesClient.class.getDeclaredMethod("userInput",
        String.class, List.class, List.class);
    method.setAccessible(true);
    return (JSONArray) method.invoke(null, prompt, Collections.emptyList(), history);
  }

  private static JSONArray chatMessages(String prompt, List<ConversationTurn> history) throws Exception {
    Method method = OpenRouterClient.class.getDeclaredMethod("conversationMessages",
        String.class, List.class, String.class, List.class);
    method.setAccessible(true);
    return (JSONArray) method.invoke(null, "system", history, prompt, Collections.emptyList());
  }
}
