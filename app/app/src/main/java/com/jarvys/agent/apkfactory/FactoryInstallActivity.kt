package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import com.jarvys.agent.R
import java.util.concurrent.Executors

/** Starts installation and forwards any required consent to Android PackageInstaller. */
open class FactoryInstallActivity : ComponentActivity() {
    private val coordinator by lazy { FactoryInstallCoordinator.get(applicationContext) }
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var resumed = false
    @Volatile private var generation = 0L
    private var working = false
    private var failed = false
    private var automaticPosted = false
    private lateinit var status: TextView
    private lateinit var system: Button
    private lateinit var cancel: Button
    private lateinit var sourceSettings: Button
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { textSize = 16f; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(TextView(this).apply { setText(R.string.factory_install_title); textSize = 23f })
        column.addView(status)
        fun button(label: Int, tagValue: String, action: () -> Unit) = Button(this).also {
            it.setText(label); it.tag = tagValue; it.filterTouchesWhenObscured = true
            it.setOnClickListener { if (ready()) runCatching(action).onFailure { failed = true; render() } }
            column.addView(it)
        }
        system = button(R.string.factory_install_system, "factory-install-system", ::openSystem)
        cancel = button(R.string.factory_install_cancel, "factory-install-cancel") {
            failed = runCatching { coordinator.cancel() }.isFailure
            render()
        }
        sourceSettings = button(R.string.factory_install_open_settings, "factory-install-source-settings") {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        val close = button(R.string.factory_install_close, "factory-install-close") { finish() }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(column); isSaveEnabled = false }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        ViewCompat.requestApplyInsets(scroll)
        coordinator.consumeLaunch(intent.getStringExtra("launch_token"))
        coordinator.observe { runOnUiThread { if (!isDestroyed) render() } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { coordinator.revokeBeforeCommit(); finish() }
        })
        render()
    }

    private fun ready() = resumed && !isFinishing && !isDestroyed
    private fun requireActive(expected: Long = generation) {
        check(resumed && generation == expected && !isFinishing && !isDestroyed)
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
        startActivity(coordinator.takeSystemIntent { requireActive() })
        render()
    }
    private fun render() {
        if (!::status.isInitialized) return
        val result = runCatching { coordinator.status() }.getOrNull()
        val state = result?.optString("state") ?: "journal_unavailable"
        status.text = buildString {
            append(getString(R.string.factory_install_disclosure)).append("\n\n")
            if (state == "permission_required") append(getString(R.string.factory_install_permission_required)).append("\n\n")
            if (state == "permission_unavailable") append(getString(R.string.factory_install_permission_unavailable)).append("\n\n")
            if (state == "installation_unavailable") append(getString(R.string.factory_install_unavailable)).append("\n\n")
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
        sourceSettings.visibility = if (state == "permission_required") View.VISIBLE else View.GONE
        sourceSettings.isEnabled = ready() && !working
        cancel.isEnabled = ready() && !working && state !in setOf("no_session", "journal_unavailable")
        system.isEnabled = ready() && !working && state == "pending_system"
        if (!ready() || working || failed || automaticPosted) return
        if (state == "awaiting_user" || state == "pending_system") {
            automaticPosted = true
            status.post {
                automaticPosted = false
                if (ready() && !working && !failed) runCatching {
                    when (coordinator.status().optString("state")) {
                        "awaiting_user" -> beginInstall()
                        "pending_system" -> openSystem()
                    }
                }.onFailure { failed = true; render() }
            }
        }
    }

    override fun onResume() { super.onResume(); resumed = true; render() }
    override fun onPause() { resumed = false; generation++; coordinator.revokeBeforeCommit(); super.onPause() }
    override fun onDestroy() { coordinator.observe(null); worker.shutdown(); super.onDestroy() }
}
