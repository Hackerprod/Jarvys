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

/** Explicit authenticated for-result broker. Recipient access outlives this short UI lease. */
open class FactoryFileShareActivity : ComponentActivity() {
    private val coordinator by lazy { FactoryFileShareCoordinator.get(applicationContext) }
    private var guard: MemoryUiAutomationGuard.Lease? = null
    private var token: String? = null
    private var request: FactoryFileShareCoordinator.Request? = null
    @Volatile private var resumed = false
    @Volatile private var generation = 0L
    private var working = false
    private var external = false
    private var returned = false
    private var rejected = false
    private var closed = false
    private lateinit var status: TextView
    private lateinit var choose: Button
    private lateinit var close: Button
    private lateinit var acknowledge: Button
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); setResult(Activity.RESULT_CANCELED)
        val recovery = savedInstanceState == null && runCatching { FactoryFileShareCoordinator.consumeRecoveryToken(intent) }.getOrDefault(false)
        val caller = callingPackage
        val incoming = if (!recovery && savedInstanceState == null && caller != null) runCatching { FactoryFileShareCoordinator.Request.parse(intent) }.getOrNull() else null
        if (!recovery && incoming == null) { finish(); return }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 32); isSaveEnabled = false }
        column.addView(TextView(this).apply { setText(R.string.factory_file_sharing_title); textSize = 22f; isSaveEnabled = false })
        status = TextView(this).apply { textSize = 16f; isSaveEnabled = false }; column.addView(status)
        fun button(label: Int, name: String, action: () -> Unit) = Button(this).also {
            it.setText(label); it.tag = "factory-file-sharing-$name"; it.filterTouchesWhenObscured = true; it.isSaveEnabled = false
            it.setOnClickListener { if (humanReady() && !working) runCatching(action).onFailure { reject() } }; column.addView(it)
        }
        choose = button(R.string.factory_file_sharing_choose, "choose") { launchChooser() }
        close = button(R.string.factory_file_sharing_close, "close") { closeNative(false) }
        acknowledge = button(R.string.factory_file_sharing_acknowledge, "acknowledge") { closeNative(true) }
        val scroll = ScrollView(this).apply { isFillViewport = true; isSaveEnabled = false; addView(column) }; setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }; ViewCompat.requestApplyInsets(scroll)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (working || external) { token?.let(coordinator::invalidate); token = null; finish() }
                else runCatching { closeNative(recovery) }.onFailure { reject(); finish() }
            }
        })
        if (incoming != null && caller != null) {
            val expected = generation; working = true
            if (!FactoryFileShareCoordinator.afterStartup {
                if (generation != expected || isDestroyed || isFinishing || !coordinator.canBegin()) { working = false; rejected = true; finish() }
                else execute {
                    val result = runCatching { coordinator.begin(caller, incoming) { check(generation == expected && !isDestroyed && !isFinishing) } }
                    runOnUiThread {
                        working = false
                        result.onSuccess { owner ->
                            if (!isDestroyed && !isFinishing && generation == expected) {
                                token = owner; request = incoming
                                if (coordinator.requiresApproval()) guard = MemoryUiAutomationGuard.enterProtectedSurface()
                            } else coordinator.invalidate(owner)
                        }.onFailure { rejected = true; if (!coordinator.needsRecovery()) finish() }
                        render(); advanceAllowed()
                    }
                }
            }) { working = false; rejected = true; finish() }
        }
        render()
    }
    private fun execute(action: () -> Unit) {
        try { FactoryFileShareCoordinator.WORKER.execute(action) }
        catch (_: RejectedExecutionException) { working = false; reject() }
    }
    private fun humanReady() = resumed && (guard?.isReadyForUser != false) && !isFinishing && !isDestroyed
    private fun requireHuman(expected: Long = generation) {
        check(humanReady() && generation == expected)
        if (coordinator.requiresApproval()) check(guard != null)
        guard?.requireHumanUiInteraction()
    }
    private fun reject() { rejected = true; token?.let(coordinator::invalidate); render() }
    private fun advanceAllowed() {
        if (!humanReady() || working || rejected || token == null || external) return
        if (returned) deliverReceipt()
        else if (!coordinator.requiresApproval() && coordinator.status() == "review") runCatching { launchChooser() }.onFailure { reject() }
    }
    private fun launchChooser() {
        requireHuman(); val owner = token ?: error("Missing owner"); val expected = generation
        working = true; render()
        execute {
            val result = runCatching {
                val chooser = coordinator.prepareChooser(owner, getString(R.string.factory_file_sharing_title)) { requireHuman(expected) }
                try { trustedChooser(this, chooser) } catch (failure: Exception) { coordinator.chooserLaunchFailed(owner); throw failure }
            }
            runOnUiThread {
                working = false
                result.onSuccess { chooser ->
                    try {
                        requireHuman(expected); coordinator.checkCurrent(owner)
                        guard?.close(); guard = null
                        external = true; startActivityForResult(chooser, CHOOSER); coordinator.chooserLaunched(owner)
                    } catch (_: Exception) { external = false; runCatching { coordinator.chooserLaunchFailed(owner) }; reject() }
                }.onFailure { reject() }; render()
            }
        }
    }
    private fun deliverReceipt() {
        val owner = token ?: return; val expected = generation; working = true; render()
        execute {
            val result = runCatching { coordinator.detachRecipientLease(owner) { check(generation == expected && !isDestroyed && !isFinishing) } }
            runOnUiThread {
                working = false
                result.onSuccess { receipt ->
                    if (generation == expected && !isDestroyed && !isFinishing) setResult(Activity.RESULT_OK, receipt)
                    closed = true; token = null; request = null; finish()
                }.onFailure { reject() }
            }
        }
    }
    private fun closeNative(acknowledged: Boolean) {
        requireHuman(); val owner = token; val expected = generation
        if (owner == null && !coordinator.needsRecovery()) { closed = true; finish(); return }
        working = true; render()
        execute {
            val result = runCatching {
                if (owner != null) coordinator.close(owner, acknowledged) { requireHuman(expected) }
                else { coordinator.acknowledgeRecovery { requireHuman(expected) }; null }
            }
            runOnUiThread {
                working = false
                result.onSuccess { receipt ->
                    if (receipt != null && generation == expected && !isDestroyed && !isFinishing) setResult(Activity.RESULT_OK, receipt)
                    closed = true; token = null; request = null; finish()
                }.onFailure { reject() }
            }
        }
    }
    @Deprecated("Chooser return is not proof of delivery or recipient closure")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CHOOSER || !external) return
        external = false; returned = true
        token?.let { runCatching { coordinator.chooserCallback(it) }.onFailure { rejected = true } }
        render(); advanceAllowed()
    }
    private fun render() {
        if (!::status.isInitialized) return
        val recovery = coordinator.needsRecovery()
        status.text = buildString {
            append(getString(R.string.factory_file_sharing_disclosure))
            request?.let { append("\n\n").append(callingPackage).append("\n").append(it.filename).append("\n").append(it.mimeType).append(" · ").append(it.size).append(" bytes") }
            if (working) append("\n\n").append(getString(R.string.factory_file_sharing_checking))
            if (recovery) append("\n\n").append(getString(R.string.factory_file_sharing_unknown))
            if (rejected) append("\n\n").append(getString(R.string.factory_file_sharing_rejected))
        }
        choose.visibility = if (coordinator.requiresApproval()) View.VISIBLE else View.GONE
        choose.isEnabled = humanReady() && !working && !rejected && token != null && coordinator.status() == "review"
        close.visibility = if (recovery) View.GONE else View.VISIBLE
        close.isEnabled = humanReady() && !working && !external
        acknowledge.visibility = if (recovery) View.VISIBLE else View.GONE
        acknowledge.isEnabled = humanReady() && !working && recovery
    }
    override fun onResume() { super.onResume(); resumed = true; render(); advanceAllowed() }
    override fun onPause() {
        resumed = false; generation++
        if (!closed && !external && !returned) { token?.let(coordinator::invalidate); token = null; request = null; rejected = true }
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) { try { super.onSaveInstanceState(outState) } finally { outState.clear() } }
    override fun onDestroy() {
        if (!closed) token?.let(coordinator::invalidate)
        token = null; request = null; guard?.close(); guard = null
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
