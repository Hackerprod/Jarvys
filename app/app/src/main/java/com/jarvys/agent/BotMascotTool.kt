package com.jarvys.agent

import android.content.Context
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
    override fun auditDetail(arguments: Map<String, Any>?) = "Review and compile a bot mascot locally"
    override fun declaration() = SPEC
    override fun executionMode() = CoreTool.ExecutionMode.SEQUENTIAL
    override fun execute(arguments: Map<String, Any>?, token: CancellationToken): CoreToolResult {
        token.throwIfCancelled()
        if (!service.available(token)) return CoreToolResult.failure("Mascot authoring is available only in the main chat.")
        return try {
            require(arguments != null && arguments.keys == FIELDS) { "Provide exactly request_id, bot_id, expected_revision, visual_description and scene_json." }
            val id = arguments["bot_id"] as? String ?: throw IllegalArgumentException("Invalid bot_id.")
            val request = arguments["request_id"] as? String ?: throw IllegalArgumentException("Invalid request_id.")
            val description = arguments["visual_description"] as? String ?: throw IllegalArgumentException("Invalid visual_description.")
            val source = BotMascotSceneCompiler.normalizeProductScene(arguments["scene_json"], "scene_json")
            val rawRevision = arguments["expected_revision"]
            require(rawRevision is Number) { "expected_revision must be a positive integer from list_bots." }
            val revision = try { java.math.BigDecimal(rawRevision.toString()).intValueExact() } catch (_: RuntimeException) { 0 }
            require(revision > 0) { "expected_revision must be a positive integer from list_bots." }
            CoreToolResult.success(service.outcome(service.assign(id, revision, request, description, source, token)).toString())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: IllegalArgumentException) { CoreToolResult.failure(invalid.message ?: "Invalid mascot scene. The existing visual was kept.") }
        catch (_: RuntimeException) { CoreToolResult.failure("Could not finish local mascot authoring. Inspect list_bots, then retry the same request_id; do not recreate the bot.") }
    }
    companion object {
        const val NAME = "compile_bot_mascot"
        internal val FIELDS = setOf("request_id", "bot_id", "expected_revision", "visual_description", "scene_json")
        private val modes = listOf("Idle", "Thinking", "Working", "Queued", "WaitingProvider", "WaitingUser", "Done", "Error", "Interrupted")
        internal val SCENE_EXAMPLE_JSON: String = buildString {
            append("""{"contract":"bot-mascot-v1","name":"Simple moving mascot","nodes":[{"kind":"rectangle","name":"Mascot","width":16,"height":16,"color":4294901760}],"animations":{""")
            val states = modes.map { it to false } + modes.map { it + "Reduced" to true }
            states.forEachIndexed { index, (state, reduced) ->
                if (index > 0) append(',')
                append('"').append(state).append("\":{\"duration\":1,\"loop\":").append(!reduced)
                    .append(",\"tracks\":[{\"node\":\"Mascot\",\"property\":\"x\",\"keys\":")
                    .append(if (reduced) "[[0,128]]" else "[[0,128],[1,129]]").append("}]}")
            }
            append("}}")
        }
        internal val SCENE_GUIDE = "Author original geometry and choreography, not a recolored fixed character. scene_json accepts either a JSON string or a JSON object. A complete minimal valid example is: " +
            SCENE_EXAMPLE_JSON + " Root fields are contract, name, nodes, and animations. Nodes are ordered: kind is group/ellipse/rectangle, name is unique, parent is optional and must name an earlier node (default Artboard), and x/y/rotation/scaleX/scaleY are optional. Shapes require width and height from 1 through 256 and an unsigned ARGB integer color; rectangles may include radius. " +
            "animations must contain exactly Idle, Thinking, Working, Queued, WaitingProvider, WaitingUser, Done, Error, Interrupted, plus IdleReduced, ThinkingReduced, WorkingReduced, QueuedReduced, WaitingProviderReduced, WaitingUserReduced, DoneReduced, ErrorReduced, InterruptedReduced. Each animation has duration (an integer from 1 through 600 frames at 60 fps), loop (boolean), and tracks. Each track is {node,property,keys:[[frame,value],...]}; property is x/y/rotation/scaleX/scaleY. Frames must be integers, increase strictly, and every track must start at frame 0. All 18 animations must use the same (node, property) target set. Each normal mode must contain at least one genuinely moving Float32 track. Every Reduced variant must have loop=false and exactly one key per track. Names are opaque labels. Maximum source is 128 KiB UTF-8, with 96 nodes, hierarchy depth 8, 64 tracks per state, 64 keys per track, and 64 KiB compiled output. No URLs, paths, images, fonts, scripts, audio, code, or external resources."
        private val SPEC by lazy {
            fun text(description: String, max: Int) = mapOf("type" to "string", "description" to description, "minLength" to 1, "maxLength" to max)
            val scene = linkedMapOf<String, Any>(
                "description" to SCENE_GUIDE,
                "anyOf" to listOf(
                    mapOf("type" to "string", "maxLength" to BotMascotSceneCompiler.MAX_SOURCE_BYTES),
                    mapOf("type" to "object"),
                ),
            )
            val p = linkedMapOf<String, Any>(
                "request_id" to text("Stable unique operation key. Keep identical on retries for the same scene and bot.",80),
                "bot_id" to text("Exact existing enabled bot ID from list_bots, including built-in templates.",256),
                "expected_revision" to mapOf("type" to "integer","minimum" to 1,"description" to "Current definition revision from list_bots; stale writes cannot overwrite newer edits."),
                "visual_description" to text("Short plain-text design and motion description requested by the user, separate from operational instructions.",1200),
                "scene_json" to scene,
            )
            ToolSpec(NAME,"jarvys/bots","Compile and save an original animated mascot for any existing bot, including built-in Coding and Android-use, locally on the phone, only when requested by the user. " +
                "Original editable scene and immutable versioned bytes are saved with an idempotent receipt. No image service, CLI or new network service is used. " +
                "LOCAL_COMPILED proves local compilation, not Android rendering acceptance. A temporary visual test is available in the saved bot editor; normal avatars remain the static PNG/glyph fallback. Never claim playback is verified from compilation alone. " +
                "Read list_bots first; never guess IDs. This changes presentation only, not prompts, tools, permissions or running tasks. Main chat only.",
                "bots",ToolSpec.Status.IMPLEMENTED,emptyMap(),FIELDS.toList(),mapOf("type" to "object","properties" to p,"required" to FIELDS.toList(),"additionalProperties" to false))
        }
        @JvmStatic fun isAvailable(context: Context?, depth: Int, sessionId: String?) = BotCatalogTool.isAvailable(context,depth,sessionId)
    }
}

