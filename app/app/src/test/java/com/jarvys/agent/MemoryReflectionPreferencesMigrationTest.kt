package com.jarvys.agent

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryReflectionPreferencesMigrationTest {
    @Test fun removedNumericPreferencesAndLegacyLimitedStatusesMigrateNeutrally() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("jarvys_memory_reflection", 0)
        val session = "legacy-rate-session"
        prefs.edit()
            .putString("trigger", "step-count")
            .putInt("step_count", 25)
            .putInt("minimum_interval_minutes", 15)
            .putInt("max_per_day", 8)
            .putString("daily_day", "old-day")
            .putInt("daily_count", 8)
            .putLong("session_${session}_last_start", 123L)
            .putLong("session_${session}_last_attempt", 123L)
            .putString("session_${session}_status", "RATE_LIMITED")
            .putString("global_status", "La reflexión espera el intervalo de 15 minutos por chat o el límite diario (8).")
            .commit()

        val reflection = MemoryReflectionPreferences(context)

        assertEquals("compaction-event", prefs.getString("trigger", ""))
        listOf("step_count", "minimum_interval_minutes", "max_per_day", "daily_day", "daily_count",
            "session_${session}_last_start", "session_${session}_last_attempt").forEach { key ->
            assertFalse("Legacy preference should be removed: $key", prefs.contains(key))
        }
        assertEquals("", reflection.status(session))
        assertEquals("", reflection.globalStatus())
        assertEquals("", reflection.localizedStatus(reflection.globalStatus()))
    }
}
