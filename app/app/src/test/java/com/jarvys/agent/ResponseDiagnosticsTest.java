package com.jarvys.agent;

import static org.junit.Assert.*;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Synthetic provider evidence only. No network, accounts, persistence, or history mutation. */
public class ResponseDiagnosticsTest {
  @Test public void p001StopIdentifiesTheAssistantChoice() throws Exception {
    ResponseDiagnostics value = chat("stop", "Ready.", null);
    assertEquals(ResponseDiagnostics.Completion.SUCCEEDED, value.completion);
    assertEquals("stop", value.finishReason);
    assertEquals("synthetic-chat", value.responseId);
    assertTrue(value.isSuccessfulFinalAnswer());
    assertEquals("choice:0", value.finalAssistantItemKey);
    assertEquals("Ready.", value.assistantItems.get(0).text);
    assertEquals(Integer.valueOf(0), value.assistantItems.get(0).outputIndex);
    assertNull(value.assistantItems.get(0).providerId);
    assertEquals(ResponseDiagnostics.Origin.CHAT_COMPLETION_CHOICE,
        value.assistantItems.get(0).origin);
  }

  @Test public void p002ToolOnlyCompletionHasNoFinalText() throws Exception {
    ResponseDiagnostics value = chat("tool_calls", null, calls());
    assertEquals(ResponseDiagnostics.Completion.SUCCEEDED, value.completion);
    assertTrue(value.hasToolCalls);
    assertFalse(value.isSuccessfulFinalAnswer());
    assertNull(value.finalAssistantItemKey);
    assertEquals("", value.assistantItems.get(0).text);
  }

  @Test public void p003ProgressAlongsideCallsIsNotFinal() throws Exception {
    ResponseDiagnostics value = chat("tool_calls", "Checking.", calls());
    assertTrue(value.hasToolCalls);
    assertEquals("Checking.", value.assistantItems.get(0).text);
    assertFalse(value.isSuccessfulFinalAnswer());
    // Even a provider reporting stop cannot turn a real tool request into a final answer.
    assertFalse(chat("stop", "Checking.", calls()).isSuccessfulFinalAnswer());
  }

  @Test public void p004LengthPreservesPartialText() throws Exception {
    ResponseDiagnostics value = chat("length", "Unfinished", null);
    assertEquals(ResponseDiagnostics.Completion.INCOMPLETE, value.completion);
    assertEquals("length", value.finishReason);
    assertEquals("Unfinished", value.assistantItems.get(0).text);
    assertEquals(ResponseDiagnostics.ItemFinality.INCOMPLETE, value.assistantItems.get(0).finality);
    assertFalse(value.isSuccessfulFinalAnswer());
  }

  @Test public void p005ContentFilterIsNotStop() throws Exception {
    ResponseDiagnostics value = chat("content_filter", "Available", null);
    assertEquals(ResponseDiagnostics.Completion.FILTERED, value.completion);
    assertEquals("content_filter", value.finishReason);
    assertEquals("Available", value.assistantItems.get(0).text);
    assertFalse(value.isSuccessfulFinalAnswer());
  }

  @Test public void p006MissingAndUnknownReasonsStayUnknown() throws Exception {
    for (String reason : new String[] {null, "future_reason", "", "STOP"}) {
      ResponseDiagnostics value = chat(reason, "Readable", null);
      assertEquals(ResponseDiagnostics.Completion.UNKNOWN, value.completion);
      assertEquals(reason, value.finishReason);
      assertEquals("Readable", value.assistantItems.get(0).text);
      assertFalse(value.isSuccessfulFinalAnswer());
    }
  }

  @Test public void p007SseDeltasAndCompletedSnapshotShareIdentity() throws Exception {
    String body = added("item-a", 0, null) + delta("item-a", 0, 0, "Ho")
        + delta("item-a", 0, 0, "la 🚀")
        + textDone("item-a", 0, 0, "Hola 🚀")
        + completed(message("item-a", "Hola 🚀", null));
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(body);
    assertEquals(ResponseDiagnostics.Completion.SUCCEEDED, value.completion);
    assertEquals("synthetic-response", value.responseId);
    assertEquals(1, value.assistantItems.size());
    assertEquals("item-a", value.assistantItems.get(0).providerId);
    assertEquals("Hola 🚀", value.assistantItems.get(0).text);
    assertTrue(value.isSuccessfulFinalAnswer());
    assertTrue(value.warningCodes.toString(), value.warningCodes.isEmpty());
  }

