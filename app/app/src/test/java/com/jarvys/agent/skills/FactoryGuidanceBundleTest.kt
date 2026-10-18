package com.jarvys.agent.skills

import com.jarvys.factory.contract.CapabilityCatalog
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/** Reviewed contracts, not historical prose copies. Integrity/authority checks remain strict. */
class FactoryGuidanceBundleTest {
    private val root = File("src/main/assets")
    private fun core() = SkillMarkdownParser.parse(File(root, "skills/com.jarvys.apk-factory/SKILL.md").readText()).body
    private fun load(change: (String, ByteArray) -> ByteArray = { _, bytes -> bytes }) =
        FactoryGuidanceBundle.load(core()) { path -> ByteArrayInputStream(change(path, File(root, path).readBytes())) }
    private fun contract(topic: String, vararg required: String) {
        val body = load().resources.getValue(topic)
        for (text in required) assertTrue("Missing $topic contract: $text", body.contains(text, ignoreCase = true))
    }

    @Test fun catalogHashesBoundsAndImmutableMapAreVerified() {
        val bundle = load()
        assertEquals("factory-guidance-v89", bundle.version)
        assertEquals(listOf("basics", "documents-media", "external-actions", "contacts-calendar", "database",
            "presentation", "lifecycle", "sensors", "biometrics", "local-ui", "native-services", "native-input", "radios", "durable-native"), FactoryGuidanceBundle.NAMES)
        assertEquals(14, bundle.resources.size)
        assertEquals(8192, FactoryGuidanceBundle.MAX_CORE_BYTES)
        assertEquals(16384, FactoryGuidanceBundle.MAX_RESOURCE_BYTES)
        assertEquals(65536, FactoryGuidanceBundle.MAX_TOTAL_BYTES)
        assertEquals(16, FactoryGuidanceBundle.MAX_RESOURCES)
        assertTrue("Keep complete core headroom", core().toByteArray().size <= 7680)
        assertThrows(UnsupportedOperationException::class.java) { (bundle.resources as MutableMap)["evil"] = "injected" }
        assertThrows(UnsupportedOperationException::class.java) { (FactoryGuidanceBundle.NAMES as MutableList).add("evil") }
    }

    @Test fun everyImplementedMethodAndReferenceIsDiscoverable() {
        val bundle = load()
        val all = bundle.resources.values.joinToString("\n")
        assertEquals(33, CapabilityCatalog.NAMES.size)
        assertEquals(134, CapabilityCatalog.METHODS.size)
        assertTrue(core().contains("${CapabilityCatalog.NAMES.size} capabilities / ${CapabilityCatalog.METHODS.size} methods"))
        for (name in FactoryGuidanceBundle.NAMES) assertTrue(core().contains("- $name:"))
        for (method in CapabilityCatalog.METHODS.keys) assertTrue("Undocumented method: $method", all.contains(method))
    }

    @Test fun factoryActionsKeepCallerAuthenticationAndSystemOwnedInteractions() {
        contract("basics", "Authenticated native operations", "installed app caller is authenticated", "Android runtime permission prompts",
            "user selects documents, contacts, photos or recipients",
            "backup protocol v2", "legacy v1", "authenticated installed artifact", "no blind replay", "performed:false")
        for (text in listOf("appId+certificate", "64 recent artifacts", "retain exact signed scope", "Nonce/request/source-bound",
            "Android runtime permissions", "system selectors", "Damaged/missing evidence fails closed", "precompiled DEX",
            "no arbitrary per-app native compiler", "final packaged acceptance")) assertTrue("Missing core boundary: $text", core().contains(text, true))
    }

    @Test fun storagePagingAndFrozenBackupsKeepScopeAndUnknownOutcomes() {
        contract("basics", "storage.listPage({afterKey?,limit?})", "nextAfterKey", "snapshotAcrossPages:false", "TOO_LARGE",
            "523264", "65536", "storage.backupExport", "storage.backupImport", "dataBackup.keys defaults empty",
            "storage+documents", "jarvys-preferences-data", "storageVersion:1", "insertOnly", "replaceSelectedKeys",
            "even if equal", "unselected", "not a secret detector", "unknown outcome", "no rollback", "310000", "UNAVAILABLE")
        contract("database", "database.migrate", "database.transact", "database.select", "nextAfterId", "261120", "2046",
            "dataBackup.tables defaults empty", "database.backupExport", "database.backupImport", "replaceSelectedTables",
            "insertOnly", "No migration", "not authentication", "plaintext", "protocol v2", "legacy v1",
            "providerCommitConfirmed:false", "partial file", "never automatically retry", "UNAVAILABLE")
    }

