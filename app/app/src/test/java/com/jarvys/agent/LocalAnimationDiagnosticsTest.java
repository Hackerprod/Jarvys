package com.jarvys.agent;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class,
        shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
public class LocalAnimationDiagnosticsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private File base;
    private LocalDiagnostics store;
    @Before public void setup() throws Exception {
        base = temporary.newFolder(); store = new LocalDiagnostics(base, clock::get);
    }
    @After public void teardown() { ArtifactOsShadow.reset(); }
    private JSONObject newest() throws Exception {
        return store.snapshot(null, 1, true).getJSONArray("records").getJSONObject(0);
    }
    @Test public void fixedStatusSurvivesReopenAndExactIdRead() throws Exception {
        assertTrue(store.animationRecord(LocalDiagnostics.AnimationStage.DOWNLOAD,
                LocalDiagnostics.AnimationStatus.FAILED, LocalDiagnostics.AnimationId.THINKING));
        JSONObject row = newest();
        assertEquals("animation", row.getString("kind"));
        assertEquals("DOWNLOAD", row.getString("animation_stage"));
        assertEquals("FAILED", row.getString("animation_status"));
        assertEquals("THINKING", row.getString("animation_id"));
        assertEquals("recoverable", row.getString("severity"));
        assertEquals(0, row.getJSONArray("causes").length());
        LocalDiagnostics reopened = new LocalDiagnostics(base, clock::get);
        assertEquals(row.toString(), reopened.snapshot(row.getString("id"), 1, true)
                .getJSONArray("records").getJSONObject(0).toString());
    }
    @Test public void duplicateObservationIsBoundedButStateTransitionPersists() throws Exception {
        assertTrue(store.animationRecord(LocalDiagnostics.AnimationStage.CACHE,
                LocalDiagnostics.AnimationStatus.UNAVAILABLE, LocalDiagnostics.AnimationId.SAD));
        for (int i = 0; i < 100; i++) assertFalse(store.animationRecord(LocalDiagnostics.AnimationStage.CACHE,
                LocalDiagnostics.AnimationStatus.UNAVAILABLE, LocalDiagnostics.AnimationId.SAD));
        assertEquals(1, store.snapshot(null, 10, false).getJSONArray("records").length());
        clock.incrementAndGet();
        assertTrue(store.animationRecord(LocalDiagnostics.AnimationStage.CACHE,
                LocalDiagnostics.AnimationStatus.READY, LocalDiagnostics.AnimationId.SAD));
        assertEquals("READY", newest().getString("animation_status"));
        assertEquals("info", newest().getString("severity"));
        assertFalse(store.animationRecord(LocalDiagnostics.AnimationStage.CACHE,
                LocalDiagnostics.AnimationStatus.UNAVAILABLE, LocalDiagnostics.AnimationId.SAD));
        assertEquals(2, store.snapshot(null, 10, false).getJSONArray("records").length());
    }
    @Test public void invalidCallerEnumsAreRejectedWithoutCreatingStorage() {
        assertFalse(store.animationRecord(null, LocalDiagnostics.AnimationStatus.FAILED, LocalDiagnostics.AnimationId.IDLE));
        assertFalse(store.animationRecord(LocalDiagnostics.AnimationStage.INDEX, null, LocalDiagnostics.AnimationId.IDLE));
        assertFalse(store.animationRecord(LocalDiagnostics.AnimationStage.INDEX, LocalDiagnostics.AnimationStatus.FAILED, null));
        assertFalse(new File(base, LocalDiagnostics.DIRECTORY_NAME).exists());
    }
    @Test public void persistedUntrustedEnumOrExtraTextCannotEscape() throws Exception {
        assertTrue(store.animationRecord(LocalDiagnostics.AnimationStage.FIRST_FRAME,
                LocalDiagnostics.AnimationStatus.READY, LocalDiagnostics.AnimationId.IDLE));
        File slot = new File(new File(base, LocalDiagnostics.DIRECTORY_NAME), "slot-00.json");
        String original = new String(Files.readAllBytes(slot.toPath()), StandardCharsets.UTF_8);
        for (String field : new String[]{"animation_stage", "animation_status", "animation_id"}) {
            JSONObject altered = new JSONObject(original); altered.put(field, "CANARY_SECRET");
            Files.write(slot.toPath(), altered.toString().getBytes(StandardCharsets.UTF_8));
            assertEquals(0, store.snapshot(null, 10, true).getJSONArray("records").length());
        }
        JSONObject altered = new JSONObject(original); altered.put("url", "CANARY_SECRET");
        Files.write(slot.toPath(), altered.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(0, store.snapshot(null, 10, true).getJSONArray("records").length());
    }
    @Test public void successCannotMasqueradeAsFatalOrContainExceptionPayload() throws Exception {
        assertTrue(store.animationRecord(LocalDiagnostics.AnimationStage.IMPORT,
                LocalDiagnostics.AnimationStatus.READY, LocalDiagnostics.AnimationId.WORKING));
        File slot = new File(new File(base, LocalDiagnostics.DIRECTORY_NAME), "slot-00.json");
        JSONObject row = new JSONObject(new String(Files.readAllBytes(slot.toPath()), StandardCharsets.UTF_8));
        row.put("severity", "fatal");
        Files.write(slot.toPath(), row.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(0, store.snapshot(null, 10, true).getJSONArray("records").length());
    }
    @Test public void animationRowsRejectHttpAndCausePayloads() throws Exception {
        assertTrue(store.animationRecord(LocalDiagnostics.AnimationStage.CACHE,
                LocalDiagnostics.AnimationStatus.READY, LocalDiagnostics.AnimationId.WAITING));
        File slot = new File(new File(base, LocalDiagnostics.DIRECTORY_NAME), "slot-00.json");
        String original = new String(Files.readAllBytes(slot.toPath()), StandardCharsets.UTF_8);
        JSONObject row = new JSONObject(original); row.put("http_status", 200);
        Files.write(slot.toPath(), row.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(0, store.snapshot(null, 10, true).getJSONArray("records").length());
        row = new JSONObject(original);
        row.getJSONArray("causes").put(new JSONObject().put("type", "java.lang.IllegalStateException")
                .put("frames", new org.json.JSONArray()));
        Files.write(slot.toPath(), row.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(0, store.snapshot(null, 10, true).getJSONArray("records").length());
    }
    @Test public void failedPersistenceDoesNotMarkObservationAsWritten() throws Exception {
        File directory = new File(base, LocalDiagnostics.DIRECTORY_NAME);
        assertTrue(directory.createNewFile());
        assertFalse(store.animationRecord(LocalDiagnostics.AnimationStage.IMPORT,
                LocalDiagnostics.AnimationStatus.FAILED, LocalDiagnostics.AnimationId.SAD));
        assertTrue(directory.delete());
        assertTrue(store.animationRecord(LocalDiagnostics.AnimationStage.IMPORT,
                LocalDiagnostics.AnimationStatus.FAILED, LocalDiagnostics.AnimationId.SAD));
        assertEquals("FAILED", newest().getString("animation_status"));
    }

}