  @Test public void p008CompletedFunctionCallIsTransportSuccess() throws Exception {
    JSONObject tool = new JSONObject().put("type", "function_call").put("id", "item-tool")
        .put("call_id", "call-synthetic").put("name", "read_fixture").put("arguments", "{}");
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(completed(tool));
    assertEquals(ResponseDiagnostics.Completion.SUCCEEDED, value.completion);
    assertTrue(value.hasToolCalls);
    assertTrue(value.assistantItems.isEmpty());
    assertFalse(value.isSuccessfulFinalAnswer());
    assertNull(value.finalAssistantItemKey);
  }

  @Test public void p009IncompletePreservesReasonTextAndUsage() throws Exception {
    JSONObject response = response("incomplete", message("a", "Partial", null))
        .put("incomplete_details", new JSONObject().put("reason", "max_output_tokens"))
        .put("usage", fullUsage(true));
    response.getJSONArray("output").getJSONObject(0).put("status", "incomplete");
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(event("response.incomplete", response));
    assertEquals(ResponseDiagnostics.Completion.INCOMPLETE, value.completion);
    assertEquals("max_output_tokens", value.finishReason);
    assertEquals("Partial", value.assistantItems.get(0).text);
    assertEquals(Long.valueOf(15), value.usage.totalTokens);
    assertFalse(value.isSuccessfulFinalAnswer());
  }

  @Test public void p010FailedKeepsAvailableTextAndProviderErrorCode() throws Exception {
    JSONObject response = response("failed", message("a", "Available", null))
        .put("error", new JSONObject().put("code", "synthetic_failure")
            .put("message", "Synthetic detail must never enter generic warnings"));
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(event("response.failed", response));
    assertEquals(ResponseDiagnostics.Completion.FAILED, value.completion);
    assertEquals("synthetic_failure", value.finishReason);
    assertEquals("Available", value.assistantItems.get(0).text);
    assertFalse(value.isSuccessfulFinalAnswer());
    assertFalse(value.warningCodes.toString().contains("Synthetic detail"));
  }

  @Test public void p011EofAfterTextDoesNotProveResponseSuccess() throws Exception {
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(
        added("a", 0, null) + delta("a", 0, 0, "Partial") + textDone("a", 0, 0, "Partial"));
    assertEquals(ResponseDiagnostics.Completion.UNKNOWN, value.completion);
    assertEquals("Partial", value.assistantItems.get(0).text);
    assertFalse(value.isSuccessfulFinalAnswer());
  }

  @Test public void p012DoneSentinelCannotSupplyTerminalEvidence() throws Exception {
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(
        added("a", 0, null) + delta("a", 0, 0, "Partial") + "data: [DONE]\n\n");
    assertEquals(ResponseDiagnostics.Completion.UNKNOWN, value.completion);
    assertEquals("Partial", value.assistantItems.get(0).text);
    assertFalse(value.isSuccessfulFinalAnswer());
    assertEquals(ResponseDiagnostics.Completion.UNKNOWN,
        ResponseDiagnostics.fromCodex("data: [DONE]\n\n").completion);
  }

  @Test public void p013EveryTruncatedTerminalFrameIsNonSuccessful() throws Exception {
    String terminal = completed(message("a", "Answer", null));
    for (int length = 0; length < terminal.length(); length++) {
      ResponseDiagnostics value = ResponseDiagnostics.fromCodex(terminal.substring(0, length));
      assertNotEquals("prefix " + length, ResponseDiagnostics.Completion.SUCCEEDED, value.completion);
      assertFalse(value.isSuccessfulFinalAnswer());
    }
    assertTrue(ResponseDiagnostics.fromCodex(terminal).isSuccessfulFinalAnswer());
  }

  @Test public void p013MalformedLaterEventPreservesEarlierTextButNotSuccess() throws Exception {
    String prefix = added("a", 0, null) + delta("a", 0, 0, "Available");
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(prefix + "data: {\"type\":\n\n");
    assertEquals("Available", value.assistantItems.get(0).text);
    assertEquals(ResponseDiagnostics.Completion.UNKNOWN, value.completion);
    assertTrue(value.warningCodes.contains("UNREADABLE_SSE_EVENT"));
    assertEquals(ResponseDiagnostics.Completion.UNKNOWN,
        ResponseDiagnostics.fromCodex(completed(message("a", "Ready", null))
            + "data: {\"type\":").completion);
  }

