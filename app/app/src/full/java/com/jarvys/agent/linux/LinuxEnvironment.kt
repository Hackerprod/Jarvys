package com.jarvys.agent.linux

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.io.IOException
import java.util.UUID

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
        processExecutor.terminateAllAndWait()
        installer.uninstall() // Keeps private workspace files by design.
    }
    override fun deleteWorkspace() = installer.deleteWorkspace()

    override fun probe(): LinuxProbeResult = LinuxProbe(paths.rootfs, nativeLibraryDir(),
        executor = LinuxProbeExecutor { command, callback ->
            exec(command, "/", null, callback, com.jarvys.agent.CancellationToken.uncancellable())
        }).probe()

    /** Independent calls may overlap; each receives an isolated PRoot temporary directory. */
    override fun exec(command: String, cwd: String, timeoutMillis: Long?, callback: LinuxOutputCallback,
                      token: com.jarvys.agent.CancellationToken): LinuxExecResult {
        token.throwIfCancelled()
        ProotLauncher.validateGuestCwd(cwd)
        if (!paths.rootfs.isDirectory) throw IOException("Linux rootfs is not installed")
        ensureCertificates(paths.rootfs)
        val workspace = paths.workspace
        if (!workspace.exists() && !workspace.mkdirs()) throw IOException("Cannot create private Linux workspace")
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
                runCatching { Os.chmod(binary.absolutePath, 0x1ED /* 0755 */) }
                if (!binary.canExecute()) throw IOException("exec denied: ${binary.absolutePath}")
            }
            val plan = ProotLauncher.plan(proot, loader, paths.rootfs, cwd, workspace, processTemp,
                nativeLibraryDir, command)
            return processExecutor.execute(plan, timeoutMillis, token, callback)
        } finally {
            RootfsInstaller.deletePrivateTree(processTemp, fileKindReader)
        }
    }
}
