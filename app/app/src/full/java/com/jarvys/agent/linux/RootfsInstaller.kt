package com.jarvys.agent.linux

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import com.jarvys.agent.CancellationToken
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream

enum class LinuxInstallPhase { NOT_INSTALLED, DOWNLOADING, VERIFYING, EXTRACTING, PATCHING, READY, FAILED }

data class LinuxInstallState(
    val phase: LinuxInstallPhase = LinuxInstallPhase.NOT_INSTALLED,
    val bytes: Long = 0,
    val total: Long = LinuxCatalog.DOWNLOAD_BYTES,
    val extractedEntries: Int = 0,
    val lastEntrySeen: String = "",
    val skippedEntries: Int = 0,
    val version: String = "",
    val distro: String = "",
    val versionId: String = "",
    val codename: String = "",
    val arch: String = "",
    val installedAt: Long = 0,
    val sizeBytes: Long = 0,
    val failureCode: String = "",
    val failureDetail: String = "",
    val degradedEntries: Int = 0,
    val hardlinksCopied: Int = 0,
)

interface DownloadSession : AutoCloseable {
    val statusCode: Int
    val rangeStart: Long?
    val input: java.io.InputStream
    override fun close()
}

fun interface DownloadTransport {
    fun open(url: String, offset: Long): DownloadSession
}