  @Test public void p015RepeatedDeltasAreLegitimateAndNotSubstringDeduplicated() throws Exception {
    String body = added("a", 0, null) + delta("a", 0, 0, "ha") + delta("a", 0, 0, "ha")
        + textDone("a", 0, 0, "haha") + completed(message("a", "haha", null));
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(body);
    assertEquals(1, value.assistantItems.size());
    assertEquals("haha", value.assistantItems.get(0).text);
    assertTrue(value.isSuccessfulFinalAnswer());
  }

  @Test public void p015IdenticalTextWithDistinctIdsRemainsTwoItems() throws Exception {
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(completed(
        message("a", "Same", null), message("b", "Same", null)));
    assertEquals(2, value.assistantItems.size());
    assertEquals("Same", value.assistantItems.get(0).text);
    assertEquals("Same", value.assistantItems.get(1).text);
    assertNotEquals(value.assistantItems.get(0).key, value.assistantItems.get(1).key);
    assertEquals("a", value.assistantItems.get(0).providerId);
    assertEquals("b", value.assistantItems.get(1).providerId);
    assertNull(value.finalAssistantItemKey);
  }

  @Test public void p016SeveralUnmarkedItemsDoNotInventOneFinalMessage() throws Exception {
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(completed(
        message("a", "Progress", null), message("b", "Answer", null)));
    assertEquals(ResponseDiagnostics.Completion.SUCCEEDED, value.completion);
    assertEquals(2, value.assistantItems.size());
    assertNull(value.finalAssistantItemKey);
    assertFalse(value.isSuccessfulFinalAnswer());
  }

  @Test public void p016ExplicitFinalPhaseDisambiguatesCommentary() throws Exception {
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(completed(
        message("a", "Progress", "commentary"), message("b", "Answer", "final_answer")));
    assertTrue(value.isSuccessfulFinalAnswer());
    assertEquals(value.assistantItems.get(1).key, value.finalAssistantItemKey);
    assertFalse(ResponseDiagnostics.fromCodex(completed(
        message("a", "One", "final_answer"), message("b", "Two", "final_answer")))
        .isSuccessfulFinalAnswer());
    assertFalse(ResponseDiagnostics.fromCodex(completed(message("a", "Progress", "commentary")))
        .isSuccessfulFinalAnswer());
  }

  @Test public void p016MissingProviderIdUsesHonestLocalIdentity() throws Exception {
    JSONObject message = message("a", "Answer", null);
    message.remove("id");
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(completed(message));
    assertNull(value.assistantItems.get(0).providerId);
    assertEquals(Integer.valueOf(0), value.assistantItems.get(0).outputIndex);
    assertEquals("output:0", value.assistantItems.get(0).key);
    assertTrue(value.isSuccessfulFinalAnswer());
  }

  @Test public void p016UnknownRolesAndOrphanTextDoNotBecomeOwnFinalMessage() throws Exception {
    JSONObject message = message("a", "Unknown speaker", null);
    message.remove("role");
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(completed(message));
    assertTrue(value.assistantItems.isEmpty());
    assertNull(value.finalAssistantItemKey);
    assertTrue(value.warningCodes.contains("UNCONFIRMED_ITEM_ROLE"));
    value = ResponseDiagnostics.fromCodex(delta("orphan", 0, 0, "Unknown speaker") + completed());
    assertTrue(value.assistantItems.isEmpty());
    assertFalse(value.isSuccessfulFinalAnswer());
  }

  @Test public void p016ConflictingIdsDoNotMergeDistinctText() throws Exception {
    String body = added("a", 0, null) + delta("a", 0, 0, "One")
        + added("b", 0, null) + delta("b", 0, 0, "Two") + completed();
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(body);
    assertEquals(2, value.assistantItems.size());
    assertEquals("One", value.assistantItems.get(0).text);
    assertEquals("Two", value.assistantItems.get(1).text);
    assertTrue(value.warningCodes.contains("AMBIGUOUS_ITEM_IDENTITY"));
    assertNull(value.finalAssistantItemKey);
  }

  @Test public void repeatedProviderIdAcrossOutputPositionsRetainsBothAvailableItems() throws Exception {
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(completed(
        message("duplicate", "First", null), message("duplicate", "Second", null)));
    assertEquals(2, value.assistantItems.size());
    assertEquals("First", value.assistantItems.get(0).text);
    assertEquals("Second", value.assistantItems.get(1).text);
    assertNotEquals(value.assistantItems.get(0).key, value.assistantItems.get(1).key);
    assertNull(value.finalAssistantItemKey);
  }

