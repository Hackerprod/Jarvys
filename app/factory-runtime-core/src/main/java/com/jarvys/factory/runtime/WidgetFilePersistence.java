package com.jarvys.factory.runtime;
import android.content.Context;
import android.os.UserManager;
import android.util.AtomicFile;
import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
/** One credential-protected no-backup file per installed app; no account/global/shared storage. */
final class WidgetFilePersistence implements WidgetLedger.Persistence {
    private static final ConcurrentHashMap<String,Object> LOCKS=new ConcurrentHashMap<>();
    private final AtomicFile file;private final Object lock;
    WidgetFilePersistence(Context context){
        if(context==null||context.isDeviceProtectedStorage())throw new IllegalArgumentException("Credential-protected context required");
        UserManager manager=(UserManager)context.getSystemService(Context.USER_SERVICE);if(manager==null||!manager.isUserUnlocked())throw new IllegalStateException("Widget storage requires unlocked user");
        File location=new File(context.getNoBackupFilesDir(),"factory-widget-ledger-v1.json");file=new AtomicFile(location);lock=LOCKS.computeIfAbsent(location.getAbsolutePath(),key->new Object());
    }
    public Object lock(){return lock;}
    public byte[] read()throws Exception{try(InputStream input=file.openRead();ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(output.size()+count>WidgetModel.Limits.ceiling().storageBytes/2)throw new IOException("Widget ledger size");output.write(buffer,0,count);}return output.toByteArray();}catch(FileNotFoundException absent){return null;}}
    public void write(byte[] value)throws Exception{FileOutputStream output=null;try{output=file.startWrite();output.write(value);output.getFD().sync();file.finishWrite(output);}catch(Exception failure){if(output!=null)file.failWrite(output);throw failure;}}
}
