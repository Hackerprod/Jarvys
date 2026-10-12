package com.jarvys.agent.linux

import android.content.Context
import com.jarvys.agent.R
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.connectors.ActionPolicySnapshot
import com.jarvys.agent.connectors.ActionPolicyOrigin
import com.jarvys.agent.connectors.AutonomyAuditRecord
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.ConnectorAutonomyStore
import com.jarvys.agent.connectors.ConnectorUiText
import com.jarvys.agent.connectors.SharedPreferencesConnectorAutonomyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Properties
import java.util.WeakHashMap

/** Separate principal and Coding command policies. Changes invalidate commands waiting to launch. */
class LinuxExecAutonomy internal constructor(
    private val store: ConnectorAutonomyStore,
    private val installedProvider: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class EnvironmentState {
        val lock = Any()
        var uninstallInProgress = false
        var maintenanceOwner: Any? = null
        var generation = 0L
        val revision = MutableStateFlow(0L)
    }
    private val environment = synchronized(environments) { environments.getOrPut(store) { EnvironmentState() } }
    private val policyLock = environment.lock
    private val mutableRevision = environment.revision
    val revision = mutableRevision.asStateFlow()

    fun policy(): AutonomyPolicy = synchronized(policyLock) { store.policy(CONNECTOR_ID, OPERATION_NAME) }
    class Authorization internal constructor(internal val snapshot: ActionPolicySnapshot,
        internal val generation: Long, internal val owner: Any) {
        val policy: AutonomyPolicy get() = snapshot.policy
    }

    class ProjectAuthorization internal constructor(internal val snapshot: ActionPolicySnapshot,
        internal val principal: ActionPolicySnapshot?, internal val generation: Long,
        internal val owner: Any, val revision: Long) {
        val policy: AutonomyPolicy get() = snapshot.policy
        override fun equals(other: Any?): Boolean = other is ProjectAuthorization && owner === other.owner &&
            generation == other.generation && snapshot.sameAuthorizationAs(other.snapshot) &&
            (if (principal == null) other.principal == null else other.principal != null && principal.sameAuthorizationAs(other.principal))
        override fun hashCode(): Int = 31 * System.identityHashCode(owner) + generation.hashCode() + policy.hashCode()
    }

    fun authorization(): Authorization = synchronized(policyLock) {
        Authorization(store.snapshot(CONNECTOR_ID, OPERATION_NAME), environment.generation, environment)
    }

    /** Maintenance choices share the Linux store but never inherit command policy. */
    fun maintenanceAuthorization(operation: String): Authorization = synchronized(policyLock) {
        require(operation in MAINTENANCE_OPERATIONS) { "Unknown Linux maintenance action" }
        Authorization(store.snapshot(CONNECTOR_ID, operation), environment.generation, environment)
    }

    fun setMaintenancePolicyFromUi(operation: String, policy: AutonomyPolicy): ConnectorUiText? = synchronized(policyLock) {
        require(operation in MAINTENANCE_OPERATIONS) { "Unknown Linux maintenance action" }
        try {
            store.setPolicyChecked(CONNECTOR_ID, operation, policy)
            changed()
            null
        } catch (_: RuntimeException) {
            ConnectorUiText(R.string.full_linux_autonomy_save_failed, fallback = "The Linux permission could not be saved. Its previous setting is unchanged.")
        }
    }

    /** No installed check: setup and removal must work when the environment is absent or incomplete. */
    fun admitMaintenance(authorization: Authorization, approvedAsk: Boolean, token: CancellationToken,
                         validate: () -> Unit = {}) {
        require(authorization.snapshot.operationName in MAINTENANCE_OPERATIONS)
        token.throwIfCancelled()
        check(token.runIfActive {
            synchronized(policyLock) {
                requireEnvironment(authorization.owner, authorization.generation)
                store.admit(store.snapshotMatching(authorization.snapshot), approvedAsk) {
                    token.throwIfCancelled()
                    requireEnvironment(authorization.owner, authorization.generation)
                    validate()
                }
            }
        }) { "Linux maintenance cancelled before admission" }
    }

    /** Only this removal owns the new generation; old commands and other maintenance cannot borrow it. */
    fun <T> withAdmittedUninstall(authorization: Authorization, approvedAsk: Boolean, token: CancellationToken,
                                validate: () -> Unit = {}, remove: (admitEffect: () -> Unit) -> T): T {
        require(authorization.snapshot.operationName == UNINSTALL_OPERATION_NAME)
        token.throwIfCancelled()
        val owner = Any()
        var generation = -1L
        check(token.runIfActive {
            synchronized(policyLock) {
                requireEnvironment(authorization.owner, authorization.generation)
                store.admit(store.snapshotMatching(authorization.snapshot), approvedAsk) {
                    token.throwIfCancelled()
                    validate()
                }
                environment.uninstallInProgress = true
                environment.maintenanceOwner = owner
                resetBeforeUninstall()
                generation = environment.generation
            }
        }) { "Linux removal cancelled before admission" }
        fun admitEffect() {
            token.throwIfCancelled()
            check(token.runIfActive {
                synchronized(policyLock) {
                    check(environment.maintenanceOwner === owner && environment.uninstallInProgress &&
                        environment.generation == generation) { "Linux removal ownership changed" }
                    store.admit(store.snapshotMatching(authorization.snapshot), approvedAsk) {
                        token.throwIfCancelled()
                        validate()
                    }
                }
            }) { "Linux removal cancelled before effect" }
        }
        try { return remove(::admitEffect) }
        finally {
            synchronized(policyLock) {
                if (environment.maintenanceOwner === owner) {
                    // Requests captured while removal was in progress cannot outlive its owner.
                    resetBeforeUninstall()
                    environment.maintenanceOwner = null
                    environment.uninstallInProgress = false
                    changed()
                }
            }
        }
    }

    /** Both opaque snapshots bind inherited/default policy, explicit overrides, restoration and ABA changes. */
    fun projectAuthorization(): ProjectAuthorization = synchronized(policyLock) {
        var project = store.snapshot(PROJECT_CONNECTOR_ID, PROJECT_OPERATION_NAME)
        var principal: ActionPolicySnapshot? = null
        if (project.origin == ActionPolicyOrigin.CATALOG_DEFAULT) {
            val inherited = store.snapshot(CONNECTOR_ID, OPERATION_NAME)
            project = store.snapshot(PROJECT_CONNECTOR_ID, PROJECT_OPERATION_NAME, inherited.policy)
            if (project.origin == ActionPolicyOrigin.INHERITED_SERVER_DEFAULT) principal = inherited
        }
        ProjectAuthorization(project, principal, environment.generation, environment, mutableRevision.value)
    }

    /** An exact live card owns this durable edit receipt; a later ALLOW value is never a substitute. */
    fun allowFromApproval(anchor: Authorization): Authorization = synchronized(policyLock) {
        requireEnvironment(anchor.owner, anchor.generation)
        store.snapshotMatching(anchor.snapshot)
        val snapshot = store.setPolicyAndSnapshot(CONNECTOR_ID, OPERATION_NAME, AutonomyPolicy.ALLOW)
        changed()
        Authorization(snapshot, environment.generation, environment)
    }

    fun allowProjectFromApproval(anchor: ProjectAuthorization): ProjectAuthorization = synchronized(policyLock) {
        requireEnvironment(anchor.owner, anchor.generation)
        store.snapshotMatching(anchor.snapshot)
        anchor.principal?.let(store::snapshotMatching)
        val snapshot = store.setPolicyAndSnapshot(PROJECT_CONNECTOR_ID, PROJECT_OPERATION_NAME, AutonomyPolicy.ALLOW)
        changed()
        ProjectAuthorization(snapshot, null, environment.generation, environment, mutableRevision.value)
    }

    fun admit(authorization: Authorization, approvedAsk: Boolean, token: CancellationToken,
              validate: () -> Unit = {}) {
        // The installation check may touch the filesystem; keep it outside the policy-store lock.
        check(installed()) { "Linux environment is unavailable" }
        check(token.runIfActive {
            synchronized(policyLock) {
                requireEnvironment(authorization.owner, authorization.generation)
                store.admit(store.snapshotMatching(authorization.snapshot), approvedAsk) {
                    token.throwIfCancelled()
                    requireEnvironment(authorization.owner, authorization.generation)
                    validate()
                }
            }
        }) { "Command cancelled before admission" }
    }

    fun admitProject(authorization: ProjectAuthorization, approvedAsk: Boolean, token: CancellationToken,
                     validate: () -> Unit = {}) {
        check(installed()) { "Linux environment is unavailable" }
        check(token.runIfActive {
            synchronized(policyLock) {
                requireEnvironment(authorization.owner, authorization.generation)
                store.admit(store.snapshotMatching(authorization.snapshot), approvedAsk) {
                    token.throwIfCancelled()
                    requireEnvironment(authorization.owner, authorization.generation)
                    authorization.principal?.let(store::snapshotMatching)
                    validate()
                    authorization.principal?.let(store::snapshotMatching)
                }
            }
        }) { "Command cancelled before admission" }
    }

    private fun requireEnvironment(owner: Any, generation: Long) {
        check(owner === environment && generation == environment.generation && !environment.uninstallInProgress) {
            "Linux environment changed; prepare a new command"
        }
    }
    fun projectPolicy(): AutonomyPolicy = projectAuthorization().policy
    fun installed(): Boolean = runCatching(installedProvider).getOrDefault(false)

    fun setPolicyFromUi(policy: AutonomyPolicy): ConnectorUiText? = synchronized(policyLock) {
        try {
            store.setPolicyChecked(CONNECTOR_ID, OPERATION_NAME, policy)
            changed()
            null
        } catch (_: RuntimeException) {
            ConnectorUiText(R.string.full_linux_autonomy_save_failed, fallback = "The Linux permission could not be saved. Its previous setting is unchanged.")
        }
    }

    fun setProjectPolicyFromUi(policy: AutonomyPolicy): ConnectorUiText? = synchronized(policyLock) {
        try {
            store.setPolicyChecked(PROJECT_CONNECTOR_ID, PROJECT_OPERATION_NAME, policy)
            changed()
            null
        } catch (_: RuntimeException) {
            ConnectorUiText(R.string.full_coding_autonomy_save_failed, fallback = "The Coding permission could not be saved. Its previous setting is unchanged.")
        }
    }

    /** Compatibility name: invalidate prepared environments, never rewrite the user's saved choices. */
    fun resetBeforeUninstall() = synchronized(policyLock) {
        environment.generation = Math.addExact(environment.generation, 1L)
        changed()
    }

    fun <T> withUninstallReset(removeEnvironment: () -> T): T {
        synchronized(policyLock) {
            check(!environment.uninstallInProgress) { "Linux uninstall is already in progress" }
            environment.uninstallInProgress = true
            resetBeforeUninstall()
        }
        try { return removeEnvironment() }
        finally { synchronized(policyLock) { environment.uninstallInProgress = false; changed() } }
    }

    fun recordAutomaticExecution(command: String, cwd: String): ConnectorUiText? = try {
        store.appendAudit(AutonomyAuditRecord(timestampMillis = clock(), connectorId = CONNECTOR_ID,
            connectorName = "Linux", operationName = OPERATION_NAME, operationLabel = "Run commands",
            summary = "$command\nWorking directory: $cwd"))
        changed()
        null
    } catch (_: RuntimeException) {
        ConnectorUiText(R.string.full_linux_autonomy_audit_failed, fallback = "The command was not executed because its local audit could not be saved.")
    }

    private fun changed() = synchronized(mutableRevision) { mutableRevision.value += 1 }

    companion object {
        const val CONNECTOR_ID = "linux"
        const val OPERATION_NAME = "linux_exec"
        const val SETUP_OPERATION_NAME = "linux_setup"
        const val UNINSTALL_OPERATION_NAME = "linux_uninstall"
        val MAINTENANCE_OPERATIONS = setOf(SETUP_OPERATION_NAME, UNINSTALL_OPERATION_NAME)
        const val PROJECT_CONNECTOR_ID = "coding"
        const val PROJECT_OPERATION_NAME = "project_exec"
        private val environments = WeakHashMap<ConnectorAutonomyStore, EnvironmentState>()
        private val instances = WeakHashMap<Context, LinuxExecAutonomy>()
        @JvmStatic fun get(context: Context): LinuxExecAutonomy = synchronized(instances) {
            val app = context.applicationContext
            instances.getOrPut(app) {
                val paths = RootfsInstaller.Paths(app.filesDir)
                LinuxExecAutonomy(SharedPreferencesConnectorAutonomyStore(app), {
                    paths.rootfs.isDirectory && paths.stateFile.isFile && paths.stateFile.inputStream().use {
                        Properties().apply { load(it) }.getProperty("phase") in setOf("READY", "FAILED")
                    }
                })
            }
        }
    }
}
