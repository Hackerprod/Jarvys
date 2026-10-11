package com.jarvys.agent.coding

import androidx.annotation.RequiresApi
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.apkfactory.FactorySpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Binary factory I/O uses the same project scope, writer lease and no-follow primitives as Coding. */
internal object FactoryProjectFiles {
    fun read(scope: ProjectScope, path: String, maxBytes: Int, token: CancellationToken): ByteArray {
        token.throwIfCancelled()
        val output = ByteArrayOutputStream()
        scope.openRead(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                token.throwIfCancelled()
                val count = input.read(buffer)
                if (count < 0) { break }
                require(output.size().toLong() + count <= maxBytes) { "File exceeds factory size limit: $path" }
                output.write(buffer, 0, count)
            }
        }
        val bytes = output.toByteArray()
        require(sha(scope, path, maxBytes, token) == ProjectScope.sha256(bytes)) { "Factory input changed while reading: $path" }
        return bytes
    }

    fun sha(scope: ProjectScope, path: String, maxBytes: Int, token: CancellationToken): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var total = 0L
        scope.openRead(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                token.throwIfCancelled()
                val count = input.read(buffer)
                if (count < 0) { break }
                total += count
                require(total <= maxBytes) { "File grew beyond factory size limit: $path" }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Metadata-only plan; no source array is allocated before aggregate heap admission. */
    class WebsitePlan internal constructor(val directory: String, val sizes: Map<String, Int>) {
        val totalBytes: Long = sizes.values.sumOf { it.toLong() }
    }

    @RequiresApi(26)
    fun planWebsite(scope: ProjectScope, directory: String, budgets: com.jarvys.factory.contract.ResourceBudgets.Packaging,
                    token: CancellationToken): WebsitePlan {
        FactorySpec.relativePath(directory)
        val sizes = sortedMapOf<String, Int>()
        var total = 0L
        var visited = 0L
        // Every bounded file can introduce at most this many path components. Empty trees are
        // charged too, so traversal remains finite without a lower hidden file-count ceiling.
        val maxEntries = budgets.sourceFiles.toLong() * FactorySpec.MAX_PATH_SEGMENTS
        fun visit(relative: String) {
            token.throwIfCancelled()
            val folder = scope.resolve(relative)
            require(ProjectFileIO.attributes(folder).isDirectory) { "webDir must be an ordinary directory" }
            java.nio.file.Files.newDirectoryStream(folder.toPath()).use { children ->
                for (child in children) {
                    token.throwIfCancelled()
                    require(++visited <= maxEntries) { "Website directory entries exceed the declared path/file envelope" }
                    val path = "$relative/${child.fileName}"
                    FactorySpec.relativePath(path)
                    val resolved = scope.resolve(path)
                    val attributes = ProjectFileIO.attributes(resolved)
                    if (attributes.isDirectory) visit(path)
                    else {
                        require(attributes.isRegularFile) { "Website contains a special file" }
                        require(sizes.size < budgets.sourceFiles) { "Website exceeds declared sourceFiles" }
                        val size = attributes.size()
                        require(size in 0..budgets.singleSourceBytes) { "Website file exceeds declared singleSourceBytes: $path" }
                        total += size
                        require(total <= budgets.totalSourceBytes) { "Website exceeds declared totalSourceBytes" }
                        sizes[path.removePrefix("$directory/")] = size.toInt()
                    }
                }
            }
        }
        visit(directory)
        require("index.html" in sizes) { "webDir must contain index.html" }
        return WebsitePlan(directory, java.util.Collections.unmodifiableMap(sizes))
    }

    fun readWebsite(scope: ProjectScope, plan: WebsitePlan, token: CancellationToken): Map<String, ByteArray> =
        plan.sizes.mapValuesTo(sortedMapOf()) { (inside, size) ->
            val path = "${plan.directory}/$inside"
            read(scope, path, size, token).also {
                require(it.size == size) { "Website file changed after resource planning: $path" }
            }
        }

    @RequiresApi(26)
    fun website(scope: ProjectScope, directory: String, token: CancellationToken,
                budgets: com.jarvys.factory.contract.ResourceBudgets.Packaging = com.jarvys.factory.contract.ResourceBudgets.legacy().packaging): Map<String, ByteArray> =
        readWebsite(scope, planWebsite(scope, directory, budgets, token), token)

    fun output(scope: ProjectScope, path: String): File {
        FactorySpec.relativePath(path)
        require(path.endsWith(".apk")) { "Output must end with .apk" }
        val target = scope.resolve(path)
        require(!ProjectFileIO.exists(target)) { "Output already exists; choose a new path. No file was replaced." }
        require(target.parentFile != null && ProjectFileIO.isDirectory(target.parentFile!!)) {
            "Output directory must already exist in the Coding project"
        }
        return target
    }

    /** A partial exclusive copy is deliberately retained; its private receipt never authorizes replay. */
    fun publish(scope: ProjectScope, path: String, staging: File, lease: ProjectScope.WriterLease, token: CancellationToken, checkAvailable: () -> Unit) {
        checkAvailable()
        lease.validate()
        val destination = output(scope, path)
        val hash = staging.inputStream().use(ProjectScope::sha256)
        checkAvailable()
        lease.validate()
        token.throwIfCancelled()
        lease.markChanged()
        ProjectFileIO.copyNew(staging, destination, hash, staging.length()) {
            token.throwIfCancelled()
            checkAvailable()
            lease.validate()
        }
        require(sha(scope, path, staging.length().toInt(), token) == hash) { "Output verification failed; preserve and inspect the existing output" }
    }
}
