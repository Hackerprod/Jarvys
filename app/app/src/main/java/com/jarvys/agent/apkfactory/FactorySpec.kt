package com.jarvys.agent.apkfactory

import com.jarvys.factory.contract.CapabilityCatalog
import org.json.JSONArray
import org.json.JSONObject

/** The factory accepts a bounded data contract, never Gradle, shell or arbitrary native code. */
data class FactorySpec(
    val appId: String,
    val name: String,
    val versionCode: Int,
    val versionName: String,
    val capabilities: List<String>,
    val webDir: String,
    val icon: String,
) {
    fun runtimeConfig(): ByteArray = JSONObject().put("schemaVersion", 1).put("appId", appId)
        .put("name", name).put("entryPoint", "www/index.html")
        .put("capabilities", JSONArray(capabilities)).toString().toByteArray(Charsets.UTF_8)

    fun toJson(): JSONObject = JSONObject().put("schemaVersion", 1).put("appId", appId).put("name", name)
        .put("versionCode", versionCode).put("versionName", versionName)
        .put("capabilities", JSONArray(capabilities)).put("webDir", webDir).put("icon", icon)

    companion object {
        val CAPABILITIES: List<String> = CapabilityCatalog.NAMES
        const val MAX_SPEC_BYTES = 16 * 1024
        const val MAX_WEB_BYTES = 8 * 1024 * 1024
        const val MAX_FILE_BYTES = 1024 * 1024
        const val MAX_WEB_FILES = 128
        private val APP_ID = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
        @JvmStatic fun parse(json: JSONObject): FactorySpec {
            val fields = setOf("schemaVersion", "appId", "name", "versionCode", "versionName", "capabilities", "webDir", "icon")
            require(json.keys().asSequence().toSet() == fields) { "factory.json must contain exactly ${fields.joinToString()}" }
            require(integer(json, "schemaVersion") == 1) { "Unsupported factory schemaVersion" }
            val appId = string(json, "appId")
            require(APP_ID.matches(appId) && appId.split('.').size in 2..16 && appId.length <= 127 && !appId.startsWith("android.") &&
                !appId.startsWith("com.jarvys.")) { "Use a unique lowercase applicationId (maximum 127 characters); Android and Jarvys IDs are reserved" }
            val name = string(json, "name")
            require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) { "App name must be 1–80 printable characters" }
            val version = integer(json, "versionCode")
            require(version > 0) { "versionCode must be a positive 32-bit integer" }
            val versionName = string(json, "versionName")
            require(versionName.isNotBlank() && versionName.length <= 64 && versionName.none { it.isISOControl() }) { "versionName must be 1–64 printable characters" }
            val raw = json.opt("capabilities")
            require(raw is JSONArray && raw.length() <= CAPABILITIES.size) { "capabilities must be a bounded array" }
            val caps = (0 until raw.length()).map { index ->
                val value = raw.opt(index)
                require(value is String && value in CAPABILITIES) { "Unsupported native capability; update the runtime template before using it" }
                value
            }
            require(caps.distinct().size == caps.size) { "Duplicate capability" }
            val webDir = relativePath(string(json, "webDir"))
            val icon = relativePath(string(json, "icon"))
            require(icon.endsWith(".png", true) || icon.endsWith(".json", true)) { "Icon must be PNG or vector JSON" }
            return FactorySpec(appId, name, version, versionName, CapabilityCatalog.select(caps), webDir, icon)
        }
        fun relativePath(value: String): String {
            require(value.isNotBlank() && value.length <= 240 && !value.startsWith('/') && '\\' !in value &&
                value.none { it.isISOControl() } && value.split('/').all { it.isNotBlank() && it != "." && it != ".." && !it.startsWith('.') }) {
                "Use a non-hidden, relative project path without traversal"
            }
            return value
        }
        private fun string(json: JSONObject, key: String): String = (json.opt(key) as? String)
            ?: throw IllegalArgumentException("$key must be a string")
        private fun integer(json: JSONObject, key: String): Int {
            val value = json.opt(key)
            require(value is Int || value is Long) { "$key must be an integer" }
            val number = (value as Number).toLong()
            require(number in 1..Int.MAX_VALUE) { "$key is outside the supported range" }
            return number.toInt()
        }
    }
}