    @Test fun sensorAndBiometricSemanticsRemainExplicit() {
        contract("sensors", "sensors.list()", "sensors.capture({kind,durationMs,intervalMs,maxSamples})", "100–300000",
            "100–1000", "1–50", "16KiB", "10Hz", "TYPE_GYROSCOPE", "rad/s", "right-hand", "device-natural",
            "includesGravity", "simulated:false", "sample_limit", "sensors.cancel()", "UNAVAILABLE", "no automatic restart",
            "sensors.subscribe({kind,samplingPeriodUs})", "sensors.poll({subscriptionId})", "sensors.unsubscribe({subscriptionId})",
            "droppedSamples", "HIGH_SAMPLING_RATE_SENSORS", "steps.current", "since_boot_while_sensor_activated")
        contract("biometrics", "biometrics.availability({})", "biometrics.authenticate({reason})", "biometrics.cancel({})",
            "API30+", "API24–29", "BIOMETRIC_STRONG", "Confirmation is a system hint", "1–160", "512 UTF-8",
            "credentialFallback:false", "authorizesOtherActions:false", "simulated:false", "cancellationAcknowledged:false",
            "60-second", "90 seconds", "2 seconds", "process restart", "USE_BIOMETRIC", "person alone", "UNAVAILABLE")
    }

    @Test fun nativeNetworkIsRealAndStreamingDoesNotInventReplayOrInference() {
        contract("native-services", "network.request", "network.probe", "network.ping", "network.cancel",
            "http_response_headers", "icmp:false", "protocol:\"icmp\"", "summaryParsed", "utility_summary",
            "/system/bin/ping", "never shell execution", "65507", "outputTruncated", "platform_denied",
            "INTERNET is process-wide", "usesCleartextTraffic=true", "TLS", "hostname", "network.upload", "network.download",
            "UPLOAD_REDIRECT_NOT_REPLAYABLE", "TRANSFER_LIMIT_REACHED", "eofConfirmed:false", "nextOffset(null",
            "contentSnapshotBound:false", "remoteCommitConfirmed:false", "speech.status", "speech.voices", "speech.speak",
            "surrogate pairs", "engine_reported_completion", "networkMayBeUsed:true", "audibilityVerified:false",
            "android_preference_may_fallback", "emittedAudioCannotBeRecalled:true", "UNAVAILABLE in preview")
    }

    @Test fun nativeInputKeepsPermissionsSecretDisclosureAndIntakeOwnershipHonest() {
        contract("native-input", "voiceRecognition.recognize", "preferOffline", "offlineGuaranteed:false",
            "externalActivityClosedVerified:false", "location.current", "allowMock", "permissionGranularity",
            "recording.record", "RECORD_AUDIO", "contentVerified:false", "recording.stop", "recording.cancel",
            "secrets.put", "secrets.capture", "secrets.get", "explicit plaintext", "secrets.request",
            "ordinary bounded network.request options", "rechecks URL/method at each redirect",
            "Response may echo secret", "Keystore", "incomingShare.take",
            "attachmentIndices", "contentTypeVerified:false", "at-most-once", "temporary read grants")
    }

    @Test fun radiosKeepGenericContractsAndPhysicalBoundaries() {
        contract("radios", "nfc.read", "nfc.write", "allowFormat", "tnf,typeHex,idHex,payloadHex", "rollback:false",
            "NDEF_UNSUPPORTED", "bluetooth.scan", "bluetooth.connect", "bluetooth.services", "bluetooth.read",
            "bluetooth.write", "bluetooth.requestMtu", "bluetooth.subscribe", "bluetooth.unsubscribe",
            "bluetooth.disconnect", "bluetooth.cancel", "neverForLocation", "pairing", "droppedEvents",
            "att_acknowledged", "peripheral business success", "actualMtu", "no hidden reflection", "preview UNAVAILABLE")
    }

    @Test fun durableWorkKeepsFreshPolicyInertActionsAndUncertainResults() {
        contract("durable-native", "notifications.schedule", "notifications.resumeScheduled", "notifications.pollActions",
            "notifications.ackAction", "notifications.generationStatus", "notifications.forgetGeneration",
            "POST_NOTIFICATIONS", "SCHEDULE_EXACT_ALARM", "delivery_unknown", "no auto-repost", "queued_os_blocked",
            "widgets.requestPin", "widgets.update", "widgets.remove", "widgets.manage", "launcherPlacementRemoved:false",
            "oncePerGeneration", "widgets.pollActions", "widgets.acknowledgeAction", "dispatchUnknown",
            "jobs.enqueueHttp", "jobs.resume", "execution:\"now\"", "jobs.readResult", "jobs.forget",
            "replayPossible:true", "Background work waits when its caller/source or Android prerequisites are unavailable",
            "No automatic HTTP replay after reservation, even GET", "no background JS")
    }

