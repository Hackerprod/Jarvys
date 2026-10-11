package com.jarvys.agent.apkfactory

import com.jarvys.factory.contract.CapabilityCatalog
import com.jarvys.factory.contract.ResourceBudgets
import com.jarvys.factory.runtime.ResourceBudgetsJson
import com.jarvys.factory.runtime.FactoryConfig
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
    val dataBackupTables: List<String> = emptyList(),
    val dataBackupKeys: List<String> = emptyList(),
    val resources: ResourceBudgets = ResourceBudgets.legacy(),
) {
    fun runtimeConfig(documentBroker: JSONObject? = null): ByteArray {
        val config = JSONObject().put("schemaVersion", 1).put("appId", appId)
            .put("name", name).put("entryPoint", "www/index.html")
            .put("capabilities", JSONArray(capabilities)).put("resourceBudgets", ResourceBudgetsJson.toJson(resources))
        backupPolicy()?.let { config.put("dataBackup", it) }
        if (documentBroker != null) config.put("documentBroker", documentBroker)
        val text = config.toString()
        FactoryConfig.parse(text, appId)
        return text.toByteArray(Charsets.UTF_8)
    }

    fun toJson(): JSONObject = JSONObject().put("schemaVersion", 1).put("appId", appId).put("name", name)
        .put("versionCode", versionCode).put("versionName", versionName)
        .put("capabilities", JSONArray(capabilities)).put("webDir", webDir).put("icon", icon)
        .put("resourceBudgets", ResourceBudgetsJson.toJson(resources))
        .also { json -> backupPolicy()?.let { json.put("dataBackup", it) } }

    private fun backupPolicy(): JSONObject? {
        if (dataBackupTables.isEmpty() && dataBackupKeys.isEmpty()) return null
        return JSONObject().also {
            if (dataBackupTables.isNotEmpty()) it.put("tables", JSONArray(dataBackupTables))
            if (dataBackupKeys.isNotEmpty()) it.put("keys", JSONArray(dataBackupKeys))
        }
    }

    companion object {
        val CAPABILITIES: List<String> = CapabilityCatalog.NAMES
        const val MAX_SPEC_BYTES = 16 * 1024
        const val MAX_WEB_BYTES = 8 * 1024 * 1024
        const val MAX_FILE_BYTES = 1024 * 1024
        const val MAX_WEB_FILES = 128
        private val APP_ID = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
        @JvmStatic fun parse(json: JSONObject): FactorySpec {
            val fields = setOf("schemaVersion", "appId", "name", "versionCode", "versionName", "capabilities", "webDir", "icon")
            require(json.keys().asSequence().toSet().let { it.containsAll(fields) && it.all { key -> key in fields || key == "dataBackup" || key == "resourceBudgets" } }) { "factory.json requires ${fields.joinToString()} and optional dataBackup/resourceBudgets" }
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
            val policy = if (json.has("dataBackup")) {
                val value = json.opt("dataBackup")
                require(value is JSONObject && value.length() > 0 &&
                    value.keys().asSequence().all { it == "tables" || it == "keys" }) { "dataBackup requires tables or keys and no other fields" }
                value
            } else null
            val backupTables = if (policy?.has("tables") == true) {
                val tables = policy.opt("tables")
                require(tables is JSONArray && tables.length() <= 8) { "dataBackup.tables must be an array of at most 8 tables" }
                val selected = (0 until tables.length()).map { index ->
                    val table = tables.opt(index)
                    require(table is String && table.matches(Regex("[a-z][a-z0-9_]{0,31}")) && table != "id" &&
                        !table.startsWith("sqlite_") && !table.startsWith("factory_")) { "Invalid backup table identifier" }
                    table
                }
                require(selected == selected.distinct().sorted()) { "Backup tables must be sorted and unique" }
                require(selected.isEmpty() || ("database" in caps && "documents" in caps)) { "Data backup requires database and documents capabilities" }
                java.util.Collections.unmodifiableList(selected)
            } else emptyList()
            val backupKeys = if (policy?.has("keys") == true) {
                val keys = policy.opt("keys")
                require(keys is JSONArray && keys.length() <= 32) { "dataBackup.keys must be an array of at most 32 keys" }
                val selected = (0 until keys.length()).map { index ->
                    val key = keys.opt(index)
                    require(key is String && key.matches(Regex("[a-zA-Z0-9_.:-]{1,96}"))) { "Invalid backup key identifier" }
                    key
                }
                require(selected == selected.distinct().sorted()) { "Backup keys must be sorted and unique" }
                require(selected.isEmpty() || ("storage" in caps && "documents" in caps)) { "Key backup requires storage and documents capabilities" }
                java.util.Collections.unmodifiableList(selected)
            } else emptyList()
            val webDir = relativePath(string(json, "webDir"))
            val icon = relativePath(string(json, "icon"))
            require(icon.endsWith(".png", true) || icon.endsWith(".json", true)) { "Icon must be PNG or vector JSON" }
            val resources = if (json.has("resourceBudgets")) ResourceBudgetsJson.parse(json.getJSONObject("resourceBudgets")) else ResourceBudgets.legacy()
            return FactorySpec(appId, name, version, versionName, CapabilityCatalog.select(caps), webDir, icon, backupTables, backupKeys, resources)
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
