package com.jarvys.agent;

import static org.junit.Assert.*;
import android.app.Application;
import com.jarvys.agent.crew.CrewCoordinationEvidence;
import com.jarvys.agent.workspaces.WorkspaceCoordinationReference;
import com.jarvys.agent.workspaces.WorkspaceCoordinationReply;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import com.jarvys.agent.workspaces.WorkspaceInputIncorporation;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Local metadata journal only. Checkpoint authenticity and native SQL source admission are tested by delivery. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class WorkspaceInputIncorporationLedgerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String WORKSPACE = "62a92262-977e-4c43-8e6a-5640a0c193c3";
    private static final String SOURCE = WorkspaceIdentity.executionSessionId(WORKSPACE);
    private static final String CENTRAL = WorkspaceIdentity.CENTRAL_SESSION;
    private static final String ASSIGNMENT = "0a941f8e-e144-4c2d-b1be-2ae33d31b1bd";
    private static final String REQUEST = "source-request";
    private static final String MISSION = id("jarvys-workspace-mission-v1\u0000" + WORKSPACE + "\u0000" + REQUEST);
    private static final String CYCLE = id("admission-cycle");
    private static final String FIRST_CYCLE = id("first-incorporation-cycle");
    private static final String HASH_A = String.join("", Collections.nCopies(64, "a"));
    private static final String HASH_B = String.join("", Collections.nCopies(64, "b"));
    private File files;
    private LocalRunStore store;

    @Before public void setup() {
        files = temporary.getRoot(); store = new LocalRunStore(files);
        store.appendConversationMessage(SOURCE, "user", "Synthetic execution chat");
        store.appendConversationMessage(CENTRAL, "user", "Synthetic Central chat");
    }
    private static String id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
    private File ledger(String session) { return new File(files, "jarvys/conversations/" + session + ".jsonl"); }
    private byte[] bytes(String session) throws Exception { return Files.readAllBytes(ledger(session).toPath()); }
    private void append(String session, String value) throws Exception {
        Files.write(ledger(session).toPath(), value.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }
    private void appendRow(String session, JSONObject row) throws Exception { append(session, row.toString() + "\n"); }
    private void deny() { throw new CancellationException("Synthetic final admission revoked"); }
    private WorkspaceCoordinationReply reply(boolean started) {
        CrewCoordinationEvidence evidence = CrewCoordinationEvidence.create(SOURCE, MISSION, "bot-1", CYCLE,
                "question-1", "QUESTION", "Exact untrusted question", Collections.emptyList(), 100L);
        store.appendCrewCoordinationEvidence(evidence);
        WorkspaceCoordinationReference ref = WorkspaceCoordinationReference.create(evidence, CENTRAL, WORKSPACE, ASSIGNMENT, REQUEST, 7L);
        store.appendWorkspaceCoordinationReferenceIfAbsent(ref, () -> { });
        store.appendWorkspaceCoordinationNoticeIfAbsent(ref, () -> { });
        WorkspaceCoordinationReply reply = store.prepareWorkspaceCoordinationReply(WorkspaceCoordinationReply.create(
                ref, "reply-1", "  Exact answer\n\ud83d\ude03  ", "central-request", "Original user request", 200L), () -> { });
        return started ? store.markWorkspaceCoordinationReplyStarted(reply, () -> { }) : reply;
    }
    private WorkspaceInputIncorporation receipt(WorkspaceCoordinationReply reply) {
        return WorkspaceInputIncorporation.create(WorkspaceInputIncorporation.SourceKind.REPLY, reply.replyId,
                reply.reference.referenceId, reply.mailboxMessageId, CENTRAL, WORKSPACE, SOURCE, ASSIGNMENT, REQUEST,
                MISSION, "bot-1", CYCLE, FIRST_CYCLE, reply.payloadSha256, HASH_A, "INBOX", HASH_B, HASH_A, 300L, 400L);
    }
    private WorkspaceInputIncorporation nativeReceipt(String sourceId) {
        return WorkspaceInputIncorporation.create(WorkspaceInputIncorporation.SourceKind.NATIVE_CORRECT, sourceId,
                sourceId, id("mailbox-" + sourceId), CENTRAL, WORKSPACE, SOURCE, ASSIGNMENT, REQUEST,
                MISSION, "bot-1", CYCLE, FIRST_CYCLE, HASH_A, HASH_B, "USER", HASH_A, HASH_B, 300L, 400L);
    }
    private WorkspaceInputIncorporation changed(WorkspaceInputIncorporation value, String field, Object next) throws Exception {
        return WorkspaceInputIncorporation.fromJsonStrict(value.toJson().put(field, next));
    }
    private WorkspaceInputIncorporation read(WorkspaceInputIncorporation value) {
        return store.readWorkspaceInputIncorporation(CENTRAL, value.sourceKind, value.sourceId);
    }

    @Test public void strictMetadataRoundTripHasNoSourceBodyOrDispatchState() throws Exception {
        WorkspaceInputIncorporation original = receipt(reply(true));
        WorkspaceInputIncorporation decoded = WorkspaceInputIncorporation.fromJsonStrict(original.toJson());
        assertTrue(original.sameBinding(decoded));
        assertEquals(original.receiptId, decoded.receiptId);
        for (String forbidden : new String[] {"role", "content", "text", "state", "originalUserRequest", "provenance"})
            assertFalse(decoded.toJson().has(forbidden));
        assertFalse(decoded.toJson().toString().contains("Exact answer"));
        for (String field : new String[] {"role", "content", "text", "unexpected"}) {
            JSONObject extra = original.toJson().put(field, "injected");
            assertThrows(IllegalArgumentException.class, () -> WorkspaceInputIncorporation.fromJsonStrict(extra));
        }
        JSONObject absent = original.toJson(); absent.remove("transportSha256");
        assertThrows(IllegalArgumentException.class, () -> WorkspaceInputIncorporation.fromJsonStrict(absent));
        for (String field : new String[] {"sourcePayloadSha256", "deliveredContentSha256", "transportSha256", "checkpointPayloadSha256"}) {
            JSONObject malformed = original.toJson().put(field, "not-a-digest");
            assertThrows(IllegalArgumentException.class, () -> WorkspaceInputIncorporation.fromJsonStrict(malformed));
        }
        JSONObject forgedId = original.toJson().put("receiptId", WorkspaceInputIncorporation.receiptId(
                WorkspaceInputIncorporation.SourceKind.NATIVE_CORRECT, "other"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceInputIncorporation.fromJsonStrict(forgedId));
        JSONObject schema = original.toJson().put("workspaceInputIncorporationSchemaVersion", 2);
        assertThrows(IllegalArgumentException.class, () -> WorkspaceInputIncorporation.fromJsonStrict(schema));
        JSONObject coerced = original.toJson().put("recordedAtMillis", "400");
        assertThrows(IllegalArgumentException.class, () -> WorkspaceInputIncorporation.fromJsonStrict(coerced));
        JSONObject fractional = original.toJson().put("checkpointSavedAtMillis", 300.5);
        assertThrows(IllegalArgumentException.class, () -> WorkspaceInputIncorporation.fromJsonStrict(fractional));
    }

    @Test public void independentReceiptCanPrecedeAcceptedAndNeverChangesDispatchState() {
        WorkspaceCoordinationReply reply = reply(true); WorkspaceInputIncorporation original = receipt(reply);
        store.appendWorkspaceInputIncorporationIfAbsent(original, () -> { });
        assertEquals(WorkspaceCoordinationReply.State.STARTED,
                store.readWorkspaceCoordinationReply(CENTRAL, reply.reference.referenceId).state);
        assertEquals(original.receiptId, new LocalRunStore(files).readWorkspaceInputIncorporation(
                CENTRAL, original.sourceKind, original.sourceId).receiptId);
        store.markWorkspaceCoordinationReplyAccepted(reply, () -> { });
        assertEquals(original.recordedAtMillis, read(original).recordedAtMillis);
        assertEquals(WorkspaceCoordinationReply.State.ACCEPTED_LOCAL,
                store.readWorkspaceCoordinationReply(CENTRAL, reply.reference.referenceId).state);
        store.requireWorkspaceInputIncorporationDisclosureLive(original);
    }

    @Test public void directReplyIdentityLookupAppliesTransitionsAndValidatesEntireTail() throws Exception {
        WorkspaceCoordinationReply prepared = reply(false);
        assertEquals(WorkspaceCoordinationReply.State.PREPARED,
                store.readWorkspaceCoordinationReplyById(CENTRAL, prepared.replyId).state);
        WorkspaceCoordinationReply started = store.markWorkspaceCoordinationReplyStarted(prepared, () -> { });
        assertEquals(WorkspaceCoordinationReply.State.STARTED,
                store.readWorkspaceCoordinationReplyById(CENTRAL, prepared.replyId).state);
        store.markWorkspaceCoordinationReplyAccepted(started, () -> { });
        WorkspaceCoordinationReply found = new LocalRunStore(files).readWorkspaceCoordinationReplyById(CENTRAL, prepared.replyId);
        assertTrue(found.samePayload(prepared)); assertEquals(WorkspaceCoordinationReply.State.ACCEPTED_LOCAL, found.state);
        assertNull(store.readWorkspaceCoordinationReplyById(CENTRAL, "workspace-coordination-reply-" + HASH_A));
        assertThrows(IllegalArgumentException.class, () -> store.readWorkspaceCoordinationReplyById(SOURCE, prepared.replyId));
        assertThrows(IllegalArgumentException.class, () -> store.readWorkspaceCoordinationReplyById(CENTRAL, "bad-id"));
        append(CENTRAL, "broken-tail\n");
        assertThrows(IllegalStateException.class, () -> store.readWorkspaceCoordinationReplyById(CENTRAL, prepared.replyId));
        assertThrows(IllegalStateException.class, () -> store.readWorkspaceCoordinationReplyById(CENTRAL,
                "workspace-coordination-reply-" + HASH_A));
    }

    @Test public void preparedOrMissingReplySourceCannotGainPositiveReceipt() throws Exception {
        WorkspaceCoordinationReply prepared = reply(false); WorkspaceInputIncorporation value = receipt(prepared);
        byte[] before = bytes(CENTRAL);
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertArrayEquals(before, bytes(CENTRAL));
        assertNull(read(value));
        // A hand-written row also cannot evade the common causal scanner.
        appendRow(CENTRAL, value.toJson());
        assertThrows(IllegalStateException.class, () -> store.listWorkspaceResultNotices(CENTRAL, 1, null));
        assertThrows(IllegalStateException.class, () -> read(value));
    }

    @Test public void missingReplyIntentCannotBeReplacedByMetadata() throws Exception {
        WorkspaceInputIncorporation value = receipt(reply(true));
        Files.write(ledger(CENTRAL).toPath(), "{}\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        appendRow(CENTRAL, value.toJson());
        assertThrows(IllegalStateException.class, () -> store.listWorkspaceCoordinationNotices(CENTRAL, 1, null));
    }

    @Test public void laterCheckpointPreservesFirstReceiptAndBothCyclesAcrossReopen() throws Exception {
        WorkspaceInputIncorporation original = receipt(reply(true));
        store.appendWorkspaceInputIncorporationIfAbsent(original, () -> { }); byte[] before = bytes(CENTRAL);
        WorkspaceInputIncorporation later = changed(changed(changed(original, "checkpointPayloadSha256", HASH_B),
                "checkpointSavedAtMillis", 700L), "recordedAtMillis", 800L);
        WorkspaceInputIncorporation repeated = new LocalRunStore(files).appendWorkspaceInputIncorporationIfAbsent(later, this::deny);
        assertEquals(original.checkpointPayloadSha256, repeated.checkpointPayloadSha256);
        assertEquals(original.checkpointSavedAtMillis, repeated.checkpointSavedAtMillis);
        assertEquals(original.recordedAtMillis, repeated.recordedAtMillis);
        assertEquals(CYCLE, repeated.admissionCycleId); assertEquals(FIRST_CYCLE, repeated.firstIncorporationCycleId);
        assertArrayEquals(before, bytes(CENTRAL));
        assertThrows(IllegalStateException.class, () -> store.requireWorkspaceInputIncorporationDisclosureLive(later));
        // Even duplicate historical rows must retain the first observation when scanned.
        appendRow(CENTRAL, later.toJson());
        assertEquals(original.recordedAtMillis, read(original).recordedAtMillis);
        assertEquals(1, store.listWorkspaceInputIncorporations(CENTRAL, 10, null).size());
    }

    @Test public void changedMailboxContentCycleAndScopeAreConflictsNotNewIdentities() throws Exception {
        WorkspaceInputIncorporation original = nativeReceipt("control-1");
        store.appendWorkspaceInputIncorporationIfAbsent(original, () -> { }); byte[] before = bytes(CENTRAL);
        for (String field : new String[] {"mailboxMessageId", "admissionCycleId", "firstIncorporationCycleId", "assignmentId"}) {
            WorkspaceInputIncorporation conflicting = changed(original, field, id("changed-" + field));
            assertEquals(original.receiptId, conflicting.receiptId);
            assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(conflicting, () -> { }));
        }
        for (String field : new String[] {"sourcePayloadSha256", "deliveredContentSha256", "transportSha256"}) {
            WorkspaceInputIncorporation conflicting = changed(original, field, HASH_A.equals(original.toJson().getString(field)) ? HASH_B : HASH_A);
            assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(conflicting, () -> { }));
        }
        WorkspaceInputIncorporation wrongBot = changed(original, "botId", "bot-2");
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(wrongBot, () -> { }));
        assertArrayEquals(before, bytes(CENTRAL));
        appendRow(CENTRAL, wrongBot.toJson());
        assertThrows(IllegalStateException.class, () -> read(original));
        assertThrows(IllegalStateException.class, () -> store.listWorkspaceResultNotices(CENTRAL, 1, null));
    }

    @Test public void sourceSpecificTransportAndCanonicalOwnershipAreStrict() throws Exception {
        WorkspaceInputIncorporation reply = receipt(reply(true)), nativeInput = nativeReceipt("control");
        for (String field : new String[] {"ownerCentralSessionId", "workspaceId", "executionSessionId", "missionId", "requestId"}) {
            JSONObject wrong = reply.toJson().put(field, "foreign");
            assertThrows(IllegalArgumentException.class, () -> WorkspaceInputIncorporation.fromJsonStrict(wrong));
        }
        assertThrows(IllegalArgumentException.class, () -> changed(reply, "transportKind", "USER"));
        assertThrows(IllegalArgumentException.class, () -> changed(nativeInput, "transportKind", "INBOX"));
        assertThrows(IllegalArgumentException.class, () -> changed(nativeInput, "sourceReferenceId", "other"));
        assertThrows(IllegalArgumentException.class, () -> changed(nativeInput, "sourceId", String.join("", Collections.nCopies(161, "a"))));
        assertThrows(IllegalArgumentException.class, () -> store.readWorkspaceInputIncorporation(SOURCE, reply.sourceKind, reply.sourceId));
        assertThrows(IllegalArgumentException.class, () -> store.listWorkspaceInputIncorporations(SOURCE, 1, null));
        WorkspaceInputIncorporation wrongBot = changed(reply, "botId", "bot-2");
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(wrongBot, () -> { }));
    }

    @Test public void mailboxCannotBeReclaimedByAnotherSourceAndTransportCannotChange() throws Exception {
        WorkspaceInputIncorporation first = nativeReceipt("control-1");
        store.appendWorkspaceInputIncorporationIfAbsent(first, () -> { }); byte[] before = bytes(CENTRAL);
        WorkspaceInputIncorporation second = changed(nativeReceipt("control-2"), "mailboxMessageId", first.mailboxMessageId);
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(second, () -> { }));
        assertArrayEquals(before, bytes(CENTRAL));
        WorkspaceInputIncorporation reply = receipt(reply(true));
        store.appendWorkspaceInputIncorporationIfAbsent(reply, () -> { });
        WorkspaceInputIncorporation differentTransport = changed(reply, "transportKind", "ASK_CHIEF");
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(differentTransport, () -> { }));
        appendRow(CENTRAL, second.toJson());
        assertThrows(IllegalStateException.class, () -> store.listWorkspaceCoordinationNotices(CENTRAL, 1, null));
    }

    @Test public void validForeignWorkspaceCannotReuseExactSourceClaim() throws Exception {
        WorkspaceInputIncorporation first = nativeReceipt("control-1");
        store.appendWorkspaceInputIncorporationIfAbsent(first, () -> { });
        String foreignWorkspace = id("foreign-workspace"), foreignSession = WorkspaceIdentity.executionSessionId(foreignWorkspace);
        store.appendConversationMessage(foreignSession, "user", "Other execution chat");
        WorkspaceInputIncorporation foreign = WorkspaceInputIncorporation.fromJsonStrict(first.toJson()
                .put("workspaceId", foreignWorkspace).put("executionSessionId", foreignSession)
                .put("missionId", id("jarvys-workspace-mission-v1\u0000" + foreignWorkspace + "\u0000" + REQUEST)));
        assertEquals(first.receiptId, foreign.receiptId);
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(foreign, () -> { }));
    }

    @Test public void deletedOrMissingExecutionLedgerNeverCreatesOrDisclosesReceipt() throws Exception {
        WorkspaceInputIncorporation value = nativeReceipt("control-1");
        store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { });
        assertTrue(store.deleteConversation(SOURCE)); byte[] before = bytes(CENTRAL);
        assertThrows(IllegalStateException.class, () -> read(value));
        assertThrows(IllegalStateException.class, () -> store.listWorkspaceInputIncorporations(CENTRAL, 1, null));
        assertThrows(IllegalStateException.class, () -> store.requireWorkspaceInputIncorporationDisclosureLive(value));
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertFalse(ledger(SOURCE).exists()); assertArrayEquals(before, bytes(CENTRAL));
    }

    @Test public void missingNativeExecutionLedgerCannotBeCreatedByNewReceipt() throws Exception {
        WorkspaceInputIncorporation value = nativeReceipt("control-1");
        assertTrue(ledger(SOURCE).delete()); byte[] before = bytes(CENTRAL);
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertFalse(ledger(SOURCE).exists()); assertArrayEquals(before, bytes(CENTRAL));
    }

    @Test public void missingAndDeletedCentralAreNeverCreatedByReceipt() throws Exception {
        WorkspaceInputIncorporation value = nativeReceipt("control-1"); assertTrue(ledger(CENTRAL).delete());
        assertNull(read(value));
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertFalse(ledger(CENTRAL).exists());
        assertTrue(store.deleteConversation(CENTRAL));
        assertThrows(IllegalStateException.class, () -> read(value));
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertFalse(ledger(CENTRAL).exists());
    }

    @Test public void finalAdmissionAndDeletionBoundariesCannotCreateFalseSuccess() throws Exception {
        WorkspaceInputIncorporation value = nativeReceipt("control-1"); byte[] before = bytes(CENTRAL);
        assertThrows(CancellationException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, this::deny));
        assertArrayEquals(before, bytes(CENTRAL)); assertNull(read(value));
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value,
                () -> store.deleteConversation(SOURCE)));
        assertArrayEquals(before, bytes(CENTRAL)); assertFalse(ledger(SOURCE).exists());
    }

    @Test public void centralDeletionAtAdmissionCannotResurrectIt() {
        WorkspaceInputIncorporation value = nativeReceipt("control-1");
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value,
                () -> store.deleteConversation(CENTRAL)));
        assertFalse(ledger(CENTRAL).exists());
    }

    @Test public void removedReplySourceReferencePreventsPositiveReadAndNewAppend() throws Exception {
        WorkspaceInputIncorporation value = receipt(reply(true));
        store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { });
        Files.write(ledger(SOURCE).toPath(), "{}\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> read(value));
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
    }

    @Test public void malformedAndTornReceiptsFailOldAndNewScannersWithoutRepair() throws Exception {
        WorkspaceInputIncorporation value = nativeReceipt("control-1");
        store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { });
        append(CENTRAL, "{\"type\":\"workspace_input_incorporation\""); byte[] before = bytes(CENTRAL);
        assertThrows(IllegalStateException.class, () -> read(value));
        assertThrows(IllegalStateException.class, () -> store.listWorkspaceResultNotices(CENTRAL, 1, null));
        assertThrows(IllegalStateException.class, () -> store.listWorkspaceCoordinationNotices(CENTRAL, 1, null));
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertArrayEquals(before, bytes(CENTRAL));
    }

    @Test public void corruptNativeExecutionSourceAndDuplicateJsonFieldsFailClosed() throws Exception {
        WorkspaceInputIncorporation value = nativeReceipt("control-1");
        append(SOURCE, "not-json\n"); byte[] before = bytes(CENTRAL);
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertArrayEquals(before, bytes(CENTRAL));
        append(CENTRAL, "{\"type\":\"safe\",\"type\":\"workspace_input_incorporation\"}\n");
        assertThrows(IllegalStateException.class, () -> store.listWorkspaceResultNotices(CENTRAL, 1, null));
    }

    @Test public void symlinkedExecutionLedgerCannotGainReceiptOrModifyTarget() throws Exception {
        WorkspaceInputIncorporation value = nativeReceipt("control-1");
        File foreign = temporary.newFile("foreign-ledger.jsonl");
        Files.write(foreign.toPath(), "{}\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(ledger(SOURCE).delete()); Files.createSymbolicLink(ledger(SOURCE).toPath(), foreign.toPath());
        assertThrows(IllegalArgumentException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertEquals("{}\n", new String(Files.readAllBytes(foreign.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void unknownReceiptSchemaAndMisplacedOwnerFailOldScanners() throws Exception {
        WorkspaceInputIncorporation value = nativeReceipt("control-1");
        appendRow(SOURCE, value.toJson());
        assertThrows(IllegalStateException.class, () -> store.listCrewResultEvidence(SOURCE, 1, null));
        assertThrows(IllegalStateException.class, () -> store.listCrewCoordinationEvidence(SOURCE, 1, null));
        appendRow(CENTRAL, value.toJson().put("workspaceInputIncorporationSchemaVersion", 999));
        assertThrows(IllegalStateException.class, () -> store.listWorkspaceResultNotices(CENTRAL, 1, null));
        assertThrows(IllegalStateException.class, () -> store.listWorkspaceCoordinationNotices(CENTRAL, 1, null));
    }

    @Test public void validAndMalformedReceiptRowsAreInvisibleToEveryHistoryPath() throws Exception {
        store.appendWorkspaceInputIncorporationIfAbsent(nativeReceipt("control-1"), () -> { });
        assertEquals(1, store.readConversationMessages(CENTRAL).size());
        assertTrue(store.readModelTranscriptRows(CENTRAL).isEmpty());
        assertEquals(1, store.readConversationTimeline(CENTRAL).size());
        for (JSONObject injected : new JSONObject[] {
                new JSONObject().put("type", "workspace_input_future").put("role", "user").put("content", "HIDDEN_BODY"),
                new JSONObject().put("type", "model_tool_result").put("workspaceInputIncorporationSchemaVersion", 999).put("content", "HIDDEN_BODY"),
                new JSONObject().put("type", "compaction").put("receiptId", "forged").put("summary", "HIDDEN_BODY").put("firstKept", 1),
                new JSONObject().put("role", "assistant").put("receiptId", "forged").put("content", "HIDDEN_BODY") }) appendRow(CENTRAL, injected);
        assertEquals(1, store.readConversationMessages(CENTRAL).size());
        assertTrue(store.readModelTranscriptRows(CENTRAL).isEmpty());
        assertEquals(1, store.readActiveConversationMessages(CENTRAL).size());
        assertEquals(1, store.readConversationTimeline(CENTRAL).size());
        assertEquals(1, store.loadConversationContext(CENTRAL).size());
        assertFalse(store.buildReflectionPayload(CENTRAL).text.contains("HIDDEN_BODY"));
        assertFalse(store.readConversationContext(CENTRAL).contains("HIDDEN_BODY"));
        assertFalse(store.hasPendingCompactionReflection(CENTRAL));
    }

    @Test public void boundedPagesAreImmutableAndDoNotLeakIntoOldResultOrCoordinationLists() {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            WorkspaceInputIncorporation value = nativeReceipt("control-" + i); expected.add(value.receiptId);
            store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { });
        }
        Collections.sort(expected); List<String> observed = new ArrayList<>(); String cursor = null;
        while (true) {
            List<WorkspaceInputIncorporation> page = store.listWorkspaceInputIncorporations(CENTRAL, 2, cursor);
            assertTrue(page.size() <= 2); if (page.isEmpty()) break;
            for (WorkspaceInputIncorporation value : page) observed.add(value.receiptId);
            cursor = page.get(page.size() - 1).receiptId;
            assertThrows(UnsupportedOperationException.class, () -> page.add(nativeReceipt("forbidden")));
        }
        assertEquals(expected, observed);
        assertTrue(store.listWorkspaceResultNotices(CENTRAL, 10, null).isEmpty());
        assertTrue(store.listWorkspaceCoordinationNotices(CENTRAL, 10, null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.listWorkspaceInputIncorporations(CENTRAL, 0, null));
        assertThrows(IllegalArgumentException.class, () -> store.listWorkspaceInputIncorporations(CENTRAL, 101, null));
        assertThrows(IllegalArgumentException.class, () -> store.listWorkspaceInputIncorporations(CENTRAL, 1, "bad"));
    }

    @Test public void concurrentStoresAppendOneReceiptWithoutDispatchOrReplay() throws Exception {
        WorkspaceInputIncorporation value = nativeReceipt("control-1");
        AtomicInteger commits = new AtomicInteger(); AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1); List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread thread = new Thread(() -> {
                try {
                    LocalRunStore instance = new LocalRunStore(files); start.await();
                    instance.appendWorkspaceInputIncorporationIfAbsent(value, commits::incrementAndGet);
                } catch (Throwable failure) { error.compareAndSet(null, failure); }
            });
            threads.add(thread); thread.start();
        }
        start.countDown(); for (Thread thread : threads) { thread.join(10000); assertFalse(thread.isAlive()); }
        if (error.get() != null) throw new AssertionError(error.get());
        assertEquals(1, commits.get()); assertEquals(1, store.listWorkspaceInputIncorporations(CENTRAL, 100, null).size());
    }
}
