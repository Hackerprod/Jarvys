package com.jarvys.agent.linux

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.io.IOException
import java.util.UUID
import com.jarvys.agent.coding.ProjectScope
import com.jarvys.agent.CancellationToken

/** Full-flavor boundary for L1. It intentionally contains no tools, UI, or approval policy. */
class LinuxEnvironment(
    context: Context,
    private val installer: RootfsInstaller = RootfsInstaller(context.applicationContext),
    private val processExecutor: ProotProcessExecutor = ProotProcessExecutor(),
    private val fileKindReader: LinuxFileKindReader = AndroidLinuxFileKindReader,
    private val ensureCertificates: (File) -> Unit = { RootfsCertificates.ensure(it) },
) : LinuxRuntime {
    private val app = context.applicationContext
    private val paths = RootfsInstaller.Paths(app.filesDir)
    private fun nativeLibraryDir(): File = app.applicationInfo.nativeLibraryDir?.let(::File) ?: File(app.filesDir, "lib")

    override fun state(): LinuxInstallState = installer.state()
    override fun observe(observer: (LinuxInstallState) -> Unit): AutoCloseable = installer.observe(observer)
    fun install(progress: (LinuxInstallState) -> Unit, token: com.jarvys.agent.CancellationToken): LinuxInstallState =
        installer.install(token, progress)

    override fun installForProbe(progress: (LinuxInstallState) -> Unit,
                                 token: com.jarvys.agent.CancellationToken): LinuxInstallState =
        installer.install(token, progress, retainArchiveForProbe = true)

    override fun availableBytes(): Long = installer.availableBytes()
    override fun supportsArm64(): Boolean = Build.SUPPORTED_ABIS.contains("arm64-v8a")
    override fun workspacePath(): String = paths.workspace.absolutePath
    override fun markProbeFailure(code: String, detail: String) = installer.markProbeFailure(code, detail)
    override fun markProbeSuccess() = installer.markProbeSuccess()
    override fun uninstall() {
        processExecutor.withLaunchesSuspended {
            processExecutor.terminateAllAndWait()
            installer.uninstall() // Keeps private workspace files by design.
        }
    }
    override fun deleteWorkspace() = installer.deleteWorkspace()

    override fun probe(): LinuxProbeResult = LinuxProbe(paths.rootfs, nativeLibraryDir(),
        executor = LinuxProbeExecutor { command, callback ->
            exec(command, "/", null, callback, com.jarvys.agent.CancellationToken.uncancellable())
        }).probe()

    /** Independent calls may overlap; each receives an isolated PRoot temporary directory. */
    override fun exec(command: String, cwd: String, timeoutMillis: Long?, callback: LinuxOutputCallback,
                      token: com.jarvys.agent.CancellationToken): LinuxExecResult {
        return execute(command, cwd, timeoutMillis, callback, token, null) { }
    }

    override fun projectStatus(scope: ProjectScope, cwd: String, requiredTools: List<String>): LinuxProjectStatus =
        LinuxProjectPaths.inspect(paths.rootfs, nativeLibraryDir(), scope, cwd, supportsArm64(), state(),
            fileKindReader, requiredTools = requiredTools, deviceAbis = Build.SUPPORTED_ABIS.toList())

    override fun execProject(scope: ProjectScope, command: String, cwd: String, timeoutMillis: Long?,
                             callback: LinuxOutputCallback, token: CancellationToken, beforeLaunch: () -> Unit): LinuxExecResult {
        val status = projectStatus(scope, cwd)
        check(status.ready) { status.reason }
        return execute(command, LinuxProjectPaths.cwd(scope, cwd), timeoutMillis, callback, token, scope) {
            val current = projectStatus(scope, cwd)
            check(current.ready) { current.reason }
            LinuxProjectPaths.cwd(scope, cwd)
            beforeLaunch()
        }
    }

    private fun execute(command: String, cwd: String, timeoutMillis: Long?, callback: LinuxOutputCallback,
                        token: CancellationToken, scope: ProjectScope?, beforeLaunch: () -> Unit): LinuxExecResult {
        token.throwIfCancelled()
        ProotLauncher.validateGuestCwd(cwd)
        if (!paths.rootfs.isDirectory) throw IOException("Linux rootfs is not installed")
        if (scope == null) ensureCertificates(paths.rootfs)
        val workspace = scope?.validatedMountRoot() ?: paths.workspace
        if (!workspace.exists() && (scope != null || !workspace.mkdirs())) throw IOException("Cannot create private Linux workspace")
        val tempRoot = File(paths.linuxDir, "tmp")
        if (!tempRoot.exists() && !tempRoot.mkdirs()) throw IOException("Cannot create PRoot temporary root")
        val processTemp = File(tempRoot, "proot-${UUID.randomUUID()}")
        if (!processTemp.mkdirs()) throw IOException("Cannot create PRoot temporary directory")
        try {
            val nativeLibraryDir = nativeLibraryDir()
            val proot = File(nativeLibraryDir, "libproot_exec.so")
            val loader = File(nativeLibraryDir, "libproot_loader.so")
            for (binary in listOf(proot, loader)) {
                if (!binary.isFile) throw IOException("Missing native binary: ${binary.absolutePath}")
                if (scope == null) runCatching { Os.chmod(binary.absolutePath, 0x1ED /* 0755 */) }
                if (!binary.canExecute()) throw IOException("exec denied: ${binary.absolutePath}")
            }
            val plan = ProotLauncher.plan(proot, loader, paths.rootfs, cwd, workspace, processTemp,
                nativeLibraryDir, command, projectProbe = scope != null)
            return processExecutor.execute(plan, timeoutMillis, token, callback, beforeLaunch)
        } finally {
            RootfsInstaller.deletePrivateTree(processTemp, fileKindReader)
        }
    }
    companion object {
        private val instances = java.util.WeakHashMap<Context, LinuxEnvironment>()
        @JvmStatic fun get(context: Context): LinuxEnvironment = synchronized(instances) {
            val app = context.applicationContext
            instances.getOrPut(app) { LinuxEnvironment(app) }
        }
    }
}
