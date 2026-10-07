package com.jarvys.agent

import android.content.Context

enum class JarvysThemeMode { SYSTEM, LIGHT, DARK }

class JarvysUiPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("jarvys_ui_preferences", Context.MODE_PRIVATE)

    init {
        if (legacyTokenPreferenceRemoved.compareAndSet(false, true)) {
            preferences.edit().remove(LEGACY_TOKEN_STATS_KEY).apply()
        }
    }

    fun themeMode(): JarvysThemeMode = runCatching {
        JarvysThemeMode.valueOf(preferences.getString(KEY_THEME, JarvysThemeMode.SYSTEM.name).orEmpty())
    }.getOrDefault(JarvysThemeMode.SYSTEM)

    fun setThemeMode(value: JarvysThemeMode) {
        preferences.edit().putString(KEY_THEME, value.name).apply()
    }

    fun showAgentEvents(): Boolean = preferences.getBoolean(KEY_SHOW_EVENTS, true)

    fun setShowAgentEvents(value: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_EVENTS, value).apply()
    }

    fun agentTimeoutSeconds(): Int = preferences.getInt(KEY_AGENT_TIMEOUT_SECONDS, DEFAULT_AGENT_TIMEOUT_SECONDS)

    fun setAgentTimeoutSeconds(value: Int) {
        require(value >= 0) { "Agent timeout must be zero (unlimited) or positive" }
        preferences.edit().putInt(KEY_AGENT_TIMEOUT_SECONDS, value).apply()
    }

    companion object {
        private const val LEGACY_TOKEN_STATS_KEY = "show_token_stats"
        private val legacyTokenPreferenceRemoved = java.util.concurrent.atomic.AtomicBoolean(false)
        const val DEFAULT_AGENT_TIMEOUT_SECONDS = 15 * 60
        const val KEY_THEME = "theme_mode"
        const val KEY_SHOW_EVENTS = "show_agent_events"
        private const val KEY_AGENT_TIMEOUT_SECONDS = "agent_timeout_seconds"
    }
}
