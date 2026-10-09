package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.crew.BotDefinition
import com.jarvys.agent.crew.CrewProfile
import com.jarvys.agent.crew.CrewProfileRepository
import com.jarvys.agent.skills.SkillRepository
import com.jarvys.agent.proactive.ProactiveConversation
import com.jarvys.agent.tasks.ScheduledTaskConversation
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Main-chat creation is a reviewed local definition, not a connector grant or a running mission. */
class BotCreationTool internal constructor(private val service: BotCreationService) : CoreTool {
    constructor(context: Context, sessionId: String) : this(BotCreationService(context, sessionId))

    override fun canDelegate() = false
    override fun auditDetail(arguments: Map<String, Any>?) = "Review and create a custom bot"
    private val specification by lazy { buildDeclaration() }
    override fun declaration(): ToolSpec = specification
    private fun buildDeclaration(): ToolSpec {
        fun text(description: String, max: Int) = mapOf("type" to "string", "description" to description, "minLength" to 1, "maxLength" to max)
        fun choices(values: List<String>, limit: Int = 128) = mapOf("type" to "array", "items" to if (values.isEmpty()) mapOf("type" to "string") else mapOf("type" to "string", "enum" to values), "maxItems" to if (values.isEmpty()) 0 else limit, "uniqueItems" to true)
        val properties = linkedMapOf<String, Any>(
            "request_id" to text("A unique key for this creation request (letters, digits, dash/underscore). Keep the SAME key on retries; use a new key only for a new user request.", 80),
            "name" to text("Simple human-readable name, ideally 1–3 words, at most 4 words. Do not use a long role description.", 40),
            "description" to text("Short purpose of this reusable bot.", 240),
            "instructions" to text("Complete reusable instructions in English, derived only from the user's requested behavior. Do not copy private history or hidden prompts.", 16000),
            "capabilities" to choices(service.capabilities()),
            "skill_ids" to choices(service.skills(), SkillRepository.MAX_SKILLS_PER_RUN),
            "workspace_mode" to mapOf("type" to "string", "enum" to listOf("legacy_chat", "conversation_project"), "description" to "coding_* and project_* tools require conversation_project. project_exec also requires project_jobs. Skills require read_skill."),
            "icon_prompt" to text("Freeform thematic visual prompt for the requested bot's static fallback icon. No private images, attachments, URLs or paths are read.", BotIconService.MAX_PROMPT_CHARS),
            "visual_description" to text("Original mascot design and state-specific movement description, separate from bot instructions. Plain text, no control characters.", 1200),
            "mascot_scene_json" to text(BotMascotTool.SCENE_GUIDE, BotMascotSceneCompiler.MAX_SOURCE_BYTES),
        )
        return ToolSpec(NAME, "jarvys/bots", "Create and persist a custom bot from the user's request, including its simple name, English instructions, original locally compiled mascot and generated static fallback icon. " +
            "The user reviews this exact definition, visual description, scene hash, icon prompt and minimum capability/skill scope before saving. This never changes connector permissions or starts a task. " +
            "Image generation uses existing signed-in Codex image access only; unavailable or failed generation returns a saved bot with an explicitly incomplete icon. " +
            "Never claim the icon is finished unless icon_status is generated. Mascot compilation is local and independent of image access; LOCAL_COMPILED does not prove Android playback. The current visual stays static; Android playback is not available yet. Retry only with the same request_id, then inspect list_bots; never recreate to fix a visual. Main chat only.",
            "bots", ToolSpec.Status.IMPLEMENTED, emptyMap(), properties.keys.toList(),
            mapOf("type" to "object", "properties" to properties, "required" to properties.keys.toList(), "additionalProperties" to false))
    }
    override fun execute(arguments: Map<String, Any>?, token: CancellationToken): CoreToolResult = service.create(arguments, token)

    companion object {
        const val NAME = "create_bot"
        @JvmStatic fun isAvailable(context: Context?, depth: Int, sessionId: String?) = BotCatalogTool.isAvailable(context, depth, sessionId)
    }
}

