package com.jarvys.agent;

import static org.junit.Assert.*;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
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
        base = temporary.newFolder();
        store = new LocalDiagnostics(base, clock::get);
    }

    @After public void teardown() { ArtifactOsShadow.reset(); }

    @Test public void animationEventsNeverCreateDiagnosticRecords() throws Exception {
        for (LocalDiagnostics.AnimationId id : LocalDiagnostics.AnimationId.values()) {
            for (LocalDiagnostics.AnimationStage stage : LocalDiagnostics.AnimationStage.values()) {
                for (LocalDiagnostics.AnimationStatus status : LocalDiagnostics.AnimationStatus.values()) {
                    assertFalse(store.animationRecord(stage, status, id));
                    LocalDiagnostics.animation(ApplicationProvider.getApplicationContext(), stage, status, id);
                }
            }
        }
        assertEquals(0, store.snapshot(null, 10, false).getJSONArray("records").length());
        assertFalse(new File(base, LocalDiagnostics.DIRECTORY_NAME).exists());
    }

    @Test public void previouslyStoredAnimationRecordsAreNoLongerReturned() throws Exception {
        File directory = new File(base, LocalDiagnostics.DIRECTORY_NAME);
        assertTrue(directory.mkdir());
        JSONObject legacy = new JSONObject().put("schema", 1)
                .put("id", "00000000-0000-0000-0000-000000000001")
                .put("time_ms", clock.get()).put("version_code", BuildConfig.VERSION_CODE)
                .put("flavor", BuildConfig.FLAVOR).put("sdk", 34).put("origin", "RUNTIME")
                .put("severity", "recoverable").put("kind", "animation")
                .put("http_status", JSONObject.NULL).put("causes", new JSONArray())
                .put("truncated", false).put("animation_stage", "DOWNLOAD")
                .put("animation_status", "FAILED").put("animation_id", "THINKING");
        Files.write(new File(directory, "slot-00.json").toPath(),
                legacy.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(0, store.snapshot(null, 1, true).getJSONArray("records").length());
    }
}
