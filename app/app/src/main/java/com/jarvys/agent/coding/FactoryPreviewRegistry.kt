package com.jarvys.agent.coding

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.apkfactory.FactorySpec
import com.jarvys.factory.contract.CapabilityCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Process-local capability. No project paths, mutable buffers, keys or live account state cross into UI. */
internal object FactoryPreviewRegistry {
    private var current: Session? = null
    private var launchToken: String? = null
    @Synchronized fun issue(snapshot: Snapshot, validate: () -> Unit, token: CancellationToken): String {
        current?.revoke()
        val session = Session(snapshot, validate)
        val key = UUID.randomUUID().toString()
        current = session
        launchToken = key
        session.unregister = token.registerCancelAction { session.revoke() }
        session.validate()
        return key
    }
    @Synchronized fun consume(key: String?): Session? {
        if (key == null || key != launchToken) return null
        launchToken = null
        return current?.takeIf { it.isActive() }
    }
    @Synchronized fun status(projectId: String, sha: String, version: Long): JSONObject {
        val session = current ?: error("No active factory preview")
        val metadata = session.snapshot.metadata()
        check(metadata.getString("project_id") == projectId && metadata.getString("apk_sha256") == sha &&
            metadata.getLong("scope_version") == version) { "Preview binding differs from this request" }
        // Caller freshly verified the requested artifact and current project scope.
        // A closed/cancelled run may still expose its bounded final evidence.
        return session.receipt()
    }
    class Snapshot(metadata: JSONObject, config: ByteArray, assets: Map<String, ByteArray>) {
        private val metadataText = metadata.toString()
        private val config = config.copyOf()
        private val assets = assets.mapValues { it.value.copyOf() }
        init {
            require(config.size <= FactorySpec.MAX_SPEC_BYTES)
            require(assets.size <= FactorySpec.MAX_WEB_FILES + 1)
            require(assets.values.all { it.size <= FactorySpec.MAX_FILE_BYTES })
            require(assets.values.sumOf { it.size.toLong() } <= FactorySpec.MAX_WEB_BYTES.toLong() + FactorySpec.MAX_FILE_BYTES)
            require("www/index.html" in assets)
        }
        fun metadata() = JSONObject(metadataText)
        fun configBytes() = config.copyOf()
        fun asset(path: String): ByteArray? = assets[path]?.copyOf()
    }
    class Session(val snapshot: Snapshot, private val checkBinding: () -> Unit) : AutoCloseable {
        @Volatile private var active = true
        @Volatile private var revoked: (() -> Unit)? = null
        internal var unregister: Runnable? = null
        private val observations = linkedMapOf<String, Long>()
        private var saturated = false
        private var webViewVersion: String? = null
        fun validate() {
            check(active) { "Factory preview expired" }
            try { checkBinding() } catch (failure: Exception) { revoke(); throw failure }
            check(active) { "Factory preview expired" }
        }
        fun isActive(): Boolean = try { validate(); true } catch (_: Exception) { false }
        fun setOnRevoked(callback: () -> Unit) { revoked = callback; if (!active) callback() }
        fun revoke() {
            val callback = synchronized(this) { if (!active) return; record("lifecycle", "revoked"); active = false; unregister?.run(); unregister = null; revoked }
            callback?.invoke()
        }
        override fun close() = revoke()
        @Synchronized fun setWebViewVersion(version: String) {
            // Package version only, never arbitrary console text or a WebView user-agent.
            if (version.matches(Regex("[0-9]+(\\.[0-9]+){0,4}")) && version.length <= 64) webViewVersion = version
        }
        @Synchronized fun record(operation: String, outcome: String) {
            if (!active || operation !in OPERATIONS || outcome !in OUTCOMES) return
            val key = "$operation:$outcome"
            observations[key] = (observations[key] ?: 0L).let { if (it == Long.MAX_VALUE) { saturated = true; it } else it + 1 }
        }
        @Synchronized fun receipt(): JSONObject = snapshot.metadata().put("mode", "functional_preview")
            .put("state", if (active) "active" else "revoked").put("webview_version", webViewVersion ?: JSONObject.NULL)
            .put("counts_saturated", saturated).put("observations", JSONArray().apply { observations.forEach { (key, count) ->
                put(JSONObject().put("operation", key.substringBefore(':')).put("outcome", key.substringAfter(':')).put("count", count))
            } }).put("evidence_limit", "Observed shared-runtime events only. No installed-app, permission, persistence or network-absence certification. No console, arguments or private data captured.")
        companion object {
            private val OPERATIONS = CapabilityCatalog.METHODS.keys + setOf("lifecycle", "page", "runtime")
            private val OUTCOMES = setOf("created", "requested", "started", "ready", "finished", "closed", "reset", "revoked", "success", "error", "simulated", "denied", "failed", "loaded")
        }
    }
}
