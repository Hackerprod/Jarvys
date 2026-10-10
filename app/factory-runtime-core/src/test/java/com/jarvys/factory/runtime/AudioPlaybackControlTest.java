package com.jarvys.factory.runtime;

import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic native IPC only; no audio hardware, application IPC or audibility evidence. */
@RunWith(RobolectricTestRunner.class) @Config(sdk={24,28,32},manifest=Config.NONE)
public class AudioPlaybackControlTest {
    private static final String NONCE=new String(new char[64]).replace('\0','a');
    private AudioPlaybackControl.Registration register(AudioPlaybackControl source,FileShareTransfer.HostVerifier verifier,Runnable callback)throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{return worker.submit(()->AudioPlaybackControl.register(source,NONCE,verifier,callback)).get(5,TimeUnit.SECONDS);}
        finally{worker.shutdownNow();}
    }
    @Test public void cancellationIsTerminalAndDeliveredOnce()throws Exception {
        AudioPlaybackControl source=new AudioPlaybackControl(NONCE,uid->true);
        CountDownLatch stopped=new CountDownLatch(1);AtomicInteger count=new AtomicInteger();
        AudioPlaybackControl.Registration registration=register(source,uid->true,()->{count.incrementAndGet();stopped.countDown();});
        assertFalse(registration.isRevoked());source.cancel();assertTrue(stopped.await(5,TimeUnit.SECONDS));
        assertTrue(registration.isRevoked());source.cancel();registration.close();assertEquals(1,count.get());
    }
    @Test public void earlyCancelIsObservedByRegistrationWithoutResurrection()throws Exception {
        AudioPlaybackControl source=new AudioPlaybackControl(NONCE,uid->true);source.cancel();
        AtomicInteger count=new AtomicInteger();AudioPlaybackControl.Registration registration=register(source,uid->true,count::incrementAndGet);
        assertTrue(registration.isRevoked());assertEquals(1,count.get());registration.close();assertEquals(1,count.get());
        try{register(source,uid->true,()->{});fail();}catch(ExecutionException expected){}
    }
    @Test public void sourceRejectsWrongHostAndMalformedRegistration()throws Exception {
        AudioPlaybackControl denied=new AudioPlaybackControl(NONCE,uid->false);
        try{register(denied,uid->true,()->{});fail();}catch(ExecutionException expected){}
        AudioPlaybackControl source=new AudioPlaybackControl(NONCE,uid->true);
        for(int variant=0;variant<4;variant++){
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try{
                data.writeInterfaceToken("com.jarvys.factory.runtime.AudioControl.v1");
                data.writeString(variant==0?"bad":NONCE);data.writeStrongBinder(variant==1?null:new Binder());
                if(variant==2)data.writeInt(9);if(variant==3)data.writeByteArray(new byte[1024]);
                assertFalse(source.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0));
            }finally{data.recycle();reply.recycle();}
        }
        source.cancel();
    }
    @Test public void forgedCallbackCannotRevokeAndMainThreadRegistrationFails()throws Exception {
        AudioPlaybackControl source=new AudioPlaybackControl(NONCE,uid->true);
        AudioPlaybackControl.Registration registration=register(source,uid->false,()->{});
        source.cancel();Thread.sleep(50);assertFalse(registration.isRevoked());registration.close();
        try{AudioPlaybackControl.register(new Binder(),NONCE,uid->true,()->{});fail();}catch(IllegalStateException expected){}
    }
    @Test public void sourceDeathAndMalformedCallbacksAreTerminalOrRejected()throws Exception {
        final IBinder[] callback={null};final IBinder.DeathRecipient[] death={null};
        Binder source=new Binder(){
            @Override public void linkToDeath(IBinder.DeathRecipient recipient,int flags){death[0]=recipient;}
            @Override public boolean unlinkToDeath(IBinder.DeathRecipient recipient,int flags){return true;}
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
                data.enforceInterface("com.jarvys.factory.runtime.AudioControl.v1");data.readString();callback[0]=data.readStrongBinder();
                reply.writeNoException();reply.writeInt(0);return true;
            }
        };
        ExecutorService worker=Executors.newSingleThreadExecutor();AtomicInteger count=new AtomicInteger();
        AudioPlaybackControl.Registration registration;
        try{registration=worker.submit(()->AudioPlaybackControl.register(source,NONCE,uid->true,count::incrementAndGet)).get(5,TimeUnit.SECONDS);}
        finally{worker.shutdownNow();}
        for(int variant=0;variant<4;variant++){
            Parcel data=Parcel.obtain();try{
                data.writeInterfaceToken("com.jarvys.factory.runtime.AudioRevoke.v1");data.writeString(variant==0?"bad":NONCE);
                if(variant==1)data.writeInt(1);if(variant==2)data.writeByteArray(new byte[1024]);
                assertFalse(callback[0].transact(IBinder.FIRST_CALL_TRANSACTION,data,null,variant==3?0:IBinder.FLAG_ONEWAY));
                assertFalse(registration.isRevoked());
            }finally{data.recycle();}
        }
        death[0].binderDied();assertTrue(registration.isRevoked());assertEquals(1,count.get());registration.close();assertEquals(1,count.get());
    }
    @Test public void cancellationDuringActiveRegistrationReplyCannotResurrectAuthority()throws Exception {
        AudioPlaybackControl source=new AudioPlaybackControl(NONCE,uid->true);
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
            Future<AudioPlaybackControl.Registration> pending=worker.submit(()->AudioPlaybackControl.register(delayed,NONCE,uid->true,()->{notifications.incrementAndGet();cancelled.countDown();}));
            assertTrue(activeReplyWritten.await(5,TimeUnit.SECONDS));source.cancel();assertTrue(cancelled.await(5,TimeUnit.SECONDS));
            releaseReply.countDown();AudioPlaybackControl.Registration registration=pending.get(5,TimeUnit.SECONDS);
            assertTrue(registration.isRevoked());assertEquals(1,notifications.get());source.cancel();registration.close();assertEquals(1,notifications.get());
        }finally{releaseReply.countDown();worker.shutdownNow();source.close();}
    }
}
