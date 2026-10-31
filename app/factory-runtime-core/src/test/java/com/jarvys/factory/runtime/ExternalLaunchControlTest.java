package com.jarvys.factory.runtime;

import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic native IPC only; no external application IPC or completed-action evidence. */
@RunWith(RobolectricTestRunner.class) @Config(sdk={24,28,32},manifest=Config.NONE)
public class ExternalLaunchControlTest {
    private static final String NONCE=new String(new char[64]).replace('\0','a');
    private ExternalLaunchControl.Registration register(ExternalLaunchControl source,ExternalLaunchControl.HostVerifier verifier,Runnable callback)throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{return worker.submit(()->ExternalLaunchControl.register(source,NONCE,verifier,callback)).get(5,TimeUnit.SECONDS);}
        finally{worker.shutdownNow();}
    }
    @Test public void cancellationIsTerminalAndDeliveredOnce()throws Exception {
        ExternalLaunchControl source=new ExternalLaunchControl(NONCE,uid->true);
        CountDownLatch stopped=new CountDownLatch(1);AtomicInteger count=new AtomicInteger();
        ExternalLaunchControl.Registration registration=register(source,uid->true,()->{count.incrementAndGet();stopped.countDown();});
        assertFalse(registration.isRevoked());source.cancel();assertTrue(stopped.await(5,TimeUnit.SECONDS));
        assertTrue(registration.isRevoked());source.cancel();registration.close();assertEquals(1,count.get());
    }
    @Test public void earlyCancelIsObservedByRegistrationWithoutResurrection()throws Exception {
        ExternalLaunchControl source=new ExternalLaunchControl(NONCE,uid->true);source.cancel();
        AtomicInteger count=new AtomicInteger();ExternalLaunchControl.Registration registration=register(source,uid->true,count::incrementAndGet);
        assertTrue(registration.isRevoked());assertEquals(1,count.get());registration.close();assertEquals(1,count.get());
        try{register(source,uid->true,()->{});fail();}catch(ExecutionException expected){}
    }
    @Test public void sourceRejectsWrongHostAndMalformedRegistration()throws Exception {
        ExternalLaunchControl denied=new ExternalLaunchControl(NONCE,uid->false);
        try{register(denied,uid->true,()->{});fail();}catch(ExecutionException expected){}
        ExternalLaunchControl source=new ExternalLaunchControl(NONCE,uid->true);
        for(int variant=0;variant<4;variant++){
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try{
                data.writeInterfaceToken("com.jarvys.factory.runtime.ExternalLaunchControl.v1");
                data.writeString(variant==0?"bad":NONCE);data.writeStrongBinder(variant==1?null:new Binder());
                if(variant==2)data.writeInt(9);if(variant==3)data.writeByteArray(new byte[1024]);
                assertFalse(source.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0));
            }finally{data.recycle();reply.recycle();}
        }
        source.cancel();
    }
    @Test public void forgedCallbackCannotRevokeAndMainThreadRegistrationFails()throws Exception {
        ExternalLaunchControl source=new ExternalLaunchControl(NONCE,uid->true);
        ExternalLaunchControl.Registration registration=register(source,uid->false,()->{});
        source.cancel();Thread.sleep(50);assertFalse(registration.isRevoked());registration.close();
        try{ExternalLaunchControl.register(new Binder(),NONCE,uid->true,()->{});fail();}catch(IllegalStateException expected){}
    }
    @Test public void sourceDeathAndMalformedCallbacksAreTerminalOrRejected()throws Exception {
        final IBinder[] callback={null};final IBinder.DeathRecipient[] death={null};
        Binder source=new Binder(){
            @Override public void linkToDeath(IBinder.DeathRecipient recipient,int flags){death[0]=recipient;}
            @Override public boolean unlinkToDeath(IBinder.DeathRecipient recipient,int flags){return true;}
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
                data.enforceInterface("com.jarvys.factory.runtime.ExternalLaunchControl.v1");data.readString();callback[0]=data.readStrongBinder();
                reply.writeNoException();reply.writeInt(0);return true;
            }
        };
        ExecutorService worker=Executors.newSingleThreadExecutor();AtomicInteger count=new AtomicInteger();
        ExternalLaunchControl.Registration registration;
        try{registration=worker.submit(()->ExternalLaunchControl.register(source,NONCE,uid->true,count::incrementAndGet)).get(5,TimeUnit.SECONDS);}
        finally{worker.shutdownNow();}
        for(int variant=0;variant<4;variant++){
            Parcel data=Parcel.obtain();try{
                data.writeInterfaceToken("com.jarvys.factory.runtime.ExternalLaunchRevoke.v1");data.writeString(variant==0?"bad":NONCE);
                if(variant==1)data.writeInt(1);if(variant==2)data.writeByteArray(new byte[1024]);
                assertFalse(callback[0].transact(IBinder.FIRST_CALL_TRANSACTION,data,null,variant==3?0:IBinder.FLAG_ONEWAY));
                assertFalse(registration.isRevoked());
            }finally{data.recycle();}
        }
        death[0].binderDied();assertTrue(registration.isRevoked());assertEquals(1,count.get());registration.close();assertEquals(1,count.get());
    }
    @Test public void cancellationDuringActiveRegistrationReplyCannotResurrectAuthority()throws Exception {
        ExternalLaunchControl source=new ExternalLaunchControl(NONCE,uid->true);
        CountDownLatch activeReplyWritten=new CountDownLatch(1),releaseReply=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        AtomicInteger notifications=new AtomicInteger();
        Binder delayed=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
            boolean accepted=source.onTransact(code,data,reply,flags);
            assertTrue(accepted);reply.setDataPosition(0);reply.readException();assertEquals(0,reply.readInt());
            activeReplyWritten.countDown();
            try{assertTrue(releaseReply.await(5,TimeUnit.SECONDS));}catch(InterruptedException failure){throw new AssertionError(failure);}
            return accepted;
        }};
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<ExternalLaunchControl.Registration> pending=worker.submit(()->ExternalLaunchControl.register(delayed,NONCE,uid->true,()->{notifications.incrementAndGet();cancelled.countDown();}));
            assertTrue(activeReplyWritten.await(5,TimeUnit.SECONDS));source.cancel();assertTrue(cancelled.await(5,TimeUnit.SECONDS));
            releaseReply.countDown();ExternalLaunchControl.Registration registration=pending.get(5,TimeUnit.SECONDS);
            assertTrue(registration.isRevoked());assertEquals(1,notifications.get());source.cancel();registration.close();assertEquals(1,notifications.get());
        }finally{releaseReply.countDown();worker.shutdownNow();source.close();}
    }
    @Test public void otherDomainTokensCannotRegisterExternalLaunchAuthority()throws Exception {
        ExternalLaunchControl source=new ExternalLaunchControl(NONCE,uid->true);
        for(String descriptor:new String[]{"AudioControl.v1","BrowserControl.v1","BrowserRevoke.v1","ExternalLaunchRevoke.v1"}) {
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try {
                data.writeInterfaceToken("com.jarvys.factory.runtime."+descriptor);data.writeString(NONCE);data.writeStrongBinder(new Binder());
                assertFalse(source.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0));
            }finally{data.recycle();reply.recycle();}
        }
        ExternalLaunchControl.Registration registration=register(source,uid->true,()->{});
        assertFalse(registration.isRevoked());registration.close();source.close();
    }

    @Test public void externalDomainCannotRegisterExistingBrowserOrAudioControls()throws Exception {
        BrowserLaunchControl browser=new BrowserLaunchControl(NONCE,uid->true);
        AudioPlaybackControl audio=new AudioPlaybackControl(NONCE,uid->true);
        try {
            for(IBinder target:new IBinder[]{browser,audio}) {
                Parcel data=Parcel.obtain(),reply=Parcel.obtain();
                try {
                    data.writeInterfaceToken("com.jarvys.factory.runtime.ExternalLaunchControl.v1");
                    data.writeString(NONCE);data.writeStrongBinder(new Binder());
                    assertFalse(target.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0));
                } finally {data.recycle();reply.recycle();}
            }
        } finally {browser.close();audio.close();}
    }

}
