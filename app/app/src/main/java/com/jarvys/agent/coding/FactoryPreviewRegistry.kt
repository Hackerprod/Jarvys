package com.jarvys.agent.coding

import com.jarvys.agent.CancellationToken
import com.jarvys.agent.apkfactory.FactorySpec
import com.jarvys.factory.contract.CapabilityCatalog
import com.jarvys.factory.runtime.FactoryUiAssets
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
        session.attachCancellation(token.registerCancelAction { session.revoke() })
        session.validate()
        return key
    }
    @Synchronized fun consume(key: String?): Session? {
        if (key == null || key != launchToken) return null
        launchToken = null
        return current?.takeIf { it.isActive() }
    }
    @Synchronized fun status(expected: JSONObject): JSONObject {
        val session = current ?: error("No factory preview receipt")
        val metadata = session.snapshot.metadata()
        check(BINDING_FIELDS.all { field ->
            if (!metadata.has(field) || !expected.has(field)) false
            else if (field in NUMERIC_BINDING_FIELDS) {
                val actual = metadata.get(field)
                val requested = expected.get(field)
                (actual is Int || actual is Long) && (requested is Int || requested is Long) &&
                    (actual as Number).toLong() == (requested as Number).toLong()
            } else metadata.get(field) is String && expected.get(field) is String && metadata.getString(field) == expected.getString(field)
        }) {
            "Preview binding differs from this request"
        }
        // Caller freshly verified the requested artifact and current project scope.
        // A closed/cancelled run may still expose its bounded final evidence.
        return session.receipt()
    }
    private val NUMERIC_BINDING_FIELDS = setOf("scope_version", "version_code")
    private val BINDING_FIELDS = setOf("project_id", "project_identity_sha256", "scope_version", "build_id", "apk_sha256", "project_sha256", "template_sha256", "app_id", "version_code")
    class Snapshot(metadata: JSONObject, config: ByteArray, assets: Map<String, ByteArray>,
                   budgets: com.jarvys.factory.contract.ResourceBudgets.Packaging = com.jarvys.factory.contract.ResourceBudgets.legacy().packaging) {
        private val metadataText = metadata.toString()
        private val config = config.also { require(it.size <= FactorySpec.MAX_SPEC_BYTES) }.copyOf()
        private val assets = assets.also { candidate ->
            // Fail before defensive copies, then validate retained bytes again below.
            val web = candidate.filterKeys { it.startsWith("www/") }
            require(web.size <= budgets.sourceFiles)
            require(web.values.all { it.size.toLong() <= budgets.singleSourceBytes })
            require(web.values.sumOf { it.size.toLong() } <= budgets.totalSourceBytes)
            require(candidate.filterKeys { !it.startsWith("www/") }.values.all { it.size <= com.jarvys.agent.apkfactory.TemplateApk.MAX_ENTRY_BYTES })
        }.mapValues { it.value.copyOf() }
        init {
            // Validate the retained copies, not caller-owned mutable byte arrays.
            val config = this.config
            val assets = this.assets
            require(config.size <= FactorySpec.MAX_SPEC_BYTES)
            require(assets.keys.all { it == "factory-sdk.js" || it.startsWith("www/") || FactoryUiAssets.contains(it) })
            assets.keys.forEach { FactorySpec.relativePath(it) }
            val web = assets.filterKeys { it.startsWith("www/") }
            require(web.size <= budgets.sourceFiles)
            require(web.values.all { it.size.toLong() <= budgets.singleSourceBytes })
            require(assets.filterKeys { !it.startsWith("www/") }.values.all { it.size <= com.jarvys.agent.apkfactory.TemplateApk.MAX_ENTRY_BYTES })
            require(web.values.sumOf { it.size.toLong() } <= budgets.totalSourceBytes)
            require("www/index.html" in assets)
            FactoryUiAssets.verifyComplete(assets.filterKeys { FactoryUiAssets.isNamespace(it) })
        }
        fun metadata() = JSONObject(metadataText)
        fun configBytes() = config.copyOf()
        fun asset(path: String): ByteArray? = assets[path]?.copyOf()
    }
    class Session(val snapshot: Snapshot, private val checkBinding: () -> Unit) : AutoCloseable {
        @Volatile private var active = true
        private var state = "launch_requested"
        @Volatile private var revoked: (() -> Unit)? = null
        private var unregister: Runnable? = null
        internal fun attachCancellation(unregister: Runnable) {
            synchronized(this) {
                if (active) this.unregister = unregister else unregister.run()
            }
        }
        private val observations = linkedMapOf<String, Long>()
        private data class ValidationEvidence(val code: String, val message: String, val count: Long)
        private val validationErrors = linkedMapOf<String, ValidationEvidence>()
        private var saturated = false
        private var webViewVersion: String? = null
        fun validate() {
            check(active) { "Factory preview expired" }
            try { checkBinding() } catch (failure: Exception) { revoke(); throw failure }
            check(active) { "Factory preview expired" }
        }
        /** Only use after full authority validation; safe under the shared RAM lifecycle lock. */
        fun isOpen(): Boolean = active
        fun isActive(): Boolean = try { validate(); true } catch (_: Exception) { false }
        fun setOnRevoked(callback: () -> Unit) {
            val notify = synchronized(this) { if (active) revoked = callback; state == "revoked" }
            if (notify) callback()
        }
        private fun terminate(outcome: String) {
            val callback = synchronized(this) {
                if (!active) return
                record("lifecycle", outcome)
                state = outcome
                active = false
                unregister?.run()
                unregister = null
                revoked.also { revoked = null }
            }
            if (outcome == "revoked") callback?.invoke()
        }
        fun revoke() = terminate("revoked")
        fun fail() = terminate("failed")
        override fun close() = terminate("closed")
        @Synchronized fun setWebViewVersion(version: String) {
            // Package version only, never arbitrary console text or a WebView user-agent.
            if (version.matches(Regex("[0-9]+(\\.[0-9]+){0,4}")) && version.length <= 64) webViewVersion = version
        }
        @Synchronized fun record(operation: String, outcome: String) {
            if (operation !in OPERATIONS || outcome !in OUTCOMES) return
            if (!active && !(operation == "runtime" && outcome == "cleanup_pending")) return
            if (operation == "lifecycle" && outcome in setOf("created", "started")) state = outcome
            val key = "$operation:$outcome"
            observations[key] = (observations[key] ?: 0L).let { if (it == Long.MAX_VALUE) { saturated = true; it } else it + 1 }
        }
        /** Retain only fixed validator diagnostics for catalog methods, never request contents. */
        @Synchronized fun recordValidationFailure(method: String, code: String, message: String) {
            if (!active || method !in CapabilityCatalog.METHODS || code != "INVALID_ARGUMENT") return
            val previous = validationErrors[method]
            val count = if (previous == null) 1L else if (previous.count == Long.MAX_VALUE) {
                saturated = true
                Long.MAX_VALUE
            } else previous.count + 1
            validationErrors[method] = ValidationEvidence(code, message, count)
        }
        @Synchronized fun receipt(): JSONObject = snapshot.metadata().put("mode", "functional_preview")
            .put("state", state).put("webview_version", webViewVersion ?: JSONObject.NULL)
            .put("counts_saturated", saturated).put("observations", JSONArray().apply { observations.forEach { (key, count) ->
                put(JSONObject().put("operation", key.substringBefore(':')).put("outcome", key.substringAfter(':')).put("count", count))
            } }).put("validation_errors", JSONArray().apply { validationErrors.forEach { (method, evidence) ->
                put(JSONObject().put("method", method).put("code", evidence.code).put("message", evidence.message).put("count", evidence.count))
            } }).put("evidence_limit", "Observed shared-runtime events and validator errors only. No installed-app, permission, persistence or network-absence certification. No console, arguments or private data captured.")
        companion object {
            private val OPERATIONS = CapabilityCatalog.METHODS.keys + setOf("lifecycle", "page", "runtime")
            private val OUTCOMES = setOf("created", "requested", "started", "ready", "finished", "closed", "reset", "revoked", "success", "error", "simulated", "denied", "failed", "loaded", "cleanup_pending")
        }
    }
}
