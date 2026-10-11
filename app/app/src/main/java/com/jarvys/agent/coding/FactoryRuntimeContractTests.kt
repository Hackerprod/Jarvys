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
        val database = PrivateDatabase.preview()
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
            FactoryDispatcher.previewMetadata(hostAppId, api, targetSdk), FactoryDispatcher.simulatedEffects(),
            FactoryDispatcher.Database { method, args -> database.execute(method, args, database.ticket()) { validate(); true } })
        try {
            val fixtures = syntheticFixtures()
            val methods = fixtures.map { it.method }
            check(methods.size == methods.toSet().size && methods.toSet() == CapabilityCatalog.METHODS.keys) {
                "Synthetic contract cases must explicitly cover the compiled catalog exactly once"
            }
            for (fixture in fixtures) {
                val method = fixture.method
                val args = JSONObject(fixture.arguments)
                val capability = CapabilityCatalog.METHODS.getValue(method).capability
                val denied = (capability != null && capability !in config.capabilities) ||
                    fixture.dependencies.any { it !in config.capabilities }
                if (denied) {
                    assertion("$method:undeclared_denied") {
                        try { dispatch(method, args); false } catch (failure: FactoryException) { failure.code == "CAPABILITY_DENIED" }
                    }
                } else if (fixture.nativeOnly) {
                    assertion("$method:preview_unavailable") {
                        try { dispatch(method, args); false } catch (failure: FactoryException) { failure.code == "UNAVAILABLE" }
                    }
                } else assertion("$method:shared_handler") {
                    val result = dispatch(method, args)
                    when (method) {
                        "database.info" -> (result as JSONObject).getInt("version") == 0 && !result.getBoolean("persistent")
                        "database.migrate" -> (result as JSONObject).getInt("version") == 1 && result.getBoolean("committed")
                        "database.transact" -> (result as JSONObject).getJSONArray("changes").getInt(0) == 1
                        "database.select" -> (result as JSONObject).getJSONArray("rows").getJSONObject(0).getString("text") == "synthetic"
                        "database.close" -> (result as JSONObject).getBoolean("closed") && !result.getBoolean("dataRetained")
                        "database.cancel" -> (result as JSONObject).getBoolean("cancelRequested") && !result.getBoolean("rollbackConfirmed")
                        "runtime.info" -> (result as JSONObject).getString("mode") == "preview" && result.getString("declaredAppId") == config.appId
                        "storage.get" -> result == "synthetic"
                        "storage.set" -> store.get("factory-test") == "synthetic"
                        "storage.list" -> (result as JSONArray).length() == 1 && result.getString(0) == "factory-test"
                        "storage.listPage" -> (result as JSONObject).getJSONArray("keys").let { keys ->
                            keys.length() == 1 && keys.getString(0) == "factory-test" &&
                                !result.getBoolean("hasMore") && result.isNull("nextAfterKey") &&
                                !result.getBoolean("snapshotAcrossPages")
                        }
                        "storage.remove" -> store.get("factory-test") == null
                        "device.info" -> (result as JSONObject).getString("hostAppId") == hostAppId && result.getString("declaredAppId") == config.appId
                        "export.text", "share.text", "clipboard.write", "haptics.perform" ->
                            (result as JSONObject).getBoolean("simulated") && !result.getBoolean("performed") &&
                                result.getString("operation") == method
                        else -> error("Missing shared handler assertion for $method")
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
        } finally { database.cancel(); database.close(); backend.clear() }
        val passed = (0 until observations.length()).all { observations.getJSONObject(it).getString("outcome") == "passed" }
        return snapshot.metadata().put("mode", "shared_core_test").put("state", if (passed) "passed" else "failed")
            .put("observations", observations).put("test_inputs", "fixed_synthetic")
            .put("evidence_limit", "Executed shared validators and native handler dispatch with isolated RAM storage, ephemeral SQLite and simulated effects. Project JavaScript, WebView rendering, installed APK identity, permissions, system effects and durable persistence were not tested. No network-absence claim.")
    }

    private data class Fixture(
        val method: String,
        val arguments: String,
        val nativeOnly: Boolean,
        val dependencies: Set<String> = emptySet()
    )

    /** Intentionally explicit: a new catalog entry must supply valid input and an expected path.
     * Native tokens and destinations are synthetic; only validators and simulatedEffects see them.
     * Keep storage and database mutations ordered so reads prove the actual shared handlers ran.
     */
    private fun syntheticFixtures(): List<Fixture> {
        val handle = "0".repeat(64)
        val uuid = "00000000-0000-0000-0000-000000000000"
        val secretId = "sec_" + "0".repeat(32)
        val http = """{"url":"https://example.invalid/fixture","method":"GET"}"""
        val notification = """{"id":1,"channelId":"synthetic","title":"Synthetic fixture","body":"Fixture"}"""
        val widget = """{"root":{"type":"text","text":"Fixture"}}"""
        val secretScope = """{"origin":"https://example.invalid","methods":["GET"],"header":"authorization","prefix":"Bearer "}"""
        fun shared(method: String, arguments: String) = Fixture(method, arguments, false)
        fun native(method: String, arguments: String, vararg dependencies: String) =
            Fixture(method, arguments, true, dependencies.toSet())
        return listOf(
            shared("runtime.info", "{}"),
            shared("storage.set", """{"key":"factory-test","value":"synthetic"}"""),
            shared("storage.get", """{"key":"factory-test"}"""),
            shared("storage.list", "{}"),
            shared("storage.listPage", """{"limit":1}"""),
            shared("storage.remove", """{"key":"factory-test"}"""),
            native("storage.backupExport", """{"version":1,"keys":["settings"],"filename":"backup.json"}""", "documents"),
            native("storage.backupImport", """{"version":1,"keys":["settings"],"handle":"$handle","mode":"insertOnly"}""", "documents"),
            shared("export.text", """{"filename":"factory-test.txt","text":"synthetic"}"""),
            shared("share.text", """{"text":"synthetic"}"""),
            native("share.file", """{"handle":"$handle","filename":"file.bin","mimeType":"application/octet-stream"}""", "documents"),
            shared("clipboard.write", """{"text":"synthetic"}"""),
            shared("haptics.perform", """{"kind":"tap"}"""),
            shared("device.info", "{}"),
            native("documents.open", """{"mimeType":"application/octet-stream"}"""),
            native("documents.create", """{"mimeType":"application/octet-stream","filename":"synthetic.bin"}"""),
            native("documents.read", """{"handle":"$handle","offset":0,"length":1}"""),
            native("documents.write", """{"handle":"$handle","offset":0,"data":"AA=="}"""),
            native("documents.close", """{"handle":"$handle"}"""),
            native("documents.cancel", "{}"),
            native("photos.pick", "{}", "documents"),
            native("photos.capture", "{}", "documents"),
            native("audio.play", """{"handle":"$handle"}""", "documents"),
            native("browser.open", """{"url":"https://example.invalid/review?fixture=1"}"""),
            native("maps.open", """{"query":"Synthetic map fixture"}"""),
            native("phone.dial", """{"number":"+15550100"}"""),
            native("email.compose", """{"to":"fixture@example.invalid","subject":"Fixture","body":"Body"}"""),
            native("sms.compose", """{"number":"+15550100","body":"Body"}"""),
            native("contacts.pick", """{"kind":"phone"}"""),
            native("calendar.insert", """{"title":"Synthetic calendar fixture","location":"","description":"","startTimeMillis":0,"endTimeMillis":3600000,"timeZone":"UTC","allDay":false}"""),
            shared("database.info", "{}"),
            shared("database.migrate", """{"fromVersion":0,"toVersion":1,"steps":[{"kind":"createTable","table":"notes","columns":{"text":{"type":"text","nullable":false}}}]}"""),
            shared("database.transact", """{"version":1,"operations":[{"kind":"insert","table":"notes","id":"one","values":{"text":"synthetic"}}]}"""),
            shared("database.select", """{"version":1,"table":"notes"}"""),
            native("database.backupExport", """{"version":1,"tables":["notes"],"filename":"backup.json"}""", "documents"),
            native("database.backupImport", """{"version":1,"tables":["notes"],"handle":"$handle","mode":"insertOnly"}""", "documents"),
            shared("database.close", "{}"),
            shared("database.cancel", "{}"),
            native("presentation.get", "{}"),
            native("presentation.set", """{"theme":"dark","orientation":"portrait"}"""),
            native("presentation.reset", "{}"),
            native("sensors.list", "{}"),
            native("sensors.capture", """{"kind":"light","durationMs":100,"intervalMs":100,"maxSamples":1}"""),
            native("sensors.cancel", "{}"),
            native("sensors.subscribe", """{"kind":"light","samplingPeriodUs":100000}"""),
            native("sensors.poll", """{"subscriptionId":"$uuid"}"""),
            native("sensors.unsubscribe", """{"subscriptionId":"$uuid"}"""),
            native("biometrics.availability", "{}"),
            native("biometrics.authenticate", """{"reason":"Confirm this local action"}"""),
            native("biometrics.cancel", "{}"),
            native("network.upload", """{"url":"https://example.invalid/fixture","handle":"$handle","length":1,"maxBytes":1}""", "documents"),
            native("network.download", """{"url":"https://example.invalid/fixture","handle":"$handle","maxBytes":1}""", "documents"),
            native("network.request", http),
            native("network.probe", """{"url":"https://example.invalid/fixture","method":"HEAD"}"""),
            native("network.ping", """{"host":"example.invalid","count":1}"""),
            native("network.cancel", "{}"),
            native("notifications.schedule", """{"scheduleId":"synthetic","atEpochMs":1,"expiresAtEpochMs":2,"notification":$notification}"""),
            native("notifications.resumeScheduled", """{"scheduleId":"synthetic"}"""),
            native("notifications.scheduleStatus", """{"scheduleId":"synthetic"}"""),
            native("notifications.cancelScheduled", """{"scheduleId":"synthetic"}"""),
            native("notifications.forgetScheduled", """{"scheduleId":"synthetic","acknowledgeDeliveryUnknown":false}"""),
            native("notifications.pollActions", "{}"),
            native("notifications.ackAction", """{"eventId":"$handle"}"""),
            native("notifications.generationStatus", """{"generationId":"$handle"}"""),
            native("notifications.forgetGeneration", """{"generationId":"$handle","acknowledgeDeliveryUnknown":false}"""),
            native("notifications.requestExactAlarmAccess", "{}"),
            native("notifications.status", """{"channelId":"synthetic"}"""),
            native("notifications.channel", """{"id":"synthetic","name":"Synthetic fixture"}"""),
            native("notifications.show", notification),
            native("notifications.cancel", """{"id":1}"""),
            native("notifications.requestPermission", "{}"),
            native("speech.status", "{}"),
            native("speech.voices", """{"engine":""}"""),
            native("speech.speak", """{"text":"Synthetic fixture","language":"en-US"}"""),
            native("speech.stop", "{}"),
            native("voiceRecognition.status", "{}"),
            native("voiceRecognition.recognize", """{"language":"en-US","maxResults":1,"preferOffline":true,"timeoutMs":1000}"""),
            native("voiceRecognition.cancel", "{}"),
            native("location.status", "{}"),
            native("location.current", """{"provider":"auto","timeoutMs":1000}"""),
            native("location.cancel", "{}"),
            native("location.requestPermission", """{"precision":"approximate"}"""),
            native("recording.status", "{}"),
            native("recording.record", """{"durationMs":100,"maxBytes":48,"format":"wav-pcm16","sampleRate":8000,"channels":1}""", "documents"),
            native("recording.stop", "{}"),
            native("recording.cancel", "{}"),
            native("recording.requestPermission", "{}"),
            native("steps.status", "{}"),
            native("steps.current", """{"timeoutMs":1000}"""),
            native("steps.cancel", "{}"),
            native("steps.requestPermission", "{}"),
            native("secrets.put", """{"label":"Synthetic fixture","scope":$secretScope,"value":"synthetic-not-a-credential"}"""),
            native("secrets.capture", """{"label":"Synthetic fixture","scope":$secretScope}"""),
            native("secrets.get", """{"id":"$secretId"}"""),
            native("secrets.list", "{}"),
            native("secrets.describe", """{"id":"$secretId"}"""),
            native("secrets.remove", """{"id":"$secretId"}"""),
            native("secrets.reset", "{}"),
            native("secrets.request", """{"id":"$secretId","request":$http}""", "network"),
            native("incomingShare.list", "{}"),
            native("incomingShare.take", """{"id":"$handle","attachmentIndices":[0]}"""),
            native("incomingShare.discard", """{"id":"$handle"}"""),
            native("nfc.status", "{}"),
            native("nfc.read", """{"timeoutMs":1000}"""),
            native("nfc.write", """{"timeoutMs":1000,"allowFormat":false,"records":[{"tnf":0,"typeHex":"","idHex":"","payloadHex":""}]}"""),
            native("nfc.cancel", "{}"),
            native("bluetooth.status", "{}"),
            native("bluetooth.requestPermissions", """{"scan":true,"connect":false}"""),
            native("bluetooth.scan", """{"serviceUuids":[],"durationMs":1}"""),
            native("bluetooth.poll", """{"handle":"$uuid"}"""),
            native("bluetooth.stopScan", """{"scanId":"$uuid"}"""),
            native("bluetooth.connect", """{"deviceId":"$uuid"}"""),
            native("bluetooth.services", """{"connectionId":"$uuid","offset":0,"limit":1}"""),
            native("bluetooth.read", """{"connectionId":"$uuid","attributeId":"$uuid"}"""),
            native("bluetooth.write", """{"connectionId":"$uuid","attributeId":"$uuid","valueBase64":"AA==","writeType":"response"}"""),
            native("bluetooth.requestMtu", """{"connectionId":"$uuid","mtu":23}"""),
            native("bluetooth.subscribe", """{"connectionId":"$uuid","attributeId":"$uuid","mode":"notify"}"""),
            native("bluetooth.unsubscribe", """{"subscriptionId":"$uuid"}"""),
            native("bluetooth.disconnect", """{"connectionId":"$uuid"}"""),
            native("bluetooth.cancel", """{"operationId":"$uuid"}"""),
            native("widgets.availability", "{}"),
            native("widgets.list", "{}"),
            native("widgets.read", """{"widgetId":1}"""),
            native("widgets.requestPin", """{"model":$widget}"""),
            native("widgets.update", """{"widgetIds":[1],"model":$widget}"""),
            native("widgets.remove", """{"widgetIds":[1]}"""),
            native("widgets.pollActions", "{}"),
            native("widgets.acknowledgeAction", """{"eventId":"$handle"}"""),
            native("jobs.enqueueHttp", """{"jobId":"$uuid","request":$http}""", "network"),
            native("jobs.status", """{"jobId":"$uuid"}""", "network"),
            native("jobs.cancel", """{"jobId":"$uuid"}""", "network"),
            native("jobs.readResult", """{"jobId":"$uuid","acknowledge":false}""", "network"),
            native("jobs.forget", """{"jobId":"$uuid","acknowledgeUnknown":false}""", "network"),
            native("jobs.resume", """{"jobId":"$uuid","execution":"now","acknowledgeUnknown":false}""", "network")
        )
    }
}
