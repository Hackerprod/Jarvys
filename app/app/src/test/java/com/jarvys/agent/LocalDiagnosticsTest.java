package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Host filesystem tests. They never install a process handler or terminate the test process. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class, shadows = {ArtifactOsShadow.class, ArtifactOsShadow.Descriptor.class})
public class LocalDiagnosticsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final AtomicLong time = new AtomicLong(1_800_000_000_000L);
    private File base;
    private LocalDiagnostics store;

    @Before public void setUp() throws Exception {
        base = temporary.newFolder("no-backup");
        store = new LocalDiagnostics(base, time::get);
    }
    @After public void tearDown() { ArtifactOsShadow.reset(); }
    private File directory() { return new File(base, LocalDiagnostics.DIRECTORY_NAME); }
    private File slot(int number) { return new File(directory(), String.format(java.util.Locale.ROOT, "slot-%02d.json", number)); }
    private JSONObject newest(boolean frames) throws Exception {
        JSONObject snapshot = store.snapshot(null, 1, frames);
        assertEquals("ok", snapshot.getString("status"));
        assertEquals(1, snapshot.getJSONArray("records").length());
        return snapshot.getJSONArray("records").getJSONObject(0);
    }
    private RuntimeException failure() {
        RuntimeException failure = new IllegalStateException("CANARY_MESSAGE sk-private /private/path secret prompt");
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement(LocalDiagnosticsTest.class.getName(),
                "fixtureMethod", "CANARY_FILENAME", 123)});
        return failure;
    }
    public static void fixtureMethod() { }
    private void overwrite(File file, String value) throws Exception {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
    private JSONObject onDisk(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void capturesActualStructureAndReopensWithoutMessageFilenameOrSuppressedContent() throws Exception {
        RuntimeException failure = failure();
        failure.addSuppressed(new IllegalArgumentException("CANARY_SUPPRESSED"));
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure, false));
        JSONObject row = newest(true);
        String all = row.toString();
        assertFalse(all.contains("CANARY")); assertFalse(all.contains("sk-private"));
        assertFalse(all.contains("/private/path")); assertFalse(all.contains("prompt"));
        assertFalse(all.contains("thread")); assertFalse(all.contains("provider"));
        assertEquals("java.lang.IllegalStateException", row.getJSONArray("causes").getJSONObject(0).getString("type"));
        JSONObject frame = row.getJSONArray("causes").getJSONObject(0).getJSONArray("frames").getJSONObject(0);
        assertEquals(LocalDiagnosticsTest.class.getName(), frame.getString("class"));
        assertEquals("fixtureMethod", frame.getString("method")); assertEquals(123, frame.getInt("line"));
        assertEquals(3, frame.length()); assertFalse(row.has("version_name"));
        assertEquals(BuildConfig.VERSION_CODE, row.getInt("version_code"));
        assertTrue(row.getString("id").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        LocalDiagnostics reopened = new LocalDiagnostics(base, time::get);
        assertEquals(row.toString(), reopened.snapshot(row.getString("id"), 99, true)
                .getJSONArray("records").getJSONObject(0).toString());
        JSONObject summary = newest(false).getJSONArray("causes").getJSONObject(0);
        assertFalse(summary.has("frames")); assertEquals(1, summary.getInt("frame_count"));
        assertEquals(BuildConfig.VERSION_NAME, store.snapshot(null, 1, false).getString("current_version_name"));
    }

    @Test public void emptyCodexReplyStillThrowsAndPersistsOnlyValidatedShapeMetadata() throws Exception {
        String providerBody = "Authorization: Bearer CANARY_TOKEN model text CANARY_MODEL_TEXT";
        ResponseDiagnostics diagnostics = ResponseDiagnostics.fromCodex(
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n");
        ModelReply empty = new ModelReply("", java.util.Collections.emptyList(), providerBody,
                200, "gpt-6-luna", null, diagnostics);
        ModelProviderClient provider = new ModelProviderClient() {
            @Override public ModelReply complete(String systemPrompt, String userPrompt,
                    java.util.List<com.jarvys.agent.device.ScreenData> images,
                    java.util.List<ToolSpec> tools, String sessionId, CancellationToken token) {
                return empty;
            }
            @Override public ModelReply completeConversation(String systemPrompt,
                    java.util.List<ConversationTurn> history, String userPrompt,
                    java.util.List<com.jarvys.agent.device.ScreenData> images,
                    java.util.List<ToolSpec> tools, String sessionId, CancellationToken token) {
                return empty;
            }
        };
        Field singleton = LocalDiagnostics.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        singleton.set(null, store);
        try {
            Context context = ApplicationProvider.getApplicationContext();
            CoreAgentLoop loop = new CoreAgentLoop(new CoreAgentModel(context, provider, "shape-test"),
                    new CoreToolRegistry(java.util.Collections.emptyList()), "instructions", "shape-test");
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> loop.run("CANARY_USER_TEXT", java.util.Collections.emptyList(),
                            CancellationToken.uncancellable(), null));
            assertEquals("Provider returned neither an answer nor a tool call", failure.getMessage());
            JSONObject row = newest(true);
            assertEquals("response_shape", row.getString("kind"));
            JSONObject shape = row.getJSONObject("response_shape");
            assertEquals(1, shape.getJSONObject("sse_event_counts").getInt("response.completed"));
            assertEquals("completed", shape.getString("status"));
            assertEquals(0, shape.getInt("text_length"));
            assertEquals(0, shape.getInt("completed_output_count"));
            assertFalse(row.toString().contains("CANARY"));
            assertFalse(row.toString().contains("gpt-6-luna"));
        } finally {
            singleton.set(null, previous);
        }
    }

    @Test public void responseShapeValidatorRejectsOutOfRangeAndPrivateFields() throws Exception {
        ResponseDiagnostics.CodexShape shape = ResponseDiagnostics.fromCodex(
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n")
                .codexShape;
        assertTrue(store.responseShapeRecord(shape, 0));
        String original = onDisk(slot(0)).toString();
        JSONObject oversized = new JSONObject(original);
        JSONObject metadata = oversized.getJSONObject("response_shape");
        metadata.put("text_length", ResponseDiagnosticsParser.MAX_BODY_CHARACTERS + 1);
        overwrite(slot(0), oversized.toString());
        assertEquals(0, store.snapshot(null, 10, true).getJSONArray("records").length());

        JSONObject withPrivateField = new JSONObject(original);
        withPrivateField.getJSONObject("response_shape").put("model_text", "CANARY_PRIVATE_TEXT");
        overwrite(slot(0), withPrivateField.toString());
        assertEquals(0, store.snapshot(null, 10, true).getJSONArray("records").length());
    }

    @Test public void responseShapeKeepsClosedStatusAndIncompleteReason() throws Exception {
        ResponseDiagnostics.CodexShape shape = ResponseDiagnostics.fromCodex(
                "data: {\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\","
                        + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}}\n\n")
                .codexShape;
        assertTrue(store.responseShapeRecord(shape, 0));
        JSONObject metadata = newest(true).getJSONObject("response_shape");
        assertEquals("incomplete", metadata.getString("status"));
        assertEquals("max_output_tokens", metadata.getString("incomplete_reason"));
    }

    @Test public void unverifiedFrameNamesAndArbitraryTypeNamesNeverEscape() throws Exception {
        RuntimeException failure = failure();
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.jarvys.agent.CANARY_CLASS", "CANARY_METHOD", "file", 9),
                new StackTraceElement(LocalDiagnosticsTest.class.getName(), "CANARY_METHOD", "file", 9),
                new StackTraceElement("external.CANARY_CLASS", "CANARY_METHOD", "file", 9),
                new StackTraceElement("java.lang.String", "substring", "CANARY_FILE", Integer.MAX_VALUE)
        });
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure, false));
        JSONArray frames = newest(true).getJSONArray("causes").getJSONObject(0).getJSONArray("frames");
        assertEquals("app", frames.getJSONObject(0).getString("family"));
        assertEquals("app", frames.getJSONObject(1).getString("family"));
        assertEquals("other", frames.getJSONObject(2).getString("family"));
        assertEquals(-1, frames.getJSONObject(3).getInt("line"));
        assertFalse(frames.toString().contains("CANARY"));
    }

    @Test public void cyclesHostileGettersAndOversizedStacksAreBounded() throws Exception {
        Throwable a = new IllegalStateException("secret"), b = new IllegalArgumentException("secret");
        a.initCause(b); b.initCause(a);
        StackTraceElement[] huge = new StackTraceElement[20_000];
        Arrays.fill(huge, new StackTraceElement(LocalDiagnosticsTest.class.getName(), "fixtureMethod", "secret", 1));
        a.setStackTrace(huge);
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, a, false));
        JSONObject row = newest(true);
        assertTrue(row.getBoolean("truncated"));
        assertEquals(2, row.getJSONArray("causes").length());
        assertEquals(LocalDiagnostics.MAX_FRAMES, row.getJSONArray("causes").getJSONObject(0).getJSONArray("frames").length());
        time.incrementAndGet();
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, new HostileFailure(), false));
        assertTrue(newest(true).getBoolean("truncated"));
    }
    public static final class HostileFailure extends RuntimeException {
        @Override public String getMessage() { throw new AssertionError("Must never inspect message"); }
        @Override public String getLocalizedMessage() { throw new AssertionError("Must never inspect localized message"); }
        @Override public String toString() { throw new AssertionError("Must never stringify throwable"); }
        @Override public synchronized Throwable getCause() { throw new AssertionError("Unavailable cause"); }
        @Override public StackTraceElement[] getStackTrace() { throw new AssertionError("Unavailable stack"); }
    }

    @Test public void byteBudgetTruncatesRatherThanDroppingLargeValidStructure() throws Exception {
        String type = StructuralFrameWithLongButActualDeclaredApplicationClassNameForVerifyingThatRecordByteBudgetsAreEnforcedBeforeAnyBytesAreCommittedToPrivateStorage.class.getName();
        String method = "actualDeclaredMethodWithALongNameThatMustRemainBoundedEvenWhenRepeatedInEveryFrameOfEveryCauseInTheDiagnosticRecord";
        StackTraceElement[] frames = new StackTraceElement[LocalDiagnostics.MAX_FRAMES];
        Arrays.fill(frames, new StackTraceElement(type, method, "CANARY_FILE", 42));
        Throwable root = new IllegalStateException(), cursor = root;
        for (int index = 0; index < LocalDiagnostics.MAX_CAUSES; index++) {
            cursor.setStackTrace(frames);
            Throwable next = new IllegalStateException(); cursor.initCause(next); cursor = next;
        }
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, root, false));
        JSONObject row = newest(true);
        assertTrue(row.getBoolean("truncated"));
        assertTrue(slot(0).length() <= LocalDiagnostics.MAX_RECORD_BYTES);
        assertTrue(row.toString().getBytes(StandardCharsets.UTF_8).length <= LocalDiagnostics.MAX_RECORD_BYTES);
        assertTrue(row.getJSONArray("causes").length() <= LocalDiagnostics.MAX_CAUSES);
    }
    public static final class StructuralFrameWithLongButActualDeclaredApplicationClassNameForVerifyingThatRecordByteBudgetsAreEnforcedBeforeAnyBytesAreCommittedToPrivateStorage {
        public void actualDeclaredMethodWithALongNameThatMustRemainBoundedEvenWhenRepeatedInEveryFrameOfEveryCauseInTheDiagnosticRecord() { }
    }

    @Test public void exactlyThirtyTwoSlotsEvictOldestAndNeverListUnknownDirectoryMembers() throws Exception {
        String firstId = null;
        for (int index = 0; index < LocalDiagnostics.SLOT_COUNT + 8; index++) {
            time.incrementAndGet(); assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
            if (index == 0) firstId = newest(false).getString("id");
        }
        assertEquals(LocalDiagnostics.SLOT_COUNT, directory().list().length);
        for (int index = 0; index < LocalDiagnostics.SLOT_COUNT; index++) {
            assertTrue(slot(index).isFile()); assertTrue(slot(index).length() <= LocalDiagnostics.MAX_RECORD_BYTES);
        }
        JSONObject snapshot = store.snapshot(null, Integer.MAX_VALUE, false);
        assertEquals(LocalDiagnostics.MAX_RESULTS, snapshot.getJSONArray("records").length());
        assertTrue(snapshot.getBoolean("truncated"));
        assertEquals("not_found", store.snapshot(firstId, 1, true).getString("status"));
        assertEquals(time.get(), newest(false).getLong("time_ms"));
        File ignored = new File(directory(), "CANARY_UNRELATED"); overwrite(ignored, "CANARY_BYTES");
        String contents = store.snapshot(null, 10, true).toString();
        assertFalse(contents.contains("CANARY")); assertTrue(ignored.exists());
        assertEquals(1, store.snapshot(null, 0, false).getJSONArray("records").length());
    }

    @Test public void allFutureCorruptSlotsAreReclaimedBeforeCapturingANewFailure() throws Exception {
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        JSONObject corrupt = new JSONObject(new String(Files.readAllBytes(slot(0).toPath()), StandardCharsets.UTF_8));
        corrupt.put("time_ms", Long.MAX_VALUE);
        for (int index = 0; index < LocalDiagnostics.SLOT_COUNT; index++) overwrite(slot(index), corrupt.toString());
        assertEquals(0, store.snapshot(null, 10, false).getJSONArray("records").length());
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        assertEquals(1, directory().list().length);
        assertEquals(time.get(), newest(false).getLong("time_ms"));
    }

    @Test public void expirationIsHiddenOnReadAndPurgedOnNextWrite() throws Exception {
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        String id = newest(true).getString("id");
        time.addAndGet(LocalDiagnostics.RETENTION_MILLIS + 1);
        assertEquals(0, store.snapshot(null, 10, true).getJSONArray("records").length());
        assertEquals("not_found", store.snapshot(id, 1, true).getString("status"));
        assertEquals("Read-only expiry does not claim erasure", 1, directory().list().length);
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        assertEquals(1, directory().list().length);
    }

    @Test public void historicalNumericVersionsRemainReadableWithoutStoredVersionName() throws Exception {
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        JSONObject row = onDisk(slot(0)); row.put("version_code", 1); row.put("flavor", "play");
        overwrite(slot(0), row.toString());
        assertEquals(1, newest(true).getInt("version_code"));
        row.put("version_name", "CANARY_BUILD"); overwrite(slot(0), row.toString());
        assertEquals(0, store.snapshot(null, 1, true).getJSONArray("records").length());
    }

    @Test public void invalidIdsNeverBecomePathsAndReadOnlyEmptyStoreCreatesNothing() throws Exception {
        assertEquals(0, store.snapshot(null, 10, false).getJSONArray("records").length());
        for (String id : new String[]{"../secret", "/tmp/private", "d../x", "D" + String.join("", java.util.Collections.nCopies(32, "a")), "a"}) {
            JSONObject result = store.snapshot(id, 1, true);
            assertEquals("invalid_id", result.getString("status")); assertFalse(result.has("id"));
            assertEquals(0, result.getJSONArray("records").length());
        }
        assertFalse(directory().exists());
    }

    @Test public void corruptOversizedMalformedAndUnknownFieldRecordsNeverEchoTheirBytes() throws Exception {
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        String original = new String(Files.readAllBytes(slot(0).toPath()), StandardCharsets.UTF_8);
        String deep = String.join("", java.util.Collections.nCopies(4000, "["));
        for (String bad : new String[]{"CANARY_CORRUPT", original + "CANARY_TRAILING", deep,
                "{\"schema\":1,\"CANARY_EXTRA\":true}",
                String.join("", java.util.Collections.nCopies(LocalDiagnostics.MAX_RECORD_BYTES + 1, "X"))}) {
            overwrite(slot(0), bad);
            JSONObject snapshot = store.snapshot(null, 10, true);
            assertEquals("ok", snapshot.getString("status")); assertEquals(0, snapshot.getJSONArray("records").length());
            assertFalse(snapshot.toString().contains("CANARY"));
        }
        Files.write(slot(0).toPath(), new byte[]{(byte) 0xff});
        assertEquals(0, store.snapshot(null, 10, true).getJSONArray("records").length());
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        assertEquals(1, directory().list().length);
        assertTrue(slot(0).length() <= LocalDiagnostics.MAX_RECORD_BYTES);
    }

    @Test public void strictSchemaRejectsForgedSymbolsPrimitivesAndSensitiveExtras() throws Exception {
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        String original = new String(Files.readAllBytes(slot(0).toPath()), StandardCharsets.UTF_8);
        for (int variant = 0; variant < 9; variant++) {
            JSONObject row = new JSONObject(original);
            JSONObject cause = row.getJSONArray("causes").getJSONObject(0);
            JSONObject frame = cause.getJSONArray("frames").getJSONObject(0);
            switch (variant) {
                case 0: frame.put("class", "com.jarvys.agent.CANARY_FORGED"); break;
                case 1: frame.put("method", "CANARY_FORGED"); break;
                case 2: frame.put("filename", "CANARY_PRIVATE_PATH"); break;
                case 3: cause.put("type", "java.lang.String"); break;
                case 4: row.put("time_ms", "1800000000000"); break;
                case 5: row.put("sdk", 34.5); break;
                case 6: row.put("http_status", 999); break;
                case 7: row.put("severity", "CANARY_CONTENT"); break;
                case 8: row.put("body", "CANARY_PRIVATE_CONTENT"); break;
                default: throw new AssertionError();
            }
            overwrite(slot(0), row.toString());
            JSONObject output = store.snapshot(null, 10, true);
            assertEquals("variant " + variant, 0, output.getJSONArray("records").length());
            assertFalse(output.toString().contains("CANARY"));
        }
    }

    @Test public void rootDirectorySlotAndStagingSymlinksFailClosedWithoutTouchingTargets() throws Exception {
        File outside = temporary.newFolder("outside"); File sentinel = new File(outside, "sentinel");
        overwrite(sentinel, "CANARY_OUTSIDE");
        File alias = new File(temporary.getRoot(), "alias"); Files.createSymbolicLink(alias.toPath(), base.toPath());
        LocalDiagnostics aliased = new LocalDiagnostics(alias, time::get);
        assertFalse(aliased.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        Files.createSymbolicLink(directory().toPath(), outside.toPath());
        assertFalse(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        assertEquals("unavailable", store.snapshot(null, 10, false).getString("status"));
        Files.delete(directory().toPath()); assertTrue(directory().mkdir());
        Files.createSymbolicLink(slot(0).toPath(), sentinel.toPath());
        assertFalse(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        assertFalse(store.snapshot(null, 10, true).toString().contains("CANARY"));
        Files.delete(slot(0).toPath());
        File pending = new File(directory(), "record.pending"); Files.createSymbolicLink(pending.toPath(), sentinel.toPath());
        assertFalse(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        assertEquals("CANARY_OUTSIDE", new String(Files.readAllBytes(sentinel.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void hardLinkedOrDirectorySlotsAreNeverReadOrMutated() throws Exception {
        assertTrue(directory().mkdir());
        File sentinel = temporary.newFile("outside-record"); overwrite(sentinel, "CANARY_PRIVATE");
        Files.createLink(slot(0).toPath(), sentinel.toPath()); assertTrue(slot(1).mkdir());
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        assertTrue(slot(2).isFile()); assertTrue(slot(1).isDirectory());
        assertEquals("CANARY_PRIVATE", new String(Files.readAllBytes(sentinel.toPath()), StandardCharsets.UTF_8));
        assertFalse(store.snapshot(null, 10, true).toString().contains("CANARY"));
    }

    @Test public void noFollowOpenRejectsSwappedSlotAndStagingTarget() throws Exception {
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        File sentinel = temporary.newFile("sentinel"); overwrite(sentinel, "CANARY_OUTSIDE");
        ArtifactOsShadow.beforeOpen = () -> {
            try { Files.delete(slot(0).toPath()); Files.createSymbolicLink(slot(0).toPath(), sentinel.toPath()); }
            catch (Exception error) { throw new AssertionError(error); }
        };
        assertEquals("unavailable", store.snapshot(null, 10, true).getString("status"));
        Files.delete(slot(0).toPath());
        ArtifactOsShadow.beforeOpen = () -> {
            try { Files.createSymbolicLink(new File(directory(), "record.pending").toPath(), sentinel.toPath()); }
            catch (Exception error) { throw new AssertionError(error); }
        };
        assertFalse(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        assertEquals("CANARY_OUTSIDE", new String(Files.readAllBytes(sentinel.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void leftoversAreBoundedAndFailedWritePreservesPreviousSlot() throws Exception {
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        byte[] old = Files.readAllBytes(slot(0).toPath());
        File pending = new File(directory(), "record.pending"); overwrite(pending, "CANARY_OLD_PARTIAL");
        time.incrementAndGet(); assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        assertFalse(pending.exists()); assertArrayEquals(old, Files.readAllBytes(slot(0).toPath()));
        // Fail after a staged descriptor was created, without invoking or installing a handler.
        java.util.concurrent.atomic.AtomicBoolean reachedStaging = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable[] hook = new Runnable[1];
        hook[0] = () -> {
            if (pending.exists()) {
                reachedStaging.set(true);
                try { Files.delete(pending.toPath()); } catch (Exception failure) { throw new AssertionError(failure); }
            } else ArtifactOsShadow.afterOpen = hook[0];
        };
        ArtifactOsShadow.afterOpen = hook[0];
        assertFalse(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        assertTrue("Failure must occur after the staging descriptor opens", reachedStaging.get());
        assertArrayEquals(old, Files.readAllBytes(slot(0).toPath()));
        assertFalse(pending.exists());
    }

    @Test public void cancellationPrivateScopesAndReentrantCaptureAreSilent() throws Exception {
        assertFalse(store.record(LocalDiagnostics.Origin.RUNTIME, new CancellationException("CANARY"), false));
        assertFalse(store.record(LocalDiagnostics.Origin.RUNTIME, new InterruptedException("CANARY"), false));
        assertFalse(store.record(LocalDiagnostics.Origin.RUNTIME,
                new IllegalStateException(new CancellationException("CANARY")), false));
        try (AgentErrorReporter.AttachmentScope ignored = AgentErrorReporter.suppressForPrivateContent()) {
            assertFalse(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
            assertFalse(store.compatibilityRecord(500));
        }
        assertFalse(directory().exists());
        RuntimeException reentrant = new RuntimeException() {
            @Override public synchronized Throwable getCause() {
                assertFalse(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false)); return null;
            }
        };
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, reentrant, false));
        assertEquals(1, store.snapshot(null, 10, false).getJSONArray("records").length());
    }

    @Test public void compatibilityAcceptsOnlyStatusAndUsesControlledOrigin() throws Exception {
        assertTrue(store.compatibilityRecord(429));
        JSONObject row = newest(true);
        assertEquals("compatibility", row.getString("kind")); assertEquals("RUNTIME", row.getString("origin"));
        assertEquals(429, row.getInt("http_status")); assertEquals(0, row.getJSONArray("causes").length());
        time.incrementAndGet(); assertTrue(store.compatibilityRecord(999));
        assertTrue(newest(true).isNull("http_status"));
    }

    @Test public void contentionDropsCrashImmediatelyAndReaderReturnsBusy() throws Exception {
        Field field = LocalDiagnostics.class.getDeclaredField("STORAGE_LOCK"); field.setAccessible(true);
        ReentrantLock lock = (ReentrantLock) field.get(null);
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try { held.countDown(); release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { lock.unlock(); }
        });
        holder.start(); assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            long start = System.nanoTime();
            assertFalse(store.record(LocalDiagnostics.Origin.UNCAUGHT, failure(), true));
            assertEquals("busy", store.snapshot(null, 10, false).getString("status"));
            assertTrue("Must never wait for a writer lock", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        } finally { release.countDown(); holder.join(5000); }
        assertFalse(holder.isAlive());
    }

    @Test public void handlerAlwaysDelegatesOriginalFailureAndIdempotentlyWrapsWithoutProcessChanges() {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Throwable failure = new AssertionError("CANARY_ORIGINAL");
        AtomicInteger calls = new AtomicInteger(), terminated = new AtomicInteger();
        Thread.UncaughtExceptionHandler delegate = (thread, error) -> {
            assertSame(Thread.currentThread(), thread); assertSame(failure, error); calls.incrementAndGet();
        };
        Thread.UncaughtExceptionHandler handler = LocalDiagnostics.handlerFor(delegate,
                (origin, error, fatal) -> { assertTrue(fatal); throw new AssertionError("writer failed"); },
                terminated::incrementAndGet);
        assertSame(handler, LocalDiagnostics.handlerFor(handler, (o, e, f) -> false, terminated::incrementAndGet));
        handler.uncaughtException(Thread.currentThread(), failure);
        assertEquals(1, calls.get()); assertEquals(0, terminated.get());
        assertSame(original, Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test public void installationDoesNotRewrapAnExternalHandlerOrRetrySuccessfulInstall() {
        Thread.UncaughtExceptionHandler actualJvmHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler[] configured = {(thread, failure) -> { }};
        AtomicInteger writes = new AtomicInteger();
        boolean installed = LocalDiagnostics.installOnce(false, () -> configured[0], next -> {
            configured[0] = next; writes.incrementAndGet();
        }, (origin, failure, fatal) -> true, () -> fail("No termination expected"));
        assertTrue(installed); assertEquals(1, writes.get());
        Thread.UncaughtExceptionHandler ours = configured[0];
        configured[0] = (thread, failure) -> ours.uncaughtException(thread, failure);
        Thread.UncaughtExceptionHandler laterExternal = configured[0];
        assertTrue(LocalDiagnostics.installOnce(installed, () -> configured[0], next -> {
            configured[0] = next; writes.incrementAndGet();
        }, (origin, failure, fatal) -> true, () -> fail("No termination expected")));
        assertSame(laterExternal, configured[0]); assertEquals(1, writes.get());
        assertSame(actualJvmHandler, Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test public void fatalCancellationIsStillCapturedAndProviderStatusUsesOnlyTypedField() throws Exception {
        assertTrue(store.record(LocalDiagnostics.Origin.UNCAUGHT, new CancellationException("CANARY"), true));
        assertEquals("fatal", newest(true).getString("severity"));
        time.incrementAndGet();
        ProviderHttpException typed = new ProviderHttpException("CANARY_BODY", 503, 99, null);
        assertTrue(store.record(LocalDiagnostics.Origin.PROVIDER, new IllegalStateException("CANARY", typed), false));
        assertEquals(503, newest(true).getInt("http_status"));
        assertFalse(newest(true).toString().contains("CANARY"));
    }

    @Test public void historicalMissingSymbolsBecomeFixedFallbacksWithoutDiscardingUsefulMetadata() throws Exception {
        assertTrue(store.record(LocalDiagnostics.Origin.RUNTIME, failure(), false));
        JSONObject row = onDisk(slot(0)); row.put("version_code", 1);
        JSONObject cause = row.getJSONArray("causes").getJSONObject(0);
        cause.put("type", "com.jarvys.agent.PreviousFailureClass");
        cause.getJSONArray("frames").getJSONObject(0).put("class", "com.jarvys.agent.PreviousRuntimeClass");
        overwrite(slot(0), row.toString());
        JSONObject read = newest(true);
        assertEquals("other", read.getJSONArray("causes").getJSONObject(0).getString("type"));
        assertEquals("app", read.getJSONArray("causes").getJSONObject(0).getJSONArray("frames").getJSONObject(0).getString("family"));
        assertFalse(read.toString().contains("Previous"));
    }

    @Test public void missingPriorHandlerUsesOnlyInjectedTerminatorAndDelegateFailuresPropagate() {
        AtomicInteger terminated = new AtomicInteger();
        Thread.UncaughtExceptionHandler handler = LocalDiagnostics.handlerFor(null,
                (origin, error, fatal) -> { throw new OutOfMemoryError("injected"); }, terminated::incrementAndGet);
        handler.uncaughtException(Thread.currentThread(), new RuntimeException());
        assertEquals(1, terminated.get());
        RuntimeException delegateFailure = new RuntimeException("delegate policy");
        Thread.UncaughtExceptionHandler throwing = LocalDiagnostics.handlerFor((thread, error) -> { throw delegateFailure; },
                (origin, error, fatal) -> false, terminated::incrementAndGet);
        assertSame(delegateFailure, assertThrows(RuntimeException.class,
                () -> throwing.uncaughtException(Thread.currentThread(), new RuntimeException())));
        assertEquals(1, terminated.get());
    }
}
