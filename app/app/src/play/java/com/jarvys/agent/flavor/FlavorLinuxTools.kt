package com.jarvys.agent.flavor

import android.content.Context
import com.jarvys.agent.CorePromptBudget
import com.jarvys.agent.CoreTool
import com.jarvys.agent.coding.ProjectScope
import com.jarvys.agent.crew.CrewManager

/** Play has no Linux implementation, execution capabilities, or Linux-specific prompt guidance. */
object FlavorLinuxTools {
    @JvmStatic fun create(context: Context, sessionId: String?, depth: Int): List<CoreTool> = emptyList()
    @JvmStatic fun create(context: Context, sessionId: String?, depth: Int, budget: CorePromptBudget): List<CoreTool> = emptyList()
    @JvmStatic fun systemPromptSection(context: Context?, sessionId: String?, depth: Int): String? = null

    @JvmStatic fun profileCapabilityNames(context: Context?, conversationId: String?): List<String> = emptyList()
    @JvmStatic fun profilePrompt(context: Context?, conversationId: String?): String = ""

    @JvmStatic fun createProfile(context: Context, conversationId: String, bot: CrewManager.Bot,
                                 crew: CrewManager, budget: CorePromptBudget): List<CoreTool> {
        requireUnavailableCapabilitiesAbsent(bot.role.tools)
        return emptyList()
    }

    @JvmStatic fun validateProfileResume(context: Context, selectedNames: List<String>) {
        requireUnavailableCapabilitiesAbsent(selectedNames)
    }

    @JvmStatic fun checkpointRecovery(context: Context, conversationId: String, scope: ProjectScope,
                                     owners: List<String>): String {
        check(conversationId.isNotBlank() && conversationId == scope.conversationId()) {
            "Project recovery is unavailable for this conversation"
        }
        scope.validate()
        scope.durableIdentity()
        // The coordinator incorporates this observation into an explicitly resumed file-only mission.
        // A saved Linux job requires the real Full recovery backend before that mission can resume.
        check(owners.isEmpty()) {
            "Saved Linux project jobs cannot be verified in this Play build; resume did not start. " +
                "Their execution outcomes, retained logs, and process liveness remain unverified. " +
                "Original evidence is preserved; no command was replayed or process signalled."
        }
        return "Project job recovery is unavailable in this Play build. No job owners were supplied by this checkpoint. " +
            "No execution outcome or process liveness was verified, and no command was replayed or process signalled."
    }

    private fun requireUnavailableCapabilitiesAbsent(selectedNames: List<String>) {
        val unavailable = selectedNames.filter { it in CodingExecutionTools.NAMES || it.startsWith("linux_") }
        check(unavailable.isEmpty()) {
            "Linux project execution is unavailable in this Play build (${unavailable.distinct().joinToString()}); " +
                "no command or resume was started. Remove unavailable capabilities before continuing."
        }
    }
}
