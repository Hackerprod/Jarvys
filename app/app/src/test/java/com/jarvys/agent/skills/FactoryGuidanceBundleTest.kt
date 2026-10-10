package com.jarvys.agent.skills

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream

class FactoryGuidanceBundleTest {
    private val root = File("src/main/assets")
    private fun core() = SkillMarkdownParser.parse(File(root, "skills/com.jarvys.apk-factory/SKILL.md").readText()).body
    private fun load(change: (String, ByteArray) -> ByteArray = { _, bytes -> bytes }) =
        FactoryGuidanceBundle.load(core()) { path -> ByteArrayInputStream(change(path, File(root, path).readBytes())) }

    @Test fun catalogHashesBoundsAndImmutableMapAreVerified() {
        val bundle = load()
        assertEquals(FactoryGuidanceBundle.NAMES.toSet(), bundle.resources.keys)
        assertThrows(UnsupportedOperationException::class.java) { (bundle.resources as MutableMap)["evil"] = "injected" }
        assertThrows(UnsupportedOperationException::class.java) { (FactoryGuidanceBundle.NAMES as MutableList).add("evil") }
    }
    @Test fun corruptedMissingOversizedOrMalformedAssetsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { load { path, bytes -> if (path.endsWith("database.md")) bytes + "changed".toByteArray() else bytes } }
        assertThrows(IllegalArgumentException::class.java) { load { path, bytes -> if (path.endsWith("database.md")) ByteArray(16385) { 65 } else bytes } }
        assertThrows(IllegalArgumentException::class.java) { load { path, bytes -> if (path.endsWith("database.md")) byteArrayOf(0xc3.toByte()) else bytes } }
        assertThrows(java.io.IOException::class.java) { FactoryGuidanceBundle.load(core()) { throw java.io.IOException("missing") } }
        assertThrows(IllegalArgumentException::class.java) { FactoryGuidanceBundle.load(core().replace("factory-guidance-v78", "factory-guidance-v77")) { File(root, it).inputStream() } }
        assertThrows(IllegalArgumentException::class.java) { FactoryGuidanceBundle.load(core() + "x".repeat(8192)) { File(root, it).inputStream() } }
    }
    @Test fun exactResourceBudgetWorksButAggregateBudgetCannotBeBypassedWithValidHashes() {
        fun synthetic(size: Int, all: Boolean): FactoryGuidanceBundle {
            val prefix = "skills/com.jarvys.apk-factory/references/"
            val assets = mutableMapOf<String, ByteArray>()
            val hashes = org.json.JSONObject()
            for (name in FactoryGuidanceBundle.NAMES) {
                var text = File(root, "$prefix$name.md").readText()
                if (all || name == "database") text += "x".repeat(size - text.toByteArray().size)
                val bytes = text.toByteArray()
                assets["$prefix$name.md"] = bytes
                hashes.put(name, java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            }
            assets[prefix + "manifest.json"] = org.json.JSONObject().put("version", FactoryGuidanceBundle.VERSION).put("sha256", hashes).toString().toByteArray()
            return FactoryGuidanceBundle.load(core()) { ByteArrayInputStream(assets.getValue(it)) }
        }
        assertEquals(16384, synthetic(16384, false).resources.getValue("database").toByteArray().size)
        assertThrows(IllegalArgumentException::class.java) { synthetic(10000, true) }
    }

    @Test fun everyPriorContractSectionIsPreservedWithOnlyReviewedReplacements() {
        val original = File("src/test/resources/factory-guidance/v77-skill.txt").readText().split("---\n", limit = 3)[2]
        val modules = load().resources
        val all = modules.values.joinToString("\n")
        val sections = original.split(Regex("(?m)(?=^## )"))
        assertEquals(18, sections.size)
        for (section in sections) {
            if (section.startsWith("## Presentation v77")) {
                val documented = File("../APK_FACTORY.md").readText()
                    .substringAfter("## Presentation preferences (UX42 F1 v77)")
                    .substringBefore("## Versioned agent guidance (v78 maintenance)").trim()
                assertTrue(modules.getValue("presentation").contains(documented))
            } else {
                val expected = section.replace("TTS/voice/F1 pending, F2/F3 closed", "TTS/voice/F1 and F2/F3 remain pending and unavailable").trim()
                assertTrue("Lost prior contract: " + section.lineSequence().first(), all.contains(expected))
            }
        }
    }

    @Test fun unknownOrMissingManifestEntriesAndVersionMismatchAreRejected() {
        for (mutate in listOf<(String) -> String>(
            { it.replace("factory-guidance-v78", "stale") },
            { it.replace("\"basics\":", "\"../basics\":") },
            { it.replace("\"version\":", "\"unknown\":") })) {
            assertThrows(Exception::class.java) { load { path, bytes -> if (path.endsWith("manifest.json")) mutate(bytes.toString(Charsets.UTF_8)).toByteArray() else bytes } }
        }
    }
}
