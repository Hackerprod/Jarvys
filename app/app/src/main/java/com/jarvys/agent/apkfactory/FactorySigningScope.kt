package com.jarvys.agent.apkfactory

import com.jarvys.factory.contract.BluetoothPolicy
import com.jarvys.factory.runtime.BluetoothPolicyJson
import com.jarvys.factory.contract.ManifestPlan
import com.jarvys.factory.contract.ResourceBudgets
import com.jarvys.factory.contract.IncomingSharePolicy
import com.jarvys.factory.runtime.IncomingSharePolicyJson
import com.jarvys.factory.contract.NotificationPolicy
import com.jarvys.factory.runtime.NotificationPolicyJson
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
        val oldIncoming = incomingShare(before); val currentIncoming = incomingShare(after)
        if (oldIncoming != currentIncoming) changes += "Incoming sharing changes: ${incomingDescription(oldIncoming)} -> ${incomingDescription(currentIncoming)}"
        val oldNotifications = notifications(before); val currentNotifications = notifications(after)
        if (oldNotifications != currentNotifications) changes += "Notification scheduling changes: scheduling=${oldNotifications.scheduling}, exactAlarms=${oldNotifications.exactAlarms} -> scheduling=${currentNotifications.scheduling}, exactAlarms=${currentNotifications.exactAlarms}"
        val oldBluetooth = bluetooth(before); val currentBluetooth = bluetooth(after)
        if (oldBluetooth != currentBluetooth) changes += "Bluetooth scan privacy changes: ${oldBluetooth?.scanPrivacy ?: "disabled"} -> ${currentBluetooth?.scanPrivacy ?: "disabled"}"
        if (before.has("privateComponents") && after.has("privateComponents")) {
            val old = values(before.getJSONArray("privateComponents")); val current = values(after.getJSONArray("privateComponents"))
            if (old != current) changes += "Private native components changed: ${old.sorted().joinToString()} -> ${current.sorted().joinToString()}"
        }
        val oldResources = resourceValues(previous.resources()); val currentResources = resourceValues(resources())
        for ((field, current) in currentResources) {
            val old = oldResources.getValue(field)
            if (old != current) changes += "Resource capacity changes ($field): $old -> $current"
        }
        return changes.ifEmpty { listOf("Effective scope unchanged from the last signed APK (capabilities, permissions, exported components, features, queries, query actions, allowed hosts, incoming sharing, notification scheduling and resource capacities).") }
    }
    fun anchored(appId: String, fingerprint: String, version: Int, apkSha: String): JSONObject = JSONObject()
        .put("schemaVersion", 1).put("appId", appId).put("certificateSha256", fingerprint)
        .put("versionCode", version).put("apkSha256", apkSha).put("scope", toJson())
    companion object {
        private val LEGACY_FIELDS = listOf("capabilities", "permissions", "exportedComponents", "features", "queries", "allowedHosts")
        private val V3_FIELDS = LEGACY_FIELDS + listOf("queryActions", "resourceBudgets")
        private val INCOMING_FIELDS = V3_FIELDS + "incomingShare"
        private val V4_FIELDS = INCOMING_FIELDS + "notifications"
        private val CURRENT_FIELDS = V4_FIELDS + listOf("bluetooth", "privateComponents")
        // Complete writer profiles, not arbitrary partial project declarations. Keep these frozen:
        // adding a runtime default must never change bytes authenticated by a historical receipt.
        private val ORIGINAL_RESOURCE_SECTIONS = setOf("version", "storage", "database", "documents", "audio", "sensors", "packaging")
        private val SERVICE_RESOURCE_SECTIONS = ORIGINAL_RESOURCE_SECTIONS + setOf("secrets", "notifications", "incomingShare")
        private val HARDWARE_RESOURCE_SECTIONS = SERVICE_RESOURCE_SECTIONS + setOf("nfc", "bluetooth", "widgets", "jobs")
        private val CURRENT_RESOURCE_SECTIONS = HARDWARE_RESOURCE_SECTIONS + "fileShare"
        private val HISTORICAL_RESOURCE_PROFILES = setOf(ORIGINAL_RESOURCE_SECTIONS, SERVICE_RESOURCE_SECTIONS, HARDWARE_RESOURCE_SECTIONS)
        private val HISTORICAL_AUDIO_FIELDS = setOf("sourceBytes", "durationMs")
        private fun bluetooth(json: JSONObject): BluetoothPolicy? =
            if (json.has("bluetooth") && !json.isNull("bluetooth")) BluetoothPolicyJson.parse(json.getJSONObject("bluetooth")) else null
        private fun notifications(json: JSONObject): NotificationPolicy =
            if (json.has("notifications")) NotificationPolicyJson.parse(json.getJSONObject("notifications")) else NotificationPolicy.disabled()
        private fun incomingShare(json: JSONObject): IncomingSharePolicy? =
            if (json.has("incomingShare") && !json.isNull("incomingShare")) IncomingSharePolicyJson.parse(json.getJSONObject("incomingShare")) else null
        private fun incomingDescription(policy: IncomingSharePolicy?): String =
            policy?.let { "MIME patterns [${it.mimeTypes.joinToString()}], ACTION_SEND, ACTION_SEND_MULTIPLE=${it.multiple}" } ?: "disabled"
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
            .put("queryActions", JSONArray(plan.queryActions)).put("resourceBudgets", ResourceBudgetsJson.toJson(resources))
            .put("incomingShare", plan.incomingShare?.let { IncomingSharePolicyJson.toJson(it) } ?: JSONObject.NULL)
            .put("notifications", NotificationPolicyJson.toJson(plan.notifications ?: NotificationPolicy.disabled()))
            .put("bluetooth", plan.bluetooth?.let { BluetoothPolicyJson.toJson(it) } ?: JSONObject.NULL)
            .put("privateComponents", JSONArray(plan.privateComponents)))
        private fun parse(json: JSONObject): FactorySigningScope {
            val fields = json.keys().asSequence().toSet()
            val legacy = fields == LEGACY_FIELDS.toSet()
            require(legacy || fields == V3_FIELDS.toSet() || fields == INCOMING_FIELDS.toSet() || fields == V4_FIELDS.toSet() || fields == CURRENT_FIELDS.toSet())
            val canonical = JSONObject()
            // Never add fields to old records: their canonical bytes authenticate retained history.
            for (field in (if (legacy) LEGACY_FIELDS else LEGACY_FIELDS + "queryActions") + listOfNotNull("privateComponents".takeIf { json.has(it) })) {
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
                val declaredSections = declared.keys().asSequence().toSet()
                val historicalResources = declaredSections in HISTORICAL_RESOURCE_PROFILES
                require(historicalResources || declaredSections == CURRENT_RESOURCE_SECTIONS)
                // Only complete known historical profiles may omit later fields. Never discard a
                // declared field or serialize a default into the already-authenticated snapshot.
                val retained = JSONObject()
                for (section in resolved.keys().asSequence()) if (section in declaredSections) {
                    if (section == "version") {
                        retained.put(section, resolved.get(section))
                        continue
                    }
                    val resolvedSection = resolved.getJSONObject(section)
                    val expectedFields = if (historicalResources && section == "audio") HISTORICAL_AUDIO_FIELDS
                        else resolvedSection.keys().asSequence().toSet()
                    require(declared.getJSONObject(section).keys().asSequence().toSet() == expectedFields)
                    val retainedSection = JSONObject()
                    for (field in resolvedSection.keys().asSequence()) if (field in expectedFields) {
                        retainedSection.put(field, resolvedSection.get(field))
                    }
                    retained.put(section, retainedSection)
                }
                canonical.put("resourceBudgets", retained)
            }
            val incoming = incomingShare(json)
            IncomingSharePolicy.validateSelection(values(canonical.getJSONArray("capabilities")), incoming)
            // Do not append this field to old authenticated snapshots, even when disabled.
            if (json.has("incomingShare")) canonical.put("incomingShare", incoming?.let { IncomingSharePolicyJson.toJson(it) } ?: JSONObject.NULL)
            val notificationPolicy = notifications(json)
            if (notificationPolicy.scheduling) NotificationPolicy.validateSelection(values(canonical.getJSONArray("capabilities")), notificationPolicy)
            // Old snapshots keep their exact field set; absence means scheduling was disabled.
            if (json.has("notifications")) canonical.put("notifications", NotificationPolicyJson.toJson(notificationPolicy))
            val bluetoothPolicy = bluetooth(json)
            BluetoothPolicy.validateSelection(values(canonical.getJSONArray("capabilities")), bluetoothPolicy)
            // Absence in historical signing scopes must remain absent; do not change authenticated bytes.
            if (json.has("bluetooth")) canonical.put("bluetooth", bluetoothPolicy?.let { BluetoothPolicyJson.toJson(it) } ?: JSONObject.NULL)
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
