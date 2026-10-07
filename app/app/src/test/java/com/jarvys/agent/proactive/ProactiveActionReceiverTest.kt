package com.jarvys.agent.proactive

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProactiveActionReceiverTest {
    @Test fun receiverIsPrivateAndRejectsUnknownMessageAndThreadBeforeWritingOrLaunching() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val appReceiver = context.packageManager.getReceiverInfo(ComponentName(context, ProactiveActionReceiver::class.java), 0)
        val approvalReceiver = context.packageManager.getReceiverInfo(
            ComponentName(context, com.jarvys.agent.connectors.ApprovalActionReceiver::class.java), 0,
        )
        assertFalse(appReceiver.exported)
        assertFalse(approvalReceiver.exported)
        assertNull(ProactiveInteractionDispatcher.prepareSuggestedReply(context, "foreign-message", 0))
        assertNull(ProactiveInteractionDispatcher.prepareInlineReply(context, "foreign-thread", "hello"))

        val before = ProactiveEventStore(context).readAll().size
        ProactiveActionReceiver().onReceive(context, Intent(ProactiveActionReceiver.ACTION_SUGGESTED_REPLY)
            .putExtra(ProactiveActionReceiver.EXTRA_MESSAGE_ID, "foreign-message")
            .putExtra(ProactiveActionReceiver.EXTRA_REPLY_INDEX, 0))
        assertFalse(context.packageManager.getReceiverInfo(ComponentName(context, ProactiveActionReceiver::class.java),
            PackageManager.GET_META_DATA).exported)
        assertEquals(before, ProactiveEventStore(context).readAll().size)
    }
}
