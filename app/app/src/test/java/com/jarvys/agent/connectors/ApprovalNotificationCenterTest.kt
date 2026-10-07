package com.jarvys.agent.connectors

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApprovalNotificationCenterTest {
    private lateinit var context: Application

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        context.getSystemService(NotificationManager::class.java)?.cancelAll()
        ApprovalNotificationCenter.attach(context)
    }

    @Test fun approvalNotificationShowsFullSummaryWarningAndOnlyOpaqueActionExtras() {
        val id = "approval-request-1"
        val fullBody = "Full reviewed SMS body ".repeat(35)
        val summary = ApprovalSummary(
            "Open SMS Draft",
            listOf("To: +15551234567", "Message: $fullBody"),
            activityIntent = ApprovalIntentSpec(ApprovalIntentKind.SMS_COMPOSE,
                "smsto:+15551234567", mapOf("sms_body" to fullBody)),
        )
        ApprovalNotificationCenter.show(id, summary)
        val active = context.getSystemService(NotificationManager::class.java)?.activeNotifications.orEmpty().single()
        val notification = active.notification
        assertEquals(id.hashCode(), active.id)
        val detail = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(detail.contains("To: +15551234567"))
        assertTrue(detail.contains(fullBody))
        assertTrue(detail.contains(context.getString(com.jarvys.agent.R.string.proactive_notification_approval_warning)))
        assertEquals(2, notification.actions.size)
        assertEquals(context.getString(com.jarvys.agent.R.string.proactive_notification_approve), notification.actions[0].title)
        assertEquals(context.getString(com.jarvys.agent.R.string.proactive_notification_reject), notification.actions[1].title)

        val contentIntent = shadowOf(notification.contentIntent).savedIntent
        assertFalse(contentIntent.extras?.toString().orEmpty().contains(fullBody))
        notification.actions.forEach { action ->
            val pending = action.actionIntent
            assertTrue(shadowOf(pending).isImmutable)
            val actionIntent = shadowOf(pending).savedIntent
            assertEquals(ComponentName(context, ApprovalActionReceiver::class.java), actionIntent.component)
            assertEquals(id, actionIntent.getStringExtra(ApprovalNotificationCenter.EXTRA_APPROVAL_ID))
            assertFalse(actionIntent.extras?.toString().orEmpty().contains(fullBody))
            assertFalse(actionIntent.data?.toString().orEmpty().contains(fullBody))
        }
        assertTrue(summary.activityIntent?.dataUri?.let(Uri::parse)?.scheme == "smsto")
    }
}
