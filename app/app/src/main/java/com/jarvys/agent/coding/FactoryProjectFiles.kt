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

    @RequiresApi(26)
    fun website(scope: ProjectScope, directory: String, token: CancellationToken): Map<String, ByteArray> {
        val result = sortedMapOf<String, ByteArray>()
        var total = 0L
        var visited = 0
        fun visit(relative: String, depth: Int) {
            require(depth <= 12) { "Website directory nesting exceeds limit" }
            val folder = scope.resolve(relative)
            require(ProjectFileIO.attributes(folder).isDirectory) { "webDir must be an ordinary directory" }
            java.nio.file.Files.newDirectoryStream(folder.toPath()).use { children ->
                for (child in children) {
                    token.throwIfCancelled()
                    require(++visited <= 256) { "Website contains more than 256 total directory entries" }
                    val path = "$relative/${child.fileName}"
                    FactorySpec.relativePath(path)
                    val resolved = scope.resolve(path)
                    val attributes = ProjectFileIO.attributes(resolved)
                    if (attributes.isDirectory) visit(path, depth + 1)
                    else {
                        require(attributes.isRegularFile) { "Website contains a special file" }
                        require(result.size < FactorySpec.MAX_WEB_FILES) { "Website has too many files" }
                        val data = read(scope, path, FactorySpec.MAX_FILE_BYTES, token)
                        total += data.size
                        require(total <= FactorySpec.MAX_WEB_BYTES) { "Website exceeds 8 MiB" }
                        val inside = path.removePrefix("$directory/")
                        result[inside] = data
                    }
                }
            }
        }
        visit(directory, 0)
        require("index.html" in result) { "webDir must contain index.html" }
        return result
    }

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
