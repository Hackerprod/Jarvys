package com.jarvys.agent;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * Positional, non-interruptible reads for append-only conversation ledgers.
 *
 * <p>{@link RandomAccessFile} reads are used directly. In particular, this class must not
 * obtain or use the RAF's FileChannel: channel reads are interruptible and may close the
 * channel when a worker thread is stopped.</p>
 */
final class NonInterruptibleLedgerFile implements Closeable {
    private static final int BUFFER_SIZE = 16 * 1024;

    private final RandomAccessFile file;
    private final byte[] buffer = new byte[BUFFER_SIZE];
    private boolean closed;

    private NonInterruptibleLedgerFile(File file) throws IOException {
        this.file = new RandomAccessFile(file, "r");
    }

    static NonInterruptibleLedgerFile open(File file) throws IOException {
        return new NonInterruptibleLedgerFile(file);
    }

    synchronized long length() throws IOException {
        ensureOpen();
        return file.length();
    }

    /** Reads at an absolute offset, advancing target by the number of bytes read. */
    synchronized int read(ByteBuffer target, long position) throws IOException {
        if (target == null) throw new NullPointerException("target");
        if (position < 0L) throw new IllegalArgumentException("position must be non-negative");
        if (!target.hasRemaining()) return 0;
        ensureOpen();
        file.seek(position);
        int requested = Math.min(target.remaining(), buffer.length);
        int count = file.read(buffer, 0, requested);
        if (count > 0) target.put(buffer, 0, count);
        return count;
    }

    synchronized void seek(long position) throws IOException {
        if (position < 0L) throw new IOException("negative seek offset");
        ensureOpen();
        file.seek(position);
    }

    /** Returns a bounded stream starting at an absolute file offset. Closing it closes this file. */
    InputStream inputStream(long position, long byteCount) throws IOException {
        if (position < 0L) throw new IllegalArgumentException("position must be non-negative");
        if (byteCount < 0L) throw new IllegalArgumentException("byteCount must be non-negative");
        synchronized (this) { ensureOpen(); }
        return new PositionedInputStream(position, byteCount);
    }

    private synchronized int read(byte[] target, int offset, int length, long position) throws IOException {
        if (length == 0) return 0;
        if (position < 0L) throw new IllegalArgumentException("position must be non-negative");
        ensureOpen();
        file.seek(position);
        int requested = Math.min(length, buffer.length);
        int count = file.read(buffer, 0, requested);
        if (count > 0) System.arraycopy(buffer, 0, target, offset, count);
        return count;
    }

    private synchronized void ensureOpen() throws IOException {
        if (closed) throw new IOException("Ledger file is closed");
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        file.close();
    }

    private final class PositionedInputStream extends InputStream {
        private long position;
        private long remaining;
        private boolean streamClosed;

        PositionedInputStream(long position, long byteCount) {
            this.position = position;
            this.remaining = byteCount;
        }

        @Override public int read() throws IOException {
            byte[] oneByte = new byte[1];
            int count = read(oneByte, 0, 1);
            return count < 0 ? -1 : oneByte[0] & 0xff;
        }

        @Override public int read(byte[] target, int offset, int length) throws IOException {
            if (target == null) throw new NullPointerException("target");
            if (offset < 0 || length < 0 || length > target.length - offset)
                throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            if (streamClosed) throw new IOException("Ledger stream is closed");
            if (remaining <= 0L) return -1;
            int requested = (int) Math.min((long) length, remaining);
            int count = NonInterruptibleLedgerFile.this.read(target, offset, requested, position);
            if (count > 0) {
                position += count;
                remaining -= count;
            }
            return count;
        }

        @Override public void close() throws IOException {
            if (streamClosed) return;
            streamClosed = true;
            NonInterruptibleLedgerFile.this.close();
        }
    }
}
