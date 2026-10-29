package com.jarvys.agent;

import com.jarvys.agent.chatdb.ChatDatabaseBridge;
import com.jarvys.agent.chatdb.MemoryConsolidationArguments;
import com.jarvys.agent.chatdb.MemoryConsolidationJson;
import com.jarvys.agent.chatdb.MemoryConsolidationRequest;
import com.jarvys.agent.chatdb.MemoryConsolidationResult;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical compound operation; deliberately not the maintenance daily-summary handler. */
final class MemoryConsolidationTool implements CoreTool {
  static final String NAME="memory_consolidate";
  static final String SOURCE="agent_memory_consolidation";
  private final ChatDatabaseBridge bridge;
  private final MemoryWriteGate.Ticket ticket;
  private final String conversation;
  private final int resultChars;
  private final ToolSpec declaration;
  MemoryConsolidationTool(ChatDatabaseBridge bridge, MemoryWriteGate.Ticket ticket, String conversation, int resultChars) {
    this.bridge=bridge;this.ticket=ticket;this.conversation=conversation;this.resultChars=resultChars;
    Map<String,String> fields=new LinkedHashMap<>();
    fields.put("ids_to_delete","array");
    for(String key:Arrays.asList("new_title","new_content","expected_project","type","topic_key","fingerprint")) fields.put(key,"string");
    fields.put("confirm","boolean");
    declaration=new ToolSpec(NAME,SOURCE,"Consolidate at least two owned live observations. Preview without writes, inspect the COMPLETE source/survivor plan, then repeat exact arguments with confirm=true and its fingerprint. Confirmation atomically creates a NEW survivor and soft-retires sources; it is not daily digest or a relation verdict. expected_project is an assertion, never a destination override. Drift returns a fresh plan requiring new confirmation. Never automatically confirm a replacement plan or retry an uncertain write. No session/scope overrides.",
        "memory",ToolSpec.Status.IMPLEMENTED,fields,Arrays.asList("ids_to_delete","new_title","new_content","expected_project"));
  }
  static boolean matches(CoreTool tool) { return tool!=null && NAME.equals(tool.declaration().name) && SOURCE.equals(tool.declaration().source); }
  static String rawError(CoreTool tool, Map<String,Object> args) {
    if(!matches(tool)) return null;
    try { MemoryConsolidationArguments.prepare(args);return null; }
    catch(IllegalArgumentException invalid) { return invalid.getMessage(); }
  }
  @Override public ToolSpec declaration() { return declaration; }
  @Override public boolean canDelegate() { return false; }
  @Override public UnattendedAccess unattendedAccess() { return UnattendedAccess.EXCLUDED; }
  @Override public AccessClassification accessClassification() { return AccessClassification.WRITE; }
  @Override public PersistenceEffect persistenceEffect() { return PersistenceEffect.MEMORY; }
  private boolean preview(Map<String,Object> args) {
    try { return !MemoryConsolidationArguments.prepare(args).getConfirm(); } catch(IllegalArgumentException invalid) { return false; }
  }
  @Override public EffectClass effectClass(Map<String,Object> args) { return preview(args)?EffectClass.READ:EffectClass.WRITE; }
  @Override public PersistenceEffect persistenceEffect(Map<String,Object> args) { return preview(args)?PersistenceEffect.NONE:PersistenceEffect.MEMORY; }
  @Override public CoreToolResult execute(Map<String,Object> args,CancellationToken token) { return run(args,token,null); }
  @Override public CoreToolResult execute(Map<String,Object> args,CancellationToken token,ProgressListener progress,EffectInvocation invocation) {
    return run(args,token,invocation==null?null:invocation.centralSource());
  }
  private CoreToolResult run(Map<String,Object> args,CancellationToken token,CentralInvocationWitness witness) {
    token.throwIfCancelled();
    final MemoryConsolidationRequest request;
    try { request=MemoryConsolidationArguments.prepare(args); }
    catch(IllegalArgumentException invalid) { return CoreToolResult.failure(invalid.getMessage()); }
    if(request.getConfirm() && witness==null) return CoreToolResult.failure("memory_consolidation_source_required");
    final MemoryConsolidationResult result;
    try { result=bridge.memoryConsolidationAutomatic(ticket,token,conversation,request,witness,resultChars); }
    catch(java.util.concurrent.CancellationException cancelled) { throw cancelled; }
    catch(RuntimeException failure) {
      if(!request.getConfirm()) throw failure;
      // Presentation boundary only: no received commit receipt, no invented rollback or automatic retry.
      // Class names preserve the actual exception taxonomy without logging exception payload/messages.
      org.json.JSONObject error=new org.json.JSONObject();
      try {
        error.put("outcome","unconfirmed").put("automatic_retry",false).put("error_class",failure.getClass().getName());
        if(failure.getCause()!=null) error.put("cause_class",failure.getCause().getClass().getName());
      } catch(org.json.JSONException encoding) { return CoreToolResult.failure("memory_consolidation_outcome_unconfirmed: do not retry automatically"); }
      return CoreToolResult.failure(error.toString());
    }
    boolean committed="consolidated".equals(result.getStatus());
    String evidence=committed && result.getSurvivor()!=null ? "memory_consolidated:"+result.getSurvivor().getId() : null;
    final String content;
    try { content=MemoryConsolidationJson.result(result); }
    catch(RuntimeException failure) {
      if(committed) return CoreToolResult.persistedResultUnavailable("memory_consolidation_committed_result_unavailable: committed; inspect memory and do not retry automatically.",evidence);
      throw failure;
    }
    if(content.length()>resultChars) {
      if(committed) return CoreToolResult.persistedResultUnavailable("memory_consolidation_committed_result_unavailable: complete result exceeds budget; no rollback or automatic retry.",evidence);
      return CoreToolResult.failure("memory_consolidation_preview_budget_exceeded");
    }
    if("fingerprint_mismatch".equals(result.getStatus())) return CoreToolResult.completeFailure(content);
    return committed?CoreToolResult.persistedComplete(content,evidence):CoreToolResult.complete(content);
  }
}
