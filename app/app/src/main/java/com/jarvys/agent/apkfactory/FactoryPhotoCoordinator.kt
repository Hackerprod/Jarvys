package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import android.util.AtomicFile
import com.jarvys.agent.MemoryUiAutomationGuard
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One human-owned photo interaction. The journal never contains a photo URI or photo bytes. */
internal class FactoryPhotoCoordinator(private val context: Context, private val verify: (String) -> FactoryDocumentCoordinator.Proof = { FactoryDocumentCoordinator.verifyInstalled(context, it, setOf("documents", "photos")) }) {
    data class Request(val operation: String, val nonce: String) {
        companion object {
            fun parse(intent: Intent): Request {
                require(intent.data == null && intent.clipData == null && intent.selector == null && intent.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) == 0)
                val extras = intent.extras ?: error("Missing photo request")
                require(extras.keySet() == setOf("operation", "nonce"))
                @Suppress("DEPRECATION") val operation = extras.get("operation")
                @Suppress("DEPRECATION") val nonce = extras.get("nonce")
                require(operation == "pick" || operation == "capture")
                require(nonce is String && nonce.matches(Regex("[a-f0-9]{64}")))
                return Request(operation as String, nonce)
            }
        }
    }
    private val journal = AtomicFile(File(context.noBackupFilesDir, "factory-photos/interaction.json"))
    private var lease: MemoryUiAutomationGuard.Lease? = null
    private var owner: String? = null
    private var proof: FactoryDocumentCoordinator.Proof? = null
    private var request: Request? = null
    private var broken = false
    private var verifying = false
    private var state = "idle"
    private var open = false
    private var previousNonce: String? = null
    private fun protect() { if (lease == null) lease = MemoryUiAutomationGuard.enterProtectedSurface() }
    @Synchronized fun restore() {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return
        // Protection precedes parsing: malformed/truncated state also fails closed.
        protect(); FactoryInteractionAdmission.restore(this)
        try {
            val bytes = journal.openRead().use { input -> val bytes = ByteArray(4097); var count = 0; while (count < bytes.size) { val n = input.read(bytes, count, bytes.size - count); if (n < 0) break; count += n }; require(count <= 4096); bytes.copyOf(count) }
            val saved = FactoryJson.objectFrom(bytes, 4096)
            require(saved.keys().asSequence().toSet() == setOf("schemaVersion", "state", "open", "nonce"))
            require(saved.get("schemaVersion") == 1 && saved.get("open") is Boolean && saved.get("nonce") is String)
            require(saved.getString("state") in STATES)
            previousNonce = saved.getString("nonce")
            open = saved.getBoolean("open")
            val savedState = saved.getString("state")
            require(if (open) savedState in setOf("review", "picker", "cancelling", "selected", "cancelled", "outcome_unknown") else savedState in setOf("returned", "cancelled", "closed_outcome_unknown"))
            require(previousNonce!!.matches(Regex("[a-f0-9]{64}")) || (previousNonce == "" && savedState == "closed_outcome_unknown"))
            state = if (open) "outcome_unknown" else saved.getString("state")
            if (open) save() else { lease?.close(); lease = null; FactoryInteractionAdmission.release(this) }
        } catch (_: Exception) { broken = true; open = true; state = "outcome_unknown" }
    }
    private fun save() {
        try {
            check(journal.baseFile.parentFile!!.let { it.isDirectory || it.mkdirs() })
            val bytes = JSONObject().put("schemaVersion", 1).put("state", state).put("open", open).put("nonce", previousNonce ?: "").toString().toByteArray()
            val stream = journal.startWrite()
            try { stream.write(bytes); stream.fd.sync() } catch (e: Throwable) { journal.failWrite(stream); throw e }
            journal.finishWrite(stream)
            check(journal.readFully().contentEquals(bytes))
        } catch (e: Exception) { broken = true; open = true; state = "outcome_unknown"; protect(); throw e }
    }
    @Synchronized fun canBegin() = !open && !broken && !verifying && FactoryInteractionAdmission.available()
    fun begin(callingPackage: String?, value: Request): String {
        require(!callingPackage.isNullOrBlank()) { "Android calling package is required" }
        synchronized(this) {
            check(!open && !broken && !verifying) { "A photo interaction is already in progress" }
            require(value.nonce != previousNonce) { "Replayed photo request" }
            FactoryInteractionAdmission.acquire(this)
            verifying = true
        }
        try {
            val identity = verify(callingPackage)
            return synchronized(this) {
                check(!open && !broken)
                require(identity.appId == callingPackage)
                protect()
                if (lease?.isReadyForUser != true) { lease?.close(); lease = null; error("Automated action in flight") }
                owner = UUID.randomUUID().toString(); proof = identity; request = value
                previousNonce = value.nonce; state = "review"; open = true; save()
                owner!!
            }
        } finally { synchronized(this) { verifying = false; if (!open && !broken) FactoryInteractionAdmission.release(this) } }
    }
    @Synchronized fun status() = state
    @Synchronized fun needsRecovery() = open && (owner == null || broken)
    private fun checkOwner(token: String) { check(open && !broken && token == owner) }
    private fun verified(token: String, human: () -> Unit): FactoryDocumentCoordinator.Proof {
        val p = synchronized(this) { checkOwner(token); human(); lease!!.requireHumanUiInteraction(); proof ?: error("Authority revoked") }
        check(verify(p.appId) == p) { "Installed app or signing evidence changed" }
        return p
    }
    fun launch(token: String, human: () -> Unit) {
        val p = verified(token, human)
        synchronized(this) {
            checkOwner(token); human(); lease!!.requireHumanUiInteraction(); check(proof == p && state == "review")
            state = "picker"; save()
        }
    }
    fun transferVerifier(token: String, human: () -> Unit): (Int) -> Boolean {
        val pinned = verified(token, human)
        val pm = context.packageManager
        val uid = pm.getApplicationInfo(pinned.appId, 0).uid
        return { caller ->
            caller == uid && pm.getPackagesForUid(uid)?.toList() == listOf(pinned.appId) &&
                runCatching { verify(pinned.appId) == pinned }.getOrDefault(false)
        }
    }
    @Synchronized fun pickerTerminal(token: String, selected: Boolean) {
        checkOwner(token); check(state == "picker" || state == "cancelling")
        state = if (selected && state == "picker") "selected" else "cancelled"; save()
    }
    @Synchronized fun cancelPicker(token: String) { checkOwner(token); check(state == "picker"); state = "cancelling"; proof = null; save() }
    @Synchronized fun revoke(token: String) {
        if (owner != token || !open) return
        proof = null; request = null; owner = null; state = "outcome_unknown"; save()
    }
    /** Only the foreground original Activity may return the URI, after a second native human action. */
    fun close(token: String, deliver: Boolean, human: () -> Unit): Request? {
        val p = if (deliver) verified(token, human) else null
        return synchronized(this) {
        checkOwner(token); human(); lease!!.requireHumanUiInteraction()
        check(state in setOf("review", "selected", "cancelled"))
        val result = if (deliver) { check(state == "selected" && proof == p); request } else null
        state = if (deliver) "returned" else "cancelled"; open = false; save()
        proof = null; request = null; owner = null; lease?.close(); lease = null; FactoryInteractionAdmission.release(this)
        result
        }
    }
    /** This acknowledges uncertainty, never asserts that an old external picker was closed. */
    @Synchronized fun acknowledgeRecovery(human: () -> Unit) {
        human(); check(needsRecovery()); lease!!.requireHumanUiInteraction()
        state = "closed_outcome_unknown"; open = false; if (previousNonce?.matches(Regex("[a-f0-9]{64}")) != true) previousNonce = ""; save(); broken = false
        proof = null; request = null; owner = null; lease?.close(); lease = null; FactoryInteractionAdmission.release(this)
    }
    companion object {
        private val STATES = setOf("idle", "review", "picker", "cancelling", "selected", "cancelled", "returned", "outcome_unknown", "closed_outcome_unknown")
        private val HOSTS = setOf("com.jarvys.agent", "com.jarvys.agent.recoverytest")
        private var recoveryToken: String? = null
        @Synchronized fun recoveryIntent(context: Context): Intent {
            recoveryToken = UUID.randomUUID().toString()
            return Intent(context, FactoryPhotoActivity::class.java).putExtra("nativeRecoveryToken", recoveryToken)
        }
        @Synchronized fun consumeRecoveryToken(intent: Intent): Boolean {
            if (intent.data != null || intent.clipData != null || intent.selector != null || intent.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) != 0) return false
            val token = intent.getStringExtra("nativeRecoveryToken")
            if (token == null || token != recoveryToken || intent.extras?.keySet() != setOf("nativeRecoveryToken")) return false
            recoveryToken = null
            return true
        }
        @Volatile private var instance: FactoryPhotoCoordinator? = null
        fun get(context: Context): FactoryPhotoCoordinator = instance ?: synchronized(this) {
            instance ?: FactoryPhotoCoordinator(context.applicationContext).also { it.restore(); instance = it }
        }
    }
}
