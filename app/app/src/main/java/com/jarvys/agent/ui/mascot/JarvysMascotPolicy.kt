package com.jarvys.agent.ui.mascot

/** Only these typed, evidence-backed projections are supported by the bounded live integration. */
internal fun jarvysMascotKnownLiveMode(mode: Int?): Boolean = when (mode) {
    2, 6, 7, 8 -> true
    else -> false
}

/** Unknown activity is static artwork, not an implicit Idle or a paused native instance. */
internal fun jarvysMascotMayPrepare(
    mode: Int?, optedIn: Boolean, resumed: Boolean, visible: Boolean,
): Boolean = jarvysMascotKnownLiveMode(mode) && optedIn && resumed && visible
