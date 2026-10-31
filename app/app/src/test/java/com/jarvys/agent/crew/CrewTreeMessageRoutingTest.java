package com.jarvys.agent.crew;

import static org.junit.Assert.*;
import com.jarvys.agent.ConversationInput;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Structural transport proof only; none of these DTOs authorizes a native run. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class CrewTreeMessageRoutingTest {
    final String workspace=UUID.randomUUID().toString(), session=WorkspaceIdentity.executionSessionId(workspace);
    final String cycle=UUID.randomUUID().toString(), successor=UUID.randomUUID().toString();
    final String hash=String.join("",Collections.nCopies(64,"a"));
    ConversationInput.Provenance provenance(String assignment,String bot,String id,ConversationInput.Kind kind,
            ConversationInput.Provenance.PrincipalRole role,long revision,String digest,String correlation) {
        return new ConversationInput.Provenance(session,workspace,assignment,bot,id,kind,role,
                correlation,"",digest,revision,"request","mission",cycle);
    }
    CrewMessage task() {
        CrewMessage m=new CrewMessage("task",session,"parent","child",CrewMessage.Type.TASK,"Exact task",Collections.emptyList(),1);
        ConversationInput.Provenance p=provenance("child-assignment","parent",m.id,ConversationInput.Kind.TASK,
                ConversationInput.Provenance.PrincipalRole.PEER,2,"","");
        return m.withInputBinding(CrewInputBinding.treeTask(workspace,session,"child-assignment","mission",
                "child",m.id,successor,"parent",m.text,p)).withInputProvenance(p);
    }
    CrewMessage report() {
        CrewMessage m=new CrewMessage("report",session,"child","parent",CrewMessage.Type.RESULT,"Exact result",Collections.emptyList(),1);
        return m.withInputBinding(CrewInputBinding.treeReport("wait",WorkspaceIdentity.CENTRAL_SESSION,workspace,session,
                "parent-assignment","mission","parent",m.id,hash,successor,"child",m.type,m.text))
            .withInputProvenance(provenance("child-assignment","child",m.id,ConversationInput.Kind.RESULT,
                ConversationInput.Provenance.PrincipalRole.SUBORDINATE,0,hash,"result-evidence"));
    }
    CrewMessage answer() {
        CrewMessage m=new CrewMessage("answer",session,"chief","child",CrewMessage.Type.ANSWER,"Exact answer",Collections.emptyList(),1);
        return m.withInputBinding(CrewInputBinding.create(CrewInputBinding.SourceKind.TREE_ANSWER,"control",WorkspaceIdentity.CENTRAL_SESSION,
                workspace,session,"child-assignment","mission","child",m.id,hash,successor,m.text))
            .withInputProvenance(provenance("child-assignment","child",m.id,ConversationInput.Kind.ANSWER,
                ConversationInput.Provenance.PrincipalRole.PEER,3,hash,"question-evidence"));
    }
    @Test public void typedTreeRoutesRoundTripWithoutSupervisorPromotion() {
        for(CrewMessage m:new CrewMessage[]{task(),report(),answer()}) {
            CrewMessage copy=CrewMessage.fromJson(m.toJson());
            assertTrue(copy.inputBinding.samePayload(m.inputBinding));assertTrue(copy.inputBinding.matchesDelivery(copy));
            assertEquals(m.inputProvenance.toJson().toString(),copy.inputProvenance.toJson().toString());
            assertNotEquals(ConversationInput.Provenance.PrincipalRole.SUPERVISOR,copy.inputProvenance.principalRole);
            new CrewInputIncorporation(copy.inputBinding,successor,CrewInputIncorporation.TransportKind.INBOX,hash);
            assertThrows(IllegalArgumentException.class,()->new CrewInputIncorporation(copy.inputBinding,successor,CrewInputIncorporation.TransportKind.USER,hash));
            assertThrows(IllegalArgumentException.class,()->new CrewInputIncorporation(copy.inputBinding,successor,CrewInputIncorporation.TransportKind.ASK_CHIEF,hash));
        }
    }
    @Test public void changedSenderTargetTypeWorkspaceAndPayloadCannotBorrowBinding() throws Exception {
        for(CrewMessage m:new CrewMessage[]{task(),report(),answer()}) for(String field:new String[]{"from","to","type","conversationId","text"}) {
            JSONObject row=m.toJson();row.put(field,field.equals("type")?"USER":"changed");
            assertThrows(field,IllegalArgumentException.class,()->CrewMessage.fromJson(row));
        }
    }
    @Test public void changedProvenanceCannotAcquireChiefOrCrossOwner() throws Exception {
        for(CrewMessage m:new CrewMessage[]{task(),report(),answer()}) for(String field:new String[]{"botId","workspaceId","sessionId","missionId","messageId","principalRole","cycleId"}) {
            JSONObject row=m.toJson();row.getJSONObject("inputProvenance").put(field,field.equals("principalRole")?"SUPERVISOR":"changed");
            assertThrows(field,IllegalArgumentException.class,()->CrewMessage.fromJson(row));
        }
    }
    @Test public void taskProvenanceFullDigestIncludesParentCycleAndRequest() throws Exception {
        for(String field:new String[]{"cycleId","requestId","assignmentId"}) {
            JSONObject row=task().toJson();row.getJSONObject("inputProvenance").put(field,UUID.randomUUID().toString());
            assertThrows(field,IllegalArgumentException.class,()->CrewMessage.fromJson(row));
        }
    }
    @Test public void unboundPeerTaskAndMismatchedBindingRemainDenied() throws Exception {
        JSONObject task=task().toJson();task.remove("inputBinding");
        assertThrows(IllegalArgumentException.class,()->CrewMessage.fromJson(task));
        JSONObject report=report().toJson();report.getJSONObject("inputBinding").put("sourcePayloadSha256",String.join("",Collections.nCopies(64,"b")));
        assertThrows(IllegalArgumentException.class,()->CrewMessage.fromJson(report));
        JSONObject answer=answer().toJson();answer.getJSONObject("inputBinding").put("admissionCycleId","invalid");
        assertThrows(IllegalArgumentException.class,()->CrewMessage.fromJson(answer));
    }
    @Test public void ordinaryUnenrolledCoordinationRetainsOriginalReadClassification() throws Exception {
        com.jarvys.agent.CoreToolRegistry empty=new com.jarvys.agent.CoreToolRegistry(Collections.emptyList());
        try(CrewManager manager=new CrewManager("legacy-metadata",empty,(bot,crew)->empty,
                (bot,tools,incoming)->new com.jarvys.agent.CoreAgentLoop((history,prompt,specs,token)->
                    new com.jarvys.agent.ModelReply("done",Collections.emptyList()),tools,"Instructions","legacy-metadata"),null)) {
            CrewManager.Bot bot=manager.spawn("custom","Metadata only",java.util.Arrays.asList("ask_chief","report_done","msg_send"),"Worker");
            bot.awaitTermination();
            assertNull(manager.treeSupport(bot));
            for(com.jarvys.agent.CoreTool tool:CrewTools.bot(bot,manager,null)) {
                assertEquals(com.jarvys.agent.CoreTool.EffectClass.READ,tool.effectClass(Collections.singletonMap("type","ANSWER")));
            }
        }
    }

}
