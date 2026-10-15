package com.jarvys.agent.chatdb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Byte-line reader for conversation ledgers. RandomAccessFile uses ordinary file I/O rather than
 * interruptible NIO channels, so Thread.interrupt does not close the descriptor or terminate a read.
 * Only the current line is materialized; the reusable read buffer is a performance setting.
 */
internal class NonInterruptibleLedgerFile(
    file: File,
    startOffset: Long = 0L,
    private val bufferBytes: Int = DEFAULT_BUFFER_BYTES,
    startLineNumber: Long = 1L,
) : Closeable {
    data class Line(
        val lineNumber: Long,
        val startOffset: Long,
        val endOffset: Long,
        val bytesIncludingTerminator: Long,
        val terminated: Boolean,
        val bytes: ByteArray,
    )

    private val input = RandomAccessFile(file, "r")
    private val buffer = ByteArray(bufferBytes.also { require(it > 0) })
    private var buffered = 0
    private var bufferIndex = 0
    private var byteOffset: Long
    private var nextLineNumber = startLineNumber
    private var eof = false

    init {
        require(startOffset >= 0L && startOffset <= input.length()) { "invalid ledger offset $startOffset" }
        require(startLineNumber > 0L) { "invalid ledger line number $startLineNumber" }
        input.seek(startOffset)
        byteOffset = startOffset
    }

    fun readLine(): Line? {
        if (eof) return null
        val start = byteOffset
        val bytes = ByteArrayOutputStream()
        while (true) {
            if (bufferIndex >= buffered) {
                buffered = input.read(buffer)
                bufferIndex = 0
                if (buffered < 0) {
                    eof = true
                    if (bytes.size() == 0) return null
                    return finish(start, bytes.toByteArray(), terminated = false)
                }
            }
            val value = buffer[bufferIndex++].toInt() and 0xff
            byteOffset++
            if (value == '\n'.code) return finish(start, bytes.toByteArray(), terminated = true)
            bytes.write(value)
        }
    }

    private fun finish(start: Long, rawBytes: ByteArray, terminated: Boolean): Line {
        val content = if (terminated && rawBytes.lastOrNull() == '\r'.code.toByte()) rawBytes.copyOf(rawBytes.size - 1) else rawBytes
        val line = Line(nextLineNumber++, start, byteOffset, byteOffset - start, terminated, content)
        return line
    }

    override fun close() = input.close()

    companion object {
        private const val DEFAULT_BUFFER_BYTES = 64 * 1024
    }
}
