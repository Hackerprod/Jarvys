package com.jarvys.agent.flavor

import android.content.Context
import com.jarvys.agent.CorePromptBudget
import com.jarvys.agent.CoreTool

/** Play has no Linux capability or Linux-specific prompt text. */
object FlavorLinuxTools {
    @JvmStatic fun create(context: Context, sessionId: String?, depth: Int): List<CoreTool> = emptyList()
    @JvmStatic fun create(context: Context, sessionId: String?, depth: Int, budget: CorePromptBudget): List<CoreTool> = emptyList()
    @JvmStatic fun systemPromptSection(context: Context?, sessionId: String?, depth: Int): String? = null
}
