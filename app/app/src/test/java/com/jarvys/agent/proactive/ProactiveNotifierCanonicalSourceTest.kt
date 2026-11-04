package com.jarvys.agent.proactive

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.ChatTestStore
import com.jarvys.agent.LocalRunStore
import com.jarvys.agent.MainActivity
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [com.jarvys.agent.ArtifactOsShadow::class])
class ProactiveNotifierCanonicalSourceTest {
    @get:Rule val chatTestStore = ChatTestStore(registerApplicationFilesDirectory = true)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val central = WorkspaceIdentity.CENTRAL_SESSION
    private val thread = "native-notification-thread"

    @Test fun onlyRemoteInputIsMutableAndEveryActionHasExactSourceBinding() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = LocalRunStore(context)
        val noticeId = store.appendProactiveAssistantMessageIfAbsent(central, "notice", "Canonical notice",
            listOf(ProactiveSuggestedReply("Yes", "Yes please"), ProactiveSuggestedReply("No", "No thanks")), thread)
        assertTrue(postCached(noticeId))
        val notification = notification()
        assertTrue(shadowOf(notification.contentIntent).isImmutable)
        assertEquals(3, notification.actions.size)
        notification.actions.take(2).forEachIndexed { ordinal, action ->
            assertTrue(shadowOf(action.actionIntent).isImmutable)
            val binding = ProactiveReplyNotificationIdentity.readValidated(context, shadowOf(action.actionIntent).savedIntent)
            assertNotNull(binding)
            assertEquals(noticeId, binding!!.sourceMessageId)
            assertEquals("OPTION", binding.actionKind)
            assertEquals(ordinal, binding.selectedOrdinal)
        }
        val inline = notification.actions.last()
        assertFalse(shadowOf(inline.actionIntent).isImmutable)
        assertEquals(ProactiveActionReceiver.EXTRA_INLINE_REPLY, inline.remoteInputs.single().resultKey)
        val binding = ProactiveReplyNotificationIdentity.readValidated(context, shadowOf(inline.actionIntent).savedIntent)
        assertNotNull(binding)
        assertEquals("INLINE", binding!!.actionKind)
        assertEquals(noticeId, binding.sourceMessageId)
    }

    @Test @Config(sdk = [28])
    fun preAndroidTwelveRemoteInputRemainsFillableWhileOpenAndChipsAreImmutable() {
        val store = LocalRunStore(context)
        val notice = store.appendProactiveAssistantMessageIfAbsent(central, "pre-s", "Notice",
            listOf(ProactiveSuggestedReply("Yes", "Yes")), thread)
        assertTrue(postCached(notice))
        val notification = notification()
        assertTrue(shadowOf(notification.contentIntent).isImmutable)
        assertTrue(shadowOf(notification.actions.first().actionIntent).isImmutable)
        assertFalse(shadowOf(notification.actions.last().actionIntent).isImmutable)
    }

    @Test fun unchangedSourceKeepsIdentityAndRefreshUsesCanonicalUserInsteadOfCachedAssistant() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = LocalRunStore(context)
        val noticeId = store.appendProactiveAssistantMessageIfAbsent(central, "notice", "Original notice", emptyList(), thread)
        assertTrue(postCached(noticeId))
        val originalHandle = notification().actions.last().actionIntent
        val originalIntent = shadowOf(originalHandle).savedIntent
        val original = requireNotNull(ProactiveReplyNotificationIdentity.readValidated(context, originalIntent))
        assertTrue(postCached(noticeId))
        assertEquals(originalHandle, notification().actions.last().actionIntent)
        assertEquals(original, ProactiveReplyNotificationIdentity.readValidated(context,
            shadowOf(notification().actions.last().actionIntent).savedIntent))

        // This is a notification-source test, not evidence of composite acceptance or a provider run.
        val userId = store.appendProactiveUserMessage(central, "Intentional same reply", thread, null)
        assertTrue(ProactiveNotifier.refreshThread(context, thread, noticeId, "Stale cached assistant",
            emptyList(), noticeId, emptyList(), false))
        val refreshed = notification()
        val refreshedHandle = refreshed.actions.last().actionIntent
        val current = requireNotNull(ProactiveReplyNotificationIdentity.readValidated(context,
            shadowOf(refreshedHandle).savedIntent))
        assertEquals(userId, current.sourceMessageId)
        assertNotEquals(original.actionId, current.actionId)
        assertNotEquals(originalHandle, refreshedHandle)
        assertEquals(original, ProactiveReplyNotificationIdentity.readValidated(context, shadowOf(originalHandle).savedIntent))
        assertEquals(userId, shadowOf(refreshed.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_OPEN_PROACTIVE_MESSAGE_ID))
        assertTrue(refreshed.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Intentional same reply"))
        assertEquals(2, store.readProactiveThreadMessages(central, thread).size)
        assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test fun callerOnlySourceCannotCreateAReplyAffordance() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(postCached("missing-canonical-message"))
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test
    @Config(shadows = [com.jarvys.agent.ArtifactOsShadow::class, PausingNotificationManagerShadow::class])
    fun latePublicationCannotOverwriteTheCanonicalReplyRefresh() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = LocalRunStore(context)
        val noticeId = store.appendProactiveAssistantMessageIfAbsent(central, "paused-notice", "Original notice", emptyList(), thread)
        val firstTask = java.util.concurrent.FutureTask { postCached(noticeId) }
        val firstPublisher = Thread(firstTask, "p4-notification-first")
        var secondPublisher: Thread? = null
        PausingNotificationManagerShadow.pauseNext.set(true)
        firstPublisher.start()
        try {
            assertTrue("The old source must reach Android before the USER commit",
                PausingNotificationManagerShadow.reachedAndroid.await(10, java.util.concurrent.TimeUnit.SECONDS))
            // Committing while Android is paused also proves the notifier holds no Room transaction here.
            // This tests publication order, not the composite writer's acceptance or provider execution.
            val userId = store.appendProactiveUserMessage(central, "New canonical reply", thread, null)
            val secondTask = java.util.concurrent.FutureTask {
                ProactiveNotifier.refreshThread(context, thread, noticeId, "stale", emptyList(), noticeId, emptyList(), false)
            }
            val refreshPublisher = Thread(secondTask, "p4-notification-refresh")
            secondPublisher = refreshPublisher
            refreshPublisher.start()
            val threads = java.lang.management.ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            var waitingOnFirst = false
            while (System.nanoTime() < deadline && !secondTask.isDone) {
                val state = threads.getThreadInfo(refreshPublisher.id)
                waitingOnFirst = state != null && state.threadState == Thread.State.BLOCKED && state.lockOwnerId == firstPublisher.id
                if (waitingOnFirst) break
                Thread.sleep(5)
            }
            assertTrue("Refresh must wait behind the old publication, then re-read the committed USER", waitingOnFirst)
            PausingNotificationManagerShadow.releaseAndroid.countDown()
            assertTrue(firstTask.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(secondTask.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf(noticeId, userId), PausingNotificationManagerShadow.publishedSources.toList())
            val current = requireNotNull(ProactiveReplyNotificationIdentity.readValidated(context,
                shadowOf(notification().actions.last().actionIntent).savedIntent))
            assertEquals(userId, current.sourceMessageId)
            assertEquals(2, store.readProactiveThreadMessages(central, thread).size)
        } finally {
            PausingNotificationManagerShadow.releaseAndroid.countDown()
            firstPublisher.join(10_000)
            secondPublisher?.join(10_000)
        }
    }

    /** Android publication seam only; there is no production test hook or Room callback. */
    @org.robolectric.annotation.Implements(NotificationManager::class)
    class PausingNotificationManagerShadow : org.robolectric.shadows.ShadowNotificationManager() {
        @org.robolectric.annotation.Implementation
        override fun notify(tag: String?, id: Int, notification: Notification) {
            val proactive = tag?.startsWith("jarvys_proactive_thread:") == true
            if (proactive && pauseNext.compareAndSet(true, false)) {
                reachedAndroid.countDown()
                check(releaseAndroid.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Timed out releasing Android publication" }
            }
            super.notify(tag, id, notification)
            if (proactive) {
                publishedSources += shadowOf(notification.actions.last().actionIntent).savedIntent
                    .getStringExtra(ProactiveActionReceiver.EXTRA_MESSAGE_ID).orEmpty()
            }
        }

        companion object {
            val pauseNext = java.util.concurrent.atomic.AtomicBoolean(false)
            var reachedAndroid = java.util.concurrent.CountDownLatch(1)
            var releaseAndroid = java.util.concurrent.CountDownLatch(1)
            val publishedSources = java.util.concurrent.CopyOnWriteArrayList<String>()

            @JvmStatic @org.robolectric.annotation.Resetter
            fun resetPublication() {
                pauseNext.set(false)
                reachedAndroid = java.util.concurrent.CountDownLatch(1)
                releaseAndroid = java.util.concurrent.CountDownLatch(1)
                publishedSources.clear()
            }
        }
    }

    private fun postCached(messageId: String): Boolean = ProactiveNotifier.post(context, thread, messageId,
        "Caller text is not the canonical source", "normal", emptyList(), messageId, emptyList(), false)

    private fun notification(): Notification = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.single().notification
}
