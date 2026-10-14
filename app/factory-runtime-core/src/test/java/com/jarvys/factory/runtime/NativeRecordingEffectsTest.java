package com.jarvys.factory.runtime;

import android.Manifest;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static com.jarvys.factory.runtime.NativeOperationSessionsTest.idle;
import static org.junit.Assert.*;

/** Shared policy/Android permission flow and bridge delivery with synthetic, never live, PCM. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeRecordingEffectsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private NativeOperationSessionsTest.Fixture fixture;
    private NativeEffects effects;
    private FactoryConfig config;
    private DocumentHandles documents;
    private File directory;
    private int drivers, sequence;
    private final Queue<Runnable> cleanup = new ConcurrentLinkedQueue<>();
    private final Scheduler scheduler = new Scheduler();

    @Before public void setup() throws Exception {
        fixture = new NativeOperationSessionsTest.Fixture();
        config = FactoryConfig.parse(new JSONObject(FactoryDispatcherTest.configuration("[\"recording\",\"documents\"]"))
                .put("appId", fixture.config.appId).put("documentBroker", new JSONObject()
                        .put("packageName", fixture.config.documentBroker.packageName)
                        .put("certificateSha256", fixture.config.documentBroker.certificateSha256)).toString(), fixture.config.appId);
        PackageInfo own = fixture.screen.getPackageManager().getPackageInfo(config.appId, PackageManager.GET_PERMISSIONS);
        own.requestedPermissions = new String[]{Manifest.permission.RECORD_AUDIO};
        Shadows.shadowOf(fixture.screen.getPackageManager()).installPackage(own);
        Shadows.shadowOf(fixture.screen.getPackageManager()).setPackagesForUid(android.os.Process.myUid(), DocumentBrokerIdentity.PRIMARY);
        Shadows.shadowOf(fixture.screen.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        documents = new DocumentHandles(SystemClock::elapsedRealtime, cleanup::add, config.resources);
        effects = new NativeEffects(fixture.screen, config, fixture.sessions, fixture.io, true, () -> documents);
        directory = temporary.newFolder("synthetic");
        NativeAudioRecording recorder = new NativeAudioRecording(directory, request -> {
            drivers++; return new Driver(request.sampleRate, request.channels);
        }, new NativeAudioRecording.Permission() {
            public boolean granted() { return fixture.screen.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }
            public boolean available() { return true; }
        }, scheduler, SystemClock::elapsedRealtime);
        ReflectionHelpers.setField(effects, "recording", new RecordingEffects(recorder, () -> documents, config.resources, cleanup::add));
    }
    @After public void finish() throws Exception {
        effects.close(); scheduler.runDue(); drain(); documents.revokeAll(); drain(); fixture.close();
    }
    private Result perform(String method, JSONObject args) throws Exception {
        Result result = new Result();
        effects.perform(BridgeProtocol.validate(BridgeProtocol.ORIGIN, true,
                new JSONObject().put("v", 1).put("id", "recording_" + ++sequence).put("method", method).put("args", args).toString(), config), result);
        return result;
    }
    private JSONObject tiny() throws Exception { return new JSONObject().put("durationMs", 100).put("maxBytes", 48); }
    private void drain() { Runnable task; while ((task=cleanup.poll()) != null) task.run(); }
    private int files() { String[] names=directory.list(); return names == null ? 0 : names.length; }

    @Test public void unavailableInstalledSourceCannotReserveDocumentsOrStartMicrophone() throws Exception {
        fixture.provider.unavailable=true; Result denied=perform("recording.record", tiny()); fixture.pump(); scheduler.runDue();
        assertEquals("UNAVAILABLE", denied.error); assertEquals(0, drivers); assertEquals(0, files());
        List<DocumentHandles.Reservation> slots=new ArrayList<>();
        for (int i=0;i<config.resources.documents.openHandles;i++) slots.add(documents.reserveRead());
        for (DocumentHandles.Reservation slot:slots) slot.cancel();
        assertTrue(fixture.screen.launched.isEmpty()); assertEquals(0, denied.claims);
    }
    @Test public void stopAndCancelReuseTheExistingCaptureWithoutANewHostSession() throws Exception {
        Result capture=perform("recording.record", new JSONObject().put("durationMs",1000).put("maxBytes",100));
        fixture.pump(); scheduler.runDue(); assertEquals(1, fixture.provider.issued.size());
        Result stop=perform("recording.stop", new JSONObject());
        assertTrue(stop.value.getBoolean("stopRequested")); assertEquals(1, fixture.provider.issued.size());
        scheduler.runDue(); idle(); assertEquals("stopped", capture.value.getString("reason"));
        fixture.provider.unavailable=true;
        Result cancel=perform("recording.cancel", new JSONObject());
        assertEquals(0, cancel.value.getInt("cancelled")); assertEquals(1, fixture.provider.issued.size());
        assertTrue(fixture.screen.launched.isEmpty()); assertEquals(0,capture.claims);
    }
    @Test public void permissionHandoffUsesSharedSlotAndReadsActualOsStateAfterResume() throws Exception {
        Shadows.shadowOf(fixture.screen.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);
        Result request=perform("recording.requestPermission", new JSONObject()); fixture.pump();
        assertTrue(effects.hasPendingPermission()); assertEquals(1,request.claims);
        assertArrayEquals(new String[]{Manifest.permission.RECORD_AUDIO},
                Shadows.shadowOf(fixture.screen).getLastRequestedPermission().requestedPermissions);
        assertEquals(NativeEffects.NOTIFICATION_PERMISSION_REQUEST,
                Shadows.shadowOf(fixture.screen).getLastRequestedPermission().requestCode);
        effects.onPause(); fixture.sessions.onPause();
        effects.onRequestPermissionsResult(NativeEffects.NOTIFICATION_PERMISSION_REQUEST,
                new String[]{Manifest.permission.RECORD_AUDIO},new int[]{PackageManager.PERMISSION_GRANTED});
        fixture.pump(); assertNull(request.value); assertEquals(0,request.releases);
        fixture.sessions.onResume(); effects.onResume(); fixture.pump();
        assertFalse(request.value.getBoolean("permissionGranted")); assertEquals(1,request.releases); assertEquals(0,drivers);
        Result next=perform("recording.requestPermission",new JSONObject()); fixture.pump();
        Shadows.shadowOf(fixture.screen.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        effects.onRequestPermissionsResult(NativeEffects.NOTIFICATION_PERMISSION_REQUEST,
                new String[]{Manifest.permission.RECORD_AUDIO},new int[]{PackageManager.PERMISSION_DENIED});
        fixture.pump(); assertTrue(next.value.getBoolean("permissionGranted")); assertEquals(1,next.releases);
        assertFalse(effects.hasPendingPermission()); assertEquals(0,drivers);
    }
    @Test public void failedBridgeDeliveryRetiresGrantedHandleAndPrivateFile() throws Exception {
        Result result=perform("recording.record",tiny()); result.accept=false;
        fixture.pump(); scheduler.runDue(); assertEquals(1,files()); assertEquals(0,result.attempts);
        idle(); assertEquals(1,result.attempts); assertEquals(0,result.successes); assertEquals(1,files());
        drain(); assertEquals(0,files()); assertFalse(effectsStatus().getBoolean("busy"));
    }
    @Test public void staleReplyOrPauseCannotDeliverQueuedRecording() throws Exception {
        Result result=perform("recording.record",tiny()); fixture.pump(); scheduler.runDue(); result.current=false;
        idle(); drain(); assertEquals(0,result.successes); assertEquals(0,result.attempts); assertEquals(0,files());
        Result next=perform("recording.record",tiny()); fixture.pump(); scheduler.runDue();
        effects.onPause(); fixture.sessions.onPause(); idle(); drain();
        assertEquals(0,next.successes); assertEquals(0,files());
    }
    @Test public void successfulDeliveryClearsRecordingCancellationOwnership() throws Exception {
        Result result=perform("recording.record",tiny()); fixture.pump(); scheduler.runDue(); idle();
        assertEquals(1,result.successes); assertEquals(1,result.attempts); assertEquals(1,files());
        effects.close(); scheduler.runDue(); drain(); assertEquals(1,files());
        assertEquals(48,documents.read(result.value.getString("handle"),0,48).nextOffset);
        documents.close(result.value.getString("handle")); drain(); assertEquals(0,files());
    }
    private JSONObject effectsStatus() throws Exception { return perform("recording.status",new JSONObject()).value; }
    private static final class Result implements NativeEffects.Reply {
        JSONObject value; String error; int claims,releases,attempts,successes; boolean held,current=true,accept=true;
        public boolean current() { return current; }
        public boolean claimUi() { assertFalse(held); held=true; claims++; return true; }
        public void releaseUi() { if (held) { held=false; releases++; } }
        public void ok(Object result) { value=(JSONObject)result; successes++; }
        public boolean deliver(Object result) { attempts++; if (!accept || !current) return false; ok(result); return true; }
        public void fail(String code,String message) { error=code; }
    }
    private static final class Driver implements NativeAudioRecording.Driver {
        final int rate,channels; boolean started;
        Driver(int rate,int channels) { this.rate=rate; this.channels=channels; }
        public boolean initialized() { return true; }
        public int sampleRate() { return rate; }
        public int channels() { return channels; }
        public void start() { started=true; }
        public boolean recording() { return started; }
        public int read(short[] buffer,int max) { int n=Math.min(max,2); for(int i=0;i<n;i++) buffer[i]=3; return n; }
        public void stop() { started=false; }
        public void release() { }
    }
    private static final class Scheduler implements NativeAudioRecording.Scheduler {
        final Queue<Runnable> due=new ConcurrentLinkedQueue<>();
        public void post(Runnable action,long delay) { if(delay == 0) due.add(action); }
        public void remove(Runnable action) { due.remove(action); }
        public void close() { }
        void runDue() { Runnable task; while ((task=due.poll()) != null) task.run(); }
    }
}
