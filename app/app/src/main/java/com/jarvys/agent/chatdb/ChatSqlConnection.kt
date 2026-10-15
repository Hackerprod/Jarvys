package com.jarvys.agent.chatdb

import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.PooledConnection
import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.room.withTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/** Small synchronous SQL facade used by the M1 repositories with Room leases and the read pool. */
internal interface ChatSqlDatabase {
    fun execSQL(sql: String, bindArgs: Array<out Any?> = emptyArray())
    fun query(sql: String, bindArgs: Array<out Any?> = emptyArray()): Cursor
    fun queryOnWriter(sql: String, bindArgs: Array<out Any?> = emptyArray()): Cursor = query(sql, bindArgs)
    fun compileStatement(sql: String): SupportSQLiteStatement
}

/** Test-only observer for bounded-query assertions; the callback follows SQL across Room dispatchers. */
internal object ChatSqlInstrumentation {
    private val observer = AtomicReference<((String) -> Unit)?>(null)

    internal suspend fun <T> observeStatements(
        onStatement: (String) -> Unit,
        block: suspend () -> T,
    ): T {
        val callback: (String) -> Unit = onStatement
        check(observer.compareAndSet(null, callback)) { "A chat SQL observer is already active" }
        return try {
            block()
        } finally {
            check(observer.compareAndSet(callback, null)) { "Chat SQL observer changed while active" }
        }
    }

    internal fun statementPrepared(sql: String) {
        observer.get()?.invoke(sql)
    }
}

/** Fail fast when a synchronous StrictJournalRepository hook tries to re-enter chat storage. */
internal object ChatSqlBeforeWriteGuard {
    private val inCallback = ThreadLocal.withInitial { false }

    internal fun <T> invoke(callback: () -> T): T {
        val previous = inCallback.get()
        inCallback.set(true)
        return try {
            callback()
        } finally {
            if (previous) inCallback.set(true) else inCallback.remove()
        }
    }

    internal fun checkRepositoryAccess() {
        check(!inCallback.get()) {
            "beforeWrite callbacks cannot call chat repositories or blocking facades"
        }
    }
}

internal class SupportChatSqlDatabase(private val database: SupportSQLiteDatabase) : ChatSqlDatabase {
    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        ChatSqlInstrumentation.statementPrepared(sql)
        if (bindArgs.isEmpty()) database.execSQL(sql) else database.execSQL(sql, bindArgs)
    }

    override fun query(sql: String, bindArgs: Array<out Any?>): Cursor {
        ChatSqlInstrumentation.statementPrepared(sql)
        return database.query(sql, bindArgs)
    }

    override fun queryOnWriter(sql: String, bindArgs: Array<out Any?>): Cursor = query(sql, bindArgs)

    override fun compileStatement(sql: String): SupportSQLiteStatement = database.compileStatement(sql)
}

/** Direct adapter for Room lifecycle callbacks, which hand out the underlying SQLiteConnection. */
internal class DriverChatSqlDatabase(private val connection: SQLiteConnection) : ChatSqlDatabase {
    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        connection.prepare(sql).use { statement ->
            ChatSqlInstrumentation.statementPrepared(sql)
            bind(statement, bindArgs)
            statement.executeAndConsumeRows()
        }
    }

    override fun query(sql: String, bindArgs: Array<out Any?>): Cursor = connection.prepare(sql).use { statement ->
        ChatSqlInstrumentation.statementPrepared(sql)
        bind(statement, bindArgs)
        statement.toMatrixCursor()
    }

    override fun queryOnWriter(sql: String, bindArgs: Array<out Any?>): Cursor = query(sql, bindArgs)

    override fun compileStatement(sql: String): SupportSQLiteStatement = DriverChatSqlStatement(this, sql)

    internal fun <T> useStatement(sql: String, args: Array<out Any?>, block: (SQLiteStatement) -> T): T =
        connection.prepare(sql).use { statement ->
            ChatSqlInstrumentation.statementPrepared(sql)
            bind(statement, args)
            block(statement)
        }

    internal fun executeInsert(sql: String, args: Array<out Any?>): Long = useStatement(sql, args) { statement ->
        statement.executeAndConsumeRows()
        connection.prepare("SELECT last_insert_rowid()").use { result ->
            check(result.step()) { "last_insert_rowid() returned no row" }
            result.getLong(0)
        }
    }

    internal fun executeUpdateDelete(sql: String, args: Array<out Any?>): Int = useStatement(sql, args) { statement ->
        statement.executeAndConsumeRows()
        connection.prepare("SELECT changes()").use { result ->
            check(result.step()) { "changes() returned no row" }
            result.getLong(0).toInt()
        }
    }
}

/**
 * The legacy M1 repositories use synchronous Cursor-shaped helpers. Statements inside an active
 * Room lease stay on that connection; independent reads use the provider's separate raw read pool.
 */
