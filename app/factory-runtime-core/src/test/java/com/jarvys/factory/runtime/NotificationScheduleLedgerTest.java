package com.jarvys.factory.runtime;

import android.content.Context;
import android.os.UserManager;
import java.io.File;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static com.jarvys.factory.runtime.NotificationScheduleEngine.*;
import static com.jarvys.factory.runtime.NotificationScheduleMachine.*;

/** Synthetic Android SQLite tests. Staged for the authorized aggregate Robolectric run; not yet executed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={24,35},manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NotificationScheduleLedgerTest {
    Context context;NotificationScheduleLedger ledger;Source source;long now=1000;
    static String hex(char c){char[] a=new char[64];java.util.Arrays.fill(a,c);return new String(a);}
    @Before public void setup(){
        context=RuntimeEnvironment.getApplication();
        Shadows.shadowOf((UserManager)context.getSystemService(Context.USER_SERVICE)).setUserUnlocked(true);
        source=new Source(context.getPackageName(),hex('a'),hex('b'),hex('c'),1,true,new Limits(128,256,1024,4194304));
        ledger=new NotificationScheduleLedger(context);
    }
    @After public void close(){if(ledger!=null)ledger.close();}
    NotificationScheduleRequest.Schedule request(String id)throws Exception{
        return NotificationScheduleRequest.parseSchedule("{\"scheduleId\":\""+id+"\",\"atEpochMs\":2000,\"expiresAtEpochMs\":4000,\"notification\":{\"id\":7,\"channelId\":\"main\",\"title\":\"Hello\",\"body\":\"Body\",\"actions\":[{\"id\":\"open\",\"title\":\"Open\",\"data\":{\"nested\":[null,1,true]}}]}}");
    }
    Row reserve(String id)throws Exception{return ledger.change(ledger.insert(source,request(id),now),State.DISPATCH_RESERVED,"ready",hex('1'),hex('2'),now);}
    @Test public void requestBytesAndTokensSurviveCloseReopen()throws Exception{
        Row row=ledger.insert(source,request("one"),now);ledger.close();ledger=new NotificationScheduleLedger(context);
        Row reopened=ledger.get(source.ownerFingerprint,"one");assertEquals(row.token,reopened.token);assertEquals(row.fingerprint,reopened.fingerprint);
        assertEquals(request("one").fingerprint(),reopened.request.fingerprint());assertNotNull(ledger.byAlarmToken(row.token));
        assertTrue(new File(context.getNoBackupFilesDir(),"factory-notification-ledger-v1.db").isFile());
    }
    @Test public void immutableRequestConflictDoesNotReplaceExistingRecord()throws Exception{
        Row old=ledger.insert(source,request("one"),now);String changed=request("one").authorizationDetails().put("atEpochMs",3000).toString();
        assertEquals("CONFLICT",assertThrows(FactoryException.class,()->ledger.insert(source,NotificationScheduleRequest.parseSchedule(changed),now)).code);
        assertEquals(old.token,ledger.get(source.ownerFingerprint,"one").token);
    }
    @Test public void consumedActionReplaysOneEventBeforeAndAfterAckAndRestart()throws Exception{
        Row row=reserve("one");ActionToken token=ledger.actions(row).get(0);
        NotificationActionEvents.Event first=ledger.acceptTap(source.ownerFingerprint,token.token,2000);assertNotNull(first);
        assertEquals(first.eventId,ledger.acceptTap(source.ownerFingerprint,token.token,2001).eventId);
        assertEquals(first.eventId,ledger.poll(source.ownerFingerprint).eventId);assertEquals(first.eventId,ledger.poll(source.ownerFingerprint).eventId);
        assertTrue(ledger.acknowledge(source.ownerFingerprint,first.eventId));assertTrue(ledger.acknowledge(source.ownerFingerprint,first.eventId));assertNull(ledger.poll(source.ownerFingerprint));
        ledger.close();ledger=new NotificationScheduleLedger(context);
        assertEquals(first.eventId,ledger.acceptTap(source.ownerFingerprint,token.token,2002).eventId);assertNull(ledger.poll(source.ownerFingerprint));
    }
    @Test public void wrongArtifactUnknownExpiredAndDisabledTokensYieldNothing()throws Exception{
        Row row=reserve("one");ActionToken token=ledger.actions(row).get(0);
        assertNull(ledger.acceptTap(hex('d'),token.token,2000));assertNull(ledger.acceptTap(source.ownerFingerprint,hex('e'),2000));
        assertNull(ledger.acceptTap(source.ownerFingerprint,token.token,4001));ledger.invalidateVisibleActions(source.ownerFingerprint,7);
        assertNull(ledger.acceptTap(source.ownerFingerprint,token.token,2000));assertNull(ledger.poll(source.ownerFingerprint));
    }
    @Test public void secondVisibleGenerationInvalidatesPreviousActions()throws Exception{
        Row first=reserve("one");ActionToken old=ledger.actions(first).get(0);Row second=reserve("two");
        assertNull(ledger.acceptTap(source.ownerFingerprint,old.token,2000));assertNotNull(ledger.acceptTap(source.ownerFingerprint,ledger.actions(second).get(0).token,2000));
    }
    @Test public void inboxFullLeavesTokenRetryableAfterAcknowledgment()throws Exception{
        source=new Source(context.getPackageName(),hex('a'),hex('b'),hex('c'),1,true,new Limits(128,1,1024,4194304));
        Row one=reserve("one");NotificationActionEvents.Event first=ledger.acceptTap(source.ownerFingerprint,ledger.actions(one).get(0).token,2000);
        Row two=reserve("two");ActionToken second=ledger.actions(two).get(0);assertThrows(NotificationLedgerCapacityException.class,()->ledger.acceptTap(source.ownerFingerprint,second.token,2001));
        assertTrue(ledger.acknowledge(source.ownerFingerprint,first.eventId));assertNotNull(ledger.acceptTap(source.ownerFingerprint,second.token,2002));
    }
    @Test public void forgetCannotDropUnackedEventAndOldTokensCannotAffectReuse()throws Exception{
        Row row=reserve("one");ActionToken token=ledger.actions(row).get(0);NotificationActionEvents.Event event=ledger.acceptTap(source.ownerFingerprint,token.token,2000);
        Row accepted=ledger.change(row,State.ACCEPTED,"notify_returned",hex('1'),hex('2'),2001);
        assertEquals("UNACKNOWLEDGED_ACTIONS",assertThrows(FactoryException.class,()->ledger.forget(accepted,false)).code);
        ledger.acknowledge(source.ownerFingerprint,event.eventId);assertTrue(ledger.forget(accepted,false));
        Row replacement=ledger.insert(source,request("one"),2002);assertNotEquals(row.token,replacement.token);
        assertNull(ledger.acceptTap(source.ownerFingerprint,token.token,2003));assertNull(ledger.byAlarmToken(row.token));
        assertNull(ledger.change(row,State.CANCELLED,"late",hex('1'),hex('2'),2003));
    }
    @Test public void capacityFailureDoesNotEvictAnyIdentifier()throws Exception{
        source=new Source(context.getPackageName(),hex('a'),hex('b'),hex('c'),1,true,new Limits(1,1,2,4194304));
        Row row=ledger.insert(source,request("one"),now);
        assertEquals("CAPACITY_EXCEEDED",assertThrows(FactoryException.class,()->ledger.insert(source,request("two"),now)).code);
        assertEquals(row.token,ledger.get(source.ownerFingerprint,"one").token);assertNull(ledger.get(source.ownerFingerprint,"two"));
    }
    @Test public void casConflictCannotMutateLaterRow()throws Exception{
        Row row=ledger.insert(source,request("one"),now);Row newer=ledger.change(row,State.CANCELLED,"cancel",hex('1'),hex('2'),now);
        assertNull(ledger.change(row,State.ARMING,"stale",hex('1'),hex('3'),now));assertEquals(newer.revision,ledger.get(source.ownerFingerprint,"one").revision);
    }
    @Test public void durableStorageRejectsPreunlockContext(){
        assertThrows(IllegalArgumentException.class,()->new NotificationScheduleLedger(context.createDeviceProtectedStorageContext()));
        Shadows.shadowOf((UserManager)context.getSystemService(Context.USER_SERVICE)).setUserUnlocked(false);
        assertThrows(IllegalStateException.class,()->new NotificationScheduleLedger(context));
    }
    @Test public void pagesHaveStableTokenOrderAndBoundedSize()throws Exception{
        ledger.insert(source,request("one"),now);ledger.insert(source,request("two"),now);ledger.insert(source,request("three"),now);
        java.util.List<Row> first=ledger.page(null,2);assertEquals(2,first.size());java.util.List<Row> second=ledger.page(first.get(1).token,2);assertEquals(1,second.size());
        assertTrue(second.get(0).token.compareTo(first.get(1).token)>0);assertTrue(ledger.page(second.get(0).token,2).isEmpty());
    }
    @Test public void immediateActionRowsHaveNoSyntheticScheduleOrClockExpiry()throws Exception {
        NotificationScheduleRequest.Notification notification=request("unused").notification;
        Row immediate=ledger.insertImmediate(source,notification,hex('1'),1000);
        assertEquals(Kind.IMMEDIATE,immediate.kind);assertNull(immediate.request);assertNull(immediate.scheduleId);
        Row reserved=ledger.change(immediate,State.DISPATCH_RESERVED,"ready",hex('1'),hex('2'),1000);ActionToken token=ledger.actions(reserved).get(0);
        NotificationActionEvents.Event event=ledger.acceptTap(source.ownerFingerprint,token.token,9999999999L);
        assertNotNull(event);assertNull(event.scheduleId);assertTrue(event.details().isNull("scheduleId"));
        ledger.close();ledger=new NotificationScheduleLedger(context);assertEquals(event.eventId,ledger.poll(source.ownerFingerprint).eventId);
    }
    @Test public void expiredIngressDoesNotStealNewerResumeAcquisition()throws Exception {
        Row row=ledger.insert(source,request("one"),now);Row pending=ledger.change(row,State.QUEUED_SESSION_UNAVAILABLE,"acquiring_host_session",hex('1'),hex('2'),2000);
        ledger.queueUnavailable(row.token,hex('3'),2000);assertEquals(pending.revision,ledger.get(source.ownerFingerprint,"one").revision);
    }
    @Test public void recoveryProgressSurvivesReopen()throws Exception {
        Row row=ledger.insert(source,request("one"),now);ledger.checkpointRecovery(row.token);ledger.close();ledger=new NotificationScheduleLedger(context);
        assertEquals(row.token,ledger.recoveryCursor());ledger.checkpointRecovery(null);assertNull(ledger.recoveryCursor());
    }
    @Test public void lowerCurrentBudgetRetainsExistingDataAndClosesNewAdmission()throws Exception {
        Row row=ledger.insert(source,request("one"),now);
        Source lower=new Source(context.getPackageName(),hex('a'),hex('d'),hex('e'),2,true,new Limits(1,1,1,65536));ledger.bindCurrentSource(lower);
        assertNotNull(ledger.get(lower.ownerFingerprint,"one"));
        assertEquals("CAPACITY_EXCEEDED",assertThrows(FactoryException.class,()->ledger.insert(lower,request("two"),now)).code);
        assertEquals(row.token,ledger.get(lower.ownerFingerprint,"one").token);
    }

}
