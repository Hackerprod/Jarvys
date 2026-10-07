package com.jarvys.agent.tasks.ui

import android.content.Context
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.jarvys.agent.AppNavigationBackPolicy
import com.jarvys.agent.tasks.ScheduledTask
import com.jarvys.agent.tasks.TaskRepository
import com.jarvys.agent.tasks.TaskRunLedger

object TasksNavigationRoutes {
    const val LIST = AppNavigationBackPolicy.TASKS
    const val DETAIL = AppNavigationBackPolicy.TASK_DETAIL
    fun detail(taskId: String) = "tasks/detail/${android.net.Uri.encode(taskId)}"
}

/** Original task-only navigation destinations; MainActivity contributes only this registration call. */
fun NavGraphBuilder.tasksDestinations(
    navController: NavController,
    context: Context,
    onRequestChangeDraft: (String) -> Unit,
    onConfigureProvider: () -> Unit,
    onMessage: (String) -> Unit,
    onReturnToSettings: () -> Unit,
    backgroundWorkBusyForAll: Boolean? = null,
) {
    val app = context.applicationContext
    val repository = TaskRepository(app)
    val ledger = TaskRunLedger(app)
    composable(TasksNavigationRoutes.LIST) {
        TasksListScreen(
            context = app,
            repository = repository,
            ledger = ledger,
            onOpenTask = { id -> navController.navigate(TasksNavigationRoutes.detail(id)) },
            onRequestChange = { task ->
                onRequestChangeDraft(app.getString(com.jarvys.agent.R.string.tasks_change_draft, task.name) + " ")
                navController.navigate(AppNavigationBackPolicy.CHAT_ROOT) {
                    popUpTo(AppNavigationBackPolicy.CHAT_ROOT) { inclusive = false }
                    launchSingleTop = true
                }
            },
            onConfigureProvider = onConfigureProvider,
            onMessage = onMessage,
            onReturnToSettings = onReturnToSettings,
            backgroundWorkBusyForAll = backgroundWorkBusyForAll,
        )
    }
    composable(
        route = TasksNavigationRoutes.DETAIL,
        arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
    ) { entry ->
        val taskId = entry.arguments?.getString("taskId").orEmpty()
        TaskDetailScreen(
            context = app,
            taskId = taskId,
            repository = repository,
            ledger = ledger,
            onRequestChange = { task ->
                onRequestChangeDraft(app.getString(com.jarvys.agent.R.string.tasks_change_draft, task.name) + " ")
                navController.navigate(AppNavigationBackPolicy.CHAT_ROOT) {
                    popUpTo(AppNavigationBackPolicy.CHAT_ROOT) { inclusive = false }
                    launchSingleTop = true
                }
            },
            onConfigureProvider = onConfigureProvider,
            onMessage = onMessage,
            onMissingTask = onReturnToSettings,
            backgroundWorkBusyForAll = backgroundWorkBusyForAll,
        )
    }
}
