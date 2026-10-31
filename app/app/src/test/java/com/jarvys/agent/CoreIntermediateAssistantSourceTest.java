package com.jarvys.agent;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** A persisted intermediate answer must be the exact next-request source, not a text match. */
public class CoreIntermediateAssistantSourceTest {
    private static final String TEXT = "same answer text";
    private static final ConversationTurn.Source OLD = new ConversationTurn.Source(
            ConversationTurn.Source.Kind.MESSAGE, "chat", "old-assistant", "");
    private static final ConversationTurn.Source RECEIPT = new ConversationTurn.Source(
            ConversationTurn.Source.Kind.MESSAGE, "chat", "persisted-assistant", "");

    @Test public void ordinaryReplyBindsExactObjectBeforeNextProvider() { verify(false, false); }
    @Test public void terminalToolCreatesExactAssistantBeforeSteering() { verify(true, false); }
    @Test public void ordinaryPersistenceFailureStopsBeforeNextProviderAndInputAck() { verify(false, true); }
    @Test public void terminalPersistenceFailureStopsBeforeNextProviderAndInputAck() { verify(true, true); }

    @Test public void ordinaryUserSteeringPreservesExactDurableIdentity() { verify(false,false,true); }
    @Test public void terminalUserSteeringPreservesExactDurableIdentity() { verify(true,false,true); }
    private void verify(boolean terminal, boolean failWrite) {verify(terminal,failWrite,false);}
    private void verify(boolean terminal, boolean failWrite, boolean genuineUser) {
        AtomicInteger calls = new AtomicInteger(), writes = new AtomicInteger(), acknowledged = new AtomicInteger();
        AtomicBoolean ready = new AtomicBoolean(), delivered = new AtomicBoolean();
        ConversationTurn old = ConversationTurn.assistantReply(TEXT, Collections.emptyList());
        old.bindSource(OLD);
        CoreTool finish = new CoreTool() {
            @Override public ToolSpec declaration() {
                return new ToolSpec("finish", "finish", "test", "test", ToolSpec.Status.IMPLEMENTED,
                        Collections.emptyMap(), Collections.emptyList());
            }
            @Override public CoreToolResult execute(Map<String,Object> arguments, CancellationToken token) {
                ready.set(true);
                return CoreToolResult.finish(TEXT);
            }
        };
        CoreAgentLoop loop = new CoreAgentLoop((CoreAgentLoop.Model) (history, prompt, tools, token) -> {
            if (calls.incrementAndGet() == 1) {
                if (terminal) return new ModelReply("", Collections.singletonList(
                        new ModelReply.Call("finish-call", "finish", Collections.emptyMap())));
                ready.set(true);
                return new ModelReply(TEXT, Collections.emptyList());
            }
            assertEquals(1, writes.get());
            assertEquals(OLD, old.source());
            int incomingIndex = -1, answerIndex = -1, count = 0;
            for (int i = 0; i < history.size(); i++) {
                ConversationTurn turn = history.get(i);
                if ("next-input".equals(turn.durableMessageId)) incomingIndex = i;
                if (RECEIPT.equals(turn.source())) {
                    answerIndex = i; count++;
                    assertEquals(ConversationTurn.Kind.MESSAGE, turn.kind);
                    assertEquals("assistant", turn.role);
                    assertEquals(TEXT, turn.content);
                    assertNotSame(old, turn);
                }
            }
            assertEquals(1, count);
            assertTrue(answerIndex >= 0 && incomingIndex > answerIndex);
            return new ModelReply("completed", Collections.emptyList());
        }, new CoreToolRegistry(Collections.singletonList(finish)), "", "chat", CorePromptBudget.standard(), null,
                CoreAgentLoop.ToolResultBudget.UNBOUNDED, new CoreAgentLoop.TurnContextProvider() {
                    @Override public List<CoreAgentLoop.IncomingMessage> takeDurableMessages() { return Collections.emptyList(); }
                    @Override public List<CoreAgentLoop.IncomingMessage> takeDurableMessagesAtFinalCheckpoint() {
                        if (!ready.get() || delivered.getAndSet(true)) return Collections.emptyList();
                        if(genuineUser)return Collections.singletonList(CoreAgentLoop.IncomingMessage.mainChat("next-input","new request"));
                        return Collections.singletonList(CoreAgentLoop.IncomingMessage.mainChat(new ConversationInput("chat", "next-input", "new request",
                                ConversationInput.Origin.AGENT, new ConversationInput.Provenance("worker", "", "", "bot",
                                    "source", ConversationInput.Kind.BLOCKER))));
                    }
                    @Override public void durableMessagesIncorporated(List<CoreAgentLoop.IncomingMessage> messages) {
                        acknowledged.addAndGet(messages.size());
                    }
                }, null);
        CoreAgentLoop.ProgressListener progress = new CoreAgentLoop.ProgressListener() {
            @Override public void onProgress(String stage, String message) { }
            @Override public ConversationTurn.Source persistAssistantAnswerBeforeInput(String run, String text,
                    CoreAgentLoop.IncomingMessage next) {
                assertEquals(TEXT, text); assertEquals("next-input", next.id);
                writes.incrementAndGet();
                if (failWrite) throw new IllegalStateException("durable assistant write failed");
                return RECEIPT;
            }
        };
        if (failWrite) {
            assertThrows(IllegalStateException.class, () -> loop.run("initial", Collections.singletonList(old),
                    CancellationToken.uncancellable(), progress));
            assertEquals(1, calls.get()); assertEquals(0, acknowledged.get());
            assertFalse(loop.checkpointSnapshot().appliedIncomingIds.contains("next-input"));
            assertTrue(loop.transcriptSnapshot().stream().noneMatch(t -> RECEIPT.equals(t.source())));
        } else {
            assertEquals("COMPLETED", loop.run("initial", Collections.singletonList(old),
                    CancellationToken.uncancellable(), progress).outcome);
            assertEquals(2, calls.get()); assertEquals(1, acknowledged.get());
        }
    }

