package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.crew.BotDefinition
import com.jarvys.agent.crew.BotMascotDescriptor
import com.jarvys.agent.crew.CrewProfileRepository
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Local, bounded visual authoring only. It cannot grant capabilities or execute scene content. */
class BotMascotTool internal constructor(private val service: BotMascotService) : CoreTool {
    constructor(context: Context, sessionId: String) : this(BotMascotService(context, sessionId))
    override fun canDelegate() = false
    override fun auditDetail(arguments: Map<String, Any>?) = "Review and compile a custom bot mascot locally"
    override fun declaration() = SPEC
    override fun execute(arguments: Map<String, Any>?, token: CancellationToken): CoreToolResult {
        token.throwIfCancelled()
        if (!service.available(token)) return CoreToolResult.failure("Mascot authoring is available only in the main chat.")
        return try {
            require(arguments != null && arguments.keys == FIELDS) { "Provide exactly request_id, bot_id, expected_revision, visual_description and scene_json." }
            val id = arguments["bot_id"] as? String ?: throw IllegalArgumentException("Invalid bot_id.")
            val request = arguments["request_id"] as? String ?: throw IllegalArgumentException("Invalid request_id.")
            val description = arguments["visual_description"] as? String ?: throw IllegalArgumentException("Invalid visual_description.")
            val source = arguments["scene_json"] as? String ?: throw IllegalArgumentException("scene_json must be the original scene JSON string.")
            val rawRevision = arguments["expected_revision"]
            require(rawRevision is Number) { "expected_revision must be a positive integer from list_bots." }
            val revision = try { java.math.BigDecimal(rawRevision.toString()).intValueExact() } catch (_: RuntimeException) { 0 }
            require(revision > 0) { "expected_revision must be a positive integer from list_bots." }
            CoreToolResult.success(service.outcome(service.assign(id, revision, request, description, source, token, true)).toString())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: IllegalArgumentException) { CoreToolResult.failure(invalid.message ?: "Invalid mascot scene. The existing visual was kept.") }
        catch (_: RuntimeException) { CoreToolResult.failure("Could not finish local mascot authoring. Inspect list_bots, then retry the same request_id; do not recreate the bot.") }
    }
    companion object {
        const val NAME = "compile_bot_mascot"
        internal val FIELDS = setOf("request_id", "bot_id", "expected_revision", "visual_description", "scene_json")
        const val SCENE_GUIDE = "Author original geometry and choreography, not a recolored fixed character. scene_json is an object with contract=bot-mascot-v1, name, nodes, animations. " +
            "nodes is an ordered array: each node has kind group/ellipse/rectangle, unique name, optional parent (earlier node; default Artboard), optional x/y/rotation/scaleX/scaleY; shapes require width,height (1..256) and unsigned ARGB integer color; rectangle optionally radius. " +
            "animations must contain Idle,Thinking,Working,Queued,WaitingProvider,WaitingUser,Done,Error,Interrupted and each name plus Reduced (18 total). Each has duration (1..600 frames at 60 fps), loop boolean, tracks array. A track is {node,property,keys:[[frame,value],...]}; property is x/y/rotation/scaleX/scaleY, frames increase starting at0. " +
            "All 18 animations must reset the same node/property target set. Each Reduced variant has loop=false and one constant key per track; retain a readable state-specific pose. Names are opaque labels. Maximum 128 KiB UTF-8 source, 96 nodes, depth 8, 64 tracks/state, 64 keys/track and 64 KiB compiled. No URLs,paths,images,fonts,scripts,audio,code or external resources."
        private val SPEC by lazy {
            fun text(description: String, max: Int) = mapOf("type" to "string", "description" to description, "minLength" to 1, "maxLength" to max)
            val p = linkedMapOf<String, Any>(
                "request_id" to text("Stable unique operation key. Keep identical on retries for the same scene and bot.",80),
                "bot_id" to text("Exact existing enabled custom bot ID from list_bots.",256),
                "expected_revision" to mapOf("type" to "integer","minimum" to 1,"description" to "Current definition revision from list_bots; stale writes cannot overwrite newer edits."),
                "visual_description" to text("Short plain-text design and motion description requested by the user, separate from operational instructions.",1200),
                "scene_json" to text(SCENE_GUIDE, BotMascotSceneCompiler.MAX_SOURCE_BYTES),
            )
            ToolSpec(NAME,"jarvys/bots","Compile and save an original animated mascot for an existing custom bot, locally on the phone, only when requested by the user. " +
                "Review is required; original editable scene and immutable versioned bytes are saved with an idempotent receipt. No image service, CLI or new network service is used. " +
                "LOCAL_COMPILED proves local compilation, not Android rendering acceptance. Android playback is not available yet; the current visual remains the static PNG/glyph fallback. Never claim playback is verified from compilation alone. " +
                "Read list_bots first; never guess IDs. Built-ins are immutable. This changes presentation only, not prompts, tools, permissions or running tasks. Main chat only.",
                "bots",ToolSpec.Status.IMPLEMENTED,emptyMap(),FIELDS.toList(),mapOf("type" to "object","properties" to p,"required" to FIELDS.toList(),"additionalProperties" to false))
        }
        @JvmStatic fun isAvailable(context: Context?, depth: Int, sessionId: String?) = BotCatalogTool.isAvailable(context,depth,sessionId)
    }
}

