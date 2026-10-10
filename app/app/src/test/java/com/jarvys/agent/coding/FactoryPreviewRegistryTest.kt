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
    private fun snapshot(capabilities: List<String> = emptyList()): FactoryPreviewRegistry.Snapshot {
        val config = JSONObject().put("schemaVersion", 1).put("appId", "org.example.test").put("name", "Test")
            .put("entryPoint", "www/index.html").put("capabilities", JSONArray(capabilities))
        return FactoryPreviewRegistry.Snapshot(JSONObject().put("project_id", "project").put("apk_sha256", "a".repeat(64))
            .put("scope_version", 1).put("project_identity_sha256", "identity").put("build_id", "build")
            .put("project_sha256", "sources").put("template_sha256", "template").put("app_id", "org.example.test").put("version_code", 1), config.toString().toByteArray(), mapOf("www/index.html" to "<title>Test</title>".toByteArray()))
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
        val snapshot = FactoryPreviewRegistry.Snapshot(metadata, config, mapOf("www/index.html" to asset))
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
    @Test fun snapshotRejectsTraversalExtraAssetsAndBuildLimitOverflow() {
        fun create(assets: Map<String, ByteArray>) = FactoryPreviewRegistry.Snapshot(JSONObject(), byteArrayOf(), assets)
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
        for (capabilities in listOf(emptyList(), listOf("share"), listOf("documents"), listOf("audio"), listOf("audio", "documents"), listOf("browser"), listOf("browser", "documents"), listOf("maps"), listOf("phone"), listOf("maps", "phone"), listOf("email"), listOf("sms"), listOf("email", "sms"), listOf("contacts"), listOf("calendar"), com.jarvys.factory.contract.CapabilityCatalog.NAMES)) {
            val result = FactoryRuntimeContractTests.run(snapshot(capabilities), "com.jarvys.host", 34, 34) {}
            assertEquals("passed", result.getString("state"))
            val observations = result.getJSONArray("observations")
            assertEquals(30, observations.length())
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
            assertFalse(result.toString().contains("Synthetic map fixture"))
            assertFalse(result.toString().contains("+15550100"))
            assertEquals("shared_core_test", result.getString("mode"))
            assertTrue(result.getString("evidence_limit").contains("were not tested"))
            assertFalse(result.toString().contains("factory-test"))
        }
    }
    @Test fun cancellationIsNotConvertedIntoAPassingOrFailedTestReceipt() {
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            FactoryRuntimeContractTests.run(snapshot(), "com.jarvys.host", 34, 34) { throw java.util.concurrent.CancellationException() }
        }
    }
}
