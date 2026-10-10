package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.Context
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.agent.R
import com.jarvys.factory.runtime.ExternalLaunchControl
import java.util.concurrent.RejectedExecutionException

/** Human reviews the immutable typed input and chooses a native recipient; no automatic navigation. */
open class FactoryExternalLaunchActivity : ComponentActivity() {
    private val coordinator by lazy { FactoryExternalLaunchCoordinator.get(applicationContext) }
    private val targets by lazy { FactoryExternalLaunchTargets(applicationContext) }
    private lateinit var guard: MemoryUiAutomationGuard.Lease
    private var session: FactoryExternalLaunchCoordinator.Session? = null
    private var candidates = emptyList<FactoryExternalLaunchTargets.Target>()
    private var selected: FactoryExternalLaunchTargets.Target? = null
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
    private lateinit var choices: RadioGroup
    private lateinit var open: Button
    private lateinit var close: Button
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(Activity.RESULT_CANCELED)
        val recovery = savedInstanceState == null && runCatching { FactoryExternalLaunchCoordinator.consumeRecoveryToken(intent) }.getOrDefault(false)
        val caller = callingPackage
        val incoming = if (!recovery && savedInstanceState == null && caller != null) runCatching { FactoryExternalLaunchCoordinator.Request.parse(intent) }.getOrNull() else null
        if (!recovery && incoming == null) { rejected = true; finish(); return }
        if (recovery) { guard = MemoryUiAutomationGuard.enterProtectedSurface(); session = coordinator.session() }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 32); isSaveEnabled = false }
        column.addView(TextView(this).apply { setText(R.string.factory_external_launch_title); textSize = 22f; isSaveEnabled = false })
        status = TextView(this).apply { textSize = 16f; isSaveEnabled = false; autoLinkMask = 0 }; column.addView(status)
        choices = RadioGroup(this).apply { isSaveEnabled = false }; column.addView(choices)
        fun button(label: Int, name: String, action: () -> Unit) = object : Button(this) {
            override fun onFilterTouchEventForSecurity(event: MotionEvent) = super.onFilterTouchEventForSecurity(event) && unoccluded(event)
        }.also {
            it.setText(label); it.tag = "factory-external-launch-$name"; it.filterTouchesWhenObscured = true; it.isSaveEnabled = false
            it.setOnClickListener { if (humanReady() && !working) runCatching(action).onFailure { rejected = true; render() } }; column.addView(it)
        }
        open = button(R.string.factory_external_launch_open, "open") { openNative() }
        close = button(R.string.factory_external_launch_close, "close") { closeNative() }
        val scroll = ScrollView(this).apply { isFillViewport = true; isSaveEnabled = false; addView(column) }; setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }; ViewCompat.requestApplyInsets(scroll)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back never declares that an external app has closed.
                if (session?.attempted == true || coordinator.needsRecovery() || working || !humanReady()) {
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
                            value.registration = ExternalLaunchControl.register(incoming.control, incoming.nonce, coordinator.sourceVerifier(value), Runnable {
                                value.revoke(); handler.post { render() }
                            })
                            coordinator.requireActive(value); check(value.registration?.isRevoked == false)
                            val targets = targets.discover(value.request.spec); coordinator.requireActive(value); value to targets
                        } catch (failure: Exception) { value.revoke(); throw failure }
                        finally { value.preparing = false }
                    }
                    runOnUiThread {
                        working = false
                        result.onSuccess { (value, targets) ->
                            if (!isDestroyed && !isFinishing && generation == approved && !value.revoked.get()) {
                                guard = MemoryUiAutomationGuard.enterProtectedSurface(); session = value; candidates = targets
                                targets.forEach { target ->
                                    choices.addView(object : RadioButton(this) {
                                        override fun onFilterTouchEventForSecurity(event: MotionEvent) = super.onFilterTouchEventForSecurity(event) && unoccluded(event)
                                    }.apply {
                                        text = target.component.flattenToString(); tag = target.component.flattenToString()
                                        id = View.generateViewId(); isSaveEnabled = false; filterTouchesWhenObscured = true
                                        setOnClickListener { if (humanReady() && !working && !value.attempted) { requireHuman(); selected = target; render() } }
                                    })
                                }
                                handler.postDelayed(expire, maxOf(0, value.expiresAt - SystemClock.elapsedRealtime()))
                            } else value.revoke()
                        }.onFailure {
                            rejected = true
                            if (coordinator.needsRecovery() && !isDestroyed && !isFinishing) {
                                session = coordinator.session(); if (!::guard.isInitialized) guard = MemoryUiAutomationGuard.enterProtectedSurface()
                            } else finish()
                        }; render()
                    }
                }
            }
        }
        if (recovery) FactoryExternalLaunchCoordinator.afterStartup { if (!isDestroyed && !isFinishing) { session = coordinator.session(); render() } }
        render()
    }
    private fun unoccluded(event: MotionEvent) = Build.VERSION.SDK_INT < 29 || event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED == 0
    private fun awaitStartup(action: () -> Unit) {
        val approved = generation
        fun alive() = generation == approved && !isDestroyed && !isFinishing
        fun reject() { working = false; rejected = true; if (!isDestroyed) finish() }
        if (!FactoryExternalLaunchCoordinator.afterStartup {
            if (!alive()) reject()
            else if (!FactoryBrowserCoordinator.afterStartup {
                if (!alive()) reject()
                else if (!FactoryAudioCoordinator.afterStartup {
                if (!alive()) reject()
                else if (!FactoryFileShareCoordinator.afterStartup { if (alive()) action() else reject() }) reject()
            }) reject()
            }) reject()
        }) reject()
    }
    private fun humanReady() = resumed && focused && ::guard.isInitialized && guard.isReadyForUser && !isFinishing && !isDestroyed
    private fun requireHuman(approved: Long = generation) { check(humanReady() && generation == approved); guard.requireHumanUiInteraction() }
    private fun execute(onRejected: (() -> Unit)? = null, action: () -> Unit) {
        try { FactoryExternalLaunchCoordinator.WORKER.execute(action) }
        catch (_: RejectedExecutionException) { onRejected?.invoke(); working = false; rejected = true; session?.revoke(); render() }
    }
    private fun openNative() {
        requireHuman(); val value = session ?: error("Missing external app request"); val target = selected ?: error("Choose an external app")
        check(!value.attempted && target in candidates); val approved = generation
        working = true; value.preparing = true; render()
        execute(onRejected = { value.preparing = false }) {
            val result = runCatching {
                targets.verifiedIntent(target, value.request.spec)
                coordinator.prepareLaunch(value) { requireHuman(approved) }
                coordinator.verifyReady(value) { requireHuman(approved) }
                val exact = targets.verifiedIntent(target, value.request.spec)
                coordinator.requireActive(value); requireHuman(approved); exact
            }
            runOnUiThread {
                working = false; value.preparing = false
                result.onSuccess { exact ->
                    try {
                        requireHuman(approved); coordinator.requireActive(value)
                        check(value.registration?.isRevoked == false && selected == target)
                        externalDispatched = true
                        startActivity(exact)
                        value.launchRequested = true
                    } catch (_: Exception) { value.revoke(); rejected = true }
                }.onFailure { value.revoke(); rejected = true }; render()
            }
        }
    }
    private fun closeNative() {
        requireHuman(); val value = session ?: coordinator.session(); val approved = generation
        check(value?.preparing != true)
        val recovery = coordinator.needsRecovery()
        if (value == null && !recovery) { closed = true; finish(); return }
        working = true; render()
        execute {
            val result = runCatching { coordinator.close(value, recovery, recovery || value?.attempted == true) { requireHuman(approved) } }
            runOnUiThread {
                working = false
                result.onSuccess { receipt ->
                    if (receipt != null && humanReady() && generation == approved) setResult(Activity.RESULT_OK, receipt)
                    closed = true; session = null; finish()
                }.onFailure { rejected = true; render() }
            }
        }
    }
    private fun render() {
        if (!::status.isInitialized) return
        val value = session
        status.text = buildString {
            append(getString(R.string.factory_external_launch_disclosure))
            if (value != null && ::guard.isInitialized) {
                append("\n\n").append(value.proof.appId).append("\n\n").append(value.request.method)
                append("\n\n").append(value.request.spec.display)
                selected?.let { append("\n\n").append(it.component.flattenToString()) }
            }
            if (working || coordinator.isBusy()) append("\n\n").append(getString(R.string.factory_external_launch_checking))
            if (coordinator.needsRecovery()) append("\n\n").append(getString(R.string.factory_external_launch_unknown))
            else if (value?.attempted == true) append("\n\n").append(getString(R.string.factory_external_launch_attempted))
            if (rejected || (value != null && candidates.isEmpty() && !value.attempted)) append("\n\n").append(getString(R.string.factory_external_launch_unavailable))
        }
        val ready = humanReady() && !working && !rejected && value != null && !value.attempted && !value.revoked.get() && value.registration?.isRevoked == false && coordinator.status() == "review"
        open.isEnabled = ready && selected != null
        for (i in 0 until choices.childCount) choices.getChildAt(i).isEnabled = ready
        close.setText(if (coordinator.needsRecovery() || value?.attempted == true) R.string.factory_external_launch_recover else R.string.factory_external_launch_close)
        close.isEnabled = humanReady() && !working && !coordinator.isBusy() && value?.preparing != true
    }
    override fun onResume() { super.onResume(); resumed = true; render() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus); focused = hasFocus
        if (!hasFocus && session != null && !closed && !externalDispatched) { generation++; session?.revoke() }
        render()
    }
    override fun onPause() { resumed = false; generation++; if (!closed && !externalDispatched) session?.revoke(); super.onPause() }
    override fun onDestroy() {
        handler.removeCallbacks(expire)
        if (!closed) session?.revoke()
        if (::guard.isInitialized) {
            val decor = window.decorView
            if (!decor.isAttachedToWindow) guard.close() else decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { guard.close(); v.removeOnAttachStateChangeListener(this) }
            })
        }
        super.onDestroy()
    }
}
