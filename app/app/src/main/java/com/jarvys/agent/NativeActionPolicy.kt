package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.connectors.ActionPolicyDefaults
import com.jarvys.agent.connectors.ActionPolicySnapshot
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.ConnectorAutonomyStore
import com.jarvys.agent.connectors.InMemoryConnectorAutonomyStore
import com.jarvys.agent.connectors.SharedPreferencesConnectorAutonomyStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Native semantic effects share the same durable choices/admission lock as connectors.
 * A request binds one exact scope/payload. It is never a credential, data grant or capability.
 */
class NativeActionPolicy(private val store: ConnectorAutonomyStore) {
    constructor(context: Context) : this(SharedPreferencesConnectorAutonomyStore(context.applicationContext))

    enum class Operation(val id: String) {
        MEMORY_VIEW("memory.view"), MEMORY_MODIFY("memory.modify"), MEMORY_DELETE("memory.delete"),
        MEMORY_RESTORE("memory.restore"), MEMORY_CLEAR("memory.clear"), MEMORY_EXPORT("memory.export"),
        MEMORY_SHARE_PERSONAL("memory.share_personal"), MEMORY_REFLECTION("memory.reflection.run"),
        BOT_CREATE("bots.create"), BOT_MASCOT("bots.mascot.compile"),
        TASK_CREATE("tasks.create"), TASK_UPDATE("tasks.update"), TASK_CANCEL("tasks.cancel"), TASK_VIEW("tasks.view"),
        IDENTITY_CREATE("factory.identity.create"), IDENTITY_EXPORT("factory.identity.backup.export"),
        IDENTITY_IMPORT("factory.identity.backup.import"), IDENTITY_RECONCILE("factory.identity.reconcile"),
    }

    init { ActionPolicyDefaults.register(CONNECTOR_ID, Operation.entries.map { it.id }) }

    fun snapshot(operation: Operation): ActionPolicySnapshot = store.snapshot(CONNECTOR_ID, operation.id)
    fun set(operation: Operation, mode: AutonomyPolicy) = store.setPolicyChecked(CONNECTOR_ID, operation.id, mode)
    fun prepare(operation: Operation, binding: String): Request {
        require(binding.matches(Regex("[a-f0-9]{64}"))) { "An exact native request binding is required" }
        val saved = snapshot(operation)
        check(saved.policy != AutonomyPolicy.DENY) { "Native action ${operation.id} is denied by your saved policy" }
        return Request(operation, binding, saved)
    }

    inner class Request internal constructor(
        val operation: Operation,
        val binding: String,
        private val initial: ActionPolicySnapshot,
    ) : AutoCloseable {
        private val lock = REQUEST_LOCK
        private var approved = initial.policy == AutonomyPolicy.ALLOW
        private var closed = false
        val requiresApproval: Boolean get() = synchronized(lock) {
            !closed && initial.policy == AutonomyPolicy.ASK && !approved
        }
        val revision: Long get() = initial.revision

        private fun current(): ActionPolicySnapshot {
            check(!closed) { "Native request is closed" }
            // Matching includes the store's opaque raw-state/identity token, not just public fields.
            val value = store.snapshotMatching(initial)
            check(value.policy != AutonomyPolicy.DENY) { "Native action is denied" }
            return value
        }
        fun requireCurrent() { synchronized(lock) { current() } }
        /** A system selection may refine exact arguments, but may not adopt a changed policy or old ASK. */
        fun rebind(exactBinding: String): Request = synchronized(lock) {
            require(exactBinding.matches(Regex("[a-f0-9]{64}")))
            current()
            Request(operation, exactBinding, initial).also { closed = true; approved = false }
        }
        fun requireBinding(expected: String) {
            check(expected == binding) { "Native request scope or content changed" }
            requireCurrent()
        }
        /** UI waits happen before this method; the short callback only verifies genuine UI/lifecycle state. */
        fun approveOnce(humanCheck: () -> Unit) = synchronized(lock) {
            current()
            if (!approved) { humanCheck(); current(); approved = true }
        }
        fun authorize(summary: ApprovalSummary, token: CancellationToken,
                      ask: (ApprovalSummary, CancellationToken) -> ApprovalDecision) {
            token.throwIfCancelled(); requireCurrent()
            if (requiresApproval) {
                val decision = ask(summary.copy(allowAlwaysAvailable = false), token)
                check(decision == ApprovalDecision.APPROVED) { "Native action was not approved" }
                approveOnce { token.throwIfCancelled() }
            }
            token.throwIfCancelled(); requireCurrent()
        }
        /** Consume a fresh snapshot for every fresh effect, never hold the policy lock across the effect. */
        @JvmOverloads fun admit(validate: () -> Unit = {}) = synchronized(lock) {
            val value = current()
            store.admit(value, approvedAsk = approved && initial.policy == AutonomyPolicy.ASK) {
                check(!closed); validate()
            }
        }
        /** STOP and the durable policy admission have a single ordering; effects run after both locks release. */
        @JvmOverloads fun admit(token: CancellationToken, validate: () -> Unit = {}) {
            if (!token.runIfActive { admit { token.throwIfCancelled(); validate() } }) {
                token.throwIfCancelled()
                throw java.util.concurrent.CancellationException("Native effect stopped before admission")
            }
        }
        internal fun linkMascot(child: NativeActionPolicy.Request): LinkedRequest = synchronized(lock) {
            check(operation == Operation.BOT_CREATE) { "Only a bot creation can link its mascot subeffect" }
            requireCurrent()
            check(child.operation == Operation.BOT_MASCOT)
            LinkedRequest(this, child, child.binding)
        }
        override fun close() { synchronized(lock) { closed = true; approved = false } }
    }

    /** Exact immutable parent-to-child link. It never substitutes for the child's saved policy. */
    internal class LinkedRequest internal constructor(
        private val parent: NativeActionPolicy.Request,
        val child: NativeActionPolicy.Request,
        private val binding: String,
    ) {
        fun requireBinding(expected: String) { child.requireBinding(expected); requireChild(child) }
        fun requireChild(child: NativeActionPolicy.Request) {
            check(child === this.child && child.operation == Operation.BOT_MASCOT && child.binding == binding) { "Native child scope or payload changed" }
            check(parent.operation == Operation.BOT_CREATE)
            parent.requireCurrent()
        }
        /** Called inside the child's short admission validation, with the shared store lock held. */
        fun admitParent(child: NativeActionPolicy.Request) {
            requireChild(child)
            parent.admit()
        }
    }

    companion object {
        private val REQUEST_LOCK = Any()
        const val CONNECTOR_ID = "native"
        @JvmStatic fun binding(vararg values: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            values.forEach { value ->
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        @JvmStatic @JvmOverloads fun inMemory(mode: AutonomyPolicy = AutonomyPolicy.ALLOW): NativeActionPolicy {
            val store = InMemoryConnectorAutonomyStore()
            if (mode != AutonomyPolicy.ALLOW) Operation.entries.forEach { store.setPolicy(CONNECTOR_ID, it.id, mode) }
            return NativeActionPolicy(store)
        }
    }
}
