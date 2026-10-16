package com.jarvys.agent.connectors

import android.content.SharedPreferences
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Only bounded local decision/provenance metadata belongs here; never credentials or action payloads. */
internal class PersistedActionPolicies(private val preferences: SharedPreferences, scope: String) {
    private class Authority {
        val lock = Any()
        var healthy = true
        val observations = mutableMapOf<String, Pair<String, String>>()
    }

    private data class Raw(val present: Boolean, val value: Any?)
    private data class Record(val revision: Long, val origin: ActionPolicyOrigin, val policyFingerprint: String)
    private val authority = synchronized(AUTHORITIES) { AUTHORITIES.getOrPut(scope) { Authority() } }
    val lock: Any get() = authority.lock

    fun savedPolicy(connectorId: String, operationName: String): AutonomyPolicy? = synchronized(lock) {
        requireHealthy()
        decodePolicy(raw(preferences.all, policyKey(connectorId, operationName)))
    }

    fun snapshot(connectorId: String, operationName: String, fallbackPolicy: AutonomyPolicy? = null): ActionPolicySnapshot = synchronized(lock) {
        snapshotLocked(connectorId, operationName, fallbackPolicy)
    }

    private fun snapshotLocked(id: String, operation: String, fallback: AutonomyPolicy?): ActionPolicySnapshot {
        requireHealthy()
        ActionPolicyDefaults.descriptor(id, operation)
        val key = metadataKey(id, operation)
        var values = preferences.all
        val policyRaw = raw(values, policyKey(id, operation))
        val saved = decodePolicy(policyRaw)
        val oldMetadata = raw(values, key)
        val decoded = decodeRecord(oldMetadata, id, operation)
        val fingerprint = fingerprint(policyRaw)
        val expectedOrigin = when {
            !policyRaw.present -> ActionPolicyOrigin.CATALOG_DEFAULT
            validPolicy(policyRaw) == null -> ActionPolicyOrigin.INVALID
            else -> ActionPolicyOrigin.LEGACY_UNKNOWN
        }
        val currentRecord = decoded?.takeIf {
            it.policyFingerprint == fingerprint && when (expectedOrigin) {
                ActionPolicyOrigin.CATALOG_DEFAULT -> it.origin == ActionPolicyOrigin.CATALOG_DEFAULT
                ActionPolicyOrigin.INVALID -> it.origin == ActionPolicyOrigin.INVALID
                else -> it.origin == ActionPolicyOrigin.USER || it.origin == ActionPolicyOrigin.LEGACY_UNKNOWN
            }
        } ?: Record(nextRevision(decoded?.revision ?: 0L), expectedOrigin, fingerprint).also {
            commitOrRollback(mapOf(key to Raw(true, encodeRecord(id, operation, it))))
            values = preferences.all
        }
        val rawToken = fingerprint(policyRaw) + ":" + fingerprint(raw(values, key))
        val seen = authority.observations[key]
        val observation = seen?.takeIf { it.first == rawToken }?.second ?: UUID.randomUUID().toString().also {
            authority.observations[key] = rawToken to it
        }
        val origin = if (!policyRaw.present && fallback != null) ActionPolicyOrigin.INHERITED_SERVER_DEFAULT else currentRecord.origin
        val defaultPolicy = ActionPolicyDefaults.selectedDefaultPolicy(preferences)
        return ActionPolicySnapshot(id, operation, saved ?: fallback ?: defaultPolicy, currentRecord.revision,
            origin, authority, "$rawToken:$observation", fallback)
    }

    fun setPolicy(id: String, operation: String, policy: AutonomyPolicy) = synchronized(lock) {
        requireHealthy()
        ActionPolicyDefaults.descriptor(id, operation)
        val values = preferences.all
        val key = metadataKey(id, operation)
        val old = decodeRecord(raw(values, key), id, operation)
        val value = Raw(true, policy.name)
        val next = Record(nextRevision(old?.revision ?: 0L), ActionPolicyOrigin.USER, fingerprint(value))
        commitOrRollback(mapOf(policyKey(id, operation) to value, key to Raw(true, encodeRecord(id, operation, next))))
        // Record the new authoritative state now, so restoring an old complete record cannot revive an old snapshot.
        snapshotLocked(id, operation, null)
        Unit
    }

    fun snapshotMatching(anchor: ActionPolicySnapshot): ActionPolicySnapshot = synchronized(lock) {
        requireHealthy()
        check(anchor.owner === authority) { "Action snapshot belongs to a different policy store" }
        snapshotLocked(anchor.connectorId, anchor.operationName, anchor.fallbackPolicy).also {
            check(anchor.sameAuthorizationAs(it)) { "Action policy changed; prepare and authorize a new action" }
        }
    }

    fun setPolicyAndSnapshot(id: String, operation: String, policy: AutonomyPolicy): ActionPolicySnapshot = synchronized(lock) {
        setPolicy(id, operation, policy)
        snapshotLocked(id, operation, null)
    }