private class PooledChatSqlDatabase(private val database: JarvysChatDatabase) : ChatSqlDatabase {
    private fun <T> withStatement(
        sql: String,
        args: Array<out Any?>,
        write: Boolean,
        block: (SQLiteStatement, PooledConnection?) -> T,
    ): T = runBlocking {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Chat SQL statements must not block the main thread"
        }
        ChatSqlBeforeWriteGuard.checkRepositoryAccess()
        suspend fun use(connection: PooledConnection): T = connection.usePrepared(sql) { statement ->
            ChatSqlInstrumentation.statementPrepared(sql)
            bind(statement, args)
            block(statement, connection)
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
        withStatement(sql, bindArgs, write = true) { statement, _ -> statement.executeAndConsumeRows() }
    }

    override fun query(sql: String, bindArgs: Array<out Any?>): Cursor {
        ChatSqlBeforeWriteGuard.checkRepositoryAccess()
        if (LeasedConnectionElement.current() == null) {
            ChatSqlInstrumentation.statementPrepared(sql)
            return withRawReadConnection { raw -> rawQuery(sql, bindArgs, raw) }
        }
        return withStatement(sql, bindArgs, write = false) { statement, _ -> statement.toMatrixCursor() }
    }

    override fun queryOnWriter(sql: String, bindArgs: Array<out Any?>): Cursor =
        withStatement(sql, bindArgs, write = true) { statement, _ -> statement.toMatrixCursor() }

    override fun compileStatement(sql: String): SupportSQLiteStatement = DriverChatSqlStatement(this, sql)

    internal fun <T> useStatement(sql: String, args: Array<out Any?>, block: (SQLiteStatement, PooledConnection?) -> T): T =
        withStatement(sql, args, write = true, block = block)

    internal fun executeInsert(sql: String, args: Array<out Any?>): Long = useStatement(sql, args) { statement, connection ->
        statement.executeAndConsumeRows()
        connection!!.usePrepared("SELECT last_insert_rowid()") { result ->
            check(result.step()) { "last_insert_rowid() returned no row" }
            result.getLong(0)
        }
    }

    internal fun executeUpdateDelete(sql: String, args: Array<out Any?>): Int = useStatement(sql, args) { statement, connection ->
        statement.executeAndConsumeRows()
        connection!!.usePrepared("SELECT changes()") { result ->
            check(result.step()) { "changes() returned no row" }
            result.getLong(0).toInt()
        }
    }

    private fun <T> withRawReadConnection(block: (SQLiteDatabase) -> T): T {
        val existing = RawReadConnectionElement.current()
        if (existing != null) return block(existing)
        val raw = JarvysChatDatabaseProvider.acquireReadDatabase(database)
        return try { block(raw) } finally { JarvysChatDatabaseProvider.releaseReadDatabase(database, raw) }
    }
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

    override fun execute() = statement { statement -> statement.executeAndConsumeRows() }

    override fun executeInsert(): Long = withBindings { args -> when (database) {
        is DriverChatSqlDatabase -> database.executeInsert(sql, args)
        is PooledChatSqlDatabase -> database.executeInsert(sql, args)
        else -> error("Unsupported SQL adapter")
    } }

    override fun executeUpdateDelete(): Int = withBindings { args -> when (database) {
        is DriverChatSqlDatabase -> database.executeUpdateDelete(sql, args)
        is PooledChatSqlDatabase -> database.executeUpdateDelete(sql, args)
        else -> error("Unsupported SQL adapter")
    } }

    override fun simpleQueryForLong(): Long = statement { statement ->
        check(statement.getColumnCount() > 0) { "Statement returned no columns" }
        check(statement.step()) { "Statement returned no row" }
        statement.getLong(0)
    }

    override fun simpleQueryForString(): String? = statement { statement ->
        check(statement.getColumnCount() > 0) { "Statement returned no columns" }
        if (!statement.step() || statement.isNull(0)) null else statement.getText(0)
    }

    private fun <T> statement(block: (SQLiteStatement) -> T): T {
        check(!closed.get()) { "Statement is closed" }
        val args = bindingValues()
        return when (database) {
            is DriverChatSqlDatabase -> database.useStatement(sql, args) { statement -> block(statement) }
            is PooledChatSqlDatabase -> database.useStatement(sql, args) { statement, _ -> block(statement) }
            else -> error("Unsupported SQL adapter")
        }
    }

    private fun <T> withBindings(block: (Array<out Any?>) -> T): T {
        check(!closed.get()) { "Statement is closed" }
        return block(bindingValues())
    }

    private fun bindingValues(): Array<out Any?> = if (bindings.isEmpty()) emptyArray() else Array(bindings.lastKey()) { index -> bindings[index + 1] }
}

