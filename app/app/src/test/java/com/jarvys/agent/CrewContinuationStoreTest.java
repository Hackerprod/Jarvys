package com.jarvys.agent;

import static org.junit.Assert.*;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Pre-write compatibility tests. No production caller enables this format merely by adding these APIs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, shadows=ArtifactOsShadow.class)
public class CrewContinuationStoreTest {
    @Rule public final ChatTestStore storage = new ChatTestStore();
    private final String workspace=UUID.randomUUID().toString(), assignment=UUID.randomUUID().toString();
    private final String session=WorkspaceIdentity.executionSessionId(workspace), bot="bot-one";
    private final String cycle=UUID.randomUUID().toString(), mission="mission-one", request="request-one";
    private String scope() { return "native-workspace:"+workspace; }
    private File root() { File f=storage.newFilesDirectory();storage.localRunStore(f);return f; }
    private JSONObject metadata(String cycleId) throws Exception {
        return new JSONObject().put("conversationId",session).put("botId",bot).put("assignmentId",assignment)
                .put("missionId",mission).put("cycleId",cycleId).put("nativeWorkspaceCheckpoint",true)
                .put("role",new JSONObject().put("id","analyst").put("version",1)).put("missionAccess","STANDARD");
    }
    private CrewCheckpointStore.ConfirmedSnapshot save(CrewCheckpointStore store) throws Exception {
        return store.saveConfirmed(session,bot,metadata(cycle),scope(),CoreAgentLoop.Checkpoint.empty(),new JSONObject());
    }
    private CentralContinuationRecord initial() { return CentralContinuationRecord.create(workspace,assignment,request,mission,bot,cycle); }
    private CrewCheckpointStore.ConfirmedSnapshot create(CrewCheckpointStore store) throws Exception {
        return store.createCentralContinuation(save(store),initial());
    }
    private JSONObject next(CrewCheckpointStore.ConfirmedSnapshot saved) throws Exception {
        JSONObject v=CrewCheckpointStore.continuation(saved).toJson();return v.put("revision",v.getLong("revision")+1);
    }
    private JSONObject selected(JSONObject v) throws Exception {
        JSONObject result=new JSONObject().put("resultReferenceId","result-ref-one").put("evidenceId","result-one")
                .put("producerCycleId",v.getString("expectedCycleId")).put("contentSha256",hash("result"));
        return v.put("selectedResult",result).put("consumedResults",new JSONArray().put(result))
                .put("inputPayloadSha256",hash("input-reference")).put("dispatchState","PREPARED");
    }
    private JSONObject prepared(CrewCheckpointStore.ConfirmedSnapshot source) throws Exception {
        return CrewCheckpointStore.continuationSourceCertificate(source).put("controlId","control-one").put("controlType","RESUME")
                .put("observedAssignmentRevision",3).put("admittedAssignmentRevision",0)
                .put("oldCycleId",cycle).put("newCycleId","").put("state","PREPARED").put("safePoint",true);
    }
    private static String hash(String text) { return CrewCheckpointStore.digest(text.getBytes(StandardCharsets.UTF_8)); }
    private File target(File root) { return new File(root,"jarvys/"+StorageGeneration.NAME+"/crew-context/"+hash(session)+"/"+hash(bot)+"/checkpoint.json"); }

    private static String read(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }

