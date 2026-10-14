package com.jarvys.agent.apkfactory

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.factory.runtime.ClipboardWriteControl
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Synthetic installed identity and clipboard service. No real device clipboard or application. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FactoryClipboardActivityTest {
    class TestActivity : FactoryClipboardActivity() {
        internal override fun verifiedCaller(caller: String): FactoryDocumentCoordinator.Proof { check(caller == identity.appId); return identity }
        internal override fun sourceUid(caller: String): Int = Binder.getCallingUid()
            }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val controllers = mutableListOf<ActivityController<TestActivity>>()
    private lateinit var source: ClipboardWriteControl
    @Before fun setup() { source = ClipboardWriteControl(nonce) { true } }
    @After fun cleanup() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }; source.close(); drain()
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    private fun wire() = Intent(context, FactoryClipboardActivity::class.java).putExtras(Bundle().apply {
        putInt("protocolVersion", 1); putString("nonce", nonce); putString("text", "synthetic clipboard text"); putBinder("control", source)
    })
    private fun drain() {
        repeat(3) {
            val worker = FactoryClipboardActivity.WORKER; worker.prestartCoreThread()
            val barrier = FutureTask(Callable { Unit }); check(worker.queue.offer(barrier, 5, TimeUnit.SECONDS)); barrier.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun launch(intent: Intent = wire()): ActivityController<TestActivity> {
        val controller = Robolectric.buildActivity(TestActivity::class.java, intent); controllers += controller
        shadowOf(controller.get()).setCallingPackage(identity.appId)
        controller.create().start().resume().visible(); controller.get().onWindowFocusChanged(true); drain(); return controller
    }
    @Test fun defaultAllowCopiesAtHostAndReturnsBoundedReceiptWithoutUiApproval() {
        val activity = launch().get()
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals("synthetic clipboard text", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(setOf("nonce", "copied"), shadowOf(activity).resultIntent.extras!!.keySet())
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun exactParserRejectsWrongTypesExtrasAndAuthority() {
        for (value in listOf(wire().putExtra("protocolVersion", 1L), wire().putExtra("appId", identity.appId),
            wire().putExtra("text", 1), wire().putExtra("nonce", "short"), wire().putExtra("control", "not a binder"),
            wire().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), wire().setAction(Intent.ACTION_SEND))) {
            assertTrue(runCatching { FactoryClipboardActivity.Request.parse(value) }.isFailure)
        }
        assertEquals("synthetic clipboard text", FactoryClipboardActivity.Request.parse(wire()).text)
    }
    companion object {
        private val nonce = "c".repeat(64)
        private val identity = FactoryDocumentCoordinator.Proof("example.factory.app", "a".repeat(64), "b".repeat(64), 2, "d".repeat(64))
    }
}