internal class BotMascotService(
    private val repository: CrewProfileRepository,
    private val store: BotMascotStore,
    private val sessionId: String,
) {
    constructor(context: Context, sessionId: String): this(CrewProfileRepository(context),BotMascotStore(context),sessionId)
    fun available(token: CancellationToken) = !token.isCrewRun && sessionId.isNotBlank()

    fun assign(id: String, revision: Int, request: String, description: String, source: String,
               token: CancellationToken): CrewProfileRepository.MascotAssignment {
        token.throwIfCancelled()
        require(available(token)) { "Mascot authoring is available only in the main chat." }
        BotMascotDescriptor.requireOperationId(request)
        require(id.matches(Regex("[a-z][a-z0-9._-]*")) && revision > 0) { "Choose an existing bot and its current revision." }
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
        // Validate the full product source before disk writes, but never regenerate on a durable retry.
        BotMascotSceneCompiler.compileProduct(bytes)
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
                token.throwIfCancelled()
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
        require(bot.enabled) { "Enable the bot before creating its mascot." }
        require(bot.revision==revision) { "The bot changed while editing. Read list_bots before retrying this operation." }
    }
    fun outcome(result: CrewProfileRepository.MascotAssignment) = JSONObject()
        .put("bot_id",result.definition.id).put("revision",result.definition.revision).put("saved",true)
        .put("already_existed",result.alreadyExisted).put("assigned_revision",result.receipt.assignedRevision)
        .put("mascot_status","LOCAL_COMPILED").put("mascot_compiled",true)
        .put("asset_current",result.definition.mascot==result.receipt.mascot).put("source_hash",result.receipt.mascot.sourceHash)
        .put("asset_hash",result.receipt.mascot.assetHash).put("contract",result.receipt.mascot.contract)
        .put("android_playback_verified",false).put("playback","static_default_manual_visual_test")
        .put("next_step","Inspect list_bots. The scene is compiled and saved; this does not establish Android playback acceptance. Do not recreate the bot.")
    companion object {
        internal fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
    }
}
