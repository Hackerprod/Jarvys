package com.jarvys.agent.linux

import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.jarvys.agent.CancellationToken
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

data class ProotCommandPlan(val argv: List<String>, val environment: Map<String, String>, val workingDirectory: File? = null)

/** Pure argument/environment construction: user command is always one guest argv element. */
object ProotLauncher {
    fun buildArgv(proot: String, rootfs: String, cwd: String, workspace: String, command: String,
                  shell: String = "/bin/bash", projectProbe: Boolean = false,
                  projectPrivateRoot: String? = null): List<String> {
        validateGuestCwd(cwd)
        require(!projectProbe || projectPrivateRoot != null) { "Project execution requires private home/temp overlays" }
        val script = (if (projectProbe) PROJECT_PROBE else "") + "cd -- \"\$1\" && eval \"\$2\""
        val argv = mutableListOf(proot, "--root-id", "--link2symlink", "--kill-on-exit", "-r", rootfs,
            "-w", cwd, "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "$workspace:/workspace")
        if (projectProbe) argv += listOf("-b", "$projectPrivateRoot/root:/root", "-b", "$projectPrivateRoot/home:/home",
            "-b", "$projectPrivateRoot/tmp:/tmp", "-b", "$projectPrivateRoot/tmp:/var/tmp")
        argv += listOf("/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "LANG=C.UTF-8")
        if (projectProbe) argv += listOf("TMPDIR=/tmp", "XDG_CONFIG_HOME=/root/.config", "GIT_CONFIG_NOSYSTEM=1")
        argv += listOf(shell, if (projectProbe) "-c" else "-lc", script, "jarvys", cwd, command)
        return argv
    }

    private const val PROJECT_PROBE = "if [ \"\$(uname -m)\" != aarch64 ] || [ \"\$(id -u)\" != 0 ] || [ ! -r /etc/os-release ]; then printf '%s\\n' 'Project environment probe failed; command not executed.' >&2; exit 125; fi; "

    fun validateGuestCwd(cwd: String) {
        require(cwd.startsWith('/') && '\u0000' !in cwd && cwd.split('/').none { it == ".." }) {
            "Guest cwd must be absolute and contain no NUL or parent traversal"
        }
    }

    fun buildEnvironment(loader: String, tempDir: String, nativeLibraryDir: String): Map<String, String> = linkedMapOf(
        "PROOT_LOADER" to loader,
        "PROOT_TMP_DIR" to tempDir,
        "TMPDIR" to tempDir,
        "LD_LIBRARY_PATH" to "$tempDir:$nativeLibraryDir",
    )

    fun plan(proot: File, loader: File, rootfs: File, cwd: String, workspace: File,
             tempDir: File, nativeLibraryDir: File, command: String,
             fileKindReader: LinuxFileKindReader = AndroidLinuxFileKindReader,
             readGuestLink: (File) -> String = { Os.readlink(it.absolutePath) },
             projectProbe: Boolean = false): ProotCommandPlan {
        val talloc = File(nativeLibraryDir, "libtalloc.so")
        if (!tempDir.exists() && !tempDir.mkdirs()) throw IOException("Cannot create PRoot temporary directory")
        if (!talloc.isFile) throw IOException("libtalloc.so is missing from native libraries")
        val uniqueLibraryDir = File(tempDir, "lib-${tallocIdentity(talloc)}")
        if (!uniqueLibraryDir.exists() && !uniqueLibraryDir.mkdirs()) throw diagnosticFailure(tempDir,
            File(uniqueLibraryDir, "libtalloc.so.2"), fileKindReader, readGuestLink, IOException("Cannot create unique libtalloc directory"))
        runCatching { Os.chmod(uniqueLibraryDir.absolutePath, 0x1C0 /* 0700 */) }
        val soname = File(uniqueLibraryDir, "libtalloc.so.2")
        val sonameKind = fileKindReader.kind(soname)
        val sonameMode = LinuxFileModes.mode(soname)
        if (!(sonameKind == LinuxFileKind.OTHER && sonameMode == OsConstants.S_IFREG && soname.length() == talloc.length())) {
            try {
                if (sonameKind != LinuxFileKind.MISSING && (sonameKind == LinuxFileKind.SYMLINK || soname.exists())) Os.remove(soname.absolutePath)
                val temporary = File(uniqueLibraryDir, "libtalloc.so.2.copy")
                if (fileKindReader.kind(temporary) != LinuxFileKind.MISSING) Os.remove(temporary.absolutePath)
                FileInputStream(talloc).use { input -> FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() } }
                if (!temporary.renameTo(soname)) throw IOException("Cannot install unique libtalloc.so.2 copy")
            } catch (failure: Throwable) {
                throw diagnosticFailure(tempDir, soname, fileKindReader, readGuestLink, failure)
            }
        }
        cleanupLegacyTalloc(tempDir, uniqueLibraryDir, fileKindReader)
        val shell = resolveGuestExecutable(rootfs, "/bin/bash", fileKindReader, readGuestLink)
            ?: resolveGuestExecutable(rootfs, "/bin/sh", fileKindReader, readGuestLink)
            ?: throw IOException("Guest rootfs has neither executable /bin/bash nor /bin/sh")
        val privateRoot = if (projectProbe) File(tempDir, "guest-private").also { private ->
            for (name in listOf("root", "home", "tmp")) {
                if (!File(private, name).mkdirs()) throw IOException("Cannot create isolated project home/temp directory")
            }
        } else null
        return ProotCommandPlan(
            buildArgv(proot.absolutePath, rootfs.absolutePath, cwd, workspace.absolutePath, command, shell, projectProbe, privateRoot?.absolutePath),
            buildEnvironment(loader.absolutePath, uniqueLibraryDir.absolutePath, nativeLibraryDir.absolutePath),
            rootfs.absoluteFile.parentFile,
        )
    }

    private fun tallocIdentity(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    private fun diagnosticFailure(tempDir: File, entry: File, reader: LinuxFileKindReader,
                                  readLink: (File) -> String, failure: Throwable): IOException {
        val kind = runCatching { reader.kind(entry) }.getOrDefault(LinuxFileKind.UNKNOWN)
        val target = if (kind == LinuxFileKind.SYMLINK) runCatching { readLink(entry) }.getOrNull()?.let {
            " target=${if (it.startsWith('/')) "[host path]/${File(it).name}" else it}"
        }.orEmpty() else ""
        val directoryMode = LinuxFileModes.mode(tempDir)?.let { "0${it.toString(8)}" } ?: "unknown"
        val errno = (failure as? android.system.ErrnoException)?.let { runCatching { OsConstants.errnoName(it.errno) }.getOrNull() }
        return IOException("Cannot prepare libtalloc: ${failure.message}; errno=${errno ?: "none"}; entry=$kind$target; tmp_mode=$directoryMode; tmp_writable=${tempDir.canWrite()}", failure)
    }

    private fun cleanupLegacyTalloc(tempDir: File, current: File, reader: LinuxFileKindReader) {
        val canonicalRoot = runCatching { tempDir.canonicalFile }.getOrNull() ?: return
        fun unlink(entry: File) {
            if (entry.parentFile?.canonicalFile != canonicalRoot) return
            when (runCatching { reader.kind(entry) }.getOrDefault(LinuxFileKind.UNKNOWN)) {
                LinuxFileKind.SYMLINK, LinuxFileKind.OTHER -> runCatching { Os.remove(entry.absolutePath) }
                LinuxFileKind.DIRECTORY -> runCatching { RootfsInstaller.deletePrivateTree(entry, reader) }
                else -> Unit
            }
        }
        unlink(File(tempDir, "libtalloc.so.2")); unlink(File(tempDir, "libtalloc.so.2.copy"))
        tempDir.listFiles()?.filter { it.name.startsWith("lib-") && it != current }?.forEach(::unlink)
    }

    internal fun resolveGuestExecutable(rootfs: File, guestPath: String,
                                        fileKindReader: LinuxFileKindReader = AndroidLinuxFileKindReader,
                                        readGuestLink: (File) -> String = { Os.readlink(it.absolutePath) }): String? =
        resolveGuestPath(rootfs, guestPath, requireExecutable = true, fileKindReader = fileKindReader, readGuestLink = readGuestLink)

    internal fun resolveGuestPath(rootfs: File, guestPath: String, requireExecutable: Boolean,
                                  fileKindReader: LinuxFileKindReader = AndroidLinuxFileKindReader,
                                  readGuestLink: (File) -> String = { Os.readlink(it.absolutePath) }): String? {
        if (!guestPath.startsWith('/') || '\u0000' in guestPath || guestPath.split('/').any { it == ".." }) return null
        val root = rootfs.canonicalFile
        val pending = java.util.ArrayDeque<String>()
        guestPath.split('/').filter(String::isNotEmpty).forEach(pending::addLast)
        val resolved = mutableListOf<String>()
        var links = 0
        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            if (component.isEmpty() || component == ".") continue
            if (component == "..") {
                if (resolved.isEmpty()) return null
                resolved.removeAt(resolved.lastIndex)
                continue
            }
            val file = File(root, (resolved + component).joinToString(File.separator))
            when (fileKindReader.kind(file)) {
                LinuxFileKind.SYMLINK -> {
                    links++
                    if (links > MAX_GUEST_SYMLINKS) return null
                    val target = runCatching { readGuestLink(file) }.getOrNull() ?: return null
                    val targetParts = target.split('/').filter(String::isNotEmpty)
                    if (target.startsWith('/')) resolved.clear()
                    targetParts.asReversed().forEach(pending::addFirst)
                }
                LinuxFileKind.DIRECTORY -> resolved += component
                LinuxFileKind.OTHER -> {
                    if (LinuxFileModes.mode(file) != OsConstants.S_IFREG) return null
                    if (pending.isNotEmpty()) return null
                    val permissionBits = LinuxFileModes.permissions(file)
                    val executable = permissionBits?.and(0x49)?.let { it != 0 } ?: file.canExecute()
                    if (requireExecutable && !executable) return null
                    return "/" + (resolved + component).joinToString("/")
                }
                else -> return null
            }
        }
        return null
    }

    private const val MAX_GUEST_SYMLINKS = 40
}

enum class LinuxOutputStream { STDOUT, STDERR }
fun interface LinuxOutputCallback { fun onChunk(stream: LinuxOutputStream, text: String) }
data class LinuxExecResult(val exitCode: Int, val timedOut: Boolean = false, val cancelled: Boolean = false)

interface ProcessTree {
    fun processIds(): List<Int>
    fun parentPid(pid: Int): Int?
    fun kill(pid: Int)
    fun identity(pid: Int): String? = null
    fun requiresIdentity(): Boolean = false
}

/** Cycle-safe parent/child traversal; children are signalled before their parent. */
class ProcessTreeKiller(private val tree: ProcessTree) {
    private val killedIdentities = ConcurrentHashMap<Int, String>()
    fun descendants(rootPid: Int): List<Int> {
        val all = tree.processIds().toSet()
        val children = all.associateWith { pid -> tree.parentPid(pid) }
        val found = linkedSetOf<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(rootPid)
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            for ((pid, ppid) in children) if (ppid == parent && pid != rootPid && found.add(pid)) queue.add(pid)
        }
        return found.toList()
    }

