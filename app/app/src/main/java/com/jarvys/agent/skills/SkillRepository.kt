package com.jarvys.agent.skills

import android.content.Context
import com.jarvys.agent.ToolRegistry
import com.jarvys.agent.WorkspaceStore
import com.jarvys.agent.WorkspaceTools
import com.jarvys.agent.mcp.McpServerRepository
import com.jarvys.agent.mcp.McpServerToolRegistry
import com.jarvys.agent.connectors.ConnectorRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

enum class SkillSource { BUNDLED, IMPORTED }

data class SkillEntry(
    val metadata: SkillMetadata,
    val body: String,
    val source: SkillSource,
    val enabled: Boolean,
    val usageCount: Int,
    val validationError: String? = null,
)

/** Loads first-party assets and app-private Markdown skills; no remote fetch or code execution. */
class SkillRepository private constructor(context: Context) : WorkspaceStore.SkillWorkspaceObserver {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val skillRoot = File(appContext.filesDir, SKILLS_DIRECTORY)
    private val mcpServers = McpServerRepository.get(appContext)
    private val connectors = ConnectorRegistry.get(appContext)
    private val lock = Any()
    init {
        BundledSkillSeeder(skillRoot).seedSkillCreator {
            appContext.assets.open("$ASSET_ROOT/$BUNDLED_SKILL_CREATOR_ID/$SKILL_FILE")
                .use(::readBoundedUtf8)
        }
    }

    private val _skills = MutableStateFlow(loadAll())
    val skills: StateFlow<List<SkillEntry>> = _skills.asStateFlow()

    fun refresh() = synchronized(lock) {
        _skills.value = loadAll()
    }

    fun enabledCount(): Int = skills.value.count { it.enabled }

    fun totalCount(): Int = skills.value.size

    fun enabledForRun(): List<SkillEntry> = skills.value.filter { it.enabled && it.validationError == null }

    fun selectedForRun(ids: Collection<String>): List<SkillEntry> {
        if (ids.isEmpty()) return emptyList()
        require(ids.distinct().size <= MAX_SKILLS_PER_RUN) { "Select at most $MAX_SKILLS_PER_RUN skills per run" }
        val available = enabledForRun().associateBy { it.metadata.id }
        val missing = ids.distinct().filterNot { it in available }
        require(missing.isEmpty()) { "Selected skills are disabled or invalid: ${missing.joinToString(", ")}" }
        return ids.distinct().map { available.getValue(it) }
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        val current = _skills.value.firstOrNull { it.metadata.id == id } ?: return@synchronized
        require(current.validationError == null || !enabled) { current.validationError ?: "Skill is invalid" }
        preferences.edit().putBoolean(enabledKey(id), enabled).apply()
        _skills.value = loadAll()
    }

