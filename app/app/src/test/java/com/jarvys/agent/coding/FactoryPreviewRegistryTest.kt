package com.jarvys.agent.coding

import com.jarvys.agent.CancellationToken
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FactoryPreviewRegistryTest {
    private fun snapshot(capabilities: List<String> = emptyList(), resourceBudgets: JSONObject? = null): FactoryPreviewRegistry.Snapshot {
        val config = JSONObject().put("schemaVersion", 1).put("appId", "org.example.test").put("name", "Test")
            .put("entryPoint", "www/index.html").put("capabilities", JSONArray(capabilities))
        if (resourceBudgets != null) config.put("resourceBudgets", resourceBudgets)
        if ("incomingShare" in capabilities) config.put("incomingShare", JSONObject().put("mimeTypes", JSONArray(listOf("text/plain"))).put("multiple", false))
        if ("bluetooth" in capabilities) config.put("bluetooth", JSONObject().put("version", 1).put("scanPrivacy", "full"))
        return FactoryPreviewRegistry.Snapshot(JSONObject().put("project_id", "project").put("apk_sha256", "a".repeat(64))
            .put("scope_version", 1).put("project_identity_sha256", "identity").put("build_id", "build")
            .put("project_sha256", "sources").put("template_sha256", "template").put("app_id", "org.example.test").put("version_code", 1), config.toString().toByteArray(), com.jarvys.agent.coding.FactoryUiTestAssets.withUi(mapOf("www/index.html" to "<title>Test</title>".toByteArray())))
    }
    @Test fun runtimeUiIsCompletePinnedAndRetainedImmutablyWithoutReducingWebQuotas() {
        val library = com.jarvys.factory.runtime.FactoryUiAssets.paths().first()
        val assets = FactoryUiTestAssets.withUi(mapOf("www/index.html" to byteArrayOf(1))).toMutableMap()
        val expected = assets.getValue(library).copyOf()
        val retained = FactoryPreviewRegistry.Snapshot(JSONObject(), byteArrayOf(), assets)
        assets.getValue(library)[0] = 0
        retained.asset(library)!![0] = 0
        assertArrayEquals(expected, retained.asset(library))
        assertThrows(IllegalArgumentException::class.java) { FactoryPreviewRegistry.Snapshot(JSONObject(), byteArrayOf(), assets) }
        val fresh = FactoryUiTestAssets.withUi(mapOf("www/index.html" to byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { FactoryPreviewRegistry.Snapshot(JSONObject(), byteArrayOf(), fresh - library) }
        assertThrows(IllegalArgumentException::class.java) { FactoryPreviewRegistry.Snapshot(JSONObject(), byteArrayOf(), fresh + ("factory-ui/evil.css" to byteArrayOf())) }
        val web = (0 until 128).associate { (if (it == 0) "www/index.html" else "www/$it.css") to ByteArray(65536) }
        FactoryPreviewRegistry.Snapshot(JSONObject(), byteArrayOf(), FactoryUiTestAssets.withUi(web))
        assertThrows(IllegalArgumentException::class.java) { FactoryPreviewRegistry.Snapshot(JSONObject(), byteArrayOf(), FactoryUiTestAssets.withUi(web + ("www/extra.css" to byteArrayOf()))) }
        assertThrows(IllegalArgumentException::class.java) { FactoryPreviewRegistry.Snapshot(JSONObject(), byteArrayOf(), FactoryUiTestAssets.withUi(web + ("www/1.css" to ByteArray(65537)))) }
    }
    @Test fun snapshotUsesResolvedPackagingBudgetsForLargeFilesAndCount() {
        val large = ByteArray(9 * 1024 * 1024)
        val web = (0 until 300).associate { "www/f$it.js" to byteArrayOf() } + ("www/index.html" to large)
        val assets = FactoryUiTestAssets.withUi(web)
        val maximum = com.jarvys.factory.contract.ResourceBudgets.maxima().packaging
        val retained = FactoryPreviewRegistry.Snapshot(JSONObject(), byteArrayOf(), assets, maximum)
        assertEquals(large.size, retained.asset("www/index.html")!!.size)
        assertThrows(IllegalArgumentException::class.java) { FactoryPreviewRegistry.Snapshot(JSONObject(), byteArrayOf(), assets) }
        val tooMany = (0..512).associate { "www/f$it" to byteArrayOf() } + ("www/index.html" to byteArrayOf())
        assertThrows(IllegalArgumentException::class.java) { FactoryPreviewRegistry.Snapshot(JSONObject(), byteArrayOf(), FactoryUiTestAssets.withUi(tooMany), maximum) }
    }
    @Test fun launchTokenIsOpaqueOneShotAndCancellationRevokesConsumedSession() {
        val token = CancellationToken.cancellable()
        val key = FactoryPreviewRegistry.issue(snapshot(), {}, token)
        assertNull(FactoryPreviewRegistry.consume("guessed"))
        val session = FactoryPreviewRegistry.consume(key)!!
        assertNull(FactoryPreviewRegistry.consume(key))
        var revoked = false
        session.setOnRevoked { revoked = true }
        token.cancel()
        assertTrue(revoked)
        assertFalse(session.isActive())
    }
    @Test fun replacementAndInvalidBindingRevokeSession() {
        var valid = true
        val first = FactoryPreviewRegistry.consume(FactoryPreviewRegistry.issue(snapshot(), { check(valid) }, CancellationToken.cancellable()))!!
        valid = false
        assertFalse(first.isActive())
        val second = FactoryPreviewRegistry.consume(FactoryPreviewRegistry.issue(snapshot(), {}, CancellationToken.cancellable()))!!
        FactoryPreviewRegistry.issue(snapshot(), {}, CancellationToken.cancellable())
        assertFalse(second.isActive())
    }
    @Test fun snapshotDefensivelyCopiesAllData() {
        val metadata = JSONObject().put("project_id", "original")
        val config = "config".toByteArray()
        val asset = "original".toByteArray()
        val snapshot = FactoryPreviewRegistry.Snapshot(metadata, config, FactoryUiTestAssets.withUi(mapOf("www/index.html" to asset)))
        metadata.put("project_id", "changed"); config[0] = 0; asset[0] = 0
        snapshot.asset("www/index.html")!![0] = 0
        snapshot.configBytes()[0] = 0
        snapshot.metadata().put("project_id", "changed")
        assertEquals("original", snapshot.metadata().getString("project_id"))
        assertEquals("original", String(snapshot.asset("www/index.html")!!))
        assertEquals("config", String(snapshot.configBytes()))
        assertNull(snapshot.asset("../private"))
    }
    @Test fun traceOnlyContainsWhitelistedOperationOutcomesAndAggregates() {
        val session = FactoryPreviewRegistry.consume(FactoryPreviewRegistry.issue(snapshot(), {}, CancellationToken.cancellable()))!!
        repeat(1000) { session.record("storage.get", "success") }
        session.record("password=my-secret", "success")
        session.record("storage.get", "error:my-secret")
        session.setWebViewVersion("private-token")
        val receipt = session.receipt()
        assertEquals(1, receipt.getJSONArray("observations").length())
        assertEquals(1000, receipt.getJSONArray("observations").getJSONObject(0).getInt("count"))
        assertFalse(receipt.toString().contains("secret")); assertTrue(receipt.isNull("webview_version"))
        assertThrows(Exception::class.java) { FactoryPreviewRegistry.status(session.snapshot.metadata().put("project_id", "other")) }
        session.close()
    }
    @Test fun finalReceiptsDistinguishClosedRevokedAndFailedWithExactBuildBinding() {
        for (state in listOf("closed", "revoked", "failed")) {
            val token = CancellationToken.cancellable()
            val session = FactoryPreviewRegistry.consume(FactoryPreviewRegistry.issue(snapshot(), {}, token))!!
            when (state) { "closed" -> session.close(); "failed" -> session.fail(); else -> token.cancel() }
            val receipt = FactoryPreviewRegistry.status(session.snapshot.metadata())
            assertEquals(state, receipt.getString("state"))
            assertThrows(Exception::class.java) { FactoryPreviewRegistry.status(session.snapshot.metadata().put("build_id", "other")) }
            assertThrows(Exception::class.java) { FactoryPreviewRegistry.status(session.snapshot.metadata().put("project_identity_sha256", "restored")) }
        }
    }
    @Test fun statusNumericBindingSurvivesJsonRoundTripWithoutAcceptingFloatingPoint() {
        val session = FactoryPreviewRegistry.consume(FactoryPreviewRegistry.issue(snapshot(), {}, CancellationToken.cancellable()))!!
        assertEquals("launch_requested", FactoryPreviewRegistry.status(session.snapshot.metadata().put("scope_version", 1L)).getString("state"))
        assertThrows(Exception::class.java) { FactoryPreviewRegistry.status(session.snapshot.metadata().put("scope_version", 1.0)) }
        assertThrows(Exception::class.java) { FactoryPreviewRegistry.status(session.snapshot.metadata().put("version_code", "1")) }
        session.close()
    }
    @Test fun teardownFailureRemainsObservableAfterCancellationWithoutLateOperationEvidence() {
        val token=CancellationToken.cancellable()
        val session=FactoryPreviewRegistry.consume(FactoryPreviewRegistry.issue(snapshot(), {}, token))!!
        token.cancel()
        assertFalse(session.isOpen())
        session.record("runtime","cleanup_pending")
        session.record("storage.set","success")
        session.record("lifecycle","started")
        val receipt=FactoryPreviewRegistry.status(session.snapshot.metadata())
        assertEquals("revoked",receipt.getString("state"))
        val events=receipt.getJSONArray("observations")
        assertEquals(2,events.length())
        assertEquals("cleanup_pending",events.getJSONObject(1).getString("outcome"))
    }
    @Test fun failedStartupDoesNotTriggerRevokeCallbackOrClaimCloseWasRevocation() {
        val session=FactoryPreviewRegistry.consume(FactoryPreviewRegistry.issue(snapshot(), {}, CancellationToken.cancellable()))!!
        var callbacks=0
        session.setOnRevoked { callbacks++ }
        session.fail()
        session.close()
        session.setOnRevoked { callbacks++ }
        assertEquals(0,callbacks)
        assertEquals("failed",session.receipt().getString("state"))
    }
    @Test fun terminalReceiptsDoNotRetainActivityCallbacks() {
        val field=FactoryPreviewRegistry.Session::class.java.getDeclaredField("revoked").apply { isAccessible=true }
        for (state in listOf("closed","failed","revoked")) {
            val session=FactoryPreviewRegistry.consume(FactoryPreviewRegistry.issue(snapshot(), {}, CancellationToken.cancellable()))!!
            var notifications=0
            session.setOnRevoked { notifications++ }
            when(state) { "closed" -> session.close(); "failed" -> session.fail(); else -> session.revoke() }
            assertNull(field.get(session))
            session.setOnRevoked { notifications++ }
            assertNull(field.get(session))
            assertEquals(if(state == "revoked") 2 else 0,notifications)
            assertEquals(state,session.receipt().getString("state"))
        }
    }
    @Test fun receiptBeginsWithLaunchRequestAndNeverClaimsJavascriptExecution() {
        val session = FactoryPreviewRegistry.consume(FactoryPreviewRegistry.issue(snapshot(), {}, CancellationToken.cancellable()))!!
        assertEquals("launch_requested", session.receipt().getString("state"))
        session.record("lifecycle", "created")
        assertEquals("created", session.receipt().getString("state"))
        session.close()
        session.record("lifecycle", "started")
        assertEquals("closed", session.receipt().getString("state"))
    }
    @Test fun receiptKeepsLatestCatalogValidationErrorWithoutRequestValues() {
        val session = FactoryPreviewRegistry.consume(FactoryPreviewRegistry.issue(snapshot(), {}, CancellationToken.cancellable()))!!
        session.recordValidationFailure("database.migrate", "INVALID_ARGUMENT",
            "database.migrate.steps[0].columns must be an object mapping column name to {type, nullable}")
        session.recordValidationFailure("database.migrate", "INVALID_ARGUMENT",
            "database.migrate.steps[0].columns values must be {type, nullable} objects")
        session.recordValidationFailure("unknown.private.method", "INVALID_ARGUMENT", "private value")
        session.recordValidationFailure("storage.set", "IO_ERROR", "private value")
        val errors = session.receipt().getJSONArray("validation_errors")
        assertEquals(1, errors.length())
        val error = errors.getJSONObject(0)
        assertEquals("database.migrate", error.getString("method"))
        assertEquals("INVALID_ARGUMENT", error.getString("code"))
        assertEquals(2L, error.getLong("count"))
        assertTrue(error.getString("message").contains("columns values"))
        assertFalse(session.receipt().toString().contains("private value"))
        session.close()
    }
    @Test fun snapshotRejectsTraversalExtraAssetsAndBuildLimitOverflow() {
        fun create(assets: Map<String, ByteArray>) = FactoryPreviewRegistry.Snapshot(JSONObject(), byteArrayOf(), FactoryUiTestAssets.withUi(assets))
        assertThrows(Exception::class.java) { create(mapOf("www/index.html" to byteArrayOf(), "www/../private" to byteArrayOf())) }
        assertThrows(Exception::class.java) { create(mapOf("www/index.html" to byteArrayOf(), "private.json" to byteArrayOf())) }
        assertThrows(Exception::class.java) { create(mapOf("www/index.html" to ByteArray(com.jarvys.agent.apkfactory.FactorySpec.MAX_FILE_BYTES + 1))) }
        val assets = (0 until com.jarvys.agent.apkfactory.FactorySpec.MAX_WEB_FILES).associate { "www/$it.js" to byteArrayOf() } + ("www/index.html" to byteArrayOf())
        assertThrows(Exception::class.java) { create(assets) }
        val megabyte = ByteArray(com.jarvys.agent.apkfactory.FactorySpec.MAX_FILE_BYTES)
        assertThrows(Exception::class.java) { create((0..8).associate { "www/$it.js" to megabyte } + ("www/index.html" to byteArrayOf())) }
    }
    @Test fun parallelConsumersCannotReuseOneShotLaunchToken() {
        val key = FactoryPreviewRegistry.issue(snapshot(), {}, CancellationToken.cancellable())
        val results = java.util.concurrent.CopyOnWriteArrayList<FactoryPreviewRegistry.Session>()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (0..7).map { Thread { start.await(); FactoryPreviewRegistry.consume(key)?.let { results.add(it) } }.apply { start() } }
        start.countDown(); threads.forEach { it.join(3000); assertFalse(it.isAlive) }
        assertEquals(1, results.size); results.single().close()
    }
    @Test fun realSharedHandlerAssertionsPassForAllAndNoCapabilitiesAndNeverExecuteJavascript() {
        val capabilitySelections = listOf(emptyList(), listOf("share"), listOf("documents"), listOf("audio"), listOf("audio", "documents"), listOf("browser"), listOf("browser", "documents"), listOf("maps"), listOf("phone"), listOf("maps", "phone"), listOf("email"), listOf("sms"), listOf("email", "sms"), listOf("contacts"), listOf("calendar"), listOf("database"), listOf("database", "documents"), listOf("storage"), listOf("storage", "documents"), listOf("storage", "database", "documents"), listOf("presentation"), listOf("sensors"), listOf("biometrics"), com.jarvys.factory.contract.CapabilityCatalog.NAMES) +
            com.jarvys.factory.contract.CapabilityCatalog.NAMES.map {
                when (it) {
                    "incomingShare" -> listOf(it, "documents")
                    "jobs" -> listOf(it, "network")
                    else -> listOf(it)
                }
            } + listOf(com.jarvys.factory.contract.CapabilityCatalog.NAMES - setOf("documents", "incomingShare"),
                com.jarvys.factory.contract.CapabilityCatalog.NAMES - setOf("network", "jobs"))
        for (capabilities in capabilitySelections.distinct()) {
            val result = FactoryRuntimeContractTests.run(snapshot(capabilities), "com.jarvys.host", 34, 34) {}
            assertEquals(result.getJSONArray("observations").toString(), "passed", result.getString("state"))
            val observations = result.getJSONArray("observations")
            assertEquals(com.jarvys.factory.contract.CapabilityCatalog.METHODS.size + 3, observations.length())
            val contractObservations = (0 until observations.length()).map { observations.getJSONObject(it) }
                .filter { it.getString("operation").substringBefore(':') in com.jarvys.factory.contract.CapabilityCatalog.METHODS }
            assertEquals(com.jarvys.factory.contract.CapabilityCatalog.METHODS.keys,
                contractObservations.map { it.getString("operation").substringBefore(':') }.toSet())
            assertEquals(com.jarvys.factory.contract.CapabilityCatalog.METHODS.size, contractObservations.size)
            val dependencies = mapOf(
                "network.upload" to "documents", "network.download" to "documents", "recording.record" to "documents",
                "secrets.request" to "network", "jobs.enqueueHttp" to "network", "jobs.status" to "network",
                "jobs.cancel" to "network", "jobs.readResult" to "network", "jobs.forget" to "network", "jobs.resume" to "network"
            )
            for ((method, dependency) in dependencies) {
                val capability = com.jarvys.factory.contract.CapabilityCatalog.METHODS.getValue(method).capability
                val check = contractObservations.single { it.getString("operation").startsWith("$method:") }
                val expected = if (capability in capabilities && dependency in capabilities) "preview_unavailable" else "undeclared_denied"
                assertEquals("$method:$expected", check.getString("operation"))
                assertEquals("passed", check.getString("outcome"))
            }
            val page = contractObservations.single { it.getString("operation").startsWith("storage.listPage:") }
            assertEquals("storage.listPage:" + if ("storage" in capabilities) "shared_handler" else "undeclared_denied", page.getString("operation"))
            assertEquals("passed", page.getString("outcome"))
            val documentAssertions = (0 until observations.length()).map { observations.getJSONObject(it) }
                .filter { it.getString("operation").startsWith("documents.") }
            assertEquals(6, documentAssertions.size)
            val suffix = if ("documents" in capabilities) ":preview_unavailable" else ":undeclared_denied"
            assertTrue(documentAssertions.all { it.getString("operation").endsWith(suffix) && it.getString("outcome") == "passed" })
            val fileShare = (0 until observations.length()).map { observations.getJSONObject(it) }.single { it.getString("operation").startsWith("share.file:") }
            assertEquals("share.file:" + if (capabilities.containsAll(listOf("documents", "share"))) "preview_unavailable" else "undeclared_denied", fileShare.getString("operation"))
            assertEquals("passed", fileShare.getString("outcome"))
            val audio = (0 until observations.length()).map { observations.getJSONObject(it) }.single { it.getString("operation").startsWith("audio.play:") }
            assertEquals("audio.play:" + if (capabilities.containsAll(listOf("audio", "documents"))) "preview_unavailable" else "undeclared_denied", audio.getString("operation"))
            assertEquals("passed", audio.getString("outcome"))
            val browser = (0 until observations.length()).map { observations.getJSONObject(it) }.single { it.getString("operation").startsWith("browser.open:") }
            assertEquals("browser.open:" + if ("browser" in capabilities) "preview_unavailable" else "undeclared_denied", browser.getString("operation"))
            assertEquals("passed", browser.getString("outcome"))
            for ((capability, method) in listOf("maps" to "maps.open", "phone" to "phone.dial", "email" to "email.compose", "sms" to "sms.compose", "contacts" to "contacts.pick", "calendar" to "calendar.insert")) {
                val external = (0 until observations.length()).map { observations.getJSONObject(it) }.single { it.getString("operation").startsWith("$method:") }
                assertEquals("$method:" + if (capability in capabilities) "preview_unavailable" else "undeclared_denied", external.getString("operation"))
                assertEquals("passed", external.getString("outcome"))
            }
            val databaseChecks = (0 until observations.length()).map { observations.getJSONObject(it) }
                .filter { it.getString("operation").startsWith("database.") }
            assertEquals(8, databaseChecks.size)
            val originalDatabaseMethods = setOf("database.info", "database.migrate", "database.transact", "database.select", "database.close", "database.cancel")
            val originalDatabaseChecks = databaseChecks.filter { it.getString("operation").substringBefore(':') in originalDatabaseMethods }
            assertEquals(6, originalDatabaseChecks.size)
            val databaseSuffix = if ("database" in capabilities) ":shared_handler" else ":undeclared_denied"
            assertEquals(originalDatabaseMethods.map { it + databaseSuffix }.toSet(), originalDatabaseChecks.map { it.getString("operation") }.toSet())
            assertTrue(originalDatabaseChecks.all { it.getString("outcome") == "passed" })
            val backupMethods = setOf("database.backupExport", "database.backupImport")
            val backupChecks = databaseChecks.filter { it.getString("operation").substringBefore(':') in backupMethods }
            assertEquals(2, backupChecks.size)
            val backupSuffix = if (capabilities.containsAll(listOf("database", "documents"))) ":preview_unavailable" else ":undeclared_denied"
            assertEquals(backupMethods.map { it + backupSuffix }.toSet(), backupChecks.map { it.getString("operation") }.toSet())
            assertTrue(backupChecks.all { it.getString("outcome") == "passed" })
            val storageBackupMethods = setOf("storage.backupExport", "storage.backupImport")
            val storageBackupChecks = (0 until observations.length()).map { observations.getJSONObject(it) }
                .filter { it.getString("operation").substringBefore(':') in storageBackupMethods }
            assertEquals(2, storageBackupChecks.size)
            val storageBackupSuffix = if (capabilities.containsAll(listOf("storage", "documents"))) ":preview_unavailable" else ":undeclared_denied"
            assertEquals(storageBackupMethods.map { it + storageBackupSuffix }.toSet(), storageBackupChecks.map { it.getString("operation") }.toSet())
            assertTrue(storageBackupChecks.all { it.getString("outcome") == "passed" })
            val presentationChecks = (0 until observations.length()).map { observations.getJSONObject(it) }.filter { it.getString("operation").startsWith("presentation.") }
            assertEquals(3, presentationChecks.size)
            assertTrue(presentationChecks.all { it.getString("operation").endsWith(if ("presentation" in capabilities) ":preview_unavailable" else ":undeclared_denied") && it.getString("outcome") == "passed" })
            val biometricChecks = (0 until observations.length()).map { observations.getJSONObject(it) }
                .filter { it.getString("operation").startsWith("biometrics.") }
            assertEquals(3, biometricChecks.size)
            val biometricSuffix = if ("biometrics" in capabilities) ":preview_unavailable" else ":undeclared_denied"
            assertEquals(setOf("biometrics.availability", "biometrics.authenticate", "biometrics.cancel").map { it + biometricSuffix }.toSet(),
                biometricChecks.map { it.getString("operation") }.toSet())
            assertTrue(biometricChecks.all { it.getString("outcome") == "passed" })
            assertFalse(result.toString().contains("Synthetic map fixture"))
            assertFalse(result.toString().contains("+15550100"))
            assertEquals("shared_core_test", result.getString("mode"))
            assertTrue(result.getString("evidence_limit").contains("were not tested"))
            assertFalse(result.toString().contains("factory-test"))
        }
    }
    @Test fun fixedSyntheticInputsFitMinimumSignedNativeValidationBudgets() {
        val budgets = JSONObject("""{"version":1,"documents":{"handleBytes":1,"sessionBytes":1,"leaseMs":1},"nfc":{"messageBytes":3,"records":1,"timeoutMs":1000},"bluetooth":{"valueBytes":1,"scanDurationMs":1},"widgets":{"nodes":1,"depth":1,"actions":0,"modelBytes":256,"actionDataBytes":2}}""")
        val result = FactoryRuntimeContractTests.run(snapshot(com.jarvys.factory.contract.CapabilityCatalog.NAMES, budgets), "com.jarvys.host", 34, 34) {}
        assertEquals(result.getJSONArray("observations").toString(), "passed", result.getString("state"))
        assertEquals(com.jarvys.factory.contract.CapabilityCatalog.METHODS.size + 3, result.getJSONArray("observations").length())
    }
    @Test fun invalidCapabilityDependenciesCannotProduceSyntheticReceipts() {
        for (capabilities in listOf(listOf("incomingShare"), listOf("jobs"))) {
            val failure = assertThrows(com.jarvys.factory.runtime.FactoryException::class.java) {
                FactoryRuntimeContractTests.run(snapshot(capabilities), "com.jarvys.host", 34, 34) {}
            }
            assertEquals("INVALID_CONFIG", failure.code)
        }
    }
    @Test fun cancellationIsNotConvertedIntoAPassingOrFailedTestReceipt() {
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            FactoryRuntimeContractTests.run(snapshot(), "com.jarvys.host", 34, 34) { throw java.util.concurrent.CancellationException() }
        }
    }
}
