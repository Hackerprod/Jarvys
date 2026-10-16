package com.jarvys.agent;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.crew.CrewCoordinationEvidence;
import com.jarvys.agent.workspaces.WorkspaceCoordinationReference;
import com.jarvys.agent.workspaces.WorkspaceCoordinationReply;
import com.jarvys.agent.workspaces.WorkspaceIdentity;
import com.jarvys.agent.workspaces.WorkspaceInputIncorporation;
import java.io.File;
import java.nio.charset.StandardCharsets;
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
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Room-backed incorporation receipts. Checkpoint authenticity and native SQL source admission are tested by delivery. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class WorkspaceInputIncorporationLedgerTest {
    @Rule public final ChatTestStore chatTestStore = new ChatTestStore();
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
    private Context context;
    private LocalRunStore store;
    private boolean centralCreated;

    @Before public void setup() {
        Context base = ApplicationProvider.getApplicationContext();
        File databaseFiles = chatTestStore.newFilesDirectory();
        context = new ContextWrapper(base) {
            @Override public File getFilesDir() { return databaseFiles; }
            @Override public Context getApplicationContext() { return this; }
        };
        store = chatTestStore.localRunStore(databaseFiles);
        store.appendConversationMessage(SOURCE, "user", "Synthetic execution chat");
    }
    private static String id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
    private LocalRunStore reopened() { return new LocalRunStore(context); }
    private List<String> inputState() {
        List<String> state = new ArrayList<>();
        for (WorkspaceInputIncorporation value : store.listWorkspaceInputIncorporations(CENTRAL, 100, null))
            state.add(value.toJson().toString());
        return state;
    }
    private void ensureCentral() {
        if (!centralCreated) {
            store.appendConversationMessage(CENTRAL, "user", "Synthetic Central chat");
            centralCreated = true;
        }
    }
    private void deny() { throw new CancellationException("Synthetic final admission revoked"); }
    private WorkspaceCoordinationReply reply(boolean started) {
        ensureCentral();
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
    private WorkspaceInputIncorporation nativeReceipt(String sourceId) { return nativeReceipt(sourceId, true); }
    private WorkspaceInputIncorporation nativeReceipt(String sourceId, boolean createCentral) {
        if (createCentral) ensureCentral();
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
        assertEquals(original.receiptId, reopened().readWorkspaceInputIncorporation(
                CENTRAL, original.sourceKind, original.sourceId).receiptId);
        store.markWorkspaceCoordinationReplyAccepted(reply, () -> { });
        assertEquals(original.recordedAtMillis, read(original).recordedAtMillis);
        assertEquals(WorkspaceCoordinationReply.State.ACCEPTED_LOCAL,
                store.readWorkspaceCoordinationReply(CENTRAL, reply.reference.referenceId).state);
        store.requireWorkspaceInputIncorporationDisclosureLive(original);
    }

    @Test public void directReplyIdentityLookupAppliesTransitionsAndValidatesPersistedRows() throws Exception {
        WorkspaceCoordinationReply prepared = reply(false);
        assertEquals(WorkspaceCoordinationReply.State.PREPARED,
                store.readWorkspaceCoordinationReplyById(CENTRAL, prepared.replyId).state);
        WorkspaceCoordinationReply started = store.markWorkspaceCoordinationReplyStarted(prepared, () -> { });
        assertEquals(WorkspaceCoordinationReply.State.STARTED,
                store.readWorkspaceCoordinationReplyById(CENTRAL, prepared.replyId).state);
        store.markWorkspaceCoordinationReplyAccepted(started, () -> { });
        WorkspaceCoordinationReply found = reopened().readWorkspaceCoordinationReplyById(CENTRAL, prepared.replyId);
        assertTrue(found.samePayload(prepared)); assertEquals(WorkspaceCoordinationReply.State.ACCEPTED_LOCAL, found.state);
        assertNull(store.readWorkspaceCoordinationReplyById(CENTRAL, "workspace-coordination-reply-" + HASH_A));
        assertThrows(IllegalArgumentException.class, () -> store.readWorkspaceCoordinationReplyById(SOURCE, prepared.replyId));
        assertThrows(IllegalArgumentException.class, () -> store.readWorkspaceCoordinationReplyById(CENTRAL, "bad-id"));
    }

    @Test public void preparedOrMissingReplySourceCannotGainPositiveReceipt() throws Exception {
        WorkspaceCoordinationReply prepared = reply(false); WorkspaceInputIncorporation value = receipt(prepared);
        List<String> before = inputState();
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertEquals(before, inputState());
        assertNull(read(value));
        assertTrue(store.listWorkspaceInputIncorporations(CENTRAL, 10, null).isEmpty());
        assertEquals(1, store.listWorkspaceCoordinationNotices(CENTRAL, 10, null).size());
    }

    @Test public void missingReplyIntentCannotBeReplacedByMetadata() {
        ensureCentral();
        CrewCoordinationEvidence evidence = CrewCoordinationEvidence.create(SOURCE, MISSION, "bot-1", CYCLE,
                "question-1", "QUESTION", "Exact untrusted question", Collections.emptyList(), 100L);
        store.appendCrewCoordinationEvidence(evidence);
        WorkspaceCoordinationReference ref = WorkspaceCoordinationReference.create(evidence, CENTRAL, WORKSPACE,
                ASSIGNMENT, REQUEST, 7L);
        store.appendWorkspaceCoordinationReferenceIfAbsent(ref, () -> { });
        store.appendWorkspaceCoordinationNoticeIfAbsent(ref, () -> { });
        WorkspaceCoordinationReply proposal = WorkspaceCoordinationReply.create(ref, "reply-1", "exact answer",
                "central-request", "Original user request", 200L);
        WorkspaceInputIncorporation value = receipt(proposal);
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertNull(read(value));
        assertTrue(store.listWorkspaceInputIncorporations(CENTRAL, 10, null).isEmpty());
        assertEquals(1, store.listWorkspaceCoordinationNotices(CENTRAL, 10, null).size());
    }


    @Test public void laterCheckpointPreservesFirstReceiptAndBothCyclesAcrossReopen() throws Exception {
        WorkspaceInputIncorporation original = receipt(reply(true));
        store.appendWorkspaceInputIncorporationIfAbsent(original, () -> { }); List<String> before = inputState();
        WorkspaceInputIncorporation later = changed(changed(changed(original, "checkpointPayloadSha256", HASH_B),
                "checkpointSavedAtMillis", 700L), "recordedAtMillis", 800L);
        WorkspaceInputIncorporation repeated = reopened().appendWorkspaceInputIncorporationIfAbsent(later, this::deny);
        assertEquals(original.checkpointPayloadSha256, repeated.checkpointPayloadSha256);
        assertEquals(original.checkpointSavedAtMillis, repeated.checkpointSavedAtMillis);
        assertEquals(original.recordedAtMillis, repeated.recordedAtMillis);
        assertEquals(CYCLE, repeated.admissionCycleId); assertEquals(FIRST_CYCLE, repeated.firstIncorporationCycleId);
        assertEquals(before, inputState());
        assertThrows(IllegalStateException.class, () -> store.requireWorkspaceInputIncorporationDisclosureLive(later));
        assertEquals(original.recordedAtMillis, read(original).recordedAtMillis);
        assertEquals(1, store.listWorkspaceInputIncorporations(CENTRAL, 10, null).size());
    }

    @Test public void changedMailboxContentCycleAndScopeAreConflictsNotNewIdentities() throws Exception {
        WorkspaceInputIncorporation original = nativeReceipt("control-1");
        store.appendWorkspaceInputIncorporationIfAbsent(original, () -> { }); List<String> before = inputState();
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
        assertEquals(before, inputState());
        assertEquals(original.receiptId, read(original).receiptId);
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
        store.appendWorkspaceInputIncorporationIfAbsent(first, () -> { }); List<String> before = inputState();
        WorkspaceInputIncorporation second = changed(nativeReceipt("control-2"), "mailboxMessageId", first.mailboxMessageId);
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(second, () -> { }));
        assertEquals(before, inputState());
        WorkspaceInputIncorporation reply = receipt(reply(true));
        store.appendWorkspaceInputIncorporationIfAbsent(reply, () -> { });
        WorkspaceInputIncorporation differentTransport = changed(reply, "transportKind", "ASK_CHIEF");
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(differentTransport, () -> { }));
        List<WorkspaceInputIncorporation> receipts = store.listWorkspaceInputIncorporations(CENTRAL, 10, null);
        assertEquals(2, receipts.size());
        assertTrue(receipts.stream().anyMatch(value -> value.receiptId.equals(first.receiptId)));
        assertTrue(receipts.stream().anyMatch(value -> value.receiptId.equals(reply.receiptId)));
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

    @Test public void deletedExecutionConversationNeverCreatesOrDisclosesReceipt() {
        WorkspaceInputIncorporation value = nativeReceipt("control-1");
        store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { });
        assertTrue(store.deleteConversation(SOURCE));
        assertThrows(IllegalStateException.class, () -> read(value));
        assertThrows(IllegalStateException.class, () -> store.listWorkspaceInputIncorporations(CENTRAL, 1, null));
        assertThrows(IllegalStateException.class, () -> store.requireWorkspaceInputIncorporationDisclosureLive(value));
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
    }


    @Test public void tombstonedNativeExecutionConversationCannotBeCreatedByNewReceipt() {
        WorkspaceInputIncorporation value = nativeReceipt("control-1");
        assertTrue(store.deleteConversation(SOURCE));
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertTrue(store.listWorkspaceInputIncorporations(CENTRAL, 10, null).isEmpty());
    }


    @Test public void missingAndDeletedCentralAreNeverCreatedByReceipt() {
        WorkspaceInputIncorporation value = nativeReceipt("control-1", false);
        assertThrows(IllegalStateException.class, () -> read(value));
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertThrows(IllegalStateException.class, () -> store.listWorkspaceInputIncorporations(CENTRAL, 10, null));
        assertTrue(store.listConversations().stream().noneMatch(row -> CENTRAL.equals(row.optString("session_id"))));
        ensureCentral();
        assertTrue(store.deleteConversation(CENTRAL));
        assertThrows(IllegalStateException.class, () -> read(value));
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
    }


    @Test public void finalAdmissionAndTombstoneBoundariesCannotCreateFalseSuccess() {
        WorkspaceInputIncorporation value = nativeReceipt("control-1");
        List<String> before = inputState();
        assertThrows(CancellationException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, this::deny));
        assertEquals(before, inputState()); assertNull(read(value));
        assertTrue(store.deleteConversation(SOURCE));
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertTrue(store.listWorkspaceInputIncorporations(CENTRAL, 10, null).isEmpty());
    }


    @Test public void tombstonedCentralCannotBeResurrectedAtReceiptAdmission() {
        WorkspaceInputIncorporation value = nativeReceipt("control-1");
        assertTrue(store.deleteConversation(CENTRAL));
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertThrows(IllegalStateException.class, () -> store.listWorkspaceInputIncorporations(CENTRAL, 10, null));
    }


    @Test public void missingReplySourceReferenceCannotGainPositiveReceipt() {
        ensureCentral();
        CrewCoordinationEvidence evidence = CrewCoordinationEvidence.create(SOURCE, MISSION, "bot-1", CYCLE,
                "question-missing-source", "QUESTION", "Exact untrusted question", Collections.emptyList(), 100L);
        store.appendCrewCoordinationEvidence(evidence);
        WorkspaceCoordinationReference missingReference = WorkspaceCoordinationReference.create(evidence, CENTRAL,
                WORKSPACE, ASSIGNMENT, REQUEST, 7L);
        WorkspaceCoordinationReply proposal = WorkspaceCoordinationReply.create(missingReference, "reply-missing-source",
                "Exact answer", "central-request", "Original user request", 200L);
        WorkspaceInputIncorporation value = receipt(proposal);
        assertNull(store.readWorkspaceCoordinationReference(SOURCE, missingReference.referenceId));
        assertNull(store.readWorkspaceCoordinationNotice(CENTRAL, missingReference.referenceId));
        assertNull(read(value));
        assertThrows(IllegalStateException.class, () -> store.appendWorkspaceInputIncorporationIfAbsent(value, () -> { }));
        assertTrue(store.listWorkspaceInputIncorporations(CENTRAL, 10, null).isEmpty());
    }

    @Test public void validReceiptIsInvisibleToEveryHistoryPath() {
        store.appendWorkspaceInputIncorporationIfAbsent(nativeReceipt("control-1"), () -> { });
        assertEquals(1, store.readConversationMessages(CENTRAL).size());
        assertTrue(store.readModelTranscriptRows(CENTRAL).isEmpty());
        assertEquals(1, store.readConversationTimeline(CENTRAL).size());
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
            List<String> pageReceipts = page.stream().map(value -> value.receiptId).collect(java.util.stream.Collectors.toList());
            assertThrows(UnsupportedOperationException.class, () -> page.add(nativeReceipt("forbidden")));
            assertEquals(pageReceipts, page.stream().map(value -> value.receiptId).collect(java.util.stream.Collectors.toList()));
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
                    LocalRunStore instance = reopened(); start.await();
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