  @Test public void toolArgumentEventIsToolEvidenceEvenWithoutItsOutputItem() throws Exception {
    String body = sse(new JSONObject().put("type", "response.function_call_arguments.delta")
        .put("item_id", "synthetic-tool").put("output_index", 1).put("delta", "{}"))
        + completed(message("a", "Progress", null));
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(body);
    assertTrue(value.hasToolCalls);
    assertEquals(ResponseDiagnostics.Completion.SUCCEEDED, value.completion);
    assertFalse(value.isSuccessfulFinalAnswer());
  }

  @Test public void p017LocalCancellationWinsAndRetainsAvailableEvidence() throws Exception {
    ResponseDiagnostics succeeded = ResponseDiagnostics.fromCodex(completed(message("a", "Late", null)));
    ResponseDiagnostics cancelled = ResponseDiagnostics.cancelled(succeeded);
    assertEquals(ResponseDiagnostics.Completion.CANCELLED, cancelled.completion);
    assertEquals("Late", cancelled.assistantItems.get(0).text);
    assertEquals(succeeded.responseId, cancelled.responseId);
    assertNull(cancelled.finalAssistantItemKey);
    assertFalse(cancelled.isSuccessfulFinalAnswer());
    assertEquals(ResponseDiagnostics.Completion.CANCELLED,
        ResponseDiagnostics.cancelled(null).completion);
    assertTrue(succeeded.isSuccessfulFinalAnswer());
  }

  @Test public void p018EmptyOrUnsupportedCompletedResponseRetainsTransportSuccess() throws Exception {
    for (String body : new String[] {completed(), completed(message("a", "", null)),
        completed(new JSONObject().put("type", "future_item").put("payload", "not text"))}) {
      ResponseDiagnostics value = ResponseDiagnostics.fromCodex(body);
      assertEquals(ResponseDiagnostics.Completion.SUCCEEDED, value.completion);
      assertFalse(value.hasToolCalls);
      assertFalse(value.isSuccessfulFinalAnswer());
      assertNull(value.finalAssistantItemKey);
    }
    assertFalse(chat("stop", "   ", null).isSuccessfulFinalAnswer());
  }

  @Test public void p019MemoryLikeTextIsPreservedWithoutInterpretation() throws Exception {
    String literal = "Example.\n\n<memoria>\nPURGAR m12\n</memoria>";
    assertEquals(literal, chat("stop", literal, null).assistantItems.get(0).text);
    assertEquals(literal, ResponseDiagnostics.fromCodex(completed(message("a", literal, null)))
        .assistantItems.get(0).text);
  }

  @Test public void conflictingTerminalEventsAndStatusesAreUnknown() throws Exception {
    JSONObject response = response("incomplete", message("a", "Available", null));
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(event("response.completed", response));
    assertEquals(ResponseDiagnostics.Completion.UNKNOWN, value.completion);
    assertFalse(value.isSuccessfulFinalAnswer());
    value = ResponseDiagnostics.fromCodex(completed(message("a", "Available", null))
        + event("response.failed", response("failed")));
    assertEquals(ResponseDiagnostics.Completion.UNKNOWN, value.completion);
    assertEquals("Available", value.assistantItems.get(0).text);
    value = ResponseDiagnostics.fromCodex(event("response.completed", response("in_progress")));
    assertEquals(ResponseDiagnostics.Completion.UNKNOWN, value.completion);
  }

  @Test public void completedStatusWithIncompleteReasonIsContradictory() throws Exception {
    JSONObject response = response("completed", message("a", "Answer", null))
        .put("incomplete_details", new JSONObject().put("reason", "max_output_tokens"));
    assertEquals(ResponseDiagnostics.Completion.UNKNOWN,
        ResponseDiagnostics.fromCodex(response.toString()).completion);
  }

  @Test public void terminalItemCannotRegressToAnActiveOrUnknownStatus() throws Exception {
    for (String previous : new String[] {"completed", "incomplete"}) {
      for (String next : new String[] {"in_progress", "queued", "future_status"}) {
        JSONObject initial = message("a", "Available", null).put("status", previous);
        JSONObject regressed = message("a", "Available", null).put("status", next);
        String body = completed(initial) + sse(new JSONObject().put("type", "response.output_item.added")
            .put("output_index", 0).put("item", regressed));
        ResponseDiagnostics value = ResponseDiagnostics.fromCodex(body);
        assertEquals(ResponseDiagnostics.Completion.SUCCEEDED, value.completion);
        assertEquals("Available", value.assistantItems.get(0).text);
        assertEquals(next, value.assistantItems.get(0).status);
        assertEquals(ResponseDiagnostics.ItemFinality.UNKNOWN, value.assistantItems.get(0).finality);
        assertTrue(value.warningCodes.contains("CONFLICTING_ITEM_STATUS"));
        assertNull(value.finalAssistantItemKey);
        assertFalse(value.isSuccessfulFinalAnswer());
      }
    }
  }

