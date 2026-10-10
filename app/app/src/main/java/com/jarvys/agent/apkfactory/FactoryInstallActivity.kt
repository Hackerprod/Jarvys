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

/** No tool can press these buttons: the shared native dispatch guard also covers the external OS UI. */
open class FactoryInstallActivity : ComponentActivity() {
    private lateinit var guard: MemoryUiAutomationGuard.Lease
    private val coordinator by lazy { FactoryInstallCoordinator.get(applicationContext) }
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var resumed = false
    @Volatile private var generation = 0L
    private var working = false
    private var failed = false
    private lateinit var status: TextView
    private lateinit var install: Button
    private lateinit var system: Button
    private lateinit var cancel: Button
    private lateinit var close: Button

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guard = MemoryUiAutomationGuard.enterProtectedSurface()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { textSize = 16f; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(TextView(this).apply { text = getString(R.string.factory_install_title); textSize = 23f })
        column.addView(status)
        fun button(label: Int, tagValue: String, action: () -> Unit): Button = Button(this).also {
            it.setText(label); it.tag = tagValue; it.filterTouchesWhenObscured = true
            it.setOnClickListener { if (humanReady()) action() }; column.addView(it)
        }
        install = button(R.string.factory_install_approve, "factory-install-approve") {
            val approvedGeneration = generation
            working = true; failed = false; render()
            worker.execute {
                val result = runCatching { coordinator.install { check(resumed && generation == approvedGeneration); guard.requireHumanUiInteraction() } }
                runOnUiThread { failed = result.isFailure; working = false; render() }
            }
        }
        system = button(R.string.factory_install_system, "factory-install-system") {
            runCatching {
                val confirmation = coordinator.takeSystemIntent { requireHuman() }
                startActivity(confirmation)
            }.onFailure { failed = true }
            render()
        }
        cancel = button(R.string.factory_install_cancel, "factory-install-cancel") {
            failed = runCatching { coordinator.cancel() }.isFailure; render()
        }
        close = button(R.string.factory_install_close, "factory-install-close") { closeSafely() }
        close.setOnClickListener { if (!guard.isReadyForUser) finish() else if (humanReady()) closeSafely() }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(column); isSaveEnabled = false }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        ViewCompat.requestApplyInsets(scroll)
        coordinator.consumeLaunch(intent.getStringExtra("launch_token")) // Recovery screen never creates fresh authority.
        coordinator.observe { runOnUiThread { if (!isDestroyed) render() } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!guard.isReadyForUser) finish() else if (humanReady()) closeSafely() }
        })
        render()
    }
    private fun humanReady() = resumed && guard.isReadyForUser
    private fun requireHuman() { check(resumed); guard.requireHumanUiInteraction() }
    private fun closeSafely() {
        requireHuman()
        try { coordinator.closeInteraction { requireHuman() }; finish() }
        catch (_: Exception) { status.text = getString(R.string.factory_install_cancel_first) }
    }
    private fun render() {
        if (!::status.isInitialized) return
        val ready = guard.isReadyForUser
        if (!ready) {
            status.setText(R.string.factory_install_reopen)
            install.isEnabled = false; system.isEnabled = false; cancel.isEnabled = false; close.isEnabled = true
            return
        }
        val result = runCatching { coordinator.status() }.getOrNull()
        val state = result?.optString("state") ?: "journal_unavailable"
        status.text = buildString {
            append(getString(R.string.factory_install_disclosure)).append("\n\n")
            if (result?.has("app_id") == true) {
                append(result.optString("app_name")).append("\n").append(result.optString("app_id"))
                append("\n").append(result.optString("version_name")).append(" / ").append(result.optInt("version_code"))
                append("\nAPK SHA-256: ").append(result.optString("apk_sha256"))
                append("\nCertificate SHA-256: ").append(result.optString("certificate_sha256"))
                append("\nReceipt SHA-256: ").append(result.optString("receipt_sha256"))
            }
            if (failed) append("\n\n").append(getString(R.string.factory_install_interrupted))
            append("\n\n").append(getString(R.string.factory_install_state)).append(": ").append(state)
            if (state == "succeeded") append("\n").append(getString(R.string.factory_install_success))
            else append("\n").append(getString(R.string.factory_install_not_success))
        }
        install.isEnabled = resumed && !working && state == "awaiting_user"
        system.isEnabled = resumed && !working && state == "pending_system"
        cancel.isEnabled = resumed && !working && state !in setOf("no_session", "journal_unavailable")
        close.isEnabled = resumed && !working
    }
    override fun onResume() { super.onResume(); resumed = true; render() }
    override fun onPause() {
        resumed = false; generation++
        coordinator.revokeBeforeCommit()
        super.onPause()
    }
    override fun onDestroy() {
        coordinator.observe(null)
        worker.shutdown()
        // Keep screen protection until its actual view leaves the window, including finish/recreation.
        val decor = window.decorView
        if (!decor.isAttachedToWindow) guard.close()
        else decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { guard.close(); v.removeOnAttachStateChangeListener(this) }
        })
        super.onDestroy()
    }
}
