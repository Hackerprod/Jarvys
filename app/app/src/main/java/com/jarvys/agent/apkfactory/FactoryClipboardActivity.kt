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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.agent.R
import com.jarvys.factory.runtime.BridgeProtocol
import com.jarvys.factory.runtime.ClipboardWriteControl
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Authenticated clipboard effect at the policy-owning host. Never caches permission in the DEX. */
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
    private var grant: FactoryOperationPolicy.Grant? = null
    private var subscription: AutoCloseable? = null
    private var registration: ClipboardWriteControl.Registration? = null
    private var guard: MemoryUiAutomationGuard.Lease? = null
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
            var acquired: FactoryOperationPolicy.Grant? = null
            var registered: ClipboardWriteControl.Registration? = null
            val outcome = runCatching {
                val verified = verifiedCaller(caller)
                val uid = sourceUid(caller)
                check(!cancelled && !isDestroyed && !isFinishing)
                acquired = operationPolicy().acquire(FactoryOperationPolicy.Subject.installed(verified),
                    FactoryOperationPolicy.Operation.CLIPBOARD_WRITE, FactoryOperationPolicy.binding(incoming.nonce, incoming.text, verified.apk, verified.record))
                check(ADMISSION.compareAndSet(false, true)); ownsAdmission = true
                registered = ClipboardWriteControl.register(incoming.control, incoming.nonce, { sourceUid ->
                    sourceUid == uid && runCatching { verifiedCaller(caller) == verified }.getOrDefault(false)
                }, Runnable { cancelled = true; handler.post { cancel() } })
                acquired!!.requireCurrent(); check(registered?.isRevoked == false && !cancelled)
                verified
            }
            runOnUiThread {
                working = false
                outcome.onSuccess { verified ->
                    if (cancelled || isDestroyed || isFinishing) { acquired?.close(); registered?.close(); release(); return@onSuccess }
                    proof = verified; grant = acquired; registration = registered
                    subscription = acquired!!.onRevoked { cancelled = true; handler.post { cancel() } }
                    if (acquired!!.requiresApproval) showApproval() else advanceAllowed()
                }.onFailure { acquired?.close(); registered?.close(); cancel() }
            }
        }
    }
    private fun showApproval() {
        guard = MemoryUiAutomationGuard.enterProtectedSurface()
        val value = request ?: return cancel()
        val column = PrivateReviewColumn(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 32); privateClipboard() }
        column.addView(TextView(this).apply { setText(R.string.factory_clipboard_title); textSize = 22f; isSaveEnabled = false })
        column.addView(TextView(this).apply { setText(R.string.factory_clipboard_disclosure); isSaveEnabled = false })
        column.addView(TextView(this).apply {
            text = "${proof?.appId}\n${value.text.toByteArray(Charsets.UTF_8).size} bytes\n\n${FactoryDataBackupActivity.inert(value.text.take(16384))}"
            autoLinkMask = 0; linksClickable = false; isSaveEnabled = false
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        })
        val approve = object : Button(this) {
            override fun onFilterTouchEventForSecurity(event: MotionEvent) = super.onFilterTouchEventForSecurity(event) &&
                (Build.VERSION.SDK_INT < 29 || event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED == 0)
        }.apply {
            setText(R.string.factory_clipboard_copy); tag = "factory-clipboard-copy"; filterTouchesWhenObscured = true; isSaveEnabled = false
            setOnClickListener { if (!working) runCatching { perform() }.onFailure { cancel() } }
        }
        column.addView(approve)
        column.addView(Button(this).apply { setText(android.R.string.cancel); setOnClickListener { cancel() } })
        setContentView(ScrollView(this).apply { privateClipboard(); addView(column) })
    }
    private fun active() {
        check(resumed && focused && !working && !cancelled && !closed && !isDestroyed && !isFinishing && registration?.isRevoked == false)
        grant!!.requireCurrent()
    }
    private fun advanceAllowed() { if (resumed && focused && !working && grant?.requiresApproval == false && !closed && !cancelled) runCatching { perform() }.onFailure { cancel() } }
    internal open fun verifiedCaller(caller: String): FactoryDocumentCoordinator.Proof = FactoryDocumentCoordinator.verifyInstalled(this, caller, setOf("clipboard"))
    internal open fun sourceUid(caller: String): Int = packageManager.getApplicationInfo(caller, 0).uid
    internal open fun operationPolicy(): FactoryOperationPolicy = FactoryOperationPolicy.get(this)
    private fun perform() {
        active()
        val value = request ?: error("Missing clipboard value")
        val identity = proof ?: error("Missing clipboard identity")
        grant!!.approveOnce { active(); check(guard != null); guard!!.requireHumanUiInteraction() }
        active(); grant!!.checkBeforeEffect(); working = true
        execute {
            val verified = runCatching { check(verifiedCaller(identity.appId) == identity) }
            runOnUiThread {
                working = false
                verified.onSuccess { runCatching {
                    active(); grant!!.checkBeforeEffect()
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: error("Clipboard unavailable")
                    val clip = ClipData.newPlainText(identity.appId, value.text)
                    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    // Last live policy and lifecycle checkpoint before the actual host clipboard effect.
                    active(); grant!!.checkBeforeEffect(); clipboard.setPrimaryClip(clip)
                    setResult(Activity.RESULT_OK, Intent().putExtra("nonce", value.nonce).putExtra("copied", true))
                    closed = true; release(); finish()
                }.onFailure { cancel() } }.onFailure { cancel() }
            }
        }
    }
    private fun execute(action: () -> Unit) { try { WORKER.execute(action) } catch (_: java.util.concurrent.RejectedExecutionException) { working = false; cancel() } }
    private fun cancel() { cancelled = true; release(); finish() }
    private fun release() {
        handler.removeCallbacks(expiry); subscription?.close(); subscription = null
        grant?.close(); grant = null; registration?.close(); registration = null; guard?.close(); guard = null; request = null; proof = null
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
