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
    @Test fun foregroundSensorsExtendTheVersionedCatalogWithoutChangingOriginalBudgets() {
        val bundle = load()
        assertEquals("factory-guidance-v82", bundle.version)
        assertEquals(listOf("basics", "documents-media", "external-actions", "contacts-calendar",
            "database", "presentation", "lifecycle", "sensors", "biometrics", "local-ui"), FactoryGuidanceBundle.NAMES)
        assertEquals(10, bundle.resources.size)
        assertEquals(8192, FactoryGuidanceBundle.MAX_CORE_BYTES)
        assertEquals(16384, FactoryGuidanceBundle.MAX_RESOURCE_BYTES)
        assertEquals(65536, FactoryGuidanceBundle.MAX_TOTAL_BYTES)
        assertTrue("Keep headroom below the complete core limit", core().toByteArray().size <= 7680)
        assertTrue(core().contains("20 capabilities / 42 methods"))
        assertTrue(core().contains("- sensors:"))
        val sensors = bundle.resources.getValue("sensors")
        for (required in listOf("sensors.list()", "sensors.capture({kind,durationMs,intervalMs,maxSamples})",
            "sensors.cancel()", "100–5000", "100–1000", "1–50", "16 KiB", "10 Hz", "gyroscope", "rad/s", "right-hand", "TYPE_GYROSCOPE",
            "device-natural", "includesGravity", "simulated:false", "sample_limit", "elapsedMs",
            "CANCELLED", "UNAVAILABLE", "no automatic restart", "unregister failure")) {
            assertTrue("Missing sensor contract: $required", sensors.contains(required))
        }
    }
    @Test fun localBiometricsExposeExactLimitsSelectivePermissionAndNoReusableAuthority() {
        val bundle = load()
        val biometric = bundle.resources.getValue("biometrics")
        assertTrue(core().contains("- biometrics:"))
        assertTrue(core().contains("USE_BIOMETRIC"))
        assertTrue(core().contains("biometrics: UNAVAILABLE"))
        for (required in listOf("biometrics.availability({})", "biometrics.authenticate({reason})",
            "biometrics.cancel({})", "API30+", "API24–29", "BIOMETRIC_STRONG", "Confirmation is a system hint", "1–160 UTF-16",
            "512 UTF-8", "unpaired surrogates", "available,status,minimumApi:30", "credentialFallback:false",
            "authenticated:true", "authorizesOtherActions:false", "simulated:false",
            "cancelRequested:boolean,cancellationAcknowledged:false", "60-second", "90 seconds",
            "Mismatch/help", "2 seconds", "process restart", "3 admitted", "8 no-op",
            "1 KiB", "invalid cancel", "Oversized/unparseable", "global limiter", "USE_BIOMETRIC",
            "retain zero permissions", "not full Runtime2", "Human", "physical matching")) {
            assertTrue("Missing biometric contract: $required", biometric.contains(required, true))
        }
        val documented = File("../APK_FACTORY.md").readText()
            .substringAfter("## Local strong biometrics (UX42 F1 v80)")
            .substringBefore("## Versioned agent guidance (v80 extension)").trim()
        assertEquals(biometric.substringAfter("## Local strong biometric authentication (v80)\n\n").trim(), documented)
        assertThrows(IllegalArgumentException::class.java) {
            load { path, bytes -> if (path.endsWith("biometrics.md")) bytes + "changed".toByteArray() else bytes }
        }
    }
    @Test fun corruptedMissingOversizedOrMalformedAssetsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { load { path, bytes -> if (path.endsWith("database.md")) bytes + "changed".toByteArray() else bytes } }
        assertThrows(IllegalArgumentException::class.java) { load { path, bytes -> if (path.endsWith("database.md")) ByteArray(16385) { 65 } else bytes } }
        assertThrows(IllegalArgumentException::class.java) { load { path, bytes -> if (path.endsWith("database.md")) byteArrayOf(0xc3.toByte()) else bytes } }
        assertThrows(java.io.IOException::class.java) { FactoryGuidanceBundle.load(core()) { throw java.io.IOException("missing") } }
        assertThrows(IllegalArgumentException::class.java) { FactoryGuidanceBundle.load(core().replace("factory-guidance-v82", "factory-guidance-v77")) { File(root, it).inputStream() } }
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
            { it.replace("factory-guidance-v82", "stale") },
            { it.replace("\"basics\":", "\"../basics\":") },
            { it.replace("\"version\":", "\"unknown\":") })) {
            assertThrows(Exception::class.java) { load { path, bytes -> if (path.endsWith("manifest.json")) mutate(bytes.toString(Charsets.UTF_8)).toByteArray() else bytes } }
        }
    }
}
