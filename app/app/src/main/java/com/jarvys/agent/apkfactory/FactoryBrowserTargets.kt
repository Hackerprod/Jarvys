package com.jarvys.agent.apkfactory

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.core.net.toUri
import android.os.Build
import android.os.Process
import android.os.UserHandle
import com.jarvys.factory.contract.BrowserUrl
import java.security.MessageDigest

/** Public SDK-only browser discovery. A broad filter is eligibility, not a trust certificate. */
internal class FactoryBrowserTargets(private val context: Context) {
    data class Target(val component: ComponentName, val certificate: String, val version: Long, val updated: Long, val uid: Int)
    private val pm get() = context.packageManager
    private val queryFlags = PackageManager.MATCH_ALL or PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
    fun discover(): List<Target> {
        val results = pm.queryIntentActivities(view("https://browser-discovery.invalid/"), queryFlags)
        require(results.size <= 64) { "Too many browser candidates" }
        return results.mapNotNull { info -> runCatching { eligible(info); snapshot(info) }.getOrNull() }
            .distinctBy { it.component }.sortedBy { it.component.flattenToString() }.also { require(it.size <= 16) }
    }
    fun verifiedIntent(target: Target, url: String): Intent {
        val value = BrowserUrl.parse(url)
        check(target in discover()) { "Browser changed since review" }
        val matches = pm.queryIntentActivities(view(value.url).setPackage(target.component.packageName), queryFlags)
        require(matches.size <= 64)
        check(matches.any { info -> runCatching { eligible(info); snapshot(info) == target }.getOrDefault(false) })
        return view(value.url).setComponent(target.component)
    }
    private fun eligible(info: ResolveInfo) {
        val activity = info.activityInfo ?: error("Missing browser activity")
        val app = activity.applicationInfo ?: error("Missing browser application")
        check(activity.exported && activity.enabled && app.enabled && activity.permission.isNullOrEmpty())
        check(app.flags and ApplicationInfo.FLAG_SUSPENDED == 0)
        check(UserHandle.getUserHandleForUid(app.uid) == Process.myUserHandle()) { "Cross-profile browser unsupported" }
        check(activity.packageName == app.packageName && activity.packageName !in setOf("com.jarvys.agent", "com.jarvys.agent.recoverytest"))
        check(activity.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) && activity.packageName.length <= 255)
        check(activity.name.length in 1..255 && activity.name.all { it.code in 33..126 })
        check(pm.getPackagesForUid(app.uid)?.toSet() == setOf(app.packageName)) { "Shared browser UID unsupported" }
        if (Build.VERSION.SDK_INT >= 30) check(!info.isCrossProfileIntentForwarderActivity)
        if (Build.VERSION.SDK_INT >= 26) check(!pm.isInstantApp(app.packageName) && !info.isInstantAppAvailable)
        check(broadHttpsFilter(info.filter)) { "Not a general HTTPS browser filter" }
    }
    private fun snapshot(info: ResolveInfo): Target {
        val activity = info.activityInfo
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(activity.packageName, flags)
        check(installed.sharedUserId == null) { "Shared browser identity unsupported" }
        val signatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        check(signatures?.size == 1)
        val certificate = MessageDigest.getInstance("SHA-256").digest(signatures!![0].toByteArray()).joinToString("") { "%02x".format(it) }
        val version = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        check(installed.packageName == activity.packageName && installed.applicationInfo?.uid == activity.applicationInfo.uid)
        return Target(ComponentName(activity.packageName, activity.name), certificate, version, installed.lastUpdateTime, activity.applicationInfo.uid)
    }
    companion object {
        private fun view(url: String) = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        internal fun broadHttpsFilter(filter: IntentFilter?): Boolean = filter != null &&
            filter.hasAction(Intent.ACTION_VIEW) && filter.hasCategory(Intent.CATEGORY_BROWSABLE) && filter.hasCategory(Intent.CATEGORY_DEFAULT) &&
            filter.hasDataScheme("https") && filter.countDataAuthorities() == 0 && filter.countDataPaths() == 0 &&
            filter.countDataSchemeSpecificParts() == 0 && filter.countDataTypes() == 0 &&
            (Build.VERSION.SDK_INT < 35 || filter.countUriRelativeFilterGroups() == 0)
    }
}
