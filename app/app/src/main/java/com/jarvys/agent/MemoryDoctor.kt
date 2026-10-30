package com.jarvys.agent

import android.content.Context
import android.database.Cursor
import android.system.Os
import android.system.OsConstants
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.workspaces.WorkspaceRegistry
import java.io.File
import java.util.Collections
import java.util.UUID

/** Known inability to observe a source is never an empty/healthy source. */
class MemoryDoctorUnavailable(val reasonCode: String) : IllegalStateException(reasonCode)
internal class MemoryDoctorCloseUnconfirmed(cause: Throwable) : IllegalStateException("doctor_close_unconfirmed", cause)
data class MemoryDoctorOptions(val project: String? = null, val check: String? = null) {
    internal fun normalized(): MemoryDoctorOptions {
        require(check == null || check in MemoryDoctorChecks.ids) { "doctor_check_unknown" }
        return copy(project = project?.takeIf { MemoryObservationContent.trimSpace(it).isNotEmpty() }?.let(MemoryNamespace::normalizeProject))
    }
}
object MemoryDoctorChecks {
    @JvmField val ids: List<String> = Collections.unmodifiableList(listOf(
        "ambiguous_active_runtime_sessions", "invalid_session_identity", "manual_session_name_project_mismatch",
        "orphaned_observation_session", "orphaned_pending_relations", "session_project_directory_mismatch",
        "sqlite_lock_contention", "sync_mutation_required_fields", "sync_target_closed_space", "unowned_session_project"))
}
data class MemoryDoctorSummary(val total: Int, val ok: Int, val warnings: Int, val blocked: Int, val errors: Int,
    val complete: Int, val partial: Int, val notApplicable: Int, val unavailable: Int, val domainPending: Int)
class MemoryDoctorCheck(val checkId: String, val result: String?, val coverage: String, val reasonCode: String,
    val message: String, evidence: Map<String, String> = emptyMap(), findings: List<Map<String, String>> = emptyList(),
    val safeNextStep: String = "Review the described scope; no repair was performed.", val requiresConfirmation: Boolean = false) {
    val evidence: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(evidence))
    val findings: List<Map<String, String>> = Collections.unmodifiableList(findings.map { Collections.unmodifiableMap(LinkedHashMap(it)) })
}
class MemoryDoctorReport(val status: String, val selectedProject: String?, val coverageComplete: Boolean,
    val summary: MemoryDoctorSummary, checks: List<MemoryDoctorCheck>) {
    val checks: List<MemoryDoctorCheck> = Collections.unmodifiableList(ArrayList(checks))
}

/** Fixed credential-protected layout from metadata only; Context directory getters can create paths. */
internal object MemoryDoctorPaths {
    fun root(context: Context): File {
        val app = context.applicationContext
        if (app.isDeviceProtectedStorage) throw MemoryDoctorUnavailable("unsupported_storage_layout")
        val path = app.applicationInfo.dataDir
        if (path.isNullOrBlank() || !File(path).isAbsolute || path.indexOf('\u0000') >= 0)
            throw MemoryDoctorUnavailable("storage_metadata_unavailable")
        return File(path)
    }
    fun registryDirectories(context: Context): List<File> = listOf(File(root(context), "databases"))
    fun memoryDirectories(context: Context): List<File> {
        val files = File(root(context), "files")
        val jarvys = File(files, "jarvys")
        return listOf(files, jarvys, File(jarvys, StorageGeneration.NAME))
    }
}

/** Lstat identity only. No creator, bootstrap, checkpoint, or configurable path. */
internal class MemoryDoctorFileIdentity private constructor(val file: File, private val directories: List<DirectoryIdentity>,
    private val device: Long, private val inode: Long) {
    private data class DirectoryIdentity(val file: File, val device: Long, val inode: Long)
    fun requireCurrent() {
        val current = capture(file, directories.map { it.file }, "source_changed")
        if (current.directories != directories || current.device != device || current.inode != inode)
            throw MemoryDoctorUnavailable("source_changed")
    }
    companion object {
        fun capture(file: File, localDirectories: List<File>, missing: String): MemoryDoctorFileIdentity {
            fun stat(path: File): android.system.StructStat = try { Os.lstat(path.absolutePath) }
            catch (failure: android.system.ErrnoException) {
                if (failure.errno == OsConstants.ENOENT) throw MemoryDoctorUnavailable(missing)
                throw failure
            }
            // Fixed local descendants only: Android's trusted dataDir ancestors may themselves be aliases.
            val directories = localDirectories.map { directory ->
                val value = stat(directory)
                check(OsConstants.S_ISDIR(value.st_mode)) { "doctor_path_invalid" }
                DirectoryIdentity(directory.absoluteFile, value.st_dev, value.st_ino)
            }
            check(directories.isNotEmpty() && directories.last().file == file.absoluteFile.parentFile) { "doctor_path_invalid" }
            val main = stat(file)
            check(OsConstants.S_ISREG(main.st_mode)) { "doctor_path_invalid" }
            if (main.st_size == 0L) throw MemoryDoctorUnavailable(missing)
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                try { check(OsConstants.S_ISREG(Os.lstat(file.absolutePath + suffix).st_mode)) { "doctor_sidecar_invalid" } }
                catch (failure: android.system.ErrnoException) { if (failure.errno != OsConstants.ENOENT) throw failure }
            }
            return MemoryDoctorFileIdentity(file.absoluteFile, directories, main.st_dev, main.st_ino)
        }
    }
}

