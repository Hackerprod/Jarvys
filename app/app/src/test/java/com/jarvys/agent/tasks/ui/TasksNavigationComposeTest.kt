package com.jarvys.agent.tasks.ui

import android.content.Context
import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jarvys.agent.AppNavigationBackPolicy
import com.jarvys.agent.AppLanguageChoice
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.R
import com.jarvys.agent.WorkManagerTestCleanup
import com.jarvys.agent.tasks.CalendarCadence
import com.jarvys.agent.tasks.ScheduledTask
import com.jarvys.agent.tasks.TaskGlobalPauseStore
import com.jarvys.agent.tasks.TaskSchedule
import com.jarvys.agent.tasks.TaskState
import com.jarvys.agent.tasks.TaskStore
import com.jarvys.agent.tasks.TaskToolScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TasksNavigationComposeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var task: ScheduledTask

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppLanguageRuntime.select(context, AppLanguageChoice.SPANISH)
        shadowOf(context as Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        context.getSharedPreferences(TaskGlobalPauseStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(Executor { it.run() }).build(),
            WorkManagerTestInitHelper.ExecutorsMode.LEGACY_OVERRIDE_WITH_SYNCHRONOUS_EXECUTORS)
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        val store = TaskStore(context)
        store.list().forEach { store.delete(it.id, it.revision, System.currentTimeMillis()) }
        task = store.create(ScheduledTask(name = "Chat draft", instruction = "Check safely",
            schedule = TaskSchedule.Calendar("08:00", CalendarCadence.DAILY), state = TaskState.Active,
            nextRunAt = System.currentTimeMillis() + 60_000L, toolScope = TaskToolScope("LISTED", emptyList()),
            createdBy = "AGENT_CHAT:test-session", createdAt = System.currentTimeMillis()))
    }

    @After fun tearDown() {
        WorkManagerTestCleanup.close(context)
        AppLanguageRuntime.select(context, AppLanguageChoice.ENGLISH)
        context.getSharedPreferences(TaskGlobalPauseStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        TaskStore(context).get(task.id)?.let { TaskStore(context).delete(it.id, it.revision, System.currentTimeMillis()) }
    }

    @Test fun askingForAChangeReturnsToChatWithExactUnsendedComposerDraft() {
        val draft = androidx.compose.runtime.mutableStateOf("")
        var sends = 0
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = AppNavigationBackPolicy.SETTINGS) {
                    composable(AppNavigationBackPolicy.SETTINGS) {
                        Button(onClick = { nav.navigate(TasksNavigationRoutes.LIST) }) { Text("Open tasks") }
                    }
                    composable(AppNavigationBackPolicy.CHAT_ROOT) {
                        Column {
                            Text(draft.value, Modifier.testTag("chat-composer-draft"))
                            Button(onClick = { sends++ }) { Text("Send") }
                        }
                    }
                    tasksDestinations(nav, context,
                        onRequestChangeDraft = { draft.value = it }, onConfigureProvider = {}, onMessage = {},
                        onReturnToSettings = { nav.navigate(AppNavigationBackPolicy.SETTINGS) { launchSingleTop = true } },
                        backgroundWorkBusyForAll = false)
                }
            }
        }
        compose.onNodeWithText("Open tasks").performClick()
        assertEquals(task.id, TaskStore(context).get(task.id)?.id)
        compose.waitUntil(8_000) {
            runCatching { compose.onNodeWithTag("tasks-list").fetchSemanticsNode(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag("tasks-list").performScrollToIndex(2)
        compose.onNodeWithTag("task-menu-${task.id}").assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.tasks_request_change)).performClick()
        compose.onNodeWithTag("chat-composer-draft").assertIsDisplayed()
        assertEquals(context.getString(R.string.tasks_change_draft, task.name) + " ", draft.value)
        assertEquals(0, sends)
    }
}
