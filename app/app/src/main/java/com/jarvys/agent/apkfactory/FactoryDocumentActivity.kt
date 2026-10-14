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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.R

/** Exported solely for explicit, for-result calls from an exactly verified Factory APK. */
open class FactoryDocumentActivity : ComponentActivity() {
    private val coordinator by lazy { FactoryDocumentCoordinator.get(applicationContext) }
    private var token: String? = null
    private var request: FactoryDocumentCoordinator.Request? = null
    private var selected: Uri? = null // Never persisted, logged, queried, or opened by the host.
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
    private lateinit var close: Button
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(Activity.RESULT_CANCELED)
        val caller = callingPackage
        val externalRequest = if (savedInstanceState == null && caller != null)
            runCatching { FactoryDocumentCoordinator.Request.parse(intent) }.getOrNull() else null
        if (externalRequest == null) { rejected = true; finish(); return }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 32) }
        column.addView(TextView(this).apply { setText(R.string.factory_documents_title); textSize = 22f })
        status = TextView(this).apply { textSize = 16f }; column.addView(status)
        fun button(label: Int, tagValue: String, action: () -> Unit) = Button(this).also {
            it.setText(label); it.tag = tagValue; it.filterTouchesWhenObscured = true
            it.setOnClickListener { if (activeReady()) runCatching(action).onFailure { rejected = true; render() } }
            column.addView(it)
        }
        close = button(R.string.factory_documents_close, "factory-documents-close") { closeNative() }
        val scroll = ScrollView(this).apply { isFillViewport = true; isSaveEnabled = false; addView(column) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        ViewCompat.requestApplyInsets(scroll)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activeReady() && !working) runCatching { closeNative() }.onFailure { rejected = true; render() }
                else finish()
            }
        })
        if (externalRequest != null && caller != null) {
            val approved = generation
            working = true
            execute {
                val result = runCatching {
                    check(generation == approved && !isDestroyed && !isFinishing)
                    coordinator.begin(caller, externalRequest)
                }
                runOnUiThread {
                    working = false
                    result.onSuccess { owner ->
                        if (!isDestroyed && !isFinishing && generation == approved) {
                            token = owner; request = externalRequest
                            advance()
                        } else runCatching { coordinator.revoke(owner) }
                    }.onFailure { rejected = true; finish() }
                    render()
                }
            }
        }
        render()
    }
    private fun execute(action: () -> Unit) {
        try { worker.execute(action) }
        catch (_: RejectedExecutionException) {
            working = false; rejected = true
            if (token == null && callingPackage != null) finish() else render()
        }
    }
    private fun activeReady() = resumed && !closed && !isFinishing && !isDestroyed
    private fun requireActive(approved: Long = generation) { check(activeReady() && generation == approved) }
    private fun advance() {
        val owner = token ?: return
        if (!activeReady() || working || rejected) return
        runCatching {
            coordinator.requireCurrent(owner)
            when (coordinator.status()) {
                "review" -> launchPicker()
                "selected" -> closeNative(true)
                "cancelled" -> closeNative()
            }
        }.onFailure { selected = null; rejected = true; render() }
    }
    private fun launchPicker() {
        requireActive(); val owner = token ?: error("No request"); val approved = generation
        working = true; render()
        execute {
            val result = runCatching {
                val picker = coordinator.launch(owner) { requireActive(approved) }
                try { trustedPicker(this, picker) }
                catch (failure: Exception) { coordinator.pickerTerminal(owner, false); throw failure }
            }
            runOnUiThread {
                working = false
                result.onSuccess { picker ->
                    if (runCatching { requireActive(approved); coordinator.requireCurrent(owner) }.isSuccess) {
                        launching = true; pickerOwned = true
                        try { startActivityForResult(picker, PICKER) }
                        catch (_: Exception) { pickerOwned = false; launching = false; coordinator.pickerTerminal(owner, false); advance() }
                    } else { runCatching { coordinator.revoke(owner) }; token = null; rejected = true }
                }.onFailure { rejected = true }
                render()
            }
        }
    }
    private fun closeNative(deliver: Boolean = false) {
        requireActive()
        val owner = token
        if (owner == null) { closed = true; finish(); return }
        if (pickerOwned) {
            coordinator.cancelPicker(owner); selected = null
            finishActivity(PICKER)
            render(); return
        }
        val uri = if (deliver) selected ?: error("No selection") else null
        val approved = generation
        if (uri != null) check(checkUriPermission(uri, Process.myPid(), Process.myUid(), flagsFor(request!!.operation)) == PackageManager.PERMISSION_GRANTED)
        working = true; render()
        execute {
            val result = runCatching { coordinator.close(owner, deliver) { requireActive(approved) } }
            runOnUiThread {
                working = false
                result.onSuccess { value ->
                    try {
                        requireActive(approved)
                        if (uri != null && value != null) {
                            val flags = flagsFor(value.operation)
                            check(checkUriPermission(uri, Process.myPid(), Process.myUid(), flags) == PackageManager.PERMISSION_GRANTED)
                            setResult(Activity.RESULT_OK, Intent().setData(uri).apply { clipData = ClipData.newRawUri("document", uri) }
                                .addFlags(flags).putExtra("nonce", value.nonce))
                        }
                    } catch (_: Exception) { rejected = true }
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
        terminalPending = true; advance(); render()
    }
    private fun render() {
        if (!::status.isInitialized) return
        status.text = buildString {
            append(getString(R.string.factory_documents_disclosure))
            if (rejected) append("\n\n").append(getString(R.string.factory_documents_rejected))
            else if (working) append("\n\n").append(getString(R.string.factory_documents_checking))
            else request?.let { append("\n\n").append(callingPackage).append("\n").append(it.operation).append(" · ").append(it.mimeType); it.filename?.let { name -> append("\n").append(name) } }
        }
        close.isEnabled = activeReady() && !working
    }
    override fun onResume() { super.onResume(); resumed = true; launching = false; terminalPending = false; advance(); render() }
    override fun onPause() {
        resumed = false; generation++
        if (!closed && !launching && !pickerOwned && !terminalPending) {
            selected = null; token?.let { runCatching { coordinator.revoke(it) } }; token = null; rejected = true
        }
        super.onPause()
    }
    // Android owns temporary URI-grant lifetime.
    override fun onDestroy() {
        if (!closed) {
            selected = null
            if (pickerOwned) runCatching { finishActivity(PICKER) }
            token?.let { runCatching { coordinator.revoke(it) } }
        }
        super.onDestroy()
    }
    companion object {
        private const val PICKER = 6701
        private val WORKER = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1)).apply { allowCoreThreadTimeOut(true) }
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