  @Test public void completedResponseCannotRegressToProgressOrQueued() throws Exception {
    for (String status : new String[] {"in_progress", "queued"}) {
      String terminal = completed(message("a", "Available", null));
      String active = event("response." + status, response(status));
      ResponseDiagnostics value = ResponseDiagnostics.fromCodex(terminal + active);
      assertEquals(ResponseDiagnostics.Completion.UNKNOWN, value.completion);
      assertEquals("Available", value.assistantItems.get(0).text);
      assertTrue(value.warningCodes.contains("CONFLICTING_TERMINAL_STATUS"));
      assertNull(value.finalAssistantItemKey);
      assertFalse(value.isSuccessfulFinalAnswer());
      assertTrue(ResponseDiagnostics.fromCodex(active + terminal).isSuccessfulFinalAnswer());
    }
  }

  @Test public void moreThanOneResponseIdCannotFormOneSuccessfulResponse() throws Exception {
    String body = event("response.created", response("in_progress"))
        + event("response.completed", response("completed", message("a", "Answer", null))
            .put("id", "other-response"));
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(body);
    assertEquals(ResponseDiagnostics.Completion.UNKNOWN, value.completion);
    assertNull(value.responseId);
    assertFalse(value.isSuccessfulFinalAnswer());
  }

  @Test public void completeJsonAndSseHaveEquivalentEvidence() throws Exception {
    JSONObject response = response("completed", message("a", "Answer", null));
    ResponseDiagnostics json = ResponseDiagnostics.fromCodex(response.toString());
    ResponseDiagnostics sse = ResponseDiagnostics.fromCodex(event("response.completed", response));
    assertEquals(json.completion, sse.completion);
    assertEquals(json.responseId, sse.responseId);
    assertEquals(json.assistantItems.get(0).text, sse.assistantItems.get(0).text);
    assertTrue(json.isSuccessfulFinalAnswer());
    assertTrue(sse.isSuccessfulFinalAnswer());
  }

  @Test public void sseAllowsCrLfCommentsAndMultipleDataLines() throws Exception {
    String json = new JSONObject().put("type", "response.completed")
        .put("response", response("completed", message("a", "Answer", null))).toString(2);
    String body = "\ufeff: keepalive\r\nevent: response.completed\r\nid: stream-sequence\r\n";
    for (String line : json.split("\n")) body += "data: " + line + "\r\n";
    body += "\r\n";
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(body);
    assertTrue(value.warningCodes.toString(), value.isSuccessfulFinalAnswer());
    assertEquals("Answer", value.assistantItems.get(0).text);
  }

  @Test public void sseNameCanSupplyMissingEventTypeButConflictsAreConservative() throws Exception {
    JSONObject data = new JSONObject().put("response", response("completed", message("a", "Yes", null)));
    assertTrue(ResponseDiagnostics.fromCodex("event: response.completed\ndata: " + data + "\n\n")
        .isSuccessfulFinalAnswer());
    data.put("type", "response.failed");
    assertEquals(ResponseDiagnostics.Completion.UNKNOWN,
        ResponseDiagnostics.fromCodex("event: response.completed\ndata: " + data + "\n\n").completion);
  }

  @Test public void incompleteFilterEvidenceIsConsistent() throws Exception {
    JSONObject response = response("incomplete", message("a", "Available", null))
        .put("incomplete_details", new JSONObject().put("reason", "content_filter"));
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(event("response.incomplete", response));
    assertEquals(ResponseDiagnostics.Completion.FILTERED, value.completion);
    assertEquals("content_filter", value.finishReason);
    assertFalse(value.isSuccessfulFinalAnswer());
  }

  @Test public void fullUsageKeepsSubsetsSeparateInBothFamilies() throws Exception {
    for (boolean codex : new boolean[] {false, true}) {
      ResponseDiagnostics value = withUsage(fullUsage(codex), codex);
      assertEquals(Long.valueOf(10), value.usage.inputTokens);
      assertEquals(Long.valueOf(5), value.usage.outputTokens);
      assertEquals(Long.valueOf(15), value.usage.totalTokens);
      assertEquals(Long.valueOf(4), value.usage.cachedInputTokens);
      assertEquals(Long.valueOf(3), value.usage.reasoningTokens);
      assertEquals(ResponseDiagnostics.TotalSource.REPORTED, value.usage.totalSource);
      assertFalse(value.usage.inconsistent);
    }
  }

