package com.jarvys.agent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.json.JSONObject;

/** Reads bounded, user-turn-aligned windows from a conversation's JSONL ledger. */
public final class ConversationLedgerPager {
    /** Sentinel indicating that there is no older page. */
    public static final long NO_CURSOR = -1L;

    private static final int REVERSE_BLOCK_SIZE = 8 * 1024;
    private static final int FORWARD_BLOCK_SIZE = 16 * 1024;
    private static final int PREFIX_CHECKPOINT_BYTES = 1024 * 1024;
    private static final int MAX_CACHED_PAGES = 32;
    private static final byte[] ROLE_TOKEN = ascii("\"role\"");
    private static final byte[] CONTENT_TOKEN = ascii("\"content\"");
    private static final byte[] USER_TOKEN = ascii("\"user\"");
    private static final byte[] ASSISTANT_TOKEN = ascii("\"assistant\"");
    private static final byte[] ROLE_WORD = ascii("role");
    private static final byte[] CONTENT_WORD = ascii("content");
    private static final byte[] USER_WORD = ascii("user");
    private static final byte[] ASSISTANT_WORD = ascii("assistant");
    private static final Map<String, Page> PAGE_CACHE = new LinkedHashMap<String, Page>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Page> eldest) {
            return size() > MAX_CACHED_PAGES;
        }
    };
    private static final Map<String, PrefixIndex> PREFIX_INDEXES = new LinkedHashMap<>();
    private static long pageBytesRead;
    private static long prefixBytesRead;
    private static long prefixScanCount;

    private ConversationLedgerPager() { }

    /**
     * Reads the newest {@code turnCount} user-anchored turns, including all ledger rows
     * following the first selected user message through the end of the file.
     */
    public static Page readTail(File ledger, int turnCount) throws IOException {
        requireTurnCount(turnCount);
        if (ledger == null) return Page.empty();
        FileSnapshot snapshot = fileSnapshot(ledger);
        return snapshot == null ? Page.empty() : readTail(ledger, turnCount, snapshot.length, snapshot);
    }

    /** Package-visible snapshot overload; callers may take the length while holding their write lock. */
    static Page readTail(File ledger, int turnCount, long snapshotLength) throws IOException {
        requireTurnCount(turnCount);
        if (ledger == null || snapshotLength < 0L) return Page.empty();
        FileSnapshot snapshot = fileSnapshot(ledger);
        return snapshot == null ? Page.empty() : readTail(ledger, turnCount, snapshotLength, snapshot);
    }

    private static Page readTail(File ledger, int turnCount, long snapshotLength, FileSnapshot snapshot)
            throws IOException {
        return readPage(ledger, snapshot, snapshotLength, NO_CURSOR, turnCount);
    }

    /**
     * Reads the {@code turnCount} turns immediately older than {@code beforeOffset}.
     * The offset should be the {@link Page#olderCursor} returned by the newer page.
     */
    public static Page readPrevious(File ledger, long beforeOffset, int turnCount) throws IOException {
        requireTurnCount(turnCount);
        if (ledger == null || beforeOffset <= 0L) return Page.empty();
        FileSnapshot snapshot = fileSnapshot(ledger);
        return snapshot == null ? Page.empty() : readPrevious(ledger, beforeOffset, turnCount, snapshot.length, snapshot);
    }

    /** Package-visible snapshot overload; callers may take the length while holding their write lock. */
    static Page readPrevious(File ledger, long beforeOffset, int turnCount, long snapshotLength) throws IOException {
        requireTurnCount(turnCount);
        if (ledger == null || beforeOffset <= 0L || snapshotLength < 0L) return Page.empty();
        FileSnapshot snapshot = fileSnapshot(ledger);
        return snapshot == null ? Page.empty()
                : readPrevious(ledger, beforeOffset, turnCount, snapshotLength, snapshot);
    }

    private static Page readPrevious(File ledger, long beforeOffset, int turnCount, long snapshotLength,
                                     FileSnapshot snapshot) throws IOException {
        return readPage(ledger, snapshot, snapshotLength, beforeOffset, turnCount);
    }

    private static Page readPage(File ledger, FileSnapshot snapshot, long snapshotLength, long beforeOffset,
                                 int turnCount) throws IOException {
        long endOffset = beforeOffset == NO_CURSOR ? snapshotLength : Math.min(beforeOffset, snapshotLength);
        String cacheKey = pageCacheKey(snapshot, snapshotLength, beforeOffset, turnCount);
        synchronized (PAGE_CACHE) {
            Page cached = PAGE_CACHE.get(cacheKey);
            if (cached != null) return cached;
        }
        try (FileChannel channel = FileChannel.open(ledger.toPath(), StandardOpenOption.READ)) {
            Page page = readWindow(channel, endOffset, turnCount, snapshot);
            synchronized (PAGE_CACHE) { PAGE_CACHE.put(cacheKey, page); }
            return page;
        }
    }

    private static void requireTurnCount(int turnCount) {
        if (turnCount <= 0) throw new IllegalArgumentException("turnCount must be positive");
    }

    private static Page readWindow(FileChannel channel, long endOffset, int turnCount, FileSnapshot snapshot)
            throws IOException {
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

        // When no older page exists, expose the whole remaining prefix. In particular,
        // the first selected anchor may have malformed or otherwise unanchored rows
        // before it; those rows still belong to the history and must not be dropped.
        if (!hasMore) pageStart = 0L;
        List<Line> readLines = readLines(channel, pageStart, endOffset);
        List<String> lines = new ArrayList<>(readLines.size());
        List<Long> lineOffsets = new ArrayList<>(readLines.size());
        for (Line value : readLines) {
            lines.add(value.text);
            lineOffsets.add(value.start);
        }
        long olderCursor = hasMore ? pageStart : NO_CURSOR;
        boolean pageHasLegacyRows = false;
        for (String value : lines) {
            if (isLegacyTimelineMessageRow(value)) {
                pageHasLegacyRows = true;
                break;
            }
        }
        PrefixCounts prefix = countRowsBefore(channel, pageStart, snapshot);
        // Modern IDs do not depend on an absolute timeline index. We still index the
        // prefix so legacy rows before this page cannot be missed, but expose the count
        // only when a legacy row in the prefix or this page can consume it.
        int messageIndexBeforeStart = prefix.legacyRows > 0 || pageHasLegacyRows
                ? prefix.messageRows : 0;
        return new Page(lines, lineOffsets, pageStart, messageIndexBeforeStart, olderCursor, hasMore);
    }

    /** Counts timeline and legacy rows, resuming from sparse cached checkpoints when possible. */
    private static PrefixCounts countRowsBefore(FileChannel channel, long endOffset, FileSnapshot snapshot)
            throws IOException {
        if (endOffset <= 0L) return PrefixCounts.EMPTY;
        PrefixIndex index;
        synchronized (PREFIX_INDEXES) {
            index = PREFIX_INDEXES.get(snapshot.path);
            if (index == null || !index.matches(snapshot)) {
                index = new PrefixIndex(snapshot);
                PREFIX_INDEXES.put(snapshot.path, index);
            }
        }
        synchronized (index) {
            index.adoptAppend(snapshot);
            if (endOffset > index.scannedThrough) {
                synchronized (ConversationLedgerPager.class) { prefixScanCount++; }
                PrefixCounts result = scanPrefix(channel, index.scannedThrough, endOffset,
                        index.scannedCounts, index);
                index.scannedThrough = endOffset;
                index.scannedCounts = result;
                return result;
            }
            if (endOffset == index.scannedThrough) return index.scannedCounts;
            PrefixCounts exact = index.checkpoints.get(endOffset);
            if (exact != null) return exact;
            Map.Entry<Long, PrefixCounts> checkpoint = index.checkpoints.floorEntry(endOffset);
            long start = checkpoint == null ? 0L : checkpoint.getKey();
            PrefixCounts counts = checkpoint == null ? PrefixCounts.EMPTY : checkpoint.getValue();
            if (start == endOffset) return counts;
            synchronized (ConversationLedgerPager.class) { prefixScanCount++; }
            return scanPrefix(channel, start, endOffset, counts, null);
        }
    }

    private static PrefixCounts scanPrefix(FileChannel channel, long start, long end, PrefixCounts initialCounts,
                                                PrefixIndex index) throws IOException {
        ByteBuffer block = ByteBuffer.allocate(FORWARD_BLOCK_SIZE);
        byte[] line = new byte[256];
        int lineLength = 0;
        int messageRows = initialCounts.messageRows;
        int legacyRows = initialCounts.legacyRows;
        long position = start;
        long nextCheckpoint = ((start / PREFIX_CHECKPOINT_BYTES) + 1L) * PREFIX_CHECKPOINT_BYTES;
        while (position < end) {
            block.clear();
            block.limit((int) Math.min(block.capacity(), end - position));
            int read = readPrefix(channel, block, position);
            if (read < 0) break;
            if (read == 0) continue;
            position += read;
            block.flip();
            int segmentStart = 0;
            for (int cursor = 0; cursor < read; cursor++) {
                if (block.get(cursor) != '\n') continue;
                int segmentLength = cursor - segmentStart;
                if (segmentLength > 0) {
                    line = append(line, lineLength, block, segmentStart, segmentLength);
                    lineLength += segmentLength;
                }
                if (lineLength > 0) {
                    int classification = classifyTimelineRow(line, lineLength);
                    if (classification != 0) messageRows++;
                    if (classification == 2) legacyRows++;
                    lineLength = 0;
                }
                long lineEnd = position - read + cursor + 1L;
                if (index != null && lineEnd >= nextCheckpoint) {
                    index.checkpoints.put(lineEnd, new PrefixCounts(messageRows, legacyRows));
                    nextCheckpoint = ((lineEnd / PREFIX_CHECKPOINT_BYTES) + 1L) * PREFIX_CHECKPOINT_BYTES;
                }
                segmentStart = cursor + 1;
            }
            int trailingLength = read - segmentStart;
            if (trailingLength > 0) {
                line = append(line, lineLength, block, segmentStart, trailingLength);
                lineLength += trailingLength;
            }
        }
        if (lineLength > 0 && end == position) {
            int classification = classifyTimelineRow(line, lineLength);
            if (classification != 0) messageRows++;
            if (classification == 2) legacyRows++;
        }
        return new PrefixCounts(messageRows, legacyRows);
    }

    private static byte[] append(byte[] target, int targetLength, ByteBuffer source, int offset, int length) {
        int required = targetLength + length;
        if (required > target.length) {
            int capacity = target.length;
            while (capacity < required) capacity = Math.max(capacity * 2, required);
            byte[] expanded = new byte[capacity];
            System.arraycopy(target, 0, expanded, 0, targetLength);
            target = expanded;
        }
        source.position(offset);
        source.get(target, targetLength, length);
        return target;
    }

    private static boolean isLegacyTimelineMessageRow(String line) {
        if (line == null) return false;
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        return classifyTimelineRow(bytes, bytes.length) == 2;
    }

    /** Returns 1 for a timeline row with a stable ID, 2 for a legacy row, and 0 otherwise. */
    private static int classifyTimelineRow(byte[] bytes, int length) {
        if (!mightBeTimelineMessageRow(bytes, length)) return 0;
        int classification = new JsonByteScanner(bytes, length).timelineClassification();
        return classification == JsonByteScanner.AMBIGUOUS
                ? classifyTimelineRowWithJSONObject(bytes, length) : classification;
    }

    /** Uses the production JSONObject rule for rows the byte parser cannot classify exactly. */
    private static int classifyTimelineRowWithJSONObject(byte[] bytes, int length) {
        int jsonLength = length > 0 && bytes[length - 1] == '\r' ? length - 1 : length;
        try {
            JSONObject row = new JSONObject(new String(bytes, 0, jsonLength, StandardCharsets.UTF_8));
            if (LocalRunStore.isResultOnlyRow(row)) return 0;
            String role = row.optString("role", "");
            if (!("user".equals(role) || "assistant".equals(role)) || !row.has("content")) return 0;
            return row.optString("messageId", "").isEmpty() ? 2 : 1;
        } catch (Exception invalidJson) {
            return 0;
        }
    }

    /** One-pass byte prefilter; escaped names or values are sent to the strict scanner too. */
    private static boolean mightBeTimelineMessageRow(byte[] bytes, int length) {
        boolean role = false;
        boolean content = false;
        boolean user = false;
        boolean assistant = false;
        for (int index = 0; index < length; index++) {
            if (bytes[index] == '\\' && index + 1 < length && bytes[index + 1] == 'u') return true;
            if (bytes[index] == '"') {
                role |= matchesAt(bytes, length, index, ROLE_TOKEN);
                content |= matchesAt(bytes, length, index, CONTENT_TOKEN);
                user |= matchesAt(bytes, length, index, USER_TOKEN);
                assistant |= matchesAt(bytes, length, index, ASSISTANT_TOKEN);
            }
            // JSONObject accepts some lenient spellings (for example single-quoted or
            // unquoted names). Keep those rows on the candidate path for real validation.
            switch (bytes[index]) {
                case 'r': role |= matchesWordAt(bytes, length, index, ROLE_WORD); break;
                case 'c': content |= matchesWordAt(bytes, length, index, CONTENT_WORD); break;
                case 'u': user |= matchesWordAt(bytes, length, index, USER_WORD); break;
                case 'a': assistant |= matchesWordAt(bytes, length, index, ASSISTANT_WORD); break;
                default: break;
            }
            if (role && content && (user || assistant)) return true;
        }
        return false;
    }

    private static boolean matchesWordAt(byte[] bytes, int length, int offset, byte[] target) {
        if (!matchesAt(bytes, length, offset, target)) return false;
        if (offset > 0 && isWordByte(bytes[offset - 1])) return false;
        int end = offset + target.length;
        return end >= length || !isWordByte(bytes[end]);
    }

    private static boolean isWordByte(byte value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9' || value == '_';
    }

    private static boolean matchesAt(byte[] bytes, int length, int offset, byte[] target) {
        if (offset + target.length > length) return false;
        for (int index = 0; index < target.length; index++)
            if (bytes[offset + index] != target[index]) return false;
        return true;
    }

    private static FileSnapshot fileSnapshot(File ledger) throws IOException {
        if (!ledger.isFile()) return null;
        BasicFileAttributes attributes = Files.readAttributes(ledger.toPath(), BasicFileAttributes.class);
        if (!attributes.isRegularFile()) return null;
        return new FileSnapshot(ledger.getAbsolutePath(), attributes.fileKey(), attributes.size(),
                attributes.lastModifiedTime());
    }

    private static String pageCacheKey(FileSnapshot snapshot, long snapshotLength, long beforeOffset,
                                       int turnCount) {
        return snapshot.path + '\u0000' + snapshot.fileKey + '\u0000' + snapshot.length + '\u0000'
                + snapshot.modified + '\u0000' + snapshotLength + '\u0000' + beforeOffset + '\u0000' + turnCount;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static int readPage(FileChannel channel, ByteBuffer target, long position) throws IOException {
        int count = channel.read(target, position);
        if (count > 0) synchronized (ConversationLedgerPager.class) { pageBytesRead += count; }
        return count;
    }

    private static int readPrefix(FileChannel channel, ByteBuffer target, long position) throws IOException {
        int count = channel.read(target, position);
        if (count > 0) synchronized (ConversationLedgerPager.class) { prefixBytesRead += count; }
        return count;
    }

    /** Package-visible instrumentation used by pager regression tests. */
    static synchronized void resetMetricsForTests() {
        pageBytesRead = 0L;
        prefixBytesRead = 0L;
        prefixScanCount = 0L;
    }

    static synchronized long pageBytesReadForTests() { return pageBytesRead; }
    static synchronized long prefixBytesReadForTests() { return prefixBytesRead; }
    static synchronized long prefixScanCountForTests() { return prefixScanCount; }

    /** Slow String-based oracle retained for test parity checks only. */
    static int strictCountMessageRowsBeforeForTests(File ledger, long endOffset) throws IOException {
        int count = 0;
        ByteArrayOutputStream currentLine = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(FORWARD_BLOCK_SIZE);
        try (FileChannel channel = FileChannel.open(ledger.toPath(), StandardOpenOption.READ)) {
            long position = 0L;
            long end = Math.min(endOffset, channel.size());
            while (position < end) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), end - position));
                int read = channel.read(buffer, position);
                if (read < 0) break;
                if (read == 0) continue;
                position += read;
                buffer.flip();
                while (buffer.hasRemaining()) {
                    byte value = buffer.get();
                    if (value == '\n') {
                        if (isTimelineMessageRow(decodeLine(currentLine.toByteArray()))) count++;
                        currentLine.reset();
                    } else currentLine.write(value);
                }
            }
            if (currentLine.size() > 0 && isTimelineMessageRow(decodeLine(currentLine.toByteArray()))) count++;
        }
        return count;
    }

    private static boolean isTimelineMessageRow(String line) {
        return new JsonFieldScanner(line).isTimelineMessageRow();
    }

    private static final class FileSnapshot {
        final String path;
        final Object fileKey;
        final long length;
        final java.nio.file.attribute.FileTime modified;

        FileSnapshot(String path, Object fileKey, long length,
                     java.nio.file.attribute.FileTime modified) {
            this.path = path;
            this.fileKey = fileKey;
            this.length = length;
            this.modified = modified;
        }
    }

    private static final class PrefixIndex {
        final String path;
        final Object fileKey;
        final TreeMap<Long, PrefixCounts> checkpoints = new TreeMap<>();
        java.nio.file.attribute.FileTime modified;
        long indexedFileLength;
        long scannedThrough;
        PrefixCounts scannedCounts = PrefixCounts.EMPTY;

        PrefixIndex(FileSnapshot snapshot) {
            path = snapshot.path;
            fileKey = snapshot.fileKey;
            modified = snapshot.modified;
            indexedFileLength = snapshot.length;
            checkpoints.put(0L, PrefixCounts.EMPTY);
        }

        boolean matches(FileSnapshot snapshot) {
            if (!path.equals(snapshot.path) || (fileKey != null && !fileKey.equals(snapshot.fileKey))) return false;
            if (snapshot.length < indexedFileLength) return false;
            return snapshot.length > indexedFileLength || modified.equals(snapshot.modified);
        }

        void adoptAppend(FileSnapshot snapshot) {
            if (snapshot.length > indexedFileLength) {
                indexedFileLength = snapshot.length;
                modified = snapshot.modified;
            }
        }
    }

    private static final class PrefixCounts {
        static final PrefixCounts EMPTY = new PrefixCounts(0, 0);
        final int messageRows;
        final int legacyRows;

        PrefixCounts(int messageRows, int legacyRows) {
            this.messageRows = messageRows;
            this.legacyRows = legacyRows;
        }
    }

    /** Strict structural scanner that validates candidate rows directly in their UTF-8 bytes. */
    private static final class JsonByteScanner {
        static final int AMBIGUOUS = -1;
        private final byte[] json;
        private final int length;
        private int index;
        private int stringStart;
        private int stringEnd;
        private boolean userRole;
        private boolean assistantRole;
        private boolean content;
        private boolean resultOnly;
        private boolean stableMessageId;
        private boolean resultOnlyType;
        private boolean requiresJSONObjectValidation;

        JsonByteScanner(byte[] json, int length) {
            this.json = json;
            this.length = length > 0 && json[length - 1] == '\r' ? length - 1 : length;
        }

        int timelineClassification() {
            if (!parseObject() || requiresJSONObjectValidation) return AMBIGUOUS;
            if (!(userRole || assistantRole) || !content || resultOnly || resultOnlyType) return 0;
            return stableMessageId ? 1 : 2;
        }

        private boolean parseObject() {
            skipWhitespace();
            if (!take('{')) return false;
            Set<String> keys = new HashSet<>();
            while (true) {
                skipWhitespace();
                if (take('}')) return finishObject();
                if (!readString()) return false;
                int keyStart = stringStart;
                int keyEnd = stringEnd;
                noteObjectKey(keys, keyStart, keyEnd);
                skipWhitespace();
                if (!take(':')) return false;
                skipWhitespace();
                if (stringEquals(keyStart, keyEnd, "role")) {
                    if (!readString()) return false;
                    userRole = stringEquals(stringStart, stringEnd, "user");
                    assistantRole = stringEquals(stringStart, stringEnd, "assistant");
                } else if (stringEquals(keyStart, keyEnd, "type")) {
                    if (index < length && json[index] == '"') {
                        if (!readString()) return false;
                        resultOnlyType = isResultOnlyType(stringStart, stringEnd);
                    } else {
                        resultOnlyType = false;
                        if (!skipValue()) return false;
                    }
                } else if (isResultOnlyField(keyStart, keyEnd)) {
                    resultOnly = true;
                    if (!skipValue()) return false;
                } else if (stringEquals(keyStart, keyEnd, "content")) {
                    content = true;
                    if (!skipValue()) return false;
                } else if (stringEquals(keyStart, keyEnd, "messageId")) {
                    if (index < length && json[index] == '"') {
                        if (!readString()) return false;
                        stableMessageId = stringEnd > stringStart;
                    } else {
                        // JSONObject.optString() converts present non-string values to text;
                        // only a missing or empty-string ID needs an index-derived legacy ID.
                        stableMessageId = true;
                        if (!skipValue()) return false;
                    }
                } else if (!skipValue()) return false;
                skipWhitespace();
                if (take(',')) {
                    int afterComma = index;
                    skipWhitespace();
                    if (index < length && json[index] == '}') requiresJSONObjectValidation = true;
                    index = afterComma;
                    continue;
                }
                if (take('}')) return finishObject();
                return false;
            }
        }

        private boolean finishObject() {
            skipWhitespace();
            return index == length;
        }

        private boolean skipValue() {
            if (index >= length) return false;
            byte first = json[index];
            if (first == '"') return readString();
            if (first == '{') return skipObjectValue();
            if (first == '[') return skipArrayValue();
            return skipPrimitiveValue();
        }

        private boolean skipObjectValue() {
            index++; // opening brace
            skipWhitespace();
            if (take('}')) return true;
            Set<String> keys = new HashSet<>();
            while (true) {
                if (!readString()) return false;
                int keyStart = stringStart;
                int keyEnd = stringEnd;
                noteObjectKey(keys, keyStart, keyEnd);
                skipWhitespace();
                if (!take(':')) return false;
                skipWhitespace();
                if (!skipValue()) return false;
                skipWhitespace();
                if (take(',')) {
                    int afterComma = index;
                    skipWhitespace();
                    if (index < length && json[index] == '}') requiresJSONObjectValidation = true;
                    index = afterComma;
                    continue;
                }
                return take('}');
            }
        }

        private boolean skipArrayValue() {
            index++; // opening bracket
            skipWhitespace();
            if (take(']')) return true;
            while (true) {
                if (!skipValue()) return false;
                skipWhitespace();
                if (take(',')) {
                    int afterComma = index;
                    skipWhitespace();
                    if (index < length && json[index] == ']') requiresJSONObjectValidation = true;
                    index = afterComma;
                    continue;
                }
                return take(']');
            }
        }

        private boolean skipPrimitiveValue() {
            int start = index;
            if (json[index] == 't') return takeLiteral("true");
            if (json[index] == 'f') return takeLiteral("false");
            if (json[index] == 'n') return takeLiteral("null");

            if (take('-') && index >= length) return false;
            if (take('0')) {
                if (index < length && isDigit(json[index])) return false;
            } else {
                if (index >= length || json[index] < '1' || json[index] > '9') return false;
                while (index < length && isDigit(json[index])) index++;
            }
            if (take('.')) {
                int fractionStart = index;
                while (index < length && isDigit(json[index])) index++;
                if (index == fractionStart) return false;
            }
            if (index < length && (json[index] == 'e' || json[index] == 'E')) {
                index++;
                if (index < length && (json[index] == '+' || json[index] == '-')) index++;
                int exponentStart = index;
                while (index < length && isDigit(json[index])) index++;
                if (index == exponentStart) return false;
            }
            if (index <= start) return false;
            try {
                double value = Double.parseDouble(new String(json, start, index - start,
                        StandardCharsets.US_ASCII));
                if (Double.isInfinite(value) || Double.isNaN(value)) requiresJSONObjectValidation = true;
            } catch (NumberFormatException uncertainNumber) {
                requiresJSONObjectValidation = true;
            }
            return true;
        }

        private void noteObjectKey(Set<String> keys, int start, int end) {
            StringBuilder key = new StringBuilder(end - start);
            for (int cursor = start; cursor < end; cursor++) {
                int value = json[cursor] & 0xff;
                if (value == '\\' || value >= 0x80) {
                    // Escaped and non-ASCII spellings need JSONObject's key normalization.
                    requiresJSONObjectValidation = true;
                    return;
                }
                key.append((char) value);
            }
            if (!keys.add(key.toString())) requiresJSONObjectValidation = true;
        }

        private boolean takeLiteral(String value) {
            if (index + value.length() > length) return false;
            for (int offset = 0; offset < value.length(); offset++)
                if (json[index + offset] != value.charAt(offset)) return false;
            index += value.length();
            return true;
        }

        private static boolean isDigit(byte value) {
            return value >= '0' && value <= '9';
        }

        private boolean readString() {
            if (!take('"')) return false;
            stringStart = index;
            while (index < length) {
                byte value = json[index++];
                if (value == '"') {
                    stringEnd = index - 1;
                    return true;
                }
                if (value != '\\') {
                    if ((value & 0xff) < 0x20) return false;
                    continue;
                }
                if (index >= length) return false;
                byte escaped = json[index++];
                if (escaped == 'u') {
                    if (index + 4 > length) return false;
                    for (int digit = 0; digit < 4; digit++)
                        if (hex(json[index + digit]) < 0) return false;
                    index += 4;
                } else if (escaped != '"' && escaped != '\\' && escaped != '/'
                        && escaped != 'b' && escaped != 'f' && escaped != 'n'
                        && escaped != 'r' && escaped != 't') return false;
            }
            return false;
        }

        private boolean stringEquals(int start, int end, String expected) {
            int expectedIndex = 0;
            int cursor = start;
            while (cursor < end) {
                int decoded;
                int value = json[cursor++] & 0xff;
                if (value != '\\') decoded = value;
                else {
                    if (cursor >= end) return false;
                    int escaped = json[cursor++] & 0xff;
                    switch (escaped) {
                        case '"': decoded = '"'; break;
                        case '\\': decoded = '\\'; break;
                        case '/': decoded = '/'; break;
                        case 'b': decoded = '\b'; break;
                        case 'f': decoded = '\f'; break;
                        case 'n': decoded = '\n'; break;
                        case 'r': decoded = '\r'; break;
                        case 't': decoded = '\t'; break;
                        case 'u':
                            if (cursor + 4 > end) return false;
                            decoded = (hex(json[cursor]) << 12) | (hex(json[cursor + 1]) << 8)
                                    | (hex(json[cursor + 2]) << 4) | hex(json[cursor + 3]);
                            cursor += 4;
                            break;
                        default: return false;
                    }
                }
                if (expectedIndex >= expected.length() || decoded != expected.charAt(expectedIndex++)) return false;
            }
            return expectedIndex == expected.length();
        }

        private boolean stringStartsWith(int start, int end, String expected) {
            int expectedIndex = 0;
            int cursor = start;
            while (cursor < end && expectedIndex < expected.length()) {
                int decoded;
                int value = json[cursor++] & 0xff;
                if (value != '\\') decoded = value;
                else {
                    if (cursor >= end) return false;
                    int escaped = json[cursor++] & 0xff;
                    switch (escaped) {
                        case '"': decoded = '"'; break;
                        case '\\': decoded = '\\'; break;
                        case '/': decoded = '/'; break;
                        case 'b': decoded = '\b'; break;
                        case 'f': decoded = '\f'; break;
                        case 'n': decoded = '\n'; break;
                        case 'r': decoded = '\r'; break;
                        case 't': decoded = '\t'; break;
                        case 'u':
                            if (cursor + 4 > end) return false;
                            decoded = (hex(json[cursor]) << 12) | (hex(json[cursor + 1]) << 8)
                                    | (hex(json[cursor + 2]) << 4) | hex(json[cursor + 3]);
                            cursor += 4;
                            break;
                        default: return false;
                    }
                }
                if (decoded != expected.charAt(expectedIndex++)) return false;
            }
            return expectedIndex == expected.length();
        }

        private boolean isResultOnlyType(int start, int end) {
            return stringStartsWith(start, end, "crew_coordination_")
                    || stringStartsWith(start, end, "workspace_coordination_")
                    || stringStartsWith(start, end, "workspace_input_")
                    || stringStartsWith(start, end, "crew_result_")
                    || stringStartsWith(start, end, "workspace_result_");
        }

        private boolean isResultOnlyField(int start, int end) {
            return stringEquals(start, end, "crewCoordinationSchemaVersion")
                    || stringEquals(start, end, "workspaceCoordinationSchemaVersion")
                    || stringEquals(start, end, "workspaceCoordinationReplySchemaVersion")
                    || stringEquals(start, end, "replyId")
                    || stringEquals(start, end, "workspaceInputIncorporationSchemaVersion")
                    || stringEquals(start, end, "receiptId") || stringEquals(start, end, "crewResultSchemaVersion")
                    || stringEquals(start, end, "workspaceResultSchemaVersion") || stringEquals(start, end, "evidenceId")
                    || stringEquals(start, end, "referenceId");
        }

        private static int hex(byte value) {
            int character = value & 0xff;
            if (character >= '0' && character <= '9') return character - '0';
            if (character >= 'a' && character <= 'f') return character - 'a' + 10;
            if (character >= 'A' && character <= 'F') return character - 'A' + 10;
            return -1;
        }

        private void skipWhitespace() {
            while (index < length && isWhitespace(json[index])) index++;
        }

        private boolean take(char expected) {
            if (index < length && json[index] == (byte) expected) {
                index++;
                return true;
            }
            return false;
        }

        private static boolean isWhitespace(byte value) {
            return value == ' ' || value == '\t' || value == '\r' || value == '\n';
        }
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
            int count = readPage(channel, buffer, position);
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
        /** Timeline message-row count before this page; large pages without legacy rows may use zero. */
        public final int messageIndexBeforeStart;
        /** Start offset of this page when an older page exists; otherwise {@link #NO_CURSOR}. */
        public final long olderCursor;
        public final boolean hasMore;

        private Page(List<String> lines, List<Long> lineOffsets, long startOffset, int messageIndexBeforeStart,
                     long olderCursor, boolean hasMore) {
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
            this.lineOffsets = Collections.unmodifiableList(new ArrayList<>(lineOffsets));
            this.startOffset = startOffset;
            this.messageIndexBeforeStart = messageIndexBeforeStart;
            this.olderCursor = olderCursor;
            this.hasMore = hasMore;
        }

        private static Page empty() {
            return new Page(Collections.emptyList(), Collections.emptyList(), 0L, 0, NO_CURSOR, false);
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
        private final long endOffset;
        private long position;
        private long blockStart = -1L;
        private int blockLength;

        ReverseLineReader(FileChannel channel, long endOffset) {
            this.channel = channel;
            this.endOffset = endOffset;
            this.position = endOffset;
        }

        Line previousLine() throws IOException {
            if (position <= 0L) return null;

            // The delimiter immediately before position belongs to the preceding line.
            // Skip only that delimiter: further adjacent delimiters encode empty lines
            // and must be returned just like any other ledger row.
            long end = position;
            if (byteAt(end - 1L) == '\n') end--;
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
                target.limit((int) Math.min(target.capacity(), endOffset - blockStart));
                long readPosition = blockStart;
                while (target.hasRemaining()) {
                    int count = readPage(channel, target, readPosition);
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
                int count = readPage(channel, target, readPosition);
                if (count <= 0) throw new IOException("Ledger changed while paging");
                readPosition += count;
            }
            return bytes;
        }
    }

    /** Minimal scanner for top-level role, content, and steering-dispatch fields. */
    private static final class JsonFieldScanner {
        private final String json;
        private int index;
        private boolean userRole;
        private boolean content;
        private boolean steeringDispatch;
        private boolean assistantRole;
        private String rowType = "";
        private boolean resultOnly;

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
                    assistantRole = "assistant".equals(value);
                } else if ("type".equals(key)) {
                    if (index < json.length() && json.charAt(index) == '"') {
                        String value = readString();
                        if (value == null) return false;
                        rowType = value;
                    } else {
                        rowType = "";
                        if (!skipValue()) return false;
                    }
                } else if (isResultOnlyField(key)) {
                    resultOnly = true;
                    if (!skipValue()) return false;
                } else if ("content".equals(key)) {
                    content = skipString();
                    if (!content) return false;
                } else if ("interruptRequestId".equals(key)) {
                    steeringDispatch = true;
                    if (!skipValue()) return false;
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
            // A dispatched steering row is the durable representation of the request
            // immediately before it. Treating it as a turn anchor can split the pair
            // across adjacent pages and project the older half as a phantom pending row.
            return index == json.length() && userRole && content && !steeringDispatch && !resultOnly
                    && !isResultOnlyType(rowType);
        }

        boolean isTimelineMessageRow() {
            skipWhitespace();
            if (!take('{')) return false;
            while (true) {
                skipWhitespace();
                if (take('}')) return finishMessageObject();
                String key = readString();
                if (key == null) return false;
                skipWhitespace();
                if (!take(':')) return false;
                skipWhitespace();
                if ("role".equals(key)) {
                    String value = readString();
                    if (value == null) return false;
                    userRole = "user".equals(value);
                    assistantRole = "assistant".equals(value);
                } else if ("type".equals(key)) {
                    if (index < json.length() && json.charAt(index) == '"') {
                        String value = readString();
                        if (value == null) return false;
                        rowType = value;
                    } else {
                        rowType = "";
                        if (!skipValue()) return false;
                    }
                } else if ("content".equals(key)) {
                    if (!skipValue()) return false;
                    content = true;
                } else if (isResultOnlyField(key)) {
                    resultOnly = true;
                    if (!skipValue()) return false;
                } else if (!skipValue()) {
                    return false;
                }
                skipWhitespace();
                if (take(',')) continue;
                if (take('}')) return finishMessageObject();
                return false;
            }
        }

        private boolean finishMessageObject() {
            skipWhitespace();
            return index == json.length() && (userRole || assistantRole) && content && !resultOnly
                    && !isResultOnlyType(rowType);
        }

        private static boolean isResultOnlyField(String key) {
            return "crewCoordinationSchemaVersion".equals(key)
                    || "workspaceCoordinationSchemaVersion".equals(key)
                    || "workspaceCoordinationReplySchemaVersion".equals(key)
                    || "replyId".equals(key) || "workspaceInputIncorporationSchemaVersion".equals(key)
                    || "receiptId".equals(key) || "crewResultSchemaVersion".equals(key)
                    || "workspaceResultSchemaVersion".equals(key) || "evidenceId".equals(key)
                    || "referenceId".equals(key);
        }

        private static boolean isResultOnlyType(String type) {
            return type.startsWith("crew_coordination_") || type.startsWith("workspace_coordination_")
                    || type.startsWith("workspace_input_") || type.startsWith("crew_result_")
                    || type.startsWith("workspace_result_");
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
