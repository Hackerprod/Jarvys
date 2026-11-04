package com.jarvys.agent.proactive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.FreshStorageBootstrap
import com.jarvys.agent.MainActivity
import com.jarvys.agent.chatdb.ProactiveReplyAcceptance
import com.jarvys.agent.workspaces.WorkspaceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [com.jarvys.agent.ArtifactOsShadow::class])
class ProactiveReplyNotificationIdentityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun explicitBindingRoundTripsFullThreadAndSeparatesOrdinalFromLogicalAcceptance() {
        val first = binding(kind = "OPTION", ordinal = 0, thread = "payments/a?b#c 😀")
        val second = binding(kind = "OPTION", ordinal = 1, thread = first.threadKey)
        val firstIntent = ProactiveReplyNotificationIdentity.createIntent(context, first)
        val secondIntent = ProactiveReplyNotificationIdentity.createIntent(context, second)
        assertEquals(first, ProactiveReplyNotificationIdentity.readValidated(context, firstIntent))
        assertEquals(second, ProactiveReplyNotificationIdentity.readValidated(context, secondIntent))
        assertEquals(first.actionId, second.actionId)
        assertNotEquals(firstIntent.data, secondIntent.data)
        assertTrue(firstIntent.data!!.pathSegments.contains(first.threadKey))
        assertEquals(ProactiveActionReceiver::class.java.name, firstIntent.component?.className)
    }

    @Test fun missingOldOrMutatedUriAndExtrasCannotReplaceTheCanonicalBinding() {
        val source = binding()
        val valid = ProactiveReplyNotificationIdentity.createIntent(context, source)
        val changes: List<(Intent) -> Unit> = listOf(
            { it.removeExtra(ProactiveReplyNotificationIdentity.EXTRA_PHYSICAL_GENERATION) },
            { it.putExtra(ProactiveReplyNotificationIdentity.EXTRA_PHYSICAL_GENERATION, "recreated-generation") },
            { it.putExtra(ProactiveActionReceiver.EXTRA_MESSAGE_ID, "new-source") },
            { it.putExtra(ProactiveReplyNotificationIdentity.EXTRA_THREAD_KEY, "other-thread") },
            { it.putExtra(ProactiveActionReceiver.EXTRA_THREAD_TOKEN, "other-token") },
            { it.putExtra(ProactiveReplyNotificationIdentity.EXTRA_ACTION_ID, "other-action") },
            { it.putExtra(ProactiveReplyNotificationIdentity.EXTRA_SOURCE_FINGERPRINT, "changed-source-payload") },
            { it.removeExtra(ProactiveReplyNotificationIdentity.EXTRA_SOURCE_FINGERPRINT) },
            { it.putExtra(ProactiveReplyNotificationIdentity.EXTRA_ACTION_KIND, "OPTION") },
            { it.putExtra(ProactiveActionReceiver.EXTRA_REPLY_INDEX, 0) },
            { it.action = ProactiveActionReceiver.ACTION_SUGGESTED_REPLY },
            { it.setClass(context, MainActivity::class.java) },
            { it.component = null },
            { it.data = Uri.parse("jarvys://proactive/reply/123") },
            { it.data = Uri.parse(valid.data.toString() + "?source=other") },
            { it.putExtra(FreshStorageBootstrap.GENERATION_EXTRA, "old-format") },
        )
        changes.forEachIndexed { index, change ->
            assertNull("mutated notification field $index", ProactiveReplyNotificationIdentity.readValidated(context,
                Intent(valid).also(change)))
        }
        val old = FreshStorageBootstrap.stamp(Intent(context, ProactiveActionReceiver::class.java))
            .setAction(ProactiveActionReceiver.ACTION_INLINE_REPLY)
            .putExtra(ProactiveActionReceiver.EXTRA_MESSAGE_ID, source.sourceMessageId)
            .putExtra(ProactiveActionReceiver.EXTRA_THREAD_TOKEN, ProactiveThreadKey.fingerprint(source.threadKey))
            .setData(Uri.parse("jarvys://proactive/reply/${source.threadKey.hashCode()}"))
        assertNull(ProactiveReplyNotificationIdentity.readValidated(context, old))
    }

    @Test fun physicalGenerationSourceAndFullThreadKeepOldPendingIntentHandlesBound() {
        val source = binding(thread = "Aa")
        val original = ProactiveReplyNotificationIdentity.createIntent(context, source)
        val oldHandle = PendingIntent.getBroadcast(context, 88, original,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val replacements = listOf(
            binding(generation = "same-format-new-physical-store", thread = source.threadKey),
            binding(source = "next-source", thread = source.threadKey),
            binding(thread = "BB"), // Same String.hashCode, different full thread identity.
            binding(thread = source.threadKey, fingerprint = "same-id-changed-payload"),
        )
        assertEquals("Aa".hashCode(), "BB".hashCode())
        replacements.forEach { next ->
            val intent = ProactiveReplyNotificationIdentity.createIntent(context, next)
            val handle = PendingIntent.getBroadcast(context, 88, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            assertNotEquals(oldHandle, handle)
            assertNotEquals(original.data, intent.data)
        }
        assertEquals(source, ProactiveReplyNotificationIdentity.readValidated(context, shadowOf(oldHandle).savedIntent))
        assertFalse(shadowOf(oldHandle).isImmutable)
    }

    private fun binding(
        generation: String = "physical-generation", source: String = "source-message", thread: String = "thread",
        kind: String = "INLINE", ordinal: Int? = null, fingerprint: String = "canonical-source-fingerprint",
    ) = ProactiveReplyNotificationIdentity.Binding(generation, source, thread, kind,
        ProactiveReplyAcceptance.actionId(generation, WorkspaceIdentity.CENTRAL_SESSION, thread, source, kind), ordinal, fingerprint)
}
