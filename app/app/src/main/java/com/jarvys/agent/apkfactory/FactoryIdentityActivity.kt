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
import com.jarvys.agent.NativeActionPolicy
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.agent.NativeActionPolicy.Operation
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.PrivateKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exact policy-bound identity management. No Intent extra, web bridge or saved state accepts secrets.
 * SAF selection happens BEFORE passphrase entry, so a picker never retains a plaintext secret.
 * Background/recreation revokes pending approval. Already-started atomic commits may finish.
 */
open class FactoryIdentityActivity : ComponentActivity() {
    private enum class Action { CREATE, EXPORT, IMPORT }
    private data class Picker(val action: Action, val appId: String?, val state: FactorySigningIdentity.State?)
    private data class SelectedDocument(val picker: Picker, val uri: Uri)
    private data class ImportPreview(val material: FactoryIdentityBackup.Material, val expectedRecord: String?, val uri: Uri)

    private val worker = Executors.newSingleThreadExecutor()
    private val identities by lazy { identityStore() }
    private val policies by lazy { actionPolicy() }
    @Volatile private var foreground = false
    @Volatile private var authorization: NativeActionPolicy.Request? = null
    private var creation: FactoryIdentityBackup.Material? = null
    private var picker: Picker? = null
    private var selectedDocument: SelectedDocument? = null
    private var operation: AtomicBoolean? = null
    private var preview: ImportPreview? = null
    private var encryptedInput: ByteArray? = null
    private var dialog: AlertDialog? = null
    private var dialogProtection: MemoryUiAutomationGuard.Lease? = null
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
    internal open fun actionPolicy(): NativeActionPolicy = NativeActionPolicy(applicationContext)
    internal open fun showIdentityDialog(dialog: AlertDialog) = dialog.show()

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
            prepareSelectedDocument(it)
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
        val appId = if (action == Action.IMPORT) null else validatedAppId() ?: return
        val operation = when (action) {
            Action.CREATE -> Operation.IDENTITY_CREATE
            Action.EXPORT -> Operation.IDENTITY_EXPORT
            Action.IMPORT -> Operation.IDENTITY_IMPORT
        }
        if (beginPolicy(operation, action.name, appId.orEmpty(), "select_document") == null) return
        if (action == Action.IMPORT) {
            launchPicker(Picker(action, null, null))
            return
        }
        startWork(work = { active ->
            requireCurrent(authorization ?: error("Missing identity authorization"), active)
            identities.state(appId!!)
        }) { state ->
            if (action == Action.CREATE && state.existing) {
                finishPolicy()
                showStatus(R.string.factory_identity_existing)
            } else if (action == Action.EXPORT && (!state.existing || state.mode != "recoverable")) {
                finishPolicy()
                showStatus(R.string.factory_identity_legacy)
            } else {
                // SAF is an actual destination selection, not the product authorization review.
                rebindPolicy(action.name, appId!!, stateBinding(state), "select_document")
                launchPicker(Picker(action, appId, state))
            }
        }
    }

    private fun launchPicker(request: Picker) {
        if (!foreground || isFinishing || isDestroyed || picker != null) return
        try { authorization?.requireCurrent() ?: error("Missing identity request") }
        catch (_: Exception) { cancelSensitiveState(); showStatus(R.string.identity_action_policy_changed); return }
        picker = request
        updateEnabled()
        try {
            if (request.action == Action.IMPORT) openDocument.launch(arrayOf("application/octet-stream", "application/json", "*/*"))
            else createDocument.launch("${request.appId}-signing-identity.jfi")
        } catch (_: Exception) {
            picker = null
            finishPolicy()
            showStatus(R.string.factory_identity_picker_unavailable)
            updateEnabled()
        }
    }

    private fun receiveDocument(uri: Uri?) {
        val request = picker ?: return // Old results after recreation/cancel are deliberately inert.
        picker = null
        if (uri == null || uri.scheme != "content") {
            selectedDocument = null
            finishPolicy()
            showStatus(R.string.factory_identity_cancelled)
            updateEnabled()
            return
        }
        selectedDocument = SelectedDocument(request, uri)
        if (foreground) {
            val selected = selectedDocument!!
            selectedDocument = null
            prepareSelectedDocument(selected)
        }
    }

    private fun prepareSelectedDocument(selected: SelectedDocument) {
        try {
            rebindPolicy(selected.picker.action.name, selected.picker.appId.orEmpty(),
                selected.picker.state?.let(::stateBinding).orEmpty(), selected.uri.toString())
            when (selected.picker.action) {
                Action.IMPORT -> readImport(selected.uri)
                Action.EXPORT -> showPassphrase(selected)
                Action.CREATE -> startWork(work = { active ->
                    requireCurrent(authorization ?: error("Missing identity authorization"), active)
                    val pair = FactoryPortableCertificate.generate()
                    FactoryIdentityBackup.Material(selected.picker.appId!!, pair.key, pair.certificate, 0, null)
                }, discard = { destroyKey(it.key) }) { material ->
                    creation = material
                    // The transient key is never persisted before backup verification. Bind its actual
                    // certificate before the single final ASK review, rather than approving an unknown key.
                    rebindPolicy(Action.CREATE.name, material.appId, stateBinding(selected.picker.state!!),
                        selected.uri.toString(), FactoryIdentityBackup.fingerprint(material.certificate), "0")
                    showPassphrase(selected)
                }
            }
        } catch (_: Exception) {
            cancelSensitiveState()
            showStatus(R.string.identity_action_policy_changed)
        }
    }

    private fun showPassphrase(selected: SelectedDocument) {
        val request = authorization ?: return
        request.requireCurrent()
        val form = column()
        val certificate = creation?.certificate?.let(FactoryIdentityBackup::fingerprint)
            ?: selected.picker.state?.fingerprint.orEmpty()
        form.addView(TextView(this).apply {
            text = getString(R.string.identity_action_backup_exact, selected.picker.appId.orEmpty(), certificate,
                selected.picker.state!!.lastVersion, selected.uri.toString())
        })
        form.addView(label(R.string.factory_identity_passphrase_help))
        val first = passwordField(R.string.factory_identity_passphrase, "factory-identity-passphrase")
        val confirm = passwordField(R.string.factory_identity_passphrase_confirm, "factory-identity-passphrase-confirm")
        form.addView(first)
        form.addView(confirm)
        val accepted = if (request.requiresApproval) CheckBox(this).apply {
            setText(R.string.factory_identity_backup_ack); disablePersistence(this); form.addView(this)
        } else null
        val feedback = label(R.string.factory_identity_empty)
        form.addView(feedback)
        showForm(getString(if (selected.picker.action == Action.CREATE) R.string.factory_identity_create else R.string.factory_identity_export),
            form, if (request.requiresApproval) R.string.factory_identity_approve_save else R.string.identity_action_save_backup) {
            if (accepted != null && !accepted.isChecked) { feedback.setText(R.string.factory_identity_ack_required); return@showForm }
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
            try { approveFromForm(request) }
            catch (failure: Exception) { wipeCharacters(passphrase); throw failure }
            dismissDialog()
            saveBackup(selected, passphrase, request)
        }
    }

    private fun saveBackup(selected: SelectedDocument, passphrase: CharArray, request: NativeActionPolicy.Request) {
        val created = creation
        creation = null // The worker owns the transient key and destroys it even after cancellation.
        startWork(work = { active ->
            var material: FactoryIdentityBackup.Material? = created
            var container: ByteArray? = null
            val current = { requireCurrent(request, active); true }
            val admit = { request.admit { check(active.get() && authorization === request) } }
            try {
                current()
                material = if (selected.picker.action == Action.CREATE) {
                    check(identities.state(selected.picker.appId!!) == selected.picker.state) { "Identity changed before backup creation" }
                    created ?: error("Missing exact creation material")
                } else identities.exportRecoverableAfterApproval(selected.picker.appId!!, selected.picker.state!!,
                    beforeEffect = admit, stillApproved = current)
                current()
                container = FactoryIdentityBackup.encrypt(material, passphrase)
                check(container.size <= FactoryIdentityBackup.MAX_CONTAINER_BYTES)
                // Opening with wt can truncate the selected document; it is itself an admitted effect.
                admit()
                contentResolver.openOutputStream(selected.uri, "wt")?.use { stream ->
                    admit()
                    stream.write(container)
                    stream.flush()
                } ?: error("Unavailable document")
                current()
                // Exact ciphertext readback is evidence of this write only, never a claim of cloud
                // durability, an independent safe copy, or a tested restoration.
                val readback = contentResolver.openInputStream(selected.uri)?.use {
                    readBounded(it, FactoryIdentityBackup.MAX_CONTAINER_BYTES, active) { current() }
                } ?: error("Backup readback unavailable")
                try { check(java.security.MessageDigest.isEqual(container, readback)) { "Backup readback differs" } }
                finally { readback.fill(0) }
                current()
                if (selected.picker.action == Action.CREATE) identities.createRecoverableAfterApproval(material,
                    beforeEffect = admit, stillApproved = current)
            } finally {
                wipeCharacters(passphrase)
                container?.fill(0)
                material?.let { destroyKey(it.key) }
            }
        }, failureMessage = R.string.factory_identity_save_failed) {
            finishPolicy()
            showStatus(if (selected.picker.action == Action.CREATE) R.string.factory_identity_created else R.string.factory_identity_exported)
        }
    }

    private fun readImport(uri: Uri) {
        val selectedRequest = authorization ?: return
        startWork(work = { active ->
            requireCurrent(selectedRequest, active)
            contentResolver.openInputStream(uri)?.use {
                readBounded(it, FactoryIdentityBackup.MAX_CONTAINER_BYTES, active) { requireCurrent(selectedRequest, active) }
            } ?: error("Unavailable document")
        }, discard = { it.fill(0) }) { bytes ->
            encryptedInput = bytes
            val form = column()
            form.addView(label(if (selectedRequest.requiresApproval) R.string.factory_identity_import_help else R.string.identity_action_import_allowed_help))
            form.addView(TextView(this).apply { text = getString(R.string.identity_action_document, uri.toString()) })
            val password = passwordField(R.string.factory_identity_passphrase, "factory-identity-passphrase")
            form.addView(password)
            showForm(getString(R.string.factory_identity_import), form,
                if (selectedRequest.requiresApproval) R.string.factory_identity_unlock else R.string.identity_action_unlock_import) {
                selectedRequest.requireCurrent()
                val passphrase = copyCharacters(password)
                val encrypted = encryptedInput ?: run { wipeCharacters(passphrase); return@showForm }
                encryptedInput = null
                dismissDialog()
                startWork(work = { active ->
                    var material: FactoryIdentityBackup.Material? = null
                    try {
                        requireCurrent(selectedRequest, active)
                        material = FactoryIdentityBackup.decrypt(encrypted, passphrase)
                        requireCurrent(selectedRequest, active)
                        ImportPreview(material, identities.recoverySnapshot(material.appId), uri)
                    } catch (failure: Exception) {
                        material?.let { destroyKey(it.key) }
                        throw failure
                    } finally { wipeCharacters(passphrase); encrypted.fill(0) }
                }, discard = { destroyKey(it.material.key) }, failureMessage = R.string.factory_identity_unlock_failed) { imported ->
                    preview = imported
                    rebindPolicy(Action.IMPORT.name, imported.material.appId, imported.expectedRecord.orEmpty(),
                        FactoryIdentityBackup.fingerprint(imported.material.certificate), imported.material.lastVersion.toString(),
                        imported.material.lastApkSha256.orEmpty(), imported.uri.toString())
                    showImportPreview(imported)
                }
            }
        }
    }

    private fun showImportPreview(imported: ImportPreview) {
        val request = authorization ?: return
        val material = imported.material
        val restore = {
            check(preview === imported)
            request.requireCurrent()
            preview = null // The operation now owns the key and clears it in finally.
            startWork(work = { active ->
                try {
                    identities.importRecoverableAfterApproval(material, imported.expectedRecord,
                        beforeEffect = { request.admit { check(active.get() && authorization === request) } },
                        stillApproved = { requireCurrent(request, active); true })
                } finally { destroyKey(material.key) }
            }) { finishPolicy(); showStatus(R.string.factory_identity_restored) }
        }
        if (!request.requiresApproval) {
            restore()
            return
        }
        showApproval(getString(R.string.factory_identity_restore_title),
            getString(R.string.factory_identity_restore_preview, material.appId,
                FactoryIdentityBackup.fingerprint(material.certificate), material.lastVersion) + "\n\n" +
                getString(R.string.identity_action_document, imported.uri.toString()),
            R.string.factory_identity_approve_restore) {
            approveFromForm(request)
            dismissDialog()
            restore()
        }
    }

    private fun prepareReconciliation() {
        if (!canStart()) return
        val appId = validatedAppId() ?: return
        if (beginPolicy(Operation.IDENTITY_RECONCILE, appId, "review_version_floor") == null) return
        startWork(work = { active ->
            requireCurrent(authorization ?: error("Missing identity authorization"), active)
            identities.state(appId)
        }) { state ->
            if (!state.existing || state.mode != "recoverable") {
                finishPolicy(); showStatus(R.string.factory_identity_legacy); return@startWork
            }
            val initial = rebindPolicy(appId, stateBinding(state), "review_version_floor")
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
            // This is a factual declaration about latest release evidence, never implicit ALLOW consent.
            val checked = CheckBox(this).apply { setText(R.string.factory_identity_reconcile_ack); disablePersistence(this) }
            form.addView(checked)
            val feedback = label(R.string.factory_identity_empty)
            form.addView(feedback)
            showForm(getString(R.string.factory_identity_reconcile), form,
                if (initial.requiresApproval) R.string.factory_identity_approve_floor else R.string.identity_action_save_floor) {
                initial.requireCurrent()
                val value = floor.text.toString().toIntOrNull()
                if (!checked.isChecked || value == null || value < state.lastVersion || value < 0) {
                    feedback.setText(R.string.factory_identity_floor_invalid)
                    return@showForm
                }
                val request = rebindPolicy(appId, stateBinding(state), value.toString(), "user_declared_floor")
                approveFromForm(request)
                dismissDialog()
                startWork(work = { active ->
                    identities.reconcileAfterApproval(appId, state, value,
                        beforeEffect = { request.admit { check(active.get() && authorization === request) } },
                        stillApproved = { requireCurrent(request, active); true })
                }) { finishPolicy(); showStatus(R.string.factory_identity_reconciled) }
            }
        }
    }

    private fun beginPolicy(operation: Operation, vararg values: String): NativeActionPolicy.Request? = try {
        policies.prepare(operation, NativeActionPolicy.binding(*values)).also { authorization = it }
    } catch (_: Exception) { showStatus(R.string.identity_action_policy_changed); null }

    private fun rebindPolicy(vararg values: String): NativeActionPolicy.Request {
        val previous = authorization ?: error("Missing identity authorization")
        return previous.rebind(NativeActionPolicy.binding(*values)).also { authorization = it }
    }

    private fun requireCurrent(request: NativeActionPolicy.Request, active: AtomicBoolean) {
        check(active.get() && authorization === request) { "Identity action was interrupted" }
        request.requireCurrent()
    }

    private fun approveFromForm(request: NativeActionPolicy.Request) {
        request.requireCurrent()
        if (request.requiresApproval) request.approveOnce {
            check(authorization === request && foreground && !isFinishing && !isDestroyed &&
                dialog?.isShowing == true && operation == null) { "Identity review is no longer active" }
            (dialogProtection ?: error("Missing protected identity review")).requireHumanUiInteraction()
        }
    }

    private fun finishPolicy() { authorization?.close(); authorization = null }

    private fun stateBinding(state: FactorySigningIdentity.State): String = NativeActionPolicy.binding(
        state.existing.toString(), state.fingerprint.orEmpty(), state.lastVersion.toString(), state.recordSha256.orEmpty(),
        state.mode, state.lastApkSha256.orEmpty(), state.continuityKnown.toString(), state.continuityResolution,
        state.lastScope?.toString().orEmpty())

    private fun <T> startWork(work: (AtomicBoolean) -> T, discard: (T) -> Unit = {},
                              failureMessage: Int = R.string.factory_identity_operation_failed, success: (T) -> Unit) {
        if (!foreground || operation != null || isFinishing || isDestroyed) return
        val request = authorization ?: return
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
                    result.fold({ value ->
                        try { requireCurrent(request, approved); success(value) }
                        catch (_: Exception) { discard(value); cancelSensitiveState(); showStatus(R.string.identity_action_policy_changed) }
                    }, { cancelSensitiveState(); showStatus(failureMessage) })
                    updateEnabled()
                }
            }
        }
    }

    private fun showApproval(title: String, message: String, approvalLabel: Int, approved: () -> Unit) {
        val form = column().apply { addView(TextView(this@FactoryIdentityActivity).apply { text = message }) }
        showForm(title, form, approvalLabel, approved)
    }

    private fun showForm(title: String, form: LinearLayout, approvalLabel: Int, approved: () -> Unit) {
        if (!foreground || isFinishing || isDestroyed) return
        // Only real passphrase/key, ASK, and factual recovery forms enter this boundary. The
        // ordinary status surface, actual SAF picker, and ALLOW background effects do not.
        val protection = MemoryUiAutomationGuard.enterProtectedSurface()
        if (!protection.isReadyForUser) {
            protection.close()
            cancelSensitiveState()
            showStatus(R.string.identity_action_reopen_manually)
            return
        }
        var created: AlertDialog? = null
        try {
            val scroll = ScrollView(this).apply { addView(form); disablePersistence(this) }
            val review = AlertDialog.Builder(this).setTitle(title).setView(scroll)
                .setPositiveButton(approvalLabel, null)
                .setNegativeButton(android.R.string.cancel) { _, _ -> cancelSensitiveState() }
                .create()
            created = review
            dialog = review
            dialogProtection = protection
            review.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            review.setOnCancelListener { cancelSensitiveState() }
            review.setOnDismissListener {
                if (dialog === review) {
                    // A framework dismissal not initiated by our accepted action is cancellation.
                    dialog = null
                    if (dialogProtection === protection) dialogProtection = null
                    cancelSensitiveState()
                }
                releaseDialogProtection(review, protection)
                updateEnabled()
            }
            showIdentityDialog(review)
            review.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            review.window?.decorView?.filterTouchesWhenObscured = true
            review.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
            review.getButton(AlertDialog.BUTTON_NEGATIVE).filterTouchesWhenObscured = true
            review.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (foreground && dialog === review && operation == null) {
                    if (!protection.isReadyForUser) {
                        cancelSensitiveState()
                        showStatus(R.string.identity_action_reopen_manually)
                        return@setOnClickListener
                    }
                    try {
                        check(dialogProtection === protection)
                        protection.requireHumanUiInteraction()
                        approved()
                    } catch (_: Exception) { cancelSensitiveState(); showStatus(R.string.identity_action_policy_changed) }
                }
            }
            updateEnabled()
        } catch (_: Exception) {
            cancelSensitiveState()
            releaseDialogProtection(created, protection)
            showStatus(R.string.identity_action_reopen_manually)
        }
    }

    private fun dismissDialog() {
        val previous = dialog
        val protection = dialogProtection
        dialog = null
        dialogProtection = null
        clearSecretFields()
        previous?.dismiss()
        releaseDialogProtection(previous, protection)
    }

    /** A dismissed dialog must leave the view tree before automation can observe/click again. */
    private fun releaseDialogProtection(review: AlertDialog?, protection: MemoryUiAutomationGuard.Lease?) {
        if (protection == null) return
        val decor = review?.window?.decorView
        if (decor == null || !decor.isAttachedToWindow) { protection.close(); return }
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                protection.close()
                view.removeOnAttachStateChangeListener(this)
            }
        })
        // Handle a detach that raced adding the observer without clearing a live surface.
        if (!decor.isAttachedToWindow) protection.close()
    }

    private fun cancelSensitiveState(interrupted: Boolean = false) {
        val hadPending = operation != null || preview != null || dialog != null || encryptedInput != null || authorization != null
        operation?.set(false)
        operation = null
        finishPolicy()
        creation?.let { destroyKey(it.key) }
        creation = null
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

        internal fun readBounded(input: InputStream, limit: Int, approved: AtomicBoolean,
                                 requireCurrent: () -> Unit = {}): ByteArray {
            val buffer = ByteArray(4096)
            val output = ByteArrayOutputStream()
            try {
                while (true) {
                    check(approved.get())
                    requireCurrent()
                    val count = input.read(buffer)
                    if (count < 0) return output.toByteArray()
                    check(count > 0 && output.size() <= limit - count) { "Invalid or oversized identity backup" }
                    output.write(buffer, 0, count)
                }
            } finally { buffer.fill(0) }
        }
    }
}
