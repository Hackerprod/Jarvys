package com.jarvys.agent.coding

import com.jarvys.factory.contract.CapabilityCatalog
import com.jarvys.factory.runtime.*
import org.json.JSONArray
import org.json.JSONObject

/** Executes real shared validators and dispatcher on synthetic inputs. Never evaluates project JS. */
internal object FactoryRuntimeContractTests {
    fun run(snapshot: FactoryPreviewRegistry.Snapshot, hostAppId: String, api: Int, targetSdk: Int, validate: () -> Unit): JSONObject {
        val config = FactoryConfig.parsePreview(String(snapshot.configBytes(), Charsets.UTF_8))
        val backend = MemoryBackend()
        val store = BoundedStore(backend)
        val observations = JSONArray()
        fun assertion(operation: String, action: () -> Boolean) {
            validate()
            val passed = try { action() } catch (_: Exception) { false }
            validate()
            observations.put(JSONObject().put("operation", operation).put("outcome", if (passed) "passed" else "failed"))
        }
        fun request(method: String, args: JSONObject) = JSONObject().put("v", 1).put("id", "factory-test")
            .put("method", method).put("args", args).toString()
        fun dispatch(method: String, args: JSONObject): Any? = FactoryDispatcher.dispatch(
            BridgeProtocol.validate(BridgeProtocol.ORIGIN, true, request(method, args), config), config, store,
            FactoryDispatcher.previewMetadata(hostAppId, api, targetSdk), FactoryDispatcher.simulatedEffects())
        try {
            val ordered = listOf("runtime.info", "storage.set", "storage.get", "storage.list", "storage.remove", "export.text", "share.text", "share.file", "clipboard.write", "haptics.perform", "device.info",
                "documents.open", "documents.create", "documents.read", "documents.write", "documents.close", "documents.cancel", "photos.pick", "photos.capture", "audio.play", "browser.open")
            check(ordered.toSet() == CapabilityCatalog.METHODS.keys) { "Synthetic contract cases must cover the compiled catalog" }
            for (method in ordered) {
                val args = when (method) {
                    "browser.open" -> JSONObject().put("url", "https://example.com/review?fixture=1")
                    "storage.set" -> JSONObject().put("key", "factory-test").put("value", "synthetic")
                    "storage.get", "storage.remove" -> JSONObject().put("key", "factory-test")
                    "export.text" -> JSONObject().put("filename", "factory-test.txt").put("text", "synthetic")
                    "share.file" -> JSONObject().put("handle", "0".repeat(64)).put("filename", "file.bin").put("mimeType", "application/octet-stream")
                    "share.text", "clipboard.write" -> JSONObject().put("text", "synthetic")
                    "haptics.perform" -> JSONObject().put("kind", "tap")
                    "documents.open" -> JSONObject().put("mimeType", "application/octet-stream")
                    "documents.create" -> JSONObject().put("mimeType", "application/octet-stream").put("filename", "synthetic.bin")
                    "documents.read" -> JSONObject().put("handle", "0".repeat(64)).put("offset", 0).put("length", 1)
                    "documents.write" -> JSONObject().put("handle", "0".repeat(64)).put("offset", 0).put("data", "AA==")
                    "documents.close", "audio.play" -> JSONObject().put("handle", "0".repeat(64))
                    else -> JSONObject()
                }
                val capability = CapabilityCatalog.METHODS.getValue(method).capability
                if (capability != null && (capability !in config.capabilities || ((method == "share.file" || (capability == "photos" || capability == "audio")) && "documents" !in config.capabilities))) {
                    assertion("$method:undeclared_denied") {
                        try { dispatch(method, args); false } catch (failure: FactoryException) { failure.code == "CAPABILITY_DENIED" }
                    }
                } else if (capability == "browser" || capability == "documents" || (capability == "photos" || capability == "audio") || method == "share.file") {
                    assertion("$method:preview_unavailable") {
                        try { dispatch(method, args); false } catch (failure: FactoryException) { failure.code == "UNAVAILABLE" }
                    }
                } else assertion("$method:shared_handler") {
                    val result = dispatch(method, args)
                    when (method) {
                        "runtime.info" -> (result as JSONObject).getString("mode") == "preview" && result.getString("declaredAppId") == config.appId
                        "storage.get" -> result == "synthetic"
                        "storage.set" -> store.get("factory-test") == "synthetic"
                        "storage.list" -> (result as JSONArray).length() == 1 && result.getString(0) == "factory-test"
                        "storage.remove" -> store.get("factory-test") == null
                        "device.info" -> (result as JSONObject).getString("hostAppId") == hostAppId && result.getString("declaredAppId") == config.appId
                        else -> (result as JSONObject).getBoolean("simulated") && !result.getBoolean("performed")
                    }
                }
            }
            assertion("bridge:untrusted_origin_denied") {
                try { BridgeProtocol.validate("https://example.invalid", true, request("runtime.info", JSONObject()), config); false }
                catch (failure: FactoryException) { failure.code == "UNTRUSTED_SOURCE" }
            }
            assertion("bridge:subframe_denied") {
                try { BridgeProtocol.validate(BridgeProtocol.ORIGIN, false, request("runtime.info", JSONObject()), config); false }
                catch (failure: FactoryException) { failure.code == "UNTRUSTED_SOURCE" }
            }
            assertion("storage:isolated_session") {
                store.set("isolation", "synthetic")
                BoundedStore(MemoryBackend()).get("isolation") == null
            }
        } finally { backend.clear() }
        val passed = (0 until observations.length()).all { observations.getJSONObject(it).getString("outcome") == "passed" }
        return snapshot.metadata().put("mode", "shared_core_test").put("state", if (passed) "passed" else "failed")
            .put("observations", observations).put("test_inputs", "fixed_synthetic")
            .put("evidence_limit", "Executed shared validators and native handler dispatch with isolated RAM storage and simulated effects. Project JavaScript, WebView rendering, installed APK identity, permissions, system effects and durable persistence were not tested. No network-absence claim.")
    }
}
