package com.jarvys.agent.chatdb

import android.database.Cursor
import android.database.MatrixCursor
import androidx.room.PooledConnection
import androidx.room.Transactor
import androidx.room.withTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Small synchronous SQL facade used by the M1 repositories inside a Room connection lease. */
internal interface ChatSqlDatabase {
    fun execSQL(sql: String, bindArgs: Array<out Any?> = emptyArray())
    fun query(sql: String, bindArgs: Array<out Any?> = emptyArray()): Cursor
    fun queryOnWriter(sql: String, bindArgs: Array<out Any?> = emptyArray()): Cursor = query(sql, bindArgs)
    fun compileStatement(sql: String): SupportSQLiteStatement
}

internal class SupportChatSqlDatabase(private val database: SupportSQLiteDatabase) : ChatSqlDatabase {
    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        if (bindArgs.isEmpty()) database.execSQL(sql) else database.execSQL(sql, bindArgs)
    }

    override fun query(sql: String, bindArgs: Array<out Any?>): Cursor = database.query(sql, bindArgs)
    override fun queryOnWriter(sql: String, bindArgs: Array<out Any?>): Cursor = database.query(sql, bindArgs)

    override fun compileStatement(sql: String): SupportSQLiteStatement = database.compileStatement(sql)
}

/** Direct adapter for Room lifecycle callbacks, which hand out the underlying SQLiteConnection. */
internal class DriverChatSqlDatabase(private val connection: SQLiteConnection) : ChatSqlDatabase {
    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        connection.prepare(sql).use { statement ->
            bind(statement, bindArgs)
            while (statement.step()) Unit
        }
    }

    override fun query(sql: String, bindArgs: Array<out Any?>): Cursor = connection.prepare(sql).use { statement ->
        bind(statement, bindArgs)
        statement.toMatrixCursor()
    }

    override fun queryOnWriter(sql: String, bindArgs: Array<out Any?>): Cursor = query(sql, bindArgs)

    override fun compileStatement(sql: String): SupportSQLiteStatement = DriverChatSqlStatement(this, sql)

    internal fun <T> useStatement(sql: String, args: Array<out Any?>, block: (SQLiteStatement) -> T): T =
        connection.prepare(sql).use { statement ->
            bind(statement, args)
            block(statement)
        }
}

/**
 * The legacy M1 repositories use synchronous Cursor-shaped helpers. Room 2.7 exposes its pooled
 * connection through suspend `useReaderConnection` / `useWriterConnection`; this adapter bridges
 * each short statement through that leased connection and closes it before returning a cursor.
 */
private class PooledChatSqlDatabase(private val database: JarvysChatDatabase) : ChatSqlDatabase {
    private fun <T> withStatement(
        sql: String,
        args: Array<out Any?>,
        write: Boolean,
        block: (SQLiteStatement) -> T,
    ): T = runBlocking {
        suspend fun use(connection: PooledConnection): T = connection.usePrepared(sql) { statement ->
            bind(statement, args)
            block(statement)
        }
        val leased = LeasedConnectionElement.current()
        when {
            leased != null -> {
                check(!write || leased.isWriter) { "Cannot execute a write statement inside a reader lease" }
                use(leased.connection)
            }
            write -> database.useWriterConnection { use(it) }
            else -> database.useReaderConnection { use(it) }
        }
    }

    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        withStatement(sql, bindArgs, write = true) { statement -> while (statement.step()) Unit }
    }

    override fun query(sql: String, bindArgs: Array<out Any?>): Cursor =
        withStatement(sql, bindArgs, write = false) { it.toMatrixCursor() }

    override fun queryOnWriter(sql: String, bindArgs: Array<out Any?>): Cursor =
        withStatement(sql, bindArgs, write = true) { it.toMatrixCursor() }

    override fun compileStatement(sql: String): SupportSQLiteStatement = DriverChatSqlStatement(this, sql)

    internal fun <T> useStatement(sql: String, args: Array<out Any?>, block: (SQLiteStatement) -> T): T =
        withStatement(sql, args, write = true, block = block)
}