    fun markUsed(ids: Collection<String>) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        val editor = preferences.edit()
        ids.distinct().forEach { id ->
            if (_skills.value.any { it.metadata.id == id && it.enabled && it.validationError == null }) {
                editor.putInt(usageKey(id), preferences.getInt(usageKey(id), 0) + 1)
            }
        }
        editor.apply()
        _skills.value = loadAll()
    }

    fun importMarkdown(markdown: String): SkillEntry = synchronized(lock) {
        val parsed = SkillMarkdownParser.parse(markdown)
        require(_skills.value.none { it.metadata.id == parsed.metadata.id }) {
            "A skill with id '${parsed.metadata.id}' already exists"
        }
        val validation = validateAllowedTools(parsed.metadata.allowedTools)
        require(validation == null) { validation ?: "Skill references unknown tools" }
        val directory = safeSkillDirectory(parsed.metadata.id)
        require(!directory.exists()) { "Skill storage already exists for id '${parsed.metadata.id}'" }
        check(directory.mkdirs()) { "Could not create private skill storage" }
        val target = File(directory, SKILL_FILE)
        val temporary = File(directory, "$SKILL_FILE.tmp")
        try {
            temporary.writeText(markdown, StandardCharsets.UTF_8)
            check(temporary.renameTo(target)) { "Could not finish storing imported skill" }
        } catch (error: Exception) {
            temporary.delete()
            directory.deleteRecursively()
            throw error
        }
        preferences.edit().putBoolean(enabledKey(parsed.metadata.id), true).putInt(usageKey(parsed.metadata.id), 0).apply()
        val imported = loadAll().also { updated -> _skills.value = updated }
            .first { it.metadata.id == parsed.metadata.id }
        com.jarvys.agent.MemorySearchIndexCoordinator.notifySkillsChanged(appContext)
        imported
    }

    override fun commitSkillWorkspaceWrite(skillId: String, skillMarkdown: String?, writeFile: Runnable) = synchronized(lock) {
        require(SKILL_ID_PATTERN.matches(skillId)) { "Skill directory must use a valid skill id" }
        SkillWorkspaceMutation.commit(
            skillMarkdown = skillMarkdown,
            validateMarkdown = {
                if (skillMarkdown != null) {
                    val parsed = SkillMarkdownParser.parse(skillMarkdown)
                    require(parsed.metadata.id == skillId) {
                        "SKILL.md id '${parsed.metadata.id}' must match its directory '$skillId'"
                    }
                    val validation = validateAllowedTools(parsed.metadata.allowedTools)
                    require(validation == null) { validation ?: "Skill references unknown tools" }
                }
            },
            writeFile = { writeFile.run() },
            initializeEnabledState = {
                if (skillMarkdown != null && !preferences.contains(enabledKey(skillId))) {
                    val source = if (isBundledId(skillId)) SkillSource.BUNDLED else SkillSource.IMPORTED
                    preferences.edit().putBoolean(enabledKey(skillId), skillEnabledByDefault(source, skillId)).apply()
                }
            },
            rescan = { _skills.value = loadAll() },
        )
        com.jarvys.agent.MemorySearchIndexCoordinator.notifySkillsChanged(appContext)
    }

    fun readWorkspaceSkillFile(skillId: String, relativePath: String): String = synchronized(lock) {
        require(SKILL_ID_PATTERN.matches(skillId)) { "Skill id is invalid" }
        require(relativePath.isNotBlank() && !relativePath.startsWith('/') && '\\' !in relativePath
                && '\u0000' !in relativePath && relativePath.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Skill file path is invalid"
        }
        val directory = safeSkillDirectory(skillId)
        val file = File(directory, relativePath).canonicalFile
        require(file.path.startsWith(directory.canonicalPath + File.separator)) { "Skill file path escapes its directory" }
        require(file.isFile) { "Skill file does not exist" }
        readBoundedUtf8(file.inputStream())
    }

    fun deleteImported(id: String) = synchronized(lock) {
        val skill = _skills.value.firstOrNull { it.metadata.id == id } ?: return@synchronized
        require(skill.source == SkillSource.IMPORTED) { "Bundled skills cannot be deleted" }
        val directory = safeSkillDirectory(id)
        if (directory.exists()) check(directory.deleteRecursively()) { "Could not remove imported skill files" }
        preferences.edit().remove(enabledKey(id)).remove(usageKey(id)).apply()
        _skills.value = loadAll()
        com.jarvys.agent.MemorySearchIndexCoordinator.notifySkillsChanged(appContext)
    }

    private fun loadAll(): List<SkillEntry> {
        val bundled = loadBundled()
        val ids = bundled.mapTo(mutableSetOf()) { it.metadata.id }
        val imported = loadImported().filter { ids.add(it.metadata.id) }
        return (bundled + imported).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.metadata.name })
    }

    private fun loadBundled(): List<SkillEntry> {
        val directories = runCatching { appContext.assets.list(ASSET_ROOT).orEmpty() }.getOrDefault(emptyArray())
        val fromAssets = directories.sorted().filterNot { it == BUNDLED_SKILL_CREATOR_ID }.mapNotNull { directory ->
            val assetPath = "$ASSET_ROOT/$directory/$SKILL_FILE"
            runCatching {
                val override = File(skillRoot, "$directory/$SKILL_FILE")
                val markdown = if (override.isFile) readBoundedUtf8(override.inputStream())
                    else appContext.assets.open(assetPath).use(::readBoundedUtf8)
                val parsed = SkillMarkdownParser.parse(markdown)
                require(directory == parsed.metadata.id) { "Bundled skill directory must match its frontmatter id" }
                toEntry(parsed, SkillSource.BUNDLED)
            }.getOrNull()
        }
        val creator = runCatching {
            val markdownFile = File(skillRoot, "$BUNDLED_SKILL_CREATOR_ID/$SKILL_FILE")
            val parsed = SkillMarkdownParser.parse(readBoundedUtf8(markdownFile.inputStream()))
            require(parsed.metadata.id == BUNDLED_SKILL_CREATOR_ID) { "Bundled skill storage does not match its frontmatter id" }
            toEntry(parsed, SkillSource.BUNDLED)
        }.getOrNull()
        return if (creator == null) fromAssets else fromAssets + creator
    }

    private fun loadImported(): List<SkillEntry> {
        val directories = skillRoot.listFiles { file -> file.isDirectory } ?: return emptyList()
        return directories.sortedBy { it.name }.mapNotNull { directory ->
            runCatching {
                val safeDirectory = safeSkillDirectory(directory.name)
                val file = File(safeDirectory, SKILL_FILE)
                require(file.isFile) { "Imported skill directory is missing $SKILL_FILE" }
                val parsed = SkillMarkdownParser.parse(readBoundedUtf8(file.inputStream()))
                require(directory.name == parsed.metadata.id) { "Skill storage directory does not match frontmatter id" }
                toEntry(parsed, SkillSource.IMPORTED)
            }.getOrNull()
        }
    }

    private fun toEntry(parsed: ParsedSkill, source: SkillSource): SkillEntry {
        val id = parsed.metadata.id
        return SkillEntry(
            metadata = parsed.metadata,
            body = parsed.body,
            source = source,
            enabled = preferences.getBoolean(enabledKey(id), skillEnabledByDefault(source, id)),
            usageCount = preferences.getInt(usageKey(id), 0).coerceAtLeast(0),
            validationError = validateAllowedTools(parsed.metadata.allowedTools),
        )
    }

    private fun validateAllowedTools(requested: List<String>): String? {
        if (requested.isEmpty()) return null
        val available = buildSet {
            addAll(ToolRegistry().names())
            addAll(WorkspaceTools.acceptedNames())
            add("read_skill")
            addAll(McpServerToolRegistry(mcpServers).all().map { it.modelName })
            connectors.definitions.value.forEach { definition ->
                definition.operations.forEach { operation ->
                    add(com.jarvys.agent.CoreConnectorTool.toolName(definition.id, operation.name))
                }
            }
        }
        val groups = buildSet {
            mcpServers.servers.value.forEach { add("mcp_server:${it.id}") }
            connectors.definitions.value.forEach { add("connector:${it.id}") }
        }
        return AllowedToolPolicy.validate(requested, available, groups)
    }

    private fun safeSkillDirectory(id: String): File {
        require(SKILL_ID_PATTERN.matches(id)) { "Skill id is not a safe storage name" }
        val rootCanonical = skillRoot.canonicalFile
        val expected = File(rootCanonical, id)
        val target = expected.canonicalFile
        require(target.path == expected.path) { "Skill directory cannot be a symbolic link" }
        require(target.path.startsWith(rootCanonical.path + File.separator)) { "Skill id escaped the private skill directory" }
        return target
    }

    private fun isBundledId(id: String): Boolean = id == BUNDLED_SKILL_CREATOR_ID
            || appContext.assets.list(ASSET_ROOT)?.contains(id) == true

    private fun readBoundedUtf8(input: InputStream): String {
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                require(output.size() + read <= SkillMarkdownParser.MAX_DOCUMENT_BYTES) { "Skill Markdown exceeds 256 KiB" }
                output.write(buffer, 0, read)
            }
            return output.toString("UTF-8")
        }
    }

    private fun enabledKey(id: String) = "enabled:$id"
    private fun usageKey(id: String) = "used:$id"

    companion object {
        private const val PREFS_NAME = "jarvys_skill_settings"
        private const val SKILLS_DIRECTORY = "skills"
        private const val ASSET_ROOT = "skills"
        private const val SKILL_FILE = "SKILL.md"
        private const val MAX_SKILLS_PER_RUN = 8
        private val SKILL_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        @Volatile private var instance: SkillRepository? = null

        fun get(context: Context): SkillRepository = instance ?: synchronized(this) {
            instance ?: SkillRepository(context).also { instance = it }
        }
    }
}
