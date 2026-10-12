package com.jarvys.agent.apkfactory

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import com.jarvys.agent.R
import com.jarvys.agent.NativeActionPolicy
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.agent.NativeActionPolicy.Operation
import com.jarvys.agent.connectors.AutonomyPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** Host-only native UI checks; never touches a user's identity or AndroidKeyStore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FactoryIdentityActivityTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var controller: ActivityController<TestIdentityActivity>
    private lateinit var activity: TestIdentityActivity

    class TestIdentityActivity : FactoryIdentityActivity() {
        internal override fun identityStore(): FactorySigningIdentity = providedStore ?: object : FactorySigningIdentity(this@TestIdentityActivity) {
            override fun state(appId: String): State { stateReads++; return testState }
        }
        internal override fun actionPolicy(): NativeActionPolicy = policy
        internal override fun showIdentityDialog(dialog: AlertDialog) {
            if (failDialogShow) throw IllegalStateException("Synthetic protected-dialog show failure")
            super.showIdentityDialog(dialog)
        }
        companion object {
            internal var failDialogShow = false
            internal var policy = NativeActionPolicy.inMemory(AutonomyPolicy.ASK)
            internal var stateReads = 0
            internal var providedStore: FactorySigningIdentity? = null
            internal var testState = FactorySigningIdentity.State(false, null, 0)
        }
    }

    @Before fun setUp() {
        TestIdentityActivity.providedStore = null
        TestIdentityActivity.failDialogShow = false
        assertFalse(MemoryUiAutomationGuard.isProtected())
        TestIdentityActivity.policy = NativeActionPolicy.inMemory(AutonomyPolicy.ASK)
        TestIdentityActivity.stateReads = 0
        TestIdentityActivity.testState = FactorySigningIdentity.State(false, null, 0)
        controller = Robolectric.buildActivity(TestIdentityActivity::class.java).setup()
        activity = controller.get()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        TestIdentityActivity.providedStore = null
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun activityIsSecureAndDoesNotAutomaticallyLaunchAFilePicker() {
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertTrue(button("factory-identity-create").isEnabled)
        val info = activity.packageManager.getActivityInfo(
            android.content.ComponentName(activity, FactoryIdentityActivity::class.java), 0)
        assertFalse(info.exported)
    }

    @Test
    @Config(sdk = [24, 26, 29])
    fun nativeManagerGuardsPlatformApisAndOnlyEnablesRecoveryFromApi26() {
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        if (Build.VERSION.SDK_INT < 26) {
            for (tag in listOf("factory-identity-create", "factory-identity-export", "factory-identity-import", "factory-identity-reconcile")) {
                assertFalse(button(tag).isEnabled)
                button(tag).performClick()
            }
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertEquals(activity.getString(R.string.factory_identity_api_required), status())
        } else {
            openCreatePassword()
            val form = latestDialog()
            val password = form.window!!.decorView.findViewWithTag<EditText>("factory-identity-passphrase")
            assertNotNull(password)
            assertTrue(form.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertFalse(password.isSaveEnabled)
            assertFalse(password.isSaveFromParentEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, password.importantForAutofill)
            assertTrue(password.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            assertTrue(password.filterTouchesWhenObscured)
            password.setText("Synthetic older Android fixture")
            controller.pause().stop()
            assertEquals("", password.text.toString())
            assertFalse(form.isShowing)
            controller.restart().start().resume().visible()
            assertTrue(button("factory-identity-create").isEnabled)
        }
    }

    @Test fun invalidApplicationIdCannotLaunchPicker() {
        field("factory-identity-app-id").setText("com.jarvys.forbidden")
        button("factory-identity-create").performClick()
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertEquals(activity.getString(R.string.factory_identity_invalid_app_id), status())
    }

    @Test fun legacyIdentityCannotExportOrConvert() {
        TestIdentityActivity.testState = FactorySigningIdentity.State(true, "a".repeat(64), 12)
        field("factory-identity-app-id").setText("com.example.legacyui")
        button("factory-identity-export").performClick()
        await { status() == activity.getString(R.string.factory_identity_legacy) }
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        button("factory-identity-create").performClick()
        await { status() == activity.getString(R.string.factory_identity_existing) }
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun createSelectsExactDocumentBeforeSingleFinalReviewAndPickerSurvivesStopSave() {
        startCreate()
        assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
        val request = shadowOf(activity).nextStartedActivityForResult!!
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        assertEquals("application/octet-stream", request.intent.type)
        assertEquals("com.example.factoryui-signing-identity.jfi", request.intent.getStringExtra(Intent.EXTRA_TITLE))
        assertFalse(button("factory-identity-create").isEnabled)
        controller.pause().stop().saveInstanceState(Bundle())
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://factory-test/create")))
        controller.restart().start().resume().visible()
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        val password = latestDialog().window!!.decorView.findViewWithTag<EditText>("factory-identity-passphrase")
        assertNotNull(password)
        assertEquals("", password.text.toString())
        assertTrue(latestDialog().window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun passwordControlsDisableSavingAutofillContentCaptureAndKeyboardLearning() {
        openCreatePassword()
        val dialog = latestDialog()
        val password = dialog.window!!.decorView.findViewWithTag<EditText>("factory-identity-passphrase")
        assertFalse(password.isSaveEnabled)
        assertFalse(password.isSaveFromParentEnabled)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, password.importantForAutofill)
        assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, password.importantForContentCapture)
        assertTrue(password.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0)
        assertTrue(password.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertTrue(password.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertTrue(password.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
        assertFalse(password.isLongClickable)
        assertTrue(password.filterTouchesWhenObscured)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured)
    }

    @Test fun mismatchedPassphrasesAreClearedAndDoNotStartWork() {
        openCreatePassword()
        val dialog = latestDialog()
        val root = dialog.window!!.decorView
        val first = root.findViewWithTag<EditText>("factory-identity-passphrase")
        val confirm = root.findViewWithTag<EditText>("factory-identity-passphrase-confirm")
        first.setText("Synthetic test passphrase one")
        confirm.setText("Synthetic test passphrase two")
        allChildren(root).filterIsInstance<CheckBox>().single().isChecked = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertEquals("", first.text.toString())
        assertEquals("", confirm.text.toString())
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertTrue(allChildren(root).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.factory_identity_invalid_passphrase)
        })
    }

    @Test fun cancelAndBackgroundErasePasswordViewsAndDoNotResumeAnApproval() {
        openCreatePassword()
        val dialog = latestDialog()
        val password = dialog.window!!.decorView.findViewWithTag<EditText>("factory-identity-passphrase")
        password.setText("Synthetic lifecycle passphrase")
        controller.pause().stop()
        assertEquals("", password.text.toString())
        assertFalse(dialog.isShowing)
        controller.restart().start().resume().visible()
        assertTrue(button("factory-identity-create").isEnabled)
        assertEquals(activity.getString(R.string.factory_identity_interrupted), status())
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun pickerCancellationAndRepeatedTapsAreInert() {
        button("factory-identity-import").performClick()
        button("factory-identity-import").performClick()
        val request = shadowOf(activity).nextStartedActivityForResult!!
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        assertTrue(button("factory-identity-import").isEnabled)
        assertEquals(activity.getString(R.string.factory_identity_cancelled), status())
    }

    @Test fun selectedImportShowsPasswordOnlyAndBadFileNeverReachesRestoreApproval() {
        val uri = Uri.parse("content://factory-test/bad-backup")
        shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        button("factory-identity-import").performClick()
        val request = shadowOf(activity).nextStartedActivityForResult!!
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        val unlock = latestDialog()
        unlock.window!!.decorView.findViewWithTag<EditText>("factory-identity-passphrase").setText("Synthetic invalid backup passphrase")
        unlock.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { status() == activity.getString(R.string.factory_identity_unlock_failed) }
        assertFalse(unlock.isShowing)
        assertTrue(button("factory-identity-import").isEnabled)
    }

    @Test fun versionFloorRequiresAcknowledgmentAndRejectsDecrease() {
        TestIdentityActivity.testState = FactorySigningIdentity.State(true, "b".repeat(64), 25,
            mode = "recoverable", continuityKnown = false, continuityResolution = "restored_unknown")
        field("factory-identity-app-id").setText("com.example.restoreui")
        button("factory-identity-reconcile").performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        val review = latestDialog()
        val root = review.window!!.decorView
        val floor = root.findViewWithTag<EditText>("factory-identity-version-floor")
        assertEquals("25", floor.text.toString())
        review.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(review.isShowing)
        allChildren(root).filterIsInstance<CheckBox>().single().isChecked = true
        floor.setText("24")
        review.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(review.isShowing)
        assertTrue(allChildren(root).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.factory_identity_floor_invalid)
        })
    }

    @Test fun approvedCreateWritesDecryptableBackupBeforeLocalIdentityThenCanExportSameKey() {
        val store = installRamStore()
        val output = ByteArrayOutputStream()
        shadowOf(activity.contentResolver).registerOutputStream(Uri.parse("content://factory-test/create"), output)
        shadowOf(activity.contentResolver).registerInputStreamSupplier(Uri.parse("content://factory-test/create")) {
            ByteArrayInputStream(output.toByteArray())
        }
        openCreatePassword()
        fillBackupPasswordsAndApprove()
        await { status() == activity.getString(R.string.factory_identity_created) }
        val state = store.state("com.example.factoryui")
        assertTrue(state.existing)
        assertEquals("recoverable", state.mode)
        assertTrue(state.continuityKnown)
        assertEquals(0, state.lastVersion)
        val passphrase = "Synthetic native UI fixture passphrase".toCharArray()
        try {
            val recovered = FactoryIdentityBackup.decrypt(output.toByteArray(), passphrase)
            assertEquals("com.example.factoryui", recovered.appId)
            assertEquals(state.fingerprint, FactoryIdentityBackup.fingerprint(recovered.certificate))
            val raw = recovered.key.encoded
            try {
                for (file in files.root.walkTopDown().filter { it.isFile }) {
                    val text = file.readBytes().toString(Charsets.ISO_8859_1)
                    assertFalse(text.contains(Base64.getEncoder().encodeToString(raw)))
                    assertFalse(text.contains("BEGIN PRIVATE KEY"))
                }
            } finally { raw.fill(0) }
            button("factory-identity-export").performClick()
            await { shadowOf(activity).peekNextStartedActivityForResult() != null }
            assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
            val request = shadowOf(activity).nextStartedActivityForResult!!
            val exportUri = Uri.parse("content://factory-test/export")
            val exported = ByteArrayOutputStream()
            shadowOf(activity.contentResolver).registerOutputStream(exportUri, exported)
            shadowOf(activity.contentResolver).registerInputStreamSupplier(exportUri) { ByteArrayInputStream(exported.toByteArray()) }
            activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(exportUri))
            fillBackupPasswordsAndApprove()
            await { status() == activity.getString(R.string.factory_identity_exported) }
            val second = FactoryIdentityBackup.decrypt(exported.toByteArray(), passphrase)
            assertEquals(state.fingerprint, FactoryIdentityBackup.fingerprint(second.certificate))
            assertEquals(state, store.state("com.example.factoryui"))
        } finally { passphrase.fill('\u0000') }
    }

    @Test fun validImportNeedsPreviewApprovalThenExplicitVersionReconciliation() {
        val store = installRamStore()
        val pair = FactoryPortableCertificate.generate()
        val material = FactoryIdentityBackup.Material("com.example.restoreui", pair.key, pair.certificate, 9, "a".repeat(64))
        val passphrase = "Synthetic native UI fixture passphrase".toCharArray()
        val bytes = try { FactoryIdentityBackup.encrypt(material, passphrase) } finally { passphrase.fill('\u0000') }
        selectValidImport(bytes)
        val denied = latestDialog()
        val displayed = allChildren(denied.window!!.decorView).filterIsInstance<TextView>().joinToString { it.text.toString() }
        assertTrue(displayed.contains(material.appId))
        assertTrue(displayed.contains(FactoryIdentityBackup.fingerprint(material.certificate)))
        assertTrue(displayed.contains("9"))
        assertNull(store.recoverySnapshot(material.appId))
        denied.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        // Native AlertDialog dispatches negative-button cancellation through its Handler.
        // Wait for dismissal/cleanup before a later user click; an earlier tap is correctly inert.
        await { !denied.isShowing && button("factory-identity-import").isEnabled }
        assertNull(store.recoverySnapshot(material.appId))
        selectValidImport(bytes)
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { status() == activity.getString(R.string.factory_identity_restored) }
        val restored = store.state(material.appId)
        assertEquals(9, restored.lastVersion)
        assertFalse(restored.continuityKnown)
        assertEquals("restored_unknown", restored.continuityResolution)
        field("factory-identity-app-id").setText(material.appId)
        button("factory-identity-reconcile").performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        val review = latestDialog()
        val root = review.window!!.decorView
        root.findViewWithTag<EditText>("factory-identity-version-floor").setText("12")
        allChildren(root).filterIsInstance<CheckBox>().single().isChecked = true
        review.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { status() == activity.getString(R.string.factory_identity_reconciled) }
        val reconciled = store.state(material.appId)
        assertEquals(12, reconciled.lastVersion)
        assertTrue(reconciled.continuityKnown)
        assertEquals("user_declared_floor", reconciled.continuityResolution)
        assertNull(reconciled.lastApkSha256)
        bytes.fill(0)
    }

    @Test fun failedBackupWriteCannotCreatePersistentIdentity() {
        val store = installRamStore()
        val failed = object : OutputStream() {
            override fun write(value: Int) { throw IOException("Synthetic document failure") }
        }
        shadowOf(activity.contentResolver).registerOutputStream(Uri.parse("content://factory-test/create"), failed)
        openCreatePassword()
        fillBackupPasswordsAndApprove()
        await { status() == activity.getString(R.string.factory_identity_save_failed) }
        assertNull(store.recoverySnapshot("com.example.factoryui"))
        assertFalse(store.state("com.example.factoryui").existing)
        assertTrue(files.root.walkTopDown().none { it.isFile })
    }

    @Test fun silentProviderTruncationOrAppendFailsReadbackAndDoesNotCreateIdentity() {
        val store = installRamStore()
        val uri = Uri.parse("content://factory-test/create")
        for (append in listOf(false, true)) {
            val output = ByteArrayOutputStream()
            shadowOf(activity.contentResolver).registerOutputStream(uri, output)
            shadowOf(activity.contentResolver).registerInputStreamSupplier(uri) {
                val written = output.toByteArray()
                ByteArrayInputStream(if (append) written + byteArrayOf(1) else written.copyOf(written.size - 1))
            }
            openCreatePassword()
            fillBackupPasswordsAndApprove()
            await { status() == activity.getString(R.string.factory_identity_save_failed) }
            assertTrue(output.size() > 0)
            assertNull(store.recoverySnapshot("com.example.factoryui"))
            assertFalse(store.state("com.example.factoryui").existing)
        }
    }

    @Test fun boundedReaderRejectsOversizeCancellationAndNonProgressingStream() {
        assertArrayEquals(byteArrayOf(1, 2), FactoryIdentityActivity.readBounded(ByteArrayInputStream(byteArrayOf(1, 2)), 2, AtomicBoolean(true)))
        assertThrows(IllegalStateException::class.java) { FactoryIdentityActivity.readBounded(ByteArrayInputStream(ByteArray(3)), 2, AtomicBoolean(true)) }
        assertThrows(IllegalStateException::class.java) { FactoryIdentityActivity.readBounded(ByteArrayInputStream(byteArrayOf(1)), 2, AtomicBoolean(false)) }
        val emptyRead = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray): Int = 0
        }
        assertThrows(IllegalStateException::class.java) { FactoryIdentityActivity.readBounded(emptyRead, 2, AtomicBoolean(true)) }
    }

    @Test fun allFourDeniedActionsStopBeforeStateReadOrDocumentSelection() {
        for (operation in listOf(Operation.IDENTITY_CREATE, Operation.IDENTITY_EXPORT, Operation.IDENTITY_IMPORT, Operation.IDENTITY_RECONCILE)) {
            TestIdentityActivity.policy.set(operation, AutonomyPolicy.DENY)
        }
        field("factory-identity-app-id").setText("com.example.factoryui")
        for (tag in listOf("factory-identity-create", "factory-identity-export", "factory-identity-import", "factory-identity-reconcile")) {
            button(tag).performClick()
            assertEquals(activity.getString(R.string.identity_action_policy_changed), status())
        }
        assertEquals(0, TestIdentityActivity.stateReads)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
    }

    @Test fun defaultAllowCreatesAndExportsWithoutProductApprovalCheckbox() {
        TestIdentityActivity.policy = NativeActionPolicy.inMemory()
        val store = installRamStore()
        val uri = Uri.parse("content://factory-test/create")
        val output = ByteArrayOutputStream()
        shadowOf(activity.contentResolver).registerOutputStream(uri, output)
        shadowOf(activity.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(output.toByteArray()) }
        openCreatePassword()
        val form = latestDialog()
        assertTrue(allChildren(form.window!!.decorView).filterIsInstance<CheckBox>().isEmpty())
        assertEquals(activity.getString(R.string.identity_action_save_backup), form.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        val text = allChildren(form.window!!.decorView).filterIsInstance<TextView>().joinToString { it.text.toString() }
        assertTrue(text.contains("com.example.factoryui"))
        assertTrue(text.contains(uri.toString()))
        fillBackupPasswordsAndApprove()
        await { status() == activity.getString(R.string.factory_identity_created) }
        val state = store.state("com.example.factoryui")
        assertTrue(state.existing)
        button("factory-identity-export").performClick()
        await { shadowOf(activity).peekNextStartedActivityForResult() != null }
        val request = shadowOf(activity).nextStartedActivityForResult!!
        // Registered in-memory streams do not implement a provider's "wt" truncation. Give
        // the export its own selected document so the fixture cannot append to the create backup.
        val exportUri = Uri.parse("content://factory-test/allow-export")
        val exported = ByteArrayOutputStream()
        shadowOf(activity.contentResolver).registerOutputStream(exportUri, exported)
        shadowOf(activity.contentResolver).registerInputStreamSupplier(exportUri) { ByteArrayInputStream(exported.toByteArray()) }
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(exportUri))
        val export = latestDialog()
        assertTrue(allChildren(export.window!!.decorView).filterIsInstance<CheckBox>().isEmpty())
        assertTrue(allChildren(export.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains(state.fingerprint!!) })
        fillBackupPasswordsAndApprove()
        await { status() == activity.getString(R.string.factory_identity_exported) }
        assertEquals(state, store.state("com.example.factoryui"))
    }

    @Test fun askBackupHasExactlyOneExactReviewAfterDocumentSelection() {
        openCreatePassword()
        val form = latestDialog()
        assertEquals(1, allChildren(form.window!!.decorView).filterIsInstance<CheckBox>().size)
        val text = allChildren(form.window!!.decorView).filterIsInstance<TextView>().joinToString { it.text.toString() }
        assertTrue(text.contains("com.example.factoryui"))
        assertTrue(text.contains("content://factory-test/create"))
        assertTrue(Regex("[a-f0-9]{64}").containsMatchIn(text))
        assertEquals(activity.getString(R.string.factory_identity_approve_save), form.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
    }

    @Test fun policyChangeDuringPickerCannotAdoptNewAskOrDenyAllowRevision() {
        TestIdentityActivity.policy = NativeActionPolicy.inMemory()
        for (mode in listOf(AutonomyPolicy.ASK, AutonomyPolicy.DENY)) {
            TestIdentityActivity.policy.set(Operation.IDENTITY_CREATE, AutonomyPolicy.ALLOW)
            startCreate()
            val request = shadowOf(activity).nextStartedActivityForResult!!
            TestIdentityActivity.policy.set(Operation.IDENTITY_CREATE, mode)
            if (mode == AutonomyPolicy.DENY) TestIdentityActivity.policy.set(Operation.IDENTITY_CREATE, AutonomyPolicy.ALLOW)
            activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://factory-test/stale")))
            assertEquals(activity.getString(R.string.identity_action_policy_changed), status())
            assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
            assertTrue(button("factory-identity-create").isEnabled)
        }
    }

    @Test fun deniedAfterBackupWriteStopsLocalCreationWithoutReplayingOutput() {
        TestIdentityActivity.policy = NativeActionPolicy.inMemory()
        val store = installRamStore()
        var writes = 0
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                super.write(bytes, offset, count)
                writes++
                TestIdentityActivity.policy.set(Operation.IDENTITY_CREATE, AutonomyPolicy.DENY)
            }
        }
        shadowOf(activity.contentResolver).registerOutputStream(Uri.parse("content://factory-test/create"), output)
        openCreatePassword()
        fillBackupPasswordsAndApprove()
        await { status() == activity.getString(R.string.factory_identity_save_failed) }
        assertEquals(1, writes)
        assertTrue(output.size() > 0)
        assertNull(store.recoverySnapshot("com.example.factoryui"))
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }

    @Test fun allowImportUnlocksAndImportsButNeverInventsVersionReconciliation() {
        TestIdentityActivity.policy = NativeActionPolicy.inMemory()
        val store = installRamStore()
        val pair = FactoryPortableCertificate.generate()
        val material = FactoryIdentityBackup.Material("com.example.restoreui", pair.key, pair.certificate, 9, "a".repeat(64))
        val passphrase = "Synthetic native UI fixture passphrase".toCharArray()
        val bytes = try { FactoryIdentityBackup.encrypt(material, passphrase) } finally { passphrase.fill('\u0000') }
        val uri = Uri.parse("content://factory-test/allow-backup")
        shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        button("factory-identity-import").performClick()
        val request = shadowOf(activity).nextStartedActivityForResult!!
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        val unlock = latestDialog()
        assertEquals(activity.getString(R.string.identity_action_unlock_import), unlock.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        unlock.window!!.decorView.findViewWithTag<EditText>("factory-identity-passphrase").setText("Synthetic native UI fixture passphrase")
        unlock.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { status() == activity.getString(R.string.factory_identity_restored) }
        assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
        val restored = store.state(material.appId)
        assertEquals(9, restored.lastVersion)
        assertFalse(restored.continuityKnown)
        field("factory-identity-app-id").setText(material.appId)
        button("factory-identity-reconcile").performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        val declaration = latestDialog()
        assertEquals(activity.getString(R.string.identity_action_save_floor), declaration.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        declaration.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(declaration.isShowing)
        assertFalse(store.state(material.appId).continuityKnown)
        allChildren(declaration.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked = true
        declaration.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { status() == activity.getString(R.string.factory_identity_reconciled) }
        assertEquals("user_declared_floor", store.state(material.appId).continuityResolution)
        bytes.fill(0)
    }

    @Test fun policyRevokedDuringExactImportReviewCannotRestore() {
        val store = installRamStore()
        val pair = FactoryPortableCertificate.generate()
        val material = FactoryIdentityBackup.Material("com.example.restoreui", pair.key, pair.certificate, 0, null)
        val password = "Synthetic native UI fixture passphrase".toCharArray()
        val bytes = try { FactoryIdentityBackup.encrypt(material, password) } finally { password.fill('\u0000') }
        selectValidImport(bytes)
        TestIdentityActivity.policy.set(Operation.IDENTITY_IMPORT, AutonomyPolicy.DENY)
        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(activity.getString(R.string.identity_action_policy_changed), status())
        assertNull(store.recoverySnapshot(material.appId))
        assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
        bytes.fill(0)
    }

    @Test fun allowStatusAndPickerStayUnguardedButPassphraseBlocksQueuedAutomation() {
        TestIdentityActivity.policy = NativeActionPolicy.inMemory()
        assertFalse(MemoryUiAutomationGuard.isProtected())
        val beforeForm = MemoryUiAutomationGuard.captureAutomationEpoch()
        startCreate()
        assertFalse(MemoryUiAutomationGuard.isProtected())
        val request = shadowOf(activity).nextStartedActivityForResult!!
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://factory-test/guarded")))
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        val form = latestDialog()
        assertTrue(MemoryUiAutomationGuard.isProtected())
        var automatedClick = false
        assertFalse(MemoryUiAutomationGuard.runAutomated(beforeForm) {
            automatedClick = true
            form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        })
        assertFalse(automatedClick)
        form.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        await { !form.isShowing && !MemoryUiAutomationGuard.isProtected() }
        assertTrue(button("factory-identity-create").isEnabled)
    }

    @Test fun activeAutomationCannotEnterAskPassphraseOrRecordApproval() {
        val store = installRamStore()
        val action = MemoryUiAutomationGuard.beginAsyncAction(MemoryUiAutomationGuard.captureAutomationEpoch())
        try {
            startCreate()
            val request = shadowOf(activity).nextStartedActivityForResult!!
            activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://factory-test/tainted")))
            await { status() == activity.getString(R.string.identity_action_reopen_manually) }
            assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
            assertNull(store.recoverySnapshot("com.example.factoryui"))
            assertFalse(MemoryUiAutomationGuard.isProtected())
        } finally { action.close() }
        assertTrue(button("factory-identity-create").isEnabled)
        openCreatePassword()
        assertTrue(MemoryUiAutomationGuard.isProtected())
    }

    @Test fun activeAutomationCannotDeclareUnknownReleaseHistoryEvenWhenAllowed() {
        TestIdentityActivity.policy = NativeActionPolicy.inMemory()
        val store = installRamStore()
        val pair = FactoryPortableCertificate.generate()
        val material = FactoryIdentityBackup.Material("com.example.restoreui", pair.key, pair.certificate, 0, null)
        store.importRecoverableAfterApproval(material, null)
        val before = store.state(material.appId)
        assertFalse(before.continuityKnown)
        field("factory-identity-app-id").setText(material.appId)
        val action = MemoryUiAutomationGuard.beginAsyncAction(MemoryUiAutomationGuard.captureAutomationEpoch())
        try {
            button("factory-identity-reconcile").performClick()
            await { status() == activity.getString(R.string.identity_action_reopen_manually) }
            assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
            assertEquals(before, store.state(material.appId))
            assertFalse(MemoryUiAutomationGuard.isProtected())
        } finally { action.close() }
    }

    @Test fun protectedDialogShowFailureAndPauseAlwaysReleaseLease() {
        TestIdentityActivity.failDialogShow = true
        startCreate()
        val request = shadowOf(activity).nextStartedActivityForResult!!
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://factory-test/show-failure")))
        await { status() == activity.getString(R.string.identity_action_reopen_manually) }
        assertFalse(MemoryUiAutomationGuard.isProtected())
        assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true)
        TestIdentityActivity.failDialogShow = false
        openCreatePassword()
        assertTrue(MemoryUiAutomationGuard.isProtected())
        val form = latestDialog()
        form.window!!.decorView.findViewWithTag<EditText>("factory-identity-passphrase").setText("Synthetic protected secret")
        controller.pause().stop()
        assertFalse(form.isShowing)
        assertFalse(MemoryUiAutomationGuard.isProtected())
    }

    private fun fillBackupPasswordsAndApprove() {
        val form = latestDialog()
        val root = form.window!!.decorView
        root.findViewWithTag<EditText>("factory-identity-passphrase").setText("Synthetic native UI fixture passphrase")
        root.findViewWithTag<EditText>("factory-identity-passphrase-confirm").setText("Synthetic native UI fixture passphrase")
        allChildren(root).filterIsInstance<CheckBox>().forEach { it.isChecked = true }
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }

    private fun selectValidImport(bytes: ByteArray) {
        val uri = Uri.parse("content://factory-test/valid-backup")
        shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        button("factory-identity-import").performClick()
        val request = shadowOf(activity).nextStartedActivityForResult!!
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        val unlock = latestDialog()
        unlock.window!!.decorView.findViewWithTag<EditText>("factory-identity-passphrase").setText("Synthetic native UI fixture passphrase")
        unlock.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await { ShadowAlertDialog.getLatestAlertDialog()?.let { it !== unlock && it.isShowing } == true }
    }

    private fun installRamStore(): FactorySigningIdentity {
        val directory = files.newFolder()
        val context = object : ContextWrapper(activity.applicationContext) {
            override fun getNoBackupFilesDir(): File = directory
        }
        return object : FactorySigningIdentity(context, RamProtection()) {
            override fun legacyPresent(appId: String) = false
            override fun legacyIdentity(appId: String): Identity = error("No legacy test key")
            override fun createLegacy(appId: String) = error("Native recovery UI must not create a legacy key")
        }.also { TestIdentityActivity.providedStore = it }
    }

    private class RamProtection : FactorySigningIdentity.PortableProtection {
        private val keys = mutableMapOf<String, SecretKey>()
        override fun present(appId: String): Boolean = keys.containsKey(appId)
        override fun wrap(appId: String, fingerprint: String, bytes: ByteArray): JSONObject {
            val key = keys.getOrPut(appId) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key); updateAAD((appId + fingerprint).toByteArray())
            }
            return JSONObject().put("nonce", Base64.getEncoder().encodeToString(cipher.iv))
                .put("ciphertext", Base64.getEncoder().encodeToString(cipher.doFinal(bytes)))
        }
        override fun unwrap(appId: String, fingerprint: String, value: JSONObject): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, keys.getValue(appId), GCMParameterSpec(128, Base64.getDecoder().decode(value.getString("nonce"))))
                updateAAD((appId + fingerprint).toByteArray())
            }
            return cipher.doFinal(Base64.getDecoder().decode(value.getString("ciphertext")))
        }
    }

    private fun startCreate() {
        field("factory-identity-app-id").setText("com.example.factoryui")
        button("factory-identity-create").performClick()
        await { shadowOf(activity).peekNextStartedActivityForResult() != null }
    }
    private fun openCreatePassword() {
        startCreate()
        val request = shadowOf(activity).nextStartedActivityForResult!!
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://factory-test/create")))
        await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
    }
    private fun latestDialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog().also { assertTrue(it.isShowing) }
    private fun field(tag: String): EditText = activity.window.decorView.findViewWithTag(tag)
    private fun button(tag: String): Button = activity.window.decorView.findViewWithTag(tag)
    private fun status(): String = activity.window.decorView.findViewWithTag<TextView>("factory-identity-status").text.toString()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("Native identity UI did not reach the expected non-secret state")
    }
    private fun allChildren(view: View): List<View> = listOf(view) + if (view is android.view.ViewGroup)
        (0 until view.childCount).flatMap { allChildren(view.getChildAt(it)) } else emptyList()
}
