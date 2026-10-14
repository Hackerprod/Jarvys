package com.jarvys.agent

import android.content.Context

/** Runs a memory effect against the trusted store selected by app routing. */
internal class MemoryNativeActions {
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    companion object {
        fun forTool(@Suppress("UNUSED_PARAMETER") store: MemoryStore) = MemoryNativeActions()
    }

    private constructor()

    fun prepare(store: MemoryStore, token: CancellationToken): Prepared {
        token.throwIfCancelled()
        store.ensureInitialized()
        return Prepared(store, store.nativeStateFingerprint(true), token)
    }

    fun <T> run(store: MemoryStore, token: CancellationToken, validate: () -> Unit = {}, effect: () -> T): T =
        prepare(store, token).use { it.execute(validate, effect) }

    inner class Prepared internal constructor(
        private val store: MemoryStore,
        private val state: String,
        private val token: CancellationToken,
    ) : AutoCloseable {
        fun <T> execute(validate: () -> Unit = {}, effect: () -> T): T {
            token.throwIfCancelled()
            validate()
            val parent = NativeEffectDispatchScope.captureAdmission()
            val admission = Runnable { token.throwIfCancelled(); validate(); parent.run() }
            return store.withNativeState(state, true, admission) {
                NativeEffectDispatchScope.enter(admission).use { effect() }
            }
        }

        fun <T> executeUnlocked(validate: () -> Unit = {}, effect: () -> T): T = execute(validate, effect)

        override fun close() = Unit
    }
}
