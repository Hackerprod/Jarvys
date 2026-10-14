package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.R
import com.jarvys.factory.runtime.BridgeProtocol
import com.jarvys.factory.runtime.ClipboardWriteControl
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Authenticated clipboard effect at the host. Never trusts caller supplied identity. */
open class FactoryClipboardActivity : ComponentActivity() {
    internal data class Request(val nonce: String, val text: String, val control: IBinder) {
        companion object {
            fun parse(intent: Intent): Request {
                require(intent.component != null && intent.data == null && intent.clipData == null && intent.selector == null)
                require(intent.action == null && intent.type == null && intent.categories == null && intent.`package` == null && intent.flags == 0)
                val extras = intent.extras ?: error("Missing clipboard request")
                require(extras.keySet() == setOf("protocolVersion", "nonce", "text", "control"))
                @Suppress("DEPRECATION") val version = extras.get("protocolVersion")
                @Suppress("DEPRECATION") val nonce = extras.get("nonce")
                @Suppress("DEPRECATION") val text = extras.get("text")
                @Suppress("DEPRECATION") val control = extras.get("control")
                require(version is Int && version == 1 && nonce is String && nonce.matches(Regex("[a-f0-9]{64}")))
                require(text is String && text.toByteArray(Charsets.UTF_8).size <= BridgeProtocol.MAX_TEXT_BYTES && control is IBinder)
                return Request(nonce, text, control)
            }
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var registration: ClipboardWriteControl.Registration? = null
    private var request: Request? = null
    private var proof: FactoryDocumentCoordinator.Proof? = null
    private var resumed = false
    private var focused = false
    private var working = false
    private var closed = false
    @Volatile private var ownsAdmission = false
    @Volatile private var cancelled = false
    private val expiry = Runnable { cancel() }
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); setResult(Activity.RESULT_CANCELED)
        val caller = callingPackage
        val incoming = if (savedInstanceState == null && caller != null) runCatching { Request.parse(intent) }.getOrNull() else null
        if (incoming == null || caller == null) { finish(); return }
        request = incoming; working = true
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = cancel() })
        handler.postDelayed(expiry, 300_000)
        execute {
            var registered: ClipboardWriteControl.Registration? = null
            val outcome = runCatching {
                val verified = verifiedCaller(caller)
                val uid = sourceUid(caller)
                check(!cancelled && !isDestroyed && !isFinishing)
                check(ADMISSION.compareAndSet(false, true)); ownsAdmission = true
                registered = ClipboardWriteControl.register(incoming.control, incoming.nonce, { sourceUid ->
                    sourceUid == uid && runCatching { verifiedCaller(caller) == verified }.getOrDefault(false)
                }, Runnable { cancelled = true; handler.post { cancel() } })
                check(registered?.isRevoked == false && !cancelled)
                verified
            }
            runOnUiThread {
                working = false
                outcome.onSuccess { verified ->
                    if (cancelled || isDestroyed || isFinishing) { registered?.close(); release(); return@onSuccess }
                    proof = verified; registration = registered
                    advanceAllowed()
                }.onFailure { registered?.close(); cancel() }
            }
        }
    }
    private fun active() {
        check(resumed && focused && !working && !cancelled && !closed && !isDestroyed && !isFinishing && registration?.isRevoked == false)
    }
    private fun advanceAllowed() { if (resumed && focused && !working && registration?.isRevoked == false && !closed && !cancelled) runCatching { perform() }.onFailure { cancel() } }
    internal open fun verifiedCaller(caller: String): FactoryDocumentCoordinator.Proof = FactoryDocumentCoordinator.verifyInstalled(this, caller, setOf("clipboard"))
    internal open fun sourceUid(caller: String): Int = packageManager.getApplicationInfo(caller, 0).uid
    private fun perform() {
        active()
        val value = request ?: error("Missing clipboard value")
        val identity = proof ?: error("Missing clipboard identity")
        active(); working = true
        execute {
            val verified = runCatching { check(verifiedCaller(identity.appId) == identity) }
            runOnUiThread {
                working = false
                verified.onSuccess { runCatching {
                    active()
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: error("Clipboard unavailable")
                    val clip = ClipData.newPlainText(identity.appId, value.text)
                    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    // Recheck installed caller and binder liveness immediately before the clipboard effect.
                    active(); check(verifiedCaller(identity.appId) == identity); clipboard.setPrimaryClip(clip)
                    setResult(Activity.RESULT_OK, Intent().putExtra("nonce", value.nonce).putExtra("copied", true))
                    closed = true; release(); finish()
                }.onFailure { cancel() } }.onFailure { cancel() }
            }
        }
    }
    private fun execute(action: () -> Unit) { try { WORKER.execute(action) } catch (_: java.util.concurrent.RejectedExecutionException) { working = false; cancel() } }
    private fun cancel() { cancelled = true; release(); finish() }
    private fun release() {
        handler.removeCallbacks(expiry); registration?.close(); registration = null; request = null; proof = null
        if (ownsAdmission) { ownsAdmission = false; ADMISSION.set(false) }
    }
    override fun onResume() { super.onResume(); resumed = true; advanceAllowed() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); focused = hasFocus; if (hasFocus) advanceAllowed() }
    override fun onPause() { resumed = false; if (!closed) cancel(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { try { super.onSaveInstanceState(outState) } finally { outState.clear() } }
    override fun onDestroy() { cancelled = true; release(); super.onDestroy() }
    companion object {
        private val ADMISSION = AtomicBoolean()
        internal val WORKER = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1)).apply { allowCoreThreadTimeOut(true) }
    }
}

private fun View.privateClipboard() {
    isSaveEnabled = false; isSaveFromParentEnabled = false
    if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
}
