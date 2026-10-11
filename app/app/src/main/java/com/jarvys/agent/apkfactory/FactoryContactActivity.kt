package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Policy-authorized single phone/email selection with automatic validated delivery. Never reads an address book. */
open class FactoryContactActivity : ComponentActivity() {
    private val coordinator by lazy { FactoryContactCoordinator.get(applicationContext) }
    private val selection by lazy { FactoryContactSelection(applicationContext) }
    private var guard: MemoryUiAutomationGuard.Lease? = null
    private var authorityWatch: AutoCloseable? = null
    private var recoverySurface = false
    private var session: FactoryContactCoordinator.Session? = null
    private var target: FactoryContactSelection.Target? = null
    private var datum: String? = null
    private var pendingResult: Intent? = null
    private var resultReady = false
    private var resultOk = false
    private val signal = CancellationSignal()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var resumed = false
    @Volatile private var focused = false
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    private var working = false
    private var rejected = false
    private var closing = false
    private var externalOwned = false
    private var terminalPending = false
    private lateinit var status: TextView
    private lateinit var valueView: TextView
    private lateinit var choose: Button
    private lateinit var close: Button
    private val expire = Runnable { revoke(); render() }
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); setResult(Activity.RESULT_CANCELED)
        val recovery = savedInstanceState == null && runCatching { FactoryContactCoordinator.consumeRecoveryToken(intent) }.getOrDefault(false)
        val caller = callingPackage
        val incoming = if (!recovery && savedInstanceState == null && caller != null) runCatching { FactoryContactCoordinator.Request.parse(intent) }.getOrNull() else null
        if (!recovery && incoming == null) { finish(); return }
        recoverySurface = recovery
        if (recovery) session = coordinator.session()
        val column = PrivateReviewColumn(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(24, 32, 24, 32) }
        column.addView(TextView(this).apply { setText(R.string.factory_contacts_title); textSize = 22f; privateContact() })
        status = TextView(this).apply { textSize = 16f; privateContact(); autoLinkMask = 0 }; column.addView(status)
        column.addView(TextView(this).apply { setText(R.string.factory_contacts_value); privateContact() })
        valueView = TextView(this).apply { tag = "factory-contacts-value"; textSize = 18f; privateContact(); autoLinkMask = 0; setPadding(16, 16, 16, 16) }; column.addView(valueView)
        fun button(label: Int, name: String, action: () -> Unit) = object : Button(this) {
            override fun onFilterTouchEventForSecurity(event: MotionEvent) = super.onFilterTouchEventForSecurity(event) &&
                (Build.VERSION.SDK_INT < 29 || event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED == 0)
        }.also {
            it.setText(label); it.tag = "factory-contacts-$name"; it.filterTouchesWhenObscured = true; it.privateContact()
            it.setOnClickListener { if (activeReady()) runCatching(action).onFailure { revoke(); render() } }; column.addView(it)
        }
        choose = button(R.string.factory_contacts_choose, "choose") {
            requireHuman(); coordinator.approve(session ?: error("No request")) { requireHuman() }; releaseGuard(); launchPicker()
        }
        close = button(R.string.factory_external_launch_close, "close") { closeNative() }
        val scroll = ScrollView(this).apply { privateContact(); isFillViewport = true; addView(column) }; setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }; ViewCompat.requestApplyInsets(scroll)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (activeReady() && !working && !externalOwned) runCatching { closeNative() }.onFailure { revoke(); finish() } else { revoke(); finish() } }
        })
        if (incoming != null && caller != null) {
            working = true; val approved = generation
            awaitStartup {
                if (generation != approved || isDestroyed || isFinishing || !coordinator.canBegin()) { working = false; rejected = true; finish() }
                else execute {
                    val outcome = runCatching {
                        val value = coordinator.begin(caller, incoming, preparing = true) { check(generation == approved && !isDestroyed && !isFinishing) }
                        try {
                            value.registration = ExternalLaunchControl.register(incoming.control, incoming.nonce, coordinator.sourceVerifier(value), Runnable {
                                value.revoke(); handler.post { if (!closed && !closing && !value.retired && coordinator.session() === value) { revoke(); render() } }
                            })
                            coordinator.requireActive(value); check(value.registration?.isRevoked == false)
                            val target = selection.discover(incoming.kind); coordinator.requireActive(value); value to target
                        } catch (failure: Exception) { value.revoke(); throw failure }
                        finally { value.preparing = false }
                    }
                    runOnUiThread {
                        working = false
                        outcome.onSuccess { (value, selectedTarget) ->
                            if (generation == approved && !isDestroyed && !isFinishing && !value.revoked.get()) {
                                session = value; target = selectedTarget
                                if (value.grant.requiresApproval) guard = MemoryUiAutomationGuard.enterProtectedSurface()
                                authorityWatch = value.grant.onRevoked {
                                    value.revoke()
                                    handler.post { if (!closed && session === value) { revoke(); releaseGuard(); render() } }
                                }
                                handler.postDelayed(expire, maxOf(0, value.expiresAt - SystemClock.elapsedRealtime()))
                                advance()
                            } else value.revoke()
                        }.onFailure {
                            rejected = true
                            if (coordinator.needsRecovery() && !isDestroyed && !isFinishing) {
                                session = coordinator.session()
                            } else finish()
                        }; render()
                    }
                }
            }
        }
        if (recovery) FactoryContactCoordinator.afterStartup { if (!isDestroyed && !isFinishing) { session = coordinator.session(); render() } }
        render()
    }
    private fun awaitStartup(action: () -> Unit) {
        val approved = generation
        if (!FactoryContactCoordinator.afterStartup {
            if (generation != approved || isDestroyed || isFinishing) { working = false; rejected = true }
            else action()
        }) { working = false; rejected = true; finish() }
    }
    private fun activeReady() = resumed && focused && !closed && !isFinishing && !isDestroyed
    private fun requireActive(approved: Long = generation) { check(activeReady() && approved == generation) }
    private fun humanReady() = activeReady() && guard?.isReadyForUser == true
    private fun requireHuman() { check(humanReady()); guard!!.requireHumanUiInteraction() }
    private fun releaseGuard() { guard?.close(); guard = null }
    private fun active(value: FactoryContactCoordinator.Session, approved: Long) {
        requireActive(approved); coordinator.requireActive(value); value.grant.checkBeforeEffect()
        check(!signal.isCanceled && value.registration?.isRevoked == false)
    }
    private fun advance() {
        val value = session ?: return
        if (!activeReady() || working || rejected || value.revoked.get()) return
        runCatching {
            value.grant.requireCurrent()
            when (coordinator.status()) {
                "review" -> if (!value.grant.requiresApproval) launchPicker()
                "selected" -> if (datum != null) deliver()
            }
        }.onFailure { revoke(); render() }
    }
    private fun execute(action: () -> Unit) {
        try { FactoryContactCoordinator.WORKER.execute(action) }
        catch (_: java.util.concurrent.RejectedExecutionException) { session?.preparing = false; working = false; revoke(); render() }
    }
    private fun revoke() {
        generation++; rejected = true; datum = null; pendingResult = null; resultReady = false; session?.revoke()
        releaseGuard()
        if (::valueView.isInitialized) valueView.text = getString(R.string.factory_contacts_none)
        runCatching { CANCELLATION.execute { runCatching { signal.cancel() } } }
    }
    private fun launchPicker() {
        requireActive(); check(!working); val value = session!!; val exactTarget = target!!; val approved = generation
        working = true; value.preparing = true; render()
        execute {
            val outcome = runCatching {
                selection.verifiedIntent(exactTarget, value.request.kind)
                coordinator.prepareLaunch(value) { requireActive(approved) }; coordinator.verifyReady(value) { requireActive(approved) }
                val intent = selection.verifiedIntent(exactTarget, value.request.kind); active(value, approved); intent
            }
            runOnUiThread {
                working = false; value.preparing = false
                outcome.onSuccess { intent ->
                    try { active(value, approved); externalOwned = true; startActivityForResult(intent, EXTERNAL) }
                    catch (_: Exception) { externalOwned = false; revoke() }
                }.onFailure { revoke() }; render()
            }
        }
    }
    @Deprecated("For-result ownership is required for selected contact data")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val value = session ?: return
        if (requestCode != EXTERNAL || !externalOwned || value.pickerReturned) return
        if (runCatching { coordinator.requireActive(value); check(value.registration?.isRevoked == false && coordinator.status() == "launch_pending") }.isFailure) {
            externalOwned = false; value.pickerReturned = true; revoke(); render(); return
        }
        externalOwned = false; terminalPending = true; value.pickerReturned = true
        resultOk = resultCode == Activity.RESULT_OK && data != null
        pendingResult = if (resultOk) data else null; resultReady = true
        processResult()
    }
    private fun processResult() {
        if (!resultReady || !activeReady() || working) return
        resultReady = false; terminalPending = false
        val value = session ?: return
        val data = pendingResult.also { pendingResult = null }
        if (!resultOk || data == null || rejected || value.revoked.get()) {
            datum = null
            if (!rejected && !value.revoked.get()) runCatching { closeNative() }.onFailure { revoke() }
            else rejected = true
            render(); return
        }
        val approved = generation; working = true; value.preparing = true; render()
        execute {
            val outcome = runCatching {
                coordinator.prepareRead(value) { requireActive(approved) }
                val selected = selection.read(target!!, value.request.kind, data, signal) {
                    active(value, approved); check(value.pickerReturned && value.attempted && coordinator.status() == "launch_pending")
                }
                active(value, approved); coordinator.selected(value); selected
            }
            // URI grants are never persisted, forwarded or widened. Android owns activity-scoped expiry.
            runOnUiThread {
                working = false; value.preparing = false
                outcome.onSuccess { selected ->
                    if (runCatching { active(value, approved) }.isSuccess) datum = selected else revoke()
                }.onFailure { revoke() }; advance(); render()
            }
        }
    }
    private fun deliver() {
        requireActive(); check(!working); val value = session!!; val selected = datum ?: error("No selected value"); val approved = generation
        working = true; render()
        execute {
            val outcome = runCatching { active(value, approved); coordinator.deliver(value, selected) { requireActive(approved) } }
            runOnUiThread {
                working = false; datum = null
                outcome.onSuccess { result ->
                    try {
                        requireActive(approved); check(!signal.isCanceled && SystemClock.elapsedRealtime() < value.expiresAt)
                        value.grant.checkBeforeEffect()
                        setResult(Activity.RESULT_OK, result); closed = true; session = null; finish()
                    } catch (_: Exception) { rejected = true; finish() }
                    finally { authorityWatch?.close(); authorityWatch = null; value.grant.close() }
                }.onFailure { revoke(); render() }
            }
        }
    }
    private fun closeNative() {
        requireActive()
        if (working || session?.preparing == true) { revoke(); render(); return }
        val value = session; val recovery = coordinator.needsRecovery(); val approved = generation
        if (value == null && !recovery) { closed = true; finish(); return }
        authorityWatch?.close(); authorityWatch = null; releaseGuard(); closing = true
        datum = null; pendingResult = null; working = true; render()
        execute {
            val outcome = runCatching { coordinator.close(value, recovery, true) { requireActive(approved) } }
            runOnUiThread {
                working = false
                outcome.onSuccess { closed = true; session = null; finish() }.onFailure { closing = false; revoke(); render() }
            }
        }
    }
    private fun render() {
        if (!::status.isInitialized) return
        val value = session
        status.text = buildString {
            append(getString(R.string.factory_contacts_disclosure))
            value?.let {
                append("\n\n").append(getString(R.string.factory_contacts_destination)).append('\n').append(it.proof.appId)
                append("\nVersion: ").append(it.proof.version).append("\nSHA-256: ").append(it.proof.apk)
                append('\n').append(it.request.kind)
            }
            target?.let { append("\n\n").append(it.picker.component.flattenToString()) }
            if (coordinator.needsRecovery()) append("\n\n").append(getString(R.string.factory_documents_unknown))
            else if (rejected) append("\n\n").append(getString(R.string.factory_contacts_unavailable))
            else if (working) append("\n\n").append(getString(R.string.factory_documents_checking))
        }
        valueView.text = datum ?: getString(R.string.factory_contacts_none)
        val ready = activeReady() && !working && !rejected && value != null && !value.revoked.get() && value.registration?.isRevoked == false
        val ask = value?.grant?.requiresApproval == true
        choose.visibility = if (ask) View.VISIBLE else View.GONE
        choose.isEnabled = ready && humanReady() && ask && coordinator.status() == "review" && target != null
        close.setText(if (coordinator.needsRecovery() || (value?.attempted == true && !value.pickerReturned)) R.string.factory_external_launch_recover else R.string.factory_external_launch_close)
        close.isEnabled = activeReady() && !coordinator.isBusy()
    }
    override fun onResume() { super.onResume(); resumed = true; processResult(); advance(); render() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus); focused = hasFocus
        if (!hasFocus && session != null && !closed && !externalOwned && (!terminalPending || resumed)) revoke()
        if (hasFocus) { processResult(); advance() }; render()
    }
    override fun onPause() {
        resumed = false; generation++
        if (!closed && !externalOwned) revoke()
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacks(expire); datum = null; pendingResult = null
        if (!closed) revoke()
        authorityWatch?.close(); authorityWatch = null; releaseGuard()
        super.onDestroy()
    }
    companion object {
        internal const val EXTERNAL = 7401
        private val CANCELLATION = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1)).apply { allowCoreThreadTimeOut(true) }
    }
}
private fun View.privateContact() {
    isSaveEnabled = false; isSaveFromParentEnabled = false
    if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
}
