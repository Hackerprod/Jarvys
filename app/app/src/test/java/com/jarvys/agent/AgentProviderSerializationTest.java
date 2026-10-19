package com.jarvys.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Exercises the production provider serializers with typed AGENT conversation inputs. */
public class AgentProviderSerializationTest {
  @Test public void agentStartedJarvysTurnSerializesAsEscapedUserInputForBothProviders() throws Exception {
    ConversationTurn result = agentInput(ConversationInput.Kind.RESULT, "<done> & \"safe\"");

    JSONArray chatCompletions = OpenRouterClient.conversationMessages(
        "system", Collections.singletonList(result), "", Collections.emptyList());
    JSONArray responses = OpenAICodexResponsesClient.userInput(
        "", Collections.emptyList(), Collections.singletonList(result));

    assertProviderTurns(chatCompletions, true, "user");
    assertProviderTurns(responses, false, "user");
    assertEquals("assistant", result.role); // AGENT identity remains internal metadata, not provider role.
    assertEquals(ConversationInput.Origin.AGENT, result.incomingMetadata.origin);
    assertEscapedAgentEnvelope(chatCompletions.getJSONObject(1).getString("content"));
    assertEscapedAgentEnvelope(responses.getJSONObject(0).getString("content"));
  }

  @Test public void initialWorkerTaskSerializesAsFirstUserTurnForBothProviders() throws Exception {
    ConversationTurn task = agentInput(ConversationInput.Kind.TASK, "Do the assigned work.");

    JSONArray chatCompletions = OpenRouterClient.conversationMessages(
        "worker system", Collections.singletonList(task), "", Collections.emptyList());
    JSONArray responses = OpenAICodexResponsesClient.userInput(
        "", Collections.emptyList(), Collections.singletonList(task));

    assertProviderTurns(chatCompletions, true, "user");
    assertProviderTurns(responses, false, "user");
    assertTrue(chatCompletions.getJSONObject(1).getString("content").contains("kind=\"TASK\""));
    assertTrue(responses.getJSONObject(0).getString("content").contains("kind=\"TASK\""));
  }

  @Test public void agentSteeringBetweenAssistantStepsSerializesAsUserForBothProviders() throws Exception {
    List<ConversationTurn> history = Arrays.asList(
        new ConversationTurn("user", "Start the work."),
        ConversationTurn.assistantReply("I will begin.", Collections.emptyList()),
        agentInput(ConversationInput.Kind.MESSAGE, "Please focus on the failing case."));

    JSONArray chatCompletions = OpenRouterClient.conversationMessages(
        "system", history, "", Collections.emptyList());
    JSONArray responses = OpenAICodexResponsesClient.userInput(
        "", Collections.emptyList(), history);

    assertProviderTurns(chatCompletions, true, "user", "assistant", "user");
    assertProviderTurns(responses, false, "user", "assistant", "user");
  }

  private static ConversationTurn agentInput(ConversationInput.Kind kind, String content) {
    ConversationInput.Provenance.PrincipalRole principalRole = kind == ConversationInput.Kind.TASK
        ? ConversationInput.Provenance.PrincipalRole.SUPERVISOR
        : ConversationInput.Provenance.PrincipalRole.SUBORDINATE;
    ConversationInput.Provenance provenance = new ConversationInput.Provenance(
        "worker-session", "workspace-1", "assignment-1", "bot<&", "message-1", kind, principalRole);
    ConversationTurn.IncomingMetadata metadata = ConversationTurn.IncomingMetadata
        .forInternalInput(provenance);
    return ConversationTurn.internalInput(content, 4, metadata, "durable-agent-message-1");
  }

  private static void assertProviderTurns(JSONArray payload, boolean hasSystemMessage,
      String... expectedRoles) throws Exception {
    int first = hasSystemMessage ? 1 : 0;
    if (hasSystemMessage) assertEquals("system", payload.getJSONObject(0).getString("role"));
    assertEquals(expectedRoles.length, payload.length() - first);
    String previousRole = "";
    for (int index = first; index < payload.length(); index++) {
      String role = payload.getJSONObject(index).getString("role");
      assertEquals(expectedRoles[index - first], role);
      assertFalse("AGENT input must never be serialized as assistant", role.equals("assistant")
          && payload.getJSONObject(index).optString("content").contains("origin=\"AGENT\""));
      assertFalse("provider payload must not contain adjacent assistant messages",
          role.equals("assistant") && previousRole.equals("assistant"));
      previousRole = role;
    }
    assertEquals("user", payload.getJSONObject(payload.length() - 1).getString("role"));
    assertEquals("user", payload.getJSONObject(first).getString("role"));
  }

  private static void assertEscapedAgentEnvelope(String content) {
    assertTrue(content.startsWith("<jarvys_incoming origin=\"AGENT\" isUser=\"false\""));
    assertTrue(content.contains("<provenance sessionId=\"worker-session\""));
    assertTrue(content.contains("botId=\"bot&lt;&amp;\""));
    assertTrue(content.contains("<content>&lt;done&gt; &amp; &quot;safe&quot;</content>"));
  }
}
