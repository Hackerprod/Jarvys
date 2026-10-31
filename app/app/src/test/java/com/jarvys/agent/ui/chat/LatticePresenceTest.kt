package com.jarvys.agent.ui.chat

import org.junit.Assert.*
import org.junit.Test

class LatticePresenceTest {
    private val ring = listOf(0 to 0, 0 to 1, 0 to 2, 1 to 2, 2 to 2, 2 to 1, 2 to 0, 1 to 0)

    @Test fun eightDistinctClockwisePositionsSurroundTheUnindexedCenter() {
        assertEquals((0..7).toList(), ring.map { (r, c) -> LatticePresence.perimeterIndex(r, c) })
        assertEquals(-1, LatticePresence.perimeterIndex(1, 1))
    }

    @Test fun eachEighthOfTheCycleIlluminatesTheNextPerimeterDot() {
        ring.forEachIndexed { index, point ->
            val phase = index / 8f
            val opacities = ring.map { (r, c) -> LatticePresence.opacity(r, c, phase) }
            assertEquals(index, opacities.indices.maxBy { opacities[it] })
            assertEquals(1f, LatticePresence.opacity(point.first, point.second, phase), 0.00001f)
        }
        assertEquals(864, LatticePresence.CycleMillis)
    }

    @Test fun centerNeverPulsesAndStaysFainterThanEveryPerimeterDot() {
        repeat(1000) { i ->
            val phase = i / 1000f
            assertEquals(0.08f, LatticePresence.opacity(1, 1, phase), 0f)
            ring.forEach { (r, c) -> assertTrue(LatticePresence.opacity(r, c, phase) >= 0.15f) }
        }
        assertEquals(0.08f, LatticePresence.opacity(1, 1, null), 0f)
    }

    @Test fun allWaveValuesAreFiniteAndBounded() {
        for (i in -1000..2000) for ((r, c) in ring) {
            val alpha = LatticePresence.opacity(r, c, i / 1000f)
            assertTrue(alpha.isFinite() && alpha in 0.15f..1f)
        }
    }

    @Test fun periodicWrapDoesNotJumpOrAccumulateTime() {
        ring.forEach { (r, c) ->
            assertEquals(LatticePresence.opacity(r, c, 0f), LatticePresence.opacity(r, c, 1f), 0f)
            assertEquals(LatticePresence.opacity(r, c, 0.375f), LatticePresence.opacity(r, c, 200.375f), 0f)
            assertEquals(LatticePresence.opacity(r, c, 0.99999f), LatticePresence.opacity(r, c, 0.00001f), 0.0002f)
        }
    }

    @Test fun staticPatternHasNoElapsedTimeDependency() {
        ring.forEach { (r, c) -> assertEquals(0.65f, LatticePresence.opacity(r, c, null), 0f) }
    }

    @Test fun invalidPhasesFallBackToHonestStaticInk() {
        for (phase in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            ring.forEach { (r, c) -> assertEquals(0.65f, LatticePresence.opacity(r, c, phase), 0f) }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun positionsOutsideTheLatticeAreRejected() { LatticePresence.perimeterIndex(3, 0) }
}
