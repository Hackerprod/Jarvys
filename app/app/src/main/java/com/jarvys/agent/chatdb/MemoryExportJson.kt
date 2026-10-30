package com.jarvys.agent.chatdb

import android.database.Cursor
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.MemoryExportSecretGuard
import java.io.*
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Specific export encoder. No unbounded field is obtained via Cursor.getString/getBlob. */
internal class MemoryExportJson(output: OutputStream, private val sql: ChatSqlDatabase, private val token: CancellationToken) {
    private val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8), 8192)
    fun raw(value: String) { token.throwIfCancelled(); writer.write(value) }
    fun string(value: String?) {
        if (value == null) { raw("null"); return }
        raw("\""); value.forEach(::escaped); raw("\"")
    }
    fun key(value: String) { string(value); raw(":") }
    private fun escaped(ch: Char) {
        when (ch) {
            '"' -> writer.write("\\\"")
            '\\' -> writer.write("\\\\")
            '\b' -> writer.write("\\b")
            '\u000c' -> writer.write("\\f")
            '\n' -> writer.write("\\n")
            '\r' -> writer.write("\\r")
            '\t' -> writer.write("\\t")
            else -> if (ch.code < 32) writer.write("\\u" + ch.code.toString(16).padStart(4, '0')) else writer.write(ch.code)
        }
    }
    fun type(table: String, row: Long, column: String): String = sql.query(
        "SELECT typeof($column) FROM $table WHERE rowid=?", arrayOf(row)).use { c ->
        check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_STRING) { "memory_export_row_changed" }; c.getString(0)
    }
    fun integer(table: String, row: Long, column: String): Long? {
        val storage=type(table,row,column)
        if(storage=="null") return null
        check(storage=="integer") { "memory_export_storage_type" }
        return sql.query(
        "SELECT $column FROM $table WHERE rowid=?", arrayOf(row)).use { c ->
        check(c.moveToFirst()) { "memory_export_row_changed" }
        if (c.isNull(0)) null else { check(c.getType(0) == Cursor.FIELD_TYPE_INTEGER) { "memory_export_storage_type" }; c.getLong(0) }
    }
    }
    fun real(table: String, row: Long, column: String): Double? {
        val storage=type(table,row,column)
        if(storage=="null") return null
        check(storage=="real" || storage=="integer") { "memory_export_storage_type" }
        return sql.query(
        "SELECT $column FROM $table WHERE rowid=?", arrayOf(row)).use { c ->
        check(c.moveToFirst()) { "memory_export_row_changed" }
        if (c.isNull(0)) null else {
            check(c.getType(0) == Cursor.FIELD_TYPE_FLOAT || c.getType(0) == Cursor.FIELD_TYPE_INTEGER) { "memory_export_storage_type" }
            c.getDouble(0).also { check(it.isFinite()) { "memory_export_confidence_invalid" } }
        }
    }
    }
    fun text(table: String, row: Long, column: String, nullable: Boolean = false,
        rawBytes: Boolean = false, nullAsEmpty: Boolean = false) {
        val storage = type(table, row, column)
        if (storage == "null") {
            check(nullable || nullAsEmpty) { "memory_export_required_null" }
            string(if (nullAsEmpty) "" else null); return
        }
        check(storage == "text") { "memory_export_storage_type" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(if (rawBytes) CodingErrorAction.REPLACE else CodingErrorAction.REPORT)
            .onUnmappableCharacter(if (rawBytes) CodingErrorAction.REPLACE else CodingErrorAction.REPORT)
        val guard = MemoryExportSecretGuard()
        raw("\"")
        InputStreamReader(CellBytes(table, row, column), decoder).use { reader ->
            val chars = CharArray(4096)
            while (true) {
                token.throwIfCancelled(); val read = reader.read(chars); if (read < 0) break
                for (i in 0 until read) { guard.accept(chars[i]); escaped(chars[i]) }
            }
        }
        guard.finish(); raw("\"")
    }
    fun base64(table: String, row: Long, column: String) {
        val storage = type(table, row, column)
        if (storage == "null") { raw("null"); return }
        check(storage == "text") { "memory_export_storage_type" }
        raw("\"")
        // Multiples of three ensure only the final encoded fragment contains padding.
        CellBytes(table, row, column).use { input ->
            val bytes = ByteArray(6144)
            while (true) {
                var used = 0
                while (used < bytes.size) { val n = input.read(bytes, used, bytes.size - used); if (n < 0) break; used += n }
                if (used == 0) break
                token.throwIfCancelled(); raw(Base64.getEncoder().encodeToString(if (used == bytes.size) bytes else bytes.copyOf(used)))
            }
        }
        raw("\"")
    }
    fun flush() { token.throwIfCancelled(); writer.flush() }
    private inner class CellBytes(private val table: String, private val row: Long, private val column: String) : InputStream() {
        private val length = sql.query("SELECT length(CAST($column AS BLOB)) FROM $table WHERE rowid=?", arrayOf(row)).use { c ->
            check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER) { "memory_export_storage_type" }; c.getLong(0).also { check(it >= 0) }
        }
        private var position = 0L
        private var buffer = ByteArray(0)
        private var index = 0
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
        override fun read(destination: ByteArray, offset: Int, count: Int): Int {
            token.throwIfCancelled(); if (count == 0) return 0
            if (index == buffer.size) {
                if (position == length) return -1
                buffer = sql.query("SELECT substr(CAST($column AS BLOB),?,8192) FROM $table WHERE rowid=?", arrayOf(position + 1, row)).use { c ->
                    check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_BLOB) { "memory_export_storage_type" }; c.getBlob(0)
                }
                check(buffer.isNotEmpty() && buffer.size <= 8192 && buffer.size.toLong() <= length - position) { "memory_export_row_changed" }
                position += buffer.size; index = 0
            }
            val copied = minOf(count, buffer.size - index)
            buffer.copyInto(destination, offset, index, index + copied); index += copied; return copied
        }
    }
}
