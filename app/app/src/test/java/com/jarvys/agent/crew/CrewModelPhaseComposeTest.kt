package com.jarvys.agent

import com.jarvys.agent.crew.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.jarvys.agent.WorkspaceStore
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "es-rES-w360dp-h800dp-port-mdpi")
class CrewModelPhaseComposeTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun bot(status: String = "RUNNING", phase: String = "model_wait") = CrewBotSnapshot(
        "b", "r", "Role", "Ada", "amber", "Task", status, "", "", "", emptyList(),
        1000L, 0L, false, "", false, phase, "tool_result", 1000L)

    @Test fun spanishWaitCompactionAndElapsedAreVisibleAndTerminalHasNoLiveIndicator() {
        var value by mutableStateOf(bot())
        compose.setContent { MaterialTheme { CrewBotProgress(value, nowMillis = { 62000L }) } }
        compose.onNodeWithText("Esperando respuesta del modelo").assertIsDisplayed()
        compose.onNodeWithText("Ada · Última actividad: Herramienta finalizada · hace 1:01").assertIsDisplayed()
        compose.runOnIdle { value = bot(phase = "compacting") }
        compose.onNodeWithText("Compactando").assertIsDisplayed()
        compose.onNodeWithText("Esperando respuesta del modelo").assertDoesNotExist()
        compose.runOnIdle { value = bot(status = "STOPPED") }
        compose.onNodeWithTag("crew-phase-b").assertDoesNotExist()
    }

    @Test fun debateScreenHeaderShowsActualWaitAndCompactionWithBoundedProgress() {
        val board = CrewBoard(WorkspaceStore(temporary.root, WorkspaceStore.projectIdForSession("phase-project")))
        var value by mutableStateOf(bot())
        compose.setContent {
            val snapshot = CrewMissionSnapshot("m", "c", "p", "Task", "RUNNING", "", 1000L, 0L,
                listOf(value), emptyList())
            MaterialTheme { CrewMissionScreen(snapshot, board, false, {}, { _, _ -> }, {}) }
        }
        compose.onNodeWithTag("crew-mission-header").assertIsDisplayed()
        compose.onNodeWithText("Ada · Esperando respuesta del modelo").assertIsDisplayed()
        compose.onNodeWithText("Última actividad", substring = true).assertIsDisplayed()
        compose.runOnIdle { value = bot(phase = "compacting") }
        compose.onNodeWithText("Ada · Compactando").assertIsDisplayed()
        compose.runOnIdle { value = bot(status = "PARTIAL") }
        compose.onNodeWithTag("crew-phase-b").assertDoesNotExist()
    }

    @Test fun timerStopsOffscreenInBackgroundAndAfterCompletion() {
        class Owner : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val owner = Owner()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        var value by mutableStateOf(bot())
        var outside by mutableStateOf(false)
        val reads = AtomicInteger()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme { Box {
                    CrewBotProgress(value, Modifier.offset { IntOffset(0, if (outside) 2000.dp.roundToPx() else 0) },
                        nowMillis = { reads.incrementAndGet(); 62000L })
                } }
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val visibleReads = reads.get()
        compose.mainClock.advanceTimeBy(1100)
        assertTrue(reads.get() > visibleReads)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val backgroundReads = reads.get()
        compose.mainClock.advanceTimeBy(2100)
        assertEquals(backgroundReads, reads.get())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED; outside = true }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val hiddenReads = reads.get()
        compose.mainClock.advanceTimeBy(2100)
        assertEquals(hiddenReads, reads.get())
        compose.runOnIdle { outside = false }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val resumedReads = reads.get()
        compose.mainClock.advanceTimeBy(1100)
        assertTrue(reads.get() > resumedReads)
        compose.runOnIdle { value = bot(status = "DONE") }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val completedReads = reads.get()
        compose.mainClock.advanceTimeBy(2100)
        assertEquals(completedReads, reads.get())
        compose.onNodeWithTag("crew-phase-b").assertDoesNotExist()
    }
}
