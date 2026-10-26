package com.jarvys.agent.connectors

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.ConnectionStateCoordinator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real SharedPreferences boundary in Robolectric; not a process-crash or hardware fsync test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
class ConnectionPreferenceAndroidTest {
    @Test fun realPreferenceBoundaryRejectsMainThreadReadsAndCommits() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val backend = ConnectionPreferenceBackend.Android(app.getSharedPreferences("connection-boundary-${UUID.randomUUID()}", 0))
        try { backend.snapshot(); fail("Main-thread snapshot accepted") } catch (_: IllegalStateException) { }
        try { backend.commit(mapOf("fixture" to true), emptySet()); fail("Main-thread commit accepted") }
        catch (_: IllegalStateException) { }
    }
    @Test fun realPreferencesRoundTripExactProofWithoutChangingForeignPolicy() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val prefs = app.getSharedPreferences("connection-roundtrip-${UUID.randomUUID()}", 0)
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                assertTrue(prefs.edit().putString("action_policy_fixture", "ASK").putBoolean("permission_asked_fixture", true).commit())
                val storage = SharedPreferencesConnectionStorage(ConnectionPreferenceBackend.Android(prefs))
                val image = storage.load()
                val command = Command("fixture", "generation-one", "operation-one", Kind.DISCONNECT, 0, 0, null)
                val event = Event(image.storeEpoch, 1, "fixture", null, false, "LOCAL_DISCONNECT_SELECTED", "operation-one", null, 17)
                val mutation = Mutation(event, "mutation-one", command, 1, 1, Phase.NONE, false, false,
                    Origin.REQUEST, null, "process-one")
                assertEquals(Outcome.CONFIRMED, storage.writeCandidate(mutation))
                assertEquals(Outcome.CONFIRMED, storage.writeProof(mutation))
                val restored = SharedPreferencesConnectionStorage(ConnectionPreferenceBackend.Android(prefs)).load()
                assertEquals(mutation, restored.current["fixture"])
                assertEquals(mutation, restored.proofs[mutation.version])
                assertEquals(event, restored.pending[event.eventId])
                assertNull(restored.destinationGeneration)
                assertNull(restored.lastAcknowledgedGeneration)
                assertEquals("ASK", prefs.getString("action_policy_fixture", null))
                assertTrue(prefs.getBoolean("permission_asked_fixture", false))
                assertFalse(prefs.getBoolean("connected_fixture", true))
            }.get(10, TimeUnit.SECONDS)
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)) }
    }
}