    @Test public void ordinaryUserPersistenceFailureStopsBeforeNextProvider() {verify(false,true,true);}
    @Test public void terminalUserPersistenceFailureStopsBeforeNextProvider() {verify(true,true,true);}
    @Test public void nonMainChatUserCorrectionCannotAcquireRoomIdentity() {
        AtomicInteger calls=new AtomicInteger();AtomicBoolean ready=new AtomicBoolean(),delivered=new AtomicBoolean();
        CoreAgentLoop loop=new CoreAgentLoop((CoreAgentLoop.Model)(history,prompt,tools,token)->{
            if(calls.incrementAndGet()==1){ready.set(true);return new ModelReply("first",Collections.emptyList());}
            ConversationTurn turn=history.stream().filter(t->"native correction".equals(t.content)).findFirst().orElseThrow();
            assertEquals("user",turn.role);assertEquals("",turn.durableMessageId);assertNull(turn.source());
            return new ModelReply("done",Collections.emptyList());
        },new CoreToolRegistry(Collections.emptyList()),"","chat",CorePromptBudget.standard(),null,
            CoreAgentLoop.ToolResultBudget.UNBOUNDED,new CoreAgentLoop.TurnContextProvider(){
                @Override public List<CoreAgentLoop.IncomingMessage> takeDurableMessages(){return Collections.emptyList();}
                @Override public List<CoreAgentLoop.IncomingMessage> takeDurableMessagesAtFinalCheckpoint(){
                    if(!ready.get() || delivered.getAndSet(true))return Collections.emptyList();
                    return Collections.singletonList(new CoreAgentLoop.IncomingMessage("crew-correct","captain","native correction",true));
                }
            },null);
        assertEquals("COMPLETED",loop.run("initial",Collections.emptyList(),CancellationToken.uncancellable(),null).outcome);
        assertEquals(2,calls.get());
    }

    @Test public void typedCallbackPreservesLegacyVoidConsumer() {
        AtomicInteger legacy = new AtomicInteger();
        CoreAgentLoop.ProgressListener progress = new CoreAgentLoop.ProgressListener() {
            @Override public void onProgress(String stage, String message) { }
            @Override public void onAssistantAnswerBeforeInput(String run, String text, CoreAgentLoop.IncomingMessage next) {
                legacy.incrementAndGet();
            }
        };
        assertNull(progress.persistAssistantAnswerBeforeInput("run", TEXT,
                CoreAgentLoop.IncomingMessage.mainChat("next", "request")));
        assertEquals(1, legacy.get());
    }
}
