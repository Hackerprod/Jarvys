package com.jarvys.agent.crew

import java.util.concurrent.TimeUnit

/** Espresso is provided at test runtime; avoid adding a dependency just for bounded diagnostics. */
internal fun setBotsTestIdleTimeout(seconds: Long) {
    Class.forName("androidx.test.espresso.IdlingPolicies")
        .getMethod("setMasterPolicyTimeout", java.lang.Long.TYPE, TimeUnit::class.java)
        .invoke(null, seconds, TimeUnit.SECONDS)
}
