package com.jarvys.agent.tasks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local invalidation stream for append-only task/run stores; UI consumers re-read on change. */
object TaskDataChanges {
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    @Synchronized fun invalidate() {
        mutableRevision.value += 1L
    }
}
