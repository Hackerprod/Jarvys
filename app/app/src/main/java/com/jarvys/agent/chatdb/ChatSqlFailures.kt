package com.jarvys.agent.chatdb

import android.database.SQLException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException

/** Preserve the existing Conflict boundary when the bundled driver reports SQLite result codes. */
internal fun isChatSqlConstraint(failure: SQLException): Boolean {
    if (failure is SQLiteConstraintException) return true
    return bundledPrimaryCode(failure) == 19
}

/** Preserve the projector's blocked-corrupt destination result without treating IO/busy as corruption. */
internal fun isChatSqlCorruption(failure: SQLException): Boolean {
    if (failure is SQLiteDatabaseCorruptException) return true
    return when (bundledPrimaryCode(failure)) { 11, 26 -> true; else -> false }
}

private fun bundledPrimaryCode(failure: SQLException): Int? {
    // Bundled JNI exposes no numeric property on Android's SQLException alias. Accept only its
    // complete numeric envelope, never words in sqlite3_errmsg (which can contain application data).
    val match = bundledSqlErrorEnvelope.matchEntire(failure.message ?: return null) ?: return null
    val code = match.groupValues[1].toIntOrNull() ?: return null
    return code and 0xff
}

private val bundledSqlErrorEnvelope = Regex("Error code: ([1-9][0-9]*)(?:, message: [\\s\\S]*)?")
