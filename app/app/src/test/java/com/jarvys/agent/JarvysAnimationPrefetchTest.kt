package com.jarvys.agent

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JarvysAnimationPrefetchTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Transport : JarvysAnimationTransport {
        val assets = LinkedHashMap<String, ByteArray>()
        val requests = mutableListOf<URI>()
        var catalog = ""
        var failureId: String? = null
        override fun get(uri: URI, token: CancellationToken): JarvysAnimationTransport.Response {
            requests.add(uri)
            val id = uri.path.substringAfterLast('/').removeSuffix(".riv")
            if (id == failureId) throw IOException("synthetic prefetch failure")
            val data = if (uri.toString() == JarvysAnimationSite.INDEX) catalog.toByteArray() else assets.getValue(id)
            return object : JarvysAnimationTransport.Response {
                override val status = 200
                override val contentLength = data.size.toLong()
                override val body = ByteArrayInputStream(data)
                override fun close() { body.close() }
            }
        }
    }
    private fun catalog(t: Transport) = """{"schema":1,"packages":{"default":{"animations":{${t.assets.entries.joinToString(",") { (id, data) -> "\"$id\":{\"url\":\"https://jarvys-agent.com/animations/$id.riv\",\"bytes\":${data.size},\"sha256\":\"${sha(data)}\"}" }}}}}}"""
    private fun setup(): Pair<JarvysAnimationCache, Transport> {
        val transport = Transport()
        JarvysAnimationCatalog.remoteIds.forEachIndexed { index, id -> transport.assets[id] = byteArrayOf(index.toByte(), 7, 9) }
        transport.catalog = catalog(transport)
        return JarvysAnimationCache(temporary.newFolder(), transport) { Long.MAX_VALUE } to transport
    }
    @Test fun refreshBatchPrioritizesObservedIdsAndWarmsAllSixExactlyOnce() {
        val ids = jarvysAnimationRefreshIds(linkedSetOf("thinking", "working"))
        assertEquals(listOf("thinking", "working"), ids.take(2))
        assertEquals(JarvysAnimationCatalog.remoteIds, ids)
        assertEquals(6, ids.size)
    }
    @Test fun refreshWarmsAllKnownAssetsAndUnchangedHashesAvoidTransfer() {
        val (cache, transport) = setup()
        val ids = jarvysAnimationRefreshIds(setOf("thinking"))
        cache.refresh("default", ids, CancellationToken.cancellable()) { _, _ -> }
        assertEquals(7, transport.requests.size)
        JarvysAnimationCatalog.remoteIds.forEach { assertNotNull(cache.cached("default", it, CancellationToken.cancellable())) }
        transport.requests.clear()
        cache.refresh("default", ids, CancellationToken.cancellable()) { _, _ -> }
        assertEquals(listOf(URI(JarvysAnimationSite.INDEX)), transport.requests)
        transport.assets["working"] = byteArrayOf(8, 8, 8, 8)
        transport.catalog = catalog(transport)
        transport.requests.clear()
        cache.refresh("default", ids, CancellationToken.cancellable()) { _, _ -> }
        assertEquals(listOf(URI(JarvysAnimationSite.INDEX), URI("https://jarvys-agent.com/animations/working.riv")), transport.requests)
    }
    @Test fun missingAssetStaysUnavailableAndFailedAssetDoesNotStopOthers() {
        val (cache, transport) = setup()
        transport.assets.remove("taunt")
        transport.catalog = catalog(transport)
        transport.failureId = "working"
        val values = mutableMapOf<String, JarvysVerifiedAnimation?>()
        try { cache.refresh("default", jarvysAnimationRefreshIds(setOf("working")), CancellationToken.cancellable()) { id, value -> values[id] = value }; fail("Expected individual failure") }
        catch (_: IOException) { }
        assertNull(values["working"])
        assertNull(values["taunt"])
        assertNotNull(values["thinking"])
        assertNotNull(values["interrupted"])
        assertFalse(transport.requests.any { it.path.endsWith("taunt.riv") })
    }
}
