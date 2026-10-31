package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.connectors.ConnectionAccess
import com.jarvys.agent.connectors.ConnectionAccessProvider
import com.jarvys.agent.crew.*
import com.jarvys.agent.device.ScreenData
import com.jarvys.agent.workspaces.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Shared test fixture: genuine native assignments and the production catalog, loop and checkpoint installer. */
internal class WorkspaceMemoryFixture(storage: ChatTestStore, directBridge: Boolean = true) : AutoCloseable {
    val files = storage.newFilesDirectory("workspace-memory-${UUID.randomUUID()}")
    val context: Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()), ConnectionAccessProvider {
        override fun connectionAccess() = ConnectionAccess.forTests(emptyMap())
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name)
            else File(files, "databases/$name").also { it.parentFile!!.mkdirs() }
    }
    val central = WorkspaceIdentity.CENTRAL_SESSION
    val bridge get() = ChatDatabaseBridge.getInstance(context)
    val db get() = JarvysChatDatabaseProvider.getInstance(context)
    val store get() = LocalRunStore(context)
    val policy get() = MemoryWritePolicy.forContext(context)
    private val managers = linkedMapOf<String, CrewManager>()
    private val priorMetadata = context.getSharedPreferences("provider_context_metadata", Context.MODE_PRIVATE).all.toMap()
    init {
        if (directBridge) ChatDatabaseBridge.getInstance(context, ChatTestStore.directExecutorService())
        else ChatDatabaseBridge.getInstance(context)
        storage.localRunStore(files)
        FreshStorageBootstrap.ensure(context)
        assertTrue(bridge.ensureConversationSync(central, "chat") is ChatDbResult.Success)
        policy.setEnabledBlocking(true)
        policy.requestSetPromptCaptureEnabled(false).get(10, TimeUnit.SECONDS)
        // Existing offline provider-context fixture seam, not a network fetch.
        ProviderSettings(context).apply { setProvider(ProviderSettings.Provider.OPENROUTER); setOpenRouterModel("offline/workspace-memory") }
        setWindow(16384)
    }
    fun setWindow(tokens: Int) {
        val model = ProviderSettings(context).model
        check(context.getSharedPreferences("provider_context_metadata", Context.MODE_PRIVATE).edit()
            .putInt("model_$model", tokens).putLong("fetched_$model", System.currentTimeMillis()).commit())
    }
    fun createProject(label: String): WorkspaceRecord = WorkspaceRegistry(context).use {
        it.createWorkspace(UUID.randomUUID().toString(), label).also { record ->
            assertTrue(bridge.ensureConversationSync(record.executionSessionId, "workspace") is ChatDbResult.Success)
        }
    }
    data class TaskRun(val bot: CrewManager.Bot, val manager: CrewManager, val assignment: WorkspaceAssignment)
    fun runTask(record: WorkspaceRecord, provider: ModelProviderClient,
                access: CrewMissionAccess = CrewMissionAccess.STANDARD,
                role: String = CrewRoleTemplates.ANALYST,
                mission: String = "Continue the assigned project using retained requirements and evidence.",
                transform: ((CoreToolRegistry) -> CoreToolRegistry)? = null): TaskRun {
        val runtime = CoreAgentRuntime(context, record.executionSessionId, emptyList(), CorePromptBudget.standard(), provider)
        val manager = runtime.configureCrewManager(runtime.createTools(), CoreAgentModel(context, provider, record.executionSessionId), null)
        managers[record.executionSessionId] = manager
        if (transform != null) {
            // Optional adversarial observation seam only. Preserve the entire production configuration;
            // configure(...null) would inadvertently clear progress/snapshot listeners.
            val field = CrewManager::class.java.getDeclaredField("toolFactory").apply { isAccessible = true }
            val original = field.get(manager) as CrewManager.ToolFactory
            field.set(manager, CrewManager.ToolFactory { bot, crew -> transform(original.create(bot, crew)) })
        }
        val key = UUID.randomUUID().toString()
        return WorkspaceRegistry(context).use { registry ->
            val prepared = registry.prepareAssignment(record.workspaceId, "assignment-$key", "request-$key",
                "Continue this workspace", role, mission, listOf("board_read"), "Memory continuity task", access)
            val started = registry.markStarted(record.workspaceId, prepared.assignmentId, prepared.revision, "fixture-$key")
            val bot = manager.spawnForMissionDeferred(prepared.missionId, "Continue this workspace", role,
                mission, listOf("board_read"), "Memory continuity task", access, null, started)
            val bound = registry.bindBot(record.workspaceId, prepared.assignmentId, started.revision, bot.id)
            manager.startPreparedForMission(bot)
            TaskRun(bot, manager, bound)
        }
    }
    fun runUnbound(record: WorkspaceRecord, provider: ModelProviderClient): CrewManager.Bot {
        val runtime = CoreAgentRuntime(context, record.executionSessionId, emptyList(), CorePromptBudget.standard(), provider)
        val manager = runtime.configureCrewManager(runtime.createTools(), CoreAgentModel(context, provider, record.executionSessionId), null)
        managers[record.executionSessionId] = manager
        return manager.spawn(CrewRoleTemplates.ANALYST, "Unbound workspace probe", listOf("board_read"), "Unbound task")
    }
    fun awaitDone(run: TaskRun) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (run.bot.status() in setOf(CrewManager.Status.QUEUED, CrewManager.Status.RUNNING, CrewManager.Status.WAITING) && System.nanoTime() < deadline)
            Thread.sleep(10)
        assertEquals("Worker error=${run.bot.error()}", CrewManager.Status.DONE, run.bot.status())
        run.bot.awaitTermination()
    }
    fun rows(sql: String, vararg args: Any?): List<List<String?>> = runBlocking {
        val database = db
        JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
            database.chatSql.query(sql, args).use { c -> buildList {
                while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) })
            } }
        } }
    }
    fun reopen() {
        stopManagers()
        val before = db
        val oldBridge = bridge
        ChatDatabaseBridge.closeInstanceForTests(files)
        assertFalse(before.isOpen)
        assertNull(ChatDatabaseBridge.findInstanceForFilesDirectory(files))
        assertNotSame(before, db)
        assertNotSame(oldBridge, bridge)
    }
    private fun stopManagers() {
        if (managers.isEmpty()) return
        val sessions = managers.keys.toSet()
        val field = CoreAgentRuntime::class.java.getDeclaredField("CREWS").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val map = field.get(null) as ConcurrentHashMap<String, CrewManager>
        managers.forEach { (session, manager) -> manager.close(); check(map.remove(session, manager)) }
        // Worker termination schedules genuine result/coordination receipts on a separate owner.
        // Let that real queue finish before closing Room; never disable or clear its pending work.
        fun deliveryField(name: String) = WorkspaceResultDelivery::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(null)
        val deliveryExecutor = deliveryField("executor") as ExecutorService
        val pendingLock = requireNotNull(deliveryField("pendingLock"))
        val pending = deliveryField("pending") as Map<*, *>
        val catalogPending = deliveryField("catalogPending") as java.util.concurrent.atomic.AtomicBoolean
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (true) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "Workspace receipt owner did not quiesce before database reopen" }
            val drained = deliveryExecutor.submit<Boolean> {
                synchronized(pendingLock) { sessions.none { pending.containsKey(it) } && !catalogPending.get() }
            }.get(remaining, TimeUnit.NANOSECONDS)
            if (drained) break
            // A dirty receipt may enqueue its continuation behind the first barrier.
        }
        managers.clear()
    }
    override fun close() {
        stopManagers()
        ChatDatabaseBridge.closeInstanceForTests(files)
        val edit = context.getSharedPreferences("provider_context_metadata", Context.MODE_PRIVATE).edit().clear()
        priorMetadata.forEach { (key, value) -> when (value) {
            is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value)
            is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value)
            is Float -> edit.putFloat(key, value)
        } }
        check(edit.commit())
    }
}

