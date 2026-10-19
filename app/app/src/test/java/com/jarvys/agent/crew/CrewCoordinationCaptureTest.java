package com.jarvys.agent.crew;

import static org.junit.Assert.*;
import com.jarvys.agent.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.Test;

public class CrewCoordinationCaptureTest {
    private static final CoreToolRegistry EMPTY = new CoreToolRegistry(Collections.emptyList());

    @Test(timeout=15000) public void selectedProductionPersistsBeforeActivationAndFindingsNeverReply() throws Exception {
        try (Parked parked = new Parked()) {
            List<CrewCoordinationEvidence> seen = new ArrayList<>();
            parked.manager.configureCoordinationEvidenceListener(evidence -> {
                assertFalse(parked.manager.canReplyToCoordination(evidence));
                assertFalse(parked.manager.messageBus().snapshot().stream().anyMatch(message -> message.id.equals(evidence.messageId)));
                seen.add(evidence);
            });
            CrewMessage question = parked.manager.send(parked.bot.id, "chief", CrewMessage.Type.QUESTION, "  exact question  ", Arrays.asList("ref"));
            assertEquals("  exact question  ", seen.get(0).text);
            assertEquals(parked.bot.cycleId(), seen.get(0).cycleId);
            assertTrue(question.coordination.matches(seen.get(0)));
            assertTrue(parked.manager.canReplyToCoordination(seen.get(0)));
            parked.manager.send(parked.bot.id, "chief", CrewMessage.Type.FINDING, "unverified", Collections.emptyList());
            assertFalse(parked.manager.canReplyToCoordination(seen.get(1)));
            parked.manager.send(parked.bot.id, "chief", CrewMessage.Type.STATUS, "blocked awaiting an answer", Collections.emptyList());
            assertEquals(2, seen.size());
            parked.manager.send(parked.bot.id, "chief", CrewMessage.Type.BLOCKER, "explicit blocker", Collections.emptyList());
            assertEquals(3, seen.size());
            assertTrue(parked.manager.canReplyToCoordination(seen.get(2)));
        }
    }
    @Test(timeout=15000) public void everyContentMessageToChiefUsesConversationInputPipeline() throws Exception {
        try (Parked parked = new Parked()) {
            CrewManager.Bot sender = parked.manager.spawnForMission("message-types-mission",
                    "Message type test", "custom", "Send Crew messages", Collections.singletonList("msg_send"),
                    "Message Sender", CrewMissionAccess.STANDARD, "Message type test");
            List<CrewMessage> delivered = new ArrayList<>();
            parked.manager.configureConversationMessageListener((bot, message) -> delivered.add(message));
            CoreTool msgSend = CrewTools.bot(sender, parked.manager, temporaryBoard()).stream()
                    .filter(tool -> "msg_send".equals(tool.declaration().name))
                    .findFirst().orElseThrow(AssertionError::new);
            for (CrewMessage.Type type : CrewMessage.Type.values()) {
                int prior = delivered.size();
                Map<String, Object> arguments = new HashMap<>();
                arguments.put("to", "chief"); arguments.put("type", type.name());
                arguments.put("text", "content for " + type);
                CoreToolResult result = msgSend.execute(arguments, sender.token);
                assertTrue(result.content, result.success);
                assertEquals("content for " + type, delivered.get(prior).text);
                assertEquals(type, delivered.get(prior).type);
                assertEquals(1, delivered.size() - prior);
            }
        }
    }

