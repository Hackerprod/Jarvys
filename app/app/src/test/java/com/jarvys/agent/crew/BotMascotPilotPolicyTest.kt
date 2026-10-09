package com.jarvys.agent.crew

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** Host policy tests only: no Android view, library initialization, JNI or rendering. */
class BotMascotPilotPolicyTest {
    @Test fun startsDisabledAndDoesNotReconstituteAnOptIn() {
        val fresh = MascotPilotSession()
        assertFalse(fresh.optedIn)
        val started = fresh.start(resumed = true, visible = true)
        assertTrue(started.optedIn)
        assertFalse(MascotPilotSession().optedIn) // new composition/process/key
        assertFalse(started.stop().optedIn)
    }

    @Test fun startNeedsBothResumedAndVisibleAndRepeatedStartDoesNotCreateAnotherTicket() {
        val fresh = MascotPilotSession()
        assertEquals(fresh, fresh.start(false, true))
        assertEquals(fresh, fresh.start(true, false))
        assertEquals(fresh, fresh.start(false, false))
        val started = fresh.start(true, true)
        assertEquals(started, started.start(true, true))
    }

    @Test fun stopBackgroundOffscreenAndReplacementInvalidateTheOldTicket() {
        val started = MascotPilotSession().start(true, true)
        val ticket = started.generation
        assertTrue(started.accepts(ticket, true, true))
        assertFalse(started.accepts(ticket, false, true))
        assertFalse(started.accepts(ticket, true, false))
        val stopped = started.stop()
        assertFalse(stopped.accepts(ticket, true, true))
        assertFalse(stopped.start(true, true).accepts(ticket, true, true))
        assertFalse(MascotPilotSession().accepts(ticket, true, true))
    }

    @Test fun rendererNeedsEveryGate() {
        assertTrue(mascotPilotMayRender(true, true, true, true))
        assertFalse(mascotPilotMayRender(false, true, true, true))
        assertFalse(mascotPilotMayRender(true, false, true, true))
        assertFalse(mascotPilotMayRender(true, true, false, true))
        assertFalse(mascotPilotMayRender(true, true, true, false))
    }

    @Test fun systemReductionCannotBeOverriddenByManualNormalMotion() {
        assertFalse(mascotPilotEffectiveReduced(false, false))
        assertTrue(mascotPilotEffectiveReduced(true, false))
        assertTrue(mascotPilotEffectiveReduced(false, true))
        assertTrue(mascotPilotEffectiveReduced(true, true))
    }

    @Test fun modeInputsAreExplicitNineValuesAndTwoSlotsAreIndependent() {
        assertEquals((0..8).toList(), MascotPilotMode.entries.map { it.wire })
        val original = MascotPilotControlsState()
        val aChanged = original.withMode(0, MascotPilotMode.ERROR)
        assertEquals(MascotPilotMode.ERROR, aChanged.a)
        assertEquals(original.b, aChanged.b)
        val bChanged = aChanged.withMode(1, MascotPilotMode.QUEUED)
        assertEquals(aChanged.a, bChanged.a)
        assertEquals(MascotPilotMode.QUEUED, bChanged.b)
        assertEquals(MascotPilotMode.IDLE, original.a)
    }

    @Test fun nativeReadbackMustBeAnExactInRangeInteger() {
        (0..8).forEach { assertEquals(it, mascotPilotNativeMode(it.toFloat())) }
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 9f, 0.2f, 7.99f)
            .forEach { assertNull(mascotPilotNativeMode(it)) }
    }

    @Test fun noStartDoesNotReadOrInitialize() = runBlocking {
        var loads = 0
        var inits = 0
        val result = prepareMascotPilot({ false }, { loads++; byteArrayOf(1) }, { inits++; true })
        assertSame(MascotPilotPreparation.Stale, result)
        assertEquals(0, loads)
        assertEquals(0, inits)
    }

    @Test fun invalidPackageNeverInitializesNativeRuntime() = runBlocking {
        var inits = 0
        try {
            prepareMascotPilot({ true }, { throw IllegalArgumentException("Package rejected") }, { inits++; true })
            fail("Expected rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, inits)
    }

    @Test fun dismissedLoadCannotInitializeOrPublishIntoNewSession() = runBlocking {
        var session = MascotPilotSession().start(true, true)
        val oldTicket = session.generation
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var inits = 0
        val operation = async {
            prepareMascotPilot({ session.accepts(oldTicket, true, true) }, {
                entered.complete(Unit)
                release.await()
                byteArrayOf(1, 2)
            }, { inits++; true })
        }
        entered.await()
        session = session.stop().start(true, true)
        release.complete(Unit)
        assertSame(MascotPilotPreparation.Stale, operation.await())
        assertEquals(0, inits)
    }

    @Test fun cancelledCompositionNeverInitializes() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var inits = 0
        val operation = launch {
            prepareMascotPilot({ true }, {
                entered.complete(Unit)
                release.await()
                byteArrayOf(1)
            }, { inits++; true })
        }
        entered.await()
        operation.cancelAndJoin()
        release.complete(Unit)
        yield()
        assertEquals(0, inits)
    }

    @Test fun verifiedBytesKeepIdentityAndInitFailureCannotProduceReady() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        val calls = mutableListOf<String>()
        val ready = prepareMascotPilot({ true }, { calls += "verify"; bytes }, { calls += "init"; true })
        assertEquals(listOf("verify", "init"), calls)
        assertSame(bytes, (ready as MascotPilotPreparation.Ready).bytes)
        assertSame(MascotPilotPreparation.InitFailed, prepareMascotPilot({ true }, { bytes }, { false }))
    }

    @Test fun abiDiagnosticAcceptsOnlyFixedCategories() {
        assertEquals(setOf(MascotPilotAbi.ARM64, MascotPilotAbi.X86_64, MascotPilotAbi.OTHER),
            mascotPilotAbis(listOf("arm64-v8a", "x86_64", "untrusted device/path", "private string")))
        assertEquals(setOf(MascotPilotAbi.OTHER), mascotPilotAbis(emptyList()))
    }

    @Test fun diagnosticsDistinguishRequestReadbackPixelsAndManualOpinion() {
        val diagnostic = mascotPilotDiagnostics(35, MascotPilotPhase.NATIVE_TEST, 500,
            listOf(MascotPilotMode.THINKING, MascotPilotMode.WORKING), true,
            listOf(MascotPilotEvidence(0, false, true), MascotPilotEvidence()),
            listOf(setOf(MascotPilotVisualCheck(MascotPilotMode.IDLE, true)), emptySet()))
        assertTrue(diagnostic.contains("A.requestedMode=1:Thinking"))
        assertTrue(diagnostic.contains("A.nativeVmiMode=0"))
        assertTrue(diagnostic.contains("A.pixelsAvailable=true"))
        assertTrue(diagnostic.contains("A.manualVisualChecks=0:reduced"))
        assertTrue(diagnostic.contains("activeStateObserver=UNAVAILABLE"))
        assertTrue(diagnostic.contains("ux40Complete=false"))
        assertFalse(diagnostic.contains("botId="))
        assertFalse(diagnostic.contains("source="))
        assertFalse(diagnostic.contains("path="))
        assertFalse(diagnostic.contains("exception="))
        assertTrue(diagnostic.length < 2_000)
    }
}