class HttpDownloadTransport : DownloadTransport {
    override fun open(url: String, offset: Long): DownloadSession {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        connection.connect()
        val status = connection.responseCode
        val rangeStart = Regex("bytes\\s+(\\d+)-", RegexOption.IGNORE_CASE)
            .find(connection.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            ?: run { connection.disconnect(); throw IOException("HTTP $status downloading rootfs") }
        return object : DownloadSession {
            override val statusCode = status
            override val rangeStart = rangeStart
            override val input = stream
            override fun close() { runCatching { input.close() }; connection.disconnect() }
        }
    }
}

/** Owns resumable download, verification, private staging extraction and rootfs replacement. */
class RootfsInstaller(
    private val paths: Paths,
    private val transport: DownloadTransport = HttpDownloadTransport(),
    private val patcher: RootfsPatcher = RootfsPatcher(),
    private val extractor: RootfsTarExtractor = RootfsTarExtractor(),
    private val availableBytes: (File) -> Long = { StatFs(it.path).availableBytes },
    private val release: LinuxRelease = LinuxCatalog.release,
    private val failureSanitizer: (String) -> String = { LinuxOutputSanitizer.fromFilesDir(paths.filesDir).scrub(it) },
    private val fileKindReader: LinuxFileKindReader = AndroidLinuxFileKindReader,
) {
    data class Paths(val filesDir: File) {
        val linuxDir get() = File(filesDir, "jarvys/linux")
        val rootfs get() = File(linuxDir, "rootfs")
        val staging get() = File(linuxDir, "rootfs.staging")
        val previous get() = File(linuxDir, "rootfs.previous")
        val archivePart get() = File(linuxDir, "ubuntu-base-arm64.tar.gz.part")
        val stateFile get() = File(linuxDir, "install.properties")
        val workspace get() = File(linuxDir, "workspace")
    }

    constructor(context: Context, fileKindReader: LinuxFileKindReader = AndroidLinuxFileKindReader) :
        this(Paths(context.applicationContext.filesDir),
            patcher = RootfsPatcher(context = context.applicationContext),
            failureSanitizer = LinuxOutputSanitizer.from(context.applicationContext)::scrub,
            fileKindReader = fileKindReader)

    private val lock = Any()
    private val observers = CopyOnWriteArrayList<(LinuxInstallState) -> Unit>()
    @Volatile private var current = restoreState()

    init {
        paths.linuxDir.mkdirs()
        paths.workspace.mkdirs()
        if (paths.previous.exists()) {
            if (!paths.rootfs.exists()) paths.previous.renameTo(paths.rootfs) else deleteTree(paths.previous)
        }
        deleteTree(paths.staging)
        if (current.phase in setOf(LinuxInstallPhase.EXTRACTING, LinuxInstallPhase.PATCHING, LinuxInstallPhase.VERIFYING)) {
            val interrupted = IOException("Installation process ended during ${current.phase.name}")
            val detail = LinuxFailureDiagnostics.format(current.phase.name, interrupted,
                current.extractedEntries, failureSanitizer)
            publish(current.copy(phase = LinuxInstallPhase.FAILED, failureCode = "interrupted_extraction",
                failureDetail = detail))
        } else if (paths.archivePart.isFile && paths.archivePart.length() in 1 until release.downloadBytes) {
            publish(current.copy(phase = LinuxInstallPhase.DOWNLOADING, bytes = paths.archivePart.length(),
                total = release.downloadBytes))
        } else if (!paths.rootfs.isDirectory && current.phase == LinuxInstallPhase.READY) {
            publish(LinuxInstallState())
        }
    }

    fun state(): LinuxInstallState = current
    fun observe(observer: (LinuxInstallState) -> Unit): AutoCloseable {
        observers += observer
        observer(current)
        return AutoCloseable { observers -= observer }
    }

    fun install(token: CancellationToken, onProgress: (LinuxInstallState) -> Unit = {},
                retainArchiveForProbe: Boolean = false): LinuxInstallState = synchronized(lock) {
        var phase = LinuxInstallPhase.DOWNLOADING
        try {
            paths.linuxDir.mkdirs()
            paths.workspace.mkdirs()
            publish(LinuxInstallState(LinuxInstallPhase.DOWNLOADING,
                bytes = paths.archivePart.takeIf(File::isFile)?.length() ?: 0L,
                total = release.downloadBytes), onProgress)
            recoverPart()
            val offset = paths.archivePart.takeIf(File::isFile)?.length() ?: 0L
            val required = (release.downloadBytes - offset).coerceAtLeast(0) + release.extractedBytes
            if (availableBytes(paths.linuxDir) < required) throw InstallFailure("insufficient_space", "Not enough free space")
            if (paths.archivePart.length() != release.downloadBytes) download(token, onProgress)
            token.throwIfCancelled()
            phase = LinuxInstallPhase.VERIFYING
            publish(LinuxInstallState(LinuxInstallPhase.VERIFYING, release.downloadBytes, release.downloadBytes), onProgress)
            val actualHash = sha256(paths.archivePart)
            if (!actualHash.equals(release.sha256, ignoreCase = true)) {
                paths.archivePart.delete()
                throw InstallFailure("checksum_mismatch", "Rootfs SHA-256 mismatch")
            }
            deleteTree(paths.staging)
            phase = LinuxInstallPhase.EXTRACTING
            publish(LinuxInstallState(LinuxInstallPhase.EXTRACTING, release.downloadBytes,
                release.downloadBytes), onProgress)
            if (!paths.staging.mkdirs()) throw IOException("Cannot create rootfs staging directory")
            var lastProgressAt = 0L
            val extraction = GZIPInputStream(BufferedInputStream(FileInputStream(paths.archivePart))).use { gzip ->
                extractor.extract(gzip, paths.staging) { progress ->
                    token.throwIfCancelled()
                    current = current.copy(extractedEntries = progress.entries, lastEntrySeen = progress.path)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (lastProgressAt == 0L || now - lastProgressAt >= EXTRACTION_PROGRESS_INTERVAL_MS) {
                        publish(current, onProgress)
                        lastProgressAt = now
                    }
                }
            }
            publish(current.copy(extractedEntries = extraction.entries, skippedEntries = extraction.skippedEntries,
                degradedEntries = extraction.degradedEntries, hardlinksCopied = extraction.hardlinksCopied), onProgress)
            token.throwIfCancelled()
            phase = LinuxInstallPhase.PATCHING
            publish(current.copy(phase = LinuxInstallPhase.PATCHING), onProgress)
            patcher.patch(paths.staging, release.arch)
            val inspection = RootfsInspector.inspect(paths.staging, release.arch)
            token.throwIfCancelled()
            replaceRootfs()
            val installedSize = treeSize(paths.rootfs)
            publish(LinuxInstallState(LinuxInstallPhase.READY, release.downloadBytes,
                release.downloadBytes, extractedEntries = extraction.entries,
                lastEntrySeen = current.lastEntrySeen, skippedEntries = extraction.skippedEntries,
                degradedEntries = extraction.degradedEntries, hardlinksCopied = extraction.hardlinksCopied,
                version = release.version, distro = inspection.distro, versionId = inspection.versionId,
                codename = inspection.codename, arch = release.arch,
                installedAt = System.currentTimeMillis(), sizeBytes = installedSize), onProgress)
            if (!retainArchiveForProbe) paths.archivePart.delete()
            current
        } catch (failure: Throwable) {
            deleteTree(paths.staging)
            if (failure is InstallFailure && failure.code == "checksum_mismatch") paths.archivePart.delete()
            val failurePhase = when {
                failure is InstallFailure && failure.code == "insufficient_space" -> LinuxInstallPhase.DOWNLOADING
                token.isCancelled -> phase
                else -> phase
            }
            val code = when {
                token.isCancelled -> "cancelled"
                failure is InstallFailure -> failure.code
                failure is CancellationException -> "cancelled"
                failure is SecurityException -> "permission_denied"
                failure.message.orEmpty().contains("rootfs architecture does not match", true) -> "rootfs_architecture_mismatch"
                else -> when (failurePhase) {
                    LinuxInstallPhase.DOWNLOADING -> "download_failed"
                    LinuxInstallPhase.VERIFYING -> "checksum_mismatch"
                    LinuxInstallPhase.EXTRACTING -> "extract_failed"
                    LinuxInstallPhase.PATCHING -> "patch_failed"
                    else -> "install_failed"
                }
            }
            val extractionFailure = generateSequence(failure) { it.cause }.filterIsInstance<LinuxTarEntryFailure>().firstOrNull()
            val entriesProcessed = extractionFailure?.entry?.entriesProcessed ?: current.extractedEntries
            val detail = LinuxFailureDiagnostics.format(failurePhase.name, failure,
                entriesProcessed, failureSanitizer)
            publish(LinuxInstallState(LinuxInstallPhase.FAILED, bytes = paths.archivePart.length(),
                total = release.downloadBytes, extractedEntries = entriesProcessed,
                lastEntrySeen = extractionFailure?.entry?.path ?: current.lastEntrySeen,
                failureCode = code, failureDetail = detail,
                degradedEntries = extractionFailure?.degradedEntries ?: current.degradedEntries,
                hardlinksCopied = extractionFailure?.hardlinksCopied ?: current.hardlinksCopied), onProgress)
            current
        }
    }

    fun uninstall() = synchronized(lock) {
        deleteTree(paths.rootfs)
        deleteTree(paths.staging)
        deleteTree(paths.previous)
        paths.archivePart.delete()
        // Workspace is user data: explicit removal is a separate future operation.
        publish(LinuxInstallState())
    }

    fun availableBytes(): Long {
        paths.linuxDir.mkdirs()
        return availableBytes(paths.linuxDir)
    }

    fun markProbeFailure(code: String, detail: String) = synchronized(lock) {
        if (!paths.rootfs.isDirectory) return@synchronized
        publish(current.copy(phase = LinuxInstallPhase.FAILED, failureCode = "probe_${code.ifBlank { "failed" }}",
            failureDetail = failureSanitizer(detail)))
    }

    fun markProbeSuccess() = synchronized(lock) {
        if (!paths.rootfs.isDirectory) return@synchronized
        val ready = current.copy(phase = LinuxInstallPhase.READY, failureCode = "", failureDetail = "",
            version = release.version, arch = release.arch,
            sizeBytes = current.sizeBytes.takeIf { it > 0 } ?: treeSize(paths.rootfs))
        publish(ready)
        paths.archivePart.delete()
    }

    fun deleteWorkspace() = synchronized(lock) { deleteTree(paths.workspace) }

    private fun download(token: CancellationToken, onProgress: (LinuxInstallState) -> Unit) {
        var offset = paths.archivePart.takeIf(File::isFile)?.length() ?: 0L
        var session = transport.open(release.url, offset)
        if (offset > 0 && (session.statusCode != 206 || session.rangeStart != offset)) {
            session.close()
            paths.archivePart.delete()
            offset = 0
            session = transport.open(release.url, 0)
        }
        if (session.statusCode != if (offset > 0) 206 else 200) {
            session.close()
            throw IOException("HTTP ${session.statusCode} downloading rootfs")
        }
        val active = session
        val unregister = token.registerCancelAction { active.close() }
        try {
            FileOutputStream(paths.archivePart, offset > 0).use { output ->
                active.input.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = offset
                    while (true) {
                        token.throwIfCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        bytes += count
                        publish(LinuxInstallState(LinuxInstallPhase.DOWNLOADING, bytes, release.downloadBytes), onProgress)
                    }
                    output.fd.sync()
                }
            }
            if (paths.archivePart.length() != release.downloadBytes)
                throw IOException("Incomplete rootfs download ${paths.archivePart.length()}/${release.downloadBytes}")
        } finally {
            unregister.run()
            active.close()
        }
    }