    @Test public void oversizedContinuationCasPreservesOriginalConfirmedBytes() throws Exception {
        File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);CrewCheckpointStore.ConfirmedSnapshot initial=create(store);
        byte[] before=Files.readAllBytes(target(root).toPath());
        // The existing write budget is headroom/32. One UTF-16 field just over half the
        // measured byte budget must fail its estimatedSize check before atomic replacement.
        long budget=CrewCheckpointStore.materializationBudget();
        assertTrue("Host test needs a bounded materialization budget",budget>0 && budget<64L*1024*1024);
        char[] chars=new char[(int)(budget/2)+1];java.util.Arrays.fill(chars,'x');
        JSONObject value=next(initial).put("retainedTestEvidence",new String(chars));
        CentralContinuationRecord tooLarge=CentralContinuationRecord.fromJson(value);
        assertThrows(IllegalStateException.class,()->store.updateCentralContinuation(initial,tooLarge));
        assertArrayEquals(before,Files.readAllBytes(target(root).toPath()));
        assertEquals(initial.payloadSha256,store.confirmDurable(session,bot,scope()).payloadSha256);
    }

    @Test public void legacyV1ReadsWithoutInventingCoordinationAndOrdinaryWritesCannotCreateIt() throws Exception {
        File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);CrewCheckpointStore.ConfirmedSnapshot before=save(store);
        assertFalse(new CrewCheckpointStore(root).confirmDurable(session,bot,scope()).metadataJson().has(CentralContinuationRecord.FIELD));
        JSONObject injected=metadata(cycle).put(CentralContinuationRecord.FIELD,initial().toJson());
        assertThrows(IllegalStateException.class,()->store.saveConfirmed(session,bot,injected,scope(),CoreAgentLoop.Checkpoint.empty(),new JSONObject()));
        assertEquals(before.payloadSha256,store.confirmDurable(session,bot,scope()).payloadSha256);
        assertEquals(1,new JSONObject(read(target(root))).getInt("schemaVersion"));
    }

    @Test public void createReopenAndNormalRuntimeSavePreserveAuthoritativeBlock() throws Exception {
        File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);CrewCheckpointStore.ConfirmedSnapshot first=create(store);
        JSONObject expected=CrewCheckpointStore.continuation(first).toJson();
        CrewCheckpointStore.ConfirmedSnapshot updated=store.saveConfirmed(session,bot,metadata(cycle).put("status","WAITING"),scope(),CoreAgentLoop.Checkpoint.empty(),new JSONObject());
        assertTrue(CentralContinuationRecord.equal(expected,CrewCheckpointStore.continuation(updated).toJson()));
        assertTrue(CentralContinuationRecord.equal(expected,CrewCheckpointStore.continuation(new CrewCheckpointStore(root).confirmDurable(session,bot,scope())).toJson()));
        assertArrayEquals(new String[]{"checkpoint.json"},target(root).getParentFile().list());
    }

    @Test public void staleHashAndRevisionCannotOverwriteConcurrentSelection() throws Exception {
        CrewCheckpointStore store=new CrewCheckpointStore(root());CrewCheckpointStore.ConfirmedSnapshot first=create(store);
        CrewCheckpointStore.ConfirmedSnapshot chosen=store.updateCentralContinuation(first,CentralContinuationRecord.fromJson(selected(next(first))));
        assertThrows(IllegalStateException.class,()->store.updateCentralContinuation(first,CentralContinuationRecord.fromJson(next(first).put("dispatchState","CANCELLED"))));
        assertThrows(IllegalArgumentException.class,()->store.updateCentralContinuation(chosen,CentralContinuationRecord.fromJson(next(chosen).put("revision",99))));
        assertEquals(chosen.payloadSha256,store.confirmDurable(session,bot,scope()).payloadSha256);
    }

    @Test public void ordinarySaveCannotSubmitStaleBlockOrChangeCycle() throws Exception {
        CrewCheckpointStore store=new CrewCheckpointStore(root());CrewCheckpointStore.ConfirmedSnapshot first=create(store);
        CrewCheckpointStore.ConfirmedSnapshot selected=store.updateCentralContinuation(first,CentralContinuationRecord.fromJson(selected(next(first))));
        assertThrows(IllegalStateException.class,()->store.saveConfirmed(session,bot,first.metadataJson(),scope(),first.loop,new JSONObject()));
        assertThrows(IllegalStateException.class,()->store.saveConfirmed(session,bot,metadata(UUID.randomUUID().toString()),scope(),first.loop,new JSONObject()));
        assertEquals(selected.payloadSha256,store.confirmDurable(session,bot,scope()).payloadSha256);
    }

    @Test public void claimCannotResetOrChangeOwnerAndConsumptionRemainsImmutable() throws Exception {
        CrewCheckpointStore store=new CrewCheckpointStore(root());CrewCheckpointStore.ConfirmedSnapshot created=create(store);
        CrewCheckpointStore.ConfirmedSnapshot selected=store.updateCentralContinuation(created,CentralContinuationRecord.fromJson(selected(next(created))));
        JSONObject claim=next(selected).put("dispatchState","STARTED").put("dispatchOwnerProcessId","process-one")
                .put("dispatchGenerationId","generation-one").put("assistantMessageStableId","assistant-one").put("generationRunId","run-one");
        CrewCheckpointStore.ConfirmedSnapshot started=store.updateCentralContinuation(selected,CentralContinuationRecord.fromJson(claim));
        assertThrows(IllegalArgumentException.class,()->store.updateCentralContinuation(started,CentralContinuationRecord.fromJson(next(started).put("dispatchOwnerProcessId","process-two"))));
        JSONObject replay=next(started).put("dispatchState","PREPARED").put("dispatchOwnerProcessId","")
                .put("dispatchGenerationId","").put("assistantMessageStableId","").put("generationRunId","");
        assertThrows(IllegalArgumentException.class,()->store.updateCentralContinuation(started,CentralContinuationRecord.fromJson(replay)));
        JSONObject changed=next(started);changed.getJSONObject("selectedResult").put("evidenceId","other");changed.put("consumedResults",new JSONArray().put(changed.getJSONObject("selectedResult")));
        assertThrows(IllegalArgumentException.class,()->store.updateCentralContinuation(started,CentralContinuationRecord.fromJson(changed)));
        CrewCheckpointStore.ConfirmedSnapshot acknowledged=store.updateCentralContinuation(started,CentralContinuationRecord.fromJson(next(started).put("dispatchState","ACKNOWLEDGED")));
        assertEquals("ACKNOWLEDGED",CrewCheckpointStore.continuation(acknowledged).dispatchState());
    }

    @Test public void sourceCertificateSurvivesAtomicSuccessorAndOrdinaryReplacement() throws Exception {
        File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);
        // The retained certificate must not depend on the old file.
        CrewCheckpointStore.ConfirmedSnapshot initial=create(store);JSONObject link=prepared(initial);
        JSONObject pre=next(initial);pre.getJSONArray("links").put(link);
        CrewCheckpointStore.ConfirmedSnapshot pending=store.updateCentralContinuation(initial,CentralContinuationRecord.fromJson(pre));
        String newCycle=UUID.randomUUID().toString();JSONObject bound=next(pending);
        bound.getJSONArray("links").getJSONObject(0).put("state","BOUND").put("newCycleId",newCycle).put("admittedAssignmentRevision",4);
        bound.put("expectedCycleId",newCycle);
        assertThrows(IllegalArgumentException.class,()->store.updateCentralContinuation(pending,CentralContinuationRecord.fromJson(bound)));
        CrewCheckpointStore.ConfirmedSnapshot successor=store.saveCentralContinuationSuccessor(pending,CentralContinuationRecord.fromJson(bound),metadata(newCycle),scope(),pending.loop,new JSONObject());
        assertEquals(initial.payloadSha256,CrewCheckpointStore.continuation(successor).toJson().getJSONArray("links").getJSONObject(0).getString("originCheckpointSha256"));
        CrewCheckpointStore.ConfirmedSnapshot saved=store.saveConfirmed(session,bot,metadata(newCycle).put("status","DONE"),scope(),successor.loop,new JSONObject());
        assertTrue(CentralContinuationRecord.equal(CrewCheckpointStore.continuation(successor).toJson(),CrewCheckpointStore.continuation(saved).toJson()));
    }

    @Test public void forgedSourceCertificateAndUnlinkedCycleAreRejectedWithoutWrite() throws Exception {
        CrewCheckpointStore store=new CrewCheckpointStore(root());CrewCheckpointStore.ConfirmedSnapshot initial=create(store);
        JSONObject forged=next(initial);forged.getJSONArray("links").put(prepared(initial).put("originCheckpointSha256",hash("not the checkpoint")));
        assertThrows(IllegalArgumentException.class,()->store.updateCentralContinuation(initial,CentralContinuationRecord.fromJson(forged)));
        assertThrows(IllegalArgumentException.class,()->CentralContinuationRecord.fromJson(next(initial).put("expectedCycleId",UUID.randomUUID().toString())));
        assertEquals(initial.payloadSha256,store.confirmDurable(session,bot,scope()).payloadSha256);
    }

    @Test public void failedSyncAfterRenameNeedsConfirmationAndStaleRetryCannotRepeatTransition() throws Exception {
        File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);CrewCheckpointStore.ConfirmedSnapshot initial=create(store);
        // Trigger only the write sync, not the CAS confirmation sync.
        AtomicBoolean armed=new AtomicBoolean(false);
        CrewCheckpointStore writeFault=new CrewCheckpointStore(root,directory->{
            try {
                JSONObject envelope=new JSONObject(read(target(root)));
                JSONObject payload=new JSONObject(envelope.getString("payload"));
                if(payload.getJSONObject("metadata").getJSONObject(CentralContinuationRecord.FIELD).getLong("revision")==2 && !armed.getAndSet(true))throw new IOException("post-rename");
            } catch(org.json.JSONException invalid) {throw new IOException(invalid);}
        });
        CentralContinuationRecord next=CentralContinuationRecord.fromJson(selected(next(initial)));
        assertThrows(IllegalStateException.class,()->writeFault.updateCentralContinuation(initial,next));
        CrewCheckpointStore.ConfirmedSnapshot confirmed=store.confirmDurable(session,bot,scope());
        assertEquals(2,CrewCheckpointStore.continuation(confirmed).revision());
        assertThrows(IllegalStateException.class,()->store.updateCentralContinuation(initial,next));
        assertEquals(confirmed.payloadSha256,store.confirmDurable(session,bot,scope()).payloadSha256);
    }

    @Test public void unknownVersionAndCorruptRecordBlockReadAndOverwriteWithoutDeletingBytes() throws Exception {
        File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);create(store);
        File target=target(root);JSONObject envelope=new JSONObject(read(target));JSONObject payload=new JSONObject(envelope.getString("payload"));
        payload.getJSONObject("metadata").getJSONObject(CentralContinuationRecord.FIELD).put("version",2);
        String encoded=payload.toString();String poisoned=new JSONObject().put("schemaVersion",1).put("payload",encoded).put("sha256",hash(encoded)).toString();
        Files.write(target.toPath(),poisoned.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,()->store.load(session,bot));
        assertThrows(IllegalStateException.class,()->save(store));
        assertEquals(poisoned,read(target));
        Files.write(target.toPath(),"incomplete".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,()->store.confirmDurable(session,bot,scope()));
        assertEquals("incomplete",read(target));
    }

    @Test public void malformedIntegerCrossOwnerAndMissingConsumptionFailClosed() throws Exception {
        assertThrows(IllegalArgumentException.class,()->CentralContinuationRecord.fromJson(initial().toJson().put("revision",1.25)));
        assertThrows(IllegalArgumentException.class,()->CentralContinuationRecord.fromJson(initial().toJson().put("ownerCentralSessionId","someone-else")));
        assertThrows(IllegalArgumentException.class,()->CentralContinuationRecord.fromJson(selected(initial().toJson()).put("consumedResults",new JSONArray())));
        CrewCheckpointStore store=new CrewCheckpointStore(root());CrewCheckpointStore.ConfirmedSnapshot source=save(store);
        CentralContinuationRecord other=CentralContinuationRecord.create(workspace,assignment,request,mission,"other-bot",cycle);
        assertThrows(IllegalArgumentException.class,()->store.createCentralContinuation(source,other));
        assertEquals(source.payloadSha256,store.confirmDurable(session,bot,scope()).payloadSha256);
    }
    private JSONObject admission(String call) throws Exception {
        return new JSONObject().put("userMessageId", "user-message").put("sourceBatchId", "batch")
                .put("sourceCallId", call).put("sourceRunId", "source-run")
                .put("sourceProcessId", "source-process").put("storageGeneration", "storage-generation");
    }

    @Test public void centralAdmissionsAppendOnlyAndSurviveOrdinarySaveAndClaim() throws Exception {
        CrewCheckpointStore store=new CrewCheckpointStore(root());
        CrewCheckpointStore.ConfirmedSnapshot created=create(store);
        JSONObject premature=next(created);premature.getJSONArray("centralAdmissions").put(admission("call-one"));
        assertThrows(IllegalArgumentException.class,()->CentralContinuationRecord.fromJson(premature));
        CrewCheckpointStore.ConfirmedSnapshot prepared=store.updateCentralContinuation(created,CentralContinuationRecord.fromJson(selected(next(created))));
        JSONObject one=next(prepared);one.getJSONArray("centralAdmissions").put(admission("call-one"));
        CrewCheckpointStore.ConfirmedSnapshot admitted=store.updateCentralContinuation(prepared,CentralContinuationRecord.fromJson(one));
        JSONObject two=next(admitted);two.getJSONArray("centralAdmissions").put(admission("call-two"));
        CrewCheckpointStore.ConfirmedSnapshot readmitted=store.updateCentralContinuation(admitted,CentralContinuationRecord.fromJson(two));
        CrewCheckpointStore.ConfirmedSnapshot ordinary=save(store);
        assertTrue(CentralContinuationRecord.equal(CrewCheckpointStore.continuation(readmitted).toJson().getJSONArray("centralAdmissions"),
                CrewCheckpointStore.continuation(ordinary).toJson().getJSONArray("centralAdmissions")));
        JSONObject altered=next(ordinary);altered.getJSONArray("centralAdmissions").getJSONObject(0).put("userMessageId","forged-user");
        assertThrows(IllegalArgumentException.class,()->store.updateCentralContinuation(ordinary,CentralContinuationRecord.fromJson(altered)));
        JSONObject removed=next(ordinary).put("centralAdmissions",new JSONArray());
        assertThrows(IllegalArgumentException.class,()->store.updateCentralContinuation(ordinary,CentralContinuationRecord.fromJson(removed)));
        JSONObject claim=next(ordinary).put("dispatchState","STARTED").put("dispatchOwnerProcessId","process")
                .put("dispatchGenerationId","generation").put("generationRunId","run").put("assistantMessageStableId","assistant");
        CrewCheckpointStore.ConfirmedSnapshot started=store.updateCentralContinuation(ordinary,CentralContinuationRecord.fromJson(claim));
        JSONObject late=next(started);late.getJSONArray("centralAdmissions").put(admission("late-call"));
        assertThrows(IllegalArgumentException.class,()->store.updateCentralContinuation(started,CentralContinuationRecord.fromJson(late)));
        assertEquals(started.payloadSha256,store.confirmDurable(session,bot,scope()).payloadSha256);
        CrewCheckpointStore.ConfirmedSnapshot ack=store.updateCentralContinuation(started,CentralContinuationRecord.fromJson(next(started).put("dispatchState","ACKNOWLEDGED")));
        JSONObject afterAck=next(ack);afterAck.getJSONArray("centralAdmissions").put(admission("after-ack"));
        assertThrows(IllegalArgumentException.class,()->store.updateCentralContinuation(ack,CentralContinuationRecord.fromJson(afterAck)));
    }

    @Test public void centralAdmissionRequiresExactSourceAndCannotDuplicateOrBatchOrBundleClaim() throws Exception {
        JSONObject base=selected(initial().toJson());
        JSONObject duplicate=new JSONObject(base.toString());duplicate.getJSONArray("centralAdmissions").put(admission("same")).put(admission("same"));
        assertThrows(IllegalArgumentException.class,()->CentralContinuationRecord.fromJson(duplicate));
        JSONObject missing=new JSONObject(base.toString());JSONObject invalid=admission("missing");invalid.remove("storageGeneration");missing.getJSONArray("centralAdmissions").put(invalid);
        assertThrows(IllegalArgumentException.class,()->CentralContinuationRecord.fromJson(missing));
        CentralContinuationRecord before=CentralContinuationRecord.fromJson(base);
        JSONObject batch=new JSONObject(base.toString()).put("revision",before.revision()+1);batch.getJSONArray("centralAdmissions").put(admission("one")).put(admission("two"));
        assertThrows(IllegalArgumentException.class,()->before.requireSuccessor(CentralContinuationRecord.fromJson(batch)));
        JSONObject bundled=new JSONObject(base.toString()).put("revision",before.revision()+1).put("dispatchState","STARTED")
                .put("dispatchOwnerProcessId","process").put("dispatchGenerationId","generation").put("generationRunId","run").put("assistantMessageStableId","assistant");
        bundled.getJSONArray("centralAdmissions").put(admission("one"));
        assertThrows(IllegalArgumentException.class,()->before.requireSuccessor(CentralContinuationRecord.fromJson(bundled)));
        JSONObject cancelled=new JSONObject(base.toString()).put("revision",before.revision()+1).put("dispatchState","CANCELLED");
        CentralContinuationRecord stopped=CentralContinuationRecord.fromJson(cancelled);
        JSONObject afterStop=new JSONObject(cancelled.toString()).put("revision",stopped.revision()+1);afterStop.getJSONArray("centralAdmissions").put(admission("after-stop"));
        assertThrows(IllegalArgumentException.class,()->stopped.requireSuccessor(CentralContinuationRecord.fromJson(afterStop)));
    }

}
