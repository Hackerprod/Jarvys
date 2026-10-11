package com.jarvys.agent.apkfactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Process-local live leases. Only a full, off-main receipt verification may issue a token. */
internal object FactoryArtifactAuthority {
    private const val MAX_ACTIVE = 256
    private val active = ConcurrentHashMap.newKeySet<Token>()

    internal class Token internal constructor(internal val proof: FactoryDocumentCoordinator.Proof, internal val retained: Boolean) : AutoCloseable {
        private val valid = AtomicBoolean(true)
        private val revoked = AtomicReference<(() -> Unit)?>(null)
        /** One session-owned callback; it must enqueue cancellation rather than block a signing mutation. */
        fun onRevoked(callback: () -> Unit): AutoCloseable {
            check(revoked.compareAndSet(null, callback)) { "Authority revocation listener already installed" }
            if (!valid.get()) revoked.getAndSet(null)?.let { runCatching(it) }
            return AutoCloseable { revoked.compareAndSet(callback, null) }
        }
        /** Safe for immediate UI effects and stream checkpoints: no disk, key access or signing lock. */
        fun requireCurrent() { check(valid.get()) { "Signed artifact authority changed; verify a new operation" } }
        override fun close() {
            if (valid.compareAndSet(true, false)) {
                active.remove(this)
                revoked.getAndSet(null)?.let { runCatching(it) }
            }
        }
    }
    /** Caller holds FactorySigningIdentity.LOCK through exact verification and token issuance. */
    fun capture(proof: FactoryDocumentCoordinator.Proof, retained: Boolean): Token {
        check(Thread.holdsLock(FactorySigningIdentity.LOCK)) { "Authority capture requires serialized receipt verification" }
        check(active.size < MAX_ACTIVE) { "Too many active signed artifact authorities" }
        return Token(proof, retained).also { active.add(it) }
    }
    fun invalidate(appId: String) { active.forEach { if (it.proof.appId == appId) it.close() } }
    fun beforeIdentityWrite(appId: String, preserveRetained: Boolean) {
        active.forEach { if (it.proof.appId == appId && (!preserveRetained || !it.retained)) it.close() }
    }
    fun observeIdentity(appId: String, state: FactorySigningIdentity.State) {
        active.forEach { token ->
            if (token.proof.appId == appId && (!state.existing || !state.continuityKnown || token.proof.certificate != state.fingerprint ||
                token.proof.version > state.lastVersion || (!token.retained && token.proof.record != state.recordSha256))) token.close()
        }
    }
    fun retainHistory(appId: String, records: Set<String>) {
        active.forEach { if (it.proof.appId == appId && (!it.retained || it.proof.record !in records)) it.close() }
    }
    fun historyMissing(appId: String) { active.forEach { if (it.proof.appId == appId && it.retained) it.close() } }
}