    @Test fun packagedMediaBudgetsAndAdaptiveAssetsAreSourceBounded() {
        contract("documents-media", "share.file({handle,filename,mimeType})", "immutable private disk snapshot",
            "sourceBytes8MiB/4GiB", "transferTimeoutMs", "leaseMs", "EOF probe", "v1 Int", "v2 Long",
            "deliveryConfirmed:false", "audio.play({handle})", "PCM16 WAV", "sourceBytes6MiB/4GiB",
            "durationMs30000/268435454", "setupTimeoutMs", "441245454", "audibilityConfirmed:false",
            "no physical-stop proof", "providerCommitConfirmed:false")
        contract("lifecycle", "adaptiveIcon:{foreground,background,insetPercent?}", "API24/25 legacy fallback",
            "API26+ adaptive", "sourceFiles128/512", "singleSourceBytes1MiB/16MiB", "totalSourceBytes8MiB/32MiB",
            "512ZIP entries", "16MiB/entry", "32MiB expanded/output", "including overhead", "fails busy",
            "no heap guarantee", "PackageInstaller", "unknown-source installer", "confirm installation",
            "Signing alone does not request installation", "Never automate Android security screens",
            "same appId/key", "final packaged validation")
        contract("native-services", "bounded JSON HTTP", "scoped secrets.request", "no incremental JS SSE/token callback API",
            "No native LLM engine/model weights/tool agent", "license/ABI/device")
    }

    @Test fun corruptedMissingOversizedOrMalformedAssetsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { load { p, b -> if (p.endsWith("database.md")) b + "changed".toByteArray() else b } }
        assertThrows(IllegalArgumentException::class.java) { load { p, b -> if (p.endsWith("database.md")) ByteArray(16385) { 65 } else b } }
        assertThrows(IllegalArgumentException::class.java) { load { p, b -> if (p.endsWith("database.md")) byteArrayOf(0xc3.toByte()) else b } }
        assertThrows(java.io.IOException::class.java) { FactoryGuidanceBundle.load(core()) { throw java.io.IOException("missing") } }
        assertThrows(IllegalArgumentException::class.java) { FactoryGuidanceBundle.load(core().replace(FactoryGuidanceBundle.VERSION, "stale")) { File(root, it).inputStream() } }
        assertThrows(IllegalArgumentException::class.java) { FactoryGuidanceBundle.load(core() + "x".repeat(8192)) { File(root, it).inputStream() } }
    }

    @Test fun exactResourceBudgetWorksButAggregateCannotBeBypassedWithValidHashes() {
        fun synthetic(size: Int, all: Boolean): FactoryGuidanceBundle {
            val prefix = "skills/com.jarvys.apk-factory/references/"
            val assets = mutableMapOf<String, ByteArray>()
            val hashes = org.json.JSONObject()
            for (name in FactoryGuidanceBundle.NAMES) {
                var text = "# Factory guidance ${FactoryGuidanceBundle.VERSION} / $name\n\nBudget fixture.\n"
                if (all || name == "basics") text += "x".repeat(size - text.toByteArray().size)
                val bytes = text.toByteArray()
                assets[prefix + name + ".md"] = bytes
                hashes.put(name, java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            }
            assets[prefix + "manifest.json"] = org.json.JSONObject().put("version", FactoryGuidanceBundle.VERSION).put("sha256", hashes).toString().toByteArray()
            return FactoryGuidanceBundle.load(core()) { ByteArrayInputStream(assets.getValue(it)) }
        }
        assertEquals(16384, synthetic(16384, false).resources.getValue("basics").toByteArray().size)
        val failure = assertThrows(IllegalArgumentException::class.java) { synthetic(16384, true) }
        assertEquals("Factory guidance bundle exceeds its byte budget", failure.message)
    }

    @Test fun unknownMissingOrStaleManifestEntriesAreRejected() {
        for (mutate in listOf<(String) -> String>(
            { it.replace(FactoryGuidanceBundle.VERSION, "stale") },
            { it.replace("\"basics\":", "\"../basics\":") },
            { it.replace("\"version\":", "\"unknown\":") })) {
            assertThrows(Exception::class.java) { load { p, b -> if (p.endsWith("manifest.json")) mutate(b.toString(Charsets.UTF_8)).toByteArray() else b } }
        }
    }
}