  @Test public void totalOnlyAndMissingUsageNeverInventZeros() throws Exception {
    for (boolean codex : new boolean[] {false, true}) {
      ResponseDiagnostics value = withUsage(new JSONObject().put("total_tokens", 15), codex);
      assertEquals(Long.valueOf(15), value.usage.totalTokens);
      assertNull(value.usage.inputTokens);
      assertNull(value.usage.outputTokens);
      assertNull(value.usage.cachedInputTokens);
      assertNull(value.usage.reasoningTokens);
      assertNull(withUsage(null, codex).usage);
    }
  }

  @Test public void missingTotalIsCalculatedOnlyFromBothReportedCounters() throws Exception {
    for (boolean codex : new boolean[] {false, true}) {
      JSONObject usage = fullUsage(codex);
      usage.remove("total_tokens");
      ResponseDiagnostics value = withUsage(usage, codex);
      assertEquals(Long.valueOf(15), value.usage.totalTokens);
      assertEquals(ResponseDiagnostics.TotalSource.CALCULATED, value.usage.totalSource);
      usage.remove(codex ? "output_tokens" : "completion_tokens");
      value = withUsage(usage, codex);
      assertNull(value.usage.totalTokens);
      assertEquals(ResponseDiagnostics.TotalSource.UNAVAILABLE, value.usage.totalSource);
    }
  }

  @Test public void malformedNegativeAndFractionalCountersStayAbsent() throws Exception {
    for (Object invalid : new Object[] {-1, "15", 1.5, true, new JSONObject()}) {
      JSONObject usage = fullUsage(true).put("input_tokens", invalid).put("total_tokens", invalid);
      ResponseDiagnostics value = withUsage(usage, true);
      assertNull(value.usage.inputTokens);
      assertNull(value.usage.totalTokens);
      assertEquals(Long.valueOf(5), value.usage.outputTokens);
      assertEquals(ResponseDiagnostics.TotalSource.UNAVAILABLE, value.usage.totalSource);
      assertTrue(value.warningCodes.contains("INVALID_USAGE"));
      assertTrue(value.isSuccessfulFinalAnswer());
    }
  }

  @Test public void malformedUsageObjectsAndDetailsAreDiagnosedWithoutTextFailure() throws Exception {
    JSONObject root = chatRoot("stop", "Answer", null).put("usage", "invalid");
    ResponseDiagnostics value = ResponseDiagnostics.fromChatCompletions(root.toString());
    assertNull(value.usage);
    assertTrue(value.warningCodes.contains("INVALID_USAGE"));
    assertTrue(value.isSuccessfulFinalAnswer());
    value = withUsage(fullUsage(true).put("input_tokens_details", "invalid"), true);
    assertNull(value.usage.cachedInputTokens);
    assertTrue(value.warningCodes.contains("INVALID_USAGE"));
    assertTrue(value.isSuccessfulFinalAnswer());
  }

  @Test public void inconsistentReportedUsageIsKeptWithDiagnostic() throws Exception {
    JSONObject usage = fullUsage(false).put("total_tokens", 2)
        .put("prompt_tokens_details", new JSONObject().put("cached_tokens", 20));
    ResponseDiagnostics value = withUsage(usage, false);
    assertEquals(Long.valueOf(2), value.usage.totalTokens);
    assertEquals(Long.valueOf(20), value.usage.cachedInputTokens);
    assertTrue(value.usage.inconsistent);
    assertTrue(value.warningCodes.contains("INCONSISTENT_USAGE"));
    assertEquals(ResponseDiagnostics.TotalSource.REPORTED, value.usage.totalSource);
  }

  @Test public void usageOverflowCannotCreateANegativeCalculatedTotal() throws Exception {
    JSONObject usage = new JSONObject().put("input_tokens", Long.MAX_VALUE).put("output_tokens", 1);
    ResponseDiagnostics value = withUsage(usage, true);
    assertNull(value.usage.totalTokens);
    assertTrue(value.usage.inconsistent);
    assertEquals(Long.valueOf(Long.MAX_VALUE), value.usage.inputTokens);
  }

  @Test public void realReportedZeroIsDifferentFromMissingCounter() throws Exception {
    ResponseDiagnostics value = withUsage(new JSONObject().put("total_tokens", 0), true);
    assertEquals(Long.valueOf(0), value.usage.totalTokens);
    assertNull(value.usage.inputTokens);
    assertEquals(ResponseDiagnostics.TotalSource.REPORTED, value.usage.totalSource);
  }

