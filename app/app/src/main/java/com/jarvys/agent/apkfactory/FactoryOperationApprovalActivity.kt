package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.agent.R

/** ASK's only human boundary. A returned Activity result never replaces a live Binder check. */
open class FactoryOperationApprovalActivity : ComponentActivity() {
    private var session: FactoryOperationAuthorizationProvider.Session? = null
    private var guard: MemoryUiAutomationGuard.Lease? = null
    private var subscription: AutoCloseable? = null
    private var resumed = false
    private var focused = false
    @Volatile private var cancelled = false
    private var transferred = false
    private var working = false
    private var gestureBlocked = false
    private var details: TextView? = null
    private var approve: Button? = null
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = Runnable { cancel() }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(Activity.RESULT_CANCELED)
        val caller = callingPackage
        val incoming = if (savedInstanceState == null && caller != null) runCatching { parse(intent) }.getOrNull() else null
        if (incoming == null || caller == null) { finish(); return }
        working = true
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = cancel() })
        execute {
            val outcome = runCatching { FactoryOperationAuthorizationProvider.claim(applicationContext, caller, incoming.first, incoming.second) }
            runOnUiThread {
                working = false
                outcome.onSuccess { value ->
                    if (cancelled || isDestroyed || isFinishing) { value.close(); return@onSuccess }
                    session = value
                    subscription = value.grant.onRevoked { cancelled = true; handler.post { cancel() } }
                    if (cancelled || value.closed.get()) { cancel(); return@onSuccess }
                    handler.postDelayed(expiry, maxOf(0, value.deadline - SystemClock.elapsedRealtime()))
                    showReview(value)
                }.onFailure { cancel() }
            }
        }
    }
    private fun showReview(value: FactoryOperationAuthorizationProvider.Session) {
        guard = MemoryUiAutomationGuard.enterProtectedSurface()
        val column = PrivateReviewColumn(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 32); privateOperation() }
        column.addView(TextView(this).apply { privateOperation(); setText(R.string.factory_operation_review_title); textSize = 22f })
        column.addView(TextView(this).apply { privateOperation(); setText(R.string.factory_operation_review_disclosure) })
        details = TextView(this).apply {
            privateOperation(); autoLinkMask = 0; linksClickable = false; setTextIsSelectable(false)
            // All values are inert, typed stored metadata, never text supplied as instructions or an Intent summary.
            text = "${FactoryDataBackupActivity.inert(value.caller)}\n${value.request.operation}\n\n${FactoryDataBackupActivity.inert(value.request.canonicalJson())}"
            tag = "factory-operation-details"; textSize = 14f
        }.also(column::addView)
        approve = Button(this).apply {
            privateOperation(); setText(R.string.factory_operation_review_approve); tag = "factory-operation-approve"
            filterTouchesWhenObscured = true
            setOnClickListener { if (!working) runCatching { approveOnce() }.onFailure { cancel() } }
        }.also(column::addView)
        column.addView(Button(this).apply { privateOperation(); setText(android.R.string.cancel); filterTouchesWhenObscured = true; setOnClickListener { cancel() } })
        val scroll = ScrollView(this).apply { privateOperation(); addView(column) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        ViewCompat.requestApplyInsets(scroll); render()
    }
    private fun ready() = resumed && focused && !working && !cancelled && !transferred && !isDestroyed && !isFinishing &&
        guard?.isReadyForUser == true && session?.closed?.get() == false
    private fun requireHuman() { check(ready()); guard!!.requireHumanUiInteraction() }
    private fun render() { approve?.isEnabled = ready() }
    private fun approveOnce() {
        requireHuman()
        val value = session ?: error("Missing native review")
        working = true; render()
        execute {
            val verified = runCatching { value.requireCurrent() }
            runOnUiThread {
                working = false
                verified.onSuccess {
                    runCatching {
                        requireHuman()
                        value.approve { requireHuman() }
                        value.grant.checkBeforeEffect()
                        // Transfer ownership to the original runtime lease. Closing this UI must not revoke it.
                        transferred = true
                        setResult(Activity.RESULT_OK, Intent().putExtra("nonce", value.nonce))
                        releaseSurface(); finish()
                    }.onFailure { cancel() }
                }.onFailure { cancel() }
            }
        }
    }
    private fun execute(action: () -> Unit) {
        try { FactoryOperationAuthorizationProvider.WORKER.execute(action) }
        catch (_: java.util.concurrent.RejectedExecutionException) { working = false; cancel() }
    }
    private fun cancel() {
        if (!transferred) { cancelled = true; session?.close() }
        releaseSurface(); finish()
    }
    private fun releaseSurface() {
        handler.removeCallbacks(expiry); subscription?.close(); subscription = null
        details?.text = ""; approve?.isEnabled = false
    }
    /** Reject the whole gesture if any DOWN, MOVE, or UP was fully or partially obscured. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) gestureBlocked = false
        if (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 ||
            (Build.VERSION.SDK_INT >= 29 && event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0)) gestureBlocked = true
        if (gestureBlocked) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
            return false
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onResume() { super.onResume(); resumed = true; render() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); focused = hasFocus; render() }
    override fun onPause() { resumed = false; if (!transferred) cancel(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { try { super.onSaveInstanceState(outState) } finally { outState.clear() } }
    override fun onDestroy() {
        if (!transferred) { cancelled = true; session?.close() }
        releaseSurface(); session = null
        val lease = guard; guard = null
        if (lease != null) {
            val decor = window.decorView
            if (!decor.isAttachedToWindow) lease.close() else decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { lease.close(); v.removeOnAttachStateChangeListener(this) }
            })
        }
        super.onDestroy()
    }
    companion object {
        internal fun parse(intent: Intent): Pair<String, String> {
            require(intent.component != null && intent.data == null && intent.clipData == null && intent.selector == null &&
                intent.action == null && intent.type == null && intent.categories == null && intent.`package` == null && intent.flags == 0)
            val extras = intent.extras ?: error("Missing native review token")
            require(extras.keySet() == setOf("reviewToken", "nonce") && !extras.hasFileDescriptors())
            @Suppress("DEPRECATION") val token = extras.get("reviewToken")
            @Suppress("DEPRECATION") val nonce = extras.get("nonce")
            require(token is String && nonce is String && token.matches(Regex("[a-f0-9]{64}")) && nonce.matches(Regex("[a-f0-9]{64}")))
            return token to nonce
        }
    }
}
private fun View.privateOperation() {
    isSaveEnabled = false; isSaveFromParentEnabled = false
    if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
}
