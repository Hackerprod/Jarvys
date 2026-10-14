package com.jarvys.agent.workspaces

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jarvys.agent.crew.CrewMissionAccess
import org.json.JSONArray
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID

/**
 * Additive metadata registry. Opening/reading never resumes work or changes uncertain rows.
 * All read/compare/write sequences use SQLite transactions, including across store instances.
 * beforeWrite runs synchronously inside the transaction: check STOP only, never spawn,
 * contact a provider or wait for a model here. Commit STARTED before any external effect.
 */
class WorkspaceRegistry(context: Context) : Closeable {
    companion object {
        const val DATABASE_NAME = "jarvys-workspaces-v1.db"
        const val NATIVE_UI_ORIGIN = "NATIVE_UI"
        private const val MAX_PAGE = 100
        private val TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}")
        private val DIGEST = Regex("[0-9a-f]{64}")
    }

    private val helper = Helper(context.applicationContext ?: context)

    fun nativeControls(): NativeWorkspaceControls = NativeWorkspaceControls(this)

    @JvmOverloads
    fun createWorkspace(idempotencyKey: String, title: String, beforeWrite: Runnable = Runnable {}): WorkspaceRecord {
        token(idempotencyKey, "idempotencyKey")
        val canonicalTitle = title.trim()
        boundedText(canonicalTitle, "title", 640, 160)
        return transaction { db ->
            val existing = one(db, "workspace", "creation_key=?", arrayOf(idempotencyKey), ::workspace)
            if (existing != null) {
                if (existing.title != canonicalTitle) conflict("Workspace key has a different title")
                return@transaction existing
            }
            val id = UUID.randomUUID().toString()
            val record = WorkspaceRecord(id, canonicalTitle, WorkspaceIdentity.executionSessionId(id),
                WorkspaceIdentity.CENTRAL_SESSION, now())
            beforeWrite.run()
            db.insertOrThrow("workspace", null, values(
                "workspace_id" to id, "creation_key" to idempotencyKey, "title" to canonicalTitle,
                "execution_session_id" to record.executionSessionId, "owner_central_session_id" to record.ownerCentralSessionId,
                "created_at" to record.createdAt))
            record
        }
    }

    fun getWorkspace(workspaceId: String): WorkspaceRecord? {
        workspaceId(workspaceId)
        return one(helper.readableDatabase, "workspace", "workspace_id=?", arrayOf(workspaceId), ::workspace)
    }

    fun hasBoundAssignments(workspaceId: String): Boolean {
        workspaceId(workspaceId)
        return rows(helper.readableDatabase, "assignment", "workspace_id=? AND state='BOUND'",
            arrayOf(workspaceId), "assignment_id", 1, ::assignment).isNotEmpty()
    }

    /** Exact existing binding only; never attaches evidence to a merely current revision. */
    fun findBoundAssignment(workspaceId: String, missionId: String, botId: String): WorkspaceAssignment? {
        workspaceId(workspaceId)
        val matches = rows(helper.readableDatabase, "assignment",
            "workspace_id=? AND mission_id=? AND bot_id=? AND state='BOUND'",
            arrayOf(workspaceId, missionId, botId), "assignment_id", 2, ::assignment)
        check(matches.size <= 1) { "Ambiguous workspace assignment evidence" }
        return matches.singleOrNull()
    }

    @JvmOverloads
    fun listWorkspaces(limit: Int = 50, afterWorkspaceId: String? = null): List<WorkspaceRecord> {
        page(limit)
        afterWorkspaceId?.let(::workspaceId)
        return rows(helper.readableDatabase, "workspace", if (afterWorkspaceId == null) null else "workspace_id>?",
            afterWorkspaceId?.let { arrayOf(it) }, "workspace_id", limit, ::workspace)
    }

    @JvmOverloads
    fun prepareAssignment(
        workspaceId: String, assignmentKey: String, requestId: String, originalUserRequest: String,
        role: String, missionText: String, tools: List<String>?, name: String?, missionAccess: CrewMissionAccess,
        beforeWrite: Runnable = Runnable {},
    ): WorkspaceAssignment {
        workspaceId(workspaceId)
        token(assignmentKey, "assignmentKey")
        token(requestId, "requestId")
        boundedText(originalUserRequest, "originalUserRequest", 65536)
        token(role, "role")
        boundedText(missionText, "missionText", 65536)
        name?.let { boundedText(it, "name", 512, 128) }
        val canonicalTools = canonicalTools(tools)
        return transaction { db ->
            requireWorkspace(db, workspaceId)
            val existing = one(db, "assignment", "workspace_id=? AND assignment_key=?",
                arrayOf(workspaceId, assignmentKey), ::assignment)
            if (existing != null) {
                if (existing.requestId != requestId || existing.originalUserRequest != originalUserRequest ||
                    existing.role != role || existing.missionText != missionText || existing.tools != canonicalTools ||
                    existing.name != name || existing.missionAccess != missionAccess) conflict("Assignment key has a different payload")
                return@transaction existing
            }
            // The request is the durable causal root, shared by all its assignments in this workspace.
            db.query("assignment", arrayOf("mission_id", "original_user_request"),
                "workspace_id=? AND request_id=?", arrayOf(workspaceId, requestId),
                "mission_id,original_user_request", null, null, "2").use { cursor ->
                if (cursor.moveToFirst()) {
                    val priorMission = decode("request mission") { cursor.string("mission_id") }
                    val priorRequest = decode("request source") {
                        cursor.string("original_user_request").also { boundedText(it, "originalUserRequest", 65536) }
                    }
                    if (priorMission != missionId(workspaceId, requestId) || cursor.count != 1)
                        corrupt("Conflicting durable request identity")
                    if (priorRequest != originalUserRequest) conflict("Request ID has different original user text")
                }
            }
            val id = UUID.randomUUID().toString()
            val timestamp = now()
            beforeWrite.run()
            db.insertOrThrow("assignment", null, values(
                "workspace_id" to workspaceId, "assignment_id" to id, "assignment_key" to assignmentKey,
                "request_id" to requestId, "original_user_request" to originalUserRequest,
                "mission_id" to missionId(workspaceId, requestId), "role" to role, "mission_text" to missionText,
                "tools_json" to canonicalTools?.let { JSONArray(it).toString() }, "name" to name,
                "mission_access" to missionAccess.name, "revision" to 1L, "process_id" to null,
                "state" to WorkspaceAssignmentState.PREPARED.name, "bot_id" to null,
                "created_at" to timestamp, "updated_at" to timestamp))
            requireAssignment(db, workspaceId, id)
        }
    }

    fun getAssignment(workspaceId: String, assignmentId: String): WorkspaceAssignment? {
        workspaceId(workspaceId); uuid(assignmentId, "assignmentId")
        return transaction { db ->
            val result = one(db, "assignment", "workspace_id=? AND assignment_id=?", arrayOf(workspaceId, assignmentId), ::assignment)
            if (result != null) requireWorkspace(db, workspaceId)
            result
        }
    }

    @JvmOverloads
    fun listAssignments(workspaceId: String, limit: Int = 50, afterAssignmentId: String? = null): List<WorkspaceAssignment> {
        workspaceId(workspaceId); page(limit); afterAssignmentId?.let { uuid(it, "afterAssignmentId") }
        return transaction { db ->
            requireWorkspace(db, workspaceId)
            rows(db, "assignment", "workspace_id=?" + if (afterAssignmentId == null) "" else " AND assignment_id>?",
                if (afterAssignmentId == null) arrayOf(workspaceId) else arrayOf(workspaceId, afterAssignmentId),
                "assignment_id", limit, ::assignment)
        }
    }

    @JvmOverloads
    fun markStarted(workspaceId: String, assignmentId: String, expectedRevision: Long, processId: String,
                    beforeWrite: Runnable = Runnable {}): WorkspaceAssignment {
        token(processId, "processId")
        return updateAssignment(workspaceId, assignmentId, expectedRevision, WorkspaceAssignmentState.PREPARED,
            values("state" to WorkspaceAssignmentState.STARTED.name, "process_id" to processId), beforeWrite)
    }

    @JvmOverloads
    fun bindBot(workspaceId: String, assignmentId: String, expectedRevision: Long, botId: String,
                beforeWrite: Runnable = Runnable {}): WorkspaceAssignment {
        token(botId, "botId")
        return updateAssignment(workspaceId, assignmentId, expectedRevision, WorkspaceAssignmentState.STARTED,
            values("state" to WorkspaceAssignmentState.BOUND.name, "bot_id" to botId), beforeWrite)
    }

    @JvmOverloads
    fun markUncertain(workspaceId: String, assignmentId: String, expectedRevision: Long,
                      beforeWrite: Runnable = Runnable {}): WorkspaceAssignment =
        updateAssignment(workspaceId, assignmentId, expectedRevision, WorkspaceAssignmentState.STARTED,
            values("state" to WorkspaceAssignmentState.UNCERTAIN.name), beforeWrite)

    internal fun submitNativeControl(
        requestId: String, workspaceId: String, assignmentId: String, botId: String, assignmentRevision: Long,
        type: WorkspaceControlType, userText: String, beforeWrite: Runnable,
    ): WorkspaceUserControl {
        uuid(requestId, "requestId"); workspaceId(workspaceId); uuid(assignmentId, "assignmentId")
        token(botId, "botId"); revision(assignmentRevision)
        boundedText(userText, "userText", 65536, allowEmpty = type != WorkspaceControlType.CORRECT)
        return transaction { db ->
            val existing = control(db, requestId)
            if (existing != null) {
                if (existing.workspaceId != workspaceId || existing.assignmentId != assignmentId || existing.botId != botId ||
                    existing.assignmentRevision != assignmentRevision || existing.type != type || existing.userText != userText)
                    conflict("Control request ID has a different target or payload")
                return@transaction existing
            }
            val target = requireAssignment(db, workspaceId, assignmentId)
            checkTarget(target, botId, assignmentRevision)
            val timestamp = now()
            beforeWrite.run()
            db.insertOrThrow("user_control", null, values(
                "request_id" to requestId, "workspace_id" to workspaceId, "assignment_id" to assignmentId,
                "bot_id" to botId, "assignment_revision" to assignmentRevision, "admitted_assignment_revision" to null,
                "type" to type.name, "user_text" to userText, "origin" to NATIVE_UI_ORIGIN,
                "revision" to 1L, "process_id" to null, "state" to WorkspaceControlState.PENDING.name,
                "created_at" to timestamp, "updated_at" to timestamp))
            requireControl(db, requestId)
        }
    }

    fun getControl(requestId: String): WorkspaceUserControl? {
        uuid(requestId, "requestId")
        return transaction { db ->
            val result = control(db, requestId)
            if (result != null) requireAssignment(db, result.workspaceId, result.assignmentId)
            result
        }
    }

    /** Latest explicit correction only; historical dispatch and incorporation remain independent. */
    fun getLatestCorrection(workspaceId: String, assignmentId: String): WorkspaceUserControl? {
        workspaceId(workspaceId); uuid(assignmentId, "assignmentId")
        return transaction { db ->
            requireAssignment(db, workspaceId, assignmentId)
            db.query("user_control", null, "workspace_id=? AND assignment_id=? AND type=?",
                arrayOf(workspaceId, assignmentId, WorkspaceControlType.CORRECT.name),
                null, null, "created_at DESC, request_id DESC", "1").use {
                if (it.moveToFirst()) userControl(it) else null
            }
        }
    }

    /** Latest unresolved native control; reading it never acknowledges, retries or resumes work. */
    fun getUnsettledControl(workspaceId: String, assignmentId: String): WorkspaceUserControl? {
        workspaceId(workspaceId); uuid(assignmentId, "assignmentId")
        return transaction { db ->
            requireAssignment(db, workspaceId, assignmentId)
            db.query("user_control", null, "workspace_id=? AND assignment_id=? AND state IN (?,?)",
                arrayOf(workspaceId, assignmentId, WorkspaceControlState.STARTED.name, WorkspaceControlState.UNCERTAIN.name),
                null, null, "updated_at DESC, request_id DESC", "1").use {
                if (it.moveToFirst()) userControl(it) else null
            }
        }
    }

    /** Saved, not dispatched: exact current assignment revision only; independent of unresolved effects. */
    fun getPendingControl(workspaceId: String, assignmentId: String, assignmentRevision: Long): WorkspaceUserControl? {
        workspaceId(workspaceId); uuid(assignmentId, "assignmentId"); revision(assignmentRevision)
        return transaction { db ->
            val target = requireAssignment(db, workspaceId, assignmentId)
            if (target.revision != assignmentRevision || target.botId == null) return@transaction null
            db.query("user_control", null,
                "workspace_id=? AND assignment_id=? AND assignment_revision=? AND bot_id=? AND state=?",
                arrayOf(workspaceId, assignmentId, assignmentRevision.toString(), target.botId, WorkspaceControlState.PENDING.name),
                null, null, "updated_at DESC, request_id DESC", "1").use {
                if (it.moveToFirst()) userControl(it) else null
            }
        }
    }

    @JvmOverloads
    fun listControls(workspaceId: String, limit: Int = 50, afterRequestId: String? = null): List<WorkspaceUserControl> {
        workspaceId(workspaceId); page(limit); afterRequestId?.let { uuid(it, "afterRequestId") }
        return transaction { db ->
            requireWorkspace(db, workspaceId)
            rows(db, "user_control", "workspace_id=?" + if (afterRequestId == null) "" else " AND request_id>?",
                if (afterRequestId == null) arrayOf(workspaceId) else arrayOf(workspaceId, afterRequestId),
                "request_id", limit, ::userControl)
        }
    }

    @JvmOverloads
    fun markControlStarted(requestId: String, expectedRevision: Long, processId: String,
                           beforeWrite: Runnable = Runnable {}): WorkspaceUserControl {
        uuid(requestId, "requestId"); revision(expectedRevision); token(processId, "processId")
        return transaction { db ->
            val current = requireControl(db, requestId)
            checkRevision(current.revision, expectedRevision)
            check(current.state == WorkspaceControlState.PENDING) { "Control must be PENDING" }
            val target = requireAssignment(db, current.workspaceId, current.assignmentId)
            checkTarget(target, current.botId, current.assignmentRevision)
            // An unresolved effect must not remove the user's ability to stop this exact bot.
            // PAUSE claims a fresh revision and its own receipt; it never resolves/replays the old control.
            // A late completion of that old control consequently fails its admitted-revision check.
            if (current.type != WorkspaceControlType.PAUSE) {
                db.query("user_control", arrayOf("request_id"),
                    "workspace_id=? AND assignment_id=? AND state IN (?,?)",
                    arrayOf(current.workspaceId, current.assignmentId, WorkspaceControlState.STARTED.name,
                        WorkspaceControlState.UNCERTAIN.name), null, null, null, "1").use {
                    check(!it.moveToFirst()) { "Assignment has an unresolved control" }
                }
            }
            val admitted = increment(target.revision)
            beforeWrite.run()
            updateOne(db, "assignment", values("revision" to admitted, "updated_at" to now()),
                "workspace_id=? AND assignment_id=? AND revision=?",
                arrayOf(current.workspaceId, current.assignmentId, target.revision.toString()))
            updateControl(db, current, values("state" to WorkspaceControlState.STARTED.name,
                "process_id" to processId, "admitted_assignment_revision" to admitted))
            requireControl(db, requestId)
        }
    }

    @JvmOverloads
    fun completeControl(requestId: String, expectedRevision: Long, beforeWrite: Runnable = Runnable {}): WorkspaceUserControl =
        finishControl(requestId, expectedRevision, false, beforeWrite)

    @JvmOverloads
    fun markControlUncertain(requestId: String, expectedRevision: Long, beforeWrite: Runnable = Runnable {}): WorkspaceUserControl =
        finishControl(requestId, expectedRevision, true, beforeWrite)

    @JvmOverloads
    fun markRead(workspaceId: String, assignmentId: String, snapshotDigest: String,
                 beforeWrite: Runnable = Runnable {}): CentralReadReceipt {
        workspaceId(workspaceId); uuid(assignmentId, "assignmentId"); digest(snapshotDigest)
        return transaction { db ->
            requireAssignment(db, workspaceId, assignmentId)
            receipt(db, workspaceId, assignmentId, snapshotDigest)?.let { return@transaction it }
            val result = CentralReadReceipt(workspaceId, assignmentId, snapshotDigest, now(), WorkspaceIdentity.CENTRAL_SESSION)
            beforeWrite.run()
            db.insertOrThrow("central_read_receipt", null, values("workspace_id" to workspaceId,
                "assignment_id" to assignmentId, "snapshot_digest" to snapshotDigest,
                "read_at" to result.readAt, "reader_central_session_id" to result.readerCentralSessionId))
            result
        }
    }

    fun isRead(workspaceId: String, assignmentId: String, snapshotDigest: String): Boolean {
        workspaceId(workspaceId); uuid(assignmentId, "assignmentId"); digest(snapshotDigest)
        return transaction { db ->
            val result = receipt(db, workspaceId, assignmentId, snapshotDigest)
            if (result != null) requireAssignment(db, workspaceId, assignmentId)
            result != null
        }
    }

    @JvmOverloads
    fun listReadReceipts(workspaceId: String, assignmentId: String, limit: Int = 50,
                         afterSnapshotDigest: String? = null): List<CentralReadReceipt> {
        workspaceId(workspaceId); uuid(assignmentId, "assignmentId"); page(limit); afterSnapshotDigest?.let(::digest)
        return transaction { db ->
            requireAssignment(db, workspaceId, assignmentId)
            rows(db, "central_read_receipt", "workspace_id=? AND assignment_id=?" +
                if (afterSnapshotDigest == null) "" else " AND snapshot_digest>?",
                if (afterSnapshotDigest == null) arrayOf(workspaceId, assignmentId)
                else arrayOf(workspaceId, assignmentId, afterSnapshotDigest), "snapshot_digest", limit, ::readReceipt)
        }
    }

    override fun close() = helper.close()

    private fun updateAssignment(workspaceId: String, assignmentId: String, expectedRevision: Long,
                                 requiredState: WorkspaceAssignmentState, changes: ContentValues,
                                 beforeWrite: Runnable): WorkspaceAssignment {
        workspaceId(workspaceId); uuid(assignmentId, "assignmentId"); revision(expectedRevision)
        return transaction { db ->
            val current = requireAssignment(db, workspaceId, assignmentId)
            checkRevision(current.revision, expectedRevision)
            check(current.state == requiredState) { "Assignment must be $requiredState" }
            changes.put("revision", increment(expectedRevision)); changes.put("updated_at", now())
            beforeWrite.run()
            updateOne(db, "assignment", changes, "workspace_id=? AND assignment_id=? AND revision=?",
                arrayOf(workspaceId, assignmentId, expectedRevision.toString()))
            requireAssignment(db, workspaceId, assignmentId)
        }
    }

    private fun finishControl(requestId: String, expectedRevision: Long, uncertain: Boolean,
                              beforeWrite: Runnable): WorkspaceUserControl {
        uuid(requestId, "requestId"); revision(expectedRevision)
        return transaction { db ->
            val current = requireControl(db, requestId)
            checkRevision(current.revision, expectedRevision)
            check(current.state == WorkspaceControlState.STARTED) { "Control must be STARTED; uncertain effects cannot be retried" }
            // Even a changed target must retain uncertainty. Successful completion requires the exact admitted target.
            if (!uncertain) checkTarget(requireAssignment(db, current.workspaceId, current.assignmentId),
                current.botId, requireNotNull(current.admittedAssignmentRevision))
            beforeWrite.run()
            updateControl(db, current, values("state" to if (uncertain) WorkspaceControlState.UNCERTAIN.name
                else WorkspaceControlState.COMPLETE.name))
            requireControl(db, requestId)
        }
    }

    private fun updateControl(db: SQLiteDatabase, current: WorkspaceUserControl, changes: ContentValues) {
        changes.put("revision", increment(current.revision)); changes.put("updated_at", now())
        updateOne(db, "user_control", changes, "request_id=? AND revision=?",
            arrayOf(current.requestId, current.revision.toString()))
    }

    private fun checkTarget(target: WorkspaceAssignment, botId: String, expectedRevision: Long) {
        checkRevision(target.revision, expectedRevision)
        check(target.state == WorkspaceAssignmentState.BOUND && target.botId == botId) { "Control target must match the exact BOUND bot" }
    }

    private fun requireWorkspace(db: SQLiteDatabase, id: String): WorkspaceRecord =
        one(db, "workspace", "workspace_id=?", arrayOf(id), ::workspace) ?: throw IllegalArgumentException("Unknown workspace")

    private fun requireAssignment(db: SQLiteDatabase, workspaceId: String, assignmentId: String): WorkspaceAssignment {
        requireWorkspace(db, workspaceId)
        return one(db, "assignment", "workspace_id=? AND assignment_id=?", arrayOf(workspaceId, assignmentId), ::assignment)
            ?: throw IllegalArgumentException("Unknown assignment in workspace")
    }

    private fun control(db: SQLiteDatabase, id: String) = one(db, "user_control", "request_id=?", arrayOf(id), ::userControl)
    private fun requireControl(db: SQLiteDatabase, id: String) = control(db, id) ?: throw IllegalArgumentException("Unknown control")
    private fun receipt(db: SQLiteDatabase, workspaceId: String, assignmentId: String, hash: String) =
        one(db, "central_read_receipt", "workspace_id=? AND assignment_id=? AND snapshot_digest=?",
            arrayOf(workspaceId, assignmentId, hash), ::readReceipt)

    private fun workspace(c: Cursor): WorkspaceRecord = decode("workspace") {
        val id = c.string("workspace_id").also(::workspaceId)
        val title = c.string("title").also { boundedText(it, "title", 640, 160); require(it == it.trim()) }
        token(c.string("creation_key"), "creation_key")
        val session = c.string("execution_session_id")
        val owner = c.string("owner_central_session_id")
        require(session == WorkspaceIdentity.executionSessionId(id) && owner == WorkspaceIdentity.CENTRAL_SESSION)
        WorkspaceRecord(id, title, session, owner, c.positive("created_at"))
    }

    private fun assignment(c: Cursor): WorkspaceAssignment = decode("assignment") {
        val workspaceId = c.string("workspace_id").also(::workspaceId)
        val requestId = c.string("request_id").also { token(it, "requestId") }
        val mission = c.string("mission_id").also { uuid(it, "missionId") }
        require(mission == missionId(workspaceId, requestId))
        val state = WorkspaceAssignmentState.valueOf(c.string("state"))
        val process = c.optionalString("process_id")?.also { token(it, "processId") }
        val bot = c.optionalString("bot_id")?.also { token(it, "botId") }
        val revision = c.positive("revision")
        require(when (state) {
            WorkspaceAssignmentState.PREPARED -> process == null && bot == null && revision == 1L
            WorkspaceAssignmentState.STARTED -> process != null && bot == null && revision == 2L
            WorkspaceAssignmentState.UNCERTAIN -> process != null && bot == null && revision == 3L
            WorkspaceAssignmentState.BOUND -> process != null && bot != null && revision >= 3L
        })
        val createdAt = c.positive("created_at")
        val updatedAt = c.positive("updated_at")
        WorkspaceAssignment(workspaceId, c.string("assignment_id").also { uuid(it, "assignmentId") },
            c.string("assignment_key").also { token(it, "assignmentKey") }, requestId,
            c.string("original_user_request").also { boundedText(it, "originalUserRequest", 65536) }, mission,
            c.string("role").also { token(it, "role") },
            c.string("mission_text").also { boundedText(it, "missionText", 65536) },
            decodeTools(c.optionalString("tools_json")), c.optionalString("name")?.also { boundedText(it, "name", 512, 128) },
            CrewMissionAccess.valueOf(c.string("mission_access")), revision, process, state, bot, createdAt, updatedAt)
    }

    private fun userControl(c: Cursor): WorkspaceUserControl = decode("user_control") {
        val state = WorkspaceControlState.valueOf(c.string("state"))
        val process = c.optionalString("process_id")?.also { token(it, "processId") }
        val assignmentRevision = c.positive("assignment_revision")
        val admitted = c.optionalPositive("admitted_assignment_revision")
        val revision = c.positive("revision")
        require(assignmentRevision >= 3L)
        require(when (state) {
            WorkspaceControlState.PENDING -> process == null && admitted == null && revision == 1L
            WorkspaceControlState.STARTED -> process != null && admitted == increment(assignmentRevision) && revision == 2L
            WorkspaceControlState.COMPLETE, WorkspaceControlState.UNCERTAIN ->
                process != null && admitted == increment(assignmentRevision) && revision == 3L
        })
        val origin = c.string("origin").also { require(it == NATIVE_UI_ORIGIN) }
        val type = WorkspaceControlType.valueOf(c.string("type"))
        WorkspaceUserControl(c.string("request_id").also { uuid(it, "requestId") },
            c.string("workspace_id").also(::workspaceId), c.string("assignment_id").also { uuid(it, "assignmentId") },
            c.string("bot_id").also { token(it, "botId") }, assignmentRevision, admitted, type,
            c.string("user_text").also { boundedText(it, "userText", 65536, allowEmpty = type != WorkspaceControlType.CORRECT) },
            origin, revision, process, state, c.positive("created_at"), c.positive("updated_at"))
    }

    private fun readReceipt(c: Cursor): CentralReadReceipt = decode("central_read_receipt") {
        CentralReadReceipt(c.string("workspace_id").also(::workspaceId),
            c.string("assignment_id").also { uuid(it, "assignmentId") },
            c.string("snapshot_digest").also(::digest), c.positive("read_at"),
            c.string("reader_central_session_id").also { require(it == WorkspaceIdentity.CENTRAL_SESSION) })
    }

    private fun canonicalTools(tools: List<String>?): List<String>? {
        if (tools == null) return null
        require(tools.size <= 128) { "Too many tools" }
        tools.forEach { token(it, "tool") }
        return tools.distinct().sorted()
    }

    private fun decodeTools(json: String?): List<String>? {
        if (json == null) return null
        require(json.toByteArray(StandardCharsets.UTF_8).size <= 22000)
        val array = JSONArray(json)
        require(array.length() <= 128)
        val tools = (0 until array.length()).map { index ->
            val value = array.get(index)
            require(value is String) { "Tool must be a string" }
            value
        }
        require(tools == canonicalTools(tools)) { "Noncanonical tools" }
        return tools
    }

    private fun workspaceId(value: String) { WorkspaceIdentity.requireWorkspaceId(value); uuid(value, "workspaceId") }
    private fun uuid(value: String, label: String) {
        require(value.length == 36 && UUID.fromString(value).toString() == value) { "$label must be a canonical UUID" }
    }
    private fun token(value: String, label: String) { require(TOKEN.matches(value)) { "$label must be a bounded safe token" } }
    private fun digest(value: String) { require(DIGEST.matches(value)) { "snapshotDigest must be lowercase SHA-256 hex" } }
    private fun page(limit: Int) { require(limit in 1..MAX_PAGE) { "Page limit must be 1..$MAX_PAGE" } }
    private fun revision(value: Long) { require(value > 0L) { "Revision must be positive" } }
    private fun increment(value: Long): Long { require(value in 1 until Long.MAX_VALUE) { "Revision exhausted" }; return value + 1 }
    private fun checkRevision(actual: Long, expected: Long) {
        if (actual != expected) throw StaleWorkspaceRevisionException("Revision changed: expected $expected, found $actual")
    }
    private fun boundedText(value: String, label: String, maxBytes: Int, maxChars: Int = maxBytes, allowEmpty: Boolean = false) {
        require((allowEmpty || value.isNotBlank()) && value.length <= maxChars &&
            value.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) { "$label is empty or too large" }
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            require(ch != '\u0000') { "$label contains NUL" }
            if (Character.isHighSurrogate(ch)) {
                require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) { "$label contains malformed Unicode" }
                index++
            } else require(!Character.isLowSurrogate(ch)) { "$label contains malformed Unicode" }
            index++
        }
    }
    private fun missionId(workspaceId: String, requestId: String): String = UUID.nameUUIDFromBytes(
        ("jarvys-workspace-mission-v1\u0000$workspaceId\u0000$requestId").toByteArray(StandardCharsets.UTF_8)).toString()
    private fun now(): Long = System.currentTimeMillis().coerceAtLeast(1L)
    private fun conflict(message: String): Nothing = throw WorkspaceIdempotencyConflictException(message)
    private fun corrupt(message: String): Nothing = throw WorkspaceRegistryCorruptException(message)
    private fun <T> decode(table: String, block: () -> T): T = try { block() } catch (error: Exception) {
        throw WorkspaceRegistryCorruptException("Invalid durable $table row; no recovery or replay was attempted", error)
    }

    private fun Cursor.string(column: String): String {
        val index = getColumnIndexOrThrow(column)
        require(getType(index) == Cursor.FIELD_TYPE_STRING) { "$column must be TEXT" }
        return getString(index)
    }
    private fun Cursor.optionalString(column: String): String? = if (isNull(getColumnIndexOrThrow(column))) null else string(column)
    private fun Cursor.positive(column: String): Long {
        val index = getColumnIndexOrThrow(column)
        require(getType(index) == Cursor.FIELD_TYPE_INTEGER) { "$column must be INTEGER" }
        return getLong(index).also { require(it > 0) { "$column must be positive" } }
    }
    private fun Cursor.optionalPositive(column: String): Long? = if (isNull(getColumnIndexOrThrow(column))) null else positive(column)

    private fun values(vararg entries: Pair<String, Any?>): ContentValues = ContentValues().apply {
        for ((key, value) in entries) when (value) {
            null -> putNull(key)
            is String -> put(key, value)
            is Long -> put(key, value)
            else -> throw IllegalArgumentException("Unsupported registry value")
        }
    }
    private fun updateOne(db: SQLiteDatabase, table: String, values: ContentValues, where: String, args: Array<String>) {
        if (db.update(table, values, where, args) != 1) throw StaleWorkspaceRevisionException("Durable row changed")
    }
    private fun <T> one(db: SQLiteDatabase, table: String, where: String, args: Array<String>, decode: (Cursor) -> T): T? =
        db.query(table, null, where, args, null, null, null, "2").use {
            if (!it.moveToFirst()) null else {
                if (it.count != 1) corrupt("Duplicate durable $table identity")
                decode(it)
            }
        }
    private fun <T> rows(db: SQLiteDatabase, table: String, where: String?, args: Array<String>?, order: String,
                         limit: Int, decode: (Cursor) -> T): List<T> =
        db.query(table, null, where, args, null, null, "$order ASC", limit.toString()).use { cursor ->
            val result = ArrayList<T>()
            while (cursor.moveToNext()) result.add(decode(cursor))
            Collections.unmodifiableList(result)
        }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, 1,
        DatabaseErrorHandler { throw WorkspaceRegistryCorruptException("Workspace database is corrupt; preserved without deletion") }) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE workspace (
                workspace_id TEXT PRIMARY KEY NOT NULL, creation_key TEXT NOT NULL UNIQUE, title TEXT NOT NULL,
                execution_session_id TEXT NOT NULL UNIQUE, owner_central_session_id TEXT NOT NULL,
                created_at INTEGER NOT NULL CHECK(created_at>0))""")
            db.execSQL("""CREATE TABLE assignment (
                assignment_id TEXT PRIMARY KEY NOT NULL, workspace_id TEXT NOT NULL,
                assignment_key TEXT NOT NULL, request_id TEXT NOT NULL, original_user_request TEXT NOT NULL,
                mission_id TEXT NOT NULL, role TEXT NOT NULL, mission_text TEXT NOT NULL, tools_json TEXT, name TEXT,
                mission_access TEXT NOT NULL CHECK(mission_access IN ('STANDARD','READ_ONLY')),
                revision INTEGER NOT NULL CHECK(revision>0), process_id TEXT,
                state TEXT NOT NULL CHECK(state IN ('PREPARED','STARTED','BOUND','UNCERTAIN')), bot_id TEXT,
                created_at INTEGER NOT NULL CHECK(created_at>0), updated_at INTEGER NOT NULL CHECK(updated_at>0),
                UNIQUE(workspace_id,assignment_key), UNIQUE(workspace_id,assignment_id),
                FOREIGN KEY(workspace_id) REFERENCES workspace(workspace_id))""")
            db.execSQL("CREATE INDEX assignment_request ON assignment(workspace_id,request_id)")
            db.execSQL("""CREATE TABLE user_control (
                request_id TEXT PRIMARY KEY NOT NULL, workspace_id TEXT NOT NULL, assignment_id TEXT NOT NULL,
                bot_id TEXT NOT NULL, assignment_revision INTEGER NOT NULL CHECK(assignment_revision>0),
                admitted_assignment_revision INTEGER, type TEXT NOT NULL CHECK(type IN ('PAUSE','RESUME','CORRECT')),
                user_text TEXT NOT NULL, origin TEXT NOT NULL CHECK(origin='NATIVE_UI'),
                revision INTEGER NOT NULL CHECK(revision>0), process_id TEXT,
                state TEXT NOT NULL CHECK(state IN ('PENDING','STARTED','COMPLETE','UNCERTAIN')),
                created_at INTEGER NOT NULL CHECK(created_at>0), updated_at INTEGER NOT NULL CHECK(updated_at>0),
                FOREIGN KEY(workspace_id,assignment_id) REFERENCES assignment(workspace_id,assignment_id))""")
            db.execSQL("CREATE INDEX control_workspace ON user_control(workspace_id,request_id)")
            db.execSQL("CREATE INDEX control_active ON user_control(workspace_id,assignment_id,state)")
            db.execSQL("""CREATE TABLE central_read_receipt (
                workspace_id TEXT NOT NULL, assignment_id TEXT NOT NULL, snapshot_digest TEXT NOT NULL,
                read_at INTEGER NOT NULL CHECK(read_at>0), reader_central_session_id TEXT NOT NULL,
                PRIMARY KEY(workspace_id,assignment_id,snapshot_digest),
                FOREIGN KEY(workspace_id,assignment_id) REFERENCES assignment(workspace_id,assignment_id))""")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw WorkspaceRegistryCorruptException("Unsupported workspace registry upgrade $oldVersion to $newVersion")
        }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw WorkspaceRegistryCorruptException("Unsupported workspace registry downgrade $oldVersion to $newVersion")
        }
    }
}
