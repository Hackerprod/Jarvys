package com.jarvys.agent.crew

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.jarvys.agent.AgentForegroundService
import com.jarvys.agent.AppNavigationBackPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Both mission routes carry the execution session that owns the mission. The argument lives in the
 * back stack entry, so mission -> bot -> activity recreation resolves the same session.
 */
object CrewNavigationRoutes {
    const val MISSION = AppNavigationBackPolicy.CREW
    const val BOT = AppNavigationBackPolicy.CREW_BOT
    const val SESSION = "session"
    fun mission(id: String, session: String) =
        "crew/${android.net.Uri.encode(id)}?$SESSION=${android.net.Uri.encode(session)}"
    fun bot(missionId: String, botId: String, session: String) =
        "crew/${android.net.Uri.encode(missionId)}/bot/${android.net.Uri.encode(botId)}?$SESSION=${android.net.Uri.encode(session)}"
}

/** What the mission screens read from the runtime for one execution session. */
interface CrewSessionAccess {
    /** True when [session] is a registered workspace execution session; also makes its Crew available. */
    fun resolve(session: String): Boolean
    fun board(session: String): CrewBoard
    fun manager(session: String): CrewManager?
    /** Missions of [session], or null while that session is not the one being shown. */
    fun missions(session: String): List<CrewMissionSnapshot>?
    fun stopAll(session: String)
    fun resumeBot(session: String, botId: String)
}

fun NavGraphBuilder.crewDestinations(
    navController: NavController,
    access: CrewSessionAccess,
    initialBoardReference: String? = null,
    onBoardReferenceConsumed: () -> Unit = {},
    onBoardReference: (String, String) -> Unit,
    onOpenPreview: ((String, String) -> Unit)? = null,
) {
    val sessionArgument = navArgument(CrewNavigationRoutes.SESSION) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }
    composable(route = CrewNavigationRoutes.MISSION,
        arguments = listOf(navArgument("missionId") { type = NavType.StringType }, sessionArgument)) { entry ->
        val context = LocalContext.current
        val missionId = entry.arguments?.getString("missionId").orEmpty()
        CrewSessionGate(access, entry.arguments?.getString(CrewNavigationRoutes.SESSION), missionId) { session, snapshot ->
            val manager = access.manager(session)
            CrewMissionScreen(snapshot, remember(session) { access.board(session) }, manager == null,
                onOpenBot = { botId -> navController.navigate(CrewNavigationRoutes.bot(missionId, botId, session)) },
                onAskBot = { botId, question ->
                    if (question.isNotBlank()) {
                        AgentForegroundService.ensureCrewKeepalive(context)
                        manager?.sendUserMessage(botId, question)
                    }
                    else navController.navigate(CrewNavigationRoutes.bot(missionId, botId, session))
                }, onStopAll = { access.stopAll(session) }, initialBoardReference = initialBoardReference,
                onBoardReferenceConsumed = onBoardReferenceConsumed,
                onOpenPreview = onOpenPreview?.let { open -> { token -> open(session, token) } })
        }
    }
    composable(route = CrewNavigationRoutes.BOT,
        arguments = listOf(navArgument("missionId") { type = NavType.StringType },
            navArgument("botId") { type = NavType.StringType }, sessionArgument)) { entry ->
        val missionId = entry.arguments?.getString("missionId").orEmpty()
        val context = LocalContext.current
        val botId = entry.arguments?.getString("botId").orEmpty()
        CrewSessionGate(access, entry.arguments?.getString(CrewNavigationRoutes.SESSION), missionId) { session, snapshot ->
            val manager = access.manager(session)
            CrewBotDetailScreen(snapshot, botId, remember(session) { access.board(session) }, manager == null,
                onSendMessage = { id, text -> AgentForegroundService.ensureCrewKeepalive(context); manager?.sendUserMessage(id, text) },
                onRedirect = { id, text -> AgentForegroundService.ensureCrewKeepalive(context); manager?.sendUserMessage(id, text) },
                onStopBot = { id -> manager?.stop(id) },
                onBoardReference = { reference -> onBoardReference(missionId, reference) },
                onResume = { id -> access.resumeBot(session, id) },
                onOpenPreview = onOpenPreview?.let { open -> { token -> open(session, token) } })
        }
    }
}

/**
 * A mission whose session or id does not resolve shows "not available"; it is never replaced by
 * another conversation. Resolution reads the registry, so it runs off the main thread.
 */
@Composable
private fun CrewSessionGate(
    access: CrewSessionAccess,
    session: String?,
    missionId: String,
    content: @Composable (String, CrewMissionSnapshot) -> Unit,
) {
    val resolved by produceState<Boolean?>(null, session) {
        value = if (session == null) false else withContext(Dispatchers.IO) {
            runCatching { access.resolve(session) }.getOrDefault(false)
        }
    }
    val missions = if (resolved == true) access.missions(requireNotNull(session)) else null
    when {
        resolved == false -> CrewMissionUnavailable()
        resolved == null || missions == null -> Box(Modifier.fillMaxSize().testTag("crew-session-loading"),
            contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> missions.lastOrNull { it.missionId == missionId }
            ?.let { content(requireNotNull(session), it) } ?: CrewMissionUnavailable()
    }
}