private fun rawQuery(sql: String, args: Array<out Any?>, database: SQLiteDatabase): Cursor {
    val stringArgs = Array(args.size) { index ->
        when (val value = args[index]) {
            null -> null
            is ByteArray -> error("Read-pool queries cannot bind BLOB arguments through SQLiteDatabase.rawQuery")
            is Boolean -> if (value) "1" else "0"
            else -> value.toString()
        }
    }
    return database.rawQuery(sql, stringArgs).use { source ->
        val names = Array(source.columnCount) { source.getColumnName(it) }
        val result = MatrixCursor(names)
        while (source.moveToNext()) {
            val values = Array<Any?>(names.size) { index ->
                when (source.getType(index)) {
                    Cursor.FIELD_TYPE_INTEGER -> source.getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> source.getDouble(index)
                    Cursor.FIELD_TYPE_STRING -> source.getString(index)
                    Cursor.FIELD_TYPE_BLOB -> source.getBlob(index)
                    else -> null
                }
            }
            result.addRow(values)
        }
        result
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

/** Android's driver requires row-returning statements to be stepped through to exhaustion. */
private fun SQLiteStatement.executeAndConsumeRows() {
    if (getColumnCount() > 0) {
        while (step()) Unit
    } else {
        // DML and DDL statements have no result set; one step executes them. A loop here can
        // re-enter Android's statement execution path after the write has already completed.
        step()
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

internal class LeasedConnectionElement(
    val connection: PooledConnection,
    val isWriter: Boolean,
    private val ownerDispatcher: ContinuationInterceptor? = null,
) : ThreadContextElement<ActiveChatDbConnection?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LeasedConnectionElement> {
        private val active = ThreadLocal<ActiveChatDbConnection?>()
        fun current(): ActiveChatDbConnection? = active.get()
    }

    private val lease = ActiveChatDbConnection(connection, isWriter)
    private val ownerThread = Thread.currentThread()
    private var contextInstallCount = 0

    override fun updateThreadContext(context: CoroutineContext): ActiveChatDbConnection? {
        check(!isWriter || Thread.currentThread() === ownerThread) {
            "A chat writer lease cannot resume on a different thread; suspending or changing dispatcher inside withChatWriteTransaction is unsupported"
        }
        check(!isWriter || context[ContinuationInterceptor] === ownerDispatcher) {
            "A chat writer lease cannot resume with a different dispatcher; suspending or changing dispatcher inside withChatWriteTransaction is unsupported"
        }
        if (isWriter) {
            contextInstallCount++
            check(contextInstallCount == 1) {
                "A chat writer lease cannot suspend and resume; its SQLiteSession must stay on the owning thread until commit"
            }
        }
        return active.get().also { active.set(lease) }
    }
    override fun restoreThreadContext(context: CoroutineContext, oldState: ActiveChatDbConnection?) { active.set(oldState) }
}

/** Read-only raw SQLite handle held for one projection; no Android transaction is started on API 28. */
internal class RawReadConnectionElement(
    private val database: SQLiteDatabase,
) : ThreadContextElement<SQLiteDatabase?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RawReadConnectionElement> {
        private val active = ThreadLocal<SQLiteDatabase?>()
        fun current(): SQLiteDatabase? = active.get()
    }

    override fun updateThreadContext(context: CoroutineContext): SQLiteDatabase? = active.get().also { active.set(database) }
    override fun restoreThreadContext(context: CoroutineContext, oldState: SQLiteDatabase?) { active.set(oldState) }
}

/** Synchronous M1 SQL is available only while a Room read or write lease is active. */
internal val JarvysChatDatabase.chatSql: ChatSqlDatabase
    get() = PooledChatSqlDatabase(this)

internal suspend fun <T> JarvysChatDatabase.withChatWriteTransaction(block: suspend () -> T): T {
    ChatSqlBeforeWriteGuard.checkRepositoryAccess()
    val active = LeasedConnectionElement.current()
    if (active != null) {
        check(active.isWriter) { "Cannot start a chat database write inside a reader lease" }
        return block()
    }
    return JarvysChatDatabaseProvider.onWriterExecutor(this) {
        useWriterConnection { writer ->
            writer.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val dispatcher = currentCoroutineContext()[ContinuationInterceptor]
                withContext(LeasedConnectionElement(writer, isWriter = true, ownerDispatcher = dispatcher)) { block() }
            }
        }
    }
}

internal suspend fun <T> JarvysChatDatabase.withChatReadTransaction(block: suspend () -> T): T {
    ChatSqlBeforeWriteGuard.checkRepositoryAccess()
    val active = LeasedConnectionElement.current()
    if (active != null) {
        // Reuse the current lease. A read inside a writer must see that transaction's own writes.
        return block()
    }
    if (RawReadConnectionElement.current() != null) return block()
    // Do not call beginTransaction()/withTransaction on android.database.sqlite.SQLiteDatabase:
    // on the API levels used by Robolectric it maps to EXCLUSIVE. The projection's SQL uses a
    // high-water sequence where applicable, and the per-handle data_version fence below retries
    // if any other connection commits between statements. That gives the complete projection one
    // stable committed view without taking a lock that would block the Room writer.
    val raw = JarvysChatDatabaseProvider.acquireReadDatabase(this)
    try {
        while (true) {
            val before = JarvysChatDatabaseProvider.readDataVersion(raw)
            try {
                val result = withContext(RawReadConnectionElement(raw)) { block() }
                if (before == JarvysChatDatabaseProvider.readDataVersion(raw)) return result
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException ||
                    before == JarvysChatDatabaseProvider.readDataVersion(raw)) throw failure
            }
        }
    } finally {
        JarvysChatDatabaseProvider.releaseReadDatabase(this, raw)
    }
}
