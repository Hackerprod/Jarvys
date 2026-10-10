package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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
import java.util.concurrent.RejectedExecutionException

/** Exported only for explicit for-result requests; Android callingPackage is independently verified. */
open class FactoryFileShareActivity : ComponentActivity() {
    private val coordinator by lazy { FactoryFileShareCoordinator.get(applicationContext) }
    private lateinit var guard: MemoryUiAutomationGuard.Lease
    private var token: String? = null
    private var request: FactoryFileShareCoordinator.Request? = null
    @Volatile private var resumed = false
    @Volatile private var generation = 0L
    private var working = false
    private var external = false
    private var rejected = false
    private var closed = false
    private lateinit var status: TextView
    private lateinit var choose: Button
    private lateinit var close: Button
    private lateinit var acknowledge: Button
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(Activity.RESULT_CANCELED)
        val recovery = savedInstanceState == null && runCatching { FactoryFileShareCoordinator.consumeRecoveryToken(intent) }.getOrDefault(false)
        val caller = callingPackage
        val incoming = if (!recovery && savedInstanceState == null && caller != null)
            runCatching { FactoryFileShareCoordinator.Request.parse(intent) }.getOrNull() else null
        if (!recovery && incoming == null) { rejected = true; finish(); return }
        if (incoming != null && !coordinator.canBegin()) { rejected = true; finish(); return }
        if (recovery) guard = MemoryUiAutomationGuard.enterProtectedSurface()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 32); isSaveEnabled = false }
        column.addView(TextView(this).apply { setText(R.string.factory_file_sharing_title); textSize = 22f; isSaveEnabled = false })
        status = TextView(this).apply { textSize = 16f; isSaveEnabled = false }; column.addView(status)
        fun button(label: Int, name: String, action: () -> Unit) = Button(this).also {
            it.setText(label); it.tag = "factory-file-sharing-$name"; it.filterTouchesWhenObscured = true; it.isSaveEnabled = false
            it.setOnClickListener { if (humanReady() && !working) runCatching(action).onFailure { rejected = true; render() } }
            column.addView(it)
        }
        choose = button(R.string.factory_file_sharing_choose, "choose") {
            requireHuman(); val owner = token ?: error("Missing owner"); val approved = generation
            working = true; render()
            execute {
                val result = runCatching {
                    val chooser = coordinator.prepareChooser(owner, getString(R.string.factory_file_sharing_title)) { requireHuman(approved) }
                    try { trustedChooser(this, chooser) }
                    catch (failure: Exception) { coordinator.chooserLaunchFailed(owner); throw failure }
                }
                runOnUiThread {
                    working = false
                    result.onSuccess { chooser ->
                        if (humanReady() && generation == approved) {
                            external = true
                            try {
                                startActivityForResult(chooser, CHOOSER)
                                coordinator.chooserLaunched(owner)
                            } catch (_: Exception) {
                                external = false; runCatching { coordinator.chooserLaunchFailed(owner) }; rejected = true
                            }
                        } else { coordinator.invalidate(owner); token = null; rejected = true }
                    }.onFailure { rejected = true }
                    render()
                }
            }
        }
        close = button(R.string.factory_file_sharing_close, "close") { closeNative(false) }
        acknowledge = button(R.string.factory_file_sharing_acknowledge, "acknowledge") { closeNative(true) }
        val scroll = ScrollView(this).apply { isFillViewport = true; isSaveEnabled = false; addView(column) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        ViewCompat.requestApplyInsets(scroll)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!::guard.isInitialized || !guard.isReadyForUser) { finish(); return }
                if (!humanReady() || working) return
                // Back cannot acknowledge recipient closure. Leaving retains the durable recovery latch.
                if (coordinator.chooserAttempted() || coordinator.needsRecovery()) {
                    token?.let { coordinator.invalidate(it) }; token = null; finish()
                } else runCatching { closeNative(false) }.onFailure { rejected = true; render() }
            }
        })
        if (incoming != null && caller != null) {
            val approved = generation; working = true
            execute {
                val result = runCatching { coordinator.begin(caller, incoming) {
                    check(generation == approved && !isDestroyed && !isFinishing) { "Request Activity was interrupted" }
                } }
                runOnUiThread {
                    working = false
                    result.onSuccess { owner ->
                        if (!isDestroyed && !isFinishing && generation == approved) {
                            guard = MemoryUiAutomationGuard.enterProtectedSurface(); token = owner; request = incoming
                        } else coordinator.invalidate(owner)
                    }.onFailure {
                        rejected = true
                        if (coordinator.needsRecovery() && !isDestroyed && !isFinishing) {
                            if (!::guard.isInitialized) guard = MemoryUiAutomationGuard.enterProtectedSurface()
                        } else finish()
                    }
                    render()
                }
            }
        }
        render()
    }
    private fun execute(action: () -> Unit) {
        try { FactoryFileShareCoordinator.WORKER.execute(action) }
        catch (_: RejectedExecutionException) { working = false; rejected = true; render() }
    }
    private fun humanReady() = resumed && ::guard.isInitialized && guard.isReadyForUser && !isFinishing && !isDestroyed
    private fun requireHuman(approved: Long = generation) {
        check(resumed && generation == approved && !isFinishing && !isDestroyed && ::guard.isInitialized)
        guard.requireHumanUiInteraction()
    }
    private fun closeNative(acknowledged: Boolean) {
        requireHuman(); val owner = token; val approved = generation
        if (owner == null && !coordinator.needsRecovery()) { closed = true; finish(); return }
        if (owner == null) check(acknowledged)
        working = true; render()
        execute {
            val result = runCatching {
                if (owner != null) coordinator.close(owner, acknowledged) { requireHuman(approved) }
                else { coordinator.acknowledgeRecovery { requireHuman(approved) }; null }
            }
            runOnUiThread {
                working = false
                result.onSuccess { receipt ->
                    if (receipt != null && humanReady() && generation == approved) setResult(Activity.RESULT_OK, receipt)
                    closed = true; token = null; request = null; finish()
                }.onFailure { rejected = true; render() }
            }
        }
    }
    @Deprecated("Chooser return is only a hint; it never establishes delivery or recipient closure")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CHOOSER || !external) return
        external = false
        token?.let { runCatching { coordinator.chooserCallback(it) }.onFailure { rejected = true } }
        // Ignore all chooser result data and codes, including RESULT_OK. Explicit human closure is still required.
        render()
    }
    private fun render() {
        if (!::status.isInitialized) return
        val recovery = coordinator.needsRecovery(); val attempted = coordinator.chooserAttempted()
        status.text = buildString {
            append(getString(R.string.factory_file_sharing_disclosure))
            if (recovery) append("\n\n").append(getString(R.string.factory_file_sharing_unknown))
            else if (working || !::guard.isInitialized) append("\n\n").append(getString(R.string.factory_file_sharing_checking))
            else {
                request?.let { value ->
                    append("\n\n").append(callingPackage).append("\n").append(value.filename)
                    append("\n").append(value.mimeType).append(" · ").append(value.size).append(" bytes")
                }
                if (attempted) append("\n\n").append(getString(R.string.factory_file_sharing_waiting))
                if (rejected || !guard.isReadyForUser) append("\n\n").append(getString(R.string.factory_file_sharing_rejected))
            }
        }
        choose.isEnabled = humanReady() && !working && !rejected && token != null && coordinator.status() == "review"
        close.visibility = if (attempted || recovery) View.GONE else View.VISIBLE
        close.isEnabled = humanReady() && !working && !attempted && !recovery
        acknowledge.visibility = if (attempted || recovery) View.VISIBLE else View.GONE
        acknowledge.isEnabled = humanReady() && !working && (attempted || recovery)
    }
    override fun onResume() { super.onResume(); resumed = true; render() }
    override fun onPause() {
        resumed = false; generation++
        if (!closed && !external) { token?.let { coordinator.invalidate(it) }; token = null; request = null; rejected = true }
        super.onPause()
    }
    override fun onDestroy() {
        if (!closed) { token?.let { coordinator.invalidate(it) }; token = null; request = null }
        if (::guard.isInitialized) {
            val decor = window.decorView
            if (!decor.isAttachedToWindow) guard.close() else decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { guard.close(); v.removeOnAttachStateChangeListener(this) }
            })
        }
        super.onDestroy()
    }
    companion object {
        private const val CHOOSER = 6801
        internal fun trustedChooser(context: Context, chooser: Intent): Intent {
            check(chooser.action == Intent.ACTION_CHOOSER && chooser.component == null && chooser.`package` == null)
            return Intent(chooser).setComponent(selectTrustedChooser(context.packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)))
        }
        internal fun selectTrustedChooser(candidates: List<ResolveInfo>): ComponentName {
            val trusted = candidates.mapNotNull { it.activityInfo }.filter { info ->
                !info.packageName.isNullOrBlank() && !info.name.isNullOrBlank() && info.exported && info.enabled &&
                    info.applicationInfo?.let { app -> app.enabled && app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 } == true
            }.map { ComponentName(it.packageName, it.name) }.distinct()
            check(trusted.size == 1) { "A unique trusted Android chooser is unavailable" }
            return trusted.single()
        }
    }
}
