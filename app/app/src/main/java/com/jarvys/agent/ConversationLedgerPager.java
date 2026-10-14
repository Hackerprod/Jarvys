package com.jarvys.agent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads bounded, user-turn-aligned windows from a conversation's JSONL ledger. */
public final class ConversationLedgerPager {
    /** Sentinel indicating that there is no older page. */
    public static final long NO_CURSOR = -1L;

    private static final int REVERSE_BLOCK_SIZE = 8 * 1024;
    private static final int FORWARD_BLOCK_SIZE = 16 * 1024;

    private ConversationLedgerPager() { }

    /**
     * Reads the newest {@code turnCount} user-anchored turns, including all ledger rows
     * following the first selected user message through the end of the file.
     */
    public static Page readTail(File ledger, int turnCount) throws IOException {
        requireTurnCount(turnCount);
        if (ledger == null || !ledger.isFile()) return Page.empty();
        try (FileChannel channel = FileChannel.open(ledger.toPath(), StandardOpenOption.READ)) {
            return readWindow(channel, channel.size(), turnCount);
        }
    }

    /**
     * Reads the {@code turnCount} turns immediately older than {@code beforeOffset}.
     * The offset should be the {@link Page#olderCursor} returned by the newer page.
     */
    public static Page readPrevious(File ledger, long beforeOffset, int turnCount) throws IOException {
        requireTurnCount(turnCount);
        if (ledger == null || !ledger.isFile() || beforeOffset <= 0L) return Page.empty();
        try (FileChannel channel = FileChannel.open(ledger.toPath(), StandardOpenOption.READ)) {
            return readWindow(channel, Math.min(beforeOffset, channel.size()), turnCount);
        }
    }

    private static void requireTurnCount(int turnCount) {
        if (turnCount <= 0) throw new IllegalArgumentException("turnCount must be positive");
    }

    private static Page readWindow(FileChannel channel, long endOffset, int turnCount) throws IOException {
        if (endOffset <= 0L) return Page.empty();
        ReverseLineReader reader = new ReverseLineReader(channel, endOffset);
        int anchors = 0;
        long pageStart = -1L;
        boolean hasMore = false;
        Line line;
        while ((line = reader.previousLine()) != null) {
            if (!isUserMessageAnchor(line.text)) continue;
            anchors++;
            if (anchors == turnCount) {
                pageStart = line.start;
            } else if (anchors > turnCount) {
                hasMore = true;
                break;
            }
        }

        // If there are fewer anchors than requested, expose all available ledger rows.
        if (pageStart < 0L) pageStart = 0L;
        List<Line> readLines = readLines(channel, pageStart, endOffset);
        List<String> lines = new ArrayList<>(readLines.size());
        List<Long> lineOffsets = new ArrayList<>(readLines.size());
        for (Line value : readLines) {
            lines.add(value.text);
            lineOffsets.add(value.start);
        }
        long olderCursor = hasMore ? pageStart : NO_CURSOR;
        return new Page(lines, lineOffsets, pageStart, olderCursor, hasMore);
    }

    /**
     * A small top-level JSON field scanner avoids constructing JSONObject instances while
     * walking backwards. It accepts legacy user rows without message IDs.
     */
    private static boolean isUserMessageAnchor(String line) {
        if (line == null || line.indexOf("\"role\"") < 0 || line.indexOf("\"content\"") < 0) return false;
        JsonFieldScanner scanner = new JsonFieldScanner(line);
        return scanner.isUserMessageAnchor();
    }

