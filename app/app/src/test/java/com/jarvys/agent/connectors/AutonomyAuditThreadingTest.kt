package com.jarvys.agent.connectors

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual preference adapter and Room bridge; no replacement migration target. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutonomyAuditThreadingTest {
    @get:Rule val chatStore = ChatTestStore(runTestOnWorker = false)

    @Test fun constructionDoesNotTouchRoomAndWorkerMigrationNeverOwnsPolicyLock() {
        val files = chatStore.newFilesDirectory("audit-threading")
        chatStore.onDatabaseWorker { chatStore.localRunStore(files) }
        val app = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        val preferences = app.getSharedPreferences("audit-threading-isolated", Context.MODE_PRIVATE)
        val original = JSONArray().put(JSONObject().put("id", "threading-event").put("timestampMillis", 12L)
            .put("connectorId", "calendar").put("connectorName", "Calendar")
            .put("operationName", "create_event").put("operationLabel", "Create event")
            .put("summary", "Synthetic legacy event")).toString()
        preferences.edit().clear().putString("autonomy_audit", original).commit()
        val scope = "audit-threading-isolated"
        val policyLock = PersistedActionPolicies(preferences, scope).lock
        var sourceReads = 0
        val observing = object : SharedPreferences by preferences {
            override fun getString(key: String?, defValue: String?): String? {
                if (key == "autonomy_audit") {
                    assertFalse("Legacy source must not be read under policy lock", Thread.holdsLock(policyLock))
                    sourceReads++
                }
                return preferences.getString(key, defValue)
            }
            override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                if (key == "autonomy_audit_room_migrated")
                    assertFalse("Migration/Room path must not own policy lock", Thread.holdsLock(policyLock))
                return preferences.getBoolean(key, defValue)
            }
        }
        val previousProperty = System.getProperty("jarvys.unitTests.mainThreadGuardOff")
        val previousGuard = ChatDatabaseBridge.mainThreadGuardDisabledForTests
        try {
            System.setProperty("jarvys.unitTests.mainThreadGuardOff", "false")
            ChatDatabaseBridge.mainThreadGuardDisabledForTests = false
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            val bridge = ChatDatabaseBridge.getInstance(context)
            try {
                bridge.recentConnectorAuditSync("calendar", 1)
                fail("Test must exercise the real main-thread guard")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("main thread"))
            }
            val store = SharedPreferencesConnectorAutonomyStore(observing, scope, bridge)
            assertEquals("Constructor must not start migration", 0, sourceReads)
            assertEquals(original, preferences.getString("autonomy_audit", null))
            val rows = chatStore.onDatabaseWorker { store.recentAudit("calendar", 10) }
            assertEquals(listOf("threading-event"), rows.map { it.id })
            assertTrue(sourceReads > 0)
            assertFalse(preferences.contains("autonomy_audit"))
            assertTrue(preferences.getBoolean("autonomy_audit_room_migrated", false))
            val reopened = SharedPreferencesConnectorAutonomyStore(observing, scope, bridge)
            assertEquals(rows, chatStore.onDatabaseWorker { reopened.recentAudit("calendar", 10) })
        } finally {
            ChatDatabaseBridge.mainThreadGuardDisabledForTests = previousGuard
            if (previousProperty == null) System.clearProperty("jarvys.unitTests.mainThreadGuardOff")
            else System.setProperty("jarvys.unitTests.mainThreadGuardOff", previousProperty)
            preferences.edit().clear().commit()
        }
    }
}
