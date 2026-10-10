package com.jarvys.agent.apkfactory

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Process
import android.os.UserHandle
import android.provider.ContactsContract
import com.jarvys.factory.contract.ContactPickSpec
import java.security.MessageDigest

/** Closed Android contact row boundary. Only an owned human-started picker result can authorize an exact row. */
internal class FactoryContactSelection(private val context: Context) {
    data class Identity(val component: ComponentName, val uid: Int, val version: Long, val updated: Long, val certificates: List<String>)
    data class Target(val picker: Identity, val provider: Identity)
    private val pm get() = context.packageManager
    fun discover(kind: String): Target {
        val candidates = pm.queryIntentActivities(pick(kind), PackageManager.MATCH_DEFAULT_ONLY)
        check(candidates.size <= 32)
        val trusted = candidates.mapNotNull { result -> runCatching {
            val activity = result.activityInfo ?: error("Missing picker")
            check(activity.exported && activity.enabled && activity.permission.isNullOrEmpty())
            if (Build.VERSION.SDK_INT >= 30) check(!result.isCrossProfileIntentForwarderActivity)
            identity(activity.packageName, activity.name, activity.applicationInfo)
        }.getOrNull() }.distinct()
        check(trusted.size == 1) { "One trusted system contact picker is required" }
        return Target(trusted.single(), provider())
    }
    private fun provider(): Identity {
        val info = pm.resolveContentProvider(AUTHORITY, 0) ?: error("Contacts provider unavailable")
        check(info.enabled && info.exported && info.authority.split(';').contains(AUTHORITY))
        return identity(info.packageName, info.name, info.applicationInfo)
    }
    private fun identity(packageName: String, name: String, app: ApplicationInfo): Identity {
        check(app.packageName == packageName && app.enabled && app.uid != context.applicationInfo.uid)
        check(packageName !in setOf("com.jarvys.agent", "com.jarvys.agent.recoverytest"))
        check(app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
        check(app.flags and ApplicationInfo.FLAG_SUSPENDED == 0 && UserHandle.getUserHandleForUid(app.uid) == Process.myUserHandle())
        check(packageName.length in 1..255 && packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        check(name.length in 1..255 && name.all { it.code in 33..126 })
        val info = pm.getPackageInfo(packageName, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        check(info.packageName == packageName && info.applicationInfo?.uid == app.uid)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        check(!signatures.isNullOrEmpty() && signatures.size <= 4)
        val certificates = signatures!!.map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.sorted()
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        // AOSP's contacts provider legitimately has a platform shared UID; its exact identity is pinned.
        return Identity(ComponentName(packageName, name), app.uid, version, info.lastUpdateTime, certificates)
    }
    fun verifiedIntent(target: Target, kind: String): Intent {
        check(discover(kind) == target) { "System contact components changed" }
        return pick(kind).setComponent(target.picker.component)
    }
    fun read(target: Target, kind: String, result: Intent, signal: CancellationSignal, active: () -> Unit): String {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val uri = selectedUri(result)
        active(); check(!signal.isCanceled); check(discover(kind) == target)
        // AOSP can omit a redundant grant when the host already has READ_CONTACTS. Authority to
        // select this row comes from the live, consumed OS picker callback checked by active(),
        // never this permission or a caller-supplied URI. Both paths use this exact item query.
        check(canReadSelectedRow(uri))
        require(result.type == null || result.type == itemType(kind))
        active(); check(!signal.isCanceled)
        val value = context.contentResolver.query(uri, arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1), null, null, null, signal)?.use { cursor ->
            active(); check(!signal.isCanceled)
            check(cursor.columnNames.toList() == listOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1))
            active(); check(!signal.isCanceled)
            check(cursor.moveToFirst()); active(); check(!signal.isCanceled)
            check(cursor.getType(0) == android.database.Cursor.FIELD_TYPE_STRING && cursor.getString(0) == itemType(kind))
            active(); check(!signal.isCanceled)
            check(cursor.getType(1) == android.database.Cursor.FIELD_TYPE_STRING)
            active(); check(!signal.isCanceled)
            val datum = ContactPickSpec.value(cursor.getString(1))
            active(); check(!signal.isCanceled)
            check(!cursor.moveToNext()) { "Contact selection must contain exactly one datum" }
            active(); check(!signal.isCanceled); datum
        } ?: error("Contact row unavailable")
        active(); check(provider() == target.provider)
        check(canReadSelectedRow(uri))
        return value
    }
    private fun canReadSelectedRow(uri: Uri): Boolean =
        context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    companion object {
        private const val AUTHORITY = "com.android.contacts"
        internal fun pick(kind: String): Intent = Intent(Intent.ACTION_PICK).setType(when (ContactPickSpec.kind(kind)) {
            "phone" -> ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
            else -> ContactsContract.CommonDataKinds.Email.CONTENT_TYPE
        }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        internal fun itemType(kind: String) = when (ContactPickSpec.kind(kind)) {
            "phone" -> ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
            else -> ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
        }
        internal fun selectedUri(result: Intent): Uri {
            val uri = result.data ?: error("No selected contact")
            val raw = uri.toString()
            require(raw.length <= 128 && raw.matches(Regex("content://com\\.android\\.contacts/data/[1-9][0-9]{0,18}")))
            require(uri.lastPathSegment!!.toLongOrNull()?.let { it > 0 } == true)
            require(result.selector == null && result.component == null && result.`package` == null && result.categories == null)
            require(result.flags == 0 || result.flags == Intent.FLAG_GRANT_READ_URI_PERMISSION)
            require(result.sourceBounds == null && (Build.VERSION.SDK_INT < 29 || result.identifier == null))
            require(result.type == null || result.type in setOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE))
            require(result.action == null && (result.extras == null || result.extras!!.isEmpty))
            val clip = result.clipData
            require(clip == null || (clip.itemCount == 1 && clip.getItemAt(0).uri == uri && clip.getItemAt(0).intent == null && clip.getItemAt(0).text == null && clip.getItemAt(0).htmlText == null))
            return uri
        }
    }
}
