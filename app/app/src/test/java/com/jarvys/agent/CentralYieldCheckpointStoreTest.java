package com.jarvys.agent;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Evidence storage tests only: reading a checkpoint does not mint runtime permission. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,shadows=ArtifactOsShadow.class)
public class CentralYieldCheckpointStoreTest {
    @Rule public final ChatTestStore storage=new ChatTestStore();
    private static final String SESSION=com.jarvys.agent.workspaces.WorkspaceIdentity.CENTRAL_SESSION;
    private File root(){File root=storage.newFilesDirectory();storage.localRunStore(root);return root;}
    private JSONObject identity() throws Exception {
        return new JSONObject().put("storageGeneration","generation").put("rootId","root")
            .put("oldCycleId","old-cycle").put("runId","run").put("ownerMessageId","original-user")
            .put("confirmedReceiptDigest",String.join("",Collections.nCopies(64,"a")))
            .put("selectedSubmission",new JSONObject().put("submissionId","submission").put("userMessageId","new-user")
                .put("sourceSha256",String.join("",Collections.nCopies(64,"b"))).put("storageGeneration","generation")
                .put("processId","original-process").put("revision",1));
    }
    private CoreAgentLoop.Checkpoint loop(){
        ConversationTurn turn=new ConversationTurn("user","exact retained \u0000\ud800 token=not-a-real-secret",0)
            .withDurableMessageId("original-user");
        turn.bindSource(new ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,SESSION,"original-user",""));
        return new CoreAgentLoop.Checkpoint(Collections.singletonList(turn),Collections.emptySet(),Collections.emptyMap(),0);
    }
    private File target(File root,String reference){return new File(new StorageGeneration(root).directory("crew-context"),
        CrewCheckpointStore.digest(SESSION.getBytes(StandardCharsets.UTF_8))+"/central-yields/"+reference.substring("central-yield-v1:".length())+".json");}
    @Test public void nativeCentralSnapshotReopensExactlyWithoutCrewScopeOrAuthority() throws Exception {
        File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);CoreAgentLoop.Checkpoint loop=loop();
        CrewCheckpointStore.ConfirmedCentralYieldSnapshot saved=store.saveCentralYieldConfirmed(SESSION,identity(),loop);
        CrewCheckpointStore.ConfirmedCentralYieldSnapshot reopened=new CrewCheckpointStore(root)
            .readCentralYieldConfirmed(SESSION,saved.referenceId(),saved.payloadSha256());
        assertEquals(CentralYieldCheckpointCodec.encodeCanonical(loop),CentralYieldCheckpointCodec.encodeCanonical(reopened.checkpoint()));
        assertEquals(saved.payloadSha256(),reopened.payloadSha256());
        assertTrue(store.list(SESSION).isEmpty());
        assertFalse(reopened.identity().has("botId"));assertFalse(reopened.identity().has("scopeIdentity"));
        reopened.identity().put("runId","mutated-copy");
        assertEquals("run",reopened.identity().getString("runId"));
        assertEquals(saved.referenceId(),store.saveCentralYieldConfirmed(SESSION,identity(),loop).referenceId());
    }
    @Test public void renameBeforeDirectorySyncFailureNeverReturnsConfirmation() throws Exception {
        File root=root();AtomicBoolean fail=new AtomicBoolean(true);
        CrewCheckpointStore store=new CrewCheckpointStore(root,directory->{
            if(directory.getName().equals("central-yields") && fail.get())throw new IOException("injected directory sync failure");
        });
        assertThrows(IllegalStateException.class,()->store.saveCentralYieldConfirmed(SESSION,identity(),loop()));
        File dir=new File(new StorageGeneration(root).directory("crew-context"),CrewCheckpointStore.digest(SESSION.getBytes(StandardCharsets.UTF_8))+"/central-yields");
        File[] committed=dir.listFiles((parent,name)->name.matches("[0-9a-f]{64}\\.json"));
        assertNotNull(committed);assertEquals(1,committed.length);
        String hash=committed[0].getName().replace(".json","");
        assertThrows(IllegalStateException.class,()->store.readCentralYieldConfirmed(SESSION,"central-yield-v1:"+hash,hash));
        fail.set(false);
        assertEquals(hash,store.readCentralYieldConfirmed(SESSION,"central-yield-v1:"+hash,hash).payloadSha256());
    }
    @Test public void corruptionMissingReferenceUnknownVersionAndForeignScopeAreRejected() throws Exception {
        File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);
        CrewCheckpointStore.ConfirmedCentralYieldSnapshot saved=store.saveCentralYieldConfirmed(SESSION,identity(),loop());
        File target=target(root,saved.referenceId());byte[] original=Files.readAllBytes(target.toPath());
        JSONObject changed=new JSONObject(new String(original,StandardCharsets.UTF_8)).put("schemaVersion",2);
        Files.write(target.toPath(),changed.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,()->store.readCentralYieldConfirmed(SESSION,saved.referenceId(),saved.payloadSha256()));
        Files.write(target.toPath(),original);
        assertThrows(IllegalStateException.class,()->store.readCentralYieldConfirmed("chat-other",saved.referenceId(),saved.payloadSha256()));
        assertThrows(IllegalStateException.class,()->store.saveCentralYieldConfirmed("chat-other",identity(),loop()));
        Files.write(target.toPath(),"broken".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,()->store.saveCentralYieldConfirmed(SESSION,identity(),loop()));
        assertEquals("broken",new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));
        assertTrue(target.delete());
        assertThrows(IllegalStateException.class,()->store.readCentralYieldConfirmed(SESSION,saved.referenceId(),saved.payloadSha256()));
    }
    @Test public void incompleteToolsRejectWithoutPublishingAnySnapshot() throws Exception {
        File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);
        CoreAgentLoop.Checkpoint unsafe=new CoreAgentLoop.Checkpoint(Collections.emptyList(),Collections.emptySet(),
            Collections.singletonMap("unknown-call","STARTED"),-1);
        assertThrows(IllegalStateException.class,()->store.saveCentralYieldConfirmed(SESSION,identity(),unsafe));
        File dir=new File(new StorageGeneration(root).directory("crew-context"),CrewCheckpointStore.digest(SESSION.getBytes(StandardCharsets.UTF_8))+"/central-yields");
        assertFalse(dir.exists());
    }
    @Test public void recoveredReadEvidenceCannotRecreateSelectedUserPermission() throws Exception {
        File root=root();CrewCheckpointStore.ConfirmedCentralYieldSnapshot saved=new CrewCheckpointStore(root)
            .saveCentralYieldConfirmed(SESSION,identity(),loop());
        JSONObject selected=identity().getJSONObject("selectedSubmission");
        String certificate=CentralYieldCheckpointCodec.canonical(new JSONObject().put("version",1).put("kind","CENTRAL_YIELD")
            .put("runId","run").put("oldCycleId","old-cycle").put("ownerMessageId","original-user").put("rootId","root")
            .put("checkpointDigest",saved.payloadSha256()).put("checkpointReferenceId",saved.referenceId())
            .put("submissionId","submission").put("submissionUserMessageId","new-user")
            .put("submissionSourceSha256",selected.getString("sourceSha256"))
            .put("storageGeneration","generation").put("submissionRevision",1));
        MainChatTranscriptStore.ConfirmedCentralYield restored=MainChatTranscriptStore.recoveredYield(saved,certificate);
        assertEquals(saved.referenceId(),restored.checkpointReference());
        assertEquals("root",restored.rootId());
        assertThrows(IllegalStateException.class,restored::request);
        assertThrows(IllegalStateException.class,()->MainChatTranscriptStore.recoveredYield(saved,certificate+" "));
    }
    @Test public void symlinkCheckpointCannotBecomeReadConfirmedEvidence() throws Exception {
        File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);
        CrewCheckpointStore.ConfirmedCentralYieldSnapshot saved=store.saveCentralYieldConfirmed(SESSION,identity(),loop());
        File target=target(root,saved.referenceId());
        File different=new File(root,"different-evidence.json");
        Files.write(different.toPath(),Files.readAllBytes(target.toPath()));
        assertTrue(target.delete());Files.createSymbolicLink(target.toPath(),different.toPath());
        assertThrows(IllegalStateException.class,()->store.readCentralYieldConfirmed(SESSION,saved.referenceId(),saved.payloadSha256()));
        assertTrue(different.isFile());
    }

    @Test public void foreignSourcesAndUnrelatedNewUserAreNeverPersistedAsOldRootEvidence() throws Exception {
        File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);
        ConversationTurn foreign=new ConversationTurn("user","foreign",0).withDurableMessageId("foreign-user");
        foreign.bindSource(new ConversationTurn.Source(ConversationTurn.Source.Kind.MESSAGE,"other-chat","foreign-user",""));
        CoreAgentLoop.Checkpoint foreignLoop=new CoreAgentLoop.Checkpoint(Collections.singletonList(foreign),Collections.emptySet(),Collections.emptyMap(),0);
        assertThrows(IllegalStateException.class,()->store.saveCentralYieldConfirmed(SESSION,identity(),foreignLoop));
        ConversationTurn unrelated=new ConversationTurn("user","new turn",0).withDurableMessageId("new-user");
        CoreAgentLoop.Checkpoint leaked=new CoreAgentLoop.Checkpoint(Collections.singletonList(unrelated),Collections.emptySet(),Collections.emptyMap(),0);
        assertThrows(IllegalStateException.class,()->store.saveCentralYieldConfirmed(SESSION,identity(),leaked));
    }

    @Test public void generationMismatchAndReplacementRootCannotReuseOldEvidenceReference() throws Exception {
        File original=root();CrewCheckpointStore store=new CrewCheckpointStore(original);
        CrewCheckpointStore.ConfirmedCentralYieldSnapshot saved=store.saveCentralYieldConfirmed(SESSION,identity(),loop());
        assertThrows(IllegalStateException.class,()->store.readCentralYieldConfirmed(SESSION,saved.referenceId(),saved.payloadSha256(),"replacement-generation"));
        File replacement=root();CrewCheckpointStore reopened=new CrewCheckpointStore(replacement);
        assertThrows(IllegalStateException.class,()->reopened.readCentralYieldConfirmed(SESSION,saved.referenceId(),saved.payloadSha256(),"generation"));
        assertEquals(saved.payloadSha256(),store.readCentralYieldConfirmed(SESSION,saved.referenceId(),saved.payloadSha256(),"generation").payloadSha256());
    }
    @Test public void namespaceScanReportsUnknownObjectsAndSymlinksInsteadOfHidingThem() throws Exception {
        File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);
        CrewCheckpointStore.ConfirmedCentralYieldSnapshot saved=store.saveCentralYieldConfirmed(SESSION,identity(),loop());
        File dir=target(root,saved.referenceId()).getParentFile();
        File unknown=new File(dir,"unknown-object");Files.write(unknown.toPath(),new byte[]{1});
        assertFalse(store.listIssues(SESSION).isEmpty());
        assertTrue(unknown.delete());
        File link=new File(dir,"linked-object");Files.createSymbolicLink(link.toPath(),target(root,saved.referenceId()).toPath());
        assertFalse(store.listIssues(SESSION).isEmpty());
        assertTrue(target(root,saved.referenceId()).isFile());
    }

    @Test public void corruptReadbackAfterRenameNeverReturnsConfirmedEvidence() throws Exception {
        File root=root();AtomicBoolean corruptOnce=new AtomicBoolean(true);
        CrewCheckpointStore store=new CrewCheckpointStore(root,directory->{
            if(directory.getName().equals("central-yields") && corruptOnce.compareAndSet(true,false)) {
                File[] committed=directory.listFiles((parent,name)->name.matches("[0-9a-f]{64}\\.json"));
                assertNotNull(committed);assertEquals(1,committed.length);
                Files.write(committed[0].toPath(),"corrupt-before-readback".getBytes(StandardCharsets.UTF_8));
            }
        });
        assertThrows(IllegalStateException.class,()->store.saveCentralYieldConfirmed(SESSION,identity(),loop()));
        assertFalse(corruptOnce.get());
        assertFalse(store.listIssues(SESSION).isEmpty());
        File directory=new File(new StorageGeneration(root).directory("crew-context"),
            CrewCheckpointStore.digest(SESSION.getBytes(StandardCharsets.UTF_8))+"/central-yields");
        File[] committed=directory.listFiles((parent,name)->name.matches("[0-9a-f]{64}\\.json"));
        assertNotNull(committed);assertEquals(1,committed.length);
        assertEquals("corrupt-before-readback",new String(Files.readAllBytes(committed[0].toPath()),StandardCharsets.UTF_8));
    }

    @Test public void emptyPartialAndFullyWrittenPendingCrashResiduesNeverBecomeConfirmedEvidence() throws Exception {
        // Simulate on-disk crash states, not an injected JVM failure inside write/fsync/rename.
        // No authority is inferred from even a complete valid envelope without the committed path.
        for(int residue=0;residue<3;residue++) {
            File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);
            CrewCheckpointStore.ConfirmedCentralYieldSnapshot saved=store.saveCentralYieldConfirmed(SESSION,identity(),loop());
            File committed=target(root,saved.referenceId());byte[] bytes=Files.readAllBytes(committed.toPath());
            assertTrue(committed.delete());
            File pending=new File(committed.getParentFile(),"central-yield-pending-simulated.json");
            Files.write(pending.toPath(),residue==0?new byte[0]:residue==1?Arrays.copyOf(bytes,bytes.length/2):bytes);
            byte[] original=Files.readAllBytes(pending.toPath());
            CrewCheckpointStore reopened=new CrewCheckpointStore(root);
            assertThrows(IllegalStateException.class,()->reopened.readCentralYieldConfirmed(SESSION,saved.referenceId(),saved.payloadSha256()));
            assertFalse(reopened.listIssues(SESSION).isEmpty());
            assertArrayEquals(original,Files.readAllBytes(pending.toPath()));
            assertFalse(committed.exists());
        }
    }

    @Test public void invalidNamespaceFailsBeforeAnyCheckpointFileWrite() throws Exception {
        File root=root();CrewCheckpointStore store=new CrewCheckpointStore(root);
        File directory=new File(new StorageGeneration(root).directory("crew-context"),
            CrewCheckpointStore.digest(SESSION.getBytes(StandardCharsets.UTF_8))+"/central-yields");
        assertTrue(directory.getParentFile().mkdirs());
        Files.write(directory.toPath(),"not-a-directory".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,()->store.saveCentralYieldConfirmed(SESSION,identity(),loop()));
        assertEquals("not-a-directory",new String(Files.readAllBytes(directory.toPath()),StandardCharsets.UTF_8));
    }

    @Test public void injectedWriteSyncAndRenameBoundariesLeaveOnlyUnconfirmedOrphans() throws Exception {
        for(CrewCheckpointStore.CentralYieldWriteStage failing:CrewCheckpointStore.CentralYieldWriteStage.values()) {
            File root=root();java.util.concurrent.atomic.AtomicReference<File> retained=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<File> targetPath=new java.util.concurrent.atomic.AtomicReference<>();
            CrewCheckpointStore store=new CrewCheckpointStore(root,directory->{},null,(stage,pending,target)->{
                if(stage==failing) {
                    assertTrue(Thread.holdsLock(ConversationMetadataAccess.lockForSession(SESSION)));
                    assertTrue(pending.isFile());assertFalse(target.exists());
                    if(stage==CrewCheckpointStore.CentralYieldWriteStage.BEFORE_WRITE)assertEquals(0L,pending.length());
                    else assertTrue(pending.length()>0L);
                    retained.set(pending);targetPath.set(target);
                    throw new IOException("injected native checkpoint stage "+stage);
                }
            });
            assertThrows(IllegalStateException.class,()->store.saveCentralYieldConfirmed(SESSION,identity(),loop()));
            assertNotNull(retained.get());assertNotNull(targetPath.get());
            byte[] original=Files.readAllBytes(retained.get().toPath());
            String hash=targetPath.get().getName().replace(".json","");
            CrewCheckpointStore reopened=new CrewCheckpointStore(root);
            assertThrows(IllegalStateException.class,()->reopened.readCentralYieldConfirmed(SESSION,"central-yield-v1:"+hash,hash));
            assertFalse(reopened.listIssues(SESSION).isEmpty());
            assertArrayEquals(original,Files.readAllBytes(retained.get().toPath()));
            assertFalse(targetPath.get().exists());
        }
    }

}
