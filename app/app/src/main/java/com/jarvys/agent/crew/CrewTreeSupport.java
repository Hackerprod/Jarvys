package com.jarvys.agent.crew;

import com.jarvys.agent.CancellationToken;
import com.jarvys.agent.CoreToolResult;
import com.jarvys.agent.CrewCheckpointStore;
import com.jarvys.agent.CrewInvocationWitness;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.json.JSONObject;

/** Registry adapter; DTOs and checkpoint backlinks never create execution authority. */
public interface CrewTreeSupport {
    boolean enrolled(CrewManager.Bot bot);
    /** Denial only, called outside native cycle locks even for an already terminal requester. */
    void onRequesterStop(CrewManager.Bot bot,CancellationToken token,String cycleId);
    /** Native cycle -> pure denial monitor -> mailbox only; no IO/listeners inside action. */
    void admitOrdinaryEnqueue(CrewManager.Bot bot,CancellationToken token,Runnable mailbox);
    void requireCurrentDispatch(CrewManager.Bot bot, CancellationToken token);
    /** Null means not a formal child question; ordinary MESSAGE routing remains native. */
    CoreToolResult retainAnswer(CrewManager.Bot bot,CancellationToken token,CrewInvocationWitness source,Map<String,Object> arguments);
    CoreToolResult delegateChild(CrewManager.Bot bot, CancellationToken token, CrewInvocationWitness source,
            Map<String,Object> arguments,List<String> toolCeiling,List<String> skillCeiling);
    void prepareInvocation(CrewManager.Bot bot,CancellationToken token,CrewInvocationWitness source,Map<String,Object> arguments,String kind);
    void recordInvocationResult(CrewManager.Bot bot,CancellationToken token,CrewInvocationWitness source,CoreToolResult result);
    WaitTicket armWait(CrewManager.Bot bot,CancellationToken token,CrewInvocationWitness source,List<String> children,CrewManager.WaitMode mode);
    /** Null means no awaited/unobserved dependency, never an authority or recovery fallback. */
    WaitTicket armImplicitWait(CrewManager.Bot bot,CancellationToken token,String draft);
    WaitTicket armReportDoneWait(CrewManager.Bot bot,CancellationToken token,CrewInvocationWitness source,String draft);
    void confirmPark(CrewManager.Bot bot,CancellationToken token,WaitTicket ticket,CrewCheckpointStore.ConfirmedSnapshot checkpoint);
    void afterParkQuiescent(CrewManager.Bot bot,CancellationToken token,WaitTicket ticket,
            CrewCheckpointStore.ConfirmedSnapshot checkpoint,CrewManager.TreeParkLease lease);
    void requireLinkedResume(CrewManager.Bot bot,CancellationToken token,CrewManager.ResumeCheckpointCommit commit);
    default void requireCompletedUserResume(CrewManager.Bot bot,CancellationToken token,CrewManager.ResumeCheckpointCommit commit) {
        throw new IllegalStateException("Completed tree worker needs exact fresh USER control");
    }
    /** Mailbox-only staging inside the actual registered USER successor commit. */
    default void requireUserSuccessorStaging(CrewManager.Bot bot,CancellationToken token,CrewManager.ResumeCheckpointCommit commit,CrewMessage message) {
        throw new IllegalStateException("Exact registered USER successor required");
    }
    final class WaitTicket {
        public static final String FIELD="workerTreeWaitV1";
        public final String rootId,nodeId,waitId,cycleId,draft;
        public final long revision;
        public final CrewManager.WaitMode mode;
        public final boolean implicit;
        public WaitTicket(String rootId,String nodeId,String waitId,String cycleId,long revision,
                CrewManager.WaitMode mode,boolean implicit,String draft) {
            this.rootId=text(rootId);this.nodeId=text(nodeId);this.waitId=text(waitId);this.cycleId=text(cycleId);
            if(revision<1)throw new IllegalArgumentException("Invalid wait revision");
            this.revision=revision;this.mode=Objects.requireNonNull(mode);this.implicit=implicit;this.draft=Objects.requireNonNull(draft);
            if(implicit&&mode!=CrewManager.WaitMode.ALL)throw new IllegalArgumentException("Implicit wait must be ALL");
        }
        public JSONObject toJson() {
            try { return new JSONObject().put("version",1).put("rootId",rootId).put("nodeId",nodeId).put("waitId",waitId)
                .put("cycleId",cycleId).put("revision",revision).put("mode",mode.name()).put("implicit",implicit).put("draft",draft); }
            catch(org.json.JSONException invalid){throw new IllegalStateException("Invalid typed wait",invalid);}
        }
        private static String text(String value){if(value==null||value.trim().isEmpty())throw new IllegalArgumentException("Missing wait identity");return value;}
    }
}
