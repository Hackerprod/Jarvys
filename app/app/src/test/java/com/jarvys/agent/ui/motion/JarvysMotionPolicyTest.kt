package com.jarvys.agent.ui.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvysMotionPolicyTest {
    @Test fun reducedModeReadsEverySystemAnimationScaleAsAStaticPreference() {
        assertFalse(JarvysMotionPolicy.reducedByScales(1f, 1f, 1f))
        assertTrue(JarvysMotionPolicy.reducedByScales(0f, 1f, 1f))
        assertTrue(JarvysMotionPolicy.reducedByScales(1f, 0f, 1f))
        assertTrue(JarvysMotionPolicy.reducedByScales(1f, 1f, 0f))
    }

    @Test fun activityVisibilityAndReducedMotionAllGateContinuousEffects() {
        assertTrue(JarvysMotionPolicy.shouldAnimate(active = true, reduced = false))
        assertFalse(JarvysMotionPolicy.shouldAnimate(active = false, reduced = false))
        assertFalse(JarvysMotionPolicy.shouldAnimate(active = true, reduced = true))
        assertFalse(JarvysMotionPolicy.shouldAnimate(active = false, reduced = true))
    }
}
