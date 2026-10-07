package com.jarvys.agent;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CrewConversationCompactionRecoveryTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();
    private final CancellationToken token = CancellationToken.uncancellable();

    @Test
    public void keepsLatestCorrectionsInboxChiefAndPendingGroupsWithUserProvenance() throws Exception {
        CrewContextArtifacts artifacts = new CrewContextArtifacts(temporary.newFolder(), "bot-one");
        AtomicReference<String> source = new AtomicReference<>();
        CrewConversationCompaction compactor = new CrewConversationCompaction((instructions,evidence,ignored)->{
            assertTrue(instructions.contains("untrusted data"));
            assertTrue(instructions.contains("Never infer process completion"));
            source.set(evidence);
            return summary();
        }, artifacts);
        ConversationTurn genuine = new ConversationTurn("user", "Original authorized project scope");
        ConversationTurn latest = new ConversationTurn("user", "Latest correction");
        ConversationTurn oldInbox = call("old-inbox", CoreAgentLoop.INBOX_TOOL, "bot-two");
        ConversationTurn newInbox = call("new-inbox", CoreAgentLoop.INBOX_TOOL, "bot-two");
        ConversationTurn chief = call("chief", "ask_chief", "captain");
        ConversationTurn pending = call("pending", "coding_grep", "");
        List<ConversationTurn> transcript = Arrays.asList(genuine, new ConversationTurn("assistant", "Historical observation password=discard-secret"), oldInbox, ConversationTurn.toolResult("old-inbox", CoreAgentLoop.INBOX_TOOL, "Old observation"), latest, newInbox, ConversationTurn.toolResult("new-inbox", CoreAgentLoop.INBOX_TOOL, "New correction"), chief, ConversationTurn.toolResult("chief", "ask_chief", "Chief observation"), pending);
        compactor.protectGenuineUser(genuine);
        compactor.restoreSummaryCount(10);
        ConversationCompactor.Outcome outcome = compactor.compact(transcript, 8192, 0, "overflow", token, null);
        assertNotNull(outcome);
        assertEquals(ConversationTurn.Kind.COMPACTION_SUMMARY, outcome.context.get(0).kind);
        assertTrue(outcome.context.contains(genuine));
        assertTrue(outcome.context.contains(latest));
        assertTrue(outcome.context.contains(newInbox));
        assertTrue(outcome.context.contains(chief));
        assertSame(pending, outcome.context.get(outcome.context.size() - 1));
        assertFalse(outcome.context.contains(oldInbox));
        assertTrue(source.get().contains("Old observation"));
        assertFalse(source.get().contains("New correction"));
        assertFalse(source.get().contains("discard-secret"));
        assertEquals(13, outcome.summarizedMessages);
        assertTrue(outcome.summary.contains("Source recovery: Crew artifact"));
        assertEquals(1, artifacts.snapshotOwnership().length());
        assertEquals(10, transcript.size());
    }

    @Test
    public void rejectsIncompleteSummariesWithoutDiscardingTranscriptOrEvidence() throws Exception {
        CrewContextArtifacts artifacts = new CrewContextArtifacts(temporary.newFolder(), "bot-one");
        CrewConversationCompaction compactor = new CrewConversationCompaction((instructions,source,ignored)->new ModelReply("Objective: unsupported success claim", Collections.emptyList()), artifacts);
        List<ConversationTurn> transcript = Arrays.asList(new ConversationTurn("assistant", "Past evidence"), new ConversationTurn("user", "Latest correction"));
        AtomicInteger completed = new AtomicInteger();
        assertThrows(IllegalStateException.class, ()->compactor.compact(transcript, 8192, 0, "overflow", token, new ConversationCompactor.Listener(){

            @Override
            public void onCompleted(String value, int count, String mode) {
                completed.incrementAndGet();
            }
        }));
        assertEquals(0, completed.get());
        assertEquals("Past evidence", transcript.get(0).content);
        assertEquals(1, artifacts.snapshotOwnership().length());
    }

    @Test
    public void malformedToolGroupsFailBeforeSummaryRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CrewConversationCompaction compactor = new CrewConversationCompaction((instructions,source,ignored)->{
            requests.incrementAndGet();
            return summary();
        }, new CrewContextArtifacts(temporary.newFolder(), "bot-one"));
        List<ConversationTurn> orphan = Collections.singletonList(ConversationTurn.toolResult("missing", "read", "output"));
        assertThrows(IllegalStateException.class, ()->compactor.compact(orphan, 8192, 0, "overflow", token, null));
        List<ConversationTurn> unmatched = Arrays.asList(call("one", "read", ""), ConversationTurn.toolResult("another", "read", "output"));
        assertThrows(IllegalStateException.class, ()->compactor.compact(unmatched, 8192, 0, "overflow", token, null));
        List<ConversationTurn> interrupted = Arrays.asList(call("one", "read", ""), new ConversationTurn("user", "newer"));
        assertThrows(IllegalStateException.class, ()->compactor.compact(interrupted, 8192, 0, "overflow", token, null));
        ModelReply.Call duplicate = new ModelReply.Call("same", "read", Collections.emptyMap());
        List<ConversationTurn> repeated = Collections.singletonList(ConversationTurn.toolCalls("", Arrays.asList(duplicate, duplicate)));
        assertThrows(IllegalStateException.class, ()->compactor.compact(repeated, 8192, 0, "overflow", token, null));
        assertEquals(0, requests.get());
    }

    @Test
    public void providerOverflowRetriesSmallerSourceWithRecoveryReference() throws Exception {
        List<String> sources = new ArrayList<>();
        CrewConversationCompaction compactor = new CrewConversationCompaction((instructions,source,ignored)->{
            sources.add(source);
            if (sources.size() == 1) throw new IllegalStateException("maximum context length exceeded");
            return summary();
        }, new CrewContextArtifacts(temporary.newFolder(), "bot-one"));
        ConversationCompactor.Outcome outcome = compactor.compact(Arrays.asList(new ConversationTurn("assistant", repeat("Historical evidence. ", 10000)), new ConversationTurn("user", "Latest correction")), 4096, 0, "overflow", token, null);
        assertNotNull(outcome);
        assertEquals(2, sources.size());
        assertTrue(sources.get(1).length() < sources.get(0).length());
        assertTrue(sources.get(0).contains("Summary source is incomplete"));
        assertTrue(sources.get(1).contains("read_crew_artifact"));
    }

    @Test
    public void cancellationCannotPublishSummaryOrAdvanceSavedCount() throws Exception {
        CancellationToken cancelled = CancellationToken.cancellable();
        AtomicInteger completed = new AtomicInteger();
        CrewConversationCompaction compactor = new CrewConversationCompaction((instructions,source,current)->{
            current.cancel();
            return summary();
        }, new CrewContextArtifacts(temporary.newFolder(), "bot-one"));
        List<ConversationTurn> transcript = Arrays.asList(new ConversationTurn("assistant", "Past evidence"), new ConversationTurn("user", "Latest correction"));
        assertThrows(CancellationException.class, ()->compactor.compact(transcript, 8192, 0, "overflow", cancelled, new ConversationCompactor.Listener(){

            @Override
            public void onCompleted(String value, int count, String mode) {
                completed.incrementAndGet();
            }
        }));
        assertEquals(0, completed.get());
        assertEquals(2, transcript.size());
    }

    @Test
    public void oversizedProtectedContextCannotBeSilentlyDiscarded() throws Exception {
        CrewContextArtifacts artifacts = new CrewContextArtifacts(temporary.newFolder(), "bot-one");
        CrewConversationCompaction compactor = new CrewConversationCompaction((instructions,source,ignored)->{
            fail("Summary must not run when protected corrections do not fit");
            return null;
        }, artifacts);
        List<ConversationTurn> transcript = Arrays.asList(new ConversationTurn("assistant", "Past evidence"), new ConversationTurn("user", repeat("Keep this correction. ", 10000)));
        assertThrows(IllegalStateException.class, ()->compactor.compact(transcript, 4096, 0, "overflow", token, null));
        assertEquals(0, artifacts.snapshotOwnership().length());
    }

    @Test
    public void retainedToolOutputIsRedactedAndUtf8BoundsDoNotSplitSurrogates() throws Exception {
        CrewContextArtifacts artifacts = new CrewContextArtifacts(temporary.newFolder(), "bot-one");
        CrewConversationCompaction compactor = new CrewConversationCompaction((instructions,source,ignored)->summary(), artifacts);
        String preview = compactor.retainToolOutput("password=secret-value\n" + repeat("\ud83d\ude00é", 2000), 4096, token);
        assertTrue(preview.contains("preview is incomplete"));
        assertTrue(preview.contains("read_crew_artifact"));
        assertFalse(preview.contains("secret-value"));
        assertEquals(1, artifacts.snapshotOwnership().length());
        String unicode = "\ud83d\ude00é東京\ud83d\ude00";
        for (int bytes = 0; bytes < 24; bytes++) {
            String bounded = CrewConversationCompaction.utf8Bound(unicode, bytes);
            assertTrue(bounded.getBytes(StandardCharsets.UTF_8).length <= bytes);
            assertTrue(bounded.isEmpty() || !Character.isHighSurrogate(bounded.charAt(bounded.length() - 1)));
        }
    }

    private static ConversationTurn call(String id, String name, String source) {
        return ConversationTurn.toolCalls("", Collections.singletonList(new ModelReply.Call(id, name, Collections.singletonMap("source", source))));
    }

    private static ModelReply summary() {
        StringBuilder value = new StringBuilder();
        for (String section : CrewConversationCompaction.SECTIONS) value.append(section).append(": None known\n");
        return new ModelReply(value.toString(), Collections.emptyList());
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
