package com.jarvys.agent

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

class NoThirdPartyDerivationMarkersTest {
    @Test fun productionSourcesAssetsResourcesManifestsAndNoticeHaveNoKelivoMarker() {
        val project = projectRoot()
        val locations = scanForMarker(listOf(File(project, "app/src/main"), File(project, "NOTICE.md")))
        assertTrue("Third-party derivation marker remains: ${locations.joinToString()}", locations.isEmpty())
    }

    @Test fun scannerDetectsMixedCaseMarkerInAnIsolatedTextFile() {
        val temporary = kotlin.io.path.createTempDirectory("derivation-marker-guard-").toFile()
        try {
            File(temporary, "sample.kt").writeText("// kElIvO provenance marker\n")
            val locations = scanForMarker(listOf(temporary))
            assertTrue("Test fixture should be reported: $locations", locations.any { it.endsWith("sample.kt:1") })
        } finally {
            temporary.deleteRecursively()
        }
    }

    private fun scanForMarker(roots: List<File>): List<String> = buildList {
        roots.forEach { root ->
            if (root.isFile) {
                readMarkerLocations(root, root.name)?.let(::addAll)
            } else if (root.isDirectory) {
                root.walkTopDown().filter(File::isFile).forEach { file ->
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    readMarkerLocations(file, "${root.name}/$relative")?.let(::addAll)
                }
            }
        }
    }

    private fun readMarkerLocations(file: File, label: String): List<String>? {
        val text = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(file.readBytes())).toString()
        }.getOrNull() ?: return null
        val results = mutableListOf<String>()
        text.lineSequence().forEachIndexed { index, line ->
            if (line.lowercase(Locale.ROOT).contains(MARKER)) results += "$label:${index + 1}"
        }
        return results
    }

    private fun projectRoot(): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return sequenceOf(working, working.parentFile, File(working, "app"), File(working.parentFile, "app"))
            .first { File(it, "app/src/main").isDirectory && File(it, "NOTICE.md").isFile }
    }

    private companion object {
        const val MARKER = "kelivo"
    }
}
