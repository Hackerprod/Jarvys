package com.jarvys.agent.apkfactory

import org.junit.Assert.*
import org.junit.Test

class FactoryAudioPlayerTest {
    private class FakeTrack : FactoryAudioPlayer.Track {
        var writes = 0; var plays = 0; var stops = 0; var releases = 0
        var writeFails = false; var shortWrite = false; var releaseFails = false; var stopFails = false; var playFails = false
        var marker: (() -> Unit)? = null
        override fun write(pcm: FactoryAudioPcm): Int { writes++; if (writeFails) error("write"); return if (shortWrite) 0 else pcm.pcmBytes }
        override fun marker(frames: Int, callback: () -> Unit) { marker = callback }
        override fun play() { plays++; if (playFails) error("play") }
        override fun stop() { stops++; if (stopFails) error("stop") }
        override fun release() { releases++; if (releaseFails) error("release") }
    }
    private class FakePlatform : FactoryAudioPlayer.Platform {
        val track = FakeTrack(); var allocationFails = false; var main = false; var granted = true; var focusCalls = 0
        var loss: (() -> Unit)? = null; var route: (() -> Unit)? = null; var deadline: (() -> Unit)? = null
        var time = 0L; var unobserved = 0; var abandoned = 0
        override fun requireMain() { check(main) }
        override fun requireWorker() { check(!main) }
        override fun create(pcm: FactoryAudioPcm): FactoryAudioPlayer.Track { if (allocationFails) error("allocate"); return track }
        override fun acquireFocus(onLoss: () -> Unit): Boolean { focusCalls++; loss = onLoss; return granted }
        override fun abandonFocus() { abandoned++ }
        override fun observeRoutes(onChange: () -> Unit) { route = onChange }
        override fun unobserveRoutes() { unobserved++ }
        override fun now() = time
        override fun schedule(delayMs: Long, callback: () -> Unit): Any { deadline = callback; return callback }
        override fun cancel(token: Any) { deadline = null }
    }
    private fun fixture(): Triple<FakePlatform, FactoryAudioPlayer, FactoryAudioPlayer.Prepared> {
        val platform = FakePlatform(); val player = FactoryAudioPlayer(platform)
        val prepared = player.prepare(FactoryAudioPcm.parseOwned(syntheticWav()))
        assertEquals(1, platform.track.writes); assertEquals(0, platform.track.plays)
        platform.main = true
        return Triple(platform, player, prepared)
    }
    @Test fun oneShotAndMarkerNeverReportHeard() {
        val (p, player, prepared) = fixture(); val results = mutableListOf<FactoryAudioPlayer.StopResult>()
        assertTrue(player.start(prepared, { true }, results::add))
        p.track.marker!!.invoke(); p.track.marker!!.invoke(); p.loss!!.invoke()
        assertEquals(1, results.size); assertTrue(results.single().cleanupConfirmed)
        assertTrue(results.single().reason.contains("unknown"))
        assertFalse(player.start(prepared, { true }) { }); assertEquals(1, p.track.plays)
    }
    @Test fun failedFocusConsumesPreparedWithoutPlay() {
        val (p, player, prepared) = fixture(); p.granted = false
        assertFalse(player.start(prepared, { true }) { assertTrue(it.cleanupConfirmed) })
        assertFalse(player.start(prepared, { true }) { }); assertEquals(1, p.focusCalls); assertEquals(0, p.track.plays)
    }
    @Test fun authorizationCheckedBeforeFocusAndAgainBeforePlay() {
        val (p, player, prepared) = fixture(); var calls = 0
        assertFalse(player.start(prepared, { ++calls == 1 }) { })
        assertEquals(2, calls); assertEquals(1, p.focusCalls); assertEquals(0, p.track.plays)
    }
    @Test fun noAuthorizationSkipsFocus() {
        val (p, player, prepared) = fixture()
        assertFalse(player.start(prepared, { false }) { }); assertEquals(0, p.focusCalls)
    }
    @Test fun focusLossAndRouteChangeStopWithoutReplay() {
        for (focus in listOf(true, false)) {
            val (p, player, prepared) = fixture(); var notices = 0
            player.start(prepared, { true }) { notices++ }
            (if (focus) p.loss else p.route)!!.invoke()
            assertEquals(1, notices); assertEquals(1, p.track.releases); assertEquals(1, p.unobserved)
            assertEquals(1, p.abandoned); assertNull(p.deadline)
        }
    }
    @Test fun deadlineUsesMonotonicTime() {
        val (p, player, prepared) = fixture(); var stopped = false
        player.start(prepared, { true }) { stopped = true }
        p.time = 9; p.deadline!!.invoke(); assertFalse(stopped)
        p.time = 10; p.deadline!!.invoke(); assertTrue(stopped)
    }
    @Test fun releaseFailureRetainsHandleForExplicitRetry() {
        val (p, player, prepared) = fixture(); p.track.releaseFails = true
        player.start(prepared, { true }) { assertFalse(it.cleanupConfirmed) }
        assertFalse(player.stop().cleanupConfirmed)
        p.track.releaseFails = false
        assertTrue(player.retryCleanup().cleanupConfirmed); assertEquals(2, p.track.releases)
    }
    @Test fun stopFailureIsConservativelyUnconfirmedUntilRetry() {
        val (p, player, prepared) = fixture(); p.track.stopFails = true
        player.start(prepared, { true }) { }
        assertFalse(player.stop().cleanupConfirmed); assertTrue(player.retryCleanup().cleanupConfirmed)
    }
    @Test fun shortWriteIsRetainedForMainThreadDiscard() {
        val p = FakePlatform(); p.track.shortWrite = true; val player = FactoryAudioPlayer(p)
        val prepared = player.prepare(FactoryAudioPcm.parseOwned(syntheticWav()))
        assertNotNull(prepared.preparationFailure); assertEquals(0, p.track.releases)
        p.main = true; assertTrue(player.discard(prepared).cleanupConfirmed); assertEquals(0, p.track.plays)
    }
    @Test fun allocationAndWriteFailureProduceDisposablePreparedResults() {
        for (allocation in listOf(true, false)) {
            val p = FakePlatform(); p.allocationFails = allocation; p.track.writeFails = !allocation
            val player = FactoryAudioPlayer(p)
            val prepared = player.prepare(FactoryAudioPcm.parseOwned(syntheticWav()))
            assertNotNull(prepared.preparationFailure)
            p.main = true
            assertFalse(player.start(prepared, { true }) { assertTrue(it.cleanupConfirmed) })
            assertEquals(0, p.focusCalls); assertEquals(0, p.track.plays)
        }
    }
    @Test fun threadBoundariesAreEnforced() {
        val (p, player, prepared) = fixture()
        try { player.prepare(FactoryAudioPcm.parseOwned(syntheticWav())); fail("main prepare") } catch (_: IllegalStateException) { }
        p.main = false
        try { player.start(prepared, { true }) { }; fail("worker start") } catch (_: IllegalStateException) { }
        try { player.stop(); fail("worker stop") } catch (_: IllegalStateException) { }
        p.main = true; assertTrue(player.discard(prepared).cleanupConfirmed)
    }
    @Test fun playFailureStopsAndCannotRetry() {
        val (p, player, prepared) = fixture(); p.track.playFails = true
        assertFalse(player.start(prepared, { true }) { assertTrue(it.cleanupConfirmed) })
        assertFalse(player.start(prepared, { true }) { }); assertEquals(1, p.track.plays)
    }
}
