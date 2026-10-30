package com.jarvys.agent;

import android.content.Context;
import com.jarvys.agent.chatdb.MemoryNamespace;
import com.jarvys.agent.chatdb.MemoryObservationContent;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Native read adapter. Policy locks never enclose the controller, database acquisition or encoding. */
final class MemoryDoctorTool implements CoreTool {
  static final String NAME = "memory_doctor";
  private final Context context;
  private final MemoryWriteGate gate;
  private final MemoryWriteGate.Ticket ticket;
  private final String caller;
  private final int resultChars;
  private final ToolSpec declaration;

  MemoryDoctorTool(Context context, MemoryWriteGate gate, MemoryWriteGate.Ticket ticket, String caller, int resultChars) {
    this.context=context.getApplicationContext(); this.gate=gate; this.ticket=ticket;
    this.caller=caller; this.resultChars=resultChars;
    Map<String,String> fields=new LinkedHashMap<>(); fields.put("project","string"); fields.put("check","string");
    declaration=new ToolSpec(NAME,"agent_memory",
        "Observe memory diagnostics without repair or initialization. Omitted project means all CURRENTLY AUTHORIZED projects, not global access. Omitted check runs all ten checks. Read status AND coverage: incomplete, unavailable and pending domains are not healthy. No automatic repair, checkpoint, network or mutation.",
        "memory",ToolSpec.Status.IMPLEMENTED,fields,Collections.emptyList());
  }
  @Override public ToolSpec declaration() { return declaration; }
  @Override public boolean canDelegate() { return false; }
  @Override public AccessClassification accessClassification() { return AccessClassification.READ; }
  @Override public PersistenceEffect persistenceEffect() { return PersistenceEffect.NONE; }
  @Override public UnattendedAccess unattendedAccess() { return UnattendedAccess.EXCLUDED; }

  static MemoryDoctorOptions options(Map<String,Object> args) {
    if(args==null || !Arrays.asList("project","check").containsAll(args.keySet()))
      throw new IllegalArgumentException("memory_doctor_invalid_arguments");
    String project=args.get("project") instanceof String ? MemoryObservationContent.trimSpace((String)args.get("project")) : "";
    String check=args.get("check") instanceof String ? MemoryObservationContent.trimSpace((String)args.get("check")) : "";
    if(!check.isEmpty() && !MemoryDoctorChecks.ids.contains(check))
      throw new IllegalArgumentException("memory_doctor_invalid_arguments");
    return new MemoryDoctorOptions(project.isEmpty()?null:MemoryNamespace.normalizeProject(project),check.isEmpty()?null:check);
  }
  private <T> T accepted(CancellationToken token, T prepared) throws Exception {
    token.throwIfCancelled();
    return gate.commit(ticket,()->!token.isCancellationRequested() && WorkspaceIdentity.isCentral(caller),()->prepared);
  }
  @Override public CoreToolResult execute(Map<String,Object> args,CancellationToken token) {
    token.throwIfCancelled();
    CoreToolResult prepared;
    boolean argumentsPrepared=false;
    try {
      MemoryDoctorOptions selected=options(args);
      argumentsPrepared=true;
      accepted(token,Boolean.TRUE); // No controller, I/O or external dispatch under this gate.
      MemoryDoctorReport report=MemoryDoctorController.get(context).execute(selected,token);
      token.throwIfCancelled();
      String json=serialize(report);
      prepared=json.length()>resultChars ? failure("result_budget_exceeded",null)
          : "error".equals(report.getStatus()) ? CoreToolResult.completeFailure(json) : CoreToolResult.complete(json);
    } catch(CancellationException cancelled) { throw cancelled; }
    catch(MemoryWriteGate.RejectedException denied) { return denied(token); }
    catch(IllegalArgumentException invalid) { prepared=failure(argumentsPrepared?"operation_failed":"invalid_arguments",invalid); }
    catch(Exception failure) { prepared=failure("operation_failed",failure); }
    try { return accepted(token,prepared); }
    catch(CancellationException cancelled) { throw cancelled; }
    catch(MemoryWriteGate.RejectedException denied) { return denied(token); }
    catch(Exception failure) {
      token.throwIfCancelled();
      // The no-I/O acceptance callable cannot throw; preserve unexpected gate failures, not diagnostic data.
      if(failure instanceof RuntimeException) throw (RuntimeException)failure;
      throw new IllegalStateException("memory_doctor_acceptance_failed",failure);
    }
  }
  private static CoreToolResult denied(CancellationToken token) {
    token.throwIfCancelled();
    return failure("policy_denied",null); // Terminal fixed denial; never revalidate an already denied ticket.
  }
  private static CoreToolResult failure(String code,Exception cause) {
    try {
      JSONObject out=new JSONObject().put("status","error").put("error_code",code);
      if(cause!=null) {
        out.put("error_class",cause.getClass().getName());
        if(cause.getCause()!=null) out.put("cause_class",cause.getCause().getClass().getName());
      }
      return CoreToolResult.completeFailure(out.toString());
    } catch(JSONException impossible) { throw new IllegalStateException("memory_doctor_encoding_failed",impossible); }
  }
  static String serialize(MemoryDoctorReport report) throws JSONException {
    MemoryDoctorSummary s=report.getSummary();
    JSONObject summary=new JSONObject().put("total",s.getTotal()).put("ok",s.getOk()).put("warnings",s.getWarnings())
        .put("blocked",s.getBlocked()).put("errors",s.getErrors()).put("complete",s.getComplete()).put("partial",s.getPartial())
        .put("not_applicable",s.getNotApplicable()).put("unavailable",s.getUnavailable()).put("domain_pending",s.getDomainPending());
    JSONArray checks=new JSONArray();
    for(MemoryDoctorCheck c:report.getChecks()) {
      JSONArray findings=new JSONArray();
      for(Map<String,String> finding:c.getFindings()) findings.put(new JSONObject(finding));
      checks.put(new JSONObject().put("check_id",c.getCheckId()).put("result",c.getResult()==null?JSONObject.NULL:c.getResult())
          .put("coverage",c.getCoverage()).put("reason_code",c.getReasonCode()).put("message",c.getMessage())
          .put("evidence",new JSONObject(c.getEvidence())).put("findings",findings).put("safe_next_step",c.getSafeNextStep())
          .put("requires_confirmation",c.getRequiresConfirmation()));
    }
    return new JSONObject().put("status",report.getStatus()).put("selected_project",report.getSelectedProject()==null?JSONObject.NULL:report.getSelectedProject())
        .put("coverage_complete",report.getCoverageComplete()).put("summary",summary).put("checks",checks).toString();
  }
}
