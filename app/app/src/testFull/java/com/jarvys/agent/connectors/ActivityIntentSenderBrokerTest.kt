package com.jarvys.agent.connectors

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActivityIntentSenderBrokerTest {
    private class Registry : ActivityResultRegistry() {
        val requests = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                                     options: ActivityOptionsCompat?) { requests += requestCode }
    }
    private fun pendingIntent(): PendingIntent = PendingIntent.getActivity(RuntimeEnvironment.getApplication(), 100,
        Intent("test.google.authorization"), PendingIntent.FLAG_IMMUTABLE)
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Expected fake authorization progress", condition())
    }

    @Test fun cancelledResultWithIntentStillReachesOfficialGoogleParser() {
        val intent = Intent().putExtra("provider_error", "cancelled")
        assertSame(intent, ActivityIntentSenderBroker.authorizationResultData(ActivityResult(Activity.RESULT_CANCELED, intent)))
        assertSame(intent, ActivityIntentSenderBroker.authorizationResultData(ActivityResult(42, intent)))
        assertTrue(runCatching { ActivityIntentSenderBroker.authorizationResultData(ActivityResult(Activity.RESULT_CANCELED, null)) }
            .exceptionOrNull() is ActivityAuthorizationCancelledException)
        assertTrue(runCatching { ActivityIntentSenderBroker.authorizationResultData(ActivityResult(Activity.RESULT_OK, null)) }.isFailure)
    }

    @Test fun lateResultAfterCancellationCannotResolveReplacementAttempt() {
        val broker = ActivityIntentSenderBroker()
        val registry = Registry()
        val owner = broker.attachRegistry(registry)
        val firstFailure = AtomicReference<Throwable>()
        val first = Thread { runCatching { broker.launch(pendingIntent(), 2_000) }.onFailure(firstFailure::set) }.apply { start() }
        waitUntil { registry.requests.size == 1 }
        val oldCode = registry.requests[0]
        broker.invalidate()
        first.join(1_000)
        assertNotNull(firstFailure.get())
        val result = AtomicReference<Intent>()
        val second = Thread { result.set(broker.launch(pendingIntent(), 2_000)) }.apply { start() }
        waitUntil { registry.requests.size == 2 }
        val newCode = registry.requests[1]
        assertNotEquals(oldCode, newCode)
        registry.dispatchResult(oldCode, Activity.RESULT_OK, Intent().putExtra("attempt", "old"))
        assertNull(result.get())
        registry.dispatchResult(newCode, Activity.RESULT_CANCELED, Intent().putExtra("attempt", "new"))
        second.join(1_000)
        assertEquals("new", result.get().getStringExtra("attempt"))
        broker.detach(owner)
    }

    @Test fun rotationInvalidatesOldHostAndOldDisposeCannotDetachNewHost() {
        val broker = ActivityIntentSenderBroker()
        val firstRegistry = Registry()
        val oldOwner = broker.attachRegistry(firstRegistry)
        val failure = AtomicReference<Throwable>()
        val first = Thread { runCatching { broker.launch(pendingIntent(), 2_000) }.onFailure(failure::set) }.apply { start() }
        waitUntil { firstRegistry.requests.size == 1 }
        val replacementRegistry = Registry()
        val newOwner = broker.attachRegistry(replacementRegistry)
        broker.detach(oldOwner)
        first.join(1_000)
        assertNotNull(failure.get())
        val result = AtomicReference<Intent>()
        val replacement = Thread { result.set(broker.launch(pendingIntent(), 2_000)) }.apply { start() }
        waitUntil { replacementRegistry.requests.size == 1 }
        firstRegistry.dispatchResult(firstRegistry.requests[0], Activity.RESULT_OK, Intent().putExtra("old", true))
        assertNull(result.get())
        replacementRegistry.dispatchResult(replacementRegistry.requests[0], Activity.RESULT_OK, Intent().putExtra("new", true))
        replacement.join(1_000)
        assertTrue(result.get().getBooleanExtra("new", false))
        broker.detach(newOwner)
    }
}
