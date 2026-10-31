package com.jarvys.factory.runtime;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Native synthetic Binder contract, not evidence of cross-process Android scheduling. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34,manifest=Config.NONE)
public class BackupCommitAuthorizationTest {
    private static final String NONCE=new String(new char[64]).replace('\0','a'), PLAN=new String(new char[64]).replace('\0','b');
    private <T> T worker(Callable<T> action) throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try { return executor.submit(action).get(5,TimeUnit.SECONDS); } finally { executor.shutdownNow(); }
    }
    @Test public void exactLeaseSurvivesUiReturnUntilCommitAndTerminalClose() throws Exception {
        AtomicBoolean allowed=new AtomicBoolean(true); AtomicInteger checks=new AtomicInteger(),closed=new AtomicInteger();
        BackupCommitAuthorization host=new BackupCommitAuthorization(NONCE,PLAN,SystemClock.elapsedRealtime()+300000,uid->true,
                ()->{checks.incrementAndGet();if(!allowed.get())throw new SecurityException();},closed::incrementAndGet);
        BackupCommitAuthorization.Client client=worker(()->BackupCommitAuthorization.connect(host,NONCE,PLAN,uid->true));
        assertFalse(host.isClosed()); assertTrue(worker(client::allowed));
        allowed.set(false); assertFalse(worker(client::allowed)); assertTrue(host.isClosed());
        worker(()->{client.close();return null;}); host.close(); assertEquals(1,closed.get()); assertTrue(checks.get()>=3);
    }
    @Test public void wrongSourceUidWrongHostUidAndWrongPlanCannotAuthorize() throws Exception {
        BackupCommitAuthorization host=new BackupCommitAuthorization(NONCE,PLAN,SystemClock.elapsedRealtime()+300000,uid->false,()->{},()->{});
        final BackupCommitAuthorization rejectedSource=host;
        assertThrows(ExecutionException.class,()->worker(()->BackupCommitAuthorization.connect(rejectedSource,NONCE,PLAN,uid->true)));
        host=new BackupCommitAuthorization(NONCE,PLAN,SystemClock.elapsedRealtime()+300000,uid->true,()->{},()->{});
        final BackupCommitAuthorization rejectedHost=host;
        assertThrows(ExecutionException.class,()->worker(()->BackupCommitAuthorization.connect(rejectedHost,NONCE,PLAN,uid->false)));
        host=new BackupCommitAuthorization(NONCE,PLAN,SystemClock.elapsedRealtime()+300000,uid->true,()->{},()->{});
        final BackupCommitAuthorization wrongPlan=host;
        assertThrows(ExecutionException.class,()->worker(()->BackupCommitAuthorization.connect(wrongPlan,NONCE,NONCE,uid->true)));
        host.close();
    }
    @Test public void forgedSuccessfulReplyCannotReplaceUidChallenge() throws Exception {
        Binder fake=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){reply.writeNoException();reply.writeInt(1);return true;}};
        assertThrows(ExecutionException.class,()->worker(()->BackupCommitAuthorization.connect(fake,NONCE,PLAN,uid->true)));
    }
    @Test public void terminalCloseAndConnectionCannotBeReplayed() throws Exception {
        AtomicInteger count=new AtomicInteger();
        BackupCommitAuthorization host=new BackupCommitAuthorization(NONCE,PLAN,SystemClock.elapsedRealtime()+300000,uid->true,()->{},count::incrementAndGet);
        BackupCommitAuthorization.Client client=worker(()->BackupCommitAuthorization.connect(host,NONCE,PLAN,uid->true));
        assertThrows(ExecutionException.class,()->worker(()->BackupCommitAuthorization.connect(host,NONCE,PLAN,uid->true)));
        worker(()->{client.close();return null;}); assertEquals(1,count.get()); assertFalse(worker(client::allowed));
        assertThrows(ExecutionException.class,()->worker(()->BackupCommitAuthorization.connect(host,NONCE,PLAN,uid->true)));
    }
    @Test public void checkpointsRejectMainThreadAndExpiredLease() throws Exception {
        BackupCommitAuthorization host=new BackupCommitAuthorization(NONCE,PLAN,SystemClock.elapsedRealtime()+300000,uid->true,()->{},()->{});
        assertThrows(IllegalStateException.class,()->BackupCommitAuthorization.connect(host,NONCE,PLAN,uid->true));
        host.close();
        assertThrows(IllegalArgumentException.class,()->new BackupCommitAuthorization(NONCE,PLAN,SystemClock.elapsedRealtime(),uid->true,()->{},()->{}));
    }
}
