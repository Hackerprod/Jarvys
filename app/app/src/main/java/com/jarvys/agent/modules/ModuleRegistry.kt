package com.jarvys.agent.modules

import android.content.Context
import android.content.res.AssetManager
import com.jarvys.agent.CoreToolRegistry
import com.jarvys.agent.connectors.ConnectorConnectionFlow
import com.jarvys.agent.connectors.ConnectorDefinition
import com.jarvys.agent.connectors.ConnectorInvocationContext
import com.jarvys.agent.connectors.ConnectorOperation
import com.jarvys.agent.connectors.ConnectorPresentationGroup
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.connectors.ConnectorRuntime
import com.jarvys.agent.connectors.ConnectorWritePreparation
import com.jarvys.agent.connectors.ActionPolicyDefaults
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.CoreTool
import com.jarvys.agent.ModuleTool
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

fun interface ModuleManifestLoader {
    fun loadManifests(): List<String>
}

internal data class ModuleManifestSource(val assetDirectory: String, val manifestJson: String)

/** Registry for the module manifests bundled under app/src/main/assets/modules. */
class ModuleRegistry private constructor(manifests: List<ModuleManifest>) {
    private val byId: Map<String, ModuleManifest>
    private val byToolName: Map<String, Pair<ModuleManifest, ModuleToolDeclaration>>
    private val artifactDeclarations: Map<String, Pair<ModuleManifest, ModuleArtifactType>>

    init {
        require(manifests.map { it.id }.distinct().size == manifests.size) { "Module ids must be unique" }
        val declaredTools = manifests.flatMap { manifest -> manifest.tools.map { manifest to it } }
        require(declaredTools.map { it.second.name }.distinct().size == declaredTools.size) {
            "Module tool names must be unique across bundled modules"
        }
        val declarations = manifests.flatMap { manifest ->
            manifest.artifactTypes.map { artifact -> canonicalType(artifact.type) to (manifest to artifact) }
        }
        require(declarations.map { it.first }.distinct().size == declarations.size) {
            "Artifact type declarations must be unique across bundled modules"
        }
        byId = manifests.associateBy { it.id }
        byToolName = declaredTools.associate { (manifest, tool) -> tool.name to (manifest to tool) }
        artifactDeclarations = declarations.toMap()
    }

    fun modules(): List<ModuleManifest> = byId.values.toList()

    fun module(id: String): ModuleManifest? = byId[id]

    fun moduleForTool(toolName: String): ModuleManifest? = byToolName[toolName]?.first

    fun declaredTool(toolName: String): ModuleToolDeclaration? = byToolName[toolName]?.second

    /** Exact MIME declarations win, then a type wildcard, then the universal wildcard. */
    fun artifactModuleForType(type: String): ModuleManifest? {
        return artifactDeclarationForType(type)?.first
    }

    /** Resolves the declared viewer through the same exact/wildcard rules as its module. */
    fun artifactViewerForType(type: String): String? = artifactDeclarationForType(type)?.second?.viewer

    private fun artifactDeclarationForType(type: String): Pair<ModuleManifest, ModuleArtifactType>? {
        val canonical = canonicalType(type)
        if (!ARTIFACT_TYPE_PATTERN.matches(canonical)) return null
        artifactDeclarations[canonical]?.let { return it }
        val major = canonical.substringBefore('/', "")
        if (major.isNotEmpty() && major != "*") artifactDeclarations["$major/*"]?.let { return it }
        return artifactDeclarations["*/*"]
    }

    fun moduleIdForArtifactType(type: String): String? = artifactModuleForType(type)?.id

    /** Registers module-backed operations with the shared execution and autonomy registry. */
    fun installConnectorDefinitions(
        connectorRegistry: ConnectorRegistry,
        runtime: ModuleRuntime,
    ): List<ConnectorDefinition> {
        val definitions = modules().map { manifest ->
            ActionPolicyDefaults.register(manifest.id, manifest.tools.map { it.name })
            connectorDefinition(manifest, runtime)
        }
        definitions.forEach { definition ->
            val existing = connectorRegistry.get(definition.id)
            if (existing == null) connectorRegistry.register(definition)
            else require(isCompatible(existing, definition)) {
                "A different connector already uses module id '${definition.id}'"
            }
        }
        return definitions.mapNotNull { connectorRegistry.get(it.id) }
    }

