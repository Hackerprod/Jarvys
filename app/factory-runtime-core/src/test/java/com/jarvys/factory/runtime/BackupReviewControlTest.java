package com.jarvys.factory.runtime;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic Binder identity checks; these do not claim real cross-process or OS closure coverage. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34,manifest=Config.NONE)
public class BackupReviewControlTest {
    private static final String NONCE=new String(new char[64]).replace('\0','a');
    private BackupReviewControl.Registration register(IBinder source,BackupReviewControl.HostVerifier verifier,Runnable action)throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{return worker.submit(()->BackupReviewControl.register(source,NONCE,verifier,action)).get(5,TimeUnit.SECONDS);}
        finally{worker.shutdownNow();}
    }
    @Test public void uidAuthenticatedRegistrationAndTerminalCancellation()throws Exception {
        BackupReviewControl source=new BackupReviewControl(NONCE,uid->true);
        CountDownLatch stopped=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        BackupReviewControl.Registration registration=register(source,uid->true,()->{calls.incrementAndGet();stopped.countDown();});
        assertFalse(registration.isRevoked());source.cancel();assertTrue(stopped.await(5,TimeUnit.SECONDS));
        assertTrue(registration.isRevoked());source.cancel();registration.close();assertEquals(1,calls.get());
    }
    @Test public void earlyCancellationCannotResurrectOrReregister()throws Exception {
        BackupReviewControl source=new BackupReviewControl(NONCE,uid->true);source.cancel();
        BackupReviewControl.Registration registration=register(source,uid->true,()->{});assertTrue(registration.isRevoked());
        try{register(source,uid->true,()->{});fail();}catch(ExecutionException expected){}
    }
    @Test public void wrongHostAndWrongSourceUidAreBothDenied()throws Exception {
        BackupReviewControl source=new BackupReviewControl(NONCE,uid->false);
        try{register(source,uid->true,()->{});fail();}catch(ExecutionException expected){}
        source=new BackupReviewControl(NONCE,uid->true);
        try{register(source,uid->false,()->{});fail();}catch(ExecutionException expected){}
        source.close();
    }
    @Test public void arbitraryBinderReplyCannotImpersonateVerifiedSource()throws Exception {
        Binder forwarded=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
            reply.writeNoException();reply.writeInt(0);return true;
        }};
        try{register(forwarded,uid->true,()->{});fail();}catch(ExecutionException expected){}
    }
    @Test public void malformedNonceExtrasAndOtherDomainCannotRegister()throws Exception {
        for(int variant=0;variant<5;variant++) {
            BackupReviewControl source=new BackupReviewControl(NONCE,uid->true);Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try {
                data.writeInterfaceToken(variant==4?"com.jarvys.factory.runtime.ExternalLaunchControl.v1":"com.jarvys.factory.runtime.BackupReviewControl.v1");
                data.writeString(variant==0?"bad":NONCE);data.writeStrongBinder(variant==1?null:new Binder());
                if(variant==2)data.writeInt(1);if(variant==3)data.writeByteArray(new byte[1024]);
                assertFalse(source.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0));
            }finally{data.recycle();reply.recycle();source.close();}
        }
    }
    @Test public void registrationRequiresWorker()throws Exception {
        try{BackupReviewControl.register(new Binder(),NONCE,uid->true,()->{});fail();}catch(IllegalStateException expected){}
    }
}