    @Test(timeout=15000) public void boardPostWritesSharedNoteWithoutEmittingChiefInput() throws Exception {
        try (Parked parked = new Parked()) {
            CrewManager.Bot boardBot = parked.manager.spawnForMission("board-post-mission",
                    "Board note test", "custom", "Write a board note", Collections.singletonList("board_post"),
                    "Board Writer", CrewMissionAccess.STANDARD, "Board note test");
            CrewBoard board = temporaryBoard();
            List<CrewMessage> delivered = new ArrayList<>();
            parked.manager.configureConversationMessageListener((bot, message) -> delivered.add(message));
            CoreTool boardPost = CrewTools.bot(boardBot, parked.manager, board).stream()
                    .filter(tool -> "board_post".equals(tool.declaration().name))
                    .findFirst().orElseThrow(AssertionError::new);
            Map<String, Object> arguments = new HashMap<>();
            arguments.put("path", "/board/research.md"); arguments.put("content", "A shared note");
            CoreToolResult result = boardPost.execute(arguments, boardBot.token);
            assertTrue(result.content, result.success);
            assertTrue(parked.manager.messageBus().snapshot().stream()
                    .noneMatch(message -> boardBot.id.equals(message.from) && "chief".equals(message.to)));
            assertTrue(delivered.isEmpty());
            assertEquals("A shared note", board.read("/board/research.md"));
        }
    }
    @Test(timeout=15000) public void failedPersistencePropagatesWithoutSourceSuccessOrCapability() throws Exception {
        try (Parked parked = new Parked()) {
            AtomicReference<CrewCoordinationEvidence> source = new AtomicReference<>();
            parked.manager.configureCoordinationEvidenceListener(evidence -> { source.set(evidence); throw new IllegalStateException("disk unavailable"); });
            assertThrows(IllegalStateException.class, () -> question(parked));
            assertFalse(parked.manager.canReplyToCoordination(source.get()));
            assertFalse(parked.manager.messageBus().snapshot().stream().anyMatch(message -> message.id.equals(source.get().messageId)));
            assertEquals("disk unavailable", parked.manager.coordinationEvidenceDeliveryFailures().get(source.get().evidenceId));
            assertEquals(CrewManager.Status.RUNNING, parked.bot.status());
        }
    }
    @Test(timeout=15000) public void correctionDuringSourcePersistenceCannotReactivateOriginalRequest() throws Exception {
        try (Parked parked = new Parked()) {
            CountDownLatch persisting = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<CrewCoordinationEvidence> source = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            parked.manager.configureCoordinationEvidenceListener(evidence -> { source.set(evidence); persisting.countDown(); await(release); });
            Thread producer = thread(() -> { try { question(parked); } catch (Throwable problem) { failure.set(problem); } });
            try {
                await(persisting);
                String cycle = parked.bot.cycleId();
                parked.manager.sendUserMessage(parked.bot.id, "Same-cycle native correction");
                assertEquals(cycle, parked.bot.cycleId());
                release.countDown(); join(producer);
                assertTrue(failure.get() instanceof IllegalStateException);
                assertFalse(parked.manager.canReplyToCoordination(source.get()));
                assertFalse(parked.manager.messageBus().snapshot().stream().anyMatch(message -> message.id.equals(source.get().messageId)));
                assertThrows(IllegalStateException.class, () -> answer(parked.manager, source.get(), "late", () -> true));
            } finally { release.countDown(); }
        }
    }
    @Test(timeout=15000) public void stopDuringSourcePersistenceCannotActivateOrRevive() throws Exception {
        try (Parked parked = new Parked()) {
            CountDownLatch persisting = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<CrewCoordinationEvidence> source = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            parked.manager.configureCoordinationEvidenceListener(evidence -> { source.set(evidence); persisting.countDown(); await(release); });
            Thread producer = thread(() -> { try { question(parked); } catch (Throwable problem) { failure.set(problem); } });
            try {
                await(persisting); parked.manager.stop(parked.bot.id); release.countDown(); join(producer);
                assertTrue(failure.get() instanceof IllegalStateException);
                assertFalse(parked.manager.canReplyToCoordination(source.get()));
                assertThrows(IllegalStateException.class, () -> answer(parked.manager, source.get(), "late", () -> true));
                assertTrue(parked.bot.token.isCancellationRequested());
            } finally { release.countDown(); }
        }
    }
    @Test(timeout=15000) public void exactReplyRejectsWrongOwnerMissionCycleAndDigest() throws Exception {
        try (Parked parked = new Parked()) {
            CrewCoordinationEvidence original = captureQuestion(parked);
            String[][] identities = {{"other", original.missionId, original.botId, original.cycleId},
                    {original.conversationId,"other",original.botId,original.cycleId},
                    {original.conversationId,original.missionId,"other",original.cycleId},
                    {original.conversationId,original.missionId,original.botId,UUID.randomUUID().toString()}};
            for (String[] id : identities) {
                CrewCoordinationEvidence foreign = CrewCoordinationEvidence.create(id[0],id[1],id[2],id[3],original.messageId,"QUESTION",original.text,original.refs,original.producedAtMillis);
                assertFalse(parked.manager.canReplyToCoordination(foreign));
                assertThrows(IllegalStateException.class, () -> answer(parked.manager, foreign, "invalid", () -> true));
            }
            CrewCoordinationEvidence changed = CrewCoordinationEvidence.create(original.conversationId,original.missionId,original.botId,original.cycleId,original.messageId,"QUESTION","different",original.refs,original.producedAtMillis);
            assertFalse(parked.manager.canReplyToCoordination(changed));
            assertThrows(IllegalStateException.class, () -> answer(parked.manager, changed, "invalid", () -> true));
            assertTrue(parked.manager.canReplyToCoordination(original));
            CrewMessage accepted = answer(parked.manager, original, "valid", () -> true);
            assertEquals(CrewMessage.Type.ANSWER, accepted.type);
            assertEquals("chief", accepted.from);
            assertTrue(accepted.coordination.matches(original));
            assertFalse(parked.manager.canReplyToCoordination(original));
        }
    }
    @Test(timeout=15000) public void twoDifferentReplyIdsStillDispatchOnlyOnce() throws Exception {
        try (Parked parked = new Parked()) {
            CrewCoordinationEvidence source = captureQuestion(parked);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger(), rejected = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (String id : Arrays.asList("first-reply", "other-key")) threads.add(thread(() -> {
                await(go);
                try { answer(parked.manager, source, id, () -> true); accepted.incrementAndGet(); }
                catch (IllegalStateException denied) { rejected.incrementAndGet(); }
            }));
            go.countDown(); for (Thread worker : threads) join(worker);
            assertEquals(1, accepted.get()); assertEquals(1, rejected.get());
            assertEquals(1, parked.manager.messageBus().pending(parked.bot.id).stream().filter(m -> m.type==CrewMessage.Type.ANSWER).count());
            assertThrows(IllegalStateException.class, () -> answer(parked.manager, source, "third-key", () -> true));
        }
    }
    @Test(timeout=15000) public void finalAdmissionRevocationNeverDispatchesAndSameCycleCorrectionWins() throws Exception {
        try (Parked parked = new Parked()) {
            CrewCoordinationEvidence source = captureQuestion(parked);
            assertThrows(IllegalStateException.class, () -> answer(parked.manager, source, "denied", () -> false));
            assertTrue(parked.manager.canReplyToCoordination(source));
            assertThrows(IllegalStateException.class, () -> answer(parked.manager, source, "corrected", () -> {
                parked.manager.sendUserMessage(parked.bot.id, "New instruction"); return true;
            }));
            assertFalse(parked.manager.canReplyToCoordination(source));
            assertFalse(parked.manager.messageBus().pending(parked.bot.id).stream().anyMatch(m -> m.type==CrewMessage.Type.ANSWER));
        }
    }
    @Test(timeout=15000) public void directWorkerTokenCancellationAtAdmissionCannotDispatch() throws Exception {
        try (Parked parked = new Parked()) {
            CrewCoordinationEvidence source = captureQuestion(parked);
            assertThrows(IllegalStateException.class, () -> answer(parked.manager, source, "cancelled", () -> {
                parked.bot.token.cancel(); return true;
            }));
            assertFalse(parked.manager.messageBus().pending(parked.bot.id).stream().anyMatch(m -> m.type==CrewMessage.Type.ANSWER));
        }
    }
    @Test(timeout=15000) public void checkpointFailureAfterEnqueueConsumesClaimAndNeverRetries() throws Exception {
        try (Parked parked = new Parked()) {
            CrewCoordinationEvidence source = captureQuestion(parked);
            parked.manager.configureCheckpoints(new CrewManager.CheckpointSupport() {
                @Override public void persist(CrewManager.Bot bot,List<CrewMessage> messages,List<CrewMessage> pending) { throw new IllegalStateException("checkpoint failed after accepted mailbox"); }
                @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot bot) { throw new AssertionError(); }
            });
            try {
                assertThrows(IllegalStateException.class, () -> answer(parked.manager, source, "accepted-uncertain", () -> true));
                assertEquals(1, parked.manager.messageBus().pending(parked.bot.id).stream().filter(m -> m.type==CrewMessage.Type.ANSWER).count());
                assertFalse(parked.manager.canReplyToCoordination(source));
                assertThrows(IllegalStateException.class, () -> answer(parked.manager, source, "rekey", () -> true));
            } finally { parked.manager.configureCheckpoints(null); }
        }
    }
    @Test(timeout=15000) public void snapshotFailureAfterSourcePersistenceIsVisibleAndInvalidatesRequest() throws Exception {
        try (Parked parked = new Parked()) {
            AtomicReference<CrewCoordinationEvidence> source = new AtomicReference<>();
            parked.manager.configureCoordinationEvidenceListener(source::set);
            parked.manager.configure(EMPTY, (bot, manager) -> EMPTY,
                    (bot, tools, inbox) -> { throw new AssertionError("Existing loop only"); }, null, null,
                    snapshot -> { throw new IllegalStateException("snapshot unavailable"); });
            try {
                assertThrows(IllegalStateException.class, () -> question(parked));
                assertNotNull(source.get());
                assertFalse(parked.manager.canReplyToCoordination(source.get()));
                assertEquals("snapshot unavailable", parked.manager.coordinationEvidenceDeliveryFailures().get(source.get().evidenceId));
            } finally {
                parked.manager.configure(EMPTY, (bot, manager) -> EMPTY,
                        (bot, tools, inbox) -> { throw new AssertionError("Existing loop only"); }, null, null, null);
            }
        }
    }
    @Test(timeout=15000) public void sourceCheckpointFailureCannotLeaveAnActiveReplyRequest() throws Exception {
        try (Parked parked = new Parked()) {
            AtomicReference<CrewCoordinationEvidence> source = new AtomicReference<>();
            parked.manager.configureCoordinationEvidenceListener(source::set);
            parked.manager.configureCheckpoints(new CrewManager.CheckpointSupport() {
                @Override public void persist(CrewManager.Bot bot,List<CrewMessage> messages,List<CrewMessage> pending) { throw new IllegalStateException("source checkpoint failed"); }
                @Override public CrewManager.ResumePlan reconcile(CrewManager.Bot bot) { throw new AssertionError(); }
            });
            try {
                assertThrows(IllegalStateException.class, () -> question(parked));
                assertNotNull(source.get());
                assertFalse(parked.manager.canReplyToCoordination(source.get()));
            } finally { parked.manager.configureCheckpoints(null); }
        }
    }
    @Test(timeout=15000) public void replyAdmittedBeforeStopIsOneAcceptedEffectAndStopStillWinsAfterward() throws Exception {
        try (Parked parked = new Parked()) {
            CrewCoordinationEvidence source = captureQuestion(parked);
            CountDownLatch admitted = new CountDownLatch(1), release = new CountDownLatch(1), stopping = new CountDownLatch(1);
            AtomicReference<CrewMessage> sent = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread reply = thread(() -> { try {
                sent.set(answer(parked.manager,source,"before-stop",() -> { admitted.countDown(); await(release); return true; }));
            } catch (Throwable problem) { failure.set(problem); } });
            await(admitted);
            Thread stop = thread(() -> { stopping.countDown(); parked.manager.stop(parked.bot.id); });
            try {
                await(stopping); release.countDown(); join(reply); join(stop);
                if (failure.get()!=null) throw new AssertionError(failure.get());
                assertNotNull(sent.get());
                assertTrue(parked.bot.token.isCancellationRequested());
                assertFalse(parked.manager.canReplyToCoordination(source));
                assertEquals(1,parked.manager.messageBus().snapshot().stream().filter(m -> m.id.equals("before-stop")).count());
                assertThrows(IllegalStateException.class,() -> answer(parked.manager,source,"after-stop",() -> true));
            } finally { release.countDown(); }
        }
    }
    @Test(timeout=15000) public void legacyBlockingQuestionStillAcceptsOrdinaryChiefAnswer() throws Exception {
        try (Parked parked = new Parked()) {
            AtomicReference<Boolean> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch answered = new CountDownLatch(1);
            Thread waiter = thread(() -> {
                try { result.set(parked.manager.askChief(parked.bot, "Legacy question", parked.bot.token)); }
                catch (Throwable problem) { failure.set(problem); }
                finally { answered.countDown(); }
            });
            try {
                parked.manager.messageBus().await("chief", message -> parked.bot.id.equals(message.from)
                        && message.type == CrewMessage.Type.QUESTION, () -> false, parked.bot.token);
                CrewMessage question = parked.manager.messageBus().pending("chief").stream()
                        .filter(message -> parked.bot.id.equals(message.from) && message.type == CrewMessage.Type.QUESTION)
                        .findFirst().orElseThrow(AssertionError::new);
                assertEquals("Legacy question", question.text);
                parked.manager.send("chief", parked.bot.id, CrewMessage.Type.ANSWER,
                        "ordinary answer", Collections.emptyList());
                await(answered);
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertEquals(Boolean.TRUE, result.get());
                CrewMessage accepted = parked.manager.messageBus().snapshot().stream()
                        .filter(message -> "ordinary answer".equals(message.text))
                        .findFirst().orElseThrow(AssertionError::new);
                assertNotNull(accepted.coordination);
                assertEquals(question.id, accepted.coordination.messageId);
                waiter.join(5000);
                assertFalse("ask_chief waiter should stop after the answer", waiter.isAlive());
            } finally { parked.release.countDown(); }
        }
    }
    @Test(timeout=15000) public void restoredAndFinishedSourcesNeverCreateLiveReplyCapabilities() throws Exception {
        CrewCoordinationEvidence source;
        CrewMissionSnapshot snapshot;
        try (Parked parked = new Parked()) {
            source = captureQuestion(parked);
            snapshot = parked.manager.missionSnapshots().get(0);
            parked.release.countDown(); parked.bot.awaitTermination();
            assertEquals(CrewManager.Status.DONE, parked.bot.status());
            assertFalse(parked.manager.canReplyToCoordination(source));
            assertThrows(IllegalStateException.class, () -> answer(parked.manager, source, "late", () -> true));
        }
        try (CrewManager restored = manager((history,prompt,tools,token) -> { throw new AssertionError("Reopen must not run"); })) {
            AtomicInteger persistence = new AtomicInteger();
            restored.configureCoordinationEvidenceListener(evidence -> persistence.incrementAndGet());
            CrewBotSnapshot bot = snapshot.bots.get(0);
            CrewRole role = new CrewRole("custom","Worker","custom","Task",Collections.emptyList(),null);
            restored.restoreBot(snapshot, bot, role, null, new JSONObject(), "", Collections.emptyList(),0L,snapshot.messages,Collections.emptyList(),"");
            assertEquals(source.cycleId, restored.bot(bot.id).cycleId());
            assertEquals(CrewManager.Status.INTERRUPTED, restored.bot(bot.id).status());
            assertEquals(0, persistence.get());
            assertFalse(restored.canReplyToCoordination(source));
            assertThrows(IllegalStateException.class, () -> answer(restored, source, "reopen", () -> true));
        }
    }
    @Test(timeout=15000) public void blockingQuestionIgnoresUnrelatedChiefAndPeerMessagesUntilExactReply() throws Exception {
        try (Parked parked = new Parked()) {
            AtomicReference<CrewCoordinationEvidence> evidence = new AtomicReference<>();
            AtomicReference<Boolean> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch produced = new CountDownLatch(1), answered = new CountDownLatch(1);
            parked.manager.configureCoordinationEvidenceListener(source -> {
                evidence.set(source);
                parked.manager.send("chief", source.botId, CrewMessage.Type.ANSWER,
                        "old unrelated answer", Collections.emptyList());
                produced.countDown();
            });
            Thread waiter = thread(() -> {
                try { result.set(parked.manager.askChief(parked.bot, "Which option?", parked.bot.token)); }
                catch (Throwable problem) { failure.set(problem); }
                finally { answered.countDown(); }
            });
            CrewManager.Bot peer = spawn(parked.manager);
            try {
                await(produced);
                // Observe published activation instead of interpreting a notification as activation.
                awaitReplyable(parked.manager, evidence.get());
                parked.manager.send(peer.id, parked.bot.id, CrewMessage.Type.ANSWER,
                        "another worker's unrelated message", Collections.emptyList());
                assertFalse(answered.await(100, TimeUnit.MILLISECONDS));
                CrewMessage answer = answer(parked.manager, evidence.get(), "exact-answer", () -> true);
                await(answered);
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertEquals(Boolean.TRUE, result.get());
                assertTrue(answer.coordination.matches(evidence.get()));
                assertFalse(parked.manager.canReplyToCoordination(evidence.get()));
                assertTrue(parked.manager.messageBus().pending(parked.bot.id).stream()
                        .anyMatch(message -> "old unrelated answer".equals(message.text)));
                assertTrue(parked.manager.messageBus().pending(parked.bot.id).stream()
                        .anyMatch(message -> "another worker's unrelated message".equals(message.text)));
            } finally { parked.release.countDown(); waiter.join(5000); }
        }
    }
    @Test(timeout=15000) public void userGuidanceEndsBlockingRequestAndLateAnswerCannotReturnIt() throws Exception {
        AtomicReference<CrewManager> owner = new AtomicReference<>();
        AtomicReference<CrewCoordinationEvidence> evidence = new AtomicReference<>();
        AtomicReference<Boolean> result = new AtomicReference<>();
        CountDownLatch produced = new CountDownLatch(1), release = new CountDownLatch(1), answered = new CountDownLatch(1);
        try (CrewManager manager = manager((history,prompt,tools,token) -> {
            CrewManager current = owner.get(); result.set(current.askChief(current.bots().get(0),"Question",token));
            answered.countDown(); awaitWorker(release,token); return reply();
        })) {
            owner.set(manager); manager.configureCoordinationEvidenceListener(source -> { evidence.set(source); produced.countDown(); });
            CrewManager.Bot bot = spawn(manager);
            try {
                await(produced); awaitReplyable(manager,evidence.get());
                manager.sendUserMessage(bot.id,"New user instruction"); await(answered);
                assertEquals(Boolean.FALSE, result.get()); assertFalse(manager.canReplyToCoordination(evidence.get()));
                assertThrows(IllegalStateException.class, () -> answer(manager,evidence.get(),"late",() -> true));
            } finally { release.countDown(); }
        }
    }
    @Test(timeout=15000) public void ordinaryCrewKeepsLegacyNoncausalMessaging() throws Exception {
        try (Parked parked = new Parked()) {
            CrewMessage message = question(parked);
            assertNull(message.coordination);
            assertTrue(parked.manager.coordinationEvidenceDeliveryFailures().isEmpty());
        }
    }

    private static final class Parked implements AutoCloseable {
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final CrewManager manager;
        final CrewManager.Bot bot;
        Parked() {
            manager = manager((history,prompt,tools,token) -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); token.throwIfCancelled(); }
                token.throwIfCancelled(); return reply();
            });
            bot = spawn(manager); await(started);
        }
        @Override public void close() { release.countDown(); manager.close(); }
    }
    private static CrewCoordinationEvidence captureQuestion(Parked parked) {
        AtomicReference<CrewCoordinationEvidence> source = new AtomicReference<>();
        parked.manager.configureCoordinationEvidenceListener(source::set); question(parked); return source.get();
    }
    private static CrewMessage question(Parked parked) {
        return parked.manager.send(parked.bot.id,"chief",CrewMessage.Type.QUESTION,"What next?",Collections.emptyList());
    }
    private static CrewMessage answer(CrewManager manager,CrewCoordinationEvidence source,String id,java.util.function.BooleanSupplier admission) {
        return manager.replyToCoordinationExact(source,id,"Generated untrusted guidance",admission);
    }
    private static CrewBoard temporaryBoard() throws Exception {
        java.io.File root = java.nio.file.Files.createTempDirectory("crew-board-note").toFile();
        java.lang.reflect.Constructor<WorkspaceStore> constructor = WorkspaceStore.class
                .getDeclaredConstructor(java.io.File.class, String.class);
        constructor.setAccessible(true);
        String session = "board-note-" + UUID.randomUUID();
        return new CrewBoard(constructor.newInstance(root, WorkspaceStore.projectIdForSession(session)));
    }
    private static CrewManager manager(CoreAgentLoop.Model model) {
        return new CrewManager("coordination-chat",EMPTY,(bot,owner)->EMPTY,
                (bot,tools,inbox)->new CoreAgentLoop(model,tools,"","coordination-chat",CorePromptBudget.standard(),null,
                        CoreAgentLoop.ToolResultBudget.UNBOUNDED,inbox,null),null);
    }
    private static CrewManager.Bot spawn(CrewManager manager) {
        return manager.spawnForMission("coordination-mission","Original instructions","custom","Task",
                Collections.emptyList(),"Worker",CrewMissionAccess.STANDARD,"Coordination task");
    }
    private static ModelReply reply() { return new ModelReply("finished",Collections.emptyList()); }
    private static Thread thread(Runnable work) { Thread thread = new Thread(work); thread.setDaemon(true); thread.start(); return thread; }
    private static void await(CountDownLatch latch) {
        try { assertTrue("Latch timed out",latch.await(5,TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static void awaitWorker(CountDownLatch latch,CancellationToken token) {
        try { assertTrue("Worker latch timed out",latch.await(5,TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); token.throwIfCancelled(); throw new AssertionError(interrupted); }
        token.throwIfCancelled();
    }
    private static void join(Thread thread) throws Exception { thread.join(5000); assertFalse("Thread did not finish",thread.isAlive()); }
    private static void awaitReplyable(CrewManager manager,CrewCoordinationEvidence source) throws Exception {
        long until = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (!manager.canReplyToCoordination(source) && System.nanoTime()<until) Thread.sleep(1);
        assertTrue("Source never activated",manager.canReplyToCoordination(source));
    }
}