    private fun recoverPart() {
        if (!paths.archivePart.isFile) return
        if (paths.archivePart.length() > release.downloadBytes) paths.archivePart.delete()
        else if (paths.archivePart.length() == release.downloadBytes &&
            !sha256(paths.archivePart).equals(release.sha256, true)) paths.archivePart.delete()
    }

    private fun replaceRootfs() {
        deleteTree(paths.previous)
        val hadRoot = paths.rootfs.exists()
        if (hadRoot && !paths.rootfs.renameTo(paths.previous)) throw IOException("Cannot stage previous rootfs")
        if (!paths.staging.renameTo(paths.rootfs)) {
            if (hadRoot) paths.previous.renameTo(paths.rootfs)
            throw IOException("Cannot atomically install rootfs")
        }
        deleteTree(paths.previous)
    }

    private fun publish(state: LinuxInstallState, callback: (LinuxInstallState) -> Unit = {}) {
        current = state
        val props = Properties().apply {
            setProperty("phase", state.phase.name); setProperty("bytes", state.bytes.toString())
            setProperty("total", state.total.toString()); setProperty("entries", state.extractedEntries.toString())
            setProperty("lastEntrySeen", state.lastEntrySeen); setProperty("skippedEntries", state.skippedEntries.toString())
            setProperty("version", state.version); setProperty("arch", state.arch)
            setProperty("distro", state.distro); setProperty("versionId", state.versionId); setProperty("codename", state.codename)
            setProperty("installedAt", state.installedAt.toString()); setProperty("sizeBytes", state.sizeBytes.toString())
            setProperty("failureCode", state.failureCode)
            setProperty("failureDetail", state.failureDetail)
            setProperty("degradedEntries", state.degradedEntries.toString())
            setProperty("hardlinksCopied", state.hardlinksCopied.toString())
        }
        val temp = File(paths.linuxDir, "install.properties.tmp")
        FileOutputStream(temp).use { props.store(it, "Jarvys private Linux state") }
        if (!temp.renameTo(paths.stateFile)) {
            paths.stateFile.delete()
            if (!temp.renameTo(paths.stateFile)) throw IOException("Cannot persist Linux install state")
        }
        callback(state)
        observers.forEach { observer -> runCatching { observer(state) } }
    }

