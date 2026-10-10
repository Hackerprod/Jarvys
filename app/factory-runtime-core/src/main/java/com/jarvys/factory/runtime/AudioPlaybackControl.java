package com.jarvys.factory.runtime;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Looper;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native-only terminal revocation, independent of potentially blocked document and audio I/O. */
public final class AudioPlaybackControl extends Binder implements AutoCloseable {
    private static final String SOURCE="com.jarvys.factory.runtime.AudioControl.v1";
    private static final String TARGET="com.jarvys.factory.runtime.AudioRevoke.v1";
    private static final int CALL=IBinder.FIRST_CALL_TRANSACTION;
    private static final ThreadPoolExecutor SIGNALS=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"factory-audio-revoke");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final String nonce;
    private final FileShareTransfer.HostVerifier host;
    private boolean revoked, registered;
    private IBinder callback;
    public AudioPlaybackControl(String nonce,FileShareTransfer.HostVerifier host) {
        if(nonce==null || !nonce.matches("[a-f0-9]{64}") || host==null)throw new IllegalArgumentException();
        this.nonce=nonce;this.host=host;
    }
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) {
        if(code!=CALL || flags!=0 || reply==null || data.dataSize()>512 || data.hasFileDescriptors())return false;
        try {
            if (!host.allowed(Binder.getCallingUid())) return false;
            data.enforceInterface(SOURCE);String received=data.readString();IBinder target=data.readStrongBinder();
            if(!nonce.equals(received)||target==null||data.dataAvail()!=0)return false;
            synchronized(this) {
                if(registered)return false;
                registered=true;if(!revoked)callback=target;
                reply.writeNoException();reply.writeInt(revoked?1:0);
            }
            return true;
        }catch(RuntimeException denied){return false;}
    }
    /** Terminal locally immediately; remote delivery/stop is never claimed. */
    public void cancel() {
        final IBinder target;
        synchronized(this){if(revoked)return;revoked=true;target=callback;callback=null;}
        if(target!=null)try {SIGNALS.execute(()->{
            Parcel data=Parcel.obtain();try{data.writeInterfaceToken(TARGET);data.writeString(nonce);target.transact(CALL,data,null,IBinder.FLAG_ONEWAY);}catch(Exception ignored){}finally{data.recycle();}
        });}catch(RejectedExecutionException ignored){/* Host deadline/lifecycle remains the fail-safe. */}
    }
    @Override public void close(){cancel();}
    public static final class Registration implements AutoCloseable {
        private final AtomicBoolean revoked=new AtomicBoolean();
        private final IBinder source; private final Runnable action; private final IBinder.DeathRecipient death;
        private Registration(IBinder source,Runnable action){this.source=source;this.action=action;this.death=this::revoke;}
        private void revoke(){if(revoked.compareAndSet(false,true))action.run();}
        public boolean isRevoked(){return revoked.get();}
        @Override public void close(){revoke();try{source.unlinkToDeath(death,0);}catch(Exception ignored){}}
    }
    /** Must run off main, after authenticating the installed source and before enabling Play. */
    public static Registration register(IBinder source,String nonce,FileShareTransfer.HostVerifier verifiedSource,Runnable onRevoked)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Registration requires worker");
        if(source==null||nonce==null||!nonce.matches("[a-f0-9]{64}")||verifiedSource==null||onRevoked==null)throw new IllegalArgumentException();
        Registration registration=new Registration(source,onRevoked);
        Binder callback=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
            if(code!=CALL||flags!=IBinder.FLAG_ONEWAY||data.dataSize()>512||data.hasFileDescriptors())return false;
            try{if(!verifiedSource.allowed(Binder.getCallingUid()))return false;data.enforceInterface(TARGET);if(!nonce.equals(data.readString())||data.dataAvail()!=0)return false;registration.revoke();return true;}catch(RuntimeException denied){return false;}
        }};
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try {
            source.linkToDeath(registration.death,0);
            data.writeInterfaceToken(SOURCE);data.writeString(nonce);data.writeStrongBinder(callback);
            if(!source.transact(CALL,data,reply,0)||reply.dataSize()>512||reply.hasFileDescriptors())throw new IllegalStateException("Control unavailable");
            reply.readException();int state=reply.readInt();if(state<0||state>1||reply.dataAvail()!=0)throw new IllegalStateException("Invalid control reply");
            if(state==1)registration.revoke();
            return registration;
        }catch(Exception failure){registration.close();throw failure;}finally{data.recycle();reply.recycle();}
    }
}
