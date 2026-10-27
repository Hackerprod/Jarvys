package com.jarvys.agent.coding;

import com.jarvys.agent.linux.CodingJobJournalWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Process-owned liveness only. Never a dispatch permission. Stable files are NEVER unlinked. */
public final class CodingJobOwnerLease {
    private static final String PROCESS_ID = UUID.randomUUID().toString();
    private static final java.util.Map<String,Object> LOCAL_FILES = new java.util.HashMap<>();
    private static final String HEADER = "JARVYS_CODING_OWNER_V1\n";
    private final String managerId;
    private final File file;
    private final Handle handle;
    private final FileLock lock;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private boolean quiescing;
    private boolean released;
    private int pins;
    private CodingJobOwnerLease(File file,String manager,Handle handle,FileLock lock) {
        this.file=file;managerId=manager;this.handle=handle;this.lock=lock;
    }
    public static CodingJobOwnerLease create(File filesRoot) throws IOException {
        File root=filesRoot.getAbsoluteFile();verify(root);
        File directory=new File(root,"jarvys/runtime-owners/coding");ensureDirectory(directory);
        String manager=UUID.randomUUID().toString();File file=new File(directory,manager+".lock");verify(file);
        Handle handle=open(file,true);FileLock held=null;
        try {
            held=handle.channel().tryLock();
            if(held==null)throw new IOException("Could not own the new Coding liveness file");
            handle.write(header(PROCESS_ID,manager));handle.sync();
            CodingJobJournalWriter.RealBackend.INSTANCE.syncDirectory(directory);
            CodingJobOwnerLease owner=new CodingJobOwnerLease(file,manager,handle,held);
            synchronized(LOCAL_FILES){if(LOCAL_FILES.putIfAbsent(file.getPath(),owner)!=null)throw new IOException("Owner file already registered");}
            return owner;
        } catch(Exception failure) {
            try { if(held!=null)held.release(); } catch(Exception cleanup){failure.addSuppressed(cleanup);}
            try { handle.close(); } catch(Exception cleanup){failure.addSuppressed(cleanup);}
            if(failure instanceof IOException)throw (IOException)failure;
            throw new IOException("Could not establish Coding owner liveness",failure);
        }
    }
    public String processId(){return PROCESS_ID;}
    public String managerId(){return managerId;}
    public File file(){return file;}
    public synchronized Pin pinSubmission() {
        if(quiescing||released||!lock.isValid())throw new IllegalStateException("Coding owner is quiescing");
        pins++;return new Pin(this,true);
    }
    public static final class Pin implements AutoCloseable {
        private final CodingJobOwnerLease owner;
        private boolean closed;
        private final boolean rootAdmission;
        private Pin(CodingJobOwnerLease owner,boolean rootAdmission){this.owner=owner;this.rootAdmission=rootAdmission;}
        CodingJobOwnerLease ownerIdentity(){return owner;}
        boolean isRootAdmission(){return rootAdmission;}
        public String processId(){return owner.processId();}
        public String managerId(){return owner.managerId();}
        public void requireLive(){synchronized(owner){if(closed||owner.released||!owner.lock.isValid())throw new IllegalStateException("Coding owner pin is no longer live");}}
        public Pin fork() { synchronized(owner) { requireLive(); owner.pins++; return new Pin(owner,false); } }
        @Override public void close(){
            boolean release;
            synchronized(owner){if(closed)return;closed=true;owner.pins--;release=owner.claimReleaseIfQuiescent();}
            if(release)owner.releaseOwnedFile();
        }
    }
    /** Nonblocking; cancellation of an observer future cannot cancel the owner drain. */
    public CompletableFuture<Void> requestQuiesce() {
        boolean release;
        synchronized(this){quiescing=true;release=claimReleaseIfQuiescent();}
        if(release)releaseOwnedFile();
        return closed.thenApply(value->value);
    }
    private boolean claimReleaseIfQuiescent() {
        if(!quiescing||pins!=0||released)return false;
        released=true;return true;
    }
    private void releaseOwnedFile() {
        Throwable failure=null;
        try {lock.release();}catch(Throwable error){failure=error;}
        try {handle.close();}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        // Never open/close another descriptor of a locally locked inode: POSIX close may release process locks.
        if(failure==null)synchronized(LOCAL_FILES){LOCAL_FILES.remove(file.getPath(),this);}
        // Future continuations run outside the owner monitor and all journal/Room locks.
        if(failure==null)closed.complete(null);else closed.completeExceptionally(failure);
    }
    public enum Liveness { ALIVE, DEAD, UNKNOWN }
    public static final class Probe implements AutoCloseable {
        private final Liveness liveness;
        private final Handle handle;
        private final FileLock lock;
        private String registeredPath;
        private Probe(Liveness liveness,Handle handle,FileLock lock){this.liveness=liveness;this.handle=handle;this.lock=lock;}
        public Liveness liveness(){return liveness;}
        @Override public void close() throws IOException {
            try {if(lock!=null&&lock.isValid())lock.release();}finally{if(handle!=null)handle.close();}
            if(registeredPath!=null)synchronized(LOCAL_FILES){LOCAL_FILES.remove(registeredPath,this);}
        }
    }
    /** Existing-only open; missing/corrupt is UNKNOWN. DEAD retains the acquired lock until close. */
    public static Probe probe(File filesRoot,String process,String manager) {
        synchronized(LOCAL_FILES) {
        Handle opened=null;FileLock held=null;
        try {
            CodingJobEvidence.uuid(process);CodingJobEvidence.uuid(manager);
            File root=filesRoot.getAbsoluteFile();verify(root);
            File file=new File(root,"jarvys/runtime-owners/coding/"+manager+".lock");verify(file);
            Object local=LOCAL_FILES.get(file.getPath());
            if(local!=null) {
                if(local instanceof CodingJobOwnerLease && ((CodingJobOwnerLease)local).processId().equals(process))
                    return new Probe(Liveness.ALIVE,null,null);
                return new Probe(Liveness.UNKNOWN,null,null);
            }
            opened=open(file,false);byte[] expected=header(process,manager);
            if(!Arrays.equals(expected,opened.read(expected.length))) {opened.close();return new Probe(Liveness.UNKNOWN,null,null);}
            try {held=opened.channel().tryLock();}catch(OverlappingFileLockException alive){opened.close();return new Probe(Liveness.ALIVE,null,null);}
            if(held==null){opened.close();return new Probe(Liveness.ALIVE,null,null);}
            if(!Arrays.equals(expected,opened.read(expected.length))){held.release();opened.close();return new Probe(Liveness.UNKNOWN,null,null);}
            Probe probe=new Probe(Liveness.DEAD,opened,held);probe.registeredPath=file.getPath();
            LOCAL_FILES.put(file.getPath(),probe);
            return probe;
        } catch(Exception unknown) {
            try{if(held!=null&&held.isValid())held.release();}catch(Exception ignored){}
            try{if(opened!=null)opened.close();}catch(Exception ignored){}
            return new Probe(Liveness.UNKNOWN,null,null);
        }
        }
    }
    private static byte[] header(String process,String manager){return (HEADER+process+"\n"+manager+"\n").getBytes(StandardCharsets.US_ASCII);}
    private interface Handle extends AutoCloseable {
        FileChannel channel();void write(byte[] bytes)throws Exception;byte[] read(int expected)throws Exception;
        void sync()throws Exception;void close()throws IOException;
    }
    private static Handle open(File file,boolean create)throws IOException {
        // Keep API-26 java.nio.file linkage out of the Android API-24 implementation.
        if(System.getProperty("java.vm.name","").contains("Dalvik"))return Android.open(file,create);
        return Host.open(file,create);
    }
    private static final class Host {
        static Handle open(File file,boolean create)throws IOException {
            final FileChannel channel=create
                ? FileChannel.open(file.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.READ,java.nio.file.StandardOpenOption.WRITE)
                : FileChannel.open(file.toPath(),java.nio.file.StandardOpenOption.READ,java.nio.file.StandardOpenOption.WRITE);
            return new Handle(){
                public FileChannel channel(){return channel;}
                public void write(byte[] bytes)throws IOException{ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);}
                public byte[] read(int expected)throws IOException{
                    if(channel.size()!=expected)throw new IOException("Invalid liveness header length");
                    ByteBuffer buffer=ByteBuffer.allocate(expected);long offset=0;
                    while(buffer.hasRemaining()){int count=channel.read(buffer,offset);if(count<0)throw new IOException("Truncated liveness header");offset+=count;}
                    return buffer.array();
                }
                public void sync()throws IOException{channel.force(true);}
                public void close()throws IOException{channel.close();}
            };
        }
    }
    private static final class Android {
        static Handle open(File file,boolean create)throws IOException {
            try {
                int flags=android.system.OsConstants.O_RDWR | android.system.OsConstants.O_NOFOLLOW;
                if(create)flags|=android.system.OsConstants.O_CREAT|android.system.OsConstants.O_EXCL;
                final java.io.FileDescriptor fd=android.system.Os.open(file.getPath(),flags,0600);
                if(!android.system.OsConstants.S_ISREG(android.system.Os.fstat(fd).st_mode)){
                    android.system.Os.close(fd);throw new IOException("Liveness file is not regular");
                }
                final FileOutputStream stream=new FileOutputStream(fd);
                return new Handle(){
                    public FileChannel channel(){return stream.getChannel();}
                    public void write(byte[] bytes)throws IOException{stream.write(bytes);}
                    public byte[] read(int expected)throws Exception{
                        if(android.system.Os.fstat(fd).st_size!=expected)throw new IOException("Invalid liveness header length");
                        byte[] result=new byte[expected];int offset=0;
                        while(offset<expected){int count=android.system.Os.pread(fd,result,offset,expected-offset,offset);if(count<=0)throw new IOException("Truncated liveness header");offset+=count;}
                        return result;
                    }
                    public void sync()throws Exception{android.system.Os.fsync(fd);}
                    public void close()throws IOException{stream.close();}
                };
            }catch(android.system.ErrnoException error){throw new IOException("Cannot open existing Coding owner file",error);}
        }
    }
    private static void verify(File file)throws IOException{if(!file.getAbsoluteFile().equals(file.getCanonicalFile()))throw new IOException("Owner path cannot use symlinks");}
    private static void ensureDirectory(File directory)throws IOException{
        verify(directory);if(directory.isDirectory())return;
        File parent=directory.getParentFile();if(parent==null)throw new IOException("Owner directory has no parent");
        ensureDirectory(parent);if(!directory.mkdir()&&!directory.isDirectory())throw new IOException("Cannot create owner directory");
        CodingJobJournalWriter.RealBackend.INSTANCE.syncDirectory(parent);
    }
}
