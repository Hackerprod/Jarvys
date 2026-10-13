package com.jarvys.agent.ui.mascot

import com.jarvys.agent.JarvysVerifiedAnimation
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class JarvysHeaderActivityPolicyTest {
    private fun asset(id: String) = JarvysVerifiedAnimation(File("$id.riv"), "a".repeat(64), 600L, "default", id)
    private fun controller(initialTap: String? = null) = JarvysHeaderOneShotController(
        JarvysFailurePlaybackGate(), JarvysFailurePlaybackGate(), JarvysHeaderTapPlaybackGate(initialTap))
    private fun inputs(kind: JarvysHeaderOneShotKind, id: String = "event", eligible: Boolean = true,
        handle: JarvysVerifiedAnimation? = asset(if (kind == JarvysHeaderOneShotKind.REQUESTED) "working" else kind.animationId)): JarvysHeaderOneShotInputs = when (kind) {
        JarvysHeaderOneShotKind.SAD -> JarvysHeaderOneShotInputs(eligible, failedRunId = id, sadAsset = handle)
        JarvysHeaderOneShotKind.INTERRUPTED -> JarvysHeaderOneShotInputs(eligible, interruptedRunId = id, interruptedAsset = handle)
        JarvysHeaderOneShotKind.TAUNT -> JarvysHeaderOneShotInputs(eligible, tauntEventId = id, tauntAsset = handle)
        JarvysHeaderOneShotKind.REQUESTED -> JarvysHeaderOneShotInputs(eligible,
            requestedAnimationEventId = id, requestedAnimationId = "working", requestedAnimationAsset = handle)
    }
    @Test fun liveActivityRequiresNonblankCurrentKeyAndMatchingAssetId() {
        for ((activity, id) in listOf(JarvysHeaderActivity.WORKING to "working", JarvysHeaderActivity.THINKING to "thinking", JarvysHeaderActivity.WAITING to "waiting")) {
            val request = checkNotNull(jarvysHeaderActivityRequest(activity, "live", asset(id)))
            assertEquals(id, request.timeline.remoteId); assertFalse(request.timeline.oneShot)
            assertNull(jarvysHeaderActivityRequest(activity, null, asset(id)))
            assertNull(jarvysHeaderActivityRequest(activity, " ", asset(id)))
            assertNull(jarvysHeaderActivityRequest(activity, "live", null))
            assertNull(jarvysHeaderActivityRequest(activity, "live", asset("sad")))
        }
        assertNull(jarvysHeaderActivityRequest(null, "old", asset("thinking")))
    }
    @Test fun activityArrivalCanServeOnlyTheCurrentRequestAndIdentity() {
        val original = jarvysHeaderActivityRequest(JarvysHeaderActivity.THINKING, "wait-1", asset("thinking"))
        assertNotEquals(original, jarvysHeaderActivityRequest(JarvysHeaderActivity.THINKING, "wait-2", asset("thinking")))
        assertNotEquals(original, jarvysHeaderActivityRequest(JarvysHeaderActivity.THINKING, "wait-1", asset("thinking").copy(sha256 = "b".repeat(64))))
        assertNull(jarvysHeaderActivityRequest(null, null, asset("thinking")))
    }
    @Test fun everyIdentityFieldParticipatesInRetirement() {
        val a = asset("sad")
        assertTrue(jarvysHeaderSameAnimation(a, a.copy()))
        for (b in listOf(null, a.copy(packageId = "different"), a.copy(animationId = "thinking"), a.copy(bytes = 601), a.copy(sha256 = "b".repeat(64))))
            assertFalse(jarvysHeaderSameAnimation(a, b))
        assertFalse(jarvysHeaderSameAnimation(null, null))
    }
    @Test fun allOneShotsRetirePermanentlyAcrossNullNewIdentityAndRestoration() {
        for (kind in JarvysHeaderOneShotKind.entries) {
            val a = asset(if (kind == JarvysHeaderOneShotKind.REQUESTED) "working" else kind.animationId)
            for (b in listOf(null, a.copy(packageId = "new"), a.copy(animationId = "thinking"), a.copy(bytes = 601), a.copy(sha256 = "b".repeat(64)))) {
                val control = controller()
                val current = checkNotNull(control.update(inputs(kind, handle = a)))
                assertFalse(inputs(kind, handle = b).isCurrent(current))
                assertNull(control.update(inputs(kind, handle = b)))
                assertNull(control.update(inputs(kind, handle = a)))
            }
        }
    }
    @Test fun missedHiddenAndWrongAssetEventsCannotReplayOnArrival() {
        for (kind in JarvysHeaderOneShotKind.entries) for (initial in listOf(inputs(kind, handle = null), inputs(kind, eligible = false), inputs(kind, handle = asset("thinking")))) {
            val control = controller(); assertNull(control.update(initial)); assertNull(control.update(inputs(kind)))
        }
    }
    @Test fun coverageLossClearsCurrentShotPermanently() {
        for (kind in JarvysHeaderOneShotKind.entries) {
            val control = controller(); val current = checkNotNull(control.update(inputs(kind)))
            assertFalse(inputs(kind, eligible = false).isCurrent(current))
            assertNull(control.update(inputs(kind, eligible = false))); assertNull(control.update(inputs(kind)))
        }
    }
    @Test fun tapsReplaceRatherThanQueueAndOldCompletionCannotDismissNewShot() {
        val control = controller()
        val old = checkNotNull(control.update(inputs(JarvysHeaderOneShotKind.SAD)))
        val tap1 = checkNotNull(control.update(inputs(JarvysHeaderOneShotKind.TAUNT, "tap-1")))
        val tap2 = checkNotNull(control.update(inputs(JarvysHeaderOneShotKind.TAUNT, "tap-2")))
        assertEquals(tap2, control.finish(old)); assertEquals(tap2, control.finish(tap1))
        assertNull(control.finish(tap2)); assertNull(control.update(inputs(JarvysHeaderOneShotKind.TAUNT, "tap-2")))
    }
    @Test fun activeTapConsumesNewTerminalsWithoutPlayingThemAfterCompletion() {
        for (kind in listOf(JarvysHeaderOneShotKind.SAD, JarvysHeaderOneShotKind.INTERRUPTED)) {
            val control = controller(); val tap = checkNotNull(control.update(inputs(JarvysHeaderOneShotKind.TAUNT, "tap-1")))
            val both = inputs(kind).copy(tauntEventId = "tap-1", tauntAsset = asset("taunt"))
            assertEquals(tap, control.update(both)); assertNull(control.finish(tap)); assertNull(control.update(both))
        }
    }
    @Test fun simultaneousTapWinsAndUnavailableTapDoesNotQueueATerminal() {
        for (available in listOf(false, true)) {
            val control = controller()
            val both = inputs(JarvysHeaderOneShotKind.SAD).copy(tauntEventId = "tap", tauntAsset = if (available) asset("taunt") else null)
            val selected = control.update(both)
            if (available) { assertEquals(JarvysHeaderOneShotKind.TAUNT, selected?.kind); control.finish(checkNotNull(selected)) }
            else assertNull(selected)
            assertNull(control.update(both.copy(tauntAsset = asset("taunt"))))
        }
    }
    @Test fun replacementTapCacheMissRetiresOldShotWithoutLateReplay() {
        val control = controller(); val old = checkNotNull(control.update(inputs(JarvysHeaderOneShotKind.TAUNT, "tap-1")))
        assertNull(control.update(inputs(JarvysHeaderOneShotKind.TAUNT, "tap-2", handle = null)))
        assertNull(control.finish(old)); assertNull(control.update(inputs(JarvysHeaderOneShotKind.TAUNT, "tap-2")))
    }
    @Test fun terminalNamespacesRemainDistinctAndConflictingInputsFailClosed() {
        val control = controller(); val old = checkNotNull(control.update(inputs(JarvysHeaderOneShotKind.SAD, "same")))
        val current = checkNotNull(control.update(inputs(JarvysHeaderOneShotKind.INTERRUPTED, "same")))
        assertEquals(current, control.finish(old))
        val conflicting = inputs(JarvysHeaderOneShotKind.SAD, "new").copy(interruptedRunId = "stop", interruptedAsset = asset("interrupted"))
        assertNull(control.update(conflicting)); assertNull(control.update(conflicting.copy(failedRunId = null)))
    }
    @Test fun initialTapIsConsumedOnRemountAndHasNoAccumulatedTerminalCap() {
        val control = controller("old")
        assertNull(control.update(inputs(JarvysHeaderOneShotKind.TAUNT, "old")))
        assertNull(control.update(JarvysHeaderOneShotInputs(true)))
        assertNull(control.update(inputs(JarvysHeaderOneShotKind.TAUNT, "old")))
        repeat(1024) { index -> val request = checkNotNull(control.update(inputs(JarvysHeaderOneShotKind.TAUNT, "tap-$index"))); assertNull(control.finish(request)) }
    }
    @Test fun terminalGateSurvivesControllerDisposalAndRemount() {
        val failures = JarvysFailurePlaybackGate(); val interrupted = JarvysFailurePlaybackGate()
        val before = JarvysHeaderOneShotController(failures, interrupted, JarvysHeaderTapPlaybackGate())
        checkNotNull(before.update(inputs(JarvysHeaderOneShotKind.INTERRUPTED))); before.clear()
        assertNull(before.update(inputs(JarvysHeaderOneShotKind.INTERRUPTED)))
        val after = JarvysHeaderOneShotController(failures, interrupted, JarvysHeaderTapPlaybackGate())
        assertNull(after.update(inputs(JarvysHeaderOneShotKind.INTERRUPTED)))
    }
    @Test fun finishedShotResumesCurrentActivityOrIdle() {
        val control = controller(); val shot = checkNotNull(control.update(inputs(JarvysHeaderOneShotKind.TAUNT)))
        val current = jarvysHeaderActivityRequest(JarvysHeaderActivity.WAITING, "current", asset("waiting"))
        assertEquals(JarvysHeaderTimeline.TAUNT, jarvysHeaderSelectedTimeline(shot, current))
        assertEquals(JarvysHeaderTimeline.WAITING, jarvysHeaderSelectedTimeline(control.finish(shot), current))
        assertEquals(JarvysHeaderTimeline.IDLE, jarvysHeaderSelectedTimeline(null, null))
    }
    @Test fun requestedAnimationOverridesCurrentActivityOnceAndThenResumesIt() {
        val control = controller()
        val requested = checkNotNull(control.update(inputs(JarvysHeaderOneShotKind.REQUESTED, "request-working")))
        val current = jarvysHeaderActivityRequest(JarvysHeaderActivity.THINKING, "current", asset("thinking"))
        assertEquals(JarvysHeaderOneShotKind.REQUESTED, requested.kind)
        assertEquals(JarvysHeaderTimeline.WORKING.copy(oneShot = true), requested.timeline)
        assertEquals(requested.timeline, jarvysHeaderSelectedTimeline(requested, current))
        assertEquals(current?.timeline, jarvysHeaderSelectedTimeline(control.finish(requested), current))
        assertNull(control.update(inputs(JarvysHeaderOneShotKind.REQUESTED, "request-working")))

        val idle = JarvysHeaderOneShotInputs(true, requestedAnimationEventId = "request-idle",
            requestedAnimationId = "idle")
        val idleRequest = checkNotNull(controller().update(idle))
        assertEquals(JarvysHeaderTimeline.IDLE.copy(oneShot = true), idleRequest.timeline)
        assertNull(controller().update(idle.copy(requestedAnimationId = "unknown")))
    }
    @Test fun futureNamesAreInspectedAndNeverInvented() {
        for ((timeline, oneShot) in listOf(JarvysHeaderTimeline.WAITING to false, JarvysHeaderTimeline.INTERRUPTED to true, JarvysHeaderTimeline.TAUNT to true)) {
            assertNull(timeline.artboard); assertNull(timeline.animation); assertEquals(oneShot, timeline.oneShot)
        }
    }
    @Test fun observedNamesArePreservedAfterCaseSeparatorNormalization() {
        assertEquals(JarvysHeaderNativeBinding("Canvas", "TA_UN-T"), jarvysHeaderDiscoverBinding("taunt", listOf(JarvysHeaderArtboardMetadata("Canvas", listOf("Idle", "TA_UN-T")))))
        assertEquals(JarvysHeaderNativeBinding("Interrupted", "Once"), jarvysHeaderDiscoverBinding("interrupted", listOf(JarvysHeaderArtboardMetadata("Interrupted", listOf("Once")), JarvysHeaderArtboardMetadata("Other", listOf("Idle")))))
        assertEquals(JarvysHeaderNativeBinding("Waiting", "waiting"), jarvysHeaderDiscoverBinding("waiting", listOf(JarvysHeaderArtboardMetadata("Waiting", listOf("waiting")), JarvysHeaderArtboardMetadata("Other", listOf("WAITING")))))
    }
    @Test fun dedicatedCatalogFileCanHaveASoleTimelineWithAnyObservedName() {
        assertEquals(JarvysHeaderNativeBinding("Canvas 2", "Timeline 1"), jarvysHeaderDiscoverBinding("waiting", listOf(JarvysHeaderArtboardMetadata("Canvas 2", listOf("Timeline 1")))))
    }
    @Test fun ambiguousMissingEmptyDuplicateAndUnsupportedMetadataFailClosed() {
        for (boards in listOf(emptyList(), listOf(JarvysHeaderArtboardMetadata("Waiting", emptyList())),
            listOf(JarvysHeaderArtboardMetadata("WaitingMascot", listOf("Idle", "Timeline"))),
            listOf(JarvysHeaderArtboardMetadata("Waiting", listOf("", "waiting"))),
            listOf(JarvysHeaderArtboardMetadata("", listOf("waiting"))),
            listOf(JarvysHeaderArtboardMetadata("A", listOf("waiting", "waiting"))),
            listOf(JarvysHeaderArtboardMetadata("A", listOf("waiting")), JarvysHeaderArtboardMetadata("A", listOf("Idle"))),
            listOf(JarvysHeaderArtboardMetadata("A", listOf("waiting")), JarvysHeaderArtboardMetadata("B", listOf("WAITING")))))
            assertNull(jarvysHeaderDiscoverBinding("waiting", boards))
        assertNull(jarvysHeaderDiscoverBinding("sad", listOf(JarvysHeaderArtboardMetadata("A", listOf("sad")))))
    }
    @Test fun framePublicationWaitsForBindingAndStopsAtRelease() {
        assertFalse(jarvysHeaderMayPublishFrame(false, false)); assertFalse(jarvysHeaderMayPublishFrame(false, true))
        assertFalse(jarvysHeaderMayPublishFrame(true, true)); assertTrue(jarvysHeaderMayPublishFrame(true, false))
    }
}