  @Test public void multipleChatChoicesAreNotOneFinalAnswer() throws Exception {
    JSONObject root = chatRoot("stop", "One", null);
    JSONObject second = chatRoot("stop", "Two", null).getJSONArray("choices").getJSONObject(0)
        .put("index", 1);
    root.getJSONArray("choices").put(second);
    ResponseDiagnostics value = ResponseDiagnostics.fromChatCompletions(root.toString());
    assertEquals(ResponseDiagnostics.Completion.SUCCEEDED, value.completion);
    assertEquals(2, value.assistantItems.size());
    assertNull(value.finalAssistantItemKey);
  }

  @Test public void malformedBodiesNeverThrowOrBecomeSuccessful() {
    for (String body : new String[] {null, "", " ", "{", "{\"id\":\"x\"", "[]", "null", "{}{}"}) {
      assertEquals(ResponseDiagnostics.Completion.UNKNOWN,
          ResponseDiagnostics.fromChatCompletions(body).completion);
      assertEquals(ResponseDiagnostics.Completion.UNKNOWN,
          ResponseDiagnostics.fromCodex(body).completion);
    }
  }

  @Test public void oversizedDiagnosticBodyHasABoundedUnknownResult() {
    char[] characters = new char[ResponseDiagnosticsParser.MAX_BODY_CHARACTERS + 1];
    java.util.Arrays.fill(characters, 'x');
    String body = new String(characters);
    for (ResponseDiagnostics value : new ResponseDiagnostics[] {
        ResponseDiagnostics.fromChatCompletions(body), ResponseDiagnostics.fromCodex(body)}) {
      assertEquals(ResponseDiagnostics.Completion.UNKNOWN, value.completion);
      assertTrue(value.warningCodes.contains("DIAGNOSTIC_LIMIT"));
      assertTrue(value.assistantItems.isEmpty());
      assertFalse(value.isSuccessfulFinalAnswer());
    }
    assertEquals(ResponseDiagnosticsParser.MAX_BODY_CHARACTERS + 1, body.length());
  }

  @Test public void deeplyNestedEvidenceIsBoundedBeforeJsonParsing() {
    StringBuilder body = new StringBuilder("{\"status\":\"completed\",\"extra\":");
    for (int i = 0; i < ResponseDiagnosticsParser.MAX_JSON_DEPTH; i++) body.append('[');
    body.append('0');
    for (int i = 0; i < ResponseDiagnosticsParser.MAX_JSON_DEPTH; i++) body.append(']');
    body.append('}');
    for (ResponseDiagnostics value : new ResponseDiagnostics[] {
        ResponseDiagnostics.fromChatCompletions(body.toString()),
        ResponseDiagnostics.fromCodex(body.toString()),
        ResponseDiagnostics.fromCodex("data: " + body + "\n\n")}) {
      assertEquals(ResponseDiagnostics.Completion.UNKNOWN, value.completion);
      assertTrue(value.warningCodes.contains("DIAGNOSTIC_LIMIT"));
    }
  }

  @Test public void depthGuardDoesNotCountBracesOrEscapedQuotesWithinText() throws Exception {
    StringBuilder literal = new StringBuilder();
    for (int i = 0; i < ResponseDiagnosticsParser.MAX_JSON_DEPTH * 2; i++)
      literal.append("[{\"\\\"'{}]");
    ResponseDiagnostics value = chat("stop", literal.toString(), null);
    assertTrue(value.isSuccessfulFinalAnswer());
    assertEquals(literal.toString(), value.assistantItems.get(0).text);
  }

  @Test public void tooManyOutputItemsCannotProduceFalseFinalEvidence() throws Exception {
    JSONObject root = response("completed");
    JSONArray output = root.getJSONArray("output");
    for (int i = 0; i <= ResponseDiagnosticsParser.MAX_ITEMS; i++)
      output.put(message("synthetic-" + i, "Available", null));
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(root.toString());
    assertEquals(ResponseDiagnostics.Completion.UNKNOWN, value.completion);
    assertTrue(value.warningCodes.contains("DIAGNOSTIC_LIMIT"));
    assertFalse(value.isSuccessfulFinalAnswer());
  }

