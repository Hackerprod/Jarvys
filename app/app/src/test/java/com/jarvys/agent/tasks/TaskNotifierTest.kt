package com.jarvys.agent.tasks

import android.Manifest
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.proactive.ProactiveSuggestedReply
import com.jarvys.agent.proactive.ProactiveThreadMessage
import java.util.UUID
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
        assertEquals(ScheduledTaskConversation.SESSION_ID,
            chatIntent.getStringExtra(com.jarvys.agent.MainActivity.EXTRA_OPEN_CHAT_SESSION))
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
        assertTrue(TaskNotifier.attention(context, task, "connector_unavailable"))
        val incident = shadowOf(manager).allNotifications.single()
        val incidentIntent = shadowOf(incident.contentIntent).savedIntent
        assertEquals(task.id, incidentIntent.getStringExtra(com.jarvys.agent.MainActivity.EXTRA_OPEN_TASK))
        assertTrue(shadowOf(incident.contentIntent).isImmutable)
        assertTrue(TaskNotifier.recovered(context, task))
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals(TaskNotifier.CHANNEL_ID, manager.notificationChannels.single().id)
    }

    private fun task() = ScheduledTask(
        name = "Private scheduled item", instruction = "Summarize safely",
        schedule = TaskSchedule.Every(900_000L, 100L), createdAt = 1L)
}
