package com.jarvys.agent.ui.chat

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

/** Original native dot-wave model. Positions and radii are fixed; only ink opacity changes. */
internal object LatticePresence {
    const val CycleMillis = 864
    const val IdleOpacity = 0.15f
    const val CenterOpacity = 0.08f
    const val StaticOpacity = 0.65f

    /** Clockwise perimeter, starting at the upper-left corner. The center has no wave index. */
    fun perimeterIndex(row: Int, column: Int): Int {
        require(row in 0..2 && column in 0..2)
        return when {
            row == 0 -> column
            column == 2 -> row + 2
            row == 2 -> 6 - column
            column == 0 -> 8 - row
            else -> -1
        }
    }

    /** A smooth periodic light travels over stationary dots, with no timers or per-dot state. */
    fun opacity(row: Int, column: Int, phase: Float?): Float {
        val index = perimeterIndex(row, column)
        if (index < 0) return CenterOpacity
        if (phase == null || !phase.isFinite()) return StaticOpacity
        val head = (phase - kotlin.math.floor(phase)) * 8f
        val delta = abs(index - head)
        val distance = min(delta, 8f - delta)
        val wave = if (distance >= 2.5f) 0f else
            (0.5 + 0.5 * cos(PI * distance / 2.5)).toFloat()
        return IdleOpacity + (1f - IdleOpacity) * wave
    }
}
