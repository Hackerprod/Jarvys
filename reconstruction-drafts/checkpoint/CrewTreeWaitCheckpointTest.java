package com.jarvys.agent;
import static org.junit.Assert.*;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Fresh reconstruction proof candidates: not execution capabilities or old validation results. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,shadows=ArtifactOsShadow.class)
public class CrewTreeWaitCheckpointTest {
 @Rule public final ChatTestStore storage=new ChatTestStore();
 private final String workspace=UUID.randomUUID().toString(), assignment=UUID.randomUUID().toString();
 private final String session=WorkspaceIdentity.executionSessionId(workspace),bot="bot-one",cycle=UUID.randomUUID().toString();
 private String scope(){return "native-workspace:"+workspace;}
 private File root(){File f=storage.newFilesDirectory();storage.localRunStore(f);return f;}
 private JSONObject metadata()throws Exception{return new JSONObject().put("conversationId",session).put("botId",bot).put("assignmentId",assignment).put("missionId","mission")
  .put("cycleId",cycle).put("nativeWorkspaceCheckpoint",true).put("role",new JSONObject().put("id","generalist").put("version",1)).put("missionAccess","STANDARD");}
 private JSONObject ticket(String id)throws Exception{return new JSONObject().put("version",1).put("rootId","root").put("nodeId","node").put("waitId",id).put("cycleId",cycle)
  .put("revision",1).put("mode","ALL").put("implicit",true).put("draft","exact draft \u0000\ud800");}
 private CrewCheckpointStore.ConfirmedSnapshot save(CrewCheckpointStore s)throws Exception{return s.saveConfirmed(session,bot,metadata(),scope(),CoreAgentLoop.Checkpoint.empty(),new JSONObject());}
 private CrewCheckpointStore.ConfirmedSnapshot wait(CrewCheckpointStore s,CrewCheckpointStore.ConfirmedSnapshot old,JSONObject t)throws Exception {
  return s.saveWorkerTreeWaitConfirmed(old,metadata(),scope(),old.loopForEvidence(),new JSONObject(),t);
 }
 @Test public void ordinaryReplacementAndReopenConserveExactHistoryAndP1()throws Exception {
  File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);CrewCheckpointStore.ConfirmedSnapshot old=save(store);
  old=store.createCentralContinuation(old,CentralContinuationRecord.create(workspace,assignment,"request","mission",bot,cycle));
  JSONObject p1=CrewCheckpointStore.continuation(old).toJson();JSONObject first=ticket("one");
  CrewCheckpointStore.ConfirmedSnapshot saved=wait(store,old,first);
  saved=wait(store,saved,ticket("two"));
  JSONObject wanted=saved.metadataJson().getJSONObject(WorkerTreeWaitRecord.FIELD);
  assertTrue(WorkerTreeWaitRecord.equal(first,wanted.getJSONArray("history").getJSONObject(0)));
  store.saveConfirmed(session,bot,metadata(),scope(),saved.loopForEvidence(),new JSONObject());
  CrewCheckpointStore.ConfirmedSnapshot reopened=new CrewCheckpointStore(root).confirmDurable(session,bot,scope());
  assertTrue(WorkerTreeWaitRecord.equal(wanted,reopened.metadataJson().getJSONObject(WorkerTreeWaitRecord.FIELD)));
  assertTrue(WorkerTreeWaitRecord.equal(p1,CrewCheckpointStore.continuation(reopened).toJson()));
  assertEquals(1,CrewCheckpointStore.SCHEMA_VERSION);
 }
 @Test public void genericWriteCannotIntroduceOrMutateAndExactCasConflictsPreserveSource()throws Exception {
  CrewCheckpointStore store=new CrewCheckpointStore(root());CrewCheckpointStore.ConfirmedSnapshot old=save(store);
  JSONObject bad=metadata().put(WorkerTreeWaitRecord.FIELD,WorkerTreeWaitRecord.create(ticket("one")).toJson());
  assertThrows(IllegalStateException.class,()->store.saveConfirmed(session,bot,bad,scope(),old.loopForEvidence(),new JSONObject()));
  CrewCheckpointStore.ConfirmedSnapshot saved=wait(store,old,ticket("one"));
  assertThrows(IllegalStateException.class,()->wait(store,old,ticket("two")));
  assertThrows(IllegalArgumentException.class,()->wait(store,saved,ticket("one").put("draft","changed")));
  assertThrows(IllegalArgumentException.class,()->wait(store,saved,ticket("two").put("cycleId",UUID.randomUUID().toString())));
  assertEquals(saved.payloadSha256,store.confirmDurable(session,bot,scope()).payloadSha256);
 }
 @Test public void strictCodecRejectsUnknownVersionTypesOwnerReuseAndHistoryLoss()throws Exception {
  WorkerTreeWaitRecord one=WorkerTreeWaitRecord.create(ticket("one"));
  assertThrows(IllegalArgumentException.class,()->WorkerTreeWaitRecord.fromJson(one.toJson().put("version",2)));
  assertThrows(IllegalArgumentException.class,()->WorkerTreeWaitRecord.create(ticket("one").put("revision",1.0)));
  assertThrows(IllegalArgumentException.class,()->WorkerTreeWaitRecord.create(ticket("one").put("extra",true)));
  assertThrows(IllegalArgumentException.class,()->one.withTicket(ticket("two").put("nodeId","other")));
  WorkerTreeWaitRecord two=one.withTicket(ticket("two"));
  assertThrows(IllegalArgumentException.class,()->two.withTicket(ticket("one")));
  assertTrue(WorkerTreeWaitRecord.equal(one.current(),ticket("one").put("revision",1L)));
 }
 @Test public void typedChildWaitPreservesP1AndRequiresExactPreparedClaimBeforeNewCycle()throws Exception {
  File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);CrewCheckpointStore.ConfirmedSnapshot first=save(store);
  first=store.createCentralContinuation(first,CentralContinuationRecord.create(workspace,assignment,"request","mission",bot,cycle));
  CrewCheckpointStore.ConfirmedSnapshot parked=wait(store,first,ticket("wait"));
  String hash=String.join("",java.util.Collections.nCopies(64,"a"));
  TreeSuccessorCertificate certificate=new TreeSuccessorCertificate(parked,"root","node","wait",3,3,4,hash);
  JSONObject generic=CrewCheckpointStore.continuation(parked).toJson();generic.put("revision",generic.getLong("revision")+1);
  generic.getJSONArray("links").put(certificate.preparedLink());
  assertThrows(IllegalArgumentException.class,()->store.updateCentralContinuation(parked,CentralContinuationRecord.fromJson(generic)));
  assertThrows(IllegalArgumentException.class,()->new TreeSuccessorCertificate(parked,"root","node","different",3,3,4,hash));
  CrewCheckpointStore.ConfirmedSnapshot prepared=store.prepareTreeSuccessorConfirmed(certificate);
  String successor=UUID.randomUUID().toString();JSONObject nextMetadata=metadata().put("cycleId",successor);
  CrewCheckpointStore.ConfirmedSnapshot bound=store.saveTreeSuccessorConfirmed(prepared,certificate,nextMetadata,scope(),prepared.loopForEvidence(),new JSONObject());
  assertEquals(successor,CrewCheckpointStore.continuation(bound).expectedCycleId());
  JSONObject record=CrewCheckpointStore.continuation(bound).toJson();assertTrue(record.isNull("selectedResult"));
  assertEquals(0,record.getJSONArray("consumedResults").length());assertEquals("NONE",record.getString("dispatchState"));
  assertEquals("CHILD_WAIT",record.getJSONArray("links").getJSONObject(0).getString("controlType"));
  assertTrue(WorkerTreeWaitRecord.equal(parked.metadataJson().getJSONObject(WorkerTreeWaitRecord.FIELD),bound.metadataJson().getJSONObject(WorkerTreeWaitRecord.FIELD)));
  assertThrows(IllegalStateException.class,()->store.saveTreeSuccessorConfirmed(prepared,certificate,nextMetadata,scope(),prepared.loopForEvidence(),new JSONObject()));
  store.saveConfirmed(session,bot,nextMetadata,scope(),bound.loopForEvidence(),new JSONObject());
  assertEquals(successor,CrewCheckpointStore.continuation(new CrewCheckpointStore(root).confirmDurable(session,bot,scope())).expectedCycleId());
 }
 @Test public void treeOnlyUserSuccessorDoesNotInventP1AndCannotReuseSource()throws Exception {
  CrewCheckpointStore store=new CrewCheckpointStore(root());CrewCheckpointStore.ConfirmedSnapshot source=save(store);
  String hash=String.join("",java.util.Collections.nCopies(64,"a"));
  TreeUserControlCertificate cert=new TreeUserControlCertificate(source,"root","node","control","RESUME",3,4,hash);
  JSONObject next=metadata().put("cycleId",UUID.randomUUID().toString());
  CrewCheckpointStore.ConfirmedSnapshot saved=store.saveTreeUserSuccessorConfirmed(cert,next,scope(),source.loopForEvidence(),new JSONObject());
  assertFalse(saved.metadataJson().has(CentralContinuationRecord.FIELD));
  assertThrows(IllegalStateException.class,()->store.saveTreeUserSuccessorConfirmed(cert,next,scope(),source.loopForEvidence(),new JSONObject()));
 }

 private File target(File root){return new File(new StorageGeneration(root).directory("crew-context"),CrewCheckpointStore.digest(session.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"/"+CrewCheckpointStore.digest(bot.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"/checkpoint.json");}
 @Test public void selectedP1CannotPrepareAnotherProducerOrDiscardConsumption()throws Exception {
  CrewCheckpointStore store=new CrewCheckpointStore(root());CrewCheckpointStore.ConfirmedSnapshot first=save(store);
  first=store.createCentralContinuation(first,CentralContinuationRecord.create(workspace,assignment,"request","mission",bot,cycle));
  CrewCheckpointStore.ConfirmedSnapshot parked=wait(store,first,ticket("wait"));
  String hash=String.join("",java.util.Collections.nCopies(64,"a"));
  JSONObject chosen=CrewCheckpointStore.continuation(parked).toJson();
  JSONObject result=new JSONObject().put("resultReferenceId","reference").put("evidenceId","evidence").put("producerCycleId",cycle).put("contentSha256",hash);
  chosen.put("revision",chosen.getLong("revision")+1).put("selectedResult",result).put("consumedResults",new org.json.JSONArray().put(result)).put("inputPayloadSha256",hash).put("dispatchState","PREPARED");
  CrewCheckpointStore.ConfirmedSnapshot selected=store.updateCentralContinuation(parked,CentralContinuationRecord.fromJson(chosen));
  TreeSuccessorCertificate cert=new TreeSuccessorCertificate(selected,"root","node","wait",3,3,4,hash);
  assertThrows(IllegalStateException.class,()->store.prepareTreeSuccessorConfirmed(cert));
  assertEquals(selected.payloadSha256,store.confirmDurable(session,bot,scope()).payloadSha256);
 }
 @Test public void oversizedWaitDoesNotReplaceReadableSource()throws Exception {
  File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);CrewCheckpointStore.ConfirmedSnapshot first=save(store);
  byte[] before=java.nio.file.Files.readAllBytes(target(root).toPath());
  long budget=CrewCheckpointStore.materializationBudget();assertTrue(budget>0&&budget<64L*1024*1024);
  char[] large=new char[(int)(budget/2)+1];java.util.Arrays.fill(large,'x');JSONObject ticket=ticket("large").put("draft",new String(large));
  assertThrows(IllegalStateException.class,()->wait(store,first,ticket));
  assertArrayEquals(before,java.nio.file.Files.readAllBytes(target(root).toPath()));
  assertEquals(first.payloadSha256,store.confirmDurable(session,bot,scope()).payloadSha256);
 }
 @Test public void postRenameSyncFailureIsUncertainAndStaleRetryCannotRepeatWait()throws Exception {
  File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);CrewCheckpointStore.ConfirmedSnapshot first=save(store);
  java.util.concurrent.atomic.AtomicBoolean fault=new java.util.concurrent.atomic.AtomicBoolean();
  CrewCheckpointStore failing=new CrewCheckpointStore(root,directory->{
   try {
    JSONObject envelope=new JSONObject(new String(java.nio.file.Files.readAllBytes(target(root).toPath()),java.nio.charset.StandardCharsets.UTF_8));
    JSONObject metadata=new JSONObject(envelope.getString("payload")).getJSONObject("metadata");
    if(metadata.has(WorkerTreeWaitRecord.FIELD)&&!fault.getAndSet(true))throw new java.io.IOException("post-rename wait directory sync");
   }catch(org.json.JSONException e){throw new java.io.IOException(e);}
  });
  assertThrows(IllegalStateException.class,()->wait(failing,first,ticket("wait")));
  assertTrue(fault.get());CrewCheckpointStore.ConfirmedSnapshot confirmed=store.confirmDurable(session,bot,scope());
  assertTrue(confirmed.metadataJson().has(WorkerTreeWaitRecord.FIELD));
  assertThrows(IllegalStateException.class,()->wait(store,first,ticket("wait")));
  assertEquals(confirmed.payloadSha256,store.confirmDurable(session,bot,scope()).payloadSha256);
 }

}
