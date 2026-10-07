package com.jarvys.agent.proactive

import android.content.Context
import android.content.SharedPreferences

class ProactivePreferences(
    context: Context,
    private val store: ProactiveEventStore = ProactiveEventStore(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        if (preferences.getBoolean(KEY_ENABLED, false) && !store.hasCaptureBoundary()) {
            store.initializeCaptureBoundary(System.currentTimeMillis())
        }
    }

    var enabled: Boolean
        get() = synchronized(CAPTURE_GATE) { preferences.getBoolean(KEY_ENABLED, false) }
        set(value) = synchronized(CAPTURE_GATE) {
            if (value && !preferences.getBoolean(KEY_ENABLED, false)) {
                store.initializeCaptureBoundary(System.currentTimeMillis())
            }
            check(preferences.edit().putBoolean(KEY_ENABLED, value).commit()) { "Could not persist proactive preference" }
            // Opting out cancels the active model token and scrubs event/audit text before cancelling work.
            if (!value) {
                ProactiveRunController.cancelAll()
                store.clearPending()
                ProactiveDecisionAuditStore(appContext).scrubNotificationText()
            }
            ProactiveScheduler.setEnabled(appContext, value)
        }

    /** Holds the same gate as the opt-out purge so a racing listener cannot append after cleanup. */
    fun captureIfEnabled(capture: () -> Unit): Boolean = synchronized(CAPTURE_GATE) {
        if (!preferences.getBoolean(KEY_ENABLED, false)) return@synchronized false
        capture()
        true
    }

    companion object {
        private const val PREFERENCES_NAME = "jarvys_proactive_preferences"
        private const val KEY_ENABLED = "enabled"
        private val CAPTURE_GATE = Any()
    }
}
