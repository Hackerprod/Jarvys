package com.jarvys.agent;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConversationLedgerPagerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

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

    private static String user(String id, String content) {
        return "{\"role\":\"user\",\"content\":\"" + content + "\",\"messageId\":\"" + id + "\"}";
    }

    private static String assistant(String id) {
        return "{\"role\":\"assistant\",\"content\":\"reply\",\"messageId\":\"" + id + "\"}";
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
