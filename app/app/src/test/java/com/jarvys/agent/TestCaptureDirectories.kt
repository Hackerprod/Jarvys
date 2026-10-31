package com.jarvys.agent

import java.io.File
import java.util.UUID

/** Capture fixtures are isolated below java.io.tmpdir; they never write into the host home. */
object TestCaptureDirectories {
    private val namedDirectories = mutableMapOf<String, File>()

    fun create(label: String): File {
        val tempRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
        val directory = File(tempRoot, "jarvys-$label-${UUID.randomUUID()}")
        check(directory.mkdirs())
        assertOwned(directory, directory)
        return directory
    }

    fun assertOwned(directory: File, target: File) {
        val tempRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile.path
        val owner = directory.canonicalFile.path
        val resolved = target.canonicalFile.path
        check(owner == tempRoot || owner.startsWith(tempRoot + File.separator)) {
            "capture directory is outside java.io.tmpdir: $owner"
        }
        check(resolved == owner || resolved.startsWith(owner + File.separator)) {
            "capture path is outside its private temp directory: $resolved"
        }
    }

    fun named(label: String): File = synchronized(namedDirectories) {
        namedDirectories.getOrPut(label) { create(label) }
    }

    fun delete(directory: File, target: File) {
        assertOwned(directory, target)
        if (target.exists()) check(target.delete()) { "could not remove temporary capture ${target.path}" }
    }
}
