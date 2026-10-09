package com.jarvys.agent.apkfactory

import com.jarvys.factory.contract.ManifestPlan
import org.json.JSONArray
import org.json.JSONObject

/** Disclosure evidence only, never a permission grant or authority to extend the closed template. */
internal data class FactorySigningScope private constructor(private val canonical: String) {
    fun toJson(): JSONObject = JSONObject(canonical)
    fun disclosure(previous: FactorySigningScope?): List<String> {
        if (previous == null) return listOf("Last-signed scope baseline unavailable (legacy, missing or invalid). Scope expansion cannot be determined; review every effective capability and manifest declaration above.")
        val before = previous.toJson(); val after = toJson()
        val changes = mutableListOf<String>()
        for (field in FIELDS) {
            val old = values(before.getJSONArray(field)); val current = values(after.getJSONArray(field))
            val added = current - old; val removed = old - current
            if (added.isNotEmpty()) changes += "Scope additions ($field): ${added.sorted().joinToString()}"
            if (removed.isNotEmpty()) changes += "Scope removals ($field): ${removed.sorted().joinToString()}"
        }
        return changes.ifEmpty { listOf("Effective scope unchanged from the last signed APK (capabilities, permissions, exported components, features, queries and allowed hosts).") }
    }
    fun anchored(appId: String, fingerprint: String, version: Int, apkSha: String): JSONObject = JSONObject()
        .put("schemaVersion", 1).put("appId", appId).put("certificateSha256", fingerprint)
        .put("versionCode", version).put("apkSha256", apkSha).put("scope", toJson())
    companion object {
        private val FIELDS = listOf("capabilities", "permissions", "exportedComponents", "features", "queries", "allowedHosts")
        private fun values(array: JSONArray): Set<String> = (0 until array.length()).map { array.getString(it) }.toSet()
        fun fromPlan(plan: ManifestPlan): FactorySigningScope = parse(JSONObject()
            .put("capabilities", JSONArray(plan.capabilities)).put("permissions", JSONArray(plan.permissions))
            .put("exportedComponents", JSONArray(plan.exportedComponents)).put("features", JSONArray(plan.features))
            .put("queries", JSONArray(plan.queries)).put("allowedHosts", JSONArray(plan.hosts)))
        private fun parse(json: JSONObject): FactorySigningScope {
            require(json.keys().asSequence().toSet() == FIELDS.toSet())
            val canonical = JSONObject()
            for (field in FIELDS) {
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
            require(canonical.toString().toByteArray(Charsets.UTF_8).size <= 48 * 1024)
            return FactorySigningScope(canonical.toString())
        }
        /** Old v1 records deliberately return unknown, rather than an empty/no-expansion baseline. */
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
