package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
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
import com.jarvys.agent.R
import com.jarvys.factory.contract.BrowserUrl
import com.jarvys.factory.runtime.BrowserLaunchControl
import java.util.concurrent.RejectedExecutionException

/** Authenticated browser handoff. Android resolves a browser when no default is set. */
open class FactoryBrowserActivity : ComponentActivity() {
    private val coordinator by lazy { FactoryBrowserCoordinator.get(applicationContext) }
    private val browsers by lazy { FactoryBrowserTargets(applicationContext) }
    private var session: FactoryBrowserCoordinator.Session? = null
    private var candidates = emptyList<FactoryBrowserTargets.Target>()
    private var selected: FactoryBrowserTargets.Target? = null
    @Volatile private var resumed = false
    @Volatile private var focused = false
    @Volatile private var generation = 0L
    private var working = false
    private var rejected = false
    private var closed = false
    private var externalDispatched = false
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { session?.revoke(); generation++; render() }
    private lateinit var status: TextView
    private lateinit var close: Button
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(Activity.RESULT_CANCELED)
        val caller = callingPackage
        val incoming = if (savedInstanceState == null && caller != null) runCatching { FactoryBrowserCoordinator.Request.parse(intent) }.getOrNull() else null
        if (incoming == null) { rejected = true; finish(); return }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 32); isSaveEnabled = false }
        column.addView(TextView(this).apply { setText(R.string.factory_browser_title); textSize = 22f; isSaveEnabled = false })
        status = TextView(this).apply { textSize = 16f; isSaveEnabled = false; autoLinkMask = 0 }; column.addView(status)
        fun button(label: Int, name: String, action: () -> Unit) = object : Button(this) {
            override fun onFilterTouchEventForSecurity(event: MotionEvent) = super.onFilterTouchEventForSecurity(event) && unoccluded(event)
        }.also {
            it.setText(label); it.tag = "factory-browser-$name"; it.filterTouchesWhenObscured = true; it.isSaveEnabled = false
            it.setOnClickListener { if (lifecycleReady() && !working) runCatching(action).onFailure { rejected = true; render() } }; column.addView(it)
        }
        close = button(R.string.factory_browser_close, "close") { closeNative() }
        val scroll = ScrollView(this).apply { isFillViewport = true; isSaveEnabled = false; addView(column) }; setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }; ViewCompat.requestApplyInsets(scroll)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back closes only this host request; external outcomes remain unconfirmed.
                if (working || !lifecycleReady()) {
                    session?.revoke(); finish(); return
                }
                runCatching { closeNative() }.onFailure { session?.revoke(); finish() }
            }
        })
        if (incoming != null && caller != null) {
            working = true; val approved = generation
            awaitStartup {
                if (generation != approved || isDestroyed || isFinishing || !coordinator.canBegin()) { working = false; rejected = true; finish() }
                else execute {
                    val result = runCatching {
                        val value = coordinator.begin(caller, incoming, preparing = true) { check(generation == approved && !isDestroyed && !isFinishing) }
                        try {
                            value.registration = BrowserLaunchControl.register(incoming.control, incoming.nonce, coordinator.sourceVerifier(value), Runnable {
                                value.revoke(); handler.post { render() }
                            })
                            coordinator.requireActive(value); check(value.registration?.isRevoked == false)
                            val found = browsers.discover()
                            val preferred = browsers.preferred(value.request.url, found)
                            coordinator.requireActive(value); Triple(value, found, preferred)
                        } catch (failure: Exception) { value.revoke(); throw failure }
                        finally { value.preparing = false }
                    }
                    runOnUiThread {
                        working = false
                        result.onSuccess { (value, targets, preferred) ->
                            if (!isDestroyed && !isFinishing && generation == approved && !value.revoked.get()) {
                                session = value; candidates = targets; selected = preferred
                                handler.postDelayed(expire, maxOf(0, value.expiresAt - SystemClock.elapsedRealtime()))
                            } else value.revoke()
                        }.onFailure {
                            rejected = true
                            finish()
                        }; render()
                    }
                }
            }
        }
        render()
    }
    private fun unoccluded(event: MotionEvent) = Build.VERSION.SDK_INT < 29 || event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED == 0
    private fun awaitStartup(action: () -> Unit) {
        val approved = generation
        fun reject() { working = false; rejected = true; if (!isDestroyed) finish() }
        if (!FactoryBrowserCoordinator.afterStartup {
            if (generation == approved && !isDestroyed && !isFinishing) action() else reject()
        }) reject()
    }
    private fun lifecycleReady() = resumed && focused && !isFinishing && !isDestroyed
    private fun requireLifecycle(approved: Long = generation) { check(lifecycleReady() && generation == approved) }
    private fun execute(onRejected: (() -> Unit)? = null, action: () -> Unit) {
        try { FactoryBrowserCoordinator.WORKER.execute(action) }
        catch (_: RejectedExecutionException) { onRejected?.invoke(); working = false; rejected = true; session?.revoke(); render() }
    }
    private fun openNative() {
        requireLifecycle(); val value = session ?: error("Missing browser request"); val target = selected
        check(!value.attempted && (target == null || target in candidates)); val approved = generation
        working = true; value.preparing = true; render()
        execute(onRejected = { value.preparing = false }) {
            val result = runCatching {
                if (target != null) browsers.verifiedIntent(target, value.request.url)
                coordinator.prepareLaunch(value) { requireLifecycle(approved) }
                coordinator.verifyReady(value) { requireLifecycle(approved) }
                val exact = if (target != null) browsers.verifiedIntent(target, value.request.url)
                    else Intent(Intent.ACTION_VIEW, Uri.parse(BrowserUrl.parse(value.request.url).url)).addCategory(Intent.CATEGORY_BROWSABLE).let { Intent.createChooser(it, null) }
                coordinator.requireActive(value); requireLifecycle(approved); exact
            }
            runOnUiThread {
                working = false; value.preparing = false
                result.onSuccess { exact ->
                    try {
                        requireLifecycle(approved); coordinator.requireActive(value)
                        check(value.registration?.isRevoked == false && selected == target)
                        externalDispatched = true
                        startActivity(exact)
                        value.launchRequested = true
                        completeLaunch(value)
                    } catch (_: Exception) { value.revoke(); rejected = true }
                }.onFailure { value.revoke(); rejected = true }; render()
            }
        }
    }
    private fun completeLaunch(value: FactoryBrowserCoordinator.Session) {
        working = true
        execute {
            val result = runCatching { coordinator.close(value) {} }
            runOnUiThread {
                working = false
                result.onSuccess { receipt ->
                    if (receipt != null) setResult(Activity.RESULT_OK, receipt)
                    closed = true; session = null; finish()
                }.onFailure { value.revoke(); rejected = true; render() }
            }
        }
    }
    private fun closeNative() {
        requireLifecycle(); val value = session ?: coordinator.session(); val approved = generation
        check(value?.preparing != true)
        if (value == null) { closed = true; finish(); return }
        working = true; render()
        execute {
            val result = runCatching { coordinator.close(value) { requireLifecycle(approved) } }
            runOnUiThread {
                working = false
                result.onSuccess { receipt ->
                    if (receipt != null && lifecycleReady() && generation == approved) setResult(Activity.RESULT_OK, receipt)
                    closed = true; session = null; finish()
                }.onFailure { rejected = true; render() }
            }
        }
    }
    private fun render() {
        if (!::status.isInitialized) return
        val value = session
        status.text = buildString {
            append(getString(R.string.factory_browser_disclosure))
            if (value != null) {
                append("\n\n").append(value.proof.appId).append("\n\n").append(BrowserUrl.parse(value.request.url).host)
                append("\n\n").append(value.request.url)
                selected?.let { append("\n\n").append(it.component.flattenToString()) }
            }
            if (working || coordinator.isBusy()) append("\n\n").append(getString(R.string.factory_browser_checking))
            if (value?.attempted == true) append("\n\n").append(getString(R.string.factory_browser_attempted))
            if (rejected || (value != null && candidates.isEmpty() && !value.attempted)) append("\n\n").append(getString(R.string.factory_browser_unavailable))
        }
        close.isEnabled = lifecycleReady() && !working && !coordinator.isBusy() && value?.preparing != true
        startIfReady()
    }
    private fun startIfReady() {
        val value = session ?: return
        if (lifecycleReady() && !working && !rejected && !value.attempted && !value.revoked.get() && value.registration?.isRevoked == false && coordinator.status() == "review") {
            runCatching { openNative() }.onFailure { value.revoke(); rejected = true; render() }
        }
    }
    override fun onResume() { super.onResume(); resumed = true; startIfReady(); render() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus); focused = hasFocus
        if (!hasFocus && session != null && !closed && !externalDispatched) { generation++; session?.revoke() }
        startIfReady(); render()
    }
    override fun onPause() { resumed = false; generation++; if (!closed && !externalDispatched) session?.revoke(); super.onPause() }
    override fun onDestroy() {
        handler.removeCallbacks(expire)
        if (!closed) session?.revoke()
        super.onDestroy()
    }
}