    /** Connects no-permission bundled modules only before the user has explicitly chosen connection state. */
    fun installAndConnectUnselected(
        connectorRegistry: ConnectorRegistry,
        runtime: ModuleRuntime,
    ): List<ConnectorDefinition> = installConnectorDefinitions(connectorRegistry, runtime).also { definitions ->
        definitions.forEach { definition ->
            if (connectorRegistry.state(definition) == com.jarvys.agent.connectors.ConnectorState.DISCONNECTED &&
                definition.readPermissions.isEmpty() && definition.connectionPermissions.isEmpty()) {
                connectorRegistry.connectIfUnselected(definition.id)
            }
        }
    }

    /** Creates the sole CoreTool adapter for each available bundled module declaration. */
    fun tools(
        connectorRegistry: ConnectorRegistry,
        runtime: ModuleRuntime,
        conversationId: String? = null,
    ): List<ModuleTool> {
        val definitions = installConnectorDefinitions(connectorRegistry, runtime).associateBy { it.id }
        return modules().flatMap { manifest ->
            val definition = definitions[manifest.id] ?: return@flatMap emptyList()
            if (connectorRegistry.state(definition) != com.jarvys.agent.connectors.ConnectorState.CONNECTED) {
                return@flatMap emptyList()
            }
            manifest.tools.mapNotNull { tool ->
                val operation = definition.operations.firstOrNull { it.name == tool.name } ?: return@mapNotNull null
                ModuleTool(manifest, tool, connectorRegistry, definition, operation, conversationId)
            }
        }
    }

    private fun connectorDefinition(manifest: ModuleManifest, runtime: ModuleRuntime): ConnectorDefinition {
        val connectorRuntime = ModuleConnectorRuntime(manifest, runtime)
        val operations = manifest.tools.map { tool ->
            ConnectorOperation(
                name = tool.name,
                description = tool.description,
                inputSchema = JSONObject(tool.inputSchema),
                write = tool.access == ModuleToolAccess.WRITE,
                displayLabel = CoreToolRegistry.humanizeToolName(tool.name),
            )
        }
        return ConnectorDefinition(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            description = "Internal module",
            capabilities = manifest.capabilities.toList(),
            operations = operations,
            runtime = connectorRuntime,
            permissionLabel = manifest.name,
            connectionFlow = ConnectorConnectionFlow.PERMISSIONS,
            connectionAccessGranted = { true },
            connectionPermissions = emptyList(),
            presentationGroup = ConnectorPresentationGroup.ON_DEVICE,
            operationAccessGranted = { true },
            usesDedicatedToolAdapter = true,
        )
    }

    private fun isCompatible(existing: ConnectorDefinition, requested: ConnectorDefinition): Boolean =
        existing.name == requested.name && existing.version == requested.version &&
            existing.operations.map { it.name to it.inputSchema.toString() } ==
            requested.operations.map { it.name to it.inputSchema.toString() } &&
            existing.usesDedicatedToolAdapter == requested.usesDedicatedToolAdapter &&
            existing.runtime is ModuleConnectorRuntime