    private fun restoreState(): LinuxInstallState {
        if (!paths.stateFile.isFile) return LinuxInstallState()
        return runCatching {
            val props = Properties().apply { FileInputStream(paths.stateFile).use(::load) }
            LinuxInstallState(
                phase = LinuxInstallPhase.valueOf(props.getProperty("phase", "NOT_INSTALLED")),
                bytes = props.getProperty("bytes", "0").toLong(), total = props.getProperty("total", release.downloadBytes.toString()).toLong(),
                extractedEntries = props.getProperty("entries", "0").toInt(), version = props.getProperty("version", ""),
                distro = props.getProperty("distro", ""), versionId = props.getProperty("versionId", ""),
                codename = props.getProperty("codename", ""),
                lastEntrySeen = props.getProperty("lastEntrySeen", ""),
                skippedEntries = props.getProperty("skippedEntries", "0").toInt(),
                arch = props.getProperty("arch", ""), installedAt = props.getProperty("installedAt", "0").toLong(),
                sizeBytes = props.getProperty("sizeBytes", "0").toLong(), failureCode = props.getProperty("failureCode", ""),
                failureDetail = props.getProperty("failureDetail", ""),
                degradedEntries = props.getProperty("degradedEntries", "0").toInt(),
                hardlinksCopied = props.getProperty("hardlinksCopied", "0").toInt(),
            )
        }.getOrDefault(LinuxInstallState())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun treeSize(file: File): Long {
        val mode = LinuxFileModes.mode(file) ?: return 0
        if (mode == OsConstants.S_IFLNK) return 0
        if (mode != OsConstants.S_IFDIR) return file.length()
        return file.listFiles()?.sumOf(::treeSize) ?: 0
    }

    private fun deleteTree(file: File) = deletePrivateTree(file, fileKindReader)

    companion object {
        private const val EXTRACTION_PROGRESS_INTERVAL_MS = 200L
        private data class DeleteFrame(val file: File, val afterChildren: Boolean, val isRoot: Boolean)

        /** Iterative post-order unlink. Symlinks and unknown kinds are always unlinked, never traversed. */
        internal fun deletePrivateTree(
            file: File,
            fileKindReader: LinuxFileKindReader = AndroidLinuxFileKindReader,
        ) {
            if (fileKindReader.kind(file) != LinuxFileKind.DIRECTORY) {
                file.delete()
                return
            }
            val canonicalRoot = runCatching { canonicalEntry(file) }.getOrNull()
            if (canonicalRoot == null) {
                file.delete()
                return
            }
            val resolvedRoot = runCatching { file.canonicalFile }.getOrNull()
            if (resolvedRoot == null || resolvedRoot.path != canonicalRoot.path) {
                // A bad lstat implementation may report a root symlink as a directory.
                file.delete()
                return
            }
            val rootPath = canonicalRoot.path
            val visitedDirectories = hashSetOf(rootPath)
            val stack = java.util.ArrayDeque<DeleteFrame>()
            stack.addLast(DeleteFrame(file, afterChildren = false, isRoot = true))
            while (stack.isNotEmpty()) {
                val frame = stack.removeLast()
                if (frame.afterChildren) {
                    frame.file.delete()
                    continue
                }
                when (fileKindReader.kind(frame.file)) {
                    LinuxFileKind.DIRECTORY -> {
                        val lexical = runCatching { canonicalEntry(frame.file) }.getOrNull()
                        val canonical = runCatching { frame.file.canonicalFile }.getOrNull()
                        val insideRoot = canonical != null && lexical != null && canonical.path == lexical.path &&
                            (if (frame.isRoot) canonical.path == rootPath
                            else canonical.path.startsWith(rootPath + File.separator))
                        if (!insideRoot || canonical == null || (!frame.isRoot && !visitedDirectories.add(canonical.path))) {
                            // Also handles an lstat implementation that misclassifies an outside symlink.
                            frame.file.delete()
                            continue
                        }
                        val children = frame.file.listFiles()
                        if (children == null) {
                            frame.file.delete()
                            continue
                        }
                        stack.addLast(frame.copy(afterChildren = true))
                        children.forEach { child -> stack.addLast(DeleteFrame(child, afterChildren = false, isRoot = false)) }
                    }
                    LinuxFileKind.SYMLINK, LinuxFileKind.OTHER, LinuxFileKind.MISSING, LinuxFileKind.UNKNOWN ->
                        frame.file.delete()
                }
            }
        }

        /** Canonicalizes parents but intentionally does not resolve the final entry itself. */
        private fun canonicalEntry(file: File): File {
            val absolute = file.absoluteFile
            val parent = absolute.parentFile?.canonicalFile
            return if (parent == null) absolute else File(parent, absolute.name)
        }
    }

    private class InstallFailure(val code: String, message: String) : IOException(message)

}

/** Filesystem-only, repeatable post-extraction setup. */
class RootfsPatcher(
    private val dnsFallback: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    private val supplementalGroups: () -> IntArray = ::androidSupplementalGroups,
    private val androidDnsServers: (() -> List<String>)? = null,
    private val trustManagers: () -> Array<javax.net.ssl.TrustManager> = ::androidTrustManagers,
    private val chmod: (File, Int) -> Unit = { file, mode -> Os.chmod(file.absolutePath, mode) },
    private val context: Context? = null,
    private val fileKindReader: LinuxFileKindReader = AndroidLinuxFileKindReader,
    private val aptMirror: String? = null,
) {
    fun patch(rootfs: File, architecture: String = "arm64") {
        val root = rootfs.canonicalFile
        val etc = safe(root, "etc")
        mkdirGuest(root, etc, 0x1ED)
        val resolv = safe(root, "etc/resolv.conf")
        val resolvMode = entryMode(resolv)
        if (resolvMode == OsConstants.S_IFREG) RootfsCertificates.ensureContained(root, resolv)
        val localOnly = resolvMode == OsConstants.S_IFREG && resolv.readLines()
            .filter { it.trim().startsWith("nameserver ") }
            .all { it.substringAfter("nameserver ").trim() in LOCAL_DNS }
        if (resolvMode == OsConstants.S_IFLNK || resolvMode != OsConstants.S_IFREG || localOnly) {
            if (resolvMode != null && resolvMode != OsConstants.S_IFLNK && !resolv.delete())
                throw IOException("Cannot replace guest etc/resolv.conf")
            val servers = (androidDnsServers?.invoke() ?: contextDnsServers()).filter(String::isNotBlank).ifEmpty { dnsFallback }
            writeChecked(root, resolv, (servers.map { "nameserver $it" } + "options edns0 trust-ad").joinToString("\n", postfix = "\n"),
                replaceSymlink = true)
        } else if (!resolv.readText().lineSequence().any { it.trim() == "options edns0 trust-ad" }) {
            val old = resolv.readText()
            writeChecked(root, resolv, (if (old.endsWith('\n')) old else "$old\n") + "options edns0 trust-ad\n")
        }

        val hostnameFile = safe(root, "etc/hostname")
        val hostname = readGuestText(root, hostnameFile).trim().ifEmpty { "localhost" }
        writeChecked(root, hostnameFile, "$hostname\n")
        patchHosts(root, safe(root, "etc/hosts"), hostname)

        val locale = safe(root, "etc/default/locale")
        mkdirGuest(root, safe(root, "etc/default"), 0x1ED)
        val localeText = readGuestText(root, locale)
        if (localeText.lineSequence().none { it.trimStart().startsWith("LANG=") })
            writeChecked(root, locale, localeText.appendLines(listOf("LANG=C.UTF-8")))

        mkdirGuest(root, safe(root, "tmp"), 0x3FF)
        mkdirGuest(root, safe(root, "var/tmp"), 0x3FF)
        mkdirGuest(root, safe(root, "root"), 0x1C0)

        val aptConfig = safe(root, "etc/apt/apt.conf.d")
        mkdirGuest(root, aptConfig, 0x1ED)
        writeChecked(root, File(aptConfig, "99jarvys-root-sandbox"), "APT::Sandbox::User \"root\";\n")
        val sourcesDir = safe(root, "etc/apt/sources.list.d")
        mkdirGuest(root, sourcesDir, 0x1ED)
        val sourceList = safe(root, "etc/apt/sources.list.d/ubuntu.sources")
        val backup = safe(root, "etc/apt/sources.list.d/ubuntu.sources.bak")
        val repository = if (architecture.lowercase() in setOf("amd64", "x86_64")) "ubuntu" else "ubuntu-ports"
        val mirror = (aptMirror ?: if (repository == "ubuntu") "https://archive.ubuntu.com" else "https://ports.ubuntu.com")
            .also { require(it.startsWith("https://")) { "Ubuntu APT mirror must use HTTPS" } }.trimEnd('/')
        val sources = """Types: deb
URIs: $mirror/$repository/
Suites: noble noble-updates noble-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: $mirror/$repository/
Suites: noble-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
""".trimIndent() + "\n"
        val oldSources = readGuestText(root, sourceList)
        if (entryMode(sourceList) == OsConstants.S_IFREG && oldSources != sources && !backup.exists())
            writeChecked(root, backup, oldSources)
        writeChecked(root, sourceList, sources)
        patchGroups(root, safe(root, "etc/group"), supplementalGroups())
        RootfsCertificates.ensure(root, trustManagers, fileKindReader)
    }

    private fun patchGroups(root: File, file: File, gids: IntArray) {
        val existingText = readGuestText(root, file)
        val lines = if (existingText.isNotEmpty()) existingText.split('\n').toMutableList().apply {
            if (lastOrNull().isNullOrEmpty()) removeAt(lastIndex)
        } else mutableListOf("root:x:0:")
        val existing = lines.mapNotNull { it.split(':').getOrNull(2)?.toIntOrNull() }.toSet()
        val names = lines.mapNotNull { it.substringBefore(':').takeIf(String::isNotBlank) }.toMutableSet()
        var changed = false
        for (gid in gids.distinct().sorted()) if (gid > 0 && gid !in existing) {
            val base = "android_gid_$gid"
            var name = base
            while (name in names) name += "_workspace"
            lines += "$name:x:$gid:"
            names.add(name)
            changed = true
        }
        if (changed || !file.isFile) writeChecked(root, file, lines.joinToString("\n", postfix = "\n"))
    }

    private fun patchHosts(root: File, file: File, hostname: String) {
        val original = readGuestText(root, file)
        val lines = if (original.isEmpty()) mutableListOf() else original.split('\n').toMutableList().apply {
            if (lastOrNull().isNullOrEmpty()) removeAt(lastIndex)
        }
        ensureHostsLine(lines, "127.0.0.1", listOf("localhost", hostname))
        ensureHostsLine(lines, "::1", listOf("localhost", "ip6-localhost", "ip6-loopback"))
        writeChecked(root, file, lines.joinToString("\n", postfix = "\n"))
    }

    private fun ensureHostsLine(lines: MutableList<String>, address: String, aliases: List<String>) {
        val index = lines.indexOfFirst { it.substringBefore('#').trim().split(Regex("\\s+")).firstOrNull() == address }
        val uniqueAliases = aliases.distinct()
        if (index < 0) {
            lines += "$address ${uniqueAliases.joinToString(" ")}"
            return
        }
        val data = lines[index].substringBefore('#').trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val missing = uniqueAliases.filterNot(data::contains)
        if (missing.isNotEmpty()) lines[index] = lines[index].substringBefore('#').trimEnd() + " " + missing.joinToString(" ") +
            lines[index].substringAfter('#', "").let { if (it.isEmpty()) "" else " #$it" }
    }

    private fun mkdirGuest(root: File, directory: File, mode: Int) {
        RootfsCertificates.ensureContained(root, directory)
        if (entryMode(directory) == OsConstants.S_IFLNK) throw IOException("Guest directory path is a symlink: ${directory.path}")
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory)
            throw IOException("Cannot create guest directory ${directory.path}")
        RootfsCertificates.ensureContained(root, directory)
        chmod(directory, mode)
    }

