package com.jarvys.agent

import android.app.Application
import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.FileProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.jarvys.agent.apkfactory.FactoryInstallApplication
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.skills.SkillRepository
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class, FileProviderPathShadow::class],
    instrumentedPackages = ["androidx.core.content"])
@LooperMode(LooperMode.Mode.PAUSED)
class SingleSessionStartupTest {
    @get:Rule val storeRule = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    @get:Rule val secretStore = TestSecretStoreRule()

    @Before fun forgetSandboxStaticsOfPreviousTests() {
        // FileProvider caches path strategies per authority for the whole sandbox; every test has its own data directory.
        (androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .get(null) as MutableMap<*, *>).clear()
        // The default ViewModel factory keeps the first test's Application; attachment files would land in its data directory.
        androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }

    @After fun restoreStartupExecutor() { SingleSessionReset.executorForTests = null }

    @Test fun B9_productiveStartupReturnsBeforeResetAndEarlyActivityWaitsEvenWithCorruptOldDatabase() {
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val oldDatabase = File(context.filesDir, "jarvys/jarvys-chat.db").also {
            it.parentFile!!.mkdirs()
            it.writeText("This is not a SQLite database")
        }
        val oldBytes = oldDatabase.readBytes()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("reset-test", "Reset", NotificationManager.IMPORTANCE_DEFAULT))
        val oldToken = PendingIntent.getBroadcast(context, 1, Intent("old-reset-action"), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1, Notification.Builder(context, "reset-test").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(oldToken).build())
        val image = File(context.filesDir, "jarvys/g3/generated/early.png").also {
            it.parentFile!!.mkdirs(); it.writeText("image from an interrupted reset")
        }
        val authority = context.packageName + ".generated-images"
        val provider = GeneratedImageFileProvider().apply {
            attachInfo(context, requireNotNull(context.packageManager.resolveContentProvider(authority,
                android.content.pm.PackageManager.GET_META_DATA)))
        }
        val uri = FileProvider.getUriForFile(context, authority, image)
        val application = Instrumentation.newApplication(FactoryInstallApplication::class.java, context)
        assertFalse("The barrier exists before Android initializes providers", SingleSessionReset.isReady(context))
        try { StorageGeneration(context); fail("A provider admitted storage before Application.onCreate") }
        catch (_: IllegalStateException) { }
        try { provider.query(uri, null, null, null, null); fail("A shared image bypassed the startup barrier") }
        catch (_: IllegalStateException) { }
        var reset: Runnable? = null
        SingleSessionReset.executorForTests = Executor { reset = it }
        application.onCreate()
        assertNotNull("Application startup queued reset without waiting for it", reset)
        assertFalse(SingleSessionReset.isReady(context))
        assertFalse(File(context.filesDir, "jarvys/jarvys-chat-v3.db").exists())

        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val storeField = MainActivity::class.java.getDeclaredField("localRunStore").apply { isAccessible = true }
        try {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertNull("The early Activity cannot construct conversation stores", storeField.get(controller.get()))
            assertFalse(SingleSessionReset.isReady(context))
            // The held executor is the ordering oracle: no elapsed-time threshold is involved.
            storeRule.onDatabaseWorker {
                requireNotNull(reset).run()
                SingleSessionReset.awaitReadyBlocking(context.filesDir)
            }
            assertEquals("done", SingleSessionReset.readState(context.filesDir).getString("phase"))
            assertArrayEquals(oldBytes, oldDatabase.readBytes())
            provider.query(uri, null, null, null, null).use { assertEquals(1, it.count) }
            assertTrue(manager.activeNotifications.isEmpty())
            try { oldToken.send(); fail("Old PendingIntent survived corrupt-database reset") }
            catch (_: PendingIntent.CanceledException) { }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertNotNull("The waiting Activity is admitted after reset completes", storeField.get(controller.get()))
            storeRule.onDatabaseWorker {
                val bridge = ChatDatabaseBridge.getInstance(context)
                val central = requireNotNull(bridge.readConversationSync(WorkspaceIdentity.CENTRAL_SESSION))
                assertEquals("central", central.kind)
                assertEquals(0L, central.message_count)
            }
        } finally {
            controller.destroy()
        }
    }

    @Test fun photosResultAndRestoredDraftSurviveRecreationWhileResetIsHeld() {
        attachmentResultSurvivesStartup(photos = true)
    }

    @Test fun documentsResultAndRestoredDraftSurviveRecreationWhileResetIsHeld() {
        attachmentResultSurvivesStartup(photos = false)
    }

    private fun attachmentResultSurvivesStartup(photos: Boolean) {
        val reset = startWithHeldReset()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = pickerFile(if (photos) "pending.png" else "pending.txt", "pending picker bytes")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".generated-images", source)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(restoredState()).start().resume()
        try {
            // A second recreation before delivery must preserve the original draft and picker owner.
            controller.recreate()
            assertRestoredState(controller.get())
            val input: Any = if (photos) PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                else arrayOf("*/*")
            val launcher = if (photos) "attachmentPhotosLauncher" else "attachmentFilesLauncher"
            controller.pause().stop()
            deliverResult(controller.get(), launcher, input, Intent().setData(uri))
            assertNull(field(controller.get(), "attachmentDrafts"))
            // ON_START delivers the actual result buffered by ActivityResultRegistry.
            controller.restart().start().resume()
            controller.recreate()
            assertRestoredState(controller.get())
            assertNull(field(controller.get(), "attachmentDrafts"))
            assertFalse(SingleSessionReset.isReady(context))
            releaseReset(reset)
            val drafts = field(controller.get(), "attachmentDrafts") as AttachmentDraftViewModel
            awaitExecutor(drafts, "attachmentIo")
            val attachment = requireNotNull(drafts.drafts.value.single().attachment)
            assertEquals(if (photos) ChatAttachment.Kind.IMAGE else ChatAttachment.Kind.FILE, attachment.kind)
            assertEquals("pending picker bytes", AttachmentStore(context)
                .resolve(WorkspaceIdentity.CENTRAL_SESSION, attachment).readText())
            assertNull(field(controller.get(), "attachmentPickerSession"))
            assertEquals("draft before reset", savedState(controller.get()).getString("agent_goal_input"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun skillResultWaitsForRepositoryAndSurvivesRecreationWhileResetIsHeld() {
        val reset = startWithHeldReset()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = pickerFile("pending.md", """
            ---
            id: startup-pending-skill
            name: Pending skill
            description: Imported after storage starts
            version: 1
            allowed-tools: []
            tags: []
            ---
            Keep the requested draft.
        """.trimIndent())
        val uri = FileProvider.getUriForFile(context, context.packageName + ".generated-images", source)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(restoredState())
        try {
            deliverResult(controller.get(), "skillImportLauncher", arrayOf("text/markdown"), Intent().setData(uri))
            controller.start().resume()
            assertNull(field(controller.get(), "skillRepository"))
            controller.recreate()
            assertRestoredState(controller.get())
            assertNull(field(controller.get(), "skillRepository"))
            val snapshot = savedState(controller.get())
            assertEquals(listOf(uri), snapshot.getParcelableArrayList<Uri>("pending_skill_imports"))
            releaseReset(reset)
            awaitExecutor(controller.get(), "skillImportExecutor")
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, SkillRepository.get(context).skills.value.count { it.metadata.id == "startup-pending-skill" })
            assertTrue(savedState(controller.get()).getParcelableArrayList<Uri>("pending_skill_imports").orEmpty().isEmpty())
            assertEquals("Saving a snapshot must not share the mutable pending queue", listOf(uri),
                snapshot.getParcelableArrayList<Uri>("pending_skill_imports"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun cameraResultAndDestinationSurviveRecreationWhileResetIsHeld() {
        val reset = startWithHeldReset()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = pickerFile("capture.jpg", "pending camera bytes")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".generated-images", source)
        val state = restoredState().apply { putString("attachment_camera_path", source.path) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(state)
        try {
            deliverResult(controller.get(), "attachmentCameraLauncher", uri, Intent())
            controller.start().resume()
            controller.recreate()
            assertNull(field(controller.get(), "attachmentDrafts"))
            assertTrue(source.exists())
            releaseReset(reset)
            val drafts = field(controller.get(), "attachmentDrafts") as AttachmentDraftViewModel
            awaitExecutor(drafts, "attachmentIo")
            val attachment = requireNotNull(drafts.drafts.value.single().attachment)
            assertEquals(ChatAttachment.Kind.IMAGE, attachment.kind)
            assertEquals("pending camera bytes", AttachmentStore(context)
                .resolve(WorkspaceIdentity.CENTRAL_SESSION, attachment).readText())
            assertNull(drafts.cameraFile)
            assertFalse(source.exists())
        } finally { controller.pause().stop().destroy() }
    }

    private fun startWithHeldReset(): Runnable {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val application = Instrumentation.newApplication(FactoryInstallApplication::class.java, context)
        var reset: Runnable? = null
        SingleSessionReset.executorForTests = Executor { reset = it }
        application.onCreate()
        assertNotNull("Production Application must schedule the first reset", reset)
        assertFalse(SingleSessionReset.isReady(context))
        return requireNotNull(reset)
    }

    private fun releaseReset(reset: Runnable) {
        storeRule.onDatabaseWorker { reset.run() }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(SingleSessionReset.isReady(ApplicationProvider.getApplicationContext()))
    }

    private fun restoredState() = Bundle().apply {
        putString("agent_goal_input", "draft before reset")
        putStringArrayList("selected_run_skills", arrayListOf("selected-before-reset"))
        putString("attachment_picker_session", "old-conversation")
        putString("chat_session_title", "saved title")
        putString("proactive_target_message", "saved-target")
        putString("open_scheduled_task_id", "saved-task")
    }

    private fun assertRestoredState(activity: MainActivity) {
        val state = savedState(activity)
        assertEquals("draft before reset", state.getString("agent_goal_input"))
        assertEquals(listOf("selected-before-reset"), state.getStringArrayList("selected_run_skills"))
        assertEquals(WorkspaceIdentity.CENTRAL_SESSION, state.getString("attachment_picker_session"))
        assertEquals("saved title", state.getString("chat_session_title"))
        assertEquals("saved-target", state.getString("proactive_target_message"))
        assertEquals("saved-task", state.getString("open_scheduled_task_id"))
    }

    private fun savedState(activity: MainActivity) = Bundle().also {
        MainActivity::class.java.getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
            .apply { isAccessible = true }.invoke(activity, it)
    }

    private fun pickerFile(name: String, text: String): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, "jarvys/camera/$name").also { it.parentFile!!.mkdirs(); it.writeText(text) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun deliverResult(activity: MainActivity, launcher: String, input: Any, result: Intent) {
        (field(activity, launcher) as ActivityResultLauncher<Any>).launch(input)
        val shadow = Shadows.shadowOf(activity)
        val request = requireNotNull(shadow.nextStartedActivityForResult)
        shadow.receiveResult(request.intent, Activity.RESULT_OK, result)
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private fun awaitExecutor(owner: Any, name: String) {
        (field(owner, name) as ExecutorService).submit {}.get(10, TimeUnit.SECONDS)
    }
}
