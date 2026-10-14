package com.jarvys.agent.apkfactory

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
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
import com.jarvys.factory.runtime.FileShareTransfer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Authenticated broker. Photo bytes never enter saved state, journals, logs or JavaScript IPC. */
open class FactoryPhotoActivity : ComponentActivity() {
    private val coordinator by lazy { FactoryPhotoCoordinator.get(applicationContext) }
    private var token: String? = null
    private var request: FactoryPhotoCoordinator.Request? = null
    private var selected: FactoryPhotoCapture.Image? = null
    private var capture: FactoryPhotoCapture.Capture? = null
    private var admissionCapture: FactoryPhotoCapture.Capture? = null
    private var input: Uri? = null
    private var resultReady = false
    private var resultOk = false
    private var selectionDeadline = 0L
    private var admission: FileShareTransfer.Admission? = null
    private val signal = CancellationSignal()
    private val expiryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val expireSelection = Runnable {
        if (!closed) {
            generation++
            cancelInput(); cleanup(); rejected = true
            if (!externalOwned) { token?.let { runCatching { coordinator.revoke(it) } }; token = null }
            render()
        }
    }
    @Volatile private var resumed = false
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    private var working = false
    private var launching = false
    private var externalOwned = false
    private var terminalPending = false
    private var rejected = false
    private lateinit var status: TextView
    private lateinit var close: Button
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageRuntime.attachBaseContext(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(Activity.RESULT_CANCELED)
        val caller = callingPackage
        val value = if (savedInstanceState == null && caller != null) runCatching { FactoryPhotoCoordinator.Request.parse(intent) }.getOrNull() else null
        if (value == null) { rejected = true; finish(); return }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 32) }
        column.addView(TextView(this).apply { setText(R.string.factory_photos_title); textSize = 22f })
        status = TextView(this).apply { textSize = 16f }; column.addView(status)
        fun button(label: Int, tagValue: String, action: () -> Unit) = Button(this).also {
            it.setText(label); it.tag = tagValue; it.filterTouchesWhenObscured = true
            it.setOnClickListener { if (activeReady()) runCatching(action).onFailure { rejected = true; render() } }; column.addView(it)
        }
        close = button(R.string.factory_documents_close, "factory-photos-close") { closeNative() }
        val scroll = ScrollView(this).apply { isFillViewport = true; isSaveEnabled = false; addView(column) }; setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }; ViewCompat.requestApplyInsets(scroll)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (activeReady()) runCatching { closeNative() }.onFailure { rejected = true; render() } else finish() }
        })
        if (value != null && caller != null) {
            val approved = generation; working = true
            execute {
                val outcome = runCatching {
                    check(generation == approved && !isDestroyed && !isFinishing)
                    coordinator.begin(caller, value)
                }
                runOnUiThread {
                    working = false
                    outcome.onSuccess { owner ->
                        if (generation == approved && !isDestroyed && !isFinishing) {
                            token = owner; request = value
                            advance()
                        } else runCatching { coordinator.revoke(owner) }
                    }.onFailure { rejected = true; finish() }; render()
                }
            }
        }
        render()
    }
    private fun execute(action: () -> Unit) {
        try { WORKER.execute(action) } catch (_: java.util.concurrent.RejectedExecutionException) { working = false; rejected = true; render() }
    }
    private fun activeReady() = resumed && !closed && !isFinishing && !isDestroyed
    private fun requireActive(approved: Long = generation) { check(activeReady() && generation == approved) }
    private fun advance() {
        val owner = token ?: return
        if (!activeReady() || working || rejected) return
        runCatching {
            coordinator.requireCurrent(owner)
            when (coordinator.status()) {
                "review" -> launchExternal()
                "selected" -> if (selected != null) deliver()
                "cancelled" -> closeNative()
            }
        }.onFailure { rejected = true; cleanup(); render() }
    }
    private fun launchExternal() {
        requireActive(); val owner = token ?: error("No photo request"); val value = request!!; val approved = generation
        working = true; render()
        execute {
            val outcome = runCatching {
                coordinator.requireCurrent(owner) { requireActive(approved) }
                coordinator.launch(owner) { requireActive(approved) }
                val pending = FileShareTransfer.reserve(); FactoryInteractionAdmission.restore(pending); admission = pending
                selectionDeadline = SystemClock.elapsedRealtime() + FactoryPhotoCapture.LIFETIME
                expiryHandler.postDelayed(expireSelection, FactoryPhotoCapture.LIFETIME)
                if (value.operation == "pick") photoPicker(this)
                else {
                    val intent = FactoryDocumentActivity.trustedPicker(this, Intent(MediaStore.ACTION_IMAGE_CAPTURE))
                    val component = intent.component ?: error("No trusted camera")
                    val info = packageManager.getApplicationInfo(component.packageName, 0)
                    val original = packageManager.getPackageInfo(component.packageName, PackageManager.GET_SIGNATURES)
                    check(!original.signatures.isNullOrEmpty()) { "Camera certificate unavailable" }
                    requireActive(approved); coordinator.requireCurrent(owner) { requireActive(approved) }
                    val photo = FactoryPhotoCapture.reserve(this, component.packageName, info.uid) {
                        runCatching {
                            val current = packageManager.getPackageInfo(component.packageName, PackageManager.GET_SIGNATURES)
                            packageManager.getPackagesForUid(info.uid)?.toSet() == setOf(component.packageName) &&
                                packageManager.getApplicationInfo(component.packageName, 0).uid == info.uid &&
                                current.lastUpdateTime == original.lastUpdateTime && current.longVersionCompat() == original.longVersionCompat() &&
                                current.signatures?.toList() == original.signatures?.toList() &&
                                FactoryDocumentActivity.trustedPicker(this, Intent(MediaStore.ACTION_IMAGE_CAPTURE)).component == component
                        }.getOrDefault(false)
                    }
                    capture = photo; admissionCapture = photo
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, photo.uri).apply { clipData = ClipData.newRawUri("camera output", photo.uri) }
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            }
            runOnUiThread {
                working = false
                outcome.onSuccess { external ->
                    if (activeReady() && generation == approved && token == owner && !rejected && !signal.isCanceled && SystemClock.elapsedRealtime() < selectionDeadline) {
                        launching = true; externalOwned = true
                        try { coordinator.requireCurrent(owner) { requireActive(approved) }; startActivityForResult(external, EXTERNAL) }
                        catch (_: Exception) { externalOwned = false; launching = false; failSelection(owner) }
                    } else { cleanup(); runCatching { coordinator.revoke(owner) }; token = null; rejected = true }
                }.onFailure { failSelection(owner) }; render()
            }
        }
    }
    private fun failSelection(owner: String) {
        cleanup()
        val terminal = runCatching { coordinator.pickerTerminal(owner, false) }.isSuccess
        if (terminal && activeReady() && !working) {
            runCatching { coordinator.close(owner, false) { requireActive() } }.onSuccess {
                closed = true; token = null; finish()
            }.onFailure { rejected = true }
        } else rejected = true
    }
    @Deprecated("For-result ownership is required for the external photo lifecycle")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXTERNAL || !externalOwned || token == null) return
        externalOwned = false; launching = false; terminalPending = true
        resultOk = resultCode == Activity.RESULT_OK
        if (request?.operation == "pick") {
            val uri = data?.data
            resultOk = resultOk && uri != null && validPhotoResult(this, data) &&
                checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
            if (resultOk) input = uri
        }
        resultReady = true
        if (resumed) processResult()
    }
    private fun processResult() {
        if (!resultReady || !resumed || working) return
        resultReady = false
        val owner = token ?: return
        if (!resultOk) { failSelection(owner); render(); return }
        val approved = generation; working = true; render()
        execute {
            val outcome = runCatching {
                fun allowed() { requireActive(approved); coordinator.requireCurrent(owner) { requireActive(approved) }; check(!signal.isCanceled && SystemClock.elapsedRealtime() < selectionDeadline) }
                allowed()
                if (request?.operation == "capture") {
                    val photo = capture ?: error("No camera output")
                    check(photo.opened) { "Camera did not open its streaming output" }
                    while (!photo.completed) { allowed(); check(SystemClock.elapsedRealtime() < photo.deadline); Thread.sleep(50) }
                    allowed(); FactoryPhotoCapture.take(photo)
                } else {
                    val uri = input ?: error("No photo selected")
                    val fd = contentResolver.openFileDescriptor(uri, "r", signal) ?: error("No photo stream")
                    fd.use { FactoryPhotoCapture.readDescriptor(it, selectionDeadline, ::allowed) }
                }
            }
            releaseInput(); releaseCamera()
            runOnUiThread {
                working = false
                outcome.onSuccess { image ->
                    if (activeReady() && generation == approved && token == owner && !rejected && !signal.isCanceled && SystemClock.elapsedRealtime() < selectionDeadline) {
                        selected = image
                        runCatching { coordinator.pickerTerminal(owner, true) }.onFailure { cleanup(); rejected = true }
                    } else { image.bytes.fill(0); cleanup(); runCatching { coordinator.revoke(owner) }; rejected = true }
                }.onFailure { failSelection(owner) }; advance(); render()
            }
        }
    }
    private fun deliver() {
        requireActive(); val owner = token ?: error("No photo request"); val image = selected ?: error("No image")
        val value = request!!; val approved = generation; working = true; render()
        execute {
            var transfer: FileShareTransfer? = null
            val pending = admission
            var transferred = false
            val outcome = runCatching {
                val verifier = coordinator.transferVerifier(owner) { requireActive(approved) }
                coordinator.requireCurrent(owner) { requireActive(approved) }
                transfer = pending!!.complete(image.bytes, value.nonce, verifier)
                val endpoint = transfer!!
                transferred = true
                selected = null; admission = null; admissionCapture = null
                coordinator.close(owner, true) { requireActive(approved) }
                Intent().putExtras(Bundle().apply {
                    putString("nonce", value.nonce); putBinder("transfer", endpoint); putInt("size", endpoint.size)
                    putString("sha256", endpoint.sha256); putString("mimeType", image.mimeType)
                    putInt("width", image.width); putInt("height", image.height)
                })
            }
            if (transferred && pending != null) FactoryInteractionAdmission.release(pending)
            runOnUiThread {
                working = false
                outcome.onSuccess { result ->
                    if (activeReady() && generation == approved && !rejected && !signal.isCanceled && SystemClock.elapsedRealtime() < selectionDeadline) {
                        setResult(Activity.RESULT_OK, result); closed = true; token = null; finish()
                    } else { transfer?.close(); rejected = true; finish() }
                }.onFailure { transfer?.close(); cleanup(); rejected = true; render() }
            }
        }
    }
    private fun closeNative() {
        requireActive(); val owner = token
        if (working) {
            generation++; cancelInput(); cleanup(); owner?.let { runCatching { coordinator.revoke(it) } }
            token = null; rejected = true; render(); return
        }
        if (externalOwned && owner != null) {
            runCatching { coordinator.cancelPicker(owner) }; cleanup(); finishActivity(EXTERNAL); render(); return
        }
        cleanup()
        if (owner != null) coordinator.close(owner, false) { requireActive() }
        closed = true; token = null; finish()
    }
    private fun releaseInput() { input?.let { runCatching { revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }; input = null }
    private fun releaseCamera() { capture?.let { FactoryPhotoCapture.cancel(it); runCatching { revokeUriPermission(it.uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }; capture = null }
    private fun cancelInput() {
        // Provider cancellation can itself block Binder. One bounded slot; no extra worker on retry.
        runCatching { CANCELLATION.execute { runCatching { signal.cancel() } } }
    }
    private fun cleanup() {
        selected?.bytes?.fill(0); selected = null
        // Do not close pending admission while the bounded worker still owns provisional bytes.
        val pending = if (!working) admission.also { admission = null } else null
        val camera = admissionCapture
        releaseCamera(); releaseInput()
        if (pending != null) { admissionCapture = null; FactoryPhotoCapture.afterCompletion(camera) { try { pending.close() } finally { FactoryInteractionAdmission.release(pending) } } }
    }
    private fun render() {
        if (!::status.isInitialized) return
        status.text = buildString {
            append(getString(R.string.factory_photos_disclosure))
            request?.let { append("\n\n").append(callingPackage).append("\n").append(it.operation) }
            when {
                rejected -> append("\n\n").append(getString(R.string.factory_photos_unavailable))
                working -> append("\n\n").append(getString(R.string.factory_documents_checking))
                selected != null -> append("\n\n").append(getString(R.string.factory_photos_selected))
            }
        }
        close.isEnabled = activeReady()
    }
    override fun onResume() { super.onResume(); resumed = true; launching = false; terminalPending = false; processResult(); advance(); render() }
    override fun onPause() {
        resumed = false; generation++
        if (!closed && !launching && !externalOwned && !terminalPending) {
            cancelInput(); cleanup(); token?.let { runCatching { coordinator.revoke(it) } }; token = null; rejected = true
        }
        super.onPause()
    }
    override fun onDestroy() {
        expiryHandler.removeCallbacks(expireSelection)
        if (!closed) { cancelInput(); cleanup(); if (externalOwned) runCatching { finishActivity(EXTERNAL) }; token?.let { runCatching { coordinator.revoke(it) } } }
        super.onDestroy()
    }
    companion object {
        private const val EXTERNAL = 6901
        private val CANCELLATION = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>()).apply { allowCoreThreadTimeOut(true) }
        private val WORKER = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1)).apply { allowCoreThreadTimeOut(true) }
        private fun android.content.pm.PackageInfo.longVersionCompat() = if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
        internal fun photoPicker(context: Context): Intent {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val picker = runCatching { FactoryDocumentActivity.trustedPicker(context, Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")) }.getOrNull()
                if (picker != null) return picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return FactoryDocumentActivity.trustedPicker(context, Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        internal fun validPhotoResult(context: Context, data: Intent?): Boolean {
            if (!FactoryDocumentActivity.validResult(data, Intent.FLAG_GRANT_READ_URI_PERMISSION)) return false
            val uri = data!!.data!!
            return FactoryDocumentActivity.allowedProvider(context.packageName, context.applicationInfo.uid,
                context.packageManager.resolveContentProvider(uri.authority!!, 0))
        }
    }
}
