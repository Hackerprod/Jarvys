package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One policy-authorized photo interaction. The journal never contains a photo URI or photo bytes. */
internal class FactoryPhotoCoordinator(context: Context, private val policy: FactoryOperationPolicy = FactoryOperationPolicy.get(context), private val verify: (String) -> FactoryDocumentCoordinator.Proof = { FactoryDocumentCoordinator.verifyInstalled(context.applicationContext, it, setOf("documents", "photos")) }) {
    data class Request(val operation: String, val nonce: String) {
        companion object {
            fun parse(intent: Intent): Request {
                require(intent.data == null && intent.clipData == null && intent.selector == null && intent.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) == 0)
                val extras = intent.extras ?: error("Missing photo request")
                require(extras.keySet() == setOf("operation", "nonce"))
                val operation = extras.get("operation")
                val nonce = extras.get("nonce")
                require(operation == "pick" || operation == "capture")
                require(nonce is String && nonce.matches(Regex("[a-f0-9]{64}")))
                return Request(operation as String, nonce)
            }
        }
    }
    private val packageManager = context.applicationContext.packageManager
    private val journal = AtomicFile(File(context.noBackupFilesDir, "factory-photos/interaction.json"))
    private var permit: FactoryOperationPolicy.Grant? = null
    private var owner: String? = null
    private var proof: FactoryDocumentCoordinator.Proof? = null
    private var request: Request? = null
    private var broken = false
    private var verifying = false
    private var state = "idle"
    private var open = false
    private var restoredUnknown = false
    private var previousNonce: String? = null
    @Synchronized fun restore() {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return
        // Unknown outcomes reserve only this family; they never acquire a global human UI lease.
        FactoryInteractionAdmission.restore(this)
        try {
            val bytes = journal.openRead().use { input -> val bytes = ByteArray(4097); var count = 0; while (count < bytes.size) { val n = input.read(bytes, count, bytes.size - count); if (n < 0) break; check(n > 0); count += n }; require(count <= 4096); bytes.copyOf(count) }
            val saved = FactoryJson.objectFrom(bytes, 4096)
            require(saved.keys().asSequence().toSet() == setOf("schemaVersion", "state", "open", "nonce"))
            require(saved.get("schemaVersion") == 1 && saved.get("open") is Boolean && saved.get("nonce") is String)
            require(saved.getString("state") in STATES)
            previousNonce = saved.getString("nonce")
            open = saved.getBoolean("open")
            val savedState = saved.getString("state")
            require(if (open) savedState in setOf("review", "picker", "cancelling", "selected", "cancelled", "outcome_unknown") else savedState in setOf("returned", "cancelled", "closed_outcome_unknown"))
            require(previousNonce!!.matches(Regex("[a-f0-9]{64}")) || (previousNonce == "" && savedState == "closed_outcome_unknown"))
            state = if (open) "outcome_unknown" else savedState
            if (open) { save(); restoredUnknown = true } else FactoryInteractionAdmission.release(this)
        } catch (_: Exception) { broken = true; open = true; restoredUnknown = false; state = "outcome_unknown" }
    }
    private fun save() {
        try {
            check(journal.baseFile.parentFile!!.let { it.isDirectory || it.mkdirs() })
            val bytes = JSONObject().put("schemaVersion", 1).put("state", state).put("open", open).put("nonce", previousNonce ?: "").toString().toByteArray()
            val stream = journal.startWrite()
            try { stream.write(bytes); stream.fd.sync() } catch (e: Throwable) { journal.failWrite(stream); throw e }
            journal.finishWrite(stream)
            check(journal.readFully().contentEquals(bytes))
        } catch (e: Exception) { broken = true; open = true; restoredUnknown = false; state = "outcome_unknown"; permit?.revoke(); throw e }
    }
    private fun recoverable() = restoredUnknown && open && !broken && owner == null
    @Synchronized fun canBegin() = !broken && !verifying && (recoverable() || (!open && FactoryInteractionAdmission.available(this)))
    fun begin(callingPackage: String?, value: Request): String {
        require(!callingPackage.isNullOrBlank()) { "Android calling package is required" }
        synchronized(this) {
            check(canBegin()) { "A photo interaction is already in progress" }
            require(value.nonce != previousNonce) { "Replayed photo request" }
            if (!recoverable()) FactoryInteractionAdmission.acquire(this)
            verifying = true
        }
        var acquired: FactoryOperationPolicy.Grant? = null
        try {
            val identity = verify(callingPackage)
            require(identity.appId == callingPackage)
            val grant = policy.acquire(FactoryOperationPolicy.Subject.installed(identity),
                if (value.operation == "pick") FactoryOperationPolicy.Operation.PHOTO_PICK else FactoryOperationPolicy.Operation.PHOTO_CAPTURE,
                FactoryOperationPolicy.binding(value.operation, value.nonce))
            acquired = grant
            return synchronized(this) {
                check(!broken && (!open || recoverable()))
                grant.requireCurrent()
                if (recoverable()) {
                    check(grant.mode == FactoryOperationPolicy.Mode.ALLOW_ALWAYS) { "Historical outcome needs explicit recovery" }
                    state = "closed_outcome_unknown"; open = false; save(); restoredUnknown = false
                }
                permit = grant
                owner = UUID.randomUUID().toString(); proof = identity; request = value
                previousNonce = value.nonce; state = "review"; open = true; save()
                owner!!
            }
        } catch (failure: Exception) { acquired?.close(); throw failure }
        finally { synchronized(this) { verifying = false; if (!open && !broken) FactoryInteractionAdmission.release(this) } }
    }
    @Synchronized fun status() = state
    @Synchronized fun needsRecovery() = open && (owner == null || broken || permit?.let { runCatching { it.requireCurrent() }.isFailure } == true)
    private fun checkOwner(token: String) { check(open && !broken && token == owner) }
    @Synchronized fun authority(token: String): FactoryOperationPolicy.Grant { checkOwner(token); return permit ?: error("Authority revoked") }
    fun approve(token: String, human: () -> Unit) { authority(token).approveOnce(human) }
    private fun verified(token: String, active: () -> Unit): FactoryDocumentCoordinator.Proof {
        val p = synchronized(this) { checkOwner(token); active(); permit!!.checkBeforeEffect(); proof ?: error("Authority revoked") }
        check(verify(p.appId) == p) { "Installed app or signing evidence changed" }
        synchronized(this) { checkOwner(token); active(); permit!!.checkBeforeEffect(); check(proof == p) }
        return p
    }
    fun launch(token: String, active: () -> Unit) {
        val p = verified(token, active)
        synchronized(this) {
            checkOwner(token); active(); permit!!.checkBeforeEffect(); check(proof == p && state == "review")
            state = "picker"; save()
        }
    }
    fun transferVerifier(token: String, active: () -> Unit): (Int) -> Boolean {
        val pinned = verified(token, active)
        val authority = authority(token)
        val pm = packageManager
        val uid = pm.getApplicationInfo(pinned.appId, 0).uid
        check(pm.getPackagesForUid(uid)?.toSet() == setOf(pinned.appId))
        return { caller ->
            caller == uid && pm.getPackagesForUid(uid)?.toSet() == setOf(pinned.appId) &&
                runCatching { authority.checkBeforeEffect(); verify(pinned.appId) == pinned }.getOrDefault(false)
        }
    }
    @Synchronized fun pickerTerminal(token: String, selected: Boolean) {
        checkOwner(token); check(state == "picker" || state == "cancelling")
        if (selected && state == "picker") permit!!.checkBeforeEffect()
        state = if (selected && state == "picker") "selected" else "cancelled"; save()
    }
    @Synchronized fun cancelPicker(token: String) { checkOwner(token); check(state == "picker"); state = "cancelling"; proof = null; save() }
    @Synchronized fun revoke(token: String) {
        if (owner != token || !open) return
        proof = null; request = null; owner = null; state = "outcome_unknown"; restoredUnknown = false
        permit?.revoke(); permit?.close(); permit = null; save()
    }
    /** Consumption is durable before return. The Activity owns a successful grant until actual delivery. */
    fun close(token: String, deliver: Boolean, active: () -> Unit): Request? {
        val p = if (deliver) verified(token, active) else null
        return synchronized(this) {
            checkOwner(token); active()
            check(state in setOf("review", "selected", "cancelled"))
            val result = if (deliver) { permit!!.checkBeforeEffect(); check(state == "selected" && proof == p); request } else null
            state = if (deliver) "returned" else "cancelled"; open = false; save()
            proof = null; request = null; owner = null
            if (!deliver) permit?.close()
            permit = null; FactoryInteractionAdmission.release(this)
            result
        }
    }
    /** This acknowledges uncertainty, never asserts that an old external picker was closed. */
    @Synchronized fun acknowledgeRecovery(active: () -> Unit) {
        active(); check(needsRecovery())
        state = "closed_outcome_unknown"; open = false; if (previousNonce?.matches(Regex("[a-f0-9]{64}")) != true) previousNonce = ""; save(); broken = false; restoredUnknown = false
        proof = null; request = null; owner = null; permit?.close(); permit = null; FactoryInteractionAdmission.release(this)
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
