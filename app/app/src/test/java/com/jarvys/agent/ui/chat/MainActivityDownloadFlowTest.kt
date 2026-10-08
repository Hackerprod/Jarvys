package com.jarvys.agent

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.jarvys.agent.ui.chat.ChatFileRequest
import com.jarvys.agent.ui.chat.ChatFileTransfer
import com.jarvys.agent.ui.chat.ChatFileTransfers
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the production MainActivity Save callback, retained VM and actual ActivityResult host. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-port-mdpi")
class MainActivityDownloadFlowTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val secretSingleton = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
    private var controller: ActivityController<MainActivity>? = null

    @Before fun setUp() {
        // AndroidViewModelFactory caches its Application globally; Robolectric replaces the Application per test.
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
        secretSingleton.set(null, SecretStore(context.getSharedPreferences("ux16-ui-test-secrets", 0)))
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After fun cleanUp() {
        controller?.pause()?.stop()?.destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
        controller = null
        secretSingleton.set(null, null)
        WorkManagerTestCleanup.close(context)
    }

    @Test fun saveUsesDownloadsWithoutPickerAndDuplicateTapsWriteOnceThenExplicitOpenGrantsReadOnly() {
        val provider = FakeDownloadsProvider.install()
        val source = generated("download-activity-${UUID.randomUUID()}")
        val activity = start(source.session)
        val vm = ViewModelProvider(activity)[ChatFileTransfers::class.java]
        save(activity, source)
        save(activity, source)
        await { vm.transfers.value[source.request.key]?.saved == true }
        val saved = requireNotNull(vm.transfers.value[source.request.key]?.result)
        assertEquals(1, provider.inserts)
        assertEquals(1, provider.publishes)
        assertArrayEquals(source.bytes, provider.file(provider.rows.keys.single()).readBytes())
        assertNull("Save neither opens SAF nor launches a viewer/installer", Shadows.shadowOf(activity).nextStartedActivity)
        assertNull("API 29+ must not ask for storage permission", Shadows.shadowOf(activity).lastRequestedPermission)
        assertEquals(source.session, vm.notice.value?.request?.sessionId)

        vm.dismissNotice()
        vm.open(source.request)
        var opened: Intent? = null
        await { opened = Shadows.shadowOf(activity).nextStartedActivity; opened != null }
        assertEquals(Intent.ACTION_VIEW, opened!!.action)
        assertEquals(saved.uri, opened!!.data)
        assertEquals("${context.packageName}.downloads", opened!!.data?.authority)
        assertTrue(opened!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, opened!!.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNotNull(opened!!.clipData)

        // A later tap reuses the durable completed export, including after the completion was dismissed.
        save(activity, source)
        await { vm.transfers.value[source.request.key]?.result?.status == DownloadStore.Status.ALREADY_SAVED }
        assertEquals(1, provider.inserts)
        assertEquals(saved.uri, vm.transfers.value[source.request.key]?.result?.uri)
    }

    @Test fun shareOriginalUsesTranscriptScopedReadOnlyProviderWithoutExportOrPicker() {
        val provider = FakeDownloadsProvider.install()
        val source = generated("share-activity-${UUID.randomUUID()}")
        val activity = start(source.session)
        MainActivity::class.java.getDeclaredMethod("shareGeneratedImage", String::class.java, AgentRunUiEvent::class.java)
            .apply { isAccessible = true }.invoke(activity, source.session, source.event)
        var chooser: Intent? = null
        await { chooser = Shadows.shadowOf(activity).nextStartedActivity; chooser != null }
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        val send = requireNotNull(chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        val uri = requireNotNull(send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        assertEquals("${context.packageName}.chat-files", uri.authority)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNotNull(send.clipData)
        assertEquals(0, provider.inserts)
        assertArrayEquals(source.bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    @Test fun switchingChatAndRotationDuringCopyKeepsSourceAndSelectedConversation() {
        val provider = FakeDownloadsProvider.install()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.beforeOutputOpen = Runnable { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val source = generated("download-source-${UUID.randomUUID()}")
        try {
            val activity = start(source.session)
            val vm = ViewModelProvider(activity)[ChatFileTransfers::class.java]
            save(activity, source)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            MainActivity::class.java.getDeclaredMethod("newChat").apply { isAccessible = true }.invoke(activity)
            val nextSession = selectedSession(activity)
            assertNotEquals(source.session, nextSession)
            assertEquals("Original deep-link intent is intentionally stale", source.session,
                activity.intent.getStringExtra(MainActivity.EXTRA_OPEN_CHAT_SESSION))
            controller!!.recreate()
            val rotated = controller!!.get()
            assertSame(vm, ViewModelProvider(rotated)[ChatFileTransfers::class.java])
            assertEquals(nextSession, selectedSession(rotated))
            assertTrue(vm.transfers.value[source.request.key]!!.busy)
            release.countDown()
            await { vm.transfers.value[source.request.key]?.saved == true }
            assertEquals(source.session, vm.notice.value?.request?.sessionId)
            assertEquals(nextSession, selectedSession(rotated))
            assertArrayEquals(source.bytes, provider.file(provider.rows.keys.single()).readBytes())
            assertEquals(1, provider.inserts)
        } finally { release.countDown() }
    }

    @Test fun cancelAnActiveCopyCleansPendingOutputAndRecoversDownloadControl() {
        val provider = FakeDownloadsProvider.install()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.beforeOutputOpen = Runnable { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val source = generated("download-cancel-${UUID.randomUUID()}")
        try {
            val activity = start(source.session)
            val vm = ViewModelProvider(activity)[ChatFileTransfers::class.java]
            save(activity, source)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            vm.cancel(source.request)
            release.countDown()
            await { vm.transfers.value[source.request.key]?.busy == false }
            assertFalse(vm.transfers.value[source.request.key]!!.saved)
            assertTrue(provider.rows.isEmpty())
            assertNull(vm.notice.value)
            provider.beforeOutputOpen = null
            save(activity, source)
            await { vm.transfers.value[source.request.key]?.saved == true }
            assertEquals(1, provider.rows.size)
        } finally { release.countDown() }
    }

    @Test fun destinationFailureIsNotReportedAsSuccessAndRetryUsesTheSameChat() {
        val provider = FakeDownloadsProvider.install().apply { failOpen = true }
        val source = generated("download-retry-${UUID.randomUUID()}")
        val activity = start(source.session)
        val vm = ViewModelProvider(activity)[ChatFileTransfers::class.java]
        save(activity, source)
        await { vm.notice.value?.failure != null }
        assertFalse(vm.notice.value!!.saved)
        assertTrue(provider.rows.isEmpty())
        provider.failOpen = false
        vm.dismissNotice()
        save(activity, source)
        await { vm.transfers.value[source.request.key]?.saved == true }
        assertArrayEquals(source.bytes, provider.file(provider.rows.keys.single()).readBytes())
    }

    @Test @Config(sdk = [28])
    fun legacyPermissionDenialUsesActualResultCallbackAndNeverClaimsSaved() {
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val source = generated("download-permission-${UUID.randomUUID()}")
        val activity = start(source.session)
        val vm = ViewModelProvider(activity)[ChatFileTransfers::class.java]
        save(activity, source)
        await { Shadows.shadowOf(activity).lastRequestedPermission != null }
        val request = Shadows.shadowOf(activity).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), request.requestedPermissions)
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        await { vm.notice.value?.failure == ChatFileTransfer.Failure.PERMISSION }
        assertFalse(vm.notice.value!!.saved)
        assertFalse(vm.transfers.value[source.request.key]!!.waitingForPermission)
        val permissionIntent = Shadows.shadowOf(activity).nextStartedActivity
        assertTrue(permissionIntent == null || permissionIntent.action == "android.content.pm.action.REQUEST_PERMISSIONS")
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertEquals(source.session, selectedSession(activity))
    }

    @Test fun staleSourceAfterConversationDeletionFailsBeforePublicStorageMutation() {
        val provider = FakeDownloadsProvider.install()
        val source = generated("download-stale-${UUID.randomUUID()}")
        val activity = start(source.session)
        val vm = ViewModelProvider(activity)[ChatFileTransfers::class.java]
        // A callback retained from an old card must lose its authority after chat deletion.
        LocalRunStore(context).deleteConversation(source.session)
        save(activity, source)
        await { vm.notice.value?.failure != null }
        assertEquals(ChatFileTransfer.Failure.UNAVAILABLE, vm.notice.value!!.failure)
        assertEquals(0, provider.inserts)
    }

    private fun start(session: String): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CHAT_SESSION, session)).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
        val activity = controller!!.get()
        assertEquals("VM and fixture must belong to the same Android application sandbox", context.filesDir,
            ViewModelProvider(activity)[ChatFileTransfers::class.java].getApplication<Application>().filesDir)
        return activity
    }

    private fun save(activity: MainActivity, source: GeneratedFixture) {
        MainActivity::class.java.getDeclaredMethod("saveGeneratedImage", String::class.java, AgentRunUiEvent::class.java)
            .apply { isAccessible = true }.invoke(activity, source.session, source.event)
    }

    private fun selectedSession(activity: MainActivity): String =
        context.getSharedPreferences("jarvys_chat", Context.MODE_PRIVATE).getString("active_session_id", null)!!

    private data class GeneratedFixture(val session: String, val event: AgentRunUiEvent, val bytes: ByteArray) {
        val request get() = requireNotNull(ChatFileRequest.generated(session, event))
    }

    private fun generated(session: String): GeneratedFixture {
        val image = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        val path = GeneratedImageStore(context).save(session, UUID.randomUUID().toString(), bytes)
        val store = LocalRunStore(context)
        store.appendGeneratedImageEvent(session, path, "Activity download fixture", null, "32x24", "image/png")
        return GeneratedFixture(session, store.readConversationTimeline(session).single { it.kind == "generated_image" }, bytes)
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < end)
        val vm = controller?.get()?.let { ViewModelProvider(it)[ChatFileTransfers::class.java] }
        assertTrue("Activity download did not settle: transfers=${vm?.transfers?.value}; notice=${vm?.notice?.value}; launch=${vm?.launch?.value}", condition())
    }
}
