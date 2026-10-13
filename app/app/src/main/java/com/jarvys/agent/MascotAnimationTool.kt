package com.jarvys.agent

import com.jarvys.agent.ui.mascot.jarvysHeaderAnimationIds

/** Main-chat-only request for one locally available header animation. */
class MascotAnimationTool internal constructor(
    private val sessionId: String,
    private val request: (String, String, CancellationToken) -> HeaderMascotAnimationResult,
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
        val result = request(sessionId, animation, token)
        return CoreToolResult.success(result.toJson())
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
        const val GUIDANCE = "mascot_animation requests one playback of an available header animation in the active main chat. Its JSON result reports the animation and whether playback finished, was rejected with the UI's reason, or was cancelled with the run. The request is available while the main-chat mascot is active, visible, and not in reduced-motion mode, with the selected asset loaded."
        private const val DESCRIPTION = "Request one playback of an available Jarvys header animation in the active main chat and return its UI-reported outcome."
    }
}

private fun HeaderMascotAnimationResult.toJson(): String = buildString {
    append("{\"animation\":\"").append(jsonEscape(animationId)).append("\",\"outcome\":\"")
        .append(jsonEscape(outcome)).append('"')
    reason?.let { append(",\"reason\":\"").append(jsonEscape(it)).append('"') }
    append('}')
}

private fun jsonEscape(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
        }
    }
}
