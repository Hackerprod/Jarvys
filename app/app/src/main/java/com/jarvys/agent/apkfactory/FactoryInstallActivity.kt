package com.jarvys.agent.apkfactory

import android.content.Context
import android.os.Bundle
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
import java.util.concurrent.Executors

/** ALLOW progresses without host approval; ASK reviews once; Android owns any real install consent. */
open class FactoryInstallActivity : ComponentActivity() {
    private var guard: MemoryUiAutomationGuard.Lease? = null
    private val coordinator by lazy { FactoryInstallCoordinator.get(applicationContext) }
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var resumed = false
    @Volatile private var generation = 0L
    private var working = false
    private var failed = false
    private var automaticPosted = false
    private lateinit var status: TextView
    private lateinit var install: Button
    private lateinit var system: Button
    private lateinit var cancel: Button
    private lateinit var close: Button
    private lateinit var recover: Button
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { textSize = 16f; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(TextView(this).apply { setText(R.string.factory_install_title); textSize = 23f }); column.addView(status)
        fun button(label: Int, tagValue: String, action: () -> Unit) = Button(this).also {
            it.setText(label); it.tag = tagValue; it.filterTouchesWhenObscured = true
            it.setOnClickListener { if (ready()) runCatching(action).onFailure { failed = true; render() } }; column.addView(it)
        }
        install = button(R.string.factory_install_approve, "factory-install-approve", ::beginInstall)
        system = button(R.string.factory_install_system, "factory-install-system", ::openSystem)
        cancel = button(R.string.factory_install_cancel, "factory-install-cancel") {
            failed = runCatching { coordinator.cancel() }.isFailure; render()
        }
        recover = button(R.string.factory_install_recover, "factory-install-recover") {
            val current = generation; working = true; failed = false; render()
            worker.execute {
                val result = runCatching { coordinator.recoverDamagedJournal { requireActive(current, approval = false) } }
                runOnUiThread { if (!isDestroyed) { failed = result.isFailure; working = false; render() } }
            }
        }
        close = button(R.string.factory_install_close, "factory-install-close", ::closeSafely)
        close.setOnClickListener { if (guard?.isReadyForUser == false) { coordinator.revokeBeforeCommit(); finish() } else if (ready()) closeSafely() }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(column); isSaveEnabled = false }; setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }; ViewCompat.requestApplyInsets(scroll)
        coordinator.consumeLaunch(intent.getStringExtra("launch_token")) // Never creates or restores authority.
        coordinator.observe { runOnUiThread { if (!isDestroyed) render() } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                coordinator.revokeBeforeCommit()
                if (ready()) closeSafely() else finish()
            }
        })
        render()
    }
    private fun ready() = resumed && !isFinishing && !isDestroyed && guard?.isReadyForUser != false
    private fun requireActive(expected: Long = generation, approval: Boolean = true) {
        check(resumed && generation == expected && !isFinishing && !isDestroyed)
        if (approval && coordinator.approvalRequired()) (guard ?: error("Missing configured Ask review")).requireHumanUiInteraction()
    }
    private fun beginInstall() {
        requireActive(); val current = generation
        working = true; failed = false; render()
        worker.execute {
            val result = runCatching { coordinator.install { requireActive(current) } }
            runOnUiThread { if (!isDestroyed) { failed = result.isFailure; working = false; render() } }
        }
    }
    private fun openSystem() {
        requireActive()
        val confirmation = coordinator.takeSystemIntent { requireActive() }
        // The coordinator protects only this real Android consent surface until terminal/cancel evidence.
        startActivity(confirmation)
        render()
    }
    private fun closeSafely() {
        requireActive(approval = false)
        try {
            val state = coordinator.status().optString("state")
            if (state in setOf("awaiting_user", "revoked")) coordinator.cancel()
            coordinator.closeInteraction { requireActive(approval = false) }; finish()
        } catch (_: Exception) { status.setText(R.string.factory_install_cancel_first) }
    }
    private fun render() {
        if (!::status.isInitialized) return
        if (coordinator.approvalRequired()) { if (guard == null) guard = MemoryUiAutomationGuard.enterProtectedSurface() }
        else { guard?.close(); guard = null }
        if (guard?.isReadyForUser == false) {
            status.setText(R.string.factory_install_reopen)
            install.isEnabled = false; system.isEnabled = false; cancel.isEnabled = false; recover.isEnabled = false; close.isEnabled = true
            return
        }
        val result = runCatching { coordinator.status() }.getOrNull()
        val state = result?.optString("state") ?: "journal_unavailable"
        status.text = buildString {
            append(getString(R.string.factory_install_disclosure)).append("\n\n")
            val detail = when (state) {
                "permission_required" -> R.string.factory_install_permission_required
                "permission_unavailable" -> R.string.factory_install_permission_unavailable
                "installation_unavailable" -> R.string.factory_install_unavailable
                "journal_unavailable" -> R.string.factory_install_recovery_warning
                "recovery_outcome_unknown" -> R.string.factory_install_recovery_done
                else -> null
            }
            if (detail != null) append(getString(detail)).append("\n\n")
            if (result?.has("app_id") == true) {
                append(result.optString("app_name")).append("\n").append(result.optString("app_id"))
                append("\n").append(result.optString("version_name")).append(" / ").append(result.optInt("version_code"))
                append("\nAPK SHA-256: ").append(result.optString("apk_sha256"))
                append("\nCertificate SHA-256: ").append(result.optString("certificate_sha256"))
                append("\nReceipt SHA-256: ").append(result.optString("receipt_sha256"))
            }
            if (failed) append("\n\n").append(getString(R.string.factory_install_interrupted))
            append("\n\n").append(getString(R.string.factory_install_state)).append(": ").append(state)
            append("\n").append(getString(if (state == "succeeded") R.string.factory_install_success else R.string.factory_install_not_success))
        }
        install.visibility = if (coordinator.approvalRequired()) View.VISIBLE else View.GONE
        install.isEnabled = ready() && !working && state == "awaiting_user"
        system.isEnabled = ready() && !working && state == "pending_system"
        cancel.isEnabled = ready() && !working && state !in setOf("no_session", "journal_unavailable")
        recover.isEnabled = ready() && !working && state == "journal_unavailable"
        recover.visibility = if (state == "journal_unavailable") View.VISIBLE else View.GONE
        close.isEnabled = resumed && !working
        val proceed = (state == "awaiting_user" && coordinator.automatic()) ||
            (state == "pending_system" && runCatching { coordinator.requireAuthority() }.isSuccess)
        if (proceed && ready() && !working && !failed && !automaticPosted) {
            automaticPosted = true
            status.post {
                automaticPosted = false
                if (ready() && !working && !failed) runCatching {
                    when (coordinator.status().optString("state")) {
                        "awaiting_user" -> if (coordinator.automatic()) beginInstall()
                        "pending_system" -> openSystem()
                    }
                }.onFailure { failed = true; render() }
            }
        }
    }
    override fun onResume() { super.onResume(); resumed = true; render() }
    override fun onPause() { resumed = false; generation++; coordinator.revokeBeforeCommit(); super.onPause() }
    override fun onDestroy() { coordinator.observe(null); worker.shutdown(); guard?.close(); guard = null; super.onDestroy() }
}
