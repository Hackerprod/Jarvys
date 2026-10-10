package com.jarvys.agent.skills

import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections

/** Immutable, release-bound APK text. Never resolves caller-supplied paths or workspace files. */
class FactoryGuidanceBundle private constructor(val version: String, resources: Map<String, String>) {
    val resources: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(resources))

    companion object {
        const val VERSION = "factory-guidance-v78"
        const val MAX_CORE_BYTES = 8 * 1024
        const val MAX_RESOURCE_BYTES = 16 * 1024
        const val MAX_TOTAL_BYTES = 64 * 1024
        const val MAX_RESOURCES = 16
        val NAMES: List<String> = Collections.unmodifiableList(listOf(
            "basics", "documents-media", "external-actions", "contacts-calendar", "database", "presentation", "lifecycle"))
        private const val ROOT = "skills/com.jarvys.apk-factory/references/"

        /** Loader is supplied by AssetManager in production; tests use synthetic bounded streams. */
        internal fun load(core: String, openAsset: (String) -> InputStream): FactoryGuidanceBundle {
            require(core.toByteArray(Charsets.UTF_8).size <= MAX_CORE_BYTES) { "Factory core exceeds its complete-content budget" }
            require(core.contains("Guidance version: $VERSION.")) { "Factory core guidance version mismatch" }
            require(NAMES.size <= MAX_RESOURCES)
            val manifest = JSONObject(readBounded(openAsset(ROOT + "manifest.json")))
            require(manifest.keys().asSequence().toSet() == setOf("version", "sha256") && manifest.getString("version") == VERSION) {
                "Factory guidance manifest version mismatch"
            }
            val hashes = manifest.getJSONObject("sha256")
            require(hashes.keys().asSequence().toSet() == NAMES.toSet()) { "Factory guidance resource catalog mismatch" }
            var total = 0
            val resources = linkedMapOf<String, String>()
            for (name in NAMES) {
                require(core.contains("- $name:")) { "Factory core must discover every reference" }
                val body = readBounded(openAsset(ROOT + name + ".md"))
                val bytes = body.toByteArray(Charsets.UTF_8)
                total += bytes.size
                require(total <= MAX_TOTAL_BYTES) { "Factory guidance bundle exceeds its byte budget" }
                require(body.startsWith("# Factory guidance $VERSION / $name\n")) { "Factory reference version mismatch" }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(hashes.getString(name) == hash) { "Factory reference content mismatch" }
                resources[name] = body
            }
            return FactoryGuidanceBundle(VERSION, resources)
        }

        private fun readBounded(input: InputStream): String = input.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_RESOURCE_BYTES) { "Factory reference exceeds its byte budget" }
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            val text = bytes.toString(Charsets.UTF_8)
            require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Factory reference is not valid UTF-8" }
            text
        }
    }
}
