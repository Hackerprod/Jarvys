package com.jarvys.agent.apkfactory

import com.jarvys.factory.contract.ManifestPlan
import com.jarvys.factory.contract.ResourceBudgets
import com.jarvys.factory.runtime.ResourceBudgetsJson
import org.json.JSONArray
import org.json.JSONObject

/** Disclosure evidence only, never a permission grant or authority to extend the closed template. */
internal data class FactorySigningScope private constructor(private val canonical: String) {
    fun toJson(): JSONObject = JSONObject(canonical)
    /** Legacy receipts retain their original bytes while resolving only their historical limits. */
    fun resources(): ResourceBudgets = toJson().let {
        if (it.has("resourceBudgets")) ResourceBudgetsJson.parse(it.getJSONObject("resourceBudgets")) else ResourceBudgets.legacy()
    }
    fun disclosure(previous: FactorySigningScope?): List<String> {
        if (previous == null) return listOf("Last-signed scope baseline unavailable (legacy, missing or invalid). Scope expansion cannot be determined; review every effective capability, manifest declaration, query action and resource capacity above.")
        val before = previous.toJson(); val after = toJson()
        val changes = mutableListOf<String>()
        for (field in LEGACY_FIELDS + listOfNotNull("queryActions".takeIf { before.has(it) && after.has(it) })) {
            val old = values(before.getJSONArray(field)); val current = values(after.getJSONArray(field))
            val added = current - old; val removed = old - current
            if (added.isNotEmpty()) changes += "Scope additions ($field): ${added.sorted().joinToString()}"
            if (removed.isNotEmpty()) changes += "Scope removals ($field): ${removed.sorted().joinToString()}"
        }
        if (!before.has("queryActions")) changes += "Last-signed query action baseline unavailable: the legacy scope did not record query actions. Query action expansion cannot be determined."
        if (!after.has("queryActions")) changes += "Current query action scope unavailable: the legacy scope did not record query actions."
        val oldResources = resourceValues(previous.resources()); val currentResources = resourceValues(resources())
        for ((field, current) in currentResources) {
            val old = oldResources.getValue(field)
            if (old != current) changes += "Resource capacity changes ($field): $old -> $current"
        }
        return changes.ifEmpty { listOf("Effective scope unchanged from the last signed APK (capabilities, permissions, exported components, features, queries, query actions, allowed hosts and resource capacities).") }
    }
    fun anchored(appId: String, fingerprint: String, version: Int, apkSha: String): JSONObject = JSONObject()
        .put("schemaVersion", 1).put("appId", appId).put("certificateSha256", fingerprint)
        .put("versionCode", version).put("apkSha256", apkSha).put("scope", toJson())
    companion object {
        private val LEGACY_FIELDS = listOf("capabilities", "permissions", "exportedComponents", "features", "queries", "allowedHosts")
        private val CURRENT_FIELDS = LEGACY_FIELDS + listOf("queryActions", "resourceBudgets")
        private fun values(array: JSONArray): Set<String> = (0 until array.length()).map { array.getString(it) }.toSet()
        private fun resourceValues(resources: ResourceBudgets): Map<String, Long> = buildMap {
            for ((section, value) in resources.toMap()) if (value is Map<*, *>) {
                for ((field, number) in value) put("$section.$field", (number as Number).toLong())
            }
        }
        fun fromPlan(plan: ManifestPlan): FactorySigningScope = fromPlan(plan, ResourceBudgets.legacy())
        fun fromPlan(plan: ManifestPlan, resources: ResourceBudgets): FactorySigningScope = parse(JSONObject()
            .put("capabilities", JSONArray(plan.capabilities)).put("permissions", JSONArray(plan.permissions))
            .put("exportedComponents", JSONArray(plan.exportedComponents)).put("features", JSONArray(plan.features))
            .put("queries", JSONArray(plan.queries)).put("allowedHosts", JSONArray(plan.hosts))
            .put("queryActions", JSONArray(plan.queryActions)).put("resourceBudgets", ResourceBudgetsJson.toJson(resources)))
        private fun parse(json: JSONObject): FactorySigningScope {
            val fields = json.keys().asSequence().toSet()
            val legacy = fields == LEGACY_FIELDS.toSet()
            require(legacy || fields == CURRENT_FIELDS.toSet())
            val canonical = JSONObject()
            // Never add fields to old records: their canonical bytes authenticate retained history.
            for (field in if (legacy) LEGACY_FIELDS else LEGACY_FIELDS + "queryActions") {
                val array = json.getJSONArray(field)
                require(array.length() <= 128)
                val items = (0 until array.length()).map {
                    val value = array.get(it)
                    require(value is String && value.isNotBlank() && value.length <= 512 && value.none { c -> c.isISOControl() })
                    value as String
                }
                require(items.toSet().size == items.size)
                canonical.put(field, JSONArray(items.sorted()))
            }
            if (!legacy) {
                val declared = json.getJSONObject("resourceBudgets")
                val resolved = ResourceBudgetsJson.toJson(ResourceBudgetsJson.parse(declared))
                // Signed snapshots freeze every resolved value, even when the project used a partial declaration.
                require(declared.keys().asSequence().toSet() == resolved.keys().asSequence().toSet())
                for (section in resolved.keys().asSequence().filter { it != "version" }) {
                    require(declared.getJSONObject(section).keys().asSequence().toSet() == resolved.getJSONObject(section).keys().asSequence().toSet())
                }
                canonical.put("resourceBudgets", resolved)
            }
            require(canonical.toString().toByteArray(Charsets.UTF_8).size <= 48 * 1024)
            return FactorySigningScope(canonical.toString())
        }
        /** Missing or invalid bound scopes are unknown; valid six-field legacy scopes stay unmodified. */
        fun readBaseline(record: JSONObject, appId: String, fingerprint: String, version: Int): FactorySigningScope? = try {
            val snapshot = record.getJSONObject("lastSignedScope")
            require(version > 0 && record.getString("lastApkSha256").matches(Regex("[a-f0-9]{64}")))
            require(snapshot.keys().asSequence().toSet() == setOf("schemaVersion", "appId", "certificateSha256", "versionCode", "apkSha256", "scope"))
            require(snapshot.get("schemaVersion") == 1 && snapshot.get("versionCode") == version)
            require(snapshot.getString("appId") == appId && snapshot.getString("certificateSha256") == fingerprint)
            require(snapshot.getString("apkSha256") == record.getString("lastApkSha256"))
            parse(snapshot.getJSONObject("scope"))
        } catch (_: Exception) { null }
    }
}