internal class BotMascotService(
    private val repository: CrewProfileRepository,
    private val store: BotMascotStore,
    private val sessionId: String,
    private val approve: (ApprovalSummary, CancellationToken) -> ApprovalDecision,
) {
    constructor(context: Context, sessionId: String): this(CrewProfileRepository(context),BotMascotStore(context),sessionId,
        { summary, token -> ApprovalGate.INSTANCE.request(summary,token) })
    fun available(token: CancellationToken) = !token.isCrewRun && sessionId.isNotBlank() &&
        sessionId != com.jarvys.agent.proactive.ProactiveConversation.SESSION_ID && sessionId != com.jarvys.agent.tasks.ScheduledTaskConversation.SESSION_ID

    /** requireApproval=false is used only inside the already-reviewed create_bot transaction. */
    fun assign(id: String, revision: Int, request: String, description: String, source: String,
               token: CancellationToken, requireApproval: Boolean): CrewProfileRepository.MascotAssignment {
        token.throwIfCancelled()
        require(available(token)) { "Mascot authoring is available only in the main chat." }
        BotMascotDescriptor.requireOperationId(request)
        require(id.matches(Regex("[a-z][a-z0-9._-]*")) && revision > 0) { "Choose an existing custom bot and its current revision." }
        BotMascotDescriptor.requireVisualDescription(description)
        require(source.length <= BotMascotSceneCompiler.MAX_SOURCE_BYTES) { "Mascot source exceeds its byte budget." }
        val buffer = try { StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(source))
        } catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("Scene must contain valid UTF-8 text.") }
        require(buffer.remaining() in 1..BotMascotSceneCompiler.MAX_SOURCE_BYTES) { "Mascot source exceeds its byte budget." }
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val operation = "mascot-" + digest(sessionId + "\u0000" + request)
        val payload = digest(JSONArray(listOf(id,description,digest(source))).toString())
        repository.mascotOperation(id,operation,payload)?.let { return it }
        editable(repository.definition(id),revision)
        // Validate the full product source before approval or disk writes, but never regenerate on a durable retry.
        val compiled = BotMascotSceneCompiler.compileProduct(bytes)
        if (requireApproval) {
            val summary = ApprovalSummary(title="Create mascot: ${compiled.validation.name}", lines=listOf(
                "Design and movement: $description", "Contract: bot-mascot-v1; nine states and nine static reduced-motion poses.",
                "Local scene: ${compiled.validation.complexity.nodeCount} nodes, ${compiled.validation.complexity.keyframeCount} keyframes; source SHA-256: ${digest(source)}",
                "Compiled locally without a CLI, image service or new network service. Existing PNG/glyph remains the current visual; Android playback is not available yet.",
                "Presentation only. Instructions, capabilities, skills and connector approvals remain unchanged."),allowAlwaysAvailable=false)
            require(approve(summary,token)==ApprovalDecision.APPROVED) { "Mascot authoring was not approved. The existing visual was kept." }
        }
        token.throwIfCancelled()
        repository.mascotOperation(id,operation,payload)?.let { return it }
        editable(repository.definition(id),revision)
        var prepared: BotMascotStore.ValidatedPackage? = null
        try {
            prepared = store.prepare(id,description,bytes,{ input, cancellation ->
                cancellation.throwIfCancelled()
                // No caller can supply a .riv; every package goes through the bounded product compiler.
                BotMascotSceneCompiler.compileProduct(input).bytes.also { cancellation.throwIfCancelled() }
            },token)
            var saved: CrewProfileRepository.MascotAssignment? = null
            BotIconService.CommitGate(token).use { gate -> gate.commit {
                saved = repository.setMascot(id,revision,checkNotNull(prepared),operation,payload)
            } }
            return checkNotNull(saved)
        } finally {
            prepared?.let {
                // An unknown catalog state is not permission to delete; cleanup must not mask cancellation or a durable result.
                try { store.cleanupUnassigned(repository,id,it.descriptor().packageRef) } catch (_: RuntimeException) { }
            }
        }
    }
    private fun editable(bot: BotDefinition, revision: Int) {
        require(!bot.builtIn && !CrewProfileRepository.isBuiltInId(bot.id)) { "Built-in bot visuals cannot be changed." }
        require(bot.enabled) { "Enable the bot before creating its mascot." }
        require(bot.revision==revision) { "The bot changed while editing. Read list_bots before retrying this operation." }
    }
    fun outcome(result: CrewProfileRepository.MascotAssignment) = JSONObject()
        .put("bot_id",result.definition.id).put("revision",result.definition.revision).put("saved",true)
        .put("already_existed",result.alreadyExisted).put("assigned_revision",result.receipt.assignedRevision)
        .put("mascot_status","LOCAL_COMPILED").put("mascot_compiled",true)
        .put("asset_current",result.definition.mascot==result.receipt.mascot).put("source_hash",result.receipt.mascot.sourceHash)
        .put("asset_hash",result.receipt.mascot.assetHash).put("contract",result.receipt.mascot.contract)
        .put("android_playback_verified",false).put("playback","unavailable_static_fallback")
        .put("next_step","Inspect list_bots. The scene is compiled and saved; this does not establish Android playback acceptance. Do not recreate the bot.")
    companion object {
        internal fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
    }
}
