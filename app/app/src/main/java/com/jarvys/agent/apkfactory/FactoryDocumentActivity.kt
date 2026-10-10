package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.RejectedExecutionException
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.agent.R

/** Exported solely for explicit, for-result calls from an exactly verified Factory APK. */
open class FactoryDocumentActivity : ComponentActivity() {
    private val coordinator by lazy { FactoryDocumentCoordinator.get(applicationContext) }
    private lateinit var guard: MemoryUiAutomationGuard.Lease
    private var token: String? = null
    private var request: FactoryDocumentCoordinator.Request? = null
    private var selected: Uri? = null // Deliberately never persisted, logged, queried, or opened by the host.
    @Volatile private var resumed = false
    @Volatile private var generation = 0L
    private val worker = WORKER
    private var working = false
    private var launching = false
    private var pickerOwned = false
    private var terminalPending = false
    private var rejected = false
    private var closed = false
    private lateinit var status: TextView
    private lateinit var choose: Button
    private lateinit var close: Button
    private lateinit var recover: Button
    private lateinit var use: Button
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(Activity.RESULT_CANCELED)
        val recovery = savedInstanceState == null && runCatching { FactoryDocumentCoordinator.consumeRecoveryToken(intent) }.getOrDefault(false)
        val caller = callingPackage
        val externalRequest = if (!recovery && savedInstanceState == null && caller != null)
            runCatching { FactoryDocumentCoordinator.Request.parse(intent) }.getOrNull() else null
        if (!recovery && externalRequest == null) { rejected = true; finish(); return }
        if (externalRequest != null && !coordinator.canBegin()) { rejected = true; finish(); return }
        if (recovery) guard = MemoryUiAutomationGuard.enterProtectedSurface()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 32) }
        column.addView(TextView(this).apply { setText(R.string.factory_documents_title); textSize = 22f })
        status = TextView(this).apply { textSize = 16f }; column.addView(status)
        fun button(label: Int, tagValue: String, action: () -> Unit) = Button(this).also {
            it.setText(label); it.tag = tagValue; it.filterTouchesWhenObscured = true
            it.setOnClickListener { if (humanReady()) runCatching(action).onFailure { rejected = true; render() } }
            column.addView(it)
        }
        choose = button(R.string.factory_documents_choose, "factory-documents-choose") {
            requireHuman()
            val owner = token ?: error("No request")
            val approved = generation
            working = true; render()
            execute {
                val result = runCatching {
                    val picker = coordinator.launch(owner) { requireHuman(approved) }
                    try { trustedPicker(this, picker) }
                    catch (failure: Exception) { coordinator.pickerTerminal(owner, false); throw failure }
                }
                runOnUiThread {
                    working = false
                    result.onSuccess { picker ->
                        if (humanReady() && generation == approved) {
                            launching = true; pickerOwned = true
                            try { startActivityForResult(picker, PICKER) }
                            catch (_: Exception) { pickerOwned = false; launching = false; coordinator.pickerTerminal(owner, false) }
                        } else { runCatching { coordinator.revoke(owner) }; token = null }
                    }.onFailure { rejected = true }
                    render()
                }
            }
        }
        use = button(R.string.factory_documents_use, "factory-documents-use") { closeNative(true) }
        close = button(R.string.factory_documents_close, "factory-documents-close") { closeNative() }
        recover = button(R.string.factory_documents_recover, "factory-documents-recover") {
            requireHuman(); coordinator.acknowledgeRecovery { requireHuman() }; closed = true; finish()
        }
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
                if (humanReady()) runCatching { closeNative() }.onFailure { rejected = true; render() }
            }
        })
        // A recreated Activity never recovers the old grant or replays the caller's request.
        if (externalRequest != null && caller != null) {
            val approved = generation
            working = true
            execute {
                val result = runCatching {
                    coordinator.begin(caller, externalRequest) to externalRequest
                }
                runOnUiThread {
                    working = false
                    result.onSuccess { (owner, value) ->
                        if (!isDestroyed && !isFinishing && generation == approved) {
                            guard = MemoryUiAutomationGuard.enterProtectedSurface()
                            token = owner; request = value
                        }
                        else runCatching { coordinator.revoke(owner) }
                    }.onFailure { rejected = true; finish() }
                    render()
                }
            }
        } else if (savedInstanceState != null) rejected = true
        render()
    }
    private fun execute(action: () -> Unit) {
        try { worker.execute(action) }
        catch (_: RejectedExecutionException) {
            working = false; rejected = true
            // Busy admission never revokes an already verified owner. Native close can be retried.
            if (token == null && callingPackage != null) finish() else render()
        }
    }
    private fun humanReady() = resumed && ::guard.isInitialized && guard.isReadyForUser
    private fun requireHuman(approved: Long = generation) { check(resumed && generation == approved && !isFinishing && !isDestroyed && ::guard.isInitialized); guard.requireHumanUiInteraction() }
    private fun closeNative(deliver: Boolean = false) {
        requireHuman()
        val owner = token
        if (owner == null) { closed = true; finish(); return }
        if (pickerOwned) {
            coordinator.cancelPicker(owner)
            selected = null
            finishActivity(PICKER) // Ownership is the original for-result Activity, never an arbitrary system task.
            render(); return // Keep durable guard until Android's terminal callback and another native close.
        }
        val uri = if (deliver) selected ?: error("No selection") else null
        val approved = generation
        if (uri != null) check(checkUriPermission(uri, Process.myPid(), Process.myUid(), flagsFor(request!!.operation)) == PackageManager.PERMISSION_GRANTED)
        working = true; render()
        execute {
            val result = runCatching { coordinator.close(owner, deliver) { requireHuman(approved) } }
            runOnUiThread {
                working = false
                result.onSuccess { value ->
                    if (uri != null && value != null && humanReady() && generation == approved) {
                        val flags = flagsFor(value.operation)
                        setResult(Activity.RESULT_OK, Intent().setData(uri).apply { clipData = ClipData.newRawUri("document", uri) }
                            .addFlags(flags).putExtra("nonce", value.nonce))
                    }
                    selected = null; closed = true; token = null; finish()
                }.onFailure { selected = null; rejected = true; render() }
            }
        }
    }
    @Deprecated("Android for-result ownership is required for the picker lifecycle")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICKER || !pickerOwned || token == null) return
        pickerOwned = false; launching = false
        val uri = data?.data
        val flags = request?.let { flagsFor(it.operation) } ?: 0
        val valid = resultCode == Activity.RESULT_OK && uri != null && validResult(data, flags) && allowedDocument(this, uri) &&
            checkUriPermission(uri, Process.myPid(), Process.myUid(), flags) == PackageManager.PERMISSION_GRANTED
        selected = if (valid) uri else null
        runCatching { coordinator.pickerTerminal(token!!, valid) }.onFailure { selected = null; rejected = true }
        terminalPending = true; render()
    }
    private fun render() {
        if (!::status.isInitialized) return
        val recovery = coordinator.needsRecovery()
        status.text = buildString {
            append(getString(R.string.factory_documents_disclosure))
            if (!::guard.isInitialized) append("\n\n").append(getString(R.string.factory_documents_checking))
            else if (recovery) append("\n\n").append(getString(R.string.factory_documents_unknown))
            else if (rejected || !guard.isReadyForUser) append("\n\n").append(getString(R.string.factory_documents_rejected))
            else request?.let { append("\n\n").append(callingPackage).append("\n").append(it.operation).append(" · ").append(it.mimeType); it.filename?.let { name -> append("\n").append(name) } }
            if (selected != null) append("\n\n").append(getString(R.string.factory_documents_selected))
        }
        use.isEnabled = humanReady() && !working && !rejected && selected != null && token != null
        choose.isEnabled = humanReady() && !working && !rejected && token != null && coordinator.status() == "review"
        close.isEnabled = humanReady() && !working
        recover.visibility = if (recovery) View.VISIBLE else View.GONE
        recover.isEnabled = humanReady() && !working && recovery
    }
    override fun onResume() { super.onResume(); resumed = true; launching = false; terminalPending = false; render() }
    override fun onPause() {
        resumed = false; generation++
        if (!closed && !launching && !pickerOwned && !terminalPending) {
            selected = null; token?.let { runCatching { coordinator.revoke(it) } }; token = null; rejected = true
        }
        super.onPause()
    }
    // Input grants are never persisted. Android owns their Activity/task lifetime; physical OS cleanup remains unverified.
    override fun onDestroy() {
        if (!closed) {
            selected = null
            if (pickerOwned) runCatching { finishActivity(PICKER) }
            token?.let { runCatching { coordinator.revoke(it) } }
        }
        if (!::guard.isInitialized) { super.onDestroy(); return }
        val decor = window.decorView
        if (!decor.isAttachedToWindow) guard.close() else decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { guard.close(); v.removeOnAttachStateChangeListener(this) }
        })
        super.onDestroy()
    }
    companion object {
        private const val PICKER = 6701
        private val WORKER = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, SynchronousQueue()).apply { allowCoreThreadTimeOut(true) }
        internal fun trustedPicker(context: Context, intent: Intent): Intent {
            val candidates = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            return Intent(intent).setComponent(selectTrustedPicker(candidates))
        }
        internal fun selectTrustedPicker(candidates: List<ResolveInfo>): ComponentName {
            val trusted = candidates.mapNotNull { it.activityInfo }.filter { info ->
                !info.packageName.isNullOrBlank() && !info.name.isNullOrBlank() &&
                info.exported && info.enabled && info.applicationInfo?.let { app ->
                    app.enabled && app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                } == true
            }.map { ComponentName(it.packageName, it.name) }.distinct()
            check(trusted.size == 1) { "A unique trusted Android document picker is unavailable" }
            return trusted.single()
        }
        internal fun allowedProvider(hostPackage: String, hostUid: Int, provider: ProviderInfo?): Boolean =
            provider != null && provider.packageName !in setOf(hostPackage, "com.jarvys.agent", "com.jarvys.agent.recoverytest") &&
                provider.applicationInfo != null && provider.applicationInfo.uid != hostUid
        internal fun allowedDocument(context: Context, uri: Uri): Boolean = runCatching {
            uri.scheme == "content" && uri.userInfo == null && !uri.authority.isNullOrBlank() &&
                DocumentsContract.isDocumentUri(context, uri) && !DocumentsContract.isTreeUri(uri) &&
                allowedProvider(context.packageName, context.applicationInfo.uid, context.packageManager.resolveContentProvider(uri.authority!!, 0))
        }.getOrDefault(false)
        internal fun flagsFor(operation: String) = if (operation == "open") Intent.FLAG_GRANT_READ_URI_PERMISSION else Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        internal fun validResult(data: Intent?, flags: Int): Boolean {
            val uri = data?.data ?: return false
            if (flags == 0 || uri.scheme != "content" || uri.authority.isNullOrBlank() || uri.toString().length > 8192 || uri.userInfo != null || data.selector != null) return false
            if (data.flags and flags != flags) return false
            val clips = data.clipData
            return clips == null || (clips.itemCount == 1 && clips.getItemAt(0).uri == uri && clips.getItemAt(0).intent == null && clips.getItemAt(0).text == null)
        }
    }
}