    private static List<Line> readLines(FileChannel channel, long start, long end) throws IOException {
        if (end <= start) return Collections.emptyList();
        List<Line> lines = new ArrayList<>();
        ByteArrayOutputStream currentLine = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(FORWARD_BLOCK_SIZE);
        long position = start;
        long lineStart = start;
        while (position < end) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), end - position));
            int count = channel.read(buffer, position);
            if (count < 0) break;
            if (count == 0) continue;
            long chunkStart = position;
            position += count;
            buffer.flip();
            while (buffer.hasRemaining()) {
                byte value = buffer.get();
                if (value == '\n') {
                    lines.add(new Line(lineStart, decodeLine(currentLine.toByteArray())));
                    currentLine.reset();
                    lineStart = chunkStart + buffer.position();
                } else {
                    currentLine.write(value);
                }
            }
        }
        if (currentLine.size() > 0) lines.add(new Line(lineStart, decodeLine(currentLine.toByteArray())));
        return lines;
    }

    private static String decodeLine(byte[] bytes) {
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') length--;
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    public static final class Page {
        public final List<String> lines;
        /** Byte offset of each original line, used to keep event keys stable as windows move. */
        public final List<Long> lineOffsets;
        /** Byte offset of the first line in this page, suitable for stable slot identity. */
        public final long startOffset;
        /** Start offset of this page when an older page exists; otherwise {@link #NO_CURSOR}. */
        public final long olderCursor;
        public final boolean hasMore;

        private Page(List<String> lines, List<Long> lineOffsets, long startOffset, long olderCursor, boolean hasMore) {
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
            this.lineOffsets = Collections.unmodifiableList(new ArrayList<>(lineOffsets));
            this.startOffset = startOffset;
            this.olderCursor = olderCursor;
            this.hasMore = hasMore;
        }

        private static Page empty() {
            return new Page(Collections.emptyList(), Collections.emptyList(), 0L, NO_CURSOR, false);
        }
    }

    private static final class Line {
        final long start;
        final String text;

        Line(long start, String text) {
            this.start = start;
            this.text = text;
        }
    }

    /** A byte-oriented reverse reader, so it does not decode or parse earlier ledger rows. */
    private static final class ReverseLineReader {
        private final FileChannel channel;
        private final byte[] block = new byte[REVERSE_BLOCK_SIZE];
        private long position;
        private long blockStart = -1L;
        private int blockLength;

        ReverseLineReader(FileChannel channel, long endOffset) {
            this.channel = channel;
            this.position = endOffset;
        }

        Line previousLine() throws IOException {
            // A trailing line delimiter terminates the preceding record; it is not an
            // additional empty record. Also skip consecutive blank records at the tail.
            while (position > 0L && byteAt(position - 1L) == '\n') position--;
            if (position <= 0L) return null;

            long end = position;
            long scan = end - 1L;
            while (scan >= 0L && byteAt(scan) != '\n') scan--;
            long start = scan + 1L;
            position = start;
            return new Line(start, decodeLine(readRange(start, end)));
        }

        private byte byteAt(long offset) throws IOException {
            if (blockStart < 0L || offset < blockStart || offset >= blockStart + blockLength) {
                blockStart = (offset / REVERSE_BLOCK_SIZE) * REVERSE_BLOCK_SIZE;
                ByteBuffer target = ByteBuffer.wrap(block);
                target.clear();
                long readPosition = blockStart;
                while (target.hasRemaining()) {
                    int count = channel.read(target, readPosition);
                    if (count <= 0) break;
                    readPosition += count;
                }
                blockLength = target.position();
            }
            int index = (int) (offset - blockStart);
            if (index < 0 || index >= blockLength) throw new IOException("Ledger changed while paging");
            return block[index];
        }

        private byte[] readRange(long start, long end) throws IOException {
            long length = end - start;
            if (length < 0L || length > Integer.MAX_VALUE) throw new IOException("Ledger row is too large");
            byte[] bytes = new byte[(int) length];
            ByteBuffer target = ByteBuffer.wrap(bytes);
            long readPosition = start;
            while (target.hasRemaining()) {
                int count = channel.read(target, readPosition);
                if (count <= 0) throw new IOException("Ledger changed while paging");
                readPosition += count;
            }
            return bytes;
        }
    }

    /** Minimal scanner for top-level role and messageId fields in a JSON object. */
    private static final class JsonFieldScanner {
        private final String json;
        private int index;
        private boolean userRole;
        private boolean content;

        JsonFieldScanner(String json) {
            this.json = json;
        }

        boolean isUserMessageAnchor() {
            skipWhitespace();
            if (!take('{')) return false;
            while (true) {
                skipWhitespace();
                if (take('}')) return finishObject();
                String key = readString();
                if (key == null) return false;
                skipWhitespace();
                if (!take(':')) return false;
                skipWhitespace();
                if ("role".equals(key)) {
                    String value = readString();
                    if (value == null) return false;
                    userRole = "user".equals(value);
                } else if ("content".equals(key)) {
                    content = skipString();
                    if (!content) return false;
                } else if (!skipValue()) {
                    return false;
                }
                skipWhitespace();
                if (take(',')) continue;
                if (take('}')) return finishObject();
                return false;
            }
        }

        private boolean skipValue() {
            if (index >= json.length()) return false;
            char first = json.charAt(index);
            if (first == '"') return readString() != null;
            if (first == '{' || first == '[') {
                int depth = 0;
                boolean inString = false;
                boolean escaped = false;
                while (index < json.length()) {
                    char ch = json.charAt(index++);
                    if (inString) {
                        if (escaped) escaped = false;
                        else if (ch == '\\') escaped = true;
                        else if (ch == '"') inString = false;
                    } else if (ch == '"') {
                        inString = true;
                    } else if (ch == '{' || ch == '[') {
                        depth++;
                    } else if (ch == '}' || ch == ']') {
                        depth--;
                        if (depth == 0) return true;
                    }
                }
                return false;
            }
            int start = index;
            while (index < json.length()) {
                char ch = json.charAt(index);
                if (ch == ',' || ch == '}' || Character.isWhitespace(ch)) break;
                index++;
            }
            return index > start;
        }

        private String readString() {
            if (!take('"')) return null;
            StringBuilder value = new StringBuilder();
            while (index < json.length()) {
                char ch = json.charAt(index++);
                if (ch == '"') return value.toString();
                if (ch != '\\') {
                    if (ch < 0x20) return null;
                    value.append(ch);
                    continue;
                }
                if (index >= json.length()) return null;
                char escaped = json.charAt(index++);
                switch (escaped) {
                    case '"': value.append('"'); break;
                    case '\\': value.append('\\'); break;
                    case '/': value.append('/'); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u':
                        if (index + 4 > json.length()) return null;
                        try { value.append((char) Integer.parseInt(json.substring(index, index + 4), 16)); }
                        catch (NumberFormatException invalidEscape) { return null; }
                        index += 4;
                        break;
                    default: return null;
                }
            }
            return null;
        }

        private boolean skipString() {
            if (!take('"')) return false;
            while (index < json.length()) {
                char ch = json.charAt(index++);
                if (ch == '"') return true;
                if (ch < 0x20) return false;
                if (ch == '\\') {
                    if (index >= json.length()) return false;
                    char escaped = json.charAt(index++);
                    if (escaped == 'u') {
                        if (index + 4 > json.length()) return false;
                        for (int digit = 0; digit < 4; digit++) {
                            if (Character.digit(json.charAt(index + digit), 16) < 0) return false;
                        }
                        index += 4;
                    } else if (escaped != '"' && escaped != '\\' && escaped != '/'
                            && escaped != 'b' && escaped != 'f' && escaped != 'n'
                            && escaped != 'r' && escaped != 't') {
                        return false;
                    }
                }
            }
            return false;
        }

        private boolean finishObject() {
            skipWhitespace();
            return index == json.length() && userRole && content;
        }

        private void skipWhitespace() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) index++;
        }

        private boolean take(char expected) {
            if (index < json.length() && json.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }
    }
}
