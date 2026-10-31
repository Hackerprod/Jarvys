package com.jarvys.factory.runtime;

import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DocumentBackupHandlesTest {
    static final int MAX=1024*1024;
    static class Fixture {
        long now; final List<Runnable> cleanup=new ArrayList<>();
        final DocumentHandles handles=new DocumentHandles(()->now,cleanup::add);
        void clean(){for(Runnable action:new ArrayList<>(cleanup))action.run();cleanup.clear();}
    }
    @Test public void completeSnapshotConsumesHandleAndProvesEof()throws Exception {
        Fixture f=new Fixture();byte[] value=new byte[MAX];value[0]=42;String token=f.handles.grantRead(new ByteArrayInputStream(value));
        assertArrayEquals(value,f.handles.snapshotForBackup(token));
        assertEquals("INVALID_HANDLE",assertThrows(FactoryException.class,()->f.handles.read(token,0,1)).code);f.clean();
    }
    @Test public void oneExtraByteRejectsOversizeAndRetiresSource()throws Exception {
        Fixture f=new Fixture();String token=f.handles.grantRead(new ByteArrayInputStream(new byte[MAX+1]));
        assertEquals("BACKUP_TOO_LARGE",assertThrows(FactoryException.class,()->f.handles.snapshotForBackup(token)).code);
        assertEquals("INVALID_HANDLE",assertThrows(FactoryException.class,()->f.handles.read(token,0,1)).code);f.clean();
    }
    @Test public void emptyAndAlreadyReadHandlesCannotBecomeArchives()throws Exception {
        Fixture f=new Fixture();String empty=f.handles.grantRead(new ByteArrayInputStream(new byte[0]));
        assertEquals("EMPTY_FILE",assertThrows(FactoryException.class,()->f.handles.snapshotForBackup(empty)).code);
        String used=f.handles.grantRead(new ByteArrayInputStream(new byte[]{1,2}));f.handles.read(used,0,1);
        assertEquals("INVALID_OFFSET",assertThrows(FactoryException.class,()->f.handles.snapshotForBackup(used)).code);f.handles.cancelAll();f.clean();
    }
    @Test public void eofProbeCannotBypassSessionQuota()throws Exception {
        Fixture f=new Fixture();for(int i=0;i<31;i++)f.handles.chargeBackupSnapshot(MAX);
        String token=f.handles.grantRead(new ByteArrayInputStream(new byte[MAX]));
        assertEquals("DOCUMENT_QUOTA",assertThrows(FactoryException.class,()->f.handles.snapshotForBackup(token)).code);f.clean();
    }
    @Test public void snapshotChargeAndWritesShareBudgetAndCancellationNeverRefunds()throws Exception {
        Fixture f=new Fixture();for(int i=0;i<32;i++)f.handles.chargeBackupSnapshot(MAX);
        f.handles.cancelAll();assertEquals("DOCUMENT_QUOTA",assertThrows(FactoryException.class,()->f.handles.chargeBackupSnapshot(1)).code);
        String token=f.handles.grantWrite(new ByteArrayOutputStream());
        assertEquals("DOCUMENT_QUOTA",assertThrows(FactoryException.class,()->f.handles.write(token,0,"AQ==")).code);f.handles.cancelAll();f.clean();
    }
    @Test public void revokedInstanceCannotChargeNewSession()throws Exception {
        Fixture old=new Fixture(), fresh=new Fixture();old.handles.revokeAll();
        assertEquals("SESSION_REVOKED",assertThrows(FactoryException.class,()->old.handles.chargeBackupSnapshot(1)).code);
        for(int i=0;i<32;i++)fresh.handles.chargeBackupSnapshot(MAX);
        assertThrows(FactoryException.class,()->fresh.handles.chargeBackupSnapshot(1));
    }
    @Test public void cancelledSnapshotRetainsCleanupSlotUntilCloseActuallyRuns()throws Exception {
        Fixture f=new Fixture();final boolean[] closed={false};String token=f.handles.grantRead(new ByteArrayInputStream(new byte[]{1}){
            @Override public void close(){closed[0]=true;}
        });
        f.handles.snapshotForBackup(token);assertFalse(closed[0]);assertEquals(1,f.cleanup.size());f.clean();assertTrue(closed[0]);
    }
    @Test public void snapshotChargeRejectsUnsupportedSizes()throws Exception {
        Fixture f=new Fixture();for(int value:new int[]{-1,0,MAX+1})assertEquals("BACKUP_TOO_LARGE",assertThrows(FactoryException.class,()->f.handles.chargeBackupSnapshot(value)).code);
    }
}
