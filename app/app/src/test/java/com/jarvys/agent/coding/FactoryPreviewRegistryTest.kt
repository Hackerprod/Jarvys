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
            .put("scope_version", 1), config.toString().toByteArray(), mapOf("www/index.html" to "<title>Test</title>".toByteArray()))
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
        assertThrows(Exception::class.java) { FactoryPreviewRegistry.status("other", "a".repeat(64), 1) }
        session.close()
    }
    @Test fun realSharedHandlerAssertionsPassForAllAndNoCapabilitiesAndNeverExecuteJavascript() {
        for (capabilities in listOf(emptyList(), com.jarvys.factory.contract.CapabilityCatalog.NAMES)) {
            val result = FactoryRuntimeContractTests.run(snapshot(capabilities), "com.jarvys.host", 34, 34) {}
            assertEquals("passed", result.getString("state"))
            assertEquals(13, result.getJSONArray("observations").length())
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