/** Scripted model only: verifies durable project-memory plumbing, not model relevance or physical Android restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
class WorkspaceMemoryContinuityTest {
    private val storage = ChatTestStore(bridgeExecutorFactory = { ChatTestStore.directExecutorService() })
    @get:Rule val rules: RuleChain = RuleChain.outerRule(storage).around(TestSecretStoreRule())
    private val fixtures = mutableListOf<WorkspaceMemoryFixture>()
    @After fun close() { fixtures.asReversed().forEach { it.close() } }
    private fun fixture() = WorkspaceMemoryFixture(storage).also { fixtures += it }
    private fun call(id: String, name: String, args: Map<String, Any>, text: String = "") =
        ModelReply(text, listOf(ModelReply.Call(id, name, args)))
    private fun receipt(text: String): String {
        check(text.startsWith("Persisted memory receipt: id=")) { text }
        return text.substringAfter("id=").substringBefore(';').trim()
    }
    private fun results(history: List<ConversationTurn>) = history.filter { it.kind == ConversationTurn.Kind.TOOL_RESULT }
    private fun hits(text: String): List<JSONObject> = JSONObject(text).getJSONArray("results").let { a ->
        (0 until a.length()).map(a::getJSONObject)
    }
    private fun entries(text: String): List<JSONObject> = org.json.JSONArray(text).let { a ->
        (0 until a.length()).map(a::getJSONObject)
    }
    private abstract class Provider : ModelProviderClient {
        override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>, tools: List<ToolSpec>,
                              sessionId: String, token: CancellationToken): ModelReply = error("Actual conversation request required")
    }

    @Test fun generalPurposeTaskKnowledgeSurvivesTwoRealCompactionsDeliveryReopenAndFreshAssignments() {
        val f = fixture()
        val project = f.createProject("Quartz accessibility application")
        val other = f.createProject("Unrelated private workspace")
        var otherStep = 0
        val unrelated = object : Provider() {
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply =
                if (otherStep++ == 0) call("other-note", "memory_save", mapOf("title" to "Orchid private decision",
                    "content" to "Unrelated orchid project uses linen covers.", "topic_key" to "orchid/decision"))
                else { receipt(results(history).last().content); ModelReply("Other project stored.", emptyList()) }
        }
        f.awaitDone(f.runTask(other, unrelated))
        val ordinary = WorkspaceStore(StorageGeneration(f.files), WorkspaceStore.projectIdForSession(project.executionSessionId),
            null, null, project.executionSessionId)
        val releaseBytes = "Quartz release v7; accessibility labels cobalt; fixture not an installable APK".toByteArray()
        ordinary.write("quartz-release.txt", String(releaseBytes, Charsets.UTF_8))
        val expectedHash = com.jarvys.agent.coding.ArtifactSnapshotIO.hex(java.security.MessageDigest.getInstance("SHA-256").digest(releaseBytes))
        CrewBoard(WorkspaceStore.forCrewBoard(f.context, project.executionSessionId)).post("/board/background.md", "Background fixture material. ".repeat(600))
        var decisionId: String? = null
        var deliveryReceipt: JSONObject? = null
        var summaries = 0
        var fillerCalls = 0
        var step = 0
        val observedPrompts = mutableListOf<String>()
        val summaryTexts = mutableListOf<String>()
        val original = "Quartz decision: amber labels."
        val corrected = "Quartz decision: cobalt labels replace amber labels."
        val requirement = "Quartz requirement: support large text without horizontal scrolling."
        val writer = object : Provider() {
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply {
                if (systemPrompt.startsWith("Summarize the supplied Crew transcript")) {
                    summaries++
                    val text = listOf("Objective", "Constraints", "Decisions", "Files and revisions", "Verification completed",
                        "Verification pending", "Blockers", "Active work").joinToString("\n") { "$it\nNone known" }
                    summaryTexts += text
                    return ModelReply(text, emptyList())
                }
                observedPrompts += systemPrompt
                assertTrue(tools.map { it.name }.containsAll(listOf("memory_save", "memory_search", "memory_get", "memory_update")))
                assertFalse(tools.any { it.name == "user_profile_set" || it.name == "memory_list_projects" })
                assertEquals(project.executionSessionId, sessionId)
                val last = results(history).lastOrNull()?.content
                return when (step) {
                    0 -> { step++; call("requirement", "memory_save", mapOf("title" to "Quartz requirement", "content" to requirement, "topic_key" to "quartz/requirement")) }
                    1 -> { receipt(requireNotNull(last)); step++; call("decision", "memory_save", mapOf("title" to "Quartz decision", "content" to original, "topic_key" to "quartz/decision", "type" to "decision")) }
                    2 -> { decisionId = receipt(requireNotNull(last)); step++; call("correction", "memory_update", mapOf("id" to requireNotNull(decisionId), "expected_project" to project.executionSessionId, "content" to corrected)) }
                    3 -> {
                        if (fillerCalls == 0) assertEquals(decisionId, receipt(requireNotNull(last)))
                        if (summaries < 2) {
                            check(++fillerCalls <= 20) { "Two actual compactions were not reached" }
                            call("background-$fillerCalls", "board_read", mapOf("path" to "/board/background.md"),
                                "Routine background observation. ".repeat(650))
                        } else {
                            assertTrue(history.any { it.kind == ConversationTurn.Kind.COMPACTION_SUMMARY })
                            // Fixture orchestrates a separate Central invocation, not Crew self-delivery.
                            // Keep the real Central tool/coordinator/service authority and checksum checks.
                            val centralDelivery = CentralWorkspaceTool(f.central, "delivery-${UUID.randomUUID()}",
                                "Deliver the verified Quartz version seven artifact",
                                WorkspaceCoordinator(f.context, { error("Delivery must not spawn a task") }, {}))
                            val delivered = centralDelivery.execute(mapOf("action" to "deliver",
                                "workspace_id" to project.workspaceId, "path" to "quartz-release.txt",
                                "expected_sha256" to expectedHash, "filename" to "quartz-release-v7.txt"),
                                CancellationToken.cancellable())
                            check(delivered.success) { delivered.content }
                            deliveryReceipt = JSONObject(delivered.content).getJSONObject("data")
                            assertTrue(requireNotNull(deliveryReceipt).getBoolean("attached"))
                            step++
                            call("delivery", "memory_save", mapOf("title" to "Quartz delivery v7", "topic_key" to "quartz/delivery",
                                "content" to "Quartz last delivered version v7; receipt=${deliveryReceipt}"))
                        }
                    }
                    4 -> { receipt(requireNotNull(last)); step++; call("own-search", "memory_search", mapOf("query" to "Quartz")) }
                    5 -> { val ids = hits(requireNotNull(last)).map { it.getString("id") }; assertEquals(3, ids.size); step++
                        call("own-get", "memory_get", mapOf("ids" to ids)) }
                    else -> { val data = entries(requireNotNull(last)); assertEquals(3, data.size)
                        assertTrue(data.all { it.getString("project") == project.executionSessionId && it.getString("scope") == "project" })
                        ModelReply("Project requirements, correction and delivery recorded.", emptyList()) }
                }
            }
        }
        val first = f.runTask(project, writer, mission = "Produce the Quartz accessibility application and retain the rationale for future continuation.")
        f.awaitDone(first)
        assertTrue("At least two genuine summary requests", summaries >= 2)
        assertTrue(summaryTexts.none { it.contains("cobalt") || it.contains("horizontal scrolling") })
        assertTrue("Task is general-purpose, not Coding", first.bot.role.id == CrewRoleTemplates.ANALYST)
        assertEquals(CrewProfile.WorkspaceMode.LEGACY_CHAT, first.bot.role.workspaceMode)
        assertTrue(observedPrompts.any { it.contains("DURABLE WORKSPACE MEMORY") && it.contains("Before resuming work") && it.contains("proactively save") && it.contains("do not wait for the user") })
        assertEquals(listOf(listOf(corrected)), f.rows("SELECT text FROM memory_entries WHERE id=?", requireNotNull(decisionId)))
        assertTrue(f.rows("SELECT id FROM memory_revisions WHERE target_id=?", requireNotNull(decisionId)).isNotEmpty())
        assertEquals(1, f.rows("SELECT id FROM memory_entries WHERE project=?", other.executionSessionId).size)
        val attachment = f.store.readConversationTimeline(f.central).single { it.deliveredArtifact != null }.deliveredArtifact!!
        val deliveredId = attachment.id
        assertArrayEquals(releaseBytes, DeliveredArtifactStore(f.files).resolve(f.central, attachment).readBytes())
        ordinary.write("quartz-release.txt", "New unshipped source content")
        // Simulate 240 days of persisted-memory age, not device uptime or a shadow clock.
        // The subsequent real runtime still uses System.currentTimeMillis through the native bridge.
        val ageMillis = TimeUnit.DAYS.toMillis(240)
        val agedDecisionReview = runBlocking {
            val database = f.db
            database.withChatWriteTransaction {
                fun snapshot(table: String): List<Map<String, String?>> =
                    database.chatSql.query("SELECT * FROM $table ORDER BY id").use { cursor -> buildList {
                        while (cursor.moveToNext()) add((0 until cursor.columnCount).associate { index ->
                            cursor.getColumnName(index) to if (cursor.isNull(index)) null else cursor.getString(index)
                        })
                    } }
                val before = snapshot("memory_entries")
                val revisionsBefore = snapshot("memory_revisions")
                val owned = before.filter { it["project"] == project.executionSessionId }
                assertEquals(3, owned.size)
                val decision = owned.single { it["id"] == decisionId }
                val originalReview = requireNotNull(decision["review_after"])
                assertEquals("active", MemoryReview.state(originalReview, System.currentTimeMillis()))
                val expectedReview = MemoryReview.timestamp(
                    requireNotNull(MemoryReview.parseInstant(originalReview)).toEpochMilli() - ageMillis)
                val expected = before.map { row ->
                    if (row["project"] != project.executionSessionId) row else row.toMutableMap().apply {
                        for (column in listOf("created_at", "updated_at", "last_seen_at"))
                            row[column]?.let { put(column, (it.toLong() - ageMillis).toString()) }
                        row["review_after"]?.let {
                            put("review_after", MemoryReview.timestamp(
                                requireNotNull(MemoryReview.parseInstant(it)).toEpochMilli() - ageMillis))
                        }
                    }
                }
                for (row in expected.filter { it["project"] == project.executionSessionId }) {
                    database.chatSql.execSQL(
                        "UPDATE memory_entries SET created_at=?,updated_at=?,last_seen_at=?,review_after=? WHERE id=? AND project=?",
                        arrayOf(requireNotNull(row["created_at"]).toLong(), requireNotNull(row["updated_at"]).toLong(),
                            row["last_seen_at"]?.toLong(), row["review_after"], row["id"], project.executionSessionId))
                }
                // Every other column and every foreign row is conserved byte-for-byte as returned by SQL.
                assertEquals(expected, snapshot("memory_entries"))
                assertEquals(revisionsBefore, snapshot("memory_revisions"))
                assertEquals("needs_review", MemoryReview.state(expectedReview, System.currentTimeMillis()))
                expectedReview
            }
        }
        assertArrayEquals(releaseBytes, DeliveredArtifactStore(f.files).resolve(f.central, attachment).readBytes())
        val beforeRows = f.rows("SELECT id,project,scope,text,revision_count,created_at,updated_at,last_seen_at,review_after FROM memory_entries ORDER BY id")
        f.reopen()
        assertEquals(beforeRows, f.rows("SELECT id,project,scope,text,revision_count,created_at,updated_at,last_seen_at,review_after FROM memory_entries ORDER BY id"))

        // This new model receives no previous IDs, receipt or answer state. It learns them from actual new tool results.
        var centralStep = 0
        var discovered: String? = null
        var recovered: List<JSONObject> = emptyList()
        val centralProvider = object : Provider() {
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply {
                val last = results(history).lastOrNull()?.content
                return when (centralStep++) {
                    0 -> call("discover", "memory_list_projects", emptyMap())
                    1 -> { val projects = JSONObject(requireNotNull(last)).getJSONArray("projects")
                        val names = (0 until projects.length()).map { projects.getJSONObject(it).getString("name") }
                        assertEquals(2, names.count { it != f.central })
                        call("discover-search", "memory_search", mapOf("all_projects" to true, "query" to "Quartz")) }
                    2 -> { val found = hits(requireNotNull(last)); discovered = found.map { it.getString("project") }.distinct().single()
                        call("discover-get", "memory_get", mapOf("ids" to found.map { it.getString("id") })) }
                    else -> { recovered = entries(requireNotNull(last)); ModelReply("Recovered ${recovered.joinToString { it.getString("content") }}", emptyList()) }
                }
            }
        }
        val centralOwner = f.store.appendConversationMessage(f.central, "user", "Continue the Quartz application from the previous delivery.")
        val centralRuntime = CoreAgentRuntime(f.context, f.central, emptyList(), CorePromptBudget.standard(), centralProvider)
        centralRuntime.setMainChatSteeringCursor(centralOwner); centralRuntime.setCurrentUserMessageId(centralOwner)
        centralRuntime.setCurrentTurnOwnerMessageId(centralOwner)
        val centralResult = centralRuntime.runMainChat("Continue Quartz", f.store.loadConversationContext(f.central), emptyList(), CancellationToken.cancellable(), null)
        assertEquals("COMPLETED", centralResult.outcome)
        assertEquals(project.executionSessionId, discovered)
        assertTrue(recovered.any { it.getString("content") == requirement })
        assertTrue(recovered.any { it.getString("content") == corrected })
        assertTrue(recovered.any { it.getString("content").contains(expectedHash) && it.getString("content").contains("v7") })
        fun assertAgedDecision(entries: List<JSONObject>) {
            val retained = entries.single { it.getString("topic_key") == "quartz/decision" }
            assertEquals(decisionId, retained.getString("id"))
            assertEquals(corrected, retained.getString("content"))
            assertEquals(agedDecisionReview, retained.getString("review_after"))
            // This is the actual tool response's canonical real-clock review state, not a fixture claim.
            assertEquals("needs_review", retained.getString("state"))
        }
        assertAgedDecision(recovered)

        var resumedStep = 0
        var resumedCycle = 0
        var taskRecovered: List<JSONObject> = emptyList()
        val resumed = object : Provider() {
            override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
                images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply {
                if (resumedCycle == 0 && resumedStep == 0) {
                    val oldCalls = setOf("requirement", "decision", "correction", "delivery", "own-search", "own-get")
                    assertFalse("Fresh mission must not receive the old writer transcript", history.any { turn ->
                        turn.toolCalls.any { it.id in oldCalls || it.id.startsWith("background-") } ||
                            turn.toolCallId in oldCalls || turn.toolCallId.startsWith("background-") ||
                            turn.kind == ConversationTurn.Kind.COMPACTION_SUMMARY
                    })
                }
                val last = results(history).lastOrNull()?.content
                return when (resumedStep++) {
                    0 -> call("fresh-search-$resumedCycle", "memory_search", mapOf("query" to "Quartz"))
                    1 -> call("fresh-get-$resumedCycle", "memory_get", mapOf("ids" to hits(requireNotNull(last)).map { it.getString("id") }))
                    else -> { taskRecovered = entries(requireNotNull(last)); ModelReply("Retained project knowledge inspected before new work.", emptyList()) }
                }
            }
        }
        // Host orchestration resolves the namespace returned by the real Central tool chain;
        // it does not reuse a pre-reopen workspace object or infer authority from text.
        val recoveredProject = WorkspaceRegistry(f.context).use { registry ->
            requireNotNull(registry.getWorkspace(WorkspaceIdentity.workspaceId(requireNotNull(discovered))))
        }
        val fresh = f.runTask(recoveredProject, resumed, mission = "Resume Quartz from its retained project knowledge; inspect before changing anything.")
        f.awaitDone(fresh)
        assertNotEquals(first.bot.id, fresh.bot.id)
        assertNotEquals(first.assignment.assignmentId, fresh.assignment.assignmentId)
        assertEquals(recovered.map { it.getString("id") }.toSet(), taskRecovered.map { it.getString("id") }.toSet())
        assertAgedDecision(taskRecovered)
        assertTrue(taskRecovered.all { it.getString("project") == project.executionSessionId })
        // A legitimate follow-up uses the same bot with a NEW cycle/token, never its historical authority.
        val previousCycle = fresh.bot.cycleId()
        val previousToken = fresh.bot.token
        resumedStep = 0
        resumedCycle++
        taskRecovered = emptyList()
        val coordinator = WorkspaceCoordinator(f.context, { session ->
            assertEquals(recoveredProject.executionSessionId, session); fresh.manager
        }, {})
        val followupTool = CentralWorkspaceTool(f.central, "followup-${UUID.randomUUID()}", "Continue the existing task", coordinator)
        val followup = followupTool.execute(mapOf("action" to "message", "workspace_id" to recoveredProject.workspaceId,
            "assignment_id" to fresh.assignment.assignmentId, "bot_id" to fresh.bot.id,
            "expected_assignment_revision" to fresh.assignment.revision, "key" to "followup-${UUID.randomUUID()}",
            "text" to "Inspect the saved Quartz requirements again before continuing this same assignment."), CancellationToken.cancellable())
        assertTrue(followup.content, followup.success)
        val cycleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (fresh.bot.cycleId() == previousCycle && System.nanoTime() < cycleDeadline) Thread.sleep(10)
        assertNotEquals(previousCycle, fresh.bot.cycleId())
        f.awaitDone(fresh)
        assertNotSame(previousToken, fresh.bot.token)
        assertEquals(recovered.map { it.getString("id") }.toSet(), taskRecovered.map { it.getString("id") }.toSet())
        assertAgedDecision(taskRecovered)
        val recoveredDelivery = taskRecovered.single { it.getString("topic_key") == "quartz/delivery" }.getString("content")
        assertTrue(recoveredDelivery.contains("last delivered version v7"))
        val recoveredReceipt = JSONObject(recoveredDelivery.substringAfter("receipt="))
        val recoveredArtifactId = recoveredReceipt.getString("artifact_id").removePrefix("delivered:")
        val recoveredHash = recoveredReceipt.getString("sha256")
        val restoredAttachment = f.store.readConversationTimeline(f.central).single { it.deliveredArtifact?.id == recoveredArtifactId }.deliveredArtifact!!
        assertEquals(deliveredId, recoveredArtifactId)
        assertEquals(expectedHash, recoveredHash)
        assertArrayEquals(releaseBytes, DeliveredArtifactStore(f.files).resolve(f.central, restoredAttachment).readBytes())
        assertEquals(recoveredHash, com.jarvys.agent.coding.ArtifactSnapshotIO.hex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(DeliveredArtifactStore(f.files).resolve(f.central, restoredAttachment).readBytes())))
    }
}