internal object MemoryDoctorReader {
    private fun Cursor.text(index: Int): String {
        check(getType(index) == Cursor.FIELD_TYPE_STRING) { "doctor_metadata_type_invalid" }
        return getString(index)
    }
    private fun Cursor.integer(index: Int): Long {
        check(getType(index) == Cursor.FIELD_TYPE_INTEGER) { "doctor_metadata_type_invalid" }
        return getLong(index)
    }
    private fun version(sql: ChatSqlDatabase): Long = sql.query("PRAGMA data_version").use { c ->
        check(c.moveToFirst()); c.integer(0)
    }
    private fun generation(sql: ChatSqlDatabase): String? = sql.query("SELECT value FROM storage_meta WHERE key=?",
        arrayOf(ConnectionProjectionRepository.GENERATION_KEY)).use { c ->
        if (!c.moveToFirst()) null else c.text(0).also { value ->
            check(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false) && !c.moveToNext()) { "doctor_generation_invalid" }
        }
    }
    private fun ids(options: MemoryDoctorOptions) = options.check?.let(::listOf) ?: MemoryDoctorChecks.ids
    private fun report(options: MemoryDoctorOptions, checks: List<MemoryDoctorCheck>): MemoryDoctorReport {
        fun result(value: String) = checks.count { it.result == value }
        fun coverage(value: String) = checks.count { it.coverage == value }
        val summary = MemoryDoctorSummary(checks.size, result("ok"), result("warning"), result("blocked"), result("error"),
            coverage("complete"), coverage("partial"), coverage("not_applicable"), coverage("unavailable"), coverage("domain_pending"))
        val complete = checks.all { it.coverage == "complete" }
        val status = when { summary.errors > 0 -> "error"; summary.blocked > 0 -> "blocked"; summary.warnings > 0 -> "warning"; !complete -> "incomplete"; else -> "ok" }
        return MemoryDoctorReport(status, options.project, complete, summary, checks.sortedBy { it.checkId })
    }
    fun unavailable(options: MemoryDoctorOptions, reason: String): MemoryDoctorReport = report(options, ids(options).map {
        MemoryDoctorCheck(it, null, "unavailable", reason, "The required source could not be observed; this check was not evaluated.")
    })

    fun projects(context: Context, token: CancellationToken): List<String> = WorkspaceRegistry.observeForDoctor(context, token) { registry ->
        var original: Set<String>? = null
        repeat(2) {
            token.throwIfCancelled()
            val before = registry.dataVersion()
            registry.validateSchema()
            val owners = registry.owners()
            if (original != null && owners != original) throw MemoryDoctorUnavailable("ownership_changed")
            original = owners
            val afterOwners = registry.owners()
            val after = registry.dataVersion()
            registry.requireCurrent()
            if (owners != afterOwners) throw MemoryDoctorUnavailable("ownership_changed")
            if (before == after) return@observeForDoctor Collections.unmodifiableList(owners.toList())
        }
        throw MemoryDoctorUnavailable("snapshot_unavailable")
    }

    fun execute(context: Context, options: MemoryDoctorOptions, token: CancellationToken): MemoryDoctorReport =
        WorkspaceRegistry.observeForDoctor(context, token) { registry ->
            JarvysChatDatabaseProvider.observeOpenForDoctor(context, token) { sql, requireCurrent ->
                var firstOwners: Set<String>? = null
                var firstGeneration: String? = null
                var capturedGeneration = false
                repeat(2) {
                    token.throwIfCancelled(); requireCurrent(); registry.requireCurrent()
                    val registryBefore = registry.dataVersion()
                    registry.validateSchema() // Inside the same-handle version window, not before its baseline.
                    val owners = registry.owners()
                    if (firstOwners != null && owners != firstOwners) throw MemoryDoctorUnavailable("ownership_changed")
                    firstOwners = owners
                    require(options.project == null || options.project in owners) { "memory_project_unknown" }
                    val memoryBefore = version(sql)
                    val currentGeneration = generation(sql)
                    if (capturedGeneration && currentGeneration != firstGeneration) throw MemoryDoctorUnavailable("source_changed")
                    firstGeneration = currentGeneration; capturedGeneration = true
                    val selected = options.project?.let(::setOf) ?: owners
                    val results = ids(options).map { id -> token.throwIfCancelled(); check(id, sql, selected, token) }
                    val finalGeneration = generation(sql)
                    val memoryAfter = version(sql)
                    val finalOwners = registry.owners()
                    val registryAfter = registry.dataVersion()
                    requireCurrent(); registry.requireCurrent(); token.throwIfCancelled()
                    if (owners != finalOwners) throw MemoryDoctorUnavailable("ownership_changed")
                    if (currentGeneration != finalGeneration) throw MemoryDoctorUnavailable("source_changed")
                    if (memoryBefore == memoryAfter && registryBefore == registryAfter) return@observeOpenForDoctor report(options, results)
                }
                throw MemoryDoctorUnavailable("snapshot_unavailable")
            }
        }

    private fun guard(projects: Set<String>, alias: String = ""): Pair<String, Array<Any?>> {
        val prefix = if (alias.isEmpty()) "" else "$alias."
        check(projects.isNotEmpty())
        return "${prefix}project IN (${projects.joinToString(",") { "?" }})" to projects.map { it as Any? }.toTypedArray()
    }
    private fun knownOwner(mode: String) = mode == "project_owned" || mode == "shared"
    private fun check(id: String, sql: ChatSqlDatabase, projects: Set<String>, token: CancellationToken): MemoryDoctorCheck = when (id) {
        "session_project_directory_mismatch", "manual_session_name_project_mismatch", "ambiguous_active_runtime_sessions" ->
            MemoryDoctorCheck(id, null, "not_applicable", "local_schema_has_no_equivalent", "The local session schema has no equivalent directory/name-based check.")
        "sync_mutation_required_fields", "sync_target_closed_space" ->
            MemoryDoctorCheck(id, null, "domain_pending", "sync_domain_pending", "The sync domain is not implemented; no empty healthy result is inferred.")
        "unowned_session_project" -> MemoryDoctorCheck(id, null, "unavailable", "unattributed_rows_not_authorized", "This scope does not grant global access to rows with broken ownership.")
        "invalid_session_identity" -> invalidSessions(sql, projects, token)
        "orphaned_observation_session" -> orphanSessions(sql, projects, token)
        "orphaned_pending_relations" -> pendingRelations(sql, projects, token)
        "sqlite_lock_contention" -> lockObservation(sql)
        else -> error("doctor_check_unknown")
    }
    private fun invalidSessions(sql: ChatSqlDatabase, projects: Set<String>, token: CancellationToken): MemoryDoctorCheck {
        val id = "invalid_session_identity"
        val (where, args) = guard(projects)
        val blank = ArrayList<Pair<String, String>>()
        var unknownOwner = false
        sql.query("SELECT id,project,ownership_mode FROM memory_sessions WHERE $where", args).use { c ->
            while (c.moveToNext()) {
                token.throwIfCancelled()
                val session = c.text(0); val project = c.text(1); val mode = c.text(2)
                if (!knownOwner(mode)) unknownOwner = true
                else if (MemoryObservationContent.trimSpace(session).isEmpty()) blank += session to project
            }
        }
        if (unknownOwner) return MemoryDoctorCheck(id, null, "unavailable", "session_attribution_invalid", "Session attribution is unavailable; no partial counts are returned.")
        var observations = 0L; var prompts = 0L
        for ((session, project) in blank) {
            token.throwIfCancelled()
            fun count(table: String, column: String): Long = sql.query("SELECT COUNT(*) FROM $table WHERE project=? AND $column=?", arrayOf(project, session)).use { c -> check(c.moveToFirst()); c.integer(0) }
            observations += count("memory_entries", "memory_session_id")
            prompts += count("memory_prompts", "session_id")
        }
        return MemoryDoctorCheck(id, if (blank.isEmpty()) "ok" else "blocked", "partial", "local_identity_only_sync_components_pending",
            "Only authorized local blank session identities and same-project references were evaluated; sync/quarantine components remain pending.",
            mapOf("invalid_sessions" to blank.size.toString(), "observation_references" to observations.toString(), "prompt_references" to prompts.toString()))
    }
    private fun orphanSessions(sql: ChatSqlDatabase, projects: Set<String>, token: CancellationToken): MemoryDoctorCheck {
        val id = "orphaned_observation_session"
        val (where, args) = guard(projects)
        val references = LinkedHashMap<Pair<String, String>, Pair<Long, Long>>()
        sql.query("SELECT project,memory_session_id,COUNT(*),MIN(created_at) FROM memory_entries WHERE $where AND memory_session_id IS NOT NULL GROUP BY project,memory_session_id ORDER BY project,memory_session_id", args).use { c ->
            while (c.moveToNext()) {
                token.throwIfCancelled(); val project = c.text(0); val session = c.text(1)
                // Primary SQL trim removes only TAB/LF/CR/SPACE; do not substitute Go TrimSpace.
                if (session.trim { it == '\t' || it == '\n' || it == '\r' || it == ' ' }.isNotEmpty()) {
                    references[project to session] = c.integer(2) to c.integer(3)
                }
            }
        }
        val missing = ArrayList<Map<String, String>>(); var count = 0L
        for ((key, counts) in references) {
            val (referencesCount, oldestCreatedAt) = counts
            token.throwIfCancelled(); val (project, session) = key
            val target = sql.query("SELECT project,ownership_mode FROM memory_sessions WHERE id=?", arrayOf(session)).use { c ->
                if (!c.moveToFirst()) 0 else {
                    val targetProject = c.text(0); val mode = c.text(1)
                    if (targetProject == project && targetProject in projects && knownOwner(mode)) 1 else 2
                }
            }
            if (target == 2) return MemoryDoctorCheck(id, null, "unavailable", "session_target_unattributed", "A referenced target cannot be attributed in this scope; no target details or partial counts are returned.")
            if (target == 0) {
                count += referencesCount
                missing += mapOf("project" to project, "session_id" to session, "observation_references" to referencesCount.toString(),
                    "oldest_created_at_epoch_ms" to oldestCreatedAt.toString())
            }
        }
        return MemoryDoctorCheck(id, if (count == 0L) "ok" else "warning", "complete", "authorized_retained_observations", "Authorized retained observations, including retired rows, were checked for truly absent session targets.",
            mapOf("orphaned_observations" to count.toString()), missing)
    }
    private fun pendingRelations(sql: ChatSqlDatabase, projects: Set<String>, token: CancellationToken): MemoryDoctorCheck {
        val id = "orphaned_pending_relations"
        val (aGuard, aArgs) = guard(projects, "a"); val (bGuard, bArgs) = guard(projects, "b")
        var candidates = 0L; var oneRetired = 0L; var live = 0L
        val samples = ArrayList<Map<String, String>>()
        sql.query("SELECT r.relation_id,a.id,b.id,a.project,b.project,a.deleted_at,b.deleted_at FROM memory_relations r " +
            "JOIN memory_entries a ON a.id=r.source_id JOIN memory_entries b ON b.id=r.target_id " +
            "WHERE r.status='pending' AND $aGuard AND $bGuard ORDER BY r.id ASC", (aArgs.toList() + bArgs.toList()).toTypedArray()).use { c ->
            while (c.moveToNext()) {
                token.throwIfCancelled()
                val relation = c.text(0); val source = c.text(1); val target = c.text(2); val project = c.text(3); val targetProject = c.text(4)
                if (!c.isNull(5)) c.integer(5); if (!c.isNull(6)) c.integer(6)
                val aRetired = !c.isNull(5); val bRetired = !c.isNull(6)
                when {
                    aRetired && bRetired -> {
                        candidates++
                        if (samples.size < 10) samples += mapOf("relation_id" to relation, "source_id" to source, "target_id" to target,
                            "source_project" to project, "target_project" to targetProject)
                    }
                    aRetired || bRetired -> oneRetired++
                    else -> live++
                }
            }
        }
        return MemoryDoctorCheck(id, if (candidates == 0L) "ok" else "warning", "partial", "retained_authorized_endpoints_only",
            "Only pending pairs with both endpoints retained and authorized were evaluated. Hard-missing or unattributed endpoints are not counted. Retired pairs can still be judged explicitly.",
            mapOf("candidate_count" to candidates.toString(), "one_retired_count" to oneRetired.toString(), "live_pending" to live.toString()), samples)
    }
    private fun lockObservation(sql: ChatSqlDatabase): MemoryDoctorCheck {
        val journal = sql.query("PRAGMA journal_mode").use { c -> check(c.moveToFirst()); c.text(0) }
        val timeout = sql.query("PRAGMA busy_timeout").use { c -> check(c.moveToFirst()); c.integer(0) }
        return MemoryDoctorCheck("sqlite_lock_contention", if (timeout <= 0) "warning" else null, "partial", "checkpoint_not_evaluated",
            "These values describe only the observation connection. No checkpoint was run and absence of contention was not established.",
            mapOf("connection" to "observation_read_handle", "journal_mode" to journal, "busy_timeout_ms" to timeout.toString()))
    }
}
