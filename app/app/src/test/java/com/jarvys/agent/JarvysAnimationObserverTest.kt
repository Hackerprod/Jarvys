package com.jarvys.agent

import com.jarvys.agent.LocalDiagnostics.AnimationId
import com.jarvys.agent.LocalDiagnostics.AnimationStage
import com.jarvys.agent.LocalDiagnostics.AnimationStatus
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
class JarvysAnimationObserverTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Event(val stage: AnimationStage, val status: AnimationStatus, val id: AnimationId)
    private val body = byteArrayOf(1, 2, 3, 4)
    private val digest get() = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    private fun cache(events: MutableList<Event>, corrupt: Boolean = false): JarvysAnimationCache {
        val catalog = """{"schema":1,"packages":{"default":{"animations":{"sad":{"url":"https://jarvys-agent.com/sad.riv","bytes":4,"sha256":"$digest"}}}}}"""
        val transport = object : JarvysAnimationTransport {
            override fun get(uri: URI, token: CancellationToken): JarvysAnimationTransport.Response {
                val data = if (uri.toString() == JarvysAnimationSite.INDEX) catalog.toByteArray()
                    else if (corrupt) byteArrayOf(4, 3, 2, 1) else body
                return object : JarvysAnimationTransport.Response {
                    override val status = 200
                    override val contentLength = data.size.toLong()
                    override val body = ByteArrayInputStream(data)
                    override fun close() { body.close() }
                }
            }
        }
        return JarvysAnimationCache(temporary.newFolder(), transport) { Long.MAX_VALUE }.apply {
            diagnostics = JarvysAnimationObserver { stage, status, id, _ -> events.add(Event(stage, status, id)) }
        }
    }
    @Test fun separatesIndexDownloadIntegrityAndCacheReadiness() {
        val events = mutableListOf<Event>()
        val cache = cache(events)
        cache.refresh("default", CancellationToken.cancellable()) { }
        assertTrue(events.contains(Event(AnimationStage.INDEX, AnimationStatus.READY, AnimationId.CATALOG)))
        assertTrue(events.contains(Event(AnimationStage.DOWNLOAD, AnimationStatus.READY, AnimationId.SAD)))
        assertTrue(events.contains(Event(AnimationStage.INTEGRITY, AnimationStatus.READY, AnimationId.SAD)))
        assertNotNull(cache.cached("default", CancellationToken.cancellable()))
        assertEquals(Event(AnimationStage.CACHE, AnimationStatus.READY, AnimationId.SAD), events.last())
    }
    @Test fun integrityFailureNeverReportsDownloadReady() {
        val events = mutableListOf<Event>()
        val cache = cache(events, corrupt = true)
        try { cache.refresh("default", CancellationToken.cancellable()) { }; fail("Expected integrity failure") }
        catch (_: IOException) { }
        assertTrue(events.contains(Event(AnimationStage.INTEGRITY, AnimationStatus.FAILED, AnimationId.SAD)))
        assertFalse(events.contains(Event(AnimationStage.DOWNLOAD, AnimationStatus.READY, AnimationId.SAD)))
    }
    @Test fun diagnosticsFailureCannotPreventSuccessfulPublication() {
        val cache = cache(mutableListOf())
        cache.diagnostics = JarvysAnimationObserver { _, _, _, _ -> throw IllegalStateException("synthetic observer failure") }
        var published: JarvysVerifiedAnimation? = null
        cache.refresh("default", CancellationToken.cancellable()) { if (it != null) published = it }
        assertNotNull(published)
    }
}
