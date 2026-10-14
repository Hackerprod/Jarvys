package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
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
import java.util.concurrent.RejectedExecutionException

/** Authenticated backup broker. SAF remains the system-owned export destination selector. */
open class FactoryDataBackupActivity : ComponentActivity() {
    private val coordinator by lazy { FactoryDataBackupCoordinator.get(applicationContext) }
    private var session: FactoryDataBackupCoordinator.Session? = null
    private var selected: Uri? = null
    private var expectedNonce: String? = null
    @Volatile private var resumed = false
    @Volatile private var focused = false
    @Volatile private var generation = 0L
    private var working = false
    private var rejected = false
    private var closed = false
    private var pickerOwned = false
    private var launching = false
    private var terminalPending = false
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { session?.revoke(); coordinator.session()?.takeIf { it.plan.nonce == expectedNonce }?.revoke(); selected = null; generation++; render() }
    private lateinit var status: TextView
    private lateinit var close: Button
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); setResult(Activity.RESULT_CANCELED)
        val caller = callingPackage
        val incoming = if (savedInstanceState == null && caller != null) runCatching { FactoryDataBackupCoordinator.Request.parse(intent) }.getOrNull() else null
        if (incoming == null) { rejected = true; finish(); return }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 32) }
        column.addView(TextView(this).apply { privateBackup(); setText(R.string.factory_data_backup_title); textSize = 22f })
        status = TextView(this).apply { privateBackup(); textSize = 16f; autoLinkMask = 0 }; column.addView(status)
        fun button(label: Int, name: String, action: () -> Unit) = Button(this).also {
            it.setText(label); it.tag = "factory-data-backup-$name"; it.filterTouchesWhenObscured = true
            it.setOnClickListener { if (activeReady() && !working) runCatching(action).onFailure { reject() } }; column.addView(it)
        }
        close = button(R.string.factory_data_backup_close, "close") { closeNative() }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(column) }; setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }; ViewCompat.requestApplyInsets(scroll)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (working || !activeReady()) { session?.revoke(); finish(); return }
                runCatching { closeNative() }.onFailure { reject() }
            }
        })
        if (incoming != null && caller != null) {
            expectedNonce = incoming.plan.nonce
            working = true; val admitted = generation
            handler.postDelayed(expire, maxOf(0, incoming.plan.deadline - SystemClock.elapsedRealtime()))
            awaitStartup {
                if (generation != admitted || isDestroyed || isFinishing || !coordinator.canBegin()) { working = false; rejected = true; finish() }
                else execute {
                    var owned: FactoryDataBackupCoordinator.Session? = null
                    val result = runCatching {
                        val value = coordinator.begin(caller, incoming) { check(generation == admitted && !isDestroyed && !isFinishing) }; owned = value
                        coordinator.prepare(value, { check(generation == admitted && !isDestroyed && !isFinishing) }, Runnable { handler.post { selected = null; render() } })
                        value
                    }
                    runOnUiThread {
                        working = false
                        result.onSuccess { value ->
                            if (generation == admitted && !isDestroyed && !isFinishing && !value.revoked.get()) session = value
                            else value.revoke()
                        }.onFailure {
                            owned?.revoke()
                            // Never adopt another Activity's session after an admission or identity failure.
                            session = coordinator.session()?.takeIf { it.request === incoming }
                            rejected = true
                            if (session == null) finish()
                        }
                        if (!isDestroyed && !isFinishing) { render(); advanceAllowed() }
                    }
                }
            }
        render()
    }
    private fun awaitStartup(action: () -> Unit) {
        fun rejectStartup() { working = false; rejected = true; if (!isDestroyed) finish() }
        if (!FactoryDataBackupCoordinator.afterStartup {
            if (!FactoryExternalLaunchCoordinator.afterStartup {
                if (!FactoryBrowserCoordinator.afterStartup {
                    if (!FactoryAudioCoordinator.afterStartup {
                        if (!FactoryFileShareCoordinator.afterStartup(action)) rejectStartup()
                    }) rejectStartup()
                }) rejectStartup()
            }) rejectStartup()
        }) rejectStartup()
    }
    private fun activeReady() = resumed && focused && !isFinishing && !isDestroyed
    private fun advanceAllowed() {
        val value = session ?: return
        if (working || rejected || !activeReady() || value.revoked.get()) return
        if (coordinator.status() == "review" && value.plan.operation == "export") runCatching { launchPicker() }.onFailure { reject() }
        else if (coordinator.status() == "review") runCatching { deliverNative() }.onFailure { reject() }
        else if (coordinator.status() == "selected" && !pickerOwned) runCatching { deliverNative() }.onFailure { reject() }
        else if (coordinator.status() == "cancelled") runCatching { closeNative() }.onFailure { reject() }
    }
    private fun reject() { rejected = true; selected = null; session?.revoke(); render() }
    private fun execute(onRejected: (() -> Unit)? = null, action: () -> Unit) {
        try { FactoryDataBackupCoordinator.WORKER.execute(action) }
        catch (_: RejectedExecutionException) {
            onRejected?.invoke(); working = false
            if (session == null) { rejected = true; finish() } else reject()
        }
    }
    private fun launchPicker() {
        check(activeReady())
        val value = session ?: error("No backup plan")
        if (value.plan.operation == "import") { deliverNative(); return }
        val expected = generation; working = true; render()
        execute {
            val result = runCatching {
                val picker = coordinator.launchPicker(value) { check(activeReady() && generation == expected) }
                try { FactoryDocumentActivity.trustedPicker(this, picker) }
                catch (failure: Exception) { coordinator.pickerTerminal(value, false); throw failure }
            }
            runOnUiThread {
                working = false
                result.onSuccess { picker ->
                    try {
                        check(activeReady() && generation == expected); coordinator.requireActive(value)
                        pickerOwned = true; launching = true; startActivityForResult(picker, PICKER)
                    } catch (_: Exception) {
                        // A failed or uncertain dispatch never becomes another launch opportunity.
                        pickerOwned = false; launching = false; value.revoke(); rejected = true
                    }
                }.onFailure { reject() }; render()
            }
        }
    }
    private fun deliverNative() {
        check(activeReady())
        val value = session ?: error("No backup plan"); check(!pickerOwned)
        val uri = if (value.plan.operation == "export") selected ?: error("No selected document") else null
        if (uri != null) requireWriteGrant(uri)
        val expected = generation; working = true; render()
        execute {
            val result = runCatching { coordinator.complete(value) { check(activeReady() && generation == expected) } }
            runOnUiThread {
                working = false
                result.onSuccess { receipt ->
                    var delivered = false
                    try {
                        check(activeReady() && generation == expected)
                        FactoryDataBackupCoordinator.checkDeadline(value.plan)
                        if (uri != null) {
                            requireWriteGrant(uri)
                            receipt.data = uri; receipt.clipData = ClipData.newRawUri("backup", uri); receipt.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                        coordinator.requireActive(value)
                        setResult(Activity.RESULT_OK, receipt); delivered = true
                    } catch (_: Exception) { value.revoke(); setResult(Activity.RESULT_CANCELED) }
                    if (value.request.protocolVersion == 1) coordinator.finishLegacyHandoff(value, delivered)
                    selected = null; closed = true; session = null; finish()
                }.onFailure { reject() }
            }
        }
    }
    private fun requireWriteGrant(uri: Uri) {
        check(FactoryDocumentActivity.allowedDocument(this, uri))
        check(checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED)
    }
    private fun closeNative() {
        check(activeReady())
        val value = session
        val expected = generation
        if (value == null) { closed = true; finish(); return }
        if (pickerOwned && value != null) {
            selected = null; working = true; value.revoke(); render()
            execute {
                val result = runCatching { coordinator.cancelPicker(value) }
                runOnUiThread {
                    working = false
                    result.onSuccess { runCatching { finishActivity(PICKER) }.onFailure { rejected = true } }.onFailure { rejected = true }
                    render() // Original terminal callback is still required; finishing is not proof of closure.
                }
            }; return
        }
        working = true; render()
        execute {
            val result = runCatching { coordinator.close(value) { check(activeReady() && generation == expected) } }
            runOnUiThread {
                working = false
                result.onSuccess {
                    if (value != null && activeReady() && generation == expected)
                        setResult(Activity.RESULT_CANCELED, Intent().putExtra("nonce", value.plan.nonce).putExtra("planSha256", value.plan.sha256()))
                    selected = null; closed = true; session = null; finish()
                }.onFailure { reject() }
            }
        }
    }
    @Deprecated("The original for-result Activity exclusively owns the picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICKER || !pickerOwned) return
        val value = session ?: return
        pickerOwned = false; launching = false; terminalPending = true
        val uri = data?.data
        val valid = resultCode == Activity.RESULT_OK && uri != null &&
            FactoryDocumentActivity.validResult(data, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) &&
            runCatching { requireWriteGrant(uri) }.isSuccess && !value.revoked.get()
        selected = if (valid) uri else null; working = true; render()
        execute {
            val result = runCatching { coordinator.pickerTerminal(value, valid) }
            runOnUiThread { working = false; result.onFailure { reject() }; render(); advanceAllowed() }
        }
    }
    private fun render() {
        if (!::status.isInitialized) return
        val value = session
        val active = value != null && !value.revoked.get() && value.registration?.isRevoked == false && SystemClock.elapsedRealtime() < value.plan.deadline
        status.text = buildString {
            append(getString(R.string.factory_data_backup_disclosure))
            if (value?.plan?.operation == "export") append("\n\n").append(getString(R.string.factory_data_backup_cloud_warning))
            if (working || coordinator.isBusy()) append("\n\n").append(getString(R.string.factory_data_backup_checking))
            if (selected != null) append("\n\n").append(getString(R.string.factory_data_backup_selected)).append("\n").append(inert(selected.toString()))
            if (rejected || value != null && !active) append("\n\n").append(getString(R.string.factory_data_backup_unavailable))
            if (pickerOwned || value?.pickerOutstanding == true) append("\n\n").append(getString(R.string.factory_data_backup_picker_pending))
        }
        close.isEnabled = activeReady() && !working && !coordinator.isBusy()
    }
    override fun onResume() { super.onResume(); resumed = true; launching = false; terminalPending = false; render(); advanceAllowed() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus); focused = hasFocus
        if (!hasFocus && session != null && !closed && !launching && !pickerOwned && !terminalPending) { generation++; session?.revoke(); selected = null }
        render(); if (hasFocus) advanceAllowed()
    }
    override fun onPause() {
        resumed = false; generation++
        if (!closed && !launching && !pickerOwned && !terminalPending) {
            session?.revoke(); selected = null; rejected = true
        }
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        try {
            super.onSaveInstanceState(outState)
        } finally {
            // Framework/providers may add state; this review must never restore any of it.
            outState.clear()
        }
    }
    override fun onDestroy() {
        handler.removeCallbacks(expire)
        if (!closed) {
            session?.revoke(); selected = null
            if (pickerOwned) runCatching { finishActivity(PICKER) }
        }
        super.onDestroy()
    }
    companion object {
        private const val PICKER = 6708
        /** Display every character while making control and direction-changing characters explicit. */
        internal fun inert(text: String): String = buildString {
            for (character in text) {
                if (Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt() || character == '\u2028' || character == '\u2029') {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else append(character)
            }
        }
    }
}
private fun View.privateBackup() {
    isSaveEnabled = false; isSaveFromParentEnabled = false
    if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
}
