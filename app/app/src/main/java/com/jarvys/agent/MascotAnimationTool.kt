package com.jarvys.agent

import com.jarvys.agent.ui.mascot.jarvysHeaderAnimationIds

/** Main-chat-only request for one locally available header animation. */
class MascotAnimationTool internal constructor(
    private val sessionId: String,
    private val request: (String, String) -> String?,
) : CoreTool {
    constructor(sessionId: String) : this(sessionId, AgentRunUiState::requestHeaderMascotAnimation)

    override fun canDelegate() = false

    override fun auditDetail(arguments: Map<String, Any>?) = "Request a one-shot main-chat header mascot animation"

    override fun declaration() = ToolSpec(
        NAME,
        "jarvys/chat",
        DESCRIPTION,
        "conversation",
        ToolSpec.Status.IMPLEMENTED,
        mapOf("animation" to "string"),
        listOf("animation"),
        mapOf(
            "type" to "object",
            "required" to listOf("animation"),
            "additionalProperties" to false,
            "properties" to mapOf("animation" to mapOf(
                "type" to "string",
                "enum" to ANIMATIONS,
                "description" to "Header animation to play once.",
            )),
        ),
    )

    override fun execute(arguments: Map<String, Any>?, token: CancellationToken): CoreToolResult {
        token.throwIfCancelled()
        if (token.isCrewRun || !BotCatalogTool.isAvailableForSession(sessionId)) {
            return CoreToolResult.failure("Mascot animations are available only in the main chat.")
        }
        val animation = try {
            parseAnimation(arguments)
        } catch (invalid: IllegalArgumentException) {
            return CoreToolResult.failure(invalid.message ?: "Invalid mascot animation request.")
        }
        val failure = request(sessionId, animation)
        return if (failure == null) CoreToolResult.success("animation requested: $animation")
        else CoreToolResult.failure(failure)
    }

    private fun parseAnimation(arguments: Map<String, Any>?): String {
        require(arguments != null) { "animation is required." }
        val unexpected = arguments.keys - setOf("animation")
        require(unexpected.isEmpty()) { "Unexpected parameter(s): ${unexpected.sorted().joinToString()}." }
        val animation = arguments["animation"] as? String
            ?: throw IllegalArgumentException("animation is required and must be a string.")
        require(animation in ANIMATIONS) {
            "animation must be one of: ${ANIMATIONS.joinToString(", ")}."
        }
        return animation
    }

    companion object {
        const val NAME = "mascot_animation"
        val ANIMATIONS = jarvysHeaderAnimationIds
        const val GUIDANCE = "Use mascot_animation only when the user asks to play a header animation. It plays once, then resumes the current agent state. It is available only while the main-chat mascot is visible, reduced motion is off, and the selected animation asset is available."
        private const val DESCRIPTION = "Play one available Jarvys header animation once in the active main chat."
    }
}
