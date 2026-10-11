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
        assertEquals("factory-guidance-v84", bundle.version)
        assertEquals(listOf("basics", "documents-media", "external-actions", "contacts-calendar",
            "database", "presentation", "lifecycle", "sensors", "biometrics", "local-ui"), FactoryGuidanceBundle.NAMES)
        assertEquals(10, bundle.resources.size)
        assertEquals(8192, FactoryGuidanceBundle.MAX_CORE_BYTES)
        assertEquals(16384, FactoryGuidanceBundle.MAX_RESOURCE_BYTES)
        assertEquals(65536, FactoryGuidanceBundle.MAX_TOTAL_BYTES)
        assertTrue("Keep headroom below the complete core limit", core().toByteArray().size <= 7680)
        assertTrue(core().contains("20 capabilities / 46 methods"))
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
            "retain zero permissions", "not full Runtime2", "The person alone operates the OS prompt", "physical matching")) {
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
        assertThrows(IllegalArgumentException::class.java) { FactoryGuidanceBundle.load(core().replace("factory-guidance-v84", "factory-guidance-v77")) { File(root, it).inputStream() } }
        assertThrows(IllegalArgumentException::class.java) { FactoryGuidanceBundle.load(core() + "x".repeat(8192)) { File(root, it).inputStream() } }
    }
    @Test fun exactResourceBudgetWorksButAggregateBudgetCannotBeBypassedWithValidHashes() {
        fun synthetic(size: Int, all: Boolean): FactoryGuidanceBundle {
            val prefix = "skills/com.jarvys.apk-factory/references/"
            val assets = mutableMapOf<String, ByteArray>()
            val hashes = org.json.JSONObject()
            for (name in FactoryGuidanceBundle.NAMES) {
                var text = File(root, "$prefix$name.md").readText()
                if (all || name == "basics") text += "x".repeat(size - text.toByteArray().size)
                val bytes = text.toByteArray()
                assets["$prefix$name.md"] = bytes
                hashes.put(name, java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            }
            assets[prefix + "manifest.json"] = org.json.JSONObject().put("version", FactoryGuidanceBundle.VERSION).put("sha256", hashes).toString().toByteArray()
            return FactoryGuidanceBundle.load(core()) { ByteArrayInputStream(assets.getValue(it)) }
        }
        // Pad the largest reference so the exact per-resource case remains below the total limit.
        assertEquals(16384, synthetic(16384, false).resources.getValue("basics").toByteArray().size)
        val aggregate = assertThrows(IllegalArgumentException::class.java) { synthetic(16384, true) }
        assertEquals("Factory guidance bundle exceeds its byte budget", aggregate.message)
    }

    @Test fun priorApiDataContractsArePreservedWithExplicitPolicyReplacements() {
        val original = File("src/test/resources/factory-guidance/v77-skill.txt").readText().split("---\n", limit = 3)[2]
        val modules = load().resources
        val all = modules.values.joinToString("\n")
        // Policy behavior changed deliberately. Keep exact API, quota, receipt and uncertainty text;
        // every exception to v77 byte preservation is an explicit, individually reviewed substitution.
        val reviewedReplacements = listOf(
            "Settings → Factory documents recovery: declared old-task closure, no OS proof/restored authority." to "Settings → Factory documents recovery reconciles uncertainty, never proves OS closure or restores authority.",
            "Separate guard; Settings → External action review." to "Separate broker; Settings → External action review.",
            "Missing install permission: protected review/manual grants never resume; native-close/new request. Damaged-record recovery: human-only, cancel owned sessions, preserve unknown outcome; never erase app data/keys." to "Missing install permission: manual Android grant then a fresh request, never resumed authority. Damaged-record recovery: foreground owned-session cleanup preserves unknown outcome; no global automation lock or app data/key erasure.",
            "clipboard.write(text): native confirmation; no read." to "clipboard.write(text): authenticated host policy (ALLOW_ALWAYS/ASK/DENY); no read. Preview is simulated with performed:false.",
            "Human review/picker/Use; never automate." to "Saved policy: ALLOW_ALWAYS opens the picker, ASK reviews once before it, DENY blocks. Valid selection returns access without another Use confirmation.",
            "human review opens chooser. 5min; native close/recovery blocks automation until chooser/recipient closure acknowledgment." to "saved policy opens chooser (ASK reviews once). 5min recipient lease; chooser return delivers a dispatch-only receipt without another closure acknowledgment.",
            "Human-only launch/Use; never automate." to "Saved policy controls launch; ASK reviews once, valid selection has no second Use confirmation.",
            "Host byte review; human Play once/current output/volume." to "Host byte validation; ALLOW_ALWAYS plays automatically, ASK reviews once; current output/volume.",
            "Native Close/recovery blocks automation; focus/lifecycle/route loss stops." to "ALLOW_ALWAYS closes after confirmed player cleanup; unknown cleanup still blocks; focus/lifecycle/route loss stops.",
            "Human-only full labelled data/recipient review; never automate. 5min durable guards/no replay; cancel cannot recall data/close tasks. Close external task then native/Settings acknowledgment; declared closure, not OS-verified." to "Saved policy controls dispatch: ALLOW_ALWAYS advances with a verified default recipient or waits for recipient choice; ASK reviews labelled data/recipient once; DENY blocks. 5min authority/no replay; cancel cannot recall data/close tasks. Dispatch returns without a second closure acknowledgment; recovery preserves uncertainty, not OS-verified closure.",
            "One-shot/no retries; close browser then native review/Settings recovery." to "One-shot/no retries; dispatch-only completion, Settings recovery for uncertain state.",
            "Human picker launch/full frozen value approval for exact latest-signed app." to "Saved policy controls picker launch for the exact latest-signed app; ASK reviews once. The human selects the datum in Android; validated delivery has no second frozen-value confirmation.",
            "same-project receipt/in-app approval." to "same-project receipt/factory.sign policy (default ALLOW_ALWAYS; ASK reviews once; DENY blocks).",
            "Human-only full app/version/hash/certificate/receipt review/Android consent. Signing≠install approval. Never automate/grant unknown-source permission/bypass warnings/retry commit." to "factory.install policy (default ALLOW_ALWAYS; ASK reviews exact app/version/hash/certificate/receipt once; DENY blocks) then required Android consent. Signing alone requests no install. No duplicate host prompt before system consent. Never automate unknown-source grants, bypass warnings or retry uncertain commit.",
            "TTS/voice/F1 pending, F2/F3 closed" to "TTS/voice/F1 and F2/F3 remain pending and unavailable"
        )
        for ((before, after) in reviewedReplacements) {
            assertEquals("Obsolete clause must have one historical source: $before", 1, original.split(before).size - 1)
            assertFalse("Obsolete forced behavior survived: $before", all.contains(before))
            assertTrue("Reviewed replacement missing: $after", all.contains(after))
        }
        val sections = original.split(Regex("(?m)(?=^## )"))
        assertEquals(18, sections.size)
        for (section in sections) {
            if (section.startsWith("## Presentation v77")) {
                val documented = File("../APK_FACTORY.md").readText()
                    .substringAfter("## Presentation preferences (UX42 F1 v77)")
                    .substringBefore("## Versioned agent guidance (v78 maintenance)").trim()
                assertTrue(modules.getValue("presentation").contains(documented))
            } else {
                val expected = reviewedReplacements.fold(section) { text, (before, after) -> text.replace(before, after) }.trim()
                assertTrue("Lost prior contract: " + section.lineSequence().first(), all.contains(expected))
            }
        }
    }

    @Test fun unknownOrMissingManifestEntriesAndVersionMismatchAreRejected() {
        for (mutate in listOf<(String) -> String>(
            { it.replace("factory-guidance-v84", "stale") },
            { it.replace("\"basics\":", "\"../basics\":") },
            { it.replace("\"version\":", "\"unknown\":") })) {
            assertThrows(Exception::class.java) { load { path, bytes -> if (path.endsWith("manifest.json")) mutate(bytes.toString(Charsets.UTF_8)).toByteArray() else bytes } }
        }
    }
    @Test fun selectedDataBackupRetainsExplicitScopePolicyReviewAndUncertainOutcomes() {
        val bundle = load()
        val database = bundle.resources.getValue("database")
        val documents = bundle.resources.getValue("documents-media")
        for (required in listOf("database.backupExport", "database.backupImport", "dataBackup", "empty tables list disables", "ALLOW_ALWAYS", "ASK", "DENY", "protocol v2", "legacy v1", "insertOnly", "replaceSelectedTables",
            "1 MiB", "plaintext", "not authentication", "Secrets", "all values", "cloud-sync", "providerCommitConfirmed:false",
            "partial file", "unselected", "conflict", "No migration", "310000", "UNAVAILABLE", "never automatically retry")) {
            assertTrue("Missing backup contract: $required", database.contains(required, true))
        }
        assertTrue(documents.contains("default empty disables"))
        assertTrue(documents.contains("untouched read handle"))
        assertTrue(core().contains("20 capabilities / 46 methods"))
        assertTrue(core().contains("dataBackup.tables defaults empty"))
    }

    @Test fun selectedStorageBackupDocumentsExactScopeConsentAndUnknownCommit() {
        val basics = load().resources.getValue("basics")
        for (required in listOf("storage.backupExport", "storage.backupImport", "dataBackup.keys", "0–32", "1–32",
            "storage+documents", "version:1", "storageVersion:1", "jarvys-preferences-data", "insertOnly", "replaceSelectedKeys",
            "even if", "missing selected export keys", "presentation", "no combined SQLite/storage transaction", "not a secret detector",
            "all-values approval", "ALLOW_ALWAYS", "ASK", "DENY", "protocol v2", "legacy v1", "cloud-sync", "unknown outcome", "no rollback", "no automatic replay", "310000",
            "documents.cancel", "20 maximum", "UNAVAILABLE", "before any storage snapshot")) {
            assertTrue("Missing storage backup contract: $required", basics.contains(required, true))
        }
        val documented = File("../APK_FACTORY.md").readText().substringAfter("## Selected string key-value backup (v84)\n\n").trim()
        assertEquals(basics.substringAfter("## Selected string key-value backup (v84)\n\n").trim(), documented)
        assertTrue(core().contains("dataBackup.keys defaults empty"))
    }

    @Test fun operationPolicyGuidanceMatchesTheClosedCatalogAndKeepsRealBoundaries() {
        val basics = load().resources.getValue("basics")
        val policy = basics.substringAfter("## Factory operation permissions\n\n")
            .substringBefore("## Selected string key-value backup (v84)").trim()
        val documented = File("../APK_FACTORY.md").readText()
            .substringAfter("## Factory operation permissions\n\n")
            .substringBefore("## A. Architecture and ARM64 viability").trim()
        assertEquals(policy, documented)
        val documentedOperations = policy.lineSequence().filter { it.startsWith("- ") }
            .flatMap { it.removePrefix("- ").split(", ").asSequence() }.toList()
        val implemented = com.jarvys.agent.apkfactory.FactoryOperationPolicy.Operation.entries.map { it.id }
        assertEquals(20, implemented.size)
        assertEquals(implemented.toSet(), documentedOperations.toSet())
        assertEquals(20, documentedOperations.size)
        for (required in listOf("starts with ALLOW_ALWAYS", "ASK asks once", "DENY prevents",
            "appId plus authenticated certificate", "project-bound", "persisted policy revisions",
            "Already-open file descriptors cannot be revoked retroactively", "protocol v2", "protocol v1",
            "before each export write and import commit", "last enforceable checkpoint", "latest signed APK receipt",
            "per-artifact trust history is deferred", "share.text", "export.text", "local storage", "haptics",
            "technical quotas", "Coding autonomy", "performed:false", "biometric authentication", "installer consent")) {
            assertTrue("Missing policy boundary: $required", policy.contains(required))
        }
        assertTrue(core().contains("Default ALLOW_ALWAYS"))
        assertTrue(core().contains("ASK asks once per exact request"))
        assertTrue(core().contains("Packaged precompiled DEX is supported"))
        assertTrue(core().contains("per-app arbitrary native compilation is not"))
        assertTrue(core().contains("Clipboard preview is simulated with performed:false"))
        assertFalse(core().contains("separate in-app approval"))
    }

}
