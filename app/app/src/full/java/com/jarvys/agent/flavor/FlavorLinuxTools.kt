package com.jarvys.agent.flavor

import android.content.Context
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.CorePromptBudget
import com.jarvys.agent.CoreTool
import com.jarvys.agent.MemoryReflectionRuntime
import com.jarvys.agent.UserDecisionPresenter
import com.jarvys.agent.UserDecisionRequests
import com.jarvys.agent.UserDecisionUiAvailability
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.linux.LinuxEnvironment
import com.jarvys.agent.linux.LinuxOutputSanitizer
import com.jarvys.agent.linux.LinuxRuntime
import com.jarvys.agent.proactive.ProactiveConversation
import com.jarvys.agent.tasks.ScheduledTaskConversation

/** Full-flavor seam only. No Linux implementation is linked from the Play source set. */
object FlavorLinuxTools {
    @JvmStatic
    fun create(context: Context, sessionId: String?, depth: Int): List<CoreTool> =
        create(context, sessionId, depth, CorePromptBudget.standard())

    @JvmStatic
    fun create(context: Context, sessionId: String?, depth: Int, budget: CorePromptBudget): List<CoreTool> {
        if (!available(context, sessionId, depth)) return emptyList()
        val app = context.applicationContext
        val activeSession = requireNotNull(sessionId)
        val chatAvailability = {
            UserDecisionUiAvailability.isChatVisible() && com.jarvys.agent.AgentRunUiState.state.value.let {
                it.running && it.sessionId == activeSession
            }
        }
        return createForRuntime(app, activeSession, depth, budget, LinuxEnvironment(app), ApprovalGate.INSTANCE,
            UserDecisionRequests.gate, null, chatAvailability)
    }

    @JvmStatic
    fun systemPromptSection(context: Context?, sessionId: String?, depth: Int): String? {
        if (!available(context, sessionId, depth)) return null
        return AppLanguageRuntime.localizedContext(requireNotNull(context).applicationContext)
            .getString(com.jarvys.agent.R.string.full_linux_system_prompt)
    }

    internal fun createForRuntime(
        context: Context,
        sessionId: String,
        depth: Int,
        budget: CorePromptBudget,
        runtime: LinuxRuntime,
        approvalGate: ApprovalGate,
        decisionGate: com.jarvys.agent.UserDecisionGate,
        decisionPresenter: UserDecisionPresenter?,
        decisionAvailability: () -> Boolean,
    ): List<CoreTool> {
        if (!available(context, sessionId, depth)) return emptyList()
        val app = context.applicationContext
        val localized = AppLanguageRuntime.localizedContext(app)
        val redactor = LinuxOutputSanitizer.from(app)
        return listOf(
            LinuxStatusTool(localized, sessionId, runtime, redactor, ::available),
            LinuxSetupTool(localized, sessionId, runtime, decisionGate, decisionPresenter,
                decisionAvailability, redactor, ::available),
            LinuxExecTool(localized, sessionId, runtime, approvalGate, redactor, budget, ::available),
            LinuxUninstallTool(localized, sessionId, runtime, decisionGate, decisionPresenter,
                decisionAvailability, redactor, ::available),
        )
    }

    internal fun available(context: Context?, sessionId: String?, depth: Int): Boolean {
        if (context == null || depth != 0 || sessionId.isNullOrBlank()) return false
        if (sessionId == ProactiveConversation.SESSION_ID || sessionId == ScheduledTaskConversation.SESSION_ID) return false
        if (sessionId.startsWith("proactive-") || sessionId.startsWith("task-") || sessionId.startsWith("task:")) return false
        if ("/crew/" in sessionId || sessionId.startsWith("jarvys-subagent-")) return false
        return !MemoryReflectionRuntime.isRunning(sessionId)
    }
}
