package com.jarvys.agent.linux

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

internal class TestTar {
    private val bytes = ByteArrayOutputStream()

    fun file(name: String, content: ByteArray, mode: Int = 0x1A4): TestTar = entry(name, '0', content, mode)
    fun fileWithMtime(name: String, content: ByteArray, mode: Int, mtime: Long): TestTar =
        entry(name, '0', content, mode, mtime = mtime)
    fun base256File(name: String, content: ByteArray, mode: Int, mtime: Long): TestTar =
        entry(name, '0', content, mode, mtime = mtime, base256SizeAndMtime = true)
    fun directory(name: String, mode: Int = 0x1ED): TestTar = entry(name, '5', byteArrayOf(), mode)
    fun symlink(name: String, target: String): TestTar = entry(name, '2', byteArrayOf(), 0x1FF, target)
    fun hardlink(name: String, target: String): TestTar = entry(name, '1', byteArrayOf(), 0x1A4, target)
    fun special(name: String, type: Char): TestTar = entry(name, type, byteArrayOf(), 0x1A4)

    fun paxPath(realPath: String, content: ByteArray): TestTar {
        entry("PaxHeader", 'x', pax("path", realPath), 0x1A4)
        entry("placeholder", '0', content, 0x1A4)
        return this
    }

    fun paxRawPath(realPath: ByteArray, content: ByteArray): TestTar {
        entry("PaxHeader", 'x', paxBytes("path", realPath), 0x1A4)
        entry("placeholder", '0', content, 0x1A4)
        return this
    }

    fun paxMtime(name: String, content: ByteArray, mtime: String): TestTar {
        entry("PaxHeader", 'x', pax("path", name) + pax("mtime", mtime), 0x1A4)
        entry("placeholder", '0', content, 0x1A4)
        return this
    }

    fun gnuLongName(realName: String, content: ByteArray): TestTar {
        entry("././@LongLink", 'L', realName.toByteArray() + byteArrayOf(0), 0x1A4)
        entry("placeholder", '0', content, 0x1A4)
        return this
    }

    fun gnuLongNameChain(count: Int, realName: String): TestTar {
        repeat(count) { entry("././@LongLink", 'L', realName.toByteArray() + byteArrayOf(0), 0x1A4) }
        entry("placeholder", '0', byteArrayOf(1), 0x1A4)
        return this
    }

    fun rawTruncatedFile(name: String, declaredSize: Long, data: ByteArray): ByteArray {
        val header = header(name, '0', declaredSize, 0x1A4, "")
        return header + data
    }

    fun bytes(terminate: Boolean = true): ByteArray {
        if (terminate) bytes.write(ByteArray(1024))
        return bytes.toByteArray()
    }

    fun gzip(): ByteArray {
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(bytes()) }
        return compressed.toByteArray()
    }

    private fun entry(name: String, type: Char, data: ByteArray, mode: Int, link: String = "", mtime: Long = 0,
                      base256SizeAndMtime: Boolean = false): TestTar {
        bytes.write(header(name, type, data.size.toLong(), mode, link, mtime, base256SizeAndMtime))
        bytes.write(data)
        val padding = (512 - data.size % 512) % 512
        if (padding > 0) bytes.write(ByteArray(padding))
        return this
    }

    private fun header(name: String, type: Char, size: Long, mode: Int, link: String,
                       mtime: Long = 0, base256SizeAndMtime: Boolean = false): ByteArray {
        val header = ByteArray(512)
        putString(header, 0, 100, name)
        putOctal(header, 100, 8, mode.toLong())
        putOctal(header, 108, 8, 0)
        putOctal(header, 116, 8, 0)
        if (base256SizeAndMtime) {
            putBase256(header, 124, 12, size)
            putBase256(header, 136, 12, mtime)
        } else {
            putOctal(header, 124, 12, size)
            putOctal(header, 136, 12, mtime)
        }
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        header[156] = type.code.toByte()
        putString(header, 157, 100, link)
        putString(header, 257, 6, "ustar\u0000")
        putString(header, 263, 2, "00")
        val checksum = header.sumOf { it.toInt() and 0xff }
        putOctal(header, 148, 8, checksum.toLong())
        return header
    }

    private fun putString(target: ByteArray, offset: Int, length: Int, value: String) {
        val data = value.toByteArray(StandardCharsets.UTF_8)
        data.copyInto(target, offset, 0, minOf(length, data.size))
    }

    private fun putOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
        val text = value.toString(8).padStart(length - 1, '0') + "\u0000"
        putString(target, offset, length, text)
    }

    private fun putBase256(target: ByteArray, offset: Int, length: Int, value: Long) {
        var remaining = value
        for (index in offset + length - 1 downTo offset) {
            target[index] = (remaining and 0xff).toByte()
            remaining = remaining ushr 8
        }
        target[offset] = (target[offset].toInt() or 0x80).toByte()
    }

    private fun pax(key: String, value: String): ByteArray = paxBytes(key, value.toByteArray(StandardCharsets.UTF_8))
    private fun paxBytes(key: String, value: ByteArray): ByteArray {
        val prefix = "$key=".toByteArray(StandardCharsets.UTF_8)
        var length = value.size + prefix.size + 2
        while (true) {
            val textLength = "$length ".toByteArray(StandardCharsets.US_ASCII).size
            val next = textLength + prefix.size + value.size + 1
            if (next == length) break
            length = next
        }
        return "$length ".toByteArray(StandardCharsets.US_ASCII) + prefix + value + byteArrayOf('\n'.code.toByte())
    }
}
