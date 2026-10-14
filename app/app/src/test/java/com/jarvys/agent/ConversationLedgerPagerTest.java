package com.jarvys.agent;

import static org.junit.Assert.*;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConversationLedgerPagerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void pageReadWorksWhenWorkerWasAlreadyInterrupted() throws Exception {
        File ledger = temporary.newFile("interrupted-page.jsonl");
        List<String> expected = new ArrayList<>();
        for (int turn = 0; turn < 5; turn++) {
            expected.add(user("u" + turn, "question " + turn));
            expected.add(assistant("a" + turn));
        }
        writeLines(ledger, expected);

        ConversationLedgerPager.Page uninterrupted = ConversationLedgerPager.readTail(ledger, 2);
        ConversationLedgerPager.invalidateFile(ledger);

        Thread.currentThread().interrupt();
        try {
            ConversationLedgerPager.Page interrupted = ConversationLedgerPager.readTail(ledger, 2);
            assertEquals(uninterrupted.lines, interrupted.lines);
            assertEquals(uninterrupted.lineOffsets, interrupted.lineOffsets);
            assertEquals(uninterrupted.startOffset, interrupted.startOffset);
            assertEquals(uninterrupted.olderCursor, interrupted.olderCursor);
            assertEquals(uninterrupted.hasMore, interrupted.hasMore);
            assertTrue("pager reads must retain the interrupted status",
                    Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void tailReadsOnlyNewestUserTurnWindowFromLargeLedger() throws Exception {
        File ledger = temporary.newFile("large.jsonl");
        List<String> expected = new ArrayList<>();
        for (int turn = 0; turn < 2_000; turn++) {
            expected.add(user("u" + turn, "mensaje \u03bb " + turn));
            expected.add(assistant("a" + turn));
        }
        writeLines(ledger, expected);

        ConversationLedgerPager.Page tail = ConversationLedgerPager.readTail(ledger, 40);

        assertEquals(80, tail.lines.size());
        assertEquals(expected.subList(3_920, 4_000), tail.lines);
        assertEquals(offsetOf(ledger, expected, 3_920), tail.startOffset);
        assertTrue(tail.hasMore);
        assertEquals(tail.startOffset, tail.olderCursor);
    }

    @Test public void previousPageIsContiguousAndHasNoDuplicateRows() throws Exception {
        File ledger = temporary.newFile("pages.jsonl");
        List<String> expected = new ArrayList<>();
        for (int turn = 0; turn < 57; turn++) {
            expected.add(user("u" + turn, "text " + turn));
            expected.add(assistant("a" + turn));
        }
        writeLines(ledger, expected);

        ConversationLedgerPager.Page newest = ConversationLedgerPager.readTail(ledger, 9);
        ConversationLedgerPager.Page previous = ConversationLedgerPager.readPrevious(
                ledger, newest.olderCursor, 11);

        assertEquals(expected.subList(74, 96), previous.lines);
        assertEquals(expected.subList(96, expected.size()), newest.lines);
        List<String> adjacent = new ArrayList<>(previous.lines);
        adjacent.addAll(newest.lines);
        assertEquals(expected.subList(74, expected.size()), adjacent);
        assertEquals(offsetOf(ledger, expected, 74), previous.startOffset);
        assertTrue(previous.hasMore);
        assertEquals(previous.startOffset, previous.olderCursor);
    }

    @Test public void pageBoundaryKeepsToolCallAndResultInTheirUserTurn() throws Exception {
        File ledger = temporary.newFile("tool-turns.jsonl");
        List<String> expected = new ArrayList<>();
        expected.add(user("u1", "run a tool"));
        expected.add("{\"type\":\"model_tool_calls\",\"userMessageId\":\"u1\"}");
        expected.add("{\"type\":\"model_tool_result\",\"userMessageId\":\"u1\"}");
        expected.add(assistant("a1"));
        expected.add(user("u2", "next turn"));
        expected.add("{\"type\":\"model_tool_calls\",\"userMessageId\":\"u2\"}");
        expected.add("{\"type\":\"model_tool_result\",\"userMessageId\":\"u2\"}");
        expected.add(assistant("a2"));
        writeLines(ledger, expected);

        ConversationLedgerPager.Page newest = ConversationLedgerPager.readTail(ledger, 1);
        ConversationLedgerPager.Page previous = ConversationLedgerPager.readPrevious(
                ledger, newest.olderCursor, 1);

        assertEquals(expected.subList(4, 8), newest.lines);
        assertEquals(expected.subList(0, 4), previous.lines);
        assertEquals(1, previous.lines.stream().filter(line -> line.contains("model_tool_calls")).count());
        assertEquals(1, previous.lines.stream().filter(line -> line.contains("model_tool_result")).count());
    }

    @Test public void malformedRowsAreReturnedAndDoNotBecomeTurnAnchors() throws Exception {
        File ledger = temporary.newFile("corrupt.jsonl");
        List<String> expected = new ArrayList<>();
        expected.add("{\"role\":\"user\",\"messageId\":\"synthetic-no-content\"}");
        expected.add(user("u1", "valid"));
        expected.add("{this row is corrupt");
        expected.add(assistant("a1"));
        expected.add(user("u2", "second"));
        expected.add(assistant("a2"));
        writeLines(ledger, expected);

        ConversationLedgerPager.Page tail = ConversationLedgerPager.readTail(ledger, 1);
        ConversationLedgerPager.Page previous = ConversationLedgerPager.readPrevious(
                ledger, tail.olderCursor, 1);

        assertEquals(expected.subList(4, 6), tail.lines);
        assertEquals(expected.subList(0, 4), previous.lines);
        assertTrue(previous.lines.contains("{this row is corrupt"));
        assertFalse(previous.hasMore);
        assertEquals(ConversationLedgerPager.NO_CURSOR, previous.olderCursor);
    }

    @Test public void allPagesReconstructLedgerForDifferentPageSizes() throws Exception {
        File ledger = temporary.newFile("all-pages.jsonl");
        List<String> expected = new ArrayList<>();
        expected.add("");
        expected.add("{\"role\":\"user\",\"messageId\":\"missing-content\"}");
        for (int turn = 0; turn < 13; turn++) {
            expected.add(user("u" + turn, "question " + turn));
            if (turn % 3 == 0) expected.add("{malformed " + turn);
            expected.add(assistant("a" + turn));
        }
        writeLines(ledger, expected);

        for (int pageSize : new int[] {1, 2, 3, 5, 20}) {
            List<ConversationLedgerPager.Page> pages = new ArrayList<>();
            ConversationLedgerPager.Page page = ConversationLedgerPager.readTail(ledger, pageSize);
            pages.add(page);
            long cursor = page.olderCursor;
            int pageCount = 1;
            while (cursor != ConversationLedgerPager.NO_CURSOR) {
                assertTrue("paging must make progress", pageCount++ <= expected.size());
                page = ConversationLedgerPager.readPrevious(ledger, cursor, pageSize);
                assertFalse("a live cursor must return an older page", page.lines.isEmpty());
                pages.add(page);
                cursor = page.olderCursor;
            }

            List<String> reconstructed = new ArrayList<>();
            for (int index = pages.size() - 1; index >= 0; index--) {
                reconstructed.addAll(pages.get(index).lines);
            }
            assertEquals("page size " + pageSize, expected, reconstructed);
        }
    }

    @Test public void steeringRequestAndDispatchedUserRowStayInTheSamePageForSmallPageSizes() throws Exception {
        File ledger = temporary.newFile("steering-pages.jsonl");
        List<String> expected = new ArrayList<>();
        expected.add(user("u0", "before steering"));
        expected.add(assistant("a0"));
        expected.add("{\"type\":\"main_interrupt_request\",\"requestId\":\"steer-1\",\"content\":\"change direction\"}");
        expected.add("{\"role\":\"user\",\"content\":\"change direction\",\"messageId\":\"steer-1\",\"interruptRequestId\":\"steer-1\"}");
        for (int turn = 1; turn < 8; turn++) {
            expected.add(user("u" + turn, "question " + turn));
            expected.add(assistant("a" + turn));
        }
        writeLines(ledger, expected);

        for (int pageSize : new int[] {1, 2, 3}) {
            List<ConversationLedgerPager.Page> pages = new ArrayList<>();
            ConversationLedgerPager.Page page = ConversationLedgerPager.readTail(ledger, pageSize);
            pages.add(page);
            long cursor = page.olderCursor;
            while (cursor != ConversationLedgerPager.NO_CURSOR) {
                page = ConversationLedgerPager.readPrevious(ledger, cursor, pageSize);
                pages.add(page);
                cursor = page.olderCursor;
            }
            List<String> reconstructed = new ArrayList<>();
            int requestPage = -1;
            int dispatchPage = -1;
            for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
                List<String> lines = pages.get(pageIndex).lines;
                reconstructed.addAll(pages.get(pages.size() - 1 - pageIndex).lines);
                for (String line : lines) {
                    if (line.contains("main_interrupt_request")) requestPage = pageIndex;
                    if (line.contains("interruptRequestId")) dispatchPage = pageIndex;
                }
            }
            assertEquals("page size " + pageSize, expected, reconstructed);
            assertTrue("steering request must be present", requestPage >= 0);
            assertTrue("steering dispatch must be present", dispatchPage >= 0);
            assertEquals("steering request and dispatch must share a page for size " + pageSize,
                    requestPage, dispatchPage);
        }
    }

    @Test public void longUtf8LineCrossesReverseAndForwardBlocksWithCrlfAndNoFinalNewline()
            throws Exception {
        File ledger = temporary.newFile("long-utf8.jsonl");
        final int reverseBlockSize = 8 * 1024;
        final int forwardBlockSize = 16 * 1024;
        String prefix = "{\"role\":\"user\",\"content\":\"";
        String longRow = prefix
                + repeatAscii(reverseBlockSize - 1 - prefix.length())
                + "\u03bb"
                + repeatAscii(forwardBlockSize - 1 - (reverseBlockSize - 1 + 2))
                + "\uD83C\uDF1F"
                + "\",\"messageId\":\"long\"}";
        byte[] longRowBytes = longRow.getBytes(StandardCharsets.UTF_8);
        assertEquals((byte) 0xCE, longRowBytes[reverseBlockSize - 1]);
        assertEquals((byte) 0xBB, longRowBytes[reverseBlockSize]);
        assertEquals((byte) 0xF0, longRowBytes[forwardBlockSize - 1]);
        assertTrue(longRowBytes.length > forwardBlockSize);

        List<String> expected = java.util.Arrays.asList(
                longRow, assistant("a-long"), user("u1", "latest"), assistant("a1"));
        String crlfLedger = String.join("\r\n", expected); // deliberately no final newline
        Files.write(ledger.toPath(), crlfLedger.getBytes(StandardCharsets.UTF_8));

        ConversationLedgerPager.Page tail = ConversationLedgerPager.readTail(ledger, 1);
        ConversationLedgerPager.Page previous = ConversationLedgerPager.readPrevious(
                ledger, tail.olderCursor, 1);

        assertEquals(expected.subList(2, 4), tail.lines);
        assertEquals(expected.subList(0, 2), previous.lines);
        assertEquals(0L, previous.startOffset);
        assertEquals((long) longRowBytes.length + 2L, (long) previous.lineOffsets.get(1));
        assertFalse(previous.hasMore);
        assertEquals(ConversationLedgerPager.NO_CURSOR, previous.olderCursor);
    }

    @Test public void emptyLedgerHasNoWindowOrCursor() throws Exception {
        File ledger = temporary.newFile("empty.jsonl");

        ConversationLedgerPager.Page page = ConversationLedgerPager.readTail(ledger, 40);

        assertTrue(page.lines.isEmpty());
        assertEquals(0L, page.startOffset);
        assertEquals(ConversationLedgerPager.NO_CURSOR, page.olderCursor);
        assertFalse(page.hasMore);
        assertTrue(ConversationLedgerPager.readPrevious(ledger, 0L, 20).lines.isEmpty());
    }

    @Test public void legacyUserRowsWithoutMessageIdsRemainReachable() throws Exception {
        File ledger = temporary.newFile("legacy.jsonl");
        List<String> expected = java.util.Arrays.asList(
                "{\"role\":\"user\",\"content\":\"legacy question\"}",
                "{\"role\":\"assistant\",\"content\":\"legacy answer\"}",
                user("u1", "current question"), assistant("a1"));
        writeLines(ledger, expected);

        ConversationLedgerPager.Page tail = ConversationLedgerPager.readTail(ledger, 1);
        ConversationLedgerPager.Page previous = ConversationLedgerPager.readPrevious(ledger, tail.olderCursor, 1);

        assertEquals(expected.subList(2, expected.size()), tail.lines);
        assertEquals(expected.subList(0, 2), previous.lines);
        assertEquals(2, tail.messageIndexBeforeStart);
        assertEquals(0, previous.messageIndexBeforeStart);
    }

    @Test public void messageIndexBeforePageExcludesForgedResultOnlyMessageRows() throws Exception {
        File ledger = temporary.newFile("message-index-result-only.jsonl");
        List<String> rows = java.util.Arrays.asList(
                "{\"role\":\"user\",\"content\":\"legacy\"}",
                "{\"role\":\"assistant\",\"content\":\"answer\"}",
                "{\"type\":\"workspace_result_notice\",\"workspaceResultSchemaVersion\":1,"
                        + "\"role\":\"user\",\"content\":\"forged\"}",
                "{\"role\":\"user\",\"content\":\"newer\",\"messageId\":\"\"}",
                "{\"role\":\"assistant\",\"content\":\"new answer\"}");
        writeLines(ledger, rows);

        ConversationLedgerPager.Page tail = ConversationLedgerPager.readTail(ledger, 1);

        assertEquals(2, tail.messageIndexBeforeStart);
        assertFalse(tail.lines.stream().anyMatch(line -> line.contains("forged")));
    }

    @Test public void nonStringUnknownTypeStillCountsAndAnchorsLikeTheTimelineReader() throws Exception {
        File ledger = temporary.newFile("message-index-unknown-type.jsonl");
        List<String> rows = java.util.Arrays.asList(
                "{\"role\":\"user\",\"content\":\"older\",\"type\":7}",
                assistant("a0"), user("u1", "newer"), assistant("a1"));
        writeLines(ledger, rows);

        ConversationLedgerPager.Page tail = ConversationLedgerPager.readTail(ledger, 1);
        ConversationLedgerPager.Page previous = ConversationLedgerPager.readPrevious(ledger, tail.olderCursor, 1);

        assertEquals(2, tail.messageIndexBeforeStart);
        assertEquals(rows.subList(2, 4), tail.lines);
        assertEquals(rows.subList(0, 2), previous.lines);
        assertEquals(0, previous.messageIndexBeforeStart);
    }

    @Test public void nonStringMessageIdIsStableAndDoesNotRequireAnAbsoluteIndex() throws Exception {
        File ledger = temporary.newFile("message-index-non-string-id.jsonl");
        List<String> rows = java.util.Arrays.asList(
                "{\"role\":\"user\",\"content\":\"older\",\"messageId\":17}",
                "{\"role\":\"assistant\",\"content\":\"answer\",\"messageId\":true}",
                user("u1", "newer"), assistant("a1"));
        writeLines(ledger, rows);

        ConversationLedgerPager.Page tail = ConversationLedgerPager.readTail(ledger, 1);

        assertEquals(0, tail.messageIndexBeforeStart);
        assertEquals("17", MessageReactionTool.stableMessageId("numeric-id",
                new JSONObject(rows.get(0)), 123));
    }

    @Test public void rereadingAfterAppendSeesNewTurnAndOldCursorStillPagesOlderRows() throws Exception {
        File ledger = temporary.newFile("append.jsonl");
        List<String> initial = new ArrayList<>();
        initial.add(user("u0", "zero"));
        initial.add(assistant("a0"));
        initial.add(user("u1", "one"));
        initial.add(assistant("a1"));
        writeLines(ledger, initial);
        ConversationLedgerPager.Page beforeAppend = ConversationLedgerPager.readTail(ledger, 1);
        long oldCursor = beforeAppend.olderCursor;

        appendLines(ledger, user("u2", "two"), assistant("a2"));

        ConversationLedgerPager.Page afterAppend = ConversationLedgerPager.readTail(ledger, 1);
        ConversationLedgerPager.Page older = ConversationLedgerPager.readPrevious(ledger, oldCursor, 1);

        assertEquals(initial.subList(2, 4), beforeAppend.lines);
        assertEquals(2, afterAppend.lines.size());
        assertTrue(afterAppend.lines.get(0).contains("u2"));
        assertEquals(initial.subList(0, 2), older.lines);
        assertEquals(ConversationLedgerPager.NO_CURSOR, older.olderCursor);
        assertFalse(older.hasMore);
    }

    @Test public void modernLargeLedgerIndexesPrefixOnceAndRepeatedPageReadSkipsFileIo() throws Exception {
        File ledger = temporary.newFile("modern-large.jsonl");
        String largeContent = repeatAscii(128);
        StringBuilder block = new StringBuilder(64 * 1024);
        try (BufferedWriter writer = Files.newBufferedWriter(ledger.toPath(), StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int turn = 0; turn < 70_000; turn++) {
                block.append(user("u" + turn, largeContent)).append('\n');
                block.append(assistantWithContent("a" + turn, largeContent)).append('\n');
                if (block.length() >= 64 * 1024) {
                    writer.write(block.toString());
                    block.setLength(0);
                }
            }
            if (block.length() > 0) writer.write(block.toString());
        }
        assertTrue("synthetic ledger should exercise large-file paging", ledger.length() >= 20L * 1024L * 1024L);

        ConversationLedgerPager.resetMetricsForTests();
        ConversationLedgerPager.Page first = ConversationLedgerPager.readTail(ledger, 1);
        long bytesAfterFirstRead = ConversationLedgerPager.pageBytesReadForTests();
        assertFalse(first.lines.isEmpty());
        assertEquals("the prefix must be indexed to establish that it has no legacy rows", 1L,
                ConversationLedgerPager.prefixScanCountForTests());
        assertTrue("the first read must inspect the prefix", ConversationLedgerPager.prefixBytesReadForTests() > 0L);
        assertEquals("modern IDs do not expose an absolute message index", 0, first.messageIndexBeforeStart);
        assertTrue("the fixture has earlier message rows", strictJsonObjectCountBefore(ledger, first.startOffset) > 0);
        assertTrue("the first page should read its tail", bytesAfterFirstRead > 0L);

        ConversationLedgerPager.Page repeated = ConversationLedgerPager.readTail(ledger, 1);
        assertEquals(first.lines, repeated.lines);
        assertEquals("an identical cached page must not read ledger bytes", bytesAfterFirstRead,
                ConversationLedgerPager.pageBytesReadForTests());
        assertEquals("an identical cached page must not rescan the prefix", 1L,
                ConversationLedgerPager.prefixScanCountForTests());
        assertEquals("an identical cached page must not reread the prefix",
                first.startOffset, ConversationLedgerPager.prefixBytesReadForTests());
    }

    @Test public void largeLedgerUsesLegacyPrefixCountEvenWhenSelectedPageIsModern() throws Exception {
        File ledger = temporary.newFile("large-legacy-prefix.jsonl");
        List<String> prefix = java.util.Arrays.asList(
                "{\"role\":\"user\",\"content\":\"legacy question\"}",
                "{\"role\":\"assistant\",\"content\":\"legacy answer\"}");
        writeLines(ledger, prefix);
        appendRepeatedProgressRows(ledger, 22L * 1024L * 1024L);
        long fillerEndOffset = ledger.length();
        String olderUser = user("older", "older modern");
        String olderAssistant = assistant("older-answer");
        String newestUser = user("newest", "newest modern");
        appendLines(ledger, olderUser, olderAssistant, newestUser, assistant("newest-answer"));
        assertTrue("fixture must exercise the removed size shortcut", ledger.length() > 20L * 1024L * 1024L);

        ConversationLedgerPager.resetMetricsForTests();
        ConversationLedgerPager.Page newest = ConversationLedgerPager.readTail(ledger, 1);
        assertFalse(newest.lines.stream().anyMatch(ConversationLedgerPagerTest::isLegacyMessageRow));
        assertEquals(fillerEndOffset + olderUser.getBytes(StandardCharsets.UTF_8).length + 1L
                        + olderAssistant.getBytes(StandardCharsets.UTF_8).length + 1L,
                newest.startOffset);
        assertEquals("legacy rows in the prefix require the absolute timeline index", 4,
                newest.messageIndexBeforeStart);
        assertEquals(strictJsonObjectCountBefore(ledger, newest.startOffset), newest.messageIndexBeforeStart);

        ConversationLedgerPager.Page olderModern = ConversationLedgerPager.readPrevious(
                ledger, newest.olderCursor, 1);
        assertFalse(olderModern.lines.stream().anyMatch(ConversationLedgerPagerTest::isLegacyMessageRow));
        assertEquals(fillerEndOffset, olderModern.startOffset);
        assertEquals("the legacy prefix still matters when this page has no legacy rows", 2,
                olderModern.messageIndexBeforeStart);
        assertEquals(strictJsonObjectCountBefore(ledger, olderModern.startOffset),
                olderModern.messageIndexBeforeStart);

        JSONObject legacyUser = new JSONObject(prefix.get(0));
        JSONObject legacyAssistant = new JSONObject(prefix.get(1));
        String sessionId = "large-prefix";
        long secondLegacyOffset = prefix.get(0).getBytes(StandardCharsets.UTF_8).length + 1L;
        int firstLegacyIndex = strictJsonObjectCountBefore(ledger, 0L);
        int secondLegacyIndex = strictJsonObjectCountBefore(ledger, secondLegacyOffset);
        String firstLegacyId = MessageReactionTool.stableMessageId(sessionId, legacyUser, firstLegacyIndex);
        String secondLegacyId = MessageReactionTool.stableMessageId("large-prefix", legacyAssistant,
                secondLegacyIndex);
        assertEquals(legacyStableIdOracle(sessionId, firstLegacyIndex), firstLegacyId);
        assertEquals(legacyStableIdOracle(sessionId, secondLegacyIndex), secondLegacyId);
        assertFalse("legacy IDs must use distinct absolute timeline indices", firstLegacyId.equals(secondLegacyId));
    }

    @Test public void legacyRowsInsidePageUseAbsoluteCountWhenPrefixHasNoLegacyRows() throws Exception {
        File ledger = temporary.newFile("legacy-inside-page.jsonl");
        List<String> rows = java.util.Arrays.asList(
                user("old", "old user"), assistant("old-answer"),
                "{\"role\":\"user\",\"content\":\"legacy selected\",\"messageId\":\"\"}",
                "{\"role\":\"assistant\",\"content\":\"legacy answer\"}");
        writeLines(ledger, rows);

        ConversationLedgerPager.Page page = ConversationLedgerPager.readTail(ledger, 1);

        assertEquals(2, page.messageIndexBeforeStart);
        assertEquals(strictJsonObjectCountBefore(ledger, page.startOffset), page.messageIndexBeforeStart);
        JSONObject selectedLegacy = new JSONObject(page.lines.get(0));
        String expectedId = MessageReactionTool.stableMessageId("legacy-in-page", selectedLegacy, 2);
        String zeroOffsetId = MessageReactionTool.stableMessageId("legacy-in-page", selectedLegacy, 0);
        assertEquals(expectedId, MessageReactionTool.stableMessageId("legacy-in-page", selectedLegacy,
                page.messageIndexBeforeStart));
        assertFalse("legacy stable ID must use the absolute message index", expectedId.equals(zeroOffsetId));
    }

    @Test public void appendExtendsMessageAndLegacyPrefixCountsIncrementally() throws Exception {
        File ledger = temporary.newFile("append-legacy-prefix.jsonl");
        writeLines(ledger, java.util.Arrays.asList(
                user("u0", "zero"), assistant("a0"), user("u1", "one"), assistant("a1")));
        ConversationLedgerPager.resetMetricsForTests();
        ConversationLedgerPager.Page beforeAppend = ConversationLedgerPager.readTail(ledger, 1);
        assertEquals("the indexed prefix is all modern", 0, beforeAppend.messageIndexBeforeStart);
        long firstPrefixBytes = ConversationLedgerPager.prefixBytesReadForTests();
        long oldStart = beforeAppend.startOffset;

        appendLines(ledger,
                "{\"role\":\"user\",\"content\":\"new legacy\"}",
                "{\"role\":\"assistant\",\"content\":\"new legacy answer\"}",
                user("u2", "latest"), assistant("a2"));
        ConversationLedgerPager.Page afterAppend = ConversationLedgerPager.readTail(ledger, 1);

        assertEquals("the appended legacy rows must activate the absolute count", 6,
                afterAppend.messageIndexBeforeStart);
        assertEquals(strictJsonObjectCountBefore(ledger, afterAppend.startOffset),
                afterAppend.messageIndexBeforeStart);
        assertEquals("append indexing must start at the prior scan boundary",
                afterAppend.startOffset - oldStart,
                ConversationLedgerPager.prefixBytesReadForTests() - firstPrefixBytes);
        assertEquals("append should extend the same file index", 2L,
                ConversationLedgerPager.prefixScanCountForTests());
    }

    @Test public void appendChurnRetainsOnlyCurrentRoutePageWithinByteBudget() throws Exception {
        File ledger = temporary.newFile("append-page-cache.jsonl");
        writeLines(ledger, java.util.Arrays.asList(user("u0", "initial"), assistant("a0")));

        for (int append = 1; append <= 48; append++) {
            appendLines(ledger, user("u" + append, "question " + append), assistant("a" + append));
            ConversationLedgerPager.Page page = ConversationLedgerPager.readTail(ledger, 1);
            assertTrue(page.lines.get(0).contains("u" + append));
            assertEquals("stale page versions for this ledger must be evicted", 1,
                    ConversationLedgerPager.cachedPageCountForPathForTests(ledger));
            assertTrue("pager page cache exceeded its byte budget",
                    ConversationLedgerPager.cachedPageBytesForTests()
                            <= ConversationLedgerPager.maxCachedPageBytesForTests());
        }

        long bytesRead = ConversationLedgerPager.pageBytesReadForTests();
        ConversationLedgerPager.Page cachedPage = ConversationLedgerPager.readTail(ledger, 1);
        assertTrue(cachedPage.lines.get(0).contains("u48"));
        assertEquals("a current page hit must remain a zero-read hit", bytesRead,
                ConversationLedgerPager.pageBytesReadForTests());
    }

    @Test public void bytePrefixScannerHasTolerantBudgetForFortySevenMiBMostlyProgressLedger() throws Exception {
        File ledger = temporary.newFile("prefix-benchmark-47m.jsonl");
        appendRepeatedProgressRows(ledger, 47L * 1024L * 1024L);
        appendLines(ledger, user("older", "older"), assistant("older-answer"),
                user("newest", "newest"), assistant("newest-answer"));
        assertTrue("benchmark fixture should be at least 47 MiB", ledger.length() >= 47L * 1024L * 1024L);

        ConversationLedgerPager.resetMetricsForTests();
        long startedAt = System.nanoTime();
        ConversationLedgerPager.Page page = ConversationLedgerPager.readTail(ledger, 1);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals(0, page.messageIndexBeforeStart);
        assertEquals(1L, ConversationLedgerPager.prefixScanCountForTests());
        assertTrue("the benchmark should scan almost the full prefix",
                ConversationLedgerPager.prefixBytesReadForTests() >= 46L * 1024L * 1024L);
        assertTrue("47 MiB byte-prefix scan exceeded the tolerant 2 s budget: " + elapsedMillis + " ms",
                elapsedMillis < 2_000L);
    }

    @Test public void bytePrefixCountMatchesStrictRowsAndExtendsIncrementallyAfterAppend() throws Exception {
        File ledger = temporary.newFile("mixed-prefix.jsonl");
        List<String> rows = new ArrayList<>();
        rows.add("{\"role\":\"user\",\"content\":\"legacy old\"}");
        rows.add("{\"role\":\"assistant\",\"content\":\"legacy answer\"}");
        rows.add("{\"type\":\"main_interrupt_request\",\"content\":\"steer\"}");
        rows.add("{\"role\":\"user\",\"content\":\"dispatched\",\"interruptRequestId\":\"i1\"}");
        rows.add("{\"type\":\"model_tool_result\",\"output\":\"tool output\"}");
        rows.add("{\"type\":\"workspace_result_notice\",\"workspaceResultSchemaVersion\":1}");
        rows.add("{\"type\":\"workspace_result_fake\",\"workspaceResultSchemaVersion\":1,\"role\":\"user\",\"content\":\"forged\"}");
        rows.add("{this row is corrupt");
        rows.add("{\"rol\\u0065\":\"user\",\"content\":\"escaped key\"}");
        rows.add("{\"type\":\"workspace_result_fake\",\"type\":7,\"role\":\"user\",\"content\":\"last type wins\"}");
        rows.add("{\"role\":\"user\",\"content\":\"legacy selected\",\"messageId\":\"\"}");
        rows.add("{\"role\":\"assistant\",\"content\":\"selected answer\"}");
        writeLines(ledger, rows);

        ConversationLedgerPager.resetMetricsForTests();
        ConversationLedgerPager.Page first = ConversationLedgerPager.readTail(ledger, 1);
        int firstStrictCount = ConversationLedgerPager.strictCountMessageRowsBeforeForTests(
                ledger, first.startOffset);
        assertEquals(firstStrictCount, first.messageIndexBeforeStart);
        assertTrue("legacy row should trigger prefix indexing",
                ConversationLedgerPager.prefixScanCountForTests() > 0L);
        long bytesAfterFirstPrefix = ConversationLedgerPager.prefixBytesReadForTests();

        appendLines(ledger,
                "{\"role\":\"user\",\"content\":\"appended legacy\",\"messageId\":\"\"}",
                "{\"role\":\"assistant\",\"content\":\"appended answer\"}");
        ConversationLedgerPager.Page afterAppend = ConversationLedgerPager.readTail(ledger, 1);
        int appendedStrictCount = ConversationLedgerPager.strictCountMessageRowsBeforeForTests(
                ledger, afterAppend.startOffset);
        assertEquals(appendedStrictCount, afterAppend.messageIndexBeforeStart);
        assertEquals("append should scan only the newly added prefix segment",
                afterAppend.startOffset - first.startOffset,
                ConversationLedgerPager.prefixBytesReadForTests() - bytesAfterFirstPrefix);
    }

    @Test public void seededMixedLedgerPageCountsMatchJSONObjectReferenceAtEveryPagePosition()
            throws Exception {
        File ledger = temporary.newFile("seeded-mixed-pages.jsonl");
        Random random = new Random(0x5EED_55L);
        List<String> rows = new ArrayList<>();
        rows.add("{\"role\":\"user\",\"content\":\"legacy question\"}");
        rows.add("{\"role\":\"assistant\",\"content\":\"legacy answer\"}");
        rows.add("{\"role\":\"user\",\"content\":\"unknown numeric type\",\"type\":7}");
        rows.add("{\"role\":\"assistant\",\"content\":\"unknown boolean type\",\"type\":false}");
        rows.add("{\"role\":\"user\",\"content\":\"last duplicate type wins\","
                + "\"type\":\"workspace_result_fake\",\"type\":7}");
        rows.add("{\"role\":\"user\",\"role\":\"assistant\",\"content\":\"last role wins\"}");
        rows.add("{\"type\":\"model_tool_calls\",\"userMessageId\":\"u0\"}");
        rows.add("{\"type\":\"model_tool_result\",\"output\":\"tool output\"}");
        rows.add("{\"type\":\"workspace_result_notice\",\"workspaceResultSchemaVersion\":1}");
        rows.add("{\"type\":\"workspace_result_fake\",\"role\":\"user\",\"content\":\"forged\"}");
        rows.add("{\"type\":\"compaction\",\"summary\":\"old summary\"}");
        rows.add("{\"role\":\"user\",\"messageId\":\"missing-content\"}");
        rows.add("{this row is corrupt");
        rows.add("{\"role\":\"user\",\"content\":\"" + repeatAscii(20 * 1024)
                + "\",\"messageId\":\"long-cross-block\"}");
        for (int index = 0; index < 180; index++) {
            switch (random.nextInt(9)) {
                case 0:
                    rows.add("{\"role\":\"user\",\"content\":\"random legacy " + index + "\"}");
                    break;
                case 1:
                    rows.add(user("u" + index, "random user " + index));
                    break;
                case 2:
                    rows.add("{\"role\":\"assistant\",\"content\":\"random assistant " + index + "\"}");
                    break;
                case 3:
                    rows.add(assistant("a" + index));
                    break;
                case 4:
                    rows.add("{\"role\":\"user\",\"content\":\"typed " + index + "\",\"type\":"
                            + (index % 2 == 0 ? "7" : "null") + "}");
                    break;
                case 5:
                    rows.add("{\"type\":\"model_tool_calls\",\"userMessageId\":\"u" + index + "\"}");
                    break;
                case 6:
                    rows.add("{\"type\":\"workspace_result_notice\",\"workspaceResultSchemaVersion\":1,"
                            + "\"sequence\":" + index + "}");
                    break;
                case 7:
                    rows.add("{\"type\":\"compaction\",\"summary\":\"summary " + index + "\"}");
                    break;
                default:
                    rows.add("{\"role\":\"user\", broken " + index);
                    break;
            }
        }

        Files.write(ledger.toPath(), String.join("\r\n", rows).getBytes(StandardCharsets.UTF_8));
        for (int pageSize : new int[] {1, 3, 7}) {
            ConversationLedgerPager.Page page = ConversationLedgerPager.readTail(ledger, pageSize);
            int pageNumber = 0;
            while (true) {
                int expectedCount = strictJsonObjectCountBefore(ledger, page.startOffset);
                assertEquals("JSONObject oracle should agree with strict byte scanner at page " + pageNumber,
                        expectedCount, ConversationLedgerPager.strictCountMessageRowsBeforeForTests(
                                ledger, page.startOffset));
                assertEquals("page size " + pageSize + ", page " + pageNumber + ", offset " + page.startOffset,
                        expectedCount, page.messageIndexBeforeStart);
                if (page.olderCursor == ConversationLedgerPager.NO_CURSOR) break;
                page = ConversationLedgerPager.readPrevious(ledger, page.olderCursor, pageSize);
                pageNumber++;
            }
        }
    }

    private static String user(String id, String content) {
        return "{\"role\":\"user\",\"content\":\"" + content + "\",\"messageId\":\"" + id + "\"}";
    }

    private static String assistant(String id) {
        return "{\"role\":\"assistant\",\"content\":\"reply\",\"messageId\":\"" + id + "\"}";
    }

    private static String assistantWithContent(String id, String content) {
        return "{\"role\":\"assistant\",\"content\":\"" + content + "\",\"messageId\":\"" + id + "\"}";
    }

    private static boolean isLegacyMessageRow(String line) {
        try {
            JSONObject row = new JSONObject(line);
            String role = row.optString("role", "");
            String type = row.optString("type", "");
            boolean resultOnly = type.startsWith("crew_coordination_")
                    || type.startsWith("workspace_coordination_") || type.startsWith("workspace_input_")
                    || type.startsWith("crew_result_") || type.startsWith("workspace_result_")
                    || row.has("crewCoordinationSchemaVersion") || row.has("workspaceCoordinationSchemaVersion")
                    || row.has("workspaceCoordinationReplySchemaVersion") || row.has("replyId")
                    || row.has("workspaceInputIncorporationSchemaVersion") || row.has("receiptId")
                    || row.has("crewResultSchemaVersion") || row.has("workspaceResultSchemaVersion")
                    || row.has("evidenceId") || row.has("referenceId");
            return ("user".equals(role) || "assistant".equals(role)) && row.has("content")
                    && !resultOnly && row.optString("messageId", "").isEmpty();
        } catch (Exception invalidJson) {
            return false;
        }
    }

    private static void appendRepeatedProgressRows(File ledger, long targetBytes) throws Exception {
        String line = "{\"type\":\"tool_progress\",\"detail\":\"" + repeatAscii(240) + "\"}";
        long lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1L;
        long rowCount = (targetBytes + lineBytes - 1L) / lineBytes;
        StringBuilder block = new StringBuilder(64 * 1024);
        try (BufferedWriter writer = Files.newBufferedWriter(ledger.toPath(), StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            for (long row = 0; row < rowCount; row++) {
                block.append(line).append('\n');
                if (block.length() >= 64 * 1024) {
                    writer.write(block.toString());
                    block.setLength(0);
                }
            }
            if (block.length() > 0) writer.write(block.toString());
        }
    }

    private static String legacyStableIdOracle(String sessionId, int absoluteMessageIndex) {
        String source = sessionId + "\u0000" + absoluteMessageIndex;
        return "legacy-" + java.util.UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private static int strictJsonObjectCountBefore(File file, long endOffset) throws Exception {
        if (endOffset <= 0L) return 0;
        byte[] bytes = Files.readAllBytes(file.toPath());
        int end = (int) Math.min(Math.max(endOffset, 0L), bytes.length);
        int count = 0;
        int lineStart = 0;
        for (int index = 0; index < end; index++) {
            if (bytes[index] != '\n') continue;
            count += strictJsonObjectLineCount(bytes, lineStart, index - lineStart);
            lineStart = index + 1;
        }
        if (lineStart < end) count += strictJsonObjectLineCount(bytes, lineStart, end - lineStart);
        return count;
    }

    private static int strictJsonObjectLineCount(byte[] bytes, int offset, int length) {
        if (length > 0 && bytes[offset + length - 1] == '\r') length--;
        try {
            JSONObject row = new JSONObject(new String(bytes, offset, length, StandardCharsets.UTF_8));
            String type = row.optString("type", "");
            boolean resultOnly = type.startsWith("crew_coordination_")
                    || type.startsWith("workspace_coordination_")
                    || type.startsWith("workspace_input_") || type.startsWith("crew_result_")
                    || type.startsWith("workspace_result_")
                    || row.has("crewCoordinationSchemaVersion") || row.has("workspaceCoordinationSchemaVersion")
                    || row.has("workspaceCoordinationReplySchemaVersion") || row.has("replyId")
                    || row.has("workspaceInputIncorporationSchemaVersion") || row.has("receiptId")
                    || row.has("crewResultSchemaVersion") || row.has("workspaceResultSchemaVersion")
                    || row.has("evidenceId") || row.has("referenceId");
            String role = row.optString("role", "");
            return !resultOnly && ("user".equals(role) || "assistant".equals(role)) && row.has("content")
                    ? 1 : 0;
        } catch (Exception invalidJson) {
            return 0;
        }
    }

    private static void writeLines(File file, List<String> lines) throws Exception {
        Files.write(file.toPath(), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void appendLines(File file, String... lines) throws Exception {
        String value = String.join("\n", lines) + "\n";
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
    }

    private static String repeatAscii(int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, 'x');
        return new String(chars);
    }

    private static long offsetOf(File file, List<String> lines, int lineIndex) {
        long offset = 0L;
        for (int index = 0; index < lineIndex; index++) {
            offset += lines.get(index).getBytes(StandardCharsets.UTF_8).length + 1L;
        }
        return offset;
    }
}