    private fun safe(root: File, relative: String): File = RootfsCertificates.checked(root, relative)

    private fun entryMode(file: File): Int? = if (fileKindReader.kind(file) == LinuxFileKind.SYMLINK)
        OsConstants.S_IFLNK else LinuxFileModes.mode(file)

    private fun writeChecked(root: File, file: File, text: String, replaceSymlink: Boolean = false) {
        val parent = file.parentFile ?: throw IOException("Guest path has no parent: ${file.path}")
        RootfsCertificates.ensureContained(root, parent)
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create guest parent ${parent.path}")
        RootfsCertificates.ensureContained(root, parent)
        val fileMode = entryMode(file)
        if (fileMode == OsConstants.S_IFLNK) {
            if (!replaceSymlink) RootfsCertificates.ensureContained(root, file)
            if (!file.delete()) throw IOException("Cannot unlink guest symlink ${file.path}")
        } else RootfsCertificates.ensureContained(root, file)
        if (fileMode == OsConstants.S_IFREG) {
            if (file.readText() == text) return
        }
        val temp = File(parent, file.name + ".jarvys-tmp")
        if (entryMode(temp) == OsConstants.S_IFLNK) throw IOException("Guest patch temporary path is a symlink")
        val descriptor = Os.open(temp.absolutePath,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_NOFOLLOW,
            0x180 /* 0600 */)
        FileOutputStream(descriptor).use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync() }
        Os.chmod(temp.absolutePath, 0x1A4 /* 0644 */)
        RootfsCertificates.ensureContained(root, temp)
        try {
            if (!temp.renameTo(file)) throw IOException("Cannot atomically patch guest file ${file.path}")
            RootfsCertificates.ensureContained(root, file)
        } finally {
            temp.delete()
        }
    }

    private fun readGuestText(root: File, file: File): String {
        if (entryMode(file) != OsConstants.S_IFREG) return ""
        RootfsCertificates.ensureContained(root, file)
        return file.readText()
    }

    private fun String.appendLines(additions: List<String>): String {
        val prefix = if (isEmpty() || endsWith('\n')) this else "$this\n"
        return prefix + additions.joinToString("\n", postfix = "\n")
    }

    companion object {
        private val LOCAL_DNS = setOf("127.0.0.1", "127.0.0.53", "::1")

        private fun androidTrustManagers(): Array<javax.net.ssl.TrustManager> {
            val factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as java.security.KeyStore?)
            return factory.trustManagers
        }

        private fun contextDnsServers(context: Context? = null): List<String> = try {
            val connectivity = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return emptyList()
            val network = connectivity.activeNetwork ?: return emptyList()
            connectivity.getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
        } catch (_: SecurityException) { emptyList() }

        private fun androidSupplementalGroups(): IntArray = runCatching {
            // android.system.Os exposes no getgroups wrapper at minSdk 24; procfs is the supported fallback.
            File("/proc/self/status").readLines().first { it.startsWith("Groups:") }
                .substringAfter(':').trim().split(Regex("\\s+")).filter(String::isNotEmpty).map(String::toInt).toIntArray()
        }.getOrDefault(intArrayOf())
    }

    private fun contextDnsServers(): List<String> = Companion.contextDnsServers(context)
}
