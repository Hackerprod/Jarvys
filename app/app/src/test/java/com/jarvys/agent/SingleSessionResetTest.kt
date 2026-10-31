package com.jarvys.agent

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.jarvys.agent.chatdb.*
import com.jarvys.agent.coding.ProjectScopeStore
import com.jarvys.agent.workspaces.WorkspaceIdentity
import com.jarvys.agent.workspaces.WorkspaceRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class, FileProviderPathShadow::class], instrumentedPackages = ["androidx.core.content"])
class SingleSessionResetTest {
    @get:Rule val storeRule = ChatTestStore()
    @get:Rule val secretStore = TestSecretStoreRule()
    private val contexts = mutableListOf<Context>()
    private val central = WorkspaceIdentity.CENTRAL_SESSION

    private fun context(applicationFiles: Boolean = false): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val files = if (applicationFiles) app.filesDir else storeRule.newFilesDirectory()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getDatabasePath(name: String): File = (if (File(name).isAbsolute) File(name) else File(files, "databases/$name")).also { it.parentFile!!.mkdirs() }
        }
        contexts += context
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        return context
    }

    @After fun close() {
        contexts.forEach { ChatDatabaseBridge.closeInstanceForTests(it.filesDir) }
        ArtifactOsShadow.reset()
    }

    @Test fun B9_eachCommittedPhaseResumesWithoutImportingAndMemorySeedsOnlyOnFirstUse() = runBlocking {
        for (cut in listOf("created", "registry_created", "invalidated", "done")) {
            val context = context()
            val old = File(context.filesDir, "jarvys/jarvys-chat.db").also { it.parentFile!!.mkdirs() }
            SQLiteDatabase.openOrCreateDatabase(old, null).use {
                it.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY)")
                it.execSQL("INSERT INTO conversations VALUES('old-chat')")
                it.execSQL("CREATE TABLE memory_entries(text TEXT)")
                it.execSQL("INSERT INTO memory_entries VALUES('old personal memory')")
            }
            val oldBytes = old.readBytes()
            val oldMemory = File(context.filesDir, "jarvys/memory/old.txt").also { it.parentFile!!.mkdirs(); it.writeText("old") }
            val settings = context.getSharedPreferences("jarvys_chat", 0)
            settings.edit().putString("active_session_id", "old-chat").putBoolean("chat_without_memory_old-chat", true)
                .putString("crew_mode", "AUTO").commit()
            context.getSharedPreferences("jarvys_memory", 0).edit().putBoolean("memory_enabled", false).commit()
            try {
                SingleSessionReset.prepare(context) { if (it == cut) throw InterruptedException("process death") }
                fail("Expected interruption")
            } catch (_: InterruptedException) { }
            JarvysChatDatabaseProvider.closeDatabaseFileForTests(JarvysChatDatabaseProvider.getInstance(context))
            SingleSessionReset.ensure(context)
            assertEquals("done", SingleSessionReset.readState(context.filesDir).getString("phase"))
            assertArrayEquals(oldBytes, old.readBytes())
            assertEquals("old", oldMemory.readText())
            val database = JarvysChatDatabaseProvider.getInstance(context)
            JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
                database.chatSql.query("SELECT id,kind FROM conversations").use {
                    assertTrue(it.moveToFirst()); assertEquals(central, it.getString(0)); assertEquals("central", it.getString(1)); assertFalse(it.moveToNext())
                }
                for (table in listOf("tombstones", "agent_profiles", "agent_docs", "user_directives", "memory_entries", "memory_revisions", "agent_session_state", "memory_jobs")) {
                    database.chatSql.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(table, 0, it.getInt(0)) }
                }
            } }
            WorkspaceRegistry(context).use { assertTrue(it.listWorkspaces().isEmpty()) }
            assertFalse(settings.contains("active_session_id")); assertFalse(settings.contains("chat_without_memory_old-chat"))
            assertEquals("AUTO", settings.getString("crew_mode", null))
            assertFalse(context.getSharedPreferences("jarvys_memory", 0).getBoolean("memory_enabled", true))
            assertTrue(ConversationRepository(database).deleteConversation(central) is ChatDbResult.Invalid)
            try { ConversationMetadataAccess(context.filesDir).update(central, "deleted", true); fail("Central deleted") }
            catch (_: IllegalArgumentException) { }
            PersonalAgentRepository(database).browse()
            JarvysChatDatabaseProvider.onQueryExecutor(database) { database.withChatReadTransaction {
                database.chatSql.query("SELECT bootstrap_completed FROM agent_profiles WHERE id='default'").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                database.chatSql.query("SELECT COUNT(*) FROM agent_docs").use { it.moveToFirst(); assertTrue(it.getInt(0) > 0) }
            } }
        }
    }

    @Test fun B9_retirementKeepsNewFilesAndInvalidatesOldNotificationTokens() {
        val context = context(applicationFiles = true)
        val oldFiles = listOf(
            "attachments/old-chat/old.txt", "delivered/old-chat/old.txt",
            "delivered/old-chat/thumbnails/old.png", "generated/old-chat/old.png",
            "crew-context/old-chat/context.txt", "crew-context/old-chat/checkpoints/checkpoint.json",
            "workspaces/old-project/old.txt", "workspace-inspections/old.txt",
            "coding-projects/old-project/root/old.txt", "coding-project-state/old-project/identity.json",
            "runs/old-run/old.txt", "notes/old.txt", "memory/old.txt", "index/old.txt",
            "download_results/old.json",
        ).map { path ->
            File(context.filesDir, "jarvys/$path").also { it.parentFile!!.mkdirs(); it.writeText("old") }
        }
        val preservedFiles = listOf("jarvys/tasks/tasks.jsonl", "skills/reset-fixture/SKILL.md").map { path ->
            File(context.filesDir, path).also { it.parentFile!!.mkdirs(); it.writeText("preserved") }
        }
        val preservedPreferences = listOf("jarvys_provider_settings", "jarvys_connectors", "jarvys_proactive_preferences")
            .map { context.getSharedPreferences(it, 0).also { preferences -> preferences.edit().putString("reset-fixture", "preserved").commit() } }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("reset-test", "Reset", NotificationManager.IMPORTANCE_DEFAULT))
        val oldIntent = PendingIntent.getBroadcast(context, 1, Intent("old-reset-action"), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1, Notification.Builder(context, "reset-test").setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(oldIntent).build())
        SingleSessionReset.ensure(context)
        assertTrue(manager.activeNotifications.isEmpty())
        try { oldIntent.send(); fail("Old PendingIntent survived") } catch (_: PendingIntent.CanceledException) { }
        assertFalse(SingleSessionReset.isCurrent(Intent("old-reset-action")))
        assertTrue(SingleSessionReset.isCurrent(SingleSessionReset.stamp(Intent("new-reset-action"))))
        val storage = StorageGeneration(context)
        val module = com.jarvys.agent.modules.ModuleManifest(1, "reset-fixture", "1", "Reset", emptyList(), emptyList(),
            emptyList(), setOf("workspace.write"), "{}")
        val moduleAccess = ConversationModuleCapabilityAccess(context, central, module, CancellationToken.uncancellable(), null)
        moduleAccess.writeWorkspace("module.txt", "first module write")
        assertEquals("first module write", moduleAccess.readWorkspace("module.txt"))
        val attachmentStore = AttachmentStore(context)
        val attachment = attachmentStore.copyFromStream(central, "first.txt", "text/plain", ChatAttachment.Kind.FILE,
            ByteArrayInputStream("first attachment".toByteArray()))
        val workspace = WorkspaceStore(context, WorkspaceStore.projectIdForSession(central), central, false)
        workspace.write("index.html", "<!doctype html><title>First</title><p>First snapshot</p>")
        val ledger = LocalRunStore(context)
        val userId = ledger.appendConversationMessage(central, "user", "First request")
        val artifacts = DeliveredArtifactStore(context)
        val result = DeliverFileTool(central, workspace, artifacts, ledger).execute(mapOf("path" to "index.html"), CancellationToken.uncancellable())
        assertTrue(result.content, result.success)
        ledger.appendConversationToolPresentation(central, userId, "Deliver File", "tool_result", "reset-delivery",
            result.content, result.previewId, "Attach local file")
        val delivered = ledger.readConversationTimeline(central).first { it.deliveredArtifact != null }.deliveredArtifact!!
        val descriptor = artifacts.previewMetadataForAttachment(central, delivered)
        val scope = ProjectScopeStore(context.filesDir).open(central)
        val identity = scope.durableIdentity()
        File(scope.rootDirectory(), "first.txt").writeText("first project")
        val generated = GeneratedImageStore(context)
        val imageId = "11111111-1111-1111-1111-111111111111"
        generated.save(central, imageId, byteArrayOf(137.toByte(),80,78,71,13,10,26,10,0))
        val image = generated.resolve(central, "$imageId.png")
        assertTrue(image.canonicalPath.startsWith(storage.chatRoot().canonicalPath + File.separator))
        val provider = GeneratedImageFileProvider()
        provider.attachInfo(context, requireNotNull(context.packageManager.resolveContentProvider(context.packageName + ".generated-images", android.content.pm.PackageManager.GET_META_DATA)))
        val share = GeneratedImageIntents.shareIntent(context, image)
        val uri = share.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        provider.openFile(uri, "r").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { assertArrayEquals(image.readBytes(), it.readBytes()) }
        }
        oldFiles.forEach { assertEquals("old", it.readText()) }
        SingleSessionReset.onSuccessfulRun(context, central)
        assertEquals("retired", SingleSessionReset.readState(context.filesDir).getString("phase"))
        oldFiles.forEach { assertFalse(it.exists()) }
        preservedFiles.forEach { assertEquals("preserved", it.readText()) }
        preservedPreferences.forEach { assertEquals("preserved", it.getString("reset-fixture", null)) }
        assertEquals("first attachment", attachmentStore.resolve(central, attachment).readText())
        assertEquals(identity, ProjectScopeStore(context.filesDir).open(central).durableIdentity())
        assertEquals("first project", File(scope.rootDirectory(), "first.txt").readText())
        assertTrue(DeliveredArtifactStore(context).resolvePreview(central, descriptor.token).token == descriptor.token)
        val restored = LocalRunStore(context).readConversationTimeline(central)
        assertTrue(restored.any { it.deliveredArtifact?.id == delivered.id })
        assertEquals(descriptor.token, restored.single { it.toolCallId == "reset-delivery" }.previewId)
        assertEquals("first module write", moduleAccess.readWorkspace("module.txt"))
        assertTrue(image.exists())
    }
}
