package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.chatdb.ChatDatabaseBridge;
import org.json.JSONObject;
import java.util.Map;

/** Captures normalized effects without copying tool arguments or connector content into memory. */
final class EpisodeRecorder implements CoreToolRegistry.InvocationObserver {
  private final String conversationId;
  private final String ownerMessageId;
  private final ChatDatabaseBridge database;
  private final String runId;
  private final MainChatTranscriptStore transcriptStore;
  private final Map<String, MainChatTranscriptStore.CallBinding> admitted = new java.util.concurrent.ConcurrentHashMap<>();

  EpisodeRecorder(Context context, String conversationId, String ownerMessageId) {
    this(context, conversationId, ownerMessageId, null, null);
  }

  EpisodeRecorder(Context context, String conversationId, String ownerMessageId, String runId,
      MainChatTranscriptStore transcriptStore) {
    this.conversationId = conversationId;
    this.ownerMessageId = ownerMessageId;
    this.database = ChatDatabaseBridge.getInstance(context);
    this.runId = runId;
    this.transcriptStore = transcriptStore;
    if (runId != null && transcriptStore == null)
      throw new IllegalArgumentException("A recorded chat run requires its transcript store");
  }

  @Override public void beforeDispatch(CoreTool tool, String callId, Map<String, Object> arguments,
      EffectInvocation invocation) {
    CentralInvocationWitness witness = invocation.centralSource();
    if (witness != null) {
      witness.requireInvocationCurrent();
      CentralInvocationSource source = witness.source();
      MainChatTranscriptStore.CallBinding binding = transcriptStore == null ? null : transcriptStore.committedCall(callId);
      if (binding == null || !conversationId.equals(source.ownerCentralSessionId)
          || !ownerMessageId.equals(source.sourceMessageId) || !java.util.Objects.equals(runId, source.sourceRunId)
          || !binding.batchId.equals(source.sourceBatchId) || !callId.equals(source.sourceCallId))
        throw new IllegalStateException("Effect recorder and operational source disagree");
    }
    beforeDispatch(tool, callId, arguments);
  }

  @Override public void beforeDispatch(CoreTool tool, String callId, Map<String, Object> arguments) {
    if (blank(callId) || blank(ownerMessageId)) throw new IllegalStateException("Durable call and owner identity are required for effect evidence");
    MainChatTranscriptStore.CallBinding binding = transcriptStore == null ? null : transcriptStore.committedCall(callId);
    String connector = tool instanceof CoreConnectorTool ? ((CoreConnectorTool) tool).connectorId()
        : tool instanceof CoreMcpTool ? "mcp:" + ((CoreMcpTool) tool).policyGroupName() : null;
    String policy = "UNSPECIFIED";
    JSONObject facts = new JSONObject();
    try {
      facts.put("classification", "WRITE");
      facts.put("policy", policy);
    } catch (Exception ignored) { }
    database.personalAgentRecordEpisodeToolStartedSync(conversationId, ownerMessageId, callId,
        tool.declaration().name, connector, null, facts.toString(), "intent", null, null,
        binding == null ? null : binding.batchId, runId,
        runId == null ? null : com.jarvys.agent.crew.CrewProcessIdentity.ID);
    if (binding != null) admitted.put(callId, binding);
  }

  @Override public void onEffectAdmission(CoreTool tool, String callId, EffectInvocation.Admission admission) {
    if (blank(callId) || blank(ownerMessageId)) throw new IllegalStateException("Durable call and owner identity are required for effect evidence");
    MainChatTranscriptStore.CallBinding binding = admitted.get(callId);
    if (runId != null && binding == null) throw new IllegalStateException("Committed call identity is required");
    database.personalAgentAdmitEpisodeToolEvidenceSync(conversationId, callId,
        admission.authorization.name().toLowerCase(java.util.Locale.ROOT), admissionFacts(admission), null,
        binding == null ? null : binding.batchId, runId, admission.connectionObservation);
  }

  @Override public void afterDispatch(CoreTool tool, String callId, CoreToolResult result, Throwable failure) {
    // Legacy observer entry cannot prove dispatch or completion.
    afterDispatch(tool, callId, result, failure,
        new EffectInvocation.Receipt(EffectInvocation.Outcome.UNCERTAIN, null));
  }

  @Override public void afterDispatch(CoreTool tool, String callId, CoreToolResult result, Throwable failure,
      EffectInvocation.Receipt receipt) {
    if (blank(callId) || blank(ownerMessageId)) return;
    MainChatTranscriptStore.CallBinding binding = admitted.get(callId);
    if (runId != null && binding == null) return;
    EffectInvocation.Admission admission = receipt == null ? null : receipt.admission;
    String outcome = receipt == null ? "uncertain" : receipt.outcome.name().toLowerCase(java.util.Locale.ROOT);
    database.personalAgentFinishEpisodeToolEvidenceSync(conversationId, callId, outcome,
        completionFacts(receipt), null, binding == null ? null : binding.batchId, runId,
        admission == null || admission.authorization == EffectInvocation.Authorization.UNSPECIFIED ? null
            : admission.authorization.name().toLowerCase(java.util.Locale.ROOT));
  }

  /** Registry supplies the owner/snapshot-normalized receipt, never producer response JSON. */
  private static String completionFacts(EffectInvocation.Receipt receipt) {
    if (receipt == null) return "{}";
    if (receipt.completionSemantics == EffectInvocation.CompletionSemantics.EXTERNAL_EFFECT)
      return admissionFacts(receipt.admission);
    try {
      JSONObject facts = new JSONObject(admissionFacts(receipt.admission));
      facts.put("completion_semantics", receipt.completionSemantics.name().toLowerCase(java.util.Locale.ROOT));
      facts.put("goal_verified", false);
      if (receipt.reference != null) facts.put("effect_reference", new JSONObject()
          .put("kind", receipt.reference.kind.name().toLowerCase(java.util.Locale.ROOT))
          .put("scope_id", receipt.reference.scopeId).put("id", receipt.reference.id));
      return facts.toString();
    } catch (Exception failure) { throw new IllegalStateException("Cannot encode normalized completion semantics", failure); }
  }

  private static String admissionFacts(EffectInvocation.Admission admission) {
    if (admission == null) return "{}";
    JSONObject facts = new JSONObject();
    try {
      facts.put("admission_authority", admission.authority);
      facts.put("admission_action", admission.actionId);
      facts.put("admission_revision", admission.revision);
      facts.put("admission_origin", admission.origin);
    } catch (Exception failure) { throw new IllegalStateException("Cannot encode admission facts", failure); }
    return facts.toString();
  }

  private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