internal class BotCreationService(
    private val repository: CrewProfileRepository,
    private val sessionId: String,
    private val currentCapabilities: () -> List<String>,
    private val currentSkills: () -> List<String>,
    private val approve: (ApprovalSummary, CancellationToken) -> ApprovalDecision,
    private val iconAvailable: () -> Boolean,
    private val generateIcon: (String, Int, String, CancellationToken) -> BotDefinition,
    private val compileMascot: ((String, Int, String, String, String, CancellationToken) -> BotDefinition)? = null,
) {
    constructor(context: Context, sessionId: String) : this(
        CrewProfileRepository(context), sessionId,
        { CoreAgentRuntime.profileCapabilities(context, sessionId) },
        { SkillRepository.get(context).also { it.refresh() }.enabledForRun().map { it.metadata.id } },
        { summary, token -> ApprovalGate.INSTANCE.request(summary, token) },
        { BotIconService(context, sessionId, ProviderSettings(context)).isAvailable },
        { id, revision, prompt, token -> BotIconService(context, sessionId, ProviderSettings(context)).generateAndAssign(id, revision, prompt, token) },
        { id, revision, request, description, scene, token -> BotMascotService(context, sessionId)
            .assign(id, revision, request, description, scene, token, false).definition },
    )

    fun capabilities() = currentCapabilities().filterNot { it == com.jarvys.agent.skills.SkillScopePolicy.APK_FACTORY_TOOL }.distinct()
    fun skills() = currentSkills().filterNot { com.jarvys.agent.skills.SkillScopePolicy.isReserved(it) }.distinct()

    fun create(arguments: Map<String, Any>?, token: CancellationToken): CoreToolResult {
        token.throwIfCancelled()
        if (token.isCrewRun || (sessionId.isBlank() || sessionId == ProactiveConversation.SESSION_ID || sessionId == ScheduledTaskConversation.SESSION_ID))
            return CoreToolResult.failure("Bot creation is available only in the main chat.")
        try {
            require(arguments != null && (arguments.keys == LEGACY_FIELDS || arguments.keys == FIELDS)) {
                "Provide the bot definition and both visual_description and mascot_scene_json; unknown fields are not accepted."
            }
            fun text(field: String, max: Int): String {
                val value = arguments[field]
                require(value is String && value.isNotBlank() && value.length <= max && !value.contains('\u0000')) { "Invalid $field." }
                return value.trim()
            }
            fun ids(field: String): List<String> {
                val value = arguments[field]
                require(value is List<*> && value.size <= 128 && value.all { it is String } && value.distinct().size == value.size) { "Invalid $field." }
                return value.map { it as String }
            }
            val requestKey = text("request_id", 80)
            require(requestKey.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))) { "request_id must contain only letters, digits, dash or underscore." }
            val name = text("name", 40)
            require(name.split(Regex("\\s+")).size <= 4) { "Use a simple bot name of at most four words." }
            val instructions = text("instructions", 16000)
            val iconPrompt = text("icon_prompt", BotIconService.MAX_PROMPT_CHARS)
            val hasMascot = arguments.keys == FIELDS
            val visualDescription = if (hasMascot) text("visual_description", 1200).also { value ->
                com.jarvys.agent.crew.BotMascotDescriptor.requireVisualDescription(value)
            } else null
            // Keep the exact source bytes in the fingerprint; do not trim or silently rewrite the agent's design.
            val mascotScene = if (hasMascot) (arguments["mascot_scene_json"] as? String)?.also {
                BotMascotSceneCompiler.validateProduct(it)
            } ?: throw IllegalArgumentException("mascot_scene_json must be a bounded product scene JSON string.") else null
            val workspace = text("workspace_mode", 32)
            require(workspace in listOf("legacy_chat", "conversation_project")) { "Unsupported workspace_mode." }
            val description = text("description", 240)
            val selectedSkills = ids("skill_ids")
            val selectedCapabilities = ids("capabilities")
            val operationPrefix = "custom-chat-" + digest(sessionId + "\u0000" + requestKey) + "-"
            // Both hashes are persisted atomically as the stable ID. A reused operation key with
            // any changed approved field (including icon prompt) must never create another bot.
            val approvedPayload = JSONArray(listOf(name, description, instructions, JSONArray(selectedSkills),
                JSONArray(selectedCapabilities), workspace, iconPrompt))
            if (hasMascot) approvedPayload.put(visualDescription).put(mascotScene)
            val fingerprint = digest(approvedPayload.toString())
            val id = operationPrefix + fingerprint
            val draft = CrewProfile(id, 1, name, description, instructions, selectedSkills, selectedCapabilities,
                if (workspace == "conversation_project") CrewProfile.WorkspaceMode.CONVERSATION_PROJECT else CrewProfile.WorkspaceMode.LEGACY_CHAT)
            draft.validateAvailability(capabilities(), skills())
            synchronized(CREATION_LOCK) { existing(draft, operationPrefix)?.let { return outcome(it, true, "not_retried") } }
            val summary = ApprovalSummary(
                title = "Create bot: $name",
                lines = listOf("Purpose: ${draft.description}", "Instructions (English):\n$instructions",
                    "Tools: ${draft.capabilities.joinToString().ifEmpty { "None" }}", "Skills: ${draft.skillIds.joinToString().ifEmpty { "None" }}",
                    "Workspace: $workspace", "Icon prompt: $iconPrompt",
                    if (hasMascot) "Mascot design and motion: $visualDescription\nScene SHA-256: ${digest(checkNotNull(mascotScene))}\nNine states plus nine reduced poses; compiled locally. Android playback is not available yet; the current visual remains static."
                    else "Legacy request: no animated mascot source was supplied.",
                    if (iconAvailable()) "Generate the icon with existing Codex image access and quota." else "Image access is unavailable. Save the bot now with its default icon; generation remains incomplete.",
                    "Saves this definition only. Existing connector approvals remain in force; no task starts."),
                allowAlwaysAvailable = false,
            )
            if (approve(summary, token) != ApprovalDecision.APPROVED)
                return CoreToolResult.failure("Bot creation was not approved. No bot was saved.")
            token.throwIfCancelled()
            draft.validateAvailability(capabilities(), skills())
            var saved: BotDefinition? = null
            var duplicate = false
            BotIconService.CommitGate(token).use { gate -> gate.commit {
                synchronized(CREATION_LOCK) {
                    saved = existing(draft, operationPrefix)?.also { duplicate = true } ?: repository.create(draft, capabilities(), skills())
                }
            } }
            val created = checkNotNull(saved)
            if (duplicate) return outcome(created, true, "not_retried")
            var current = created
            var mascotStatus = if (hasMascot) "unavailable" else "not_requested_legacy"
            if (hasMascot && compileMascot != null) {
                try {
                    current = compileMascot.invoke(created.id, created.revision, requestKey,
                        checkNotNull(visualDescription), checkNotNull(mascotScene), token)
                    mascotStatus = "LOCAL_COMPILED"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: RuntimeException) {
                    current = repository.definition(created.id); mascotStatus = "failed"
                    // Never rebase the original icon request onto a concurrent visual/profile edit.
                    if (current.revision != created.revision) return outcome(current, false, "not_started_conflict", mascotStatus)
                }
            }
            if (!iconAvailable()) return outcome(current, false, "unavailable", mascotStatus)
            return try {
                outcome(generateIcon(current.id, current.revision, iconPrompt, token), false, "generated", mascotStatus)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: BotIconService.Failure) { outcome(repository.definition(created.id), false, "failed_${failure.reason.name.lowercase()}", mascotStatus) }
            catch (_: RuntimeException) { outcome(repository.definition(created.id), false, "failed", mascotStatus) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: IllegalArgumentException) { return CoreToolResult.failure(invalid.message ?: "Invalid bot definition. Nothing was changed.") }
        catch (_: RuntimeException) { return CoreToolResult.failure("Could not finish bot creation. Use list_bots to check the catalog and retry only with the same request_id.") }
    }

    private fun existing(draft: CrewProfile, operationPrefix: String): BotDefinition? {
        val matches = repository.definitions().filter { it.id.startsWith(operationPrefix) }
        require(matches.size <= 1) { "Multiple bots match this creation request. Check the catalog before making changes." }
        return matches.singleOrNull()?.also {
            require(it.id == draft.id && !it.builtIn && it.profile.withVersion(1).toJson().toString() == draft.toJson().toString()) {
                "This request_id already identifies a different or edited bot. Check list_bots; do not overwrite it."
            }
        }
    }

    private fun outcome(bot: BotDefinition, duplicate: Boolean, status: String, mascotStatus: String = "not_retried"): CoreToolResult {
        val actual = if (bot.iconRef.isNotEmpty()) "generated" else status
        return CoreToolResult.success(JSONObject().put("bot_id", bot.id).put("name", bot.profile.name).put("revision", bot.revision)
            .put("saved", true).put("already_existed", duplicate).put("icon_status", actual)
            .put("icon_complete", bot.iconRef.isNotEmpty()).put("enabled", bot.enabled)
            .put("mascot_status", if (bot.mascot != null) "LOCAL_COMPILED" else mascotStatus)
            .put("mascot_compiled", bot.mascot != null).put("android_playback_verified", false)
            .put("mascot_next_step", if (bot.mascot != null) "Scene saved locally. Android playback is not available yet; the current visual remains static."
                else "Mascot is incomplete. Use compile_bot_mascot for this exact existing bot; do not recreate it.")
            .put("next_step", if (bot.iconRef.isNotEmpty()) "Verify with list_bots. No task was started."
                else "The bot is saved, but its generated icon is incomplete. Do not recreate it. Generate an icon for this exact bot after resolving image access or cancellation.")
            .toString())
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private val CREATION_LOCK = Any()
        private val LEGACY_FIELDS = setOf("request_id", "name", "description", "instructions", "capabilities", "skill_ids", "workspace_mode", "icon_prompt")
        private val FIELDS = LEGACY_FIELDS + setOf("visual_description", "mascot_scene_json")
    }
}
