package com.jarvys.agent.tasks.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.jarvys.agent.R
import com.jarvys.agent.WorkManagerTestCleanup
import com.jarvys.agent.tasks.CalendarCadence
import com.jarvys.agent.tasks.ScheduledTask
import com.jarvys.agent.tasks.ScheduleCalculator
import com.jarvys.agent.tasks.TaskDelivery
import com.jarvys.agent.tasks.TaskGlobalPauseStore
import com.jarvys.agent.tasks.TaskRepository
import com.jarvys.agent.tasks.TaskRunLedger
import com.jarvys.agent.tasks.TaskRunRecord
import com.jarvys.agent.tasks.TaskSchedule
import com.jarvys.agent.tasks.TaskSchedulePresentation
import com.jarvys.agent.tasks.TaskState
import com.jarvys.agent.tasks.TaskStore
import com.jarvys.agent.tasks.TaskToolScope
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TasksScreensComposeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var fixture: Fixture

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(TaskGlobalPauseStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(Executor { it.run() }).build(),
            WorkManagerTestInitHelper.ExecutorsMode.LEGACY_OVERRIDE_WITH_SYNCHRONOUS_EXECUTORS)
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        fixture = fixture()
    }

    @After fun tearDown() {
        WorkManagerTestCleanup.close(context)
        context.getSharedPreferences(TaskGlobalPauseStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun taskListShowsMixedStatesPausesAndResumesAndOffersManualAndChatDraftActions() {
        val now = fixture.clock.millis()
        val active = fixture.addTask("Active", TaskState.Active, nextRun = now + 60_000L)
        val paused = fixture.addTask("Paused", TaskState.Paused, nextRun = now + 120_000L)
        val done = fixture.addTask("Finished", TaskState.Done, nextRun = null)
        val attention = fixture.addTask("Attention", TaskState.NeedsAttention("no_provider"), nextRun = null)
        val queued = AtomicInteger()
        val drafts = mutableListOf<String>()
        val returned = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                TasksListScreen(
                    context, fixture.repository, fixture.ledger,
                    onOpenTask = {}, onRequestChange = { drafts += it.name }, onConfigureProvider = {},
                    onMessage = {}, onReturnToSettings = { returned.incrementAndGet() },
                    pauseAll = { pausedAll -> TaskGlobalPauseStore(context).setPaused(pausedAll, now) },
                    enqueueManualRun = { queued.incrementAndGet() },
                    healthIssuesOverride = { emptyList() },
                    backgroundWorkBusyForAll = false,
                )
            }
        }
        awaitNode("tasks-list")
        compose.onNodeWithTag("tasks-list").performScrollToIndex(1)
        compose.onNodeWithTag("task-row-${active.id}").assertIsDisplayed()
        compose.onNodeWithTag("tasks-list").performScrollToIndex(2)
        compose.onNodeWithTag("task-row-${attention.id}").assertIsDisplayed()
        compose.onNodeWithTag("tasks-list").performScrollToIndex(3)
        compose.onNodeWithTag("task-row-${paused.id}").assertIsDisplayed()
        compose.onNodeWithTag("tasks-list").performScrollToIndex(4)
        compose.onNodeWithTag("task-row-${done.id}").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tasks_state_attention)).assertIsDisplayed()
        compose.onNodeWithTag("tasks-list").performScrollToIndex(1)
        compose.onNodeWithTag("task-toggle-${active.id}").performClick()
        compose.waitUntil(5_000) { fixture.repository.get(active.id)?.state == TaskState.Paused }
        compose.onNodeWithTag("tasks-list").performScrollToIndex(3)
        compose.onNodeWithTag("task-toggle-${paused.id}").performClick()
        compose.waitUntil(5_000) { fixture.repository.get(paused.id)?.state == TaskState.Active }

        compose.onNodeWithTag("tasks-list").performScrollToIndex(3)
        compose.onNodeWithTag("task-menu-${active.id}").performScrollTo().performClick()
        awaitText(context.getString(R.string.tasks_run_now))
        compose.onNodeWithText(context.getString(R.string.tasks_run_now)).performClick()
        assertEquals(1, queued.get())
        compose.onNodeWithTag("task-menu-${active.id}").performScrollTo().performClick()
        awaitText(context.getString(R.string.tasks_request_change))
        compose.onNodeWithText(context.getString(R.string.tasks_request_change)).performClick()
        assertEquals(listOf(active.name), drafts)
        assertEquals(0, returned.get())

        val historyTask = fixture.addTask("Keep history", TaskState.Active, nextRun = now + 180_000L)
        fixture.ledger.appendIfAbsent(TaskRunRecord(historyTask.id, "history-run", 10L, 20L, 30L, "OK", "DELIVERED"))
        compose.onNodeWithTag("tasks-list").performScrollToIndex(1)
        compose.onNodeWithTag("task-menu-${historyTask.id}").performClick()
        compose.onNodeWithText(context.getString(R.string.tasks_delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.tasks_delete_history_message, historyTask.name)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tasks_cancel)).performClick()
        assertTrue(fixture.repository.get(historyTask.id) != null)
        compose.onNodeWithTag("task-menu-${historyTask.id}").performClick()
        compose.onNodeWithText(context.getString(R.string.tasks_delete)).performClick()
        compose.onNodeWithTag("task-delete-keep-history").performClick()
        compose.waitUntil(5_000) { fixture.repository.get(historyTask.id) == null }
        assertEquals(1, fixture.ledger.forTask(historyTask.id).size)
    }

    @Test fun emptyTaskListReturnsToSettingsInsteadOfRenderingAnEmptyPage() {
        val returned = AtomicInteger()
        val messages = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                TasksListScreen(context, fixture.repository, fixture.ledger, {}, {}, {}, { messages += it },
                    { returned.incrementAndGet() }, healthIssuesOverride = { emptyList() },
                    backgroundWorkBusyForAll = false)
            }
        }
        compose.waitUntil(5_000) { returned.get() == 1 }
        assertEquals(context.getString(R.string.tasks_empty_returned), messages.single())
    }

    @Test fun globalPauseBannerAndManualRunRemainAvailableWhileResumeDropsMissedOccurrences() {
        val now = fixture.clock.millis()
        val task = fixture.addTask("Vacation task", TaskState.Active, nextRun = now - 30_000L,
            schedule = TaskSchedule.Every(900_000L, now - TimeUnit.HOURS.toMillis(2)))
        val queued = AtomicInteger()
        val resumedAt = now + TimeUnit.HOURS.toMillis(3)
        compose.setContent {
            MaterialTheme {
                TasksListScreen(
                    context, fixture.repository, fixture.ledger, {}, {}, {}, {}, {},
                    pauseAll = { pausedAll ->
                        if (pausedAll) TaskGlobalPauseStore(context).setPaused(true, now)
                        else {
                            fixture.store.list().filter { it.state == TaskState.Active }.forEach { task ->
                                val next = fixture.calculator.nextRunAfter(task, resumedAt)
                                fixture.store.update(task.copy(nextRunAt = next, updatedAt = resumedAt), task.revision)
                            }
                            TaskGlobalPauseStore(context).setPaused(false, resumedAt)
                        }
                    },
                    enqueueManualRun = { queued.incrementAndGet() },
                    healthIssuesOverride = { emptyList() },
                    backgroundWorkBusyForAll = false,
                )
            }
        }
        awaitNode("tasks-global-pause")
        compose.onNodeWithTag("tasks-global-pause").performClick()
        compose.waitUntil(5_000) { TaskGlobalPauseStore(context).isPaused() }
        awaitText(context.getString(R.string.tasks_all_paused_banner))
        compose.onNodeWithText(context.getString(R.string.tasks_all_paused_banner)).assertIsDisplayed()
        compose.onNodeWithTag("tasks-list").performScrollToIndex(1)
        compose.onNodeWithTag("task-menu-${task.id}").performClick()
        compose.onNodeWithText(context.getString(R.string.tasks_run_now)).performClick()
        assertEquals(1, queued.get())
        compose.onNodeWithTag("tasks-global-pause").performClick()
        compose.waitUntil(5_000) { !TaskGlobalPauseStore(context).isPaused() }
        assertTrue(fixture.repository.get(task.id)!!.nextRunAt!! > resumedAt)
        assertEquals(1, queued.get())
    }

    @Test fun lastTaskRemovalReturnsToSettingsAndTaskDetailRendersUnboundedLazyHistory() {
        val task = fixture.addTask("Long history", TaskState.Active, nextRun = fixture.clock.millis() + 60_000L)
        (1..120).forEach { run ->
            fixture.ledger.appendIfAbsent(TaskRunRecord(task.id, "run-$run", run.toLong(), run.toLong(),
                run + 1L, "OK", "DELIVERED"))
        }
        compose.setContent {
            MaterialTheme {
                TaskDetailScreen(context, task.id, fixture.repository, fixture.ledger,
                    onRequestChange = {}, onConfigureProvider = {}, onMessage = {}, onMissingTask = {},
                    backgroundWorkBusyForAll = false)
            }
        }
        compose.waitForIdle()
        awaitNode("task-detail-history-list")
        compose.onNodeWithTag("task-detail-history-list").assertIsDisplayed()
        compose.onNodeWithTag("task-detail-history-list").performScrollToIndex(1)
        compose.onNodeWithTag("task-detail-run-count").performScrollTo()
            .assertTextEquals(context.resources.getQuantityString(R.plurals.task_detail_run_count, 120, 120))
        compose.onNodeWithTag("task-detail-history-list").performScrollToIndex(121)
        compose.onNodeWithTag("task-run-${task.id}:1").assertIsDisplayed()
        compose.onNodeWithTag("task-detail-history-list").performScrollToIndex(0)
        compose.onNodeWithTag("task-clear-history").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.task_detail_clear_history_title)).assertIsDisplayed()
        compose.onNodeWithTag("task-clear-history-confirm").performClick()
        compose.waitUntil(5_000) { fixture.ledger.forTask(task.id).isEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("task-detail-history-list").performScrollToIndex(2)
        compose.onNodeWithText(context.getString(R.string.task_detail_no_history)).assertIsDisplayed()
    }

    @Test fun healthPolicyAndCardCoverSettingsActionsAndHideWhenHealthy() {
        val task = fixture.addTask("Late", TaskState.Active, nextRun = fixture.clock.millis() - 1L)
        val attention = fixture.addTask("Needs provider", TaskState.NeedsAttention("no_provider"), nextRun = null)
        val connectorAttention = fixture.addTask("Needs connector", TaskState.NeedsAttention("connector_unavailable_or_revoked"), nextRun = null)
        val issues = TaskHealthPolicy.evaluate(TaskHealthInput(false, false, listOf(task), listOf(attention, connectorAttention)))
        assertEquals(5, issues.size)
        assertEquals(listOf("no_provider", "connector_unavailable_or_revoked"),
            issues.filter { it.kind == TaskHealthKind.ATTENTION }.map { it.reason })
        assertTrue(TaskHealthPolicy.evaluate(TaskHealthInput(true, true)).isEmpty())
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, TaskUiIntents.notificationSettings(context).action)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            TaskUiIntents.batteryOptimizationSettings().action)
        val notification = AtomicInteger()
        val battery = AtomicInteger()
        val configure = AtomicInteger()
        val openedTaskIds = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TaskHealthCard(issues, TaskSchedulePresentation(context), { notification.incrementAndGet() },
                        { battery.incrementAndGet() }, { configure.incrementAndGet() }, { openedTaskIds += it })
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.tasks_health_notifications)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tasks_health_battery)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tasks_health_configure_provider)).performClick()
        assertEquals(1, configure.get())
        compose.onNodeWithText(context.getString(R.string.tasks_health_open_notifications)).performClick()
        compose.onNodeWithText(context.getString(R.string.tasks_health_open_battery)).performClick()
        compose.onNodeWithText(context.getString(R.string.tasks_open_delayed)).performClick()
        assertEquals(listOf(task.id), openedTaskIds)
        assertEquals(1, compose.onAllNodesWithText(context.getString(R.string.tasks_health_review_task)).fetchSemanticsNodes().size)
        compose.onNodeWithText(context.getString(R.string.tasks_health_review_task)).performScrollTo().assertIsDisplayed()
        assertEquals(1, notification.get())
        assertEquals(1, battery.get())
        assertEquals(listOf(task.id), openedTaskIds)
    }

    private data class Fixture(
        val store: TaskStore,
        val ledger: TaskRunLedger,
        val repository: TaskRepository,
        val calculator: ScheduleCalculator,
        val clock: Clock,
    ) {
        fun addTask(name: String, state: TaskState, nextRun: Long?,
                    schedule: TaskSchedule = TaskSchedule.Calendar("08:00", CalendarCadence.DAILY,
                        zone = com.jarvys.agent.tasks.TaskZone.Iana("UTC"))) = store.create(ScheduledTask(
            name = name, instruction = "Literal instruction for $name", schedule = schedule,
            state = state, nextRunAt = nextRun, toolScope = TaskToolScope("LISTED", emptyList()),
            delivery = TaskDelivery.ALWAYS, createdBy = "AGENT_CHAT:test-session", createdAt = clock.millis()))
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("st3-tasks-compose").toFile()
        val store = TaskStore(File(root, "tasks.jsonl"))
        val ledger = TaskRunLedger(File(root, "runs.jsonl"))
        val clock = Clock.fixed(Instant.parse("2025-04-01T09:00:00Z"), ZoneOffset.UTC)
        val calculator = ScheduleCalculator(clock, com.jarvys.agent.tasks.TaskZoneProvider { ZoneId.of("UTC") })
        val repo = TaskRepository(context, store, calculator, clock, {}, ledger)
        return Fixture(store, ledger, repo, calculator, clock)
    }

    private fun awaitNode(tag: String) {
        compose.waitUntil(8_000) {
            runCatching { compose.onNodeWithTag(tag).fetchSemanticsNode(); true }.getOrDefault(false)
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(8_000) {
            runCatching { compose.onNodeWithText(text).fetchSemanticsNode(); true }.getOrDefault(false)
        }
    }

}