private class DriverChatSqlStatement(
    private val database: ChatSqlDatabase,
    private val sql: String,
) : SupportSQLiteStatement {
    private val bindings = sortedMapOf<Int, Any?>()
    private val closed = AtomicBoolean(false)

    override fun bindNull(index: Int) { bindings[index] = null }
    override fun bindLong(index: Int, value: Long) { bindings[index] = value }
    override fun bindDouble(index: Int, value: Double) { bindings[index] = value }
    override fun bindString(index: Int, value: String) { bindings[index] = value }
    override fun bindBlob(index: Int, value: ByteArray) { bindings[index] = value }
    override fun clearBindings() { bindings.clear() }
    override fun close() { closed.set(true) }

    override fun execute() = statement { statement -> while (statement.step()) Unit }

    override fun executeInsert(): Long = statement { statement ->
        while (statement.step()) Unit
        Unit
    }.let { database.queryOnWriter("SELECT last_insert_rowid()").use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L } }

    override fun executeUpdateDelete(): Int = statement { statement ->
        while (statement.step()) Unit
        Unit
    }.let { database.queryOnWriter("SELECT changes()").use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 } }

    override fun simpleQueryForLong(): Long = statement { statement ->
        check(statement.step()) { "Statement returned no row" }
        statement.getLong(0)
    }

    override fun simpleQueryForString(): String? = statement { statement ->
        if (!statement.step() || statement.isNull(0)) null else statement.getText(0)
    }

    private fun <T> statement(block: (SQLiteStatement) -> T): T {
        check(!closed.get()) { "Statement is closed" }
        val args = if (bindings.isEmpty()) emptyArray() else Array(bindings.lastKey()) { index -> bindings[index + 1] }
        return when (database) {
            is DriverChatSqlDatabase -> database.useStatement(sql, args, block)
            is PooledChatSqlDatabase -> database.useStatement(sql, args, block)
            else -> error("Unsupported SQL adapter")
        }
    }
}

private fun bind(statement: SQLiteStatement, args: Array<out Any?>) {
    args.forEachIndexed { offset, value ->
        val index = offset + 1
        when (value) {
            null -> statement.bindNull(index)
            is ByteArray -> statement.bindBlob(index, value)
            is String -> statement.bindText(index, value)
            is Boolean -> statement.bindLong(index, if (value) 1L else 0L)
            is Byte, is Short, is Int, is Long -> statement.bindLong(index, (value as Number).toLong())
            is Float, is Double -> statement.bindDouble(index, (value as Number).toDouble())
            else -> error("Unsupported SQLite bind value ${value::class.java.name}")
        }
    }
}

private fun SQLiteStatement.toMatrixCursor(): Cursor {
    val columnCount = getColumnCount()
    val names = Array(columnCount) { index -> getColumnName(index) }
    val cursor = MatrixCursor(names)
    while (step()) {
        val values = Array<Any?>(names.size) { index ->
            when (getColumnType(index)) {
                1 -> getLong(index)
                2 -> getDouble(index)
                3 -> getText(index)
                4 -> getBlob(index)
                else -> null
            }
        }
        cursor.addRow(values)
    }
    return cursor
}

private data class ActiveChatDbConnection(
    val connection: PooledConnection,
    val isWriter: Boolean,
)

private class LeasedConnectionElement(
    val connection: PooledConnection,
    val isWriter: Boolean,
) : ThreadContextElement<ActiveChatDbConnection?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LeasedConnectionElement> {
        private val active = ThreadLocal<ActiveChatDbConnection?>()
        fun current(): ActiveChatDbConnection? = active.get()
    }

    private val lease = ActiveChatDbConnection(connection, isWriter)

    override fun updateThreadContext(context: CoroutineContext): ActiveChatDbConnection? = active.get().also { active.set(lease) }
    override fun restoreThreadContext(context: CoroutineContext, oldState: ActiveChatDbConnection?) { active.set(oldState) }
}

/** Synchronous M1 SQL is available only while a Room read or write lease is active. */
internal val JarvysChatDatabase.chatSql: ChatSqlDatabase
    get() = PooledChatSqlDatabase(this)

internal suspend fun <T> JarvysChatDatabase.withChatWriteTransaction(block: suspend () -> T): T {
    val active = LeasedConnectionElement.current()
    if (active != null) {
        check(active.isWriter) { "Cannot start a chat database write inside a reader lease" }
        return block()
    }
    return useWriterConnection { writer ->
        writer.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            withContext(LeasedConnectionElement(writer, isWriter = true)) { block() }
        }
    }
}

internal suspend fun <T> JarvysChatDatabase.withChatReadTransaction(block: suspend () -> T): T {
    if (LeasedConnectionElement.current() != null) return block()
    return useReaderConnection { reader ->
        reader.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            withContext(LeasedConnectionElement(reader, isWriter = false)) { block() }
        }
    }
}
