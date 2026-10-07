package com.jarvys.agent.linux

import android.content.Context
import com.jarvys.agent.R
import com.jarvys.agent.connectors.AutonomyAuditRecord
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.ConnectorAutonomyStore
import com.jarvys.agent.connectors.ConnectorUiText
import com.jarvys.agent.connectors.SharedPreferencesConnectorAutonomyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Properties
import java.util.WeakHashMap

/** Explicit command consent. Changes invalidate approvals waiting to launch. */
class LinuxExecAutonomy internal constructor(
    private val store: ConnectorAutonomyStore,
    private val installedProvider: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val policyLock = Any()
    private var uninstallInProgress = false
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    fun policy(): AutonomyPolicy = synchronized(policyLock) { store.policy(CONNECTOR_ID, OPERATION_NAME) }
    fun installed(): Boolean = runCatching(installedProvider).getOrDefault(false)

    fun setPolicyFromUi(policy: AutonomyPolicy): ConnectorUiText? = synchronized(policyLock) {
        if (policy == AutonomyPolicy.ALLOW && (uninstallInProgress || !installed())) {
            return@synchronized ConnectorUiText(R.string.full_linux_autonomy_unavailable, fallback = "Install the Linux environment before enabling Allow mode.")
        }
        try {
            store.setPolicyChecked(CONNECTOR_ID, OPERATION_NAME, policy)
            changed()
            null
        } catch (_: RuntimeException) {
            ConnectorUiText(R.string.full_linux_autonomy_save_failed, fallback = "The Linux permission could not be saved. Its previous setting is unchanged.")
        }
    }

    fun resetBeforeUninstall() = synchronized(policyLock) {
        store.setPolicyChecked(CONNECTOR_ID, OPERATION_NAME, AutonomyPolicy.ASK)
        changed()
    }

    fun <T> withUninstallReset(removeEnvironment: () -> T): T {
        synchronized(policyLock) {
            check(!uninstallInProgress) { "Linux uninstall is already in progress" }
            resetBeforeUninstall()
            uninstallInProgress = true
        }
        try { return removeEnvironment() }
        finally { synchronized(policyLock) { uninstallInProgress = false; changed() } }
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
