package com.jarvys.agent.crew

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.ui.platform.LocalContext
import com.jarvys.agent.AppNavigationBackPolicy
import com.jarvys.agent.AgentForegroundService

/** Routes are factored into the same NavHost extension used by MainActivity and navigation smoke tests. */
object CrewNavigationRoutes {
    const val EMPTY = AppNavigationBackPolicy.CREW_EMPTY
    const val MISSION = AppNavigationBackPolicy.CREW
    const val BOT = AppNavigationBackPolicy.CREW_BOT
    fun mission(id: String) = "crew/${android.net.Uri.encode(id)}"
    fun bot(missionId: String, botId: String) = "crew/${android.net.Uri.encode(missionId)}/bot/${android.net.Uri.encode(botId)}"
}

fun NavGraphBuilder.crewDestinations(
    navController: NavController,
    board: CrewBoard,
    readOnly: (CrewMissionSnapshot?) -> Boolean,
    missions: () -> List<CrewMissionSnapshot>,
    manager: CrewManager?,
    onStopAll: () -> Unit,
    initialBoardReference: String? = null,
    onBoardReferenceConsumed: () -> Unit = {},
    onBoardReference: (String, String) -> Unit,
    onResumeBot: (String) -> Unit = {},
    crewMode: () -> CrewMode = { CrewMode.AUTO },
    onCrewModeChange: (CrewMode) -> Unit = {},
) {
    composable(CrewNavigationRoutes.EMPTY) {
        CrewMissionScreen(null, board, readOnly(null), onOpenBot = {}, onAskBot = { _, _ -> }, onStopAll = onStopAll, crewMode = crewMode(), onCrewModeChange = onCrewModeChange)
    }
    composable(route = CrewNavigationRoutes.MISSION,
        arguments = listOf(navArgument("missionId") { type = NavType.StringType })) { entry ->
        val context = LocalContext.current
        val missionId = entry.arguments?.getString("missionId").orEmpty()
        val snapshot = missions().lastOrNull { it.missionId == missionId }
        CrewMissionScreen(snapshot, board, readOnly(snapshot),
            onOpenBot = { botId -> navController.navigate(CrewNavigationRoutes.bot(missionId, botId)) },
            onAskBot = { botId, question ->
                if (question.isNotBlank()) {
                    AgentForegroundService.ensureCrewKeepalive(context)
                    manager?.sendUserMessage(botId, question)
                }
                else navController.navigate(CrewNavigationRoutes.bot(missionId, botId))
            }, onStopAll = onStopAll, initialBoardReference = initialBoardReference,
            onBoardReferenceConsumed = onBoardReferenceConsumed, crewMode = crewMode(), onCrewModeChange = onCrewModeChange)
    }
    composable(route = CrewNavigationRoutes.BOT,
        arguments = listOf(navArgument("missionId") { type = NavType.StringType },
            navArgument("botId") { type = NavType.StringType })) { entry ->
        val missionId = entry.arguments?.getString("missionId").orEmpty()
        val context = LocalContext.current
        val botId = entry.arguments?.getString("botId").orEmpty()
        val snapshot = missions().lastOrNull { it.missionId == missionId }
        CrewBotDetailScreen(snapshot, botId, board, readOnly(snapshot),
            onSendMessage = { id, text -> AgentForegroundService.ensureCrewKeepalive(context); manager?.sendUserMessage(id, text) },
            onRedirect = { id, text -> AgentForegroundService.ensureCrewKeepalive(context); manager?.sendUserMessage(id, text) },
            onStopBot = { id -> manager?.stop(id) },
            onBoardReference = { reference -> onBoardReference(missionId, reference) }, onResume = onResumeBot)
    }
}