    fun admit(snapshot: ActionPolicySnapshot, approvedAsk: Boolean, validate: () -> Unit) = synchronized(lock) {
        requireHealthy()
        check(snapshot.owner === authority) { "Action snapshot belongs to a different policy store" }
        snapshot.requireUnconsumed()
        val current = snapshotLocked(snapshot.connectorId, snapshot.operationName, snapshot.fallbackPolicy)
        check(snapshot.sameAuthorizationAs(current)) { "Action policy changed; prepare and authorize a new action" }
        snapshot.requireDecision(approvedAsk)
        // This callback is only a short, non-blocking capability/identity/STOP validation. Never dispatch here.
        validate()
        // synchronized locks are reentrant; an accidental setter inside validate must not be missed.
        requireHealthy()
        val afterValidation = snapshotLocked(snapshot.connectorId, snapshot.operationName, snapshot.fallbackPolicy)
        check(snapshot.sameAuthorizationAs(afterValidation)) { "Action policy changed during admission" }
        snapshot.consume()
    }

    fun clearPolicies(connectorId: String) = synchronized(lock) {
        requireHealthy()
        val values = preferences.all
        val prefix = "autonomy_${connectorId}_"
        val keys = values.keys.filterTo(linkedSetOf()) { it.startsWith(prefix) }
        values.forEach { (key, value) ->
            if (key.startsWith(METADATA_PREFIX) && value is String) {
                val owner = runCatching { JSONObject(value).optString("connectorId") }.getOrNull()
                if (owner == connectorId) keys += key
            }
        }
        if (keys.isNotEmpty()) commitOrRollback(keys.associateWith { Raw(false, null) })
        val observationPrefix = METADATA_PREFIX + hex(connectorId) + "_"
        authority.observations.keys.removeAll { it.startsWith(observationPrefix) }
    }

    private fun requireHealthy() = check(authority.healthy) {
        "Action policy persistence is unverified; restart and review saved settings before another action"
    }

    private fun commitOrRollback(changes: Map<String, Raw>) {
        val values = preferences.all
        val previous = changes.keys.associateWith { raw(values, it) }
        val success = runCatching { write(changes) }.getOrDefault(false)
        if (success) return
        // A failed Android commit can already have changed memory. Restore exact value types and presence.
        authority.observations.clear()
        val restored = runCatching { write(previous) }.getOrDefault(false)
        if (!restored) authority.healthy = false
        throw IllegalStateException(if (restored) "Could not persist action policy; previous state was restored"
            else "Could not persist or restore action policy; further actions are blocked")
    }

    private fun write(changes: Map<String, Raw>): Boolean {
        val editor = preferences.edit()
        changes.forEach { (key, raw) ->
            if (!raw.present) editor.remove(key) else when (val value = raw.value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    check(value.all { it is String }) { "Invalid policy preference value type" }
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                else -> error("Invalid policy preference value type")
            }
        }
        return editor.commit()
    }

    private fun decodeRecord(raw: Raw, id: String, operation: String): Record? = runCatching {
        if (!raw.present || raw.value !is String) return@runCatching null
        val json = JSONObject(raw.value)
        check(json.opt("schemaVersion") is Int && json.getInt("schemaVersion") == ActionPolicyDefaults.METADATA_VERSION)
        check(listOf("connectorId", "operationName", "transitionId", "policyFingerprint", "origin").all { json.opt(it) is String })
        check(json.getString("connectorId") == id && json.getString("operationName") == operation)
        val revision = json.getLong("revision")
        check(revision > 0 && (json.opt("revision") is Int || json.opt("revision") is Long))
        check(json.getString("transitionId").matches(UUID_PATTERN))
        val fingerprint = json.getString("policyFingerprint")
        check(fingerprint.matches(DIGEST_PATTERN))
        Record(revision, ActionPolicyOrigin.valueOf(json.getString("origin")), fingerprint)
    }.getOrNull()

    private fun encodeRecord(id: String, operation: String, record: Record): String = JSONObject()
        .put("schemaVersion", ActionPolicyDefaults.METADATA_VERSION).put("connectorId", id).put("operationName", operation)
        .put("revision", record.revision).put("origin", record.origin.name).put("policyFingerprint", record.policyFingerprint)
        .put("transitionId", UUID.randomUUID().toString()).toString()

    private fun nextRevision(previous: Long): Long {
        check(previous in 0 until Long.MAX_VALUE) { "Action policy revision is exhausted" }
        return previous + 1
    }

    companion object {
        private val AUTHORITIES = mutableMapOf<String, Authority>()
        private val UUID_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val DIGEST_PATTERN = Regex("[a-f0-9]{64}")
        internal const val METADATA_PREFIX = "ux44_action_policy_v1_"
        internal fun metadataKey(id: String, operation: String) = METADATA_PREFIX + hex(id) + "_" + hex(operation)
        internal fun policyKey(id: String, operation: String) = "autonomy_${id}_$operation"
        private fun hex(value: String) = value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun raw(values: Map<String, *>, key: String) = Raw(values.containsKey(key), values[key])
        private fun validPolicy(raw: Raw): AutonomyPolicy? = (raw.value as? String)?.let {
            runCatching { AutonomyPolicy.valueOf(it) }.getOrNull()
        }
        private fun decodePolicy(raw: Raw): AutonomyPolicy? = if (!raw.present) null else validPolicy(raw) ?: AutonomyPolicy.ASK
        private fun fingerprint(raw: Raw): String {
            val value = if (!raw.present) "absent" else when (val item = raw.value) {
                is Set<*> -> "set:" + item.map { it.toString() }.sorted().joinToString("") { "${it.length}:$it" }
                null -> "present:null"
                else -> item.javaClass.name + ":" + item.toString()
            }
            return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
