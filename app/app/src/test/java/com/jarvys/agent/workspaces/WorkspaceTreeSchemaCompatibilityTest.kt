package com.jarvys.agent.workspaces

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Fresh reconstruction tests. Prior lost-source test results do not validate these bytes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [com.jarvys.agent.ArtifactOsShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceTreeSchemaCompatibilityTest {
    @get:Rule val chat = com.jarvys.agent.ChatTestStore(registerApplicationFilesDirectory = true)
    private lateinit var context: Application
    private val opened = ArrayList<WorkspaceRegistry>()
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME) }
    @After fun cleanup() { opened.forEach { it.close() }; context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME) }
    private fun open() = WorkspaceRegistry(context).also { opened.add(it) }
    private fun raw() = SQLiteDatabase.openDatabase(context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READWRITE)
    private fun fixture(resource: String = "/workspace_registry_v98_v1.json"): List<String> {
        val text = javaClass.getResourceAsStream(resource)!!.bufferedReader().use { it.readText() }
        val statements = JSONObject(text).getJSONArray("statements")
        return (0 until statements.length()).map { statements.getString(it) }
    }
    private fun rows(db: SQLiteDatabase, table: String): List<List<String>> {
        val columns=db.rawQuery("PRAGMA table_info($table)",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(1)) } }
        val expressions=columns.joinToString(",") { "typeof($it),hex($it)" }
        return db.rawQuery("SELECT $expressions FROM $table ORDER BY 1," + columns.first(), null).use { c ->
            buildList { while(c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
        }
    }
    private fun doctorOwners(): Set<String> {
        // Robolectric's actual database path is authoritative for this isolated fixture.
        val info=context.applicationInfo; val old=info.dataDir
        info.dataDir=context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).parentFile!!.parentFile!!.absolutePath
        try { return WorkspaceRegistry.observeForDoctor(context,com.jarvys.agent.CancellationToken.cancellable()) { it.validateSchema(); it.owners() } }
        finally { info.dataDir=old }
    }
    private fun exportProjects(): List<String> {
        val bridge=com.jarvys.agent.chatdb.ChatDatabaseBridge.getInstance(context)
        val output=java.io.ByteArrayOutputStream()
        val snapshot=bridge.exportMemorySnapshot(com.jarvys.agent.MemoryExportOptions(),"2026-10-31T00:00:00.000Z",
            com.jarvys.agent.CancellationToken.cancellable()) { output }
        assertTrue(output.size()>0)
        return snapshot.projects
    }
    @Test fun frozenV98DdlMatchesNormalOpenWithoutUpgrade() {
        val registry = open(); registry.createWorkspace("v1", "Retained current workspace")
        raw().use { actual ->
            SQLiteDatabase.create(null).use { expected ->
                fixture().forEach(expected::execSQL)
                fun ddl(db: SQLiteDatabase) = db.rawQuery("SELECT sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY type,name", null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
                assertEquals(ddl(expected),ddl(actual))
            }
            assertEquals(1, actual.version)
        }
        assertEquals(1,open().schemaVersion())
    }
    @Test fun explicitUpgradePreservesRowsAndCachedReadersReopenAtActualVersion() {
        val original = open(); val other = open()
        val workspace = original.createWorkspace("populated", "Retained workspace")
        val prepared = original.prepareAssignment(workspace.workspaceId,"assignment","request","original task","generalist","mission",null,null,com.jarvys.agent.crew.CrewMissionAccess.STANDARD)
        val started = original.markStarted(workspace.workspaceId,prepared.assignmentId,prepared.revision,"fixture-process")
        val bound = original.bindBot(workspace.workspaceId,started.assignmentId,started.revision,"fixture-bot")
        original.nativeControls().submit("fixture-control",workspace.workspaceId,bound.assignmentId,"fixture-bot",bound.revision,WorkspaceControlType.CORRECT,"retained text")
        original.markRead(workspace.workspaceId,bound.assignmentId,"a".repeat(64))
        // Conservation includes undecodable historical reference bytes, never authority/adoption.
        // SQL hex comparison avoids Java UTF decoding normalizing lone-surrogate UTF8 bytes.
        raw().use { it.execSQL("UPDATE assignment SET source_batch='',source_call=char(0),source_run=CAST(x'EDA080' AS TEXT) WHERE assignment_id=?",arrayOf(bound.assignmentId)) }
        val names = listOf("workspace","assignment","user_control","central_read_receipt")
        val before = raw().use { db -> names.associateWith { rows(db,it) } }
        assertEquals(1,other.schemaVersion())
        val oldDoctor=doctorOwners()
        val oldExport=exportProjects()
        val info=context.applicationInfo; val oldDataDir=info.dataDir
        info.dataDir=context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).parentFile!!.parentFile!!.absolutePath
        try {
            WorkspaceRegistry.observeForDoctor(context,com.jarvys.agent.CancellationToken.cancellable()) { cached ->
                cached.validateSchema(); assertEquals(oldDoctor,cached.owners())
                original.upgradeTreeSchema()
                cached.validateSchema(); assertEquals(oldDoctor,cached.owners())
            }
        } finally { info.dataDir=oldDataDir }
        assertEquals(fixture("/workspace_tree_schema_v2.json"),WorkspaceTreeSchema.CREATE_SQL)
        original.openForBootstrap()
        assertEquals(oldDoctor,doctorOwners())
        assertEquals(oldExport,exportProjects())
        raw().use { db -> listOf("bot_tree_root","bot_tree_node","bot_tree_event","bot_tree_wait").forEach { assertTrue(rows(db,it).isEmpty()) } }
        assertEquals(2,original.schemaVersion()); assertEquals(2,other.schemaVersion())
        other.close(); assertEquals(2,other.schemaVersion())
        assertEquals(2,open().schemaVersion())
        raw().use { db -> assertEquals(before,names.associateWith { rows(db,it) }) }
        original.upgradeTreeSchema()
        assertEquals(workspace,original.getWorkspace(workspace.workspaceId))
        original.createWorkspace("after-upgrade", "Normal writer remains usable")
        assertEquals(2,WorkspaceRegistry.preflightFreshStorage(context))
    }
    @Test fun partialNativeDdlTransactionsRollbackAtEveryBoundary() {
        open().createWorkspace("rollback","Retained workspace")
        val original = raw().use { rows(it,"workspace") }
        for (boundary in 1..WorkspaceTreeSchema.CREATE_SQL.size) {
            raw().use { db ->
                db.beginTransaction()
                try { WorkspaceTreeSchema.CREATE_SQL.take(boundary).forEach(db::execSQL); db.version=2 }
                finally { db.endTransaction() } // No success: models process failure before commit.
                assertEquals(1,db.version); assertEquals(original,rows(db,"workspace"))
            }
            assertEquals(1,WorkspaceRegistry.preflightFreshStorage(context))
        }
    }
    @Test fun actualSqliteFullDuringUpgradeRollsBackVersionAndEveryOldRow() {
        val registry=open(); registry.createWorkspace("disk-full","Retained workspace")
        val original=raw().use { rows(it,"workspace") }
        registry.treeRead { db ->
            val pages=db.rawQuery("PRAGMA page_count",null).use { it.moveToFirst(); it.getLong(0) }
            db.rawQuery("PRAGMA max_page_count=$pages",null).use { assertTrue(it.moveToFirst()); assertEquals(pages,it.getLong(0)) }
        }
        val error=runCatching { registry.upgradeTreeSchema() }.exceptionOrNull()
        assertNotNull("Native SQLite must fail, not synthetic rollback",error)
        assertTrue(generateSequence(error) { it.cause }.any { it is android.database.sqlite.SQLiteFullException })
        assertEquals(1,registry.schemaVersion())
        raw().use { assertEquals(original,rows(it,"workspace")) }
        assertEquals(1,WorkspaceRegistry.preflightFreshStorage(context))
        registry.treeRead { db -> db.rawQuery("PRAGMA max_page_count=2147483646",null).use { assertTrue(it.moveToFirst()) } }
        registry.upgradeTreeSchema(); assertEquals(2,registry.schemaVersion())
    }
    @Test fun unknownVersionAndExtraDdlRemainUntouched() {
        open().createWorkspace("unknown-version","Retained workspace"); opened.forEach { it.close() }
        raw().use { it.version=9 }
        val file=context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME); val before=file.readBytes()
        assertNotNull(runCatching { WorkspaceRegistry.preflightFreshStorage(context) }.exceptionOrNull())
        assertArrayEquals(before,file.readBytes())
        raw().use { it.version=1; it.execSQL("CREATE TABLE unauthorized_extension (value TEXT)") }
        val extra=file.readBytes()
        assertNotNull(runCatching { WorkspaceRegistry.preflightFreshStorage(context) }.exceptionOrNull())
        assertArrayEquals(extra,file.readBytes())
    }
    @Test fun unknownOrPartialSchemasAreRejectedWithoutRepair() {
        open().createWorkspace("malformed","Retained workspace"); opened.forEach { it.close() }
        raw().use { db -> db.execSQL(WorkspaceTreeSchema.CREATE_SQL.first()) }
        val file=context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME)
        val before=file.readBytes()
        assertNotNull(runCatching { WorkspaceRegistry.preflightFreshStorage(context) }.exceptionOrNull())
        assertArrayEquals(before,file.readBytes())
    }
}
