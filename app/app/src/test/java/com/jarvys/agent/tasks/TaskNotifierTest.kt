package com.jarvys.agent.tasks

import android.Manifest
import android.app.NotificationManager
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.proactive.ProactiveThreadMessage
import java.util.UUID
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskNotifierTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun missingNotificationPermissionReturnsFalseWithoutPosting() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        assertFalse(TaskNotifier.post(context, task(), "result-1", emptyList(), "Result", "Body", "normal", emptyList()))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun resultsUsePrivateTaskChannelStableThreadAndImmutableSuggestedReplyActions() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val task = task()
        val message = listOf(ProactiveThreadMessage("Result body", 1L, true))
        val replies = listOf(ProactiveSuggestedReply("Open", "Explain this result"))
        assertTrue(TaskNotifier.post(context, task, "result-1", message, "Task result", "Body", "high", replies))
        assertTrue(TaskNotifier.post(context, task, "result-1", message, "Task result", "Body", "high", replies))

        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(TaskNotifier.CHANNEL_ID)?.importance)
        assertEquals(androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE, posted.single().visibility)
        val publicVersion = requireNotNull(posted.single().publicVersion)
        assertEquals(context.getString(com.jarvys.agent.R.string.scheduled_tasks_public_body),
            publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertFalse(publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
            .contains("Body", ignoreCase = true))
        assertEquals(2, posted.single().actions.size)
        val chatIntent = shadowOf(posted.single().contentIntent).savedIntent
        assertEquals("result-1",
            chatIntent.getStringExtra(com.jarvys.agent.MainActivity.EXTRA_OPEN_PROACTIVE_MESSAGE_ID))
        assertFalse(chatIntent.hasExtra(com.jarvys.agent.MainActivity.EXTRA_OPEN_CHAT_SESSION))
        val viewTaskAction = posted.single().actions.first { it.title.toString() == context.getString(com.jarvys.agent.R.string.task_view_task) }
        assertTrue(shadowOf(viewTaskAction.actionIntent).isImmutable)
        val viewTaskIntent = shadowOf(viewTaskAction.actionIntent).savedIntent
        assertEquals(task.id, viewTaskIntent.getStringExtra(com.jarvys.agent.MainActivity.EXTRA_OPEN_TASK))
        assertEquals(com.jarvys.agent.MainActivity::class.java.name, viewTaskIntent.component?.className)
    }

    @Test fun attentionAndRecoveryReplaceOneTaskIncidentNotification() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val task = task()
        assertTrue(TaskNotifier.attention(context, task, TaskRunCause.CONNECTOR_UNAVAILABLE))
        val incident = shadowOf(manager).allNotifications.single()
        val incidentIntent = shadowOf(incident.contentIntent).savedIntent
        assertEquals(task.id, incidentIntent.getStringExtra(com.jarvys.agent.MainActivity.EXTRA_OPEN_TASK))
        assertTrue(shadowOf(incident.contentIntent).isImmutable)
        assertTrue(TaskNotifier.recovered(context, task))
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals(TaskNotifier.CHANNEL_ID, manager.notificationChannels.single().id)
    }

    @Test fun pendingActionNotificationHasApproveAndRejectBroadcastActions() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val task = task()
        val pending = TaskPendingActionStore(File(Files.createTempDirectory("pending-action-notification").toFile(),
            "pending-actions.jsonl"))
            .enqueue(task, "run-approval", "gmail_send_message", """{"to":"person@example.test"}""")

        assertTrue(TaskNotifier.pendingAction(context, task, pending))
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(2, notification.actions.size)
        assertEquals(context.getString(com.jarvys.agent.R.string.task_pending_action_reject),
            notification.actions[0].title.toString())
        assertEquals(context.getString(com.jarvys.agent.R.string.task_pending_action_approve),
            notification.actions[1].title.toString())
        notification.actions.forEach { action ->
            assertTrue(shadowOf(action.actionIntent).isImmutable)
            val intent = shadowOf(action.actionIntent).savedIntent
            assertEquals(TaskPendingActionReceiver::class.java.name, intent.component?.className)
            assertEquals(pending.id, intent.getStringExtra(TaskPendingActionReceiver.EXTRA_ACTION_ID))
            assertEquals(task.id, intent.getStringExtra(TaskPendingActionReceiver.EXTRA_TASK_ID))
        }
        assertEquals(TaskPendingActionReceiver.ACTION_REJECT,
            shadowOf(notification.actions[0].actionIntent).savedIntent.action)
        assertEquals(TaskPendingActionReceiver.ACTION_APPROVE,
            shadowOf(notification.actions[1].actionIntent).savedIntent.action)
    }

    @Test fun expiredApprovalPostsALocalizedNoticeWithoutActionButtons() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val task = task()
        val pending = TaskPendingActionStore(File(Files.createTempDirectory("pending-action-expired").toFile(),
            "pending-actions.jsonl")).enqueue(task, "run-approval", "gmail_send_message", """{"to":"person@example.test"}""")

        assertTrue(TaskNotifier.pendingActionExpired(context, task.copy(revision = task.revision + 1L), pending))
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(context.getString(com.jarvys.agent.R.string.task_pending_action_expired_notification_title),
            notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertTrue(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
            .contains("was not executed"))
        assertTrue(notification.actions == null || notification.actions.isEmpty())
        assertTrue(shadowOf(notification.contentIntent).isImmutable)
    }

    @Test fun taskFailureCausesUseEnglishAndSpanishResources() {
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("en"))
        })
        val spanish = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("es"))
        })

        assertEquals("The provider is unavailable", TaskRunCause.PROVIDER_UNAVAILABLE.localized(english))
        assertEquals("El proveedor no está disponible", TaskRunCause.PROVIDER_UNAVAILABLE.localized(spanish))
    }

    private fun task() = ScheduledTask(
        name = "Private scheduled item", instruction = "Summarize safely",
        schedule = TaskSchedule.Every(900_000L, 100L), createdAt = 1L)
}
