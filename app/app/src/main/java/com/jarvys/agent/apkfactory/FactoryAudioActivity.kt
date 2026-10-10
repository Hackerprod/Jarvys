package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.jarvys.factory.runtime.AudioPlaybackControl
import java.util.concurrent.RejectedExecutionException

/** Native one-shot review/play; neither an incoming intent nor JavaScript can approve playback. */
open class FactoryAudioActivity : ComponentActivity() {
    private val coordinator by lazy { FactoryAudioCoordinator.get(applicationContext) }
    private lateinit var guard: MemoryUiAutomationGuard.Lease
    private var session: FactoryAudioCoordinator.Session? = null
    @Volatile private var resumed = false
    @Volatile private var focused = false
    @Volatile private var generation = 0L
    private var working = false
    private var rejected = false
    private var closed = false
    private lateinit var status: TextView
    private lateinit var play: Button
    private lateinit var close: Button
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(Activity.RESULT_CANCELED)
        val recovery = savedInstanceState == null && runCatching { FactoryAudioCoordinator.consumeRecoveryToken(intent) }.getOrDefault(false)
        val caller = callingPackage
        val incoming = if (!recovery && savedInstanceState == null && caller != null) runCatching { FactoryAudioCoordinator.Request.parse(intent) }.getOrNull() else null
        if (!recovery && incoming == null) { rejected = true; finish(); return }
        if (recovery) { guard = MemoryUiAutomationGuard.enterProtectedSurface(); session = coordinator.session() }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 32); isSaveEnabled = false }
        column.addView(TextView(this).apply { setText(R.string.factory_audio_title); textSize = 22f; isSaveEnabled = false })
        status = TextView(this).apply { textSize = 16f; isSaveEnabled = false }; column.addView(status)
        fun button(label: Int, name: String, action: () -> Unit) = Button(this).also {
            it.setText(label); it.tag = "factory-audio-$name"; it.filterTouchesWhenObscured = true; it.isSaveEnabled = false
            it.setOnClickListener { if (humanReady() && !working) runCatching(action).onFailure { rejected = true; render() } }; column.addView(it)
        }
        play = button(R.string.factory_audio_play, "play") { playNative() }
        close = button(R.string.factory_audio_close, "close") { closeNative() }
        val scroll = ScrollView(this).apply { isFillViewport = true; isSaveEnabled = false; addView(column) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        ViewCompat.requestApplyInsets(scroll)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!humanReady() || working) { revokeNow(); finish(); return }
                runCatching { closeNative() }.onFailure { revokeNow(); finish() }
            }
        })
        if (incoming != null && caller != null) {
            working = true; val approved = generation
            if (!FactoryAudioCoordinator.afterStartup {
                if (generation != approved || isDestroyed || isFinishing || !coordinator.canBegin()) { working = false; rejected = true; finish() }
                else execute {
                    val result = runCatching {
                        val value = coordinator.begin(caller, incoming) { check(generation == approved && !isDestroyed && !isFinishing) }
                        try {
                            value.registration = AudioPlaybackControl.register(incoming.control, incoming.nonce, coordinator.sourceVerifier(value), Runnable {
                                value.revoke()
                                Handler(Looper.getMainLooper()).post { stopNow(value); render() }
                            })
                            coordinator.requireActive(value); check(!value.registration!!.isRevoked); value
                        } catch (failure: Exception) { value.revoke(); throw failure }
                    }
                    runOnUiThread {
                        working = false
                        result.onSuccess { value ->
                            if (!isDestroyed && !isFinishing && generation == approved && !value.revoked.get()) {
                                guard = MemoryUiAutomationGuard.enterProtectedSurface(); session = value; value.player = FactoryAudioPlayer(applicationContext)
                            } else value.revoke()
                        }.onFailure {
                            rejected = true
                            if (coordinator.needsRecovery() && !isDestroyed && !isFinishing) {
                                session = coordinator.session(); if (!::guard.isInitialized) guard = MemoryUiAutomationGuard.enterProtectedSurface()
                            } else finish()
                        }
                        render()
                    }
                }
            }) { working = false; rejected = true; finish() }
        }
        render()
    }
    private fun humanReady() = resumed && focused && ::guard.isInitialized && guard.isReadyForUser && !isFinishing && !isDestroyed
    private fun requireHuman(approved: Long = generation) { check(humanReady() && generation == approved); guard.requireHumanUiInteraction() }
    private fun execute(onRejected: (() -> Unit)? = null, action: () -> Unit) {
        try { FactoryAudioCoordinator.WORKER.execute(action) }
        catch (_: RejectedExecutionException) { onRejected?.invoke(); working = false; rejected = true; session?.revoke(); render() }
    }
    private fun playNative() {
        requireHuman(); val value = session ?: error("Missing audio request"); check(!value.attempted)
        val player = value.player ?: error("Missing native player"); val approved = generation
        working = true; value.preparing.set(true); value.playerClean.set(false); render()
        execute(onRejected = { value.preparing.set(false); value.playerClean.set(player.retryCleanup().cleanupConfirmed) }) {
            val result = runCatching {
                coordinator.preparePlay(value) { requireHuman(approved) }
                val pcm = value.pcm ?: error("Expired audio")
                coordinator.requireActive(value)
                val prepared = player.prepare(pcm)
                // Always return the owned native handle, even when the final identity check fails.
                if (runCatching { coordinator.verifyReady(value) { requireHuman(approved) } }.isFailure) value.revoke()
                prepared
            }
            runOnUiThread {
                working = false; value.preparing.set(false)
                result.onSuccess { prepared ->
                    if (humanReady() && generation == approved && !value.revoked.get() && value.registration?.isRevoked == false) {
                        player.start(prepared, {
                            humanReady() && generation == approved && !value.revoked.get() && value.registration?.isRevoked == false &&
                                runCatching { coordinator.requireActive(value); guard.requireHumanUiInteraction() }.isSuccess
                        }) { stopped ->
                            value.playerClean.set(stopped.cleanupConfirmed)
                            if (!stopped.cleanupConfirmed) value.revoke()
                            render()
                        }
                    } else {
                        value.revoke(); value.playerClean.set(player.discard(prepared).cleanupConfirmed)
                    }
                }.onFailure { value.revoke(); value.playerClean.set(player.stop().cleanupConfirmed); rejected = true }
                render()
            }
        }
    }
    private fun stopNow(value: FactoryAudioCoordinator.Session) {
        val player = value.player
        // Preparation may still own a native handle. Its completion path must dispose it first.
        if (player != null) {
            val result = player.stop()
            if (!working) value.playerClean.set(result.cleanupConfirmed)
        }
    }
    private fun revokeNow() { session?.let { it.revoke(); stopNow(it) } }
    private fun closeNative() {
        requireHuman(); val value = session ?: coordinator.session(); val approved = generation
        if (value != null) {
            check(!value.preparing.get()) { "Native audio preparation is still finishing" }
            val cleanup = value.player?.retryCleanup()
            value.playerClean.set(cleanup?.cleanupConfirmed != false)
            check(value.playerClean.get()) { "Audio cleanup remains unconfirmed" }
        }
        val recovery = coordinator.needsRecovery()
        if (value == null && !recovery) { closed = true; finish(); return }
        working = true; render()
        execute {
            val result = runCatching { coordinator.close(value, recovery) { requireHuman(approved) } }
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
        val value = session; val pcm = value?.pcm
        status.text = buildString {
            append(getString(R.string.factory_audio_disclosure))
            if (pcm != null) append("\n\n").append(value.proof.appId).append("\nPCM16 WAV · ").append(pcm.channels).append(" ch · ").append(pcm.sampleRate).append(" Hz · ").append(pcm.durationMs).append(" ms")
            if (working || coordinator.isBusy()) append("\n\n").append(getString(R.string.factory_audio_checking))
            if (coordinator.needsRecovery()) append("\n\n").append(getString(R.string.factory_audio_unknown))
            else if (value?.attempted == true) append("\n\n").append(getString(R.string.factory_audio_attempted))
            if (rejected) append("\n\n").append(getString(R.string.factory_audio_unavailable))
        }
        play.isEnabled = humanReady() && !working && !rejected && value != null && !value.attempted && !value.revoked.get() && value.registration?.isRevoked == false && coordinator.status() == "review"
        close.setText(if (coordinator.needsRecovery()) R.string.factory_audio_recover else R.string.factory_audio_close)
        close.isEnabled = humanReady() && !working && !coordinator.isBusy() && value?.preparing?.get() != true
    }
    override fun onResume() { super.onResume(); resumed = true; render() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus); focused = hasFocus
        if (!hasFocus && session != null && !closed) { generation++; revokeNow() }
        render()
    }
    override fun onPause() { resumed = false; generation++; if (!closed) revokeNow(); super.onPause() }
    override fun onDestroy() {
        if (!closed) revokeNow()
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
