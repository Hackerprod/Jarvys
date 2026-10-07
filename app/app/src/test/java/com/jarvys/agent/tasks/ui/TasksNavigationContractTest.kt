package com.jarvys.agent.tasks.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.AppNavigationBackPolicy
import com.jarvys.agent.R
import com.jarvys.agent.tasks.ScheduledTask
import com.jarvys.agent.tasks.TaskSchedule
import com.jarvys.agent.tasks.TaskState
import com.jarvys.agent.tasks.TaskStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TasksNavigationContractTest {
    @Test fun taskRoutesHaveLocalizedTitlesBackAndSettingsEntryVisibilityTracksNonDeletedRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(AppNavigationBackPolicy.registeredRoutePatterns.contains(TasksNavigationRoutes.LIST))
        assertTrue(AppNavigationBackPolicy.registeredRoutePatterns.contains(TasksNavigationRoutes.DETAIL))
        assertTrue(AppNavigationBackPolicy.requiresBack(TasksNavigationRoutes.LIST))
        assertTrue(AppNavigationBackPolicy.requiresBack(TasksNavigationRoutes.DETAIL))
        assertEquals(context.getString(R.string.tasks_title), AppNavigationBackPolicy.metadata(
            context, TasksNavigationRoutes.LIST, "Chat", null, null).title)
        assertEquals("A task", AppNavigationBackPolicy.metadata(
            context, TasksNavigationRoutes.DETAIL, "Chat", null, "A task").title)
        assertFalse(TaskUiProjection.showSettingsEntry(0))
        assertTrue(TaskUiProjection.showSettingsEntry(1))
        val due = ScheduledTask(name = "Due", instruction = "Read", schedule = TaskSchedule.Every(900_000L, 1L),
            state = TaskState.Active, nextRunAt = 9L, createdAt = 1L)
        assertTrue(TaskUiProjection.isDelayed(due, nowMillis = 10L, runInProgress = false))
        assertFalse(TaskUiProjection.isDelayed(due, nowMillis = 10L, runInProgress = true))
        assertFalse(TaskUiProjection.isDelayed(due.copy(state = TaskState.Paused), nowMillis = 10L, runInProgress = false))
        val store = TaskStore(context)
        val task = store.create(ScheduledTask(name = "Deep link", instruction = "Read safely",
            schedule = TaskSchedule.Every(900_000L, 1L), createdAt = 1L))
        try {
            assertEquals(task.id, TaskDeepLink.existingTaskId(context, task.id))
            assertNull(TaskDeepLink.existingTaskId(context, "not a task id/with path"))
            assertNull(TaskDeepLink.existingTaskId(context, java.util.UUID.randomUUID().toString()))
        } finally {
            store.delete(task.id, task.revision, System.currentTimeMillis())
        }
    }

    @Test fun everyNewTaskUiFileIsOriginalAndAvoidsDerivedKelivoScreenIdentifiers() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val appRoot = sequenceOf(working, File(working, "app"), File(working.parentFile, "app"))
            .first { File(it, "src/main/java/com/jarvys/agent/tasks/ui").isDirectory }
        val uiRoot = File(appRoot, "src/main/java/com/jarvys/agent/tasks/ui")
        val forbidden = listOf("kelivo", "JarvysSettingsPage", "SettingsHome", "MemorySettingsRow",
            "KelivoSettingsRow", "McpServersScreen", "McpServerListRow", "SkillsScreen", "SkillDetailScreen",
            "settings_general", "settings_models_services", "memory_settings_summary")
        val sources = uiRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Expected original task UI Kotlin files", sources.isNotEmpty())
        sources.forEach { file ->
            val content = file.readText()
            forbidden.forEach { banned -> assertFalse("${file.name} contains derived identifier $banned",
                content.contains(banned, ignoreCase = true)) }
        }
        val manifest = File(appRoot, "src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        val settingsSource = File(appRoot, "src/main/java/com/jarvys/agent/ui/settings/SettingsWorkspace.kt").readText()
        assertTrue(settingsSource.contains("if (scheduledTasksAvailable)"))
        assertTrue(settingsSource.contains("TaskSettingsEntry(onScheduledTasks)"))
    }

    @Test fun workManagerObservationMarksManualRunsBusyPerTaskWithoutPolling() {
        val observed = TaskWorkObservation(tickRunning = false, proactiveRunning = false,
            manualTaskIds = setOf("task-one"))
        assertTrue(observed.isTaskBusy("task-one"))
        assertFalse(observed.isTaskBusy("task-two"))
        val global = TaskWorkObservation(tickRunning = true, proactiveRunning = false, manualTaskIds = emptySet())
        assertTrue(global.isTaskBusy("task-two"))
    }
}
