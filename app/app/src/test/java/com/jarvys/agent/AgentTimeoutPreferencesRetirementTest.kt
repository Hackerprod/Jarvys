package com.jarvys.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentTimeoutPreferencesRetirementTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = context.getSharedPreferences("jarvys_ui_preferences", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
    }

    @After fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test fun constructorRemovesLegacyUnlimitedValue() = assertRetired(0)

    @Test fun constructorRemovesLegacyFifteenMinuteValue() = assertRetired(900)

    @Test fun constructorRemovesCustomPositiveLegacyValue() = assertRetired(1_337)

    @Test fun constructorRemovesNegativeLegacyValue() = assertRetired(-60)

    @Test fun constructorRemovesWronglyTypedValuesWithoutReadingThemAsIntegers() {
        invalidLegacyValues.forEach(::assertRetired)
    }

    @Test fun everyConstructionRemovesValuesRestoredAfterAnEarlierMigration() {
        val retained = seedUnrelatedPreferences()
        JarvysUiPreferences(context)

        // A backup can restore the old key while this process is still alive. The cleanup
        // must run on every construction rather than behind a process-wide one-shot flag.
        (listOf(0, 900, 1_337, -60) + invalidLegacyValues).forEach { restoredValue ->
            writeLegacyValue(restoredValue)
            assertTrue("Restore fixture must include the old key", preferences.contains(LEGACY_KEY))

            val recreated = JarvysUiPreferences(context)

            assertFalse("Restored value must be retired: $restoredValue", preferences.contains(LEGACY_KEY))
            assertEquals("Restore cleanup must preserve all unrelated values", retained, preferences.all)
            assertEquals(JarvysThemeMode.DARK, recreated.themeMode())
            assertFalse(recreated.showAgentEvents())
        }
    }

    @Test fun changingAndRecreatingPreferencesDoesNotResurrectRetiredDeadline() {
        val retained = seedUnrelatedPreferences()
        writeLegacyValue(900)
        val first = JarvysUiPreferences(context)
        first.setThemeMode(JarvysThemeMode.LIGHT)
        first.setShowAgentEvents(true)
        val updated = retained + mapOf(
            JarvysUiPreferences.KEY_THEME to JarvysThemeMode.LIGHT.name,
            JarvysUiPreferences.KEY_SHOW_EVENTS to true,
        )

        repeat(3) {
            val recreated = JarvysUiPreferences(context)
            assertEquals(JarvysThemeMode.LIGHT, recreated.themeMode())
            assertTrue(recreated.showAgentEvents())
            assertFalse(preferences.contains(LEGACY_KEY))
            assertEquals(updated, preferences.all)
        }
    }

    @Test fun freshPreferencesKeepDefaultsWithoutCreatingALegacyDeadline() {
        repeat(2) {
            val fresh = JarvysUiPreferences(context)
            assertEquals(JarvysThemeMode.SYSTEM, fresh.themeMode())
            assertTrue(fresh.showAgentEvents())
            assertFalse(preferences.contains(LEGACY_KEY))
            assertTrue(preferences.all.isEmpty())
        }
    }

    private fun assertRetired(legacyValue: Any) {
        val retained = seedUnrelatedPreferences()
        writeLegacyValue(legacyValue)

        val migrated = JarvysUiPreferences(context)

        assertFalse("Legacy deadline must be removed: $legacyValue", preferences.contains(LEGACY_KEY))
        assertEquals("Migration must preserve every unrelated preference", retained, preferences.all)
        assertEquals(JarvysThemeMode.DARK, migrated.themeMode())
        assertFalse(migrated.showAgentEvents())
        JarvysUiPreferences(context)
        assertEquals("Migration must be idempotent", retained, preferences.all)
    }

    private fun seedUnrelatedPreferences(): Map<String, *> {
        assertTrue(preferences.edit().clear()
            .putString(JarvysUiPreferences.KEY_THEME, JarvysThemeMode.DARK.name)
            .putBoolean(JarvysUiPreferences.KEY_SHOW_EVENTS, false)
            .putInt("unrelated_count", 23)
            .putLong("unrelated_timestamp", 1_700_000_000_000L)
            .putFloat("unrelated_scale", 1.5f)
            .putStringSet("unrelated_selection", setOf("alpha", "beta"))
            .commit())
        return preferences.all.toMap()
    }

    private fun writeLegacyValue(value: Any) {
        val editor = preferences.edit()
        when (value) {
            is Int -> editor.putInt(LEGACY_KEY, value)
            is Long -> editor.putLong(LEGACY_KEY, value)
            is Float -> editor.putFloat(LEGACY_KEY, value)
            is Boolean -> editor.putBoolean(LEGACY_KEY, value)
            is String -> editor.putString(LEGACY_KEY, value)
            is Set<*> -> editor.putStringSet(LEGACY_KEY, value.map { it as String }.toSet())
            else -> error("Unsupported legacy fixture type: ${value.javaClass}")
        }
        assertTrue(editor.commit())
    }

    private companion object {
        const val LEGACY_KEY = "agent_timeout_seconds"
        val invalidLegacyValues: List<Any> = listOf("900", "invalid", true, 900L, 1.5f, setOf("900"))
    }
}
