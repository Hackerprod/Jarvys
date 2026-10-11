package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
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

/** Native, human-only exact-plan review. Neither archive values nor URI authority enter WebView. */
open class FactoryDataBackupActivity : ComponentActivity() {
    private val coordinator by lazy { FactoryDataBackupCoordinator.get(applicationContext) }
    private lateinit var guard: MemoryUiAutomationGuard.Lease
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
    private var recoveryEntry = false
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { session?.revoke(); coordinator.session()?.takeIf { it.plan.nonce == expectedNonce }?.revoke(); selected = null; generation++; render() }
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var acknowledgement: CheckBox
    private lateinit var approve: Button
    private lateinit var use: Button
    private lateinit var close: Button
    private lateinit var recover: Button
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); setResult(Activity.RESULT_CANCELED)
        recoveryEntry = savedInstanceState == null && runCatching { FactoryDataBackupCoordinator.consumeRecoveryToken(intent) }.getOrDefault(false)
        val caller = callingPackage
        val incoming = if (!recoveryEntry && savedInstanceState == null && caller != null) runCatching { FactoryDataBackupCoordinator.Request.parse(intent) }.getOrNull() else null
        if (!recoveryEntry && incoming == null) { rejected = true; finish(); return }
        guard = MemoryUiAutomationGuard.enterProtectedSurface()
        if (recoveryEntry) session = coordinator.session()
        val column = PrivateReviewColumn(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 32) }
        column.addView(TextView(this).apply { privateBackup(); setText(R.string.factory_data_backup_title); textSize = 22f })
        status = TextView(this).apply { privateBackup(); textSize = 16f; autoLinkMask = 0 }; column.addView(status)
        details = TextView(this).apply { privateBackup(); autoLinkMask = 0; linksClickable = false; setTextIsSelectable(false); textSize = 14f; tag = "factory-data-backup-values" }; column.addView(details)
        acknowledgement = object : CheckBox(this) {
            override fun onFilterTouchEventForSecurity(event: MotionEvent) = super.onFilterTouchEventForSecurity(event) && unoccluded(event)
        }.apply {
            privateBackup(); setText(R.string.factory_data_backup_acknowledgement); tag = "factory-data-backup-acknowledgement"; isChecked = false; filterTouchesWhenObscured = true
            setOnClickListener { if (!humanReady() || working) isChecked = false; render() }
        }; column.addView(acknowledgement)
        fun button(label: Int, name: String, action: () -> Unit) = object : Button(this) {
            override fun onFilterTouchEventForSecurity(event: MotionEvent) = super.onFilterTouchEventForSecurity(event) && unoccluded(event)
        }.also {
            it.privateBackup(); it.setText(label); it.tag = "factory-data-backup-$name"; it.filterTouchesWhenObscured = true
            it.setOnClickListener { if (humanReady() && !working) runCatching(action).onFailure { reject() } }; column.addView(it)
        }
        approve = button(R.string.factory_data_backup_review_approve, "approve") { approveNative() }
        use = button(R.string.factory_data_backup_use, "use") { deliverNative() }
        close = button(R.string.factory_data_backup_close, "close") { closeNative(false) }
        recover = button(R.string.factory_data_backup_recover, "recover") { closeNative(true) }
        val scroll = ScrollView(this).apply { privateBackup(); isFillViewport = true; addView(column) }; setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }; ViewCompat.requestApplyInsets(scroll)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (working || !humanReady() || coordinator.needsRecovery()) { session?.revoke(); finish(); return }
                runCatching { closeNative(false) }.onFailure { reject() }
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
                            else { value.revoke(); value.abandoned = true }
                        }.onFailure {
                            owned?.let { it.revoke(); if (isDestroyed || isFinishing) it.abandoned = true }
                            // Never adopt another Activity's session after an admission or identity failure.
                            session = coordinator.session()?.takeIf { it.request === incoming }
                            rejected = true
                            if (session == null) finish()
                        }
                        if (!isDestroyed && !isFinishing) { populateDetails(); render() }
                    }
                }
            }
        } else FactoryDataBackupCoordinator.afterStartup { if (!isDestroyed && !isFinishing) { session = coordinator.session(); render() } }
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
    private fun unoccluded(event: MotionEvent) = Build.VERSION.SDK_INT < 29 || event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED == 0
    private fun humanReady() = resumed && focused && ::guard.isInitialized && guard.isReadyForUser && !isFinishing && !isDestroyed
    private fun requireHuman(expected: Long = generation) { check(humanReady() && generation == expected); guard.requireHumanUiInteraction() }
    private fun reject() { rejected = true; selected = null; session?.revoke(); render() }
    private fun execute(onRejected: (() -> Unit)? = null, action: () -> Unit) {
        try { FactoryDataBackupCoordinator.WORKER.execute(action) }
        catch (_: RejectedExecutionException) {
            onRejected?.invoke(); working = false
            if (session == null && !recoveryEntry) { rejected = true; finish() } else reject()
        }
    }
    private fun approveNative() {
        requireHuman(); check(acknowledgement.isChecked && !recoveryEntry)
        val value = session ?: error("No backup plan")
        if (value.plan.operation == "import") { deliverNative(); return }
        val expected = generation; working = true; render()
        execute {
            val result = runCatching {
                val picker = coordinator.launchPicker(value) { requireHuman(expected) }
                try { FactoryDocumentActivity.trustedPicker(this, picker) }
                catch (failure: Exception) { coordinator.pickerTerminal(value, false); throw failure }
            }
            runOnUiThread {
                working = false
                result.onSuccess { picker ->
                    try {
                        requireHuman(expected); coordinator.requireActive(value); check(acknowledgement.isChecked)
                        pickerOwned = true; launching = true; startActivityForResult(picker, PICKER)
                    } catch (_: Exception) {
                        // A failed or uncertain dispatch never becomes another launch opportunity.
                        pickerOwned = false; launching = false; value.revoke(); value.abandoned = true; rejected = true
                    }
                }.onFailure { reject() }; render()
            }
        }
    }
    private fun deliverNative() {
        requireHuman(); check(acknowledgement.isChecked && !recoveryEntry)
        val value = session ?: error("No backup plan"); check(!pickerOwned)
        val uri = if (value.plan.operation == "export") selected ?: error("No selected document") else null
        if (uri != null) requireWriteGrant(uri)
        val expected = generation; working = true; render()
        execute {
            val result = runCatching { coordinator.approve(value) { requireHuman(expected) } }
            runOnUiThread {
                working = false
                result.onSuccess { receipt ->
                    try {
                        requireHuman(expected); check(acknowledgement.isChecked)
                        FactoryDataBackupCoordinator.checkDeadline(value.plan)
                        if (uri != null) {
                            requireWriteGrant(uri)
                            receipt.data = uri; receipt.clipData = ClipData.newRawUri("backup", uri); receipt.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                        setResult(Activity.RESULT_OK, receipt)
                    } catch (_: Exception) { setResult(Activity.RESULT_CANCELED) }
                    selected = null; closed = true; session = null; finish()
                }.onFailure { reject() }
            }
        }
    }
    private fun requireWriteGrant(uri: Uri) {
        check(FactoryDocumentActivity.allowedDocument(this, uri))
        check(checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED)
    }
    private fun closeNative(recovery: Boolean) {
        requireHuman()
        val value = session ?: if (recoveryEntry) coordinator.session() else null
        val expected = generation
        if (!recoveryEntry && value == null) { closed = true; finish(); return }
        check(recoveryEntry || coordinator.session() === value)
        if (value == null && !coordinator.needsRecovery()) { closed = true; finish(); return }
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
            val result = runCatching {
                check(recoveryEntry || value != null && coordinator.session() === value)
                coordinator.close(value, recovery, recovery) { requireHuman(expected) }
            }
            runOnUiThread {
                working = false
                result.onSuccess {
                    if (!recovery && value != null && humanReady() && generation == expected)
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
            runOnUiThread { working = false; result.onFailure { reject() }; render() }
        }
    }
    private fun populateDetails() {
        val value = session ?: return
        val archive = value.archive ?: return
        val counts = archive.data()
        details.text = buildString {
            append(getString(R.string.factory_data_backup_exact_plan)).append('\n')
            append(inert(value.plan.json())).append("\n\n")
            append(getString(R.string.factory_data_backup_row_counts)).append('\n')
            for (table in value.plan.tables) {
                append(table).append(": ").append(counts.getJSONArray(table).length())
                if (value.plan.operation == "import") append(" / ").append(value.plan.targetRows().getLong(table))
                append('\n')
            }
            if (value.plan.mode == "replaceSelectedTables") append(getString(R.string.factory_data_backup_delete_warning)).append("\n\n")
            append(getString(R.string.factory_data_backup_archive_values)).append('\n')
            append(inert(String(archive.bytes(), Charsets.UTF_8)))
        }
    }
    private fun render() {
        if (!::status.isInitialized) return
        val value = session
        val recovery = coordinator.needsRecovery() && (recoveryEntry || value != null && coordinator.session() === value)
        val active = value != null && !value.revoked.get() && value.registration?.isRevoked == false && SystemClock.elapsedRealtime() < value.plan.deadline
        if (!active) details.text = ""
        status.text = buildString {
            append(getString(R.string.factory_data_backup_disclosure))
            if (value?.plan?.operation == "export") append("\n\n").append(getString(R.string.factory_data_backup_cloud_warning))
            if (working || coordinator.isBusy()) append("\n\n").append(getString(R.string.factory_data_backup_checking))
            if (selected != null) append("\n\n").append(getString(R.string.factory_data_backup_selected)).append("\n").append(inert(selected.toString()))
            if (recovery) append("\n\n").append(getString(R.string.factory_data_backup_unknown))
            else if (rejected || value != null && !active) append("\n\n").append(getString(R.string.factory_data_backup_unavailable))
            if (pickerOwned || value?.pickerOutstanding == true) append("\n\n").append(getString(R.string.factory_data_backup_picker_pending))
        }
        val ready = humanReady() && !working && !rejected && !recoveryEntry && active
        acknowledgement.isEnabled = ready && coordinator.status() == "review"
        approve.isEnabled = ready && acknowledgement.isChecked && coordinator.status() == "review"
        use.visibility = if (value?.plan?.operation == "export") View.VISIBLE else View.GONE
        use.isEnabled = ready && acknowledgement.isChecked && selected != null && coordinator.status() == "selected"
        close.isEnabled = humanReady() && !working && !coordinator.isBusy() && (!recovery || pickerOwned)
        recover.visibility = if (recovery) View.VISIBLE else View.GONE
        recover.isEnabled = humanReady() && !working && !coordinator.isBusy() && (!pickerOwned || value?.abandoned == true)
    }
    override fun onResume() { super.onResume(); resumed = true; launching = false; terminalPending = false; render() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus); focused = hasFocus
        if (!hasFocus && session != null && !closed && !launching && !pickerOwned && !terminalPending) { generation++; session?.revoke(); selected = null }
        render()
    }
    override fun onPause() {
        resumed = false; generation++
        if (!closed && !launching && !pickerOwned && !terminalPending) {
            session?.revoke(); selected = null; rejected = true
            if (::details.isInitialized) details.text = ""
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
        if (::details.isInitialized) details.text = ""
        if (!closed) {
            session?.let { it.revoke(); it.abandoned = true }; selected = null
            if (pickerOwned) runCatching { finishActivity(PICKER) }
        }
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