    companion object {
        const val BUNDLED_ASSET_ROOT = "modules"
        const val MANIFEST_FILE = "manifest.json"
        private val ARTIFACT_TYPE_PATTERN = Regex("(?:\\*|[a-z0-9][a-z0-9!#$&^_.+-]*)/(?:\\*|[a-z0-9][a-z0-9!#$&^_.+-]*)")
        @Volatile private var bundledInstance: ModuleRegistry? = null

        /** Stable application registry used by artifact cards when persisted module metadata is absent. */
        @JvmStatic fun get(context: Context): ModuleRegistry = bundledInstance ?: synchronized(this) {
            bundledInstance ?: fromAssets(context).also { bundledInstance = it }
        }

        /** Returns the cached registry when startup has already loaded bundled manifests. */
        @JvmStatic fun currentOrNull(): ModuleRegistry? = bundledInstance

        fun load(loader: ModuleManifestLoader): ModuleRegistry = create(loader.loadManifests())

        fun fromManifests(manifestJson: List<String>): ModuleRegistry = create(manifestJson)

        fun fromAssets(context: Context, assetRoot: String = BUNDLED_ASSET_ROOT): ModuleRegistry {
            val assets = context.applicationContext.assets
            return fromBundleManifests(findManifestAssets(assets, assetRoot))
        }

        /** AssetManager implementations may list nested files directly instead of listing directories. */
        private fun findManifestAssets(assets: AssetManager, root: String): List<ModuleManifestSource> {
            val sources = linkedMapOf<String, ModuleManifestSource>()
            val visited = mutableSetOf<String>()

            fun addManifest(manifestPath: String, moduleDirectory: String) {
                if (moduleDirectory in sources) return
                val json = assets.open(manifestPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
                sources[moduleDirectory] = ModuleManifestSource(moduleDirectory, json)
            }

            fun visit(directory: String) {
                if (!visited.add(directory)) return
                val entries = runCatching { assets.list(directory).orEmpty().toList() }
                    .getOrDefault(emptyList()).sorted()
                if (MANIFEST_FILE in entries) {
                    addManifest("$directory/$MANIFEST_FILE", directory)
                    return
                }
                entries.forEach { rawEntry ->
                    val entry = rawEntry.removePrefix("$directory/")
                    when {
                        entry == MANIFEST_FILE -> addManifest("$directory/$entry", directory)
                        entry.endsWith("/$MANIFEST_FILE") -> {
                            val manifestPath = "$directory/$entry"
                            addManifest(manifestPath, manifestPath.removeSuffix("/$MANIFEST_FILE"))
                        }
                        entry.isNotBlank() && entry != "." && entry != ".." -> visit("$directory/$entry")
                    }
                }
            }

            visit(root)
            return sources.values.toList()
        }

        internal fun fromBundleManifests(sources: List<ModuleManifestSource>): ModuleRegistry =
            ModuleRegistry(sources.map { ModuleManifestParser.parse(it.manifestJson, it.assetDirectory) })

        private fun create(json: List<String>): ModuleRegistry = ModuleRegistry(json.map(ModuleManifestParser::parse))

        private fun canonicalType(type: String): String = type.substringBefore(';').trim().lowercase(Locale.ROOT)
    }
}

/** The registered generic connector runtime forwards validated calls to the injected module host. */
private class ModuleConnectorRuntime(
    private val manifest: ModuleManifest,
    private val moduleRuntime: ModuleRuntime,
) : ConnectorRuntime {
    override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
    override fun disconnect() = Unit

    override fun prepareWrite(
        operation: String,
        arguments: JSONObject,
        token: CancellationToken,
    ): ConnectorWritePreparation {
        val tool = manifest.tools.firstOrNull { it.name == operation }
            ?: throw IllegalArgumentException("Module operation is no longer declared")
        return ConnectorWritePreparation(
            ApprovalSummary(
                title = "${manifest.name} · ${CoreToolRegistry.humanizeToolName(tool.name)}",
                lines = listOf(tool.description, arguments.toString()),
                allowAlwaysAvailable = true,
                autonomyConnectorId = manifest.id,
                autonomyOperationName = tool.name,
                compactSummary = com.jarvys.agent.connectors.ConnectorUiText(fallback = tool.description),
            ),
            JSONObject(arguments.toString()),
        )
    }

    override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken): JSONObject =
        execute(operation, arguments, token, ConnectorInvocationContext())

    override fun invokePreparedWithContext(
        operation: String,
        arguments: JSONObject,
        preparation: ConnectorWritePreparation,
        token: CancellationToken,
        context: ConnectorInvocationContext,
    ): JSONObject = execute(operation, arguments, token, context)

    override fun invokeReadPreparedWithContext(
        operation: String,
        arguments: JSONObject,
        preparation: ConnectorWritePreparation,
        token: CancellationToken,
        context: ConnectorInvocationContext,
    ): JSONObject = execute(operation, arguments, token, context)

    private fun execute(
        operation: String,
        arguments: JSONObject,
        token: CancellationToken,
        context: ConnectorInvocationContext,
    ): JSONObject {
        token.throwIfCancelled()
        val tool = manifest.tools.firstOrNull { it.name == operation }
            ?: throw IllegalArgumentException("Module operation is no longer declared")
        val request = ModuleExecutionRequest(
            module = manifest,
            tool = tool,
            arguments = jsonObjectToMap(arguments),
            conversationId = context.conversationId,
            requester = context.requester,
            requesterColorKey = context.requesterColorKey,
        )
        val progress = context.progress?.let { listener -> CoreTool.ProgressListener { message -> listener(message) } }
        val result = moduleRuntime.execute(request, token, progress)
        return JSONObject()
            .put(MODULE_TOOL_RESULT_KEY, JSONObject()
                .put("success", result.success)
                .put("content", result.content)
                .put("completeContentRequired", result.completeContentRequired)
                .put("previewId", result.previewId)
                .put("finishRun", result.finishRun))
    }

    private fun jsonObjectToMap(value: JSONObject): Map<String, Any?> = buildMap {
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, jsonValue(value.opt(key)))
        }
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> (0 until value.length()).map { jsonValue(value.opt(it)) }
        else -> value
    }

}

internal const val MODULE_TOOL_RESULT_KEY = "module_tool_result"
