package com.jarvys.agent.apkfactory

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvys.agent.AppLanguageRuntime
import com.jarvys.agent.R
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.PrivateKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-only identity management. No tool, Intent extra, web bridge or saved state accepts secrets.
 * SAF selection happens BEFORE passphrase entry, so a picker never retains a plaintext secret.
 * Background/recreation revokes pending approval. Already-started atomic commits may finish.
 */
open class FactoryIdentityActivity : ComponentActivity() {
    private enum class Action { CREATE, EXPORT, IMPORT }
    private data class Picker(val action: Action, val appId: String?, val state: FactorySigningIdentity.State?)
    private data class SelectedDocument(val picker: Picker, val uri: Uri)
    private data class ImportPreview(val material: FactoryIdentityBackup.Material, val expectedRecord: String?)

    private val worker = Executors.newSingleThreadExecutor()
    private val identities by lazy { identityStore() }
    private var foreground = false
    private var picker: Picker? = null
    private var selectedDocument: SelectedDocument? = null
    private var operation: AtomicBoolean? = null
    private var preview: ImportPreview? = null
    private var encryptedInput: ByteArray? = null
    private var dialog: AlertDialog? = null
    private val secretFields = mutableListOf<EditText>()
    private val pendingPasswords = CopyOnWriteArrayList<CharArray>()
    private val actionButtons = mutableListOf<Button>()
    private lateinit var appIdField: EditText
    private lateinit var status: TextView

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        receiveDocument(it)
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        receiveDocument(it)
    }

    internal open fun identityStore(): FactorySigningIdentity = FactorySigningIdentity(applicationContext)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val layout = column()
        layout.addView(label(R.string.factory_identity_title, 23f))
        layout.addView(label(R.string.factory_identity_intro))
        layout.addView(label(R.string.factory_identity_warning))
        appIdField = EditText(this).apply {
            tag = "factory-identity-app-id"
            hint = getString(R.string.factory_identity_app_id)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(127))
            setSingleLine(true)
            disablePersistence(this)
        }
        layout.addView(appIdField)
        addAction(layout, R.string.factory_identity_create, "factory-identity-create") { preparePicker(Action.CREATE) }
        addAction(layout, R.string.factory_identity_export, "factory-identity-export") { preparePicker(Action.EXPORT) }
        addAction(layout, R.string.factory_identity_import, "factory-identity-import") { preparePicker(Action.IMPORT) }
        addAction(layout, R.string.factory_identity_reconcile, "factory-identity-reconcile") { prepareReconciliation() }
        status = label(if (Build.VERSION.SDK_INT >= 26) R.string.factory_identity_ready else R.string.factory_identity_api_required)
        status.tag = "factory-identity-status"
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        layout.addView(status)
        layout.addView(Button(this).apply {
            setText(R.string.factory_identity_close)
            setOnClickListener { cancelSensitiveState(); finish() }
        })
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(layout); disablePersistence(this) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { cancelSensitiveState(); finish() }
        })
        updateEnabled()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        selectedDocument?.let {
            selectedDocument = null
            if (it.picker.action == Action.IMPORT) readImport(it.uri) else showPassphrase(it)
        }
        updateEnabled()
    }

    override fun onPause() {
        foreground = false
        // The only exception is a pending picker; it contains no key, passphrase or plaintext.
        if (picker == null) cancelSensitiveState(interrupted = true)
        else clearSecretFields()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Do not serialize pending approvals, identities, passphrases or document selections.
        if (picker == null) cancelSensitiveState(interrupted = true) else clearSecretFields()
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        cancelSensitiveState()
        worker.shutdown()
        super.onDestroy()
    }

    private fun preparePicker(action: Action) {
        if (!canStart()) return
        if (action == Action.IMPORT) {
            launchPicker(Picker(action, null, null))
            return
        }
        val appId = validatedAppId() ?: return
        startWork(work = { _ -> identities.state(appId) }) { state ->
            if (action == Action.CREATE && state.existing) {
                showStatus(R.string.factory_identity_existing)
            } else if (action == Action.EXPORT && (!state.existing || state.mode != "recoverable")) {
                showStatus(R.string.factory_identity_legacy)
            } else {
                showApproval(getString(if (action == Action.CREATE) R.string.factory_identity_create else R.string.factory_identity_export),
                    getString(if (action == Action.CREATE) R.string.factory_identity_create_disclosure else R.string.factory_identity_export_disclosure, appId),
                    R.string.factory_identity_choose_file) { launchPicker(Picker(action, appId, state)) }
            }
        }
    }

    private fun launchPicker(request: Picker) {
        if (!foreground || isFinishing || isDestroyed || picker != null) return
        picker = request
        updateEnabled()
        try {
            if (request.action == Action.IMPORT) openDocument.launch(arrayOf("application/octet-stream", "application/json", "*/*"))
            else createDocument.launch("${request.appId}-signing-identity.jfi")
        } catch (_: Exception) {
            picker = null
            showStatus(R.string.factory_identity_picker_unavailable)
            updateEnabled()
        }
    }

    private fun receiveDocument(uri: Uri?) {
        val request = picker ?: return // Old results after recreation/cancel are deliberately inert.
        picker = null
        if (uri == null || uri.scheme != "content") {
            selectedDocument = null
            showStatus(R.string.factory_identity_cancelled)
            updateEnabled()
            return
        }
        selectedDocument = SelectedDocument(request, uri)
        if (foreground) {
            val selected = selectedDocument!!
            selectedDocument = null
            if (request.action == Action.IMPORT) readImport(selected.uri) else showPassphrase(selected)
        }
    }

    private fun showPassphrase(selected: SelectedDocument) {
        val form = column()
        form.addView(label(R.string.factory_identity_passphrase_help))
        val first = passwordField(R.string.factory_identity_passphrase, "factory-identity-passphrase")
        val confirm = passwordField(R.string.factory_identity_passphrase_confirm, "factory-identity-passphrase-confirm")
        form.addView(first)
        form.addView(confirm)
        val accepted = CheckBox(this).apply { setText(R.string.factory_identity_backup_ack); disablePersistence(this) }
        form.addView(accepted)
        val feedback = label(R.string.factory_identity_empty)
        form.addView(feedback)
        showForm(getString(if (selected.picker.action == Action.CREATE) R.string.factory_identity_create else R.string.factory_identity_export),
            form, R.string.factory_identity_approve_save) {
            if (!accepted.isChecked) { feedback.setText(R.string.factory_identity_ack_required); return@showForm }
            val passphrase = copyCharacters(first)
            val confirmation = copyCharacters(confirm)
            val valid = try { FactoryIdentityBackup.validatePassphrase(passphrase); passphrase.contentEquals(confirmation) }
            catch (_: Exception) { false }
            finally { wipeCharacters(confirmation) }
            if (!valid) {
                wipeCharacters(passphrase)
                feedback.setText(R.string.factory_identity_invalid_passphrase)
                clearSecretFields()
                return@showForm
            }
            dismissDialog()
            saveBackup(selected, passphrase)
        }
    }

    private fun saveBackup(selected: SelectedDocument, passphrase: CharArray) {
        startWork(work = { approved ->
            var material: FactoryIdentityBackup.Material? = null
            var container: ByteArray? = null
            try {
                check(approved.get())
                material = if (selected.picker.action == Action.CREATE) {
                    val pair = FactoryPortableCertificate.generate()
                    FactoryIdentityBackup.Material(selected.picker.appId!!, pair.key, pair.certificate, 0, null)
                } else identities.exportRecoverableAfterApproval(selected.picker.appId!!, selected.picker.state!!, approved::get)
                check(approved.get())
                container = FactoryIdentityBackup.encrypt(material, passphrase)
                check(container.size <= FactoryIdentityBackup.MAX_CONTAINER_BYTES)
                check(approved.get())
                contentResolver.openOutputStream(selected.uri, "wt")?.use { stream ->
                    check(approved.get())
                    stream.write(container)
                    stream.flush()
                } ?: error("Unavailable document")
                check(approved.get())
                // A provider may silently truncate or append despite a successful close. Verify the
                // exact bounded ciphertext before committing a new local identity. This still says
                // nothing about cloud sync, later durability or independent restoration.
                val readback = contentResolver.openInputStream(selected.uri)?.use {
                    readBounded(it, FactoryIdentityBackup.MAX_CONTAINER_BYTES, approved)
                } ?: error("Backup readback unavailable")
                try { check(java.security.MessageDigest.isEqual(container, readback)) { "Backup readback differs" } }
                finally { readback.fill(0) }
                check(approved.get())
                if (selected.picker.action == Action.CREATE) identities.createRecoverableAfterApproval(material, approved::get)
            } finally {
                wipeCharacters(passphrase)
                container?.fill(0)
                material?.let { destroyKey(it.key) }
            }
        }, failureMessage = R.string.factory_identity_save_failed) {
            showStatus(if (selected.picker.action == Action.CREATE) R.string.factory_identity_created else R.string.factory_identity_exported)
        }
    }

    private fun readImport(uri: Uri) {
        startWork(work = { approved ->
            contentResolver.openInputStream(uri)?.use { readBounded(it, FactoryIdentityBackup.MAX_CONTAINER_BYTES, approved) }
                ?: error("Unavailable document")
        }, discard = { it.fill(0) }) { bytes ->
            encryptedInput = bytes
            val form = column()
            form.addView(label(R.string.factory_identity_import_help))
            val password = passwordField(R.string.factory_identity_passphrase, "factory-identity-passphrase")
            form.addView(password)
            showForm(getString(R.string.factory_identity_import), form, R.string.factory_identity_unlock) {
                val passphrase = copyCharacters(password)
                val encrypted = encryptedInput ?: return@showForm
                encryptedInput = null
                dismissDialog()
                startWork(work = { approved ->
                    var material: FactoryIdentityBackup.Material? = null
                    try {
                        check(approved.get())
                        material = FactoryIdentityBackup.decrypt(encrypted, passphrase)
                        check(approved.get())
                        ImportPreview(material, identities.recoverySnapshot(material.appId))
                    } catch (failure: Exception) {
                        material?.let { destroyKey(it.key) }
                        throw failure
                    } finally { wipeCharacters(passphrase); encrypted.fill(0) }
                }, discard = { destroyKey(it.material.key) }, failureMessage = R.string.factory_identity_unlock_failed) { imported ->
                    preview = imported
                    showImportPreview(imported)
                }
            }
        }
    }

    private fun showImportPreview(imported: ImportPreview) {
        val material = imported.material
        showApproval(getString(R.string.factory_identity_restore_title),
            getString(R.string.factory_identity_restore_preview, material.appId,
                FactoryIdentityBackup.fingerprint(material.certificate), material.lastVersion),
            R.string.factory_identity_approve_restore) {
            if (preview !== imported) return@showApproval
            preview = null // The operation now owns the key and clears it in finally.
            startWork(work = { approved ->
                try { identities.importRecoverableAfterApproval(material, imported.expectedRecord, approved::get) }
                finally { destroyKey(material.key) }
            }) { showStatus(R.string.factory_identity_restored) }
        }
    }

    private fun prepareReconciliation() {
        if (!canStart()) return
        val appId = validatedAppId() ?: return
        startWork(work = { _ -> identities.state(appId) }) { state ->
            if (!state.existing || state.mode != "recoverable") { showStatus(R.string.factory_identity_legacy); return@startWork }
            val form = column()
            form.addView(TextView(this).apply {
                text = getString(R.string.factory_identity_reconcile_help, appId, state.fingerprint.orEmpty(), state.lastVersion,
                    getString(if (state.continuityKnown) R.string.factory_identity_known else R.string.factory_identity_unknown))
            })
            val floor = EditText(this).apply {
                tag = "factory-identity-version-floor"
                hint = getString(R.string.factory_identity_version_floor)
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(10))
                setText(String.format(java.util.Locale.ROOT, "%d", state.lastVersion))
                disablePersistence(this)
            }
            form.addView(floor)
            val checked = CheckBox(this).apply { setText(R.string.factory_identity_reconcile_ack); disablePersistence(this) }
            form.addView(checked)
            val feedback = label(R.string.factory_identity_empty)
            form.addView(feedback)
            showForm(getString(R.string.factory_identity_reconcile), form, R.string.factory_identity_approve_floor) {
                val value = floor.text.toString().toIntOrNull()
                if (!checked.isChecked || value == null || value < state.lastVersion || value < 0) {
                    feedback.setText(R.string.factory_identity_floor_invalid)
                    return@showForm
                }
                dismissDialog()
                startWork(work = { approved -> identities.reconcileAfterApproval(appId, state, value, approved::get) }) {
                    showStatus(R.string.factory_identity_reconciled)
                }
            }
        }
    }

    private fun <T> startWork(work: (AtomicBoolean) -> T, discard: (T) -> Unit = {},
                              failureMessage: Int = R.string.factory_identity_operation_failed, success: (T) -> Unit) {
        if (!foreground || operation != null || isFinishing || isDestroyed) return
        val approved = AtomicBoolean(true)
        operation = approved
        showStatus(R.string.factory_identity_working)
        updateEnabled()
        worker.execute {
            val result = runCatching { work(approved) }
            runOnUiThread {
                if (operation !== approved || !approved.get() || !foreground || isFinishing || isDestroyed) {
                    result.getOrNull()?.let(discard)
                } else {
                    operation = null
                    result.fold(success, { showStatus(failureMessage) })
                    updateEnabled()
                }
            }
        }
    }

    private fun showApproval(title: String, message: String, approvalLabel: Int, approved: () -> Unit) {
        val form = column().apply { addView(TextView(this@FactoryIdentityActivity).apply { text = message }) }
        showForm(title, form, approvalLabel) { dismissDialog(); approved() }
    }

    private fun showForm(title: String, form: LinearLayout, approvalLabel: Int, approved: () -> Unit) {
        if (!foreground || isFinishing || isDestroyed) return
        val scroll = ScrollView(this).apply { addView(form); disablePersistence(this) }
        val created = AlertDialog.Builder(this).setTitle(title).setView(scroll)
            .setPositiveButton(approvalLabel, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelSensitiveState() }
            .create()
        dialog = created
        created.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        created.setOnCancelListener { cancelSensitiveState() }
        created.setOnDismissListener {
            clearSecretFields()
            if (dialog === created) dialog = null
            updateEnabled()
        }
        created.show()
        created.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        created.window?.decorView?.filterTouchesWhenObscured = true
        created.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
        created.getButton(AlertDialog.BUTTON_NEGATIVE).filterTouchesWhenObscured = true
        created.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (foreground && dialog === created && operation == null) approved()
        }
        updateEnabled()
    }

    private fun dismissDialog() { dialog?.dismiss(); dialog = null; clearSecretFields() }

    private fun cancelSensitiveState(interrupted: Boolean = false) {
        val hadPending = operation != null || preview != null || dialog != null || encryptedInput != null
        operation?.set(false)
        operation = null
        pendingPasswords.forEach { it.fill('\u0000') }
        pendingPasswords.clear()
        picker = null
        selectedDocument = null
        encryptedInput?.fill(0)
        encryptedInput = null
        preview?.let { destroyKey(it.material.key) }
        preview = null
        dismissDialog()
        if (hadPending && ::status.isInitialized) showStatus(if (interrupted) R.string.factory_identity_interrupted else R.string.factory_identity_cancelled)
        updateEnabled()
    }

    private fun passwordField(hint: Int, fieldTag: String): EditText = EditText(this).apply {
        tag = fieldTag
        setHint(hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        if (Build.VERSION.SDK_INT >= 26) imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        setSingleLine(true)
        filters = arrayOf(InputFilter.LengthFilter(1024))
        setTextIsSelectable(false)
        isLongClickable = false
        disablePersistence(this)
        secretFields.add(this)
    }

    private fun clearSecretFields() {
        secretFields.forEach { field ->
            field.text?.let { editable ->
                for (index in 0 until editable.length) editable.replace(index, index + 1, "\u0000")
                editable.clear()
            }
        }
        // Keep references only while a form is displayed; invalid input can be entered again.
        if (dialog == null || dialog?.isShowing != true) secretFields.clear()
    }

    private fun copyCharacters(field: EditText): CharArray = field.text.let { value ->
        CharArray(value.length) { value[it] }.also(pendingPasswords::add)
    }
    private fun wipeCharacters(value: CharArray) { value.fill('\u0000'); pendingPasswords.remove(value) }

    private fun validatedAppId(): String? {
        val appId = appIdField.text.toString().trim()
        return try { FactoryIdentityBackup.validateAppId(appId); appId }
        catch (_: Exception) { showStatus(R.string.factory_identity_invalid_app_id); null }
    }

    private fun canStart() = Build.VERSION.SDK_INT >= 26 && foreground && operation == null && picker == null && dialog == null && !isFinishing
    private fun showStatus(message: Int) { if (::status.isInitialized) status.setText(message) }
    private fun updateEnabled() {
        val enabled = canStart()
        actionButtons.forEach { it.isEnabled = enabled }
        if (::appIdField.isInitialized) appIdField.isEnabled = enabled
    }
    private fun addAction(parent: LinearLayout, text: Int, buttonTag: String, action: () -> Unit) {
        val button = Button(this).apply { setText(text); tag = buttonTag; setOnClickListener { action() } }
        actionButtons.add(button)
        parent.addView(button)
    }
    private fun label(text: Int, size: Float = 16f) = TextView(this).apply {
        setText(text); textSize = size; setPadding(0, dp(7), 0, dp(7))
    }
    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(14))
        disablePersistence(this)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun disablePersistence(view: View) {
        view.filterTouchesWhenObscured = true
        view.isSaveEnabled = false
        view.isSaveFromParentEnabled = false
        if (Build.VERSION.SDK_INT >= 26) view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30) view.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }

    companion object {
        private fun destroyKey(key: PrivateKey) = FactoryIdentityBackup.destroyBestEffort(key)

        internal fun readBounded(input: InputStream, limit: Int, approved: AtomicBoolean): ByteArray {
            val buffer = ByteArray(4096)
            val output = ByteArrayOutputStream()
            try {
                while (true) {
                    check(approved.get())
                    val count = input.read(buffer)
                    if (count < 0) return output.toByteArray()
                    check(count > 0 && output.size() <= limit - count) { "Invalid or oversized identity backup" }
                    output.write(buffer, 0, count)
                }
            } finally { buffer.fill(0) }
        }
    }
}
