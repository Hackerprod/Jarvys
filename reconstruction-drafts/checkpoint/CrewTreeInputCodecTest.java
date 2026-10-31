package com.jarvys.agent;
import static org.junit.Assert.*;
import com.jarvys.agent.crew.*;
import java.io.File;
import java.util.Collections;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,shadows=ArtifactOsShadow.class)
public class CrewTreeInputCodecTest {
 @Rule public final ChatTestStore storage=new ChatTestStore();
 private final String cycle=UUID.randomUUID().toString(),hash=String.join("",Collections.nCopies(64,"a"));
 private CrewInputBinding binding(boolean report){return report?
  CrewInputBinding.treeReport("source","central","workspace","session","assignment","mission","parent","mailbox",hash,cycle,"child",CrewMessage.Type.RESULT,"exact \u0000\ud800"):
  CrewInputBinding.create(CrewInputBinding.SourceKind.TREE_ANSWER,"source","central","workspace","session","assignment","mission","parent","mailbox",hash,cycle,"exact \u0000\ud800");}
 private CrewInputIncorporation proof(boolean report){return new CrewInputIncorporation(binding(report),cycle,CrewInputIncorporation.TransportKind.INBOX,CrewInputIncorporation.transportSha256("exact \u0000\ud800"));}
 private CoreAgentLoop.Checkpoint loop(CrewInputIncorporation p){return new CoreAgentLoop.Checkpoint(Collections.emptyList(),Collections.singleton("mailbox"),Collections.emptyMap(),-1,Collections.singletonMap("mailbox",p));}
 @Test public void bothKindsRoundTripDiskReopenAndOrdinaryReplacementWithoutSchemaChange()throws Exception {
  for(boolean report:new boolean[]{true,false}) {
   File root=storage.newFilesDirectory();storage.localRunStore(root);CrewCheckpointStore store=new CrewCheckpointStore(root);
   CrewInputIncorporation p=proof(report);JSONObject metadata=new JSONObject().put("missionId","mission");
   store.saveConfirmed("session","parent",metadata,"scope",loop(p),new JSONObject());
   CrewCheckpointStore.ConfirmedSnapshot saved=new CrewCheckpointStore(root).confirmDurable("session","parent","scope");
   assertTrue(p.samePayload(saved.loopForEvidence().incorporations.get("mailbox")));
   store.saveConfirmed("session","parent",metadata,"scope",saved.loopForEvidence(),new JSONObject());
   assertTrue(p.samePayload(store.confirmDurable("session","parent","scope").loopForEvidence().incorporations.get("mailbox")));
   assertEquals(1,CrewCheckpointStore.SCHEMA_VERSION);
  }
 }
 @Test public void invalidTransportUnknownKindAndMissingOldProofFailClosed()throws Exception {
  for(boolean report:new boolean[]{true,false}) {
   CrewInputBinding binding=binding(report);
   for(CrewInputIncorporation.TransportKind kind:new CrewInputIncorporation.TransportKind[]{CrewInputIncorporation.TransportKind.USER,CrewInputIncorporation.TransportKind.ASK_CHIEF})
    assertThrows(IllegalArgumentException.class,()->new CrewInputIncorporation(binding,cycle,kind,hash));
   JSONObject encoded=CrewCheckpointStore.encodeLoop(loop(proof(report)));
   encoded.getJSONArray("incorporations").getJSONObject(0).getJSONObject("binding").put("sourceKind","UNKNOWN_TREE");
   assertThrows(Exception.class,()->CrewCheckpointStore.decodeLoop(encoded));
   JSONObject old=CrewCheckpointStore.encodeLoop(loop(proof(report)));old.remove("incorporations");
   assertTrue(CrewCheckpointStore.decodeLoop(old).incorporations.isEmpty());
   assertTrue(CrewCheckpointStore.decodeLoop(old).appliedIncomingIds.contains("mailbox"));
  }
 }
 @Test public void reportCannotBecomeChiefOrChangeSenderAndLosslessTextHashesDiffer() {
  CrewInputBinding binding=binding(true);
  CrewMessage message=new CrewMessage("mailbox","session","child","parent",CrewMessage.Type.RESULT,"exact \u0000\ud800",Collections.emptyList(),1);
  assertTrue(binding.matchesDelivery(message));
  assertFalse(binding.matchesDelivery(new CrewMessage("mailbox","session","other-child","parent",CrewMessage.Type.RESULT,message.text,Collections.emptyList(),1)));
  assertFalse(binding.matchesDelivery(new CrewMessage("mailbox","session","chief","parent",CrewMessage.Type.ANSWER,message.text,Collections.emptyList(),1)));
  assertThrows(IllegalArgumentException.class,()->CrewInputBinding.treeReport("source","central","workspace","session","assignment","mission","parent","mailbox",hash,cycle,"chief",CrewMessage.Type.RESULT,"text"));
  assertNotEquals(CrewInputIncorporation.transportSha256("\ud800"),CrewInputIncorporation.transportSha256("\ud801"));
 }
}
