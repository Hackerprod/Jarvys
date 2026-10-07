package com.jarvys.agent.linux

import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.math.BigInteger

/** Small ustar/PAX/GNU reader. No archive path is ever resolved through an existing symlink. */
open class RootfsTarExtractor(
    private val ignoredEntry: (String, Char) -> Unit = { _, _ -> },
    private val fileOps: RootfsTarFileOps = AndroidRootfsTarFileOps,
) {
    data class Progress(val entries: Int, val path: String)
    data class Report(val entries: Int, val degradedEntries: Int, val hardlinksCopied: Int, val skippedEntries: Int = 0)
    private data class PendingDirectory(val file: File, val mode: Int, val path: String, val entriesProcessed: Int, val mtime: Long?)
    private data class ExtractionStats(
        var degradedEntries: Int = 0,
        var hardlinksCopied: Int = 0,
        var skippedEntries: Int = 0,
        val fileModes: MutableMap<String, Int> = mutableMapOf(),
    )

    open fun extract(gzipTar: InputStream, destination: File, onProgress: (Progress) -> Unit = {}): Report {
        if (!destination.exists() && !destination.mkdirs()) throw IOException("Cannot create staging root ${destination.path}")
        val root = destination.canonicalFile
        val globalPax = linkedMapOf<String, String>()
        var localPax = linkedMapOf<String, String>()
        var longName: String? = null
        var longLink: String? = null
        var metadataDepth = 0
        val directories = mutableListOf<PendingDirectory>()
        val stats = ExtractionStats()
        var count = 0
        while (true) {
            val header = readBlock(gzipTar) ?: throw IOException("Truncated tar: missing end markers")
            if (header.all { it == 0.toByte() }) {
                val end = readBlock(gzipTar) ?: throw IOException("Truncated tar: missing second end marker")
                if (!end.all { it == 0.toByte() }) throw IOException("Invalid tar end marker")
                applyDirectoryModes(directories, stats, count)
                return Report(count, stats.degradedEntries, stats.hardlinksCopied, stats.skippedEntries)
            }
            val name0 = field(header, 0, 100)
            val prefix = field(header, 345, 155)
            val name = longName ?: listOf(prefix, name0).filter(String::isNotEmpty).joinToString("/")
            val link = longLink ?: field(header, 157, 100)
            val type = header[156].toInt().toChar()
            val baseSize = parseTarNumber(header, 124, 12)
            val baseMtime = parseTarNumber(header, 136, 12)
            val metadata = merged(globalPax, localPax)
            val entryName = metadata["path"] ?: name
            val entryLink = metadata["linkpath"] ?: link
            val size = metadata["size"]?.let { it.toLongOrNull() ?: throw IOException("Invalid PAX size: $it") } ?: baseSize
            val paxMtime = metadata["mtime"]?.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?.let { runCatching { (it * 1000).toLong() }.getOrNull() }
            val mtime = paxMtime ?: runCatching { Math.multiplyExact(baseMtime, 1000L) }.getOrNull()
            if (size < 0) throw IOException("Invalid negative tar size for $entryName")
            if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                metadataDepth++
                if (metadataDepth > MAX_METADATA_NESTING) throw IOException("Nested tar metadata exceeds format guard")
                val data = readPayload(gzipTar, size)
                when (type) {
                    'x' -> localPax = parsePax(data)
                    'g' -> globalPax.putAll(parsePax(data))
                    'L' -> longName = decodeName(data)
                    'K' -> longLink = decodeName(data)
                }
                skipPadding(gzipTar, size)
                continue
            }
            longName = null
            longLink = null
            localPax = linkedMapOf()
            metadataDepth = 0
            val mode = parseTarNumber(header, 100, 8).toInt() and 0xFFF
            val context = LinuxTarEntryContext(entryName, type, mode, count)
            val normalizedEntry = try { normalizedName(entryName) } catch (failure: Throwable) {
                throw LinuxTarEntryFailure(context, stats.degradedEntries, stats.hardlinksCopied, failure)
            }
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Extraction interrupted")
            onProgress(Progress(count, normalizedEntry.ifEmpty { "." }))
            try {
                if (normalizedEntry.isEmpty()) {
                    if (type != '5') throw IOException("Tar root entry must be a directory")
                    skipPayload(gzipTar, size)
                    count++
                    onProgress(Progress(count, "."))
                    skipPadding(gzipTar, size)
                    continue
                }
                when (type) {
                    '\u0000', '0', '7' -> writeRegular(root, entryName, size, mode, mtime, gzipTar, context, stats)
                    '5' -> {
                        val dir = destinationFor(root, entryName)
                        secureParents(root, dir, includeLeaf = false)
                        val kind = modeOf(dir)
                        if (kind != null && kind != OsConstants.S_IFDIR)
                            throw IOException("Archive directory path is not a directory: $entryName (mode=$kind, expected=${OsConstants.S_IFDIR})")
                        if (!dir.exists() && !dir.mkdir()) throw IOException("Cannot create directory $entryName")
                        directories += PendingDirectory(dir, mode, entryName, count + 1, mtime)
                        skipPayload(gzipTar, size)
                    }
                    '2' -> {
                        val path = destinationFor(root, entryName)
                        secureParents(root, path, includeLeaf = false)
                        val target = symlinkTarget(root, path, entryLink)
                        removeExistingLeaf(path)
                        fileOps.symlink(target, path)
                        skipPayload(gzipTar, size)
                    }
                    '1' -> {
                        val path = destinationFor(root, entryName)
                        val source = destinationFor(root, entryLink)
                        secureParents(root, path, includeLeaf = false)
                        secureParents(root, source, includeLeaf = true)
                        val sourceMode = modeOf(source)
                        if (sourceMode != OsConstants.S_IFREG)
                            throw IOException("Hardlink target is not a regular in-root file: $entryLink")
                        val relativeSource = source.relativeTo(root).invariantSeparatorsPath
                        val permissions = stats.fileModes[relativeSource]
                            ?: LinuxFileModes.permissions(source) ?: mode
                        removeExistingLeaf(path)
                        copyRegular(source, path, permissions, context, stats)
                        applyMtime(path, mtime)
                        stats.hardlinksCopied++
                        stats.fileModes[path.relativeTo(root).invariantSeparatorsPath] = permissions
                        skipPayload(gzipTar, size)
                    }
                    else -> {
                        // Character/block devices, sockets and FIFOs cannot be represented safely here.
                        ignoredEntry(entryName, type)
                        stats.skippedEntries++
                        skipPayload(gzipTar, size)
                    }
                }
                count++
                onProgress(Progress(count, normalizedEntry))
                skipPadding(gzipTar, size)
            } catch (failure: Throwable) {
                if (failure is LinuxTarEntryFailure) throw failure
                throw LinuxTarEntryFailure(context.copy(entriesProcessed = count),
                    stats.degradedEntries, stats.hardlinksCopied, failure)
            }
        }
    }

    private fun writeRegular(root: File, name: String, size: Long, mode: Int, mtime: Long?, input: InputStream,
                             context: LinuxTarEntryContext, stats: ExtractionStats) {
        val file = destinationFor(root, name)
        secureParents(root, file, includeLeaf = false)
        removeExistingLeaf(file)
        try {
            fileOps.openNewRegular(file).use { output ->
                var remaining = size
                val buffer = ByteArray(32 * 1024)
                while (remaining > 0) {
                    val amount = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (amount < 0) throw IOException("Truncated tar payload for $name: ${size - remaining}/$size bytes")
                    if (amount == 0) continue
                    output.write(buffer, 0, amount)
                    remaining -= amount
                }
                if (output is FileOutputStream) output.fd.sync()
            }
            chmod(file, mode, context, stats)
            applyMtime(file, mtime)
            stats.fileModes[file.relativeTo(root).invariantSeparatorsPath] = mode
        } catch (failure: Throwable) {
            throw failure
        }
    }

    private fun copyRegular(source: File, destination: File, mode: Int,
                            context: LinuxTarEntryContext, stats: ExtractionStats) {
        fileOps.openNewRegular(destination).use { output ->
            FileInputStream(source).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) output.write(buffer, 0, count)
                }
            }
            if (output is FileOutputStream) output.fd.sync()
        }
        chmod(destination, mode, context, stats)
    }

    private fun chmod(file: File, mode: Int, context: LinuxTarEntryContext, stats: ExtractionStats) {
        try {
            fileOps.chmod(file, mode)
        } catch (failure: Throwable) {
            val errno = errnoException(failure)?.errno
            val specialBits = OsConstants.S_ISUID or OsConstants.S_ISGID or OsConstants.S_ISVTX
            if ((errno != OsConstants.EPERM && errno != OsConstants.EACCES) || (mode and specialBits) == 0) throw failure
            val ordinaryMode = mode and specialBits.inv()
            try {
                fileOps.chmod(file, ordinaryMode)
                stats.degradedEntries++
            } catch (retryFailure: Throwable) {
                throw IOException("chmod special bits denied; retry without special bits failed for ${context.path}", retryFailure)
            }
        }
    }

    private fun applyDirectoryModes(entries: List<PendingDirectory>, stats: ExtractionStats, totalEntries: Int) {
        for (directory in entries.asReversed()) {
            val context = LinuxTarEntryContext(directory.path, '5', directory.mode, totalEntries)
            try {
                chmod(directory.file, directory.mode, context, stats)
                applyMtime(directory.file, directory.mtime)
            } catch (failure: Throwable) {
                if (failure is LinuxTarEntryFailure) throw failure
                throw LinuxTarEntryFailure(context, stats.degradedEntries, stats.hardlinksCopied, failure)
            }
        }
    }

    private fun applyMtime(file: File, mtime: Long?) {
        if (mtime != null && mtime >= 0) runCatching { file.setLastModified(mtime) }
    }

    private fun errnoException(failure: Throwable): ErrnoException? {
        var current: Throwable? = failure
        val seen = hashSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is ErrnoException) return current
            current = current.cause
        }
        return null
    }

    private fun destinationFor(root: File, rawName: String): File {
        val cleaned = normalizedName(rawName)
        if (cleaned.isEmpty()) return root
        val result = File(root, cleaned).absoluteFile
        if (!result.path.startsWith(root.path + File.separator)) throw IOException("Tar path escapes root: $rawName")
        return result
    }

    private fun normalizedName(rawName: String): String {
        if (rawName.indexOf('\u0000') >= 0) throw IOException("NUL in tar path")
        if (rawName.startsWith('/') || rawName.startsWith('\\')) throw IOException("Absolute tar path: $rawName")
        val pieces = rawName.replace('\\', '/').split('/')
        if (pieces.any { it == ".." }) throw IOException("Parent traversal in tar path: $rawName")
        return pieces.filter { it.isNotEmpty() && it != "." }.joinToString(File.separator)
    }

    /** Absolute symlink targets are guest-root-relative; relative targets must normalize inside root. */
    private fun symlinkTarget(root: File, link: File, rawTarget: String): String {
        if (rawTarget.isBlank() || rawTarget.indexOf('\u0000') >= 0) throw IOException("Invalid symlink target")
        if (rawTarget.startsWith('/')) return rawTarget
        val parent = link.parentFile.absolutePath.removePrefix(root.absolutePath).trim(File.separatorChar)
        val components = mutableListOf<String>()
        if (!rawTarget.startsWith('/')) components += parent.split(File.separator).filter(String::isNotEmpty)
        for (part in rawTarget.removePrefix("/").split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (components.isEmpty()) throw IOException("Symlink target escapes root: $rawTarget") else components.removeAt(components.lastIndex)
            else -> components += part
        }
        return rawTarget
    }

    private fun secureParents(root: File, leaf: File, includeLeaf: Boolean) {
        val relative = leaf.relativeTo(root).path
        val segments = relative.split(File.separator).filter(String::isNotEmpty)
        val limit = if (includeLeaf) segments.size else (segments.size - 1).coerceAtLeast(0)
        var current = root
        for (index in 0 until limit) {
            current = File(current, segments[index])
            if (includeLeaf && index == segments.lastIndex) {
                if (modeOf(current) == OsConstants.S_IFLNK) throw IOException("Archive link target is a symlink: ${current.path}")
                continue
            }
            when (modeOf(current)) {
                null -> if (!current.mkdirs() && !current.isDirectory) throw IOException("Cannot create parent ${current.path}")
                OsConstants.S_IFDIR -> Unit
                OsConstants.S_IFLNK -> throw IOException("Refusing to follow archive symlink while writing ${leaf.path}")
                else -> throw IOException("Archive parent is not a directory: ${current.path}")
            }
        }
        if (leaf != root && !leaf.path.startsWith(root.path + File.separator)) throw IOException("Destination escaped root")
    }

    private fun modeOf(file: File): Int? = LinuxFileModes.mode(file)

    private fun removeExistingLeaf(file: File) {
        if (modeOf(file) == null) return
        if (!file.delete()) throw IOException("Cannot replace archive path ${file.path}")
    }

    private fun removeNonDirectory(file: File) = removeExistingLeaf(file)

    private fun readBlock(input: InputStream): ByteArray? {
        val bytes = ByteArray(BLOCK)
        var offset = 0
        while (offset < BLOCK) {
            val count = input.read(bytes, offset, BLOCK - offset)
            if (count < 0) {
                if (offset == 0) return null
                throw IOException("Truncated tar header")
            }
            if (count > 0) offset += count
        }
        return bytes
    }

    private fun readPayload(input: InputStream, size: Long): ByteArray {
        if (size > Int.MAX_VALUE) throw IOException("Tar metadata record is too large")
        val bytes = ByteArray(size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw IOException("Truncated tar metadata")
            if (count > 0) offset += count
        }
        return bytes
    }

    private fun skipPayload(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val amount = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (amount < 0) throw IOException("Truncated tar payload")
            if (amount > 0) remaining -= amount
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = ((BLOCK - size % BLOCK) % BLOCK).toInt()
        var remaining = padding
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong()).toInt()
            if (skipped > 0) remaining -= skipped
            else if (input.read() < 0) throw IOException("Truncated tar padding") else remaining--
        }
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, StandardCharsets.UTF_8)
    }

    private fun parseTarNumber(header: ByteArray, offset: Int, length: Int): Long {
        if ((header[offset].toInt() and 0x80) != 0) {
            val encoded = header.copyOfRange(offset, offset + length)
            val negative = (encoded[0].toInt() and 0x40) != 0
            encoded[0] = (encoded[0].toInt() and 0x7f).toByte()
            var number = BigInteger(1, encoded)
            if (negative) number = number.subtract(BigInteger.ONE.shiftLeft(length * 8 - 1))
            return try { number.longValueExact() } catch (_: ArithmeticException) { throw IOException("Tar numeric field is out of range") }
        }
        val raw = String(header, offset, length, StandardCharsets.US_ASCII).trim('\u0000', ' ', '\t')
        if (raw.isEmpty()) return 0
        return raw.toLongOrNull(8) ?: throw IOException("Invalid tar octal field: $raw")
    }

    private fun decodeName(bytes: ByteArray): String {
        if (bytes.contains(0.toByte()) && bytes.dropLastWhile { it == 0.toByte() }.any { it == 0.toByte() })
            throw IOException("NUL in GNU tar name")
        return String(bytes, StandardCharsets.UTF_8).trimEnd('\u0000', '\n')
    }

    private fun parsePax(bytes: ByteArray): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        var offset = 0
        while (offset < bytes.size) {
            var space = offset
            while (space < bytes.size && bytes[space] != ' '.code.toByte()) space++
            if (space >= bytes.size) throw IOException("Malformed PAX record length")
            val length = String(bytes, offset, space - offset, StandardCharsets.US_ASCII).toIntOrNull()
                ?: throw IOException("Malformed PAX record length")
            if (length <= space - offset + 1 || offset + length > bytes.size) throw IOException("Malformed PAX record")
            val record = String(bytes, space + 1, offset + length - space - 2, StandardCharsets.UTF_8)
            if (record.indexOf('\u0000') >= 0) throw IOException("NUL in PAX record")
            val equals = record.indexOf('=')
            if (equals <= 0) throw IOException("Malformed PAX key/value")
            val key = record.substring(0, equals)
            if (key == "path" || key == "linkpath" || key == "size" || key == "mtime") result[key] = record.substring(equals + 1)
            offset += length
        }
        return result
    }

    private fun merged(global: Map<String, String>, local: Map<String, String>) = global + local

    companion object {
        private const val BLOCK = 512
        private const val MAX_METADATA_NESTING = 8
    }
}

/** lstat is authoritative on Android; fallback keeps symlink checks verifiable on Robolectric. */
internal object LinuxFileModes {
    fun mode(file: File): Int? {
        val native = runCatching { Os.lstat(file.absolutePath).st_mode.toInt() and OsConstants.S_IFMT }.getOrNull()
        if (native != null && native != 0) return native
        val canonical = runCatching { file.canonicalPath }.getOrNull()
        if (canonical != null && canonical != file.absoluteFile.path) return OsConstants.S_IFLNK
        if (file.isDirectory) return OsConstants.S_IFDIR
        if (file.isFile) return OsConstants.S_IFREG
        return if (file.exists()) 0 else null
    }

    fun permissions(file: File): Int? = runCatching {
        Os.lstat(file.absolutePath).st_mode.toInt() and 0xFFF
    }.getOrNull()?.takeIf { it != 0 }
}
