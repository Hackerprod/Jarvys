package com.jarvys.agent

import android.content.Context
import org.json.JSONObject

object UserDecisionRequests {
    val gate = UserDecisionGate()
    @JvmStatic fun resolve(id: String, result: UserDecisionResult): Boolean = gate.resolve(id, result)
}

/** Main-chat-only model capability for asking the user to select among explicit choices. */
class UserDecisionTool @JvmOverloads constructor(
    context: Context,
    private val sessionId: String,
    private val gate: UserDecisionGate = UserDecisionRequests.gate,
    private val availability: () -> Boolean = {
        UserDecisionUiAvailability.isChatVisible() && AgentRunUiState.state.value.let {
            it.running && it.sessionId == sessionId
        }
    },
    private val presenterOverride: UserDecisionPresenter? = null,
) : CoreTool {
    private val store = LocalRunStore(context.applicationContext)

    override fun declaration(): ToolSpec = ToolSpec(
        "request_user_decision", "jarvys/chat", DESCRIPTION, "conversation", ToolSpec.Status.IMPLEMENTED,
        mapOf("title" to "string", "body" to "string", "options" to "array", "allow_dismiss" to "boolean"),
        listOf("title", "body", "options"), schema(),
    )

    override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
        val spec = parse(arguments)
        val presenter = presenterOverride ?: object : UserDecisionPresenter {
            override fun isAvailable(): Boolean = availability()

            override fun show(id: String, spec: UserDecisionSpec) {
                store.appendUserDecisionRequest(sessionId, id, spec.title, spec.body, spec.options, spec.allowDismiss)
                AgentRunUiState.showUserDecision(sessionId, id, spec.title, spec.body, spec.options, spec.allowDismiss)
            }

            override fun update(id: String, result: UserDecisionResult) {
                val status = when (result) {
                    is UserDecisionResult.Selected -> "SELECTED"
                    UserDecisionResult.Dismissed -> "DISMISSED"
                    UserDecisionResult.Cancelled -> "CANCELLED"
                    UserDecisionResult.Unavailable -> "UNAVAILABLE"
                }
                val option = (result as? UserDecisionResult.Selected)?.option
                AgentRunUiState.updateUserDecision(sessionId, id, status, option?.id, option?.label)
                runCatching { store.appendUserDecisionResolution(sessionId, id, status, option?.id, option?.label) }
            }
        }
        val result = gate.request(spec, token, presenter)
        val output = JSONObject()
        when (result) {
            is UserDecisionResult.Selected -> output.put("status", "selected")
                .put("option_id", result.option.id).put("option_label", result.option.label)
            UserDecisionResult.Dismissed -> output.put("status", "dismissed")
            UserDecisionResult.Cancelled -> output.put("status", "cancelled")
            UserDecisionResult.Unavailable -> output.put("status", "unavailable")
        }
        return CoreToolResult.success(output.toString())
    }

    private fun parse(arguments: Map<String, Any>): UserDecisionSpec {
        val unknown = arguments.keys - setOf("title", "body", "options", "allow_dismiss")
        require(unknown.isEmpty()) { "Unexpected parameter(s): ${unknown.sorted().joinToString()}" }
        val title = requiredText(arguments, "title")
        val body = requiredText(arguments, "body")
        val rows = arguments["options"] as? List<*>
            ?: throw IllegalArgumentException("options must be a non-empty array of option objects")
        require(rows.isNotEmpty()) { "options must contain at least one option" }
        val ids = HashSet<String>()
        var primaryCount = 0
        val options = rows.mapIndexed { index, raw ->
            val row = raw as? Map<*, *> ?: throw IllegalArgumentException("options[$index] must be an object")
            require(row.keys.all { it is String }) { "options[$index] field names must be strings" }
            val unexpected = row.keys.filterIsInstance<String>().toSet() - setOf("id", "label", "description", "role")
            require(unexpected.isEmpty()) { "options[$index] has unexpected field(s): ${unexpected.sorted().joinToString()}" }
            val id = fieldText(row, "id", "options[$index].id")
            require(ids.add(id)) { "options[$index].id is duplicated: $id" }
            val label = fieldText(row, "label", "options[$index].label")
            val description = if (!row.containsKey("description")) "" else when (val value = row["description"]) {
                is String -> value.trim()
                else -> throw IllegalArgumentException("options[$index].description must be a string")
            }
            val role = if (!row.containsKey("role")) UserDecisionRole.DEFAULT else when (val value = row["role"]) {
                "primary" -> UserDecisionRole.PRIMARY
                "default" -> UserDecisionRole.DEFAULT
                "destructive" -> UserDecisionRole.DESTRUCTIVE
                is String -> throw IllegalArgumentException("options[$index].role must be primary, default, or destructive")
                else -> throw IllegalArgumentException("options[$index].role must be a string")
            }
            if (role == UserDecisionRole.PRIMARY) primaryCount++
            UserDecisionOption(id, label, description, role)
        }
        require(primaryCount <= 1) { "options may contain at most one primary role" }
        val allowDismiss = if (!arguments.containsKey("allow_dismiss")) true else when (val value = arguments["allow_dismiss"]) {
            is Boolean -> value
            else -> throw IllegalArgumentException("allow_dismiss must be a boolean")
        }
        return UserDecisionSpec(title, body, options, allowDismiss)
    }

    private fun requiredText(arguments: Map<String, Any>, name: String): String =
        fieldText(arguments, name, name)

    private fun fieldText(values: Map<*, *>, key: String, path: String): String {
        val value = values[key] as? String ?: throw IllegalArgumentException("$path must be a string")
        require(value.trim().isNotEmpty()) { "$path must not be empty" }
        return value.trim()
    }

    private fun schema(): Map<String, Any> {
        val optionProperties = linkedMapOf<String, Any>(
            "id" to mapOf("type" to "string", "minLength" to 1),
            "label" to mapOf("type" to "string", "minLength" to 1),
            "description" to mapOf("type" to "string"),
            "role" to mapOf("type" to "string", "enum" to listOf("primary", "default", "destructive")),
        )
        val optionSchema = linkedMapOf<String, Any>(
            "type" to "object", "properties" to optionProperties,
            "required" to listOf("id", "label"), "additionalProperties" to false,
        )
        return linkedMapOf(
            "type" to "object",
            "properties" to linkedMapOf(
                "title" to mapOf("type" to "string", "minLength" to 1),
                "body" to mapOf("type" to "string", "minLength" to 1),
                "options" to mapOf("type" to "array", "minItems" to 1, "items" to optionSchema),
                "allow_dismiss" to mapOf("type" to "boolean", "default" to true),
            ),
            "required" to listOf("title", "body", "options"),
            "additionalProperties" to false,
        )
    }

    companion object {
        const val NAME = "request_user_decision"
        private const val DESCRIPTION = "Ask the user to select one of the provided options in the active chat. Use only for meaningful choices; never use it to collect free text."
    }
}

object UserDecisionUiAvailability {
    @Volatile private var chatVisible = false
    @JvmStatic fun setChatVisible(visible: Boolean) { chatVisible = visible }
    @JvmStatic fun isChatVisible(): Boolean = chatVisible
}
