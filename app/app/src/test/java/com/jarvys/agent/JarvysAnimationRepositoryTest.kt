package com.jarvys.agent

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression contract for newly reconstructed code. Synthetic bytes test cache integrity, not Rive decoding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JarvysAnimationRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val token get() = CancellationToken.cancellable()
    private val content = ByteArray(90_001) { (it % 251).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun entry(bytes: ByteArray = content, path: String = "sad.riv") =
        """{"url":"https://www.jarvys-agent.com/animations/$path","bytes":${bytes.size},"sha256":"${sha(bytes)}"}"""
    private fun index(animations: String = "\"sad\":${entry()}", packages: String? = null) =
        """{"schema":1,"packages":${packages ?: "{\"default\":{\"animations\":{$animations}}}"}}"""
    private fun parse(json: String, requested: String = "default") =
        JarvysAnimationCatalog.read(ByteArrayInputStream(json.toByteArray()), requested, token)
    private fun fails(action: () -> Unit) {
        try { action(); fail("Expected rejected input") } catch (_: IOException) { }
    }
    private class FakeTransport : JarvysAnimationTransport {
        val requests = mutableListOf<URI>()
        var catalog = ""
        var body: ByteArray = byteArrayOf()
        var contentLength: Long? = null
        var failDownloads = false
        var onRead: (() -> Unit)? = null
        override fun get(uri: URI, token: CancellationToken): JarvysAnimationTransport.Response {
            requests.add(uri)
            val isIndex = uri.toString() == JarvysAnimationSite.INDEX
            if (!isIndex && failDownloads) throw IOException("synthetic failure")
            val data = if (isIndex) catalog.toByteArray() else body
            val length = if (isIndex) data.size.toLong() else contentLength ?: data.size.toLong()
            return object : JarvysAnimationTransport.Response {
                override val status = 200
                override val contentLength = length
                override val body: InputStream = object : ByteArrayInputStream(data) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (!isIndex) onRead?.invoke()
                        assertTrue("Reads must stay bounded", len <= 32_768 || isIndex)
                        return super.read(b, off, len)
                    }
                }
                override fun close() { body.close() }
            }
        }
    }
    private fun setup(): Triple<JarvysAnimationCache, FakeTransport, File> {
        val parent = temporary.newFolder()
        val transport = FakeTransport().apply { catalog = index(); body = content }
        return Triple(JarvysAnimationCache(parent, transport) { Long.MAX_VALUE }, transport, parent)
    }

    @Test fun parsesSchemaOneAndAllRemoteIdsButNeverDownloadsIdle() {
        val json = index((JarvysAnimationCatalog.remoteIds + "idle").joinToString(",") { "\"$it\":${entry(path = "$it.riv")}" })
        assertEquals(JarvysAnimationCatalog.remoteIds, parse(json).animations.keys)
    }
    @Test fun missingRequestedPackageFallsBackToDefault() {
        val selection = parse(index(), "special")
        assertEquals("special", selection.requestedPackage)
        assertEquals("default", selection.packageId)
    }
    @Test fun presentEmptyRequestedPackageDoesNotResurrectDefault() {
        val selection = parse(index(packages = """{"default":{"animations":{"sad":${entry()}}},"special":{"animations":{}}}"""), "special")
        assertEquals("special", selection.packageId)
        assertTrue(selection.animations.isEmpty())
    }
    @Test fun rejectsDuplicateIdentityAndKnownFields() {
        fails { parse(index("\"sad\":${entry()},\"sad\":${entry()}")) }
        fails { parse(index().replace("\"schema\":1", "\"schema\":1,\"schema\":1")) }
        fails { parse(index().replace("\"bytes\":90001", "\"bytes\":90001,\"bytes\":90001")) }
    }
    @Test fun rejectsFractionalQuotedNegativeOverflowAndZeroSizes() {
        listOf("1.0", "\"90001\"", "-1", "9223372036854775808", "0").forEach { size ->
            fails { parse(index().replace("\"bytes\":90001", "\"bytes\":$size")) }
        }
    }
    @Test fun rejectsBadDigestUnsupportedSchemaAndTrailingDocument() {
        fails { parse(index().replace(sha(content), "f".repeat(63))) }
        fails { parse(index().replace("\"schema\":1", "\"schema\":2")) }
        fails { parse(index() + "{}") }
    }
    @Test fun allowsOnlyExactHttpsHostsAndPortsWithoutCredentialsOrFragment() {
        listOf("https://www.jarvys-agent.com/a", "https://jarvys-agent.com:443/a").forEach { JarvysAnimationSite.checked(it) }
        listOf("http://jarvys-agent.com/a", "https://jarvys-agent.com.evil.test/a", "https://eviljarvys-agent.com/a",
            "https://user@jarvys-agent.com/a", "https://jarvys-agent.com:444/a", "https://jarvys-agent.com/a#fragment").forEach { url ->
            fails { JarvysAnimationSite.checked(url) }
        }
    }
    @Test fun firstRefreshPublishesWithdrawalBeforeVerifiedFile() {
        val (cache, transport, parent) = setup()
        val published = mutableListOf<JarvysVerifiedAnimation?>()
        cache.refresh("default", token) { published.add(it) }
        assertEquals(2, published.size)
        assertNull(published.first())
        val ready = published.last()!!
        assertArrayEquals(content, ready.file.readBytes())
        assertEquals(sha(content), ready.sha256)
        assertEquals("sad", ready.animationId)
        assertEquals(2, transport.requests.size)
        assertTrue(File(parent, "jarvys_animations").listFiles()!!.none { it.name.endsWith(".part") })
    }
    @Test fun persistedCacheIsReverifiedAcrossInstancesWithoutNetwork() {
        val (cache, transport, parent) = setup()
        cache.refresh("default", token) { }
        transport.requests.clear()
        val restarted = JarvysAnimationCache(parent, transport) { Long.MAX_VALUE }
        assertNotNull(restarted.cached("default", "sad", token))
        assertTrue(transport.requests.isEmpty())
    }
    @Test fun refreshWithSameIdentitySkipsAssetTransfer() {
        val (cache, transport) = setup()
        cache.refresh("default", token) { }
        transport.requests.clear()
        cache.refresh("default", token) { }
        assertEquals(listOf(URI(JarvysAnimationSite.INDEX)), transport.requests)
    }
    @Test fun missingCurrentIdentityWithdrawsOldAssetWithoutDeletingExposedFile() {
        val (cache, transport) = setup()
        var prior: JarvysVerifiedAnimation? = null
        cache.refresh("default", token) { if (it != null) prior = it }
        transport.catalog = index("")
        val events = mutableListOf<JarvysVerifiedAnimation?>()
        cache.refresh("default", token) { events.add(it) }
        assertEquals(listOf<JarvysVerifiedAnimation?>(null), events)
        assertNull(cache.cached("default", token))
        assertTrue(prior!!.file.exists())
    }
    @Test fun failedNewIdentityNeverFallsBackToOldIdentity() {
        val (cache, transport) = setup()
        cache.refresh("default", token) { }
        transport.catalog = index("\"sad\":${entry(byteArrayOf(7, 8, 9))}")
        transport.failDownloads = true
        val events = mutableListOf<JarvysVerifiedAnimation?>()
        fails { cache.refresh("default", token) { events.add(it) } }
        assertEquals(listOf<JarvysVerifiedAnimation?>(null), events)
        assertNull(cache.cached("default", token))
    }
    @Test fun transportFailureBeforeNewCatalogKeepsPreviousIdentity() {
        val (cache, transport) = setup()
        cache.refresh("default", token) { }
        transport.catalog = "invalid"
        try { cache.refresh("default", token) { } } catch (_: Exception) { }
        assertNotNull(cache.cached("default", token))
    }
    @Test fun wrongHashFailsWithoutPublishingOrLeavingPartialFile() {
        val (cache, transport, parent) = setup()
        transport.body = content.copyOf().also { it[0] = 55 }
        val events = mutableListOf<JarvysVerifiedAnimation?>()
        fails { cache.refresh("default", token) { events.add(it) } }
        assertTrue(events.all { it == null })
        assertTrue(File(parent, "jarvys_animations").listFiles()!!.none { it.name.endsWith(".riv") || it.name.endsWith(".part") })
    }
    @Test fun rejectsDeclaredAndActualSizeMismatchIncludingUnknownLengthOverflow() {
        for (kind in 0..2) {
            val (cache, transport) = setup()
            when (kind) {
                0 -> transport.contentLength = content.size.toLong() + 1
                1 -> { transport.body = content.copyOf(content.size - 1); transport.contentLength = -1 }
                else -> { transport.body = content.copyOf(content.size + 1); transport.contentLength = -1 }
            }
            fails { cache.refresh("default", token) { assertNull(it) } }
        }
    }
    @Test fun cancellationDuringStreamingCannotCommitOrPublish() {
        val (cache, transport, parent) = setup()
        val cancellation = token
        transport.onRead = { cancellation.cancel() }
        try { cache.refresh("default", cancellation) { assertNull(it) }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertTrue(File(parent, "jarvys_animations").listFiles()!!.none { it.name.endsWith(".riv") || it.name.endsWith(".part") })
    }
    @Test fun exposedCorruptFileCannotBeReplaced() {
        val (cache, _, _) = setup()
        var file: File? = null
        cache.refresh("default", token) { if (it != null) file = it.file }
        val corrupt = byteArrayOf(1, 2, 3)
        file!!.writeBytes(corrupt)
        fails { cache.refresh("default", token) { assertNull(it) } }
        assertArrayEquals(corrupt, file!!.readBytes())
    }
    @Test fun freshProcessCanRepairUnexposedCorruptFile() {
        val (cache, transport, parent) = setup()
        var file: File? = null
        cache.refresh("default", token) { if (it != null) file = it.file }
        file!!.writeBytes(byteArrayOf(1, 2, 3))
        val restarted = JarvysAnimationCache(parent, transport) { Long.MAX_VALUE }
        assertNull(restarted.cached("default", token))
        restarted.fulfill("default", setOf("sad"), token) { _, _ -> }
        assertArrayEquals(content, file!!.readBytes())
    }
    @Test fun missingSpaceRejectsBeforeAssetNetworkRequest() {
        val (_, transport, parent) = setup()
        val cache = JarvysAnimationCache(parent, transport) { 0L }
        fails { cache.refresh("default", token) { assertNull(it) } }
        assertEquals(1, transport.requests.size)
    }
    @Test fun successfulSiblingIsNotBlockedByFailedIdentity() {
        val (cache, transport) = setup()
        transport.catalog = index("\"sad\":${entry(byteArrayOf(1))},\"thinking\":${entry(path = "thinking.riv")}")
        val events = mutableMapOf<String, JarvysVerifiedAnimation?>()
        fails { cache.refresh("default", linkedSetOf("sad", "thinking"), token) { id, value -> events[id] = value } }
        assertNull(events["sad"])
        assertNotNull(events["thinking"])
    }
    @Test fun catalogDoesNotImposeAnUndocumentedFiveMiBLimit() {
        val declared = 9_000_000L
        val selection = parse(index().replace("\"bytes\":90001", "\"bytes\":$declared"))
        assertEquals(declared, selection.sad!!.bytes)
    }
    @Test fun refreshGateDeduplicatesInflightAndThirtySecondCooldown() {
        var now = 0L
        val gate = JarvysAnimationRefreshGate { now }
        assertTrue(gate.tryStart())
        now = 30_000
        assertFalse(gate.tryStart())
        gate.finished()
        assertTrue(gate.tryStart())
        gate.finished()
        now = 59_999
        assertFalse(gate.tryStart())
        now = 60_000
        assertTrue(gate.tryStart())
    }
    @Test fun processCookieHandlerFailsClosedWithoutOpeningConnection() {
        val previous = CookieHandler.getDefault()
        try {
            CookieHandler.setDefault(CookieManager())
            var opened = false
            val transport = JarvysAnimationHttpTransport { opened = true; throw AssertionError("Must not open") }
            fails { transport.get(URI(JarvysAnimationSite.INDEX), token) }
            assertFalse(opened)
        } finally { CookieHandler.setDefault(previous) }
    }
}