  @Test public void tooManyStreamedItemsStopOnlyAdditiveDiagnostics() throws Exception {
    StringBuilder stream = new StringBuilder();
    for (int i = 0; i <= ResponseDiagnosticsParser.MAX_ITEMS; i++)
      stream.append(added("synthetic-" + i, i, null));
    stream.append(completed());
    ResponseDiagnostics value = ResponseDiagnostics.fromCodex(stream.toString());
    assertEquals(ResponseDiagnostics.Completion.UNKNOWN, value.completion);
    assertTrue(value.warningCodes.contains("DIAGNOSTIC_LIMIT"));
    assertEquals(ResponseDiagnosticsParser.MAX_ITEMS, value.assistantItems.size());
    assertFalse(value.isSuccessfulFinalAnswer());
  }

  @Test public void assistantItemsAndWarningsAreImmutable() throws Exception {
    ResponseDiagnostics value = chat("stop", "Answer", null);
    assertUnmodifiable(value.assistantItems);
    assertUnmodifiable(value.warningCodes);
    assertUnmodifiable(ResponseDiagnostics.unknown().assistantItems);
  }

  private static void assertUnmodifiable(List<?> list) {
    try {
      list.clear();
      fail("Diagnostic lists must be immutable");
    } catch (UnsupportedOperationException expected) {
      // Expected even for empty lists.
    }
  }

  private static ResponseDiagnostics chat(String reason, String text, JSONArray tools) throws Exception {
    return ResponseDiagnostics.fromChatCompletions(chatRoot(reason, text, tools).toString());
  }

  private static JSONObject chatRoot(String reason, String text, JSONArray tools) throws Exception {
    JSONObject message = new JSONObject().put("role", "assistant")
        .put("content", text == null ? JSONObject.NULL : text);
    if (tools != null) message.put("tool_calls", tools);
    JSONObject choice = new JSONObject().put("index", 0).put("message", message);
    if (reason != null) choice.put("finish_reason", reason);
    return new JSONObject().put("id", "synthetic-chat").put("choices", new JSONArray().put(choice));
  }

  private static JSONArray calls() throws Exception {
    return new JSONArray().put(new JSONObject().put("id", "synthetic-call").put("type", "function")
        .put("function", new JSONObject().put("name", "read_fixture").put("arguments", "{}")));
  }

  private static JSONObject message(String id, String text, String phase) throws Exception {
    JSONObject item = new JSONObject().put("id", id).put("type", "message")
        .put("role", "assistant").put("status", "completed")
        .put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", text)));
    if (phase != null) item.put("phase", phase);
    return item;
  }

  private static JSONObject response(String status, JSONObject... items) throws Exception {
    JSONArray output = new JSONArray();
    for (JSONObject item : items) output.put(item);
    return new JSONObject().put("id", "synthetic-response").put("status", status).put("output", output);
  }

  private static String event(String type, JSONObject response) throws Exception {
    return sse(new JSONObject().put("type", type).put("response", response));
  }

  private static String sse(JSONObject event) { return "data: " + event + "\n\n"; }

  private static String completed(JSONObject... items) throws Exception {
    return event("response.completed", response("completed", items));
  }

  private static String added(String id, int index, String phase) throws Exception {
    JSONObject item = message(id, "", phase).put("status", "in_progress");
    return sse(new JSONObject().put("type", "response.output_item.added")
        .put("output_index", index).put("item", item));
  }

  private static String delta(String id, int output, int content, String text) throws Exception {
    return sse(new JSONObject().put("type", "response.output_text.delta").put("item_id", id)
        .put("output_index", output).put("content_index", content).put("delta", text));
  }

  private static String textDone(String id, int output, int content, String text) throws Exception {
    return sse(new JSONObject().put("type", "response.output_text.done").put("item_id", id)
        .put("output_index", output).put("content_index", content).put("text", text));
  }

  private static JSONObject fullUsage(boolean codex) throws Exception {
    String input = codex ? "input_tokens" : "prompt_tokens";
    String output = codex ? "output_tokens" : "completion_tokens";
    return new JSONObject().put(input, 10).put(output, 5).put("total_tokens", 15)
        .put(input + "_details", new JSONObject().put("cached_tokens", 4))
        .put(output + "_details", new JSONObject().put("reasoning_tokens", 3));
  }

  private static ResponseDiagnostics withUsage(JSONObject usage, boolean codex) throws Exception {
    JSONObject root = codex ? response("completed", message("a", "Answer", null))
        : chatRoot("stop", "Answer", null);
    if (usage != null) root.put("usage", usage);
    return codex ? ResponseDiagnostics.fromCodex(root.toString())
        : ResponseDiagnostics.fromChatCompletions(root.toString());
  }
}
