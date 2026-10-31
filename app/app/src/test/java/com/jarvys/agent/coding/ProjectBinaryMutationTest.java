package com.jarvys.agent.coding;

import static org.junit.Assert.*;
import com.jarvys.agent.CancellationToken;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class ProjectBinaryMutationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private ProjectScope scope() throws IOException{return new ProjectScopeStore(temporary.newFolder()).open("binary-fixture");}
    @Test public void readOnlyAndOversizedExistingFilesRejectBeforeProducingBytes() throws Exception {
        ProjectScope scope=scope(); AtomicInteger produced=new AtomicInteger(); ProjectMutationService mutations=new ProjectMutationService(16);
        Files.write(scope.resolve("large.png").toPath(),new byte[17]);
        assertThrows(IOException.class,()->mutations.produceBinary(scope,"worker",scope.version(),"large.png",scope.revision("large.png"),()->{produced.incrementAndGet();return new byte[]{1};},CancellationToken.uncancellable()));
        ProjectScope readOnly=scope.restrict(Collections.singleton(ProjectScope.Capability.READ));
        assertThrows(IOException.class,()->mutations.produceBinary(readOnly,"worker",scope.version(),"new.png","missing",()->{produced.incrementAndGet();return new byte[]{1};},CancellationToken.uncancellable()));
        assertEquals(0,produced.get());
    }
    @Test public void producerHoldsOneLeaseAndBinaryReplacementsKeepExactBytes() throws Exception {
        ProjectScope scope=scope(); byte[] bytes={0,(byte)255,7,0,2};
        ProjectMutationService mutations=new ProjectMutationService(128);
        ProjectMutationService.Result first=mutations.produceBinary(scope,"worker",0,"assets/a.png","missing",()->{
            assertThrows(ProjectScope.ConflictException.class,()->scope.acquireWriter("other",scope.version()));return bytes;
        },CancellationToken.uncancellable());
        assertTrue(first.message,first.isSuccess());assertArrayEquals(bytes,Files.readAllBytes(scope.resolve("assets/a.png").toPath()));
        byte[] next={0,(byte)128,3};ProjectMutationService.Result second=mutations.produceBinary(scope,"worker",scope.version(),"assets/a.png",ProjectScope.sha256(bytes),()->next,CancellationToken.uncancellable());
        assertTrue(second.message,second.isSuccess());assertArrayEquals(next,Files.readAllBytes(scope.resolve("assets/a.png").toPath()));
        assertEquals(2,scope.mutationRecovery().entries.size());assertFalse(second.applied.get(0).diff.contains("\u0000"));
    }
    @Test public void finalPublicationGuardCanRejectAfterStagingWithoutCreatingTarget() throws Exception {
        ProjectScope scope=scope();AtomicInteger generated=new AtomicInteger(),checks=new AtomicInteger();
        ProjectMutationService mutations=new ProjectMutationService(128,new ProjectMutationService.CommitObserver(){
            public void beforeCommit(int i,String p){}
            public void beforePromotion(String p) throws IOException{checks.incrementAndGet();throw new IOException("revoked");}
        });
        ProjectMutationService.Result result=mutations.produceBinary(scope,"worker",0,"assets/a.png","missing",()->{generated.incrementAndGet();return new byte[]{0,1,2};},CancellationToken.uncancellable());
        assertEquals(ProjectMutationService.Status.FAILED,result.status);assertEquals("missing",scope.revision("assets/a.png"));assertEquals(1,generated.get());assertEquals(1,checks.get());
        assertNotNull(result.journalId);try(ProjectScope.WriterLease ignored=scope.acquireWriter("next",scope.version())){assertNotNull(ignored);}
    }
    @Test public void interruptedExclusiveBinaryCopyRetainsPartialRecoveryWithoutReplay() throws Exception {
        ProjectScope scope=scope();AtomicInteger produced=new AtomicInteger();
        ProjectMutationService mutations=new ProjectMutationService(100000,new ProjectMutationService.CommitObserver(){
            public void beforeCommit(int i,String p){}
            public void afterCreationChunk(String p,long copied) throws IOException{throw new IOException("authority revoked during copy");}
        });
        ProjectMutationService.Result result=mutations.produceBinary(scope,"worker",0,"image.png","missing",()->{produced.incrementAndGet();return new byte[70000];},CancellationToken.uncancellable());
        assertEquals(ProjectMutationService.Status.PARTIAL,result.status);assertFalse(result.cleanupWarnings.isEmpty());assertNotNull(result.journalId);
        assertEquals(1,produced.get());assertTrue(scope.version()>0);assertTrue(scope.mutationRecovery().entries.stream().anyMatch(entry->entry.id.equals(result.journalId)));
    }
    @Test public void journalFailureAfterPromotionReturnsPartialAndKeepsCorrectBytes() throws Exception {
        ProjectScope scope=scope();byte[] bytes={0,5,2};
        ProjectMutationService mutations=new ProjectMutationService(128,new ProjectMutationService.CommitObserver(){
            public void beforeCommit(int i,String p){}
            public void afterEffectBeforeJournal(String p) throws IOException{if(p.equals("image.png"))throw new IOException("journal fixture failure");}
        });
        ProjectMutationService.Result result=mutations.produceBinary(scope,"worker",0,"image.png","missing",()->bytes,CancellationToken.uncancellable());
        assertEquals(ProjectMutationService.Status.PARTIAL,result.status);assertEquals(1,result.applied.size());assertArrayEquals(bytes,Files.readAllBytes(scope.resolve("image.png").toPath()));
        assertNotNull(result.journalId);assertEquals(ProjectScope.sha256(bytes),scope.revision("image.png"));
    }
}