    fun identity(pid: Int): String? = tree.identity(pid)

    fun killTree(rootPid: Int, expectedIdentity: String? = null): List<Int> {
        val rootIdentity = tree.identity(rootPid)
        if ((expectedIdentity != null && rootIdentity != expectedIdentity) || (tree.requiresIdentity() && rootIdentity == null)) return emptyList()
        val children = descendants(rootPid)
        val identities = children.associateWith(tree::identity).toMutableMap().apply { put(rootPid, rootIdentity) }
        val killed = mutableListOf<Int>()
        for (pid in children.asReversed() + rootPid) {
            if (tree.identity(rootPid) != rootIdentity) break
            val expected = identities[pid]
            if (tree.requiresIdentity() && expected == null) continue
            if (tree.identity(pid) != expected) continue
            if (pid != rootPid) {
                if (pid !in descendants(rootPid)) continue
                if (tree.identity(rootPid) != rootIdentity) break
                if (tree.identity(pid) != expected) continue
            }
            if (tree.identity(rootPid) != rootIdentity) break
            if (expected != null) killedIdentities[pid] = expected
            try { tree.kill(pid); killed += pid }
            catch (_: RuntimeException) { if (expected != null) killedIdentities.remove(pid, expected) }
        }
        return killed
    }

    fun awaitGone(pids: List<Int>) {
        val targets = pids.toSet()
        var interrupted = false
        try {
            while (tree.processIds().any { pid -> pid in targets &&
                    (killedIdentities[pid]?.let { tree.identity(pid) == it } ?: !tree.requiresIdentity()) }) {
                try { Thread.sleep(25) } catch (_: InterruptedException) { interrupted = true }
            }
        } finally {
            targets.forEach(killedIdentities::remove)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

}

class AndroidProcessTree(private val procRoot: File = File("/proc")) : ProcessTree {
    override fun processIds(): List<Int> = procRoot.listFiles()?.mapNotNull { it.name.toIntOrNull() }.orEmpty()
    override fun parentPid(pid: Int): Int? = runCatching {
        val stat = File(procRoot, "$pid/stat").readText()
        val end = stat.lastIndexOf(')')
        stat.substring(end + 1).trim().split(Regex("\\s+"))[1].toInt()
    }.getOrNull()
    override fun kill(pid: Int) { android.os.Process.killProcess(pid) }
    override fun requiresIdentity() = true
    override fun identity(pid: Int): String? = runCatching {
        val stat = File(procRoot, "$pid/stat").readText()
        "$pid:${stat.substring(stat.lastIndexOf(')') + 1).trim().split(Regex("\\s+"))[19]}"
    }.getOrNull()
}

/** Executes PRoot without an intervening host shell and drains both pipes independently. */
class ProotProcessExecutor(private val killer: ProcessTreeKiller = ProcessTreeKiller(AndroidProcessTree())) {
    private data class ActiveExecution(val kill: () -> Unit, val finished: CountDownLatch = CountDownLatch(1))
    private val nextExecutionId = AtomicLong(1L)
    private val activeExecutions = ConcurrentHashMap<Long, ActiveExecution>()
    private val launchLock = Any()
    private var launchesSuspended = false

    fun <T> withLaunchesSuspended(action: () -> T): T {
        synchronized(launchLock) { check(!launchesSuspended) { "Runtime removal is already in progress" }; launchesSuspended = true }
        try { return action() } finally { synchronized(launchLock) { launchesSuspended = false } }
    }

    /** Used by uninstall: signal every live PRoot tree and wait until each runner has reaped it. */
    fun terminateAllAndWait() {
        val active = activeExecutions.values.toList()
        active.forEach { runCatching { it.kill() } }
        var interrupted = false
        try {
            active.forEach { execution ->
                while (true) {
                    try { execution.finished.await(); break }
                    catch (_: InterruptedException) { interrupted = true }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun execute(plan: ProotCommandPlan, timeoutMillis: Long? = null, token: CancellationToken,
                callback: LinuxOutputCallback, beforeLaunch: () -> Unit = { }): LinuxExecResult {
        token.throwIfCancelled()
        require(timeoutMillis == null || timeoutMillis > 0) { "Timeout must be positive" }
        val builder = ProcessBuilder(plan.argv).apply {
            environment().clear(); environment().putAll(plan.environment)
            plan.workingDirectory?.let { directory(it) }
        }
        lateinit var process: Process
        var ownedPid: Int? = null
        var ownedIdentity: String? = null
        val killed = java.util.concurrent.atomic.AtomicBoolean(false)
        val killedPids = java.util.concurrent.atomic.AtomicReference<List<Int>>(emptyList())
        val killAction = {
            if (killed.compareAndSet(false, true)) {
                try {
                    if (isAlive(process)) ownedPid?.let { killedPids.set(killer.killTree(it, ownedIdentity)) }
                } finally { process.destroyForcibly() }
            }
            Unit
        }
        val executionId = nextExecutionId.getAndIncrement()
        lateinit var active: ActiveExecution
        var unregister = Runnable { }
        synchronized(launchLock) {
            check(!launchesSuspended) { "Runtime removal is in progress; command not launched" }
            token.throwIfCancelled(); beforeLaunch(); token.throwIfCancelled()
            process = builder.start()
            ownedPid = runCatching { processPid(process) }.getOrNull()
            ownedIdentity = ownedPid?.let(killer::identity)
            active = ActiveExecution(killAction)
            activeExecutions[executionId] = active
            unregister = token.registerCancelAction(killAction)
        }
        val outputFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val guardedOutput = LinuxOutputCallback { stream, text ->
            try { callback.onChunk(stream, text) }
            catch (failure: Throwable) { outputFailure.compareAndSet(null, failure); runCatching(killAction) }
        }
        val readFailure: (IOException) -> Unit = { failure ->
            if (!killed.get()) { outputFailure.compareAndSet(null, failure); runCatching(killAction) }
        }
        var stdout: Thread? = null
        var stderr: Thread? = null
        var timedOut = false
        try {
            process.outputStream.close()
            stdout = drain("jarvys-linux-stdout", process.inputStream, LinuxOutputStream.STDOUT, guardedOutput, readFailure)
            stderr = drain("jarvys-linux-stderr", process.errorStream, LinuxOutputStream.STDERR, guardedOutput, readFailure)
            if (!waitFor(process, timeoutMillis, token)) { timedOut = !token.isCancellationRequested; killAction() }
            reap(process)
            killer.awaitGone(killedPids.get())
            joinUninterruptibly(stdout); joinUninterruptibly(stderr)
            outputFailure.get()?.let { throw IOException("Command output could not be saved", it) }
            return LinuxExecResult(process.exitValue(), timedOut, token.isCancellationRequested || (killed.get() && !timedOut))
        } catch (_: CancellationException) {
            killAction(); reap(process); killer.awaitGone(killedPids.get())
            joinUninterruptibly(stdout); joinUninterruptibly(stderr)
            return LinuxExecResult(process.exitValue(), timedOut, cancelled = true)
        } finally {
            // No exceptional path may release ownership before reaping the child and drainers.
            if (isAlive(process)) runCatching(killAction)
            reap(process); killer.awaitGone(killedPids.get())
            joinUninterruptibly(stdout); joinUninterruptibly(stderr)
            unregister.run(); activeExecutions.remove(executionId, active); active.finished.countDown()
        }
    }

    private fun isAlive(process: Process): Boolean = try { process.exitValue(); false } catch (_: IllegalThreadStateException) { true }
    private fun reap(process: Process) {
        var interrupted = false
        try { while (true) { try { process.waitFor(); return } catch (_: InterruptedException) { interrupted = true } } }
        finally { if (interrupted) Thread.currentThread().interrupt() }
    }
    private fun joinUninterruptibly(thread: Thread?) {
        if (thread == null) return
        var interrupted = false
        try { while (true) { try { thread.join(); return } catch (_: InterruptedException) { interrupted = true } } }
        finally { if (interrupted) Thread.currentThread().interrupt() }
    }
    private fun waitFor(process: Process, timeoutMillis: Long?, token: CancellationToken): Boolean {
        val started = System.nanoTime()
        while (true) {
            token.throwIfCancelled()
            if (!isAlive(process)) return true
            if (timeoutMillis != null && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= timeoutMillis) return false
            try { Thread.sleep(25) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); token.throwIfCancelled() }
        }
    }

    private fun processPid(process: Process): Int {
        runCatching {
            process.javaClass.getMethod("pid").invoke(process).let { (it as Number).toInt() }
        }.getOrNull()?.let { return it }
        var type: Class<*>? = process.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == "pid" }
            if (field != null) {
                runCatching { field.isAccessible = true; (field.get(process) as Number).toInt() }
                    .getOrNull()?.let { return it }
            }
            type = type.superclass
        }
        throw IOException("Cannot obtain child process id on this Android version")
    }

    private fun drain(name: String, stream: InputStream, kind: LinuxOutputStream,
                      callback: LinuxOutputCallback, readFailure: (IOException) -> Unit = { }) = Thread({
        try {
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (count > 0) callback.onChunk(kind, String(buffer, 0, count))
                }
            }
        } catch (failure: IOException) { readFailure(failure) }
    }, name).apply { isDaemon = true; start() }
}
