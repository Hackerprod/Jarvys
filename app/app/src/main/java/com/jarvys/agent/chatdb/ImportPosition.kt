package com.jarvys.agent.chatdb

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Exact source position applied to every normalized row written for one legacy ledger row. */
internal data class ImportedLedgerPosition(
    val conversationId: String,
    val seq: Long,
    val createdAtSeconds: Double,
    val sortSeq: Long = seq,
    val sortSub: Int = 0,
    val turnAnchorSeq: Long? = null,
    val updatedAtMillis: Long? = null,
) {
    init {
        require(conversationId.isNotBlank())
        require(seq > 0L)
        require(createdAtSeconds.isFinite())
        require(sortSeq >= 0L)
    }

    val conversationUpdatedAtMillis: Long
        get() = updatedAtMillis ?: (createdAtSeconds * 1_000.0).toLong()

    val updatedAtSeconds: Double
        get() = updatedAtMillis?.div(1_000.0) ?: createdAtSeconds
}

/**
 * Coroutine-scoped import position shared by the M1 repositories. Each imported operation still
 * runs through its normal repository transaction and validation; the active row supplies its
 * ledger sequence, position, and source clock to the common insertion primitives.
 */
internal class ImportPositionElement(
    val position: ImportedLedgerPosition,
) : ThreadContextElement<ImportedLedgerPosition?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ImportPositionElement> {
        private val activePosition = ThreadLocal<ImportedLedgerPosition?>()

        fun current(): ImportedLedgerPosition? = activePosition.get()
    }

    override fun updateThreadContext(context: CoroutineContext): ImportedLedgerPosition? =
        activePosition.get().also { activePosition.set(position) }

    override fun restoreThreadContext(context: CoroutineContext, oldState: ImportedLedgerPosition?) {
        activePosition.set(oldState)
    }
}

/** Runs exactly one repository append with source seq/time overrides in the coroutine context. */
internal suspend fun <T> JarvysChatDatabase.withImportPosition(
    position: ImportedLedgerPosition,
    block: suspend () -> T,
): T {
    return withContext(ImportPositionElement(position)) { block() }
}

internal fun importedPositionFor(conversationId: String): ImportedLedgerPosition? =
    ImportPositionElement.current()?.also {
        check(it.conversationId == conversationId) {
            "Import row for ${it.conversationId} cannot write into $conversationId"
        }
    }

internal fun importedNowSeconds(): Double? = ImportPositionElement.current()?.createdAtSeconds

internal fun importedNowMillis(): Long? = ImportPositionElement.current()?.conversationUpdatedAtMillis

internal fun importedUpdatedAtSeconds(): Double? = ImportPositionElement.current()?.updatedAtSeconds
