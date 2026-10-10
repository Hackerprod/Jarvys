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
import android.provider.CalendarContract
import android.os.Process
import android.os.UserHandle
import com.jarvys.factory.contract.ExternalLaunchSpec
import java.security.MessageDigest

/** Public SDK-only external recipient discovery. A broad filter is eligibility, not a trust certificate. */
internal class FactoryExternalLaunchTargets(private val context: Context) {
    data class Target(val component: ComponentName, val certificate: String, val version: Long, val updated: Long, val uid: Int)
    private val pm get() = context.packageManager
    private val queryFlags = PackageManager.MATCH_ALL or PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
    fun discover(spec: ExternalLaunchSpec): List<Target> {
        val results = pm.queryIntentActivities(view(spec, discovery = true), queryFlags)
        require(results.size <= 64) { "Too many external recipient candidates" }
        return results.mapNotNull { info -> runCatching { eligible(info, spec); snapshot(info) }.getOrNull() }
            .distinctBy { it.component }.sortedBy { it.component.flattenToString() }.also { require(it.size <= 16) }
    }
    fun verifiedIntent(target: Target, spec: ExternalLaunchSpec): Intent {
        check(target in discover(spec)) { "External recipient changed since review" }
        val matches = pm.queryIntentActivities(view(spec).setPackage(target.component.packageName), queryFlags)
        require(matches.size <= 64)
        check(matches.any { info -> runCatching { eligible(info, spec); snapshot(info) == target }.getOrDefault(false) })
        return view(spec).setComponent(target.component)
    }
    private fun eligible(info: ResolveInfo, spec: ExternalLaunchSpec) {
        val activity = info.activityInfo ?: error("Missing external recipient activity")
        val app = activity.applicationInfo ?: error("Missing external recipient application")
        check(activity.exported && activity.enabled && app.enabled && activity.permission.isNullOrEmpty())
        check(app.flags and ApplicationInfo.FLAG_SUSPENDED == 0)
        check(UserHandle.getUserHandleForUid(app.uid) == Process.myUserHandle()) { "Cross-profile external recipient unsupported" }
        check(activity.packageName == app.packageName && activity.packageName !in setOf("com.jarvys.agent", "com.jarvys.agent.recoverytest"))
        check(activity.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) && activity.packageName.length <= 255)
        check(activity.name.length in 1..255 && activity.name.all { it.code in 33..126 })
        check(pm.getPackagesForUid(app.uid)?.toSet() == setOf(app.packageName)) { "Shared external recipient UID unsupported" }
        if (Build.VERSION.SDK_INT >= 30) check(!info.isCrossProfileIntentForwarderActivity)
        if (Build.VERSION.SDK_INT >= 26) check(!pm.isInstantApp(app.packageName) && !info.isInstantAppAvailable)
        check(broadTypedFilter(info.filter, spec)) { "Not an unrestricted typed external action filter" }
    }
    private fun snapshot(info: ResolveInfo): Target {
        val activity = info.activityInfo
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(activity.packageName, flags)
        check(installed.sharedUserId == null) { "Shared external recipient identity unsupported" }
        val signatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        check(signatures?.size == 1)
        val certificate = MessageDigest.getInstance("SHA-256").digest(signatures!![0].toByteArray()).joinToString("") { "%02x".format(it) }
        val version = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        check(installed.packageName == activity.packageName && installed.applicationInfo?.uid == activity.applicationInfo.uid)
        return Target(ComponentName(activity.packageName, activity.name), certificate, version, installed.lastUpdateTime, activity.applicationInfo.uid)
    }
    companion object {
        private const val EVENTS_MIME = "vnd.android.cursor.dir/event"
        private fun action(spec: ExternalLaunchSpec) = when (spec.kind) {
            ExternalLaunchSpec.Kind.MAPS_COORDINATES, ExternalLaunchSpec.Kind.MAPS_QUERY -> Intent.ACTION_VIEW
            ExternalLaunchSpec.Kind.PHONE_DIAL -> Intent.ACTION_DIAL
            ExternalLaunchSpec.Kind.EMAIL_COMPOSE, ExternalLaunchSpec.Kind.SMS_COMPOSE -> Intent.ACTION_SENDTO
            ExternalLaunchSpec.Kind.CALENDAR_INSERT -> Intent.ACTION_INSERT
        }
        private fun scheme(spec: ExternalLaunchSpec) = when (spec.kind) {
            ExternalLaunchSpec.Kind.MAPS_COORDINATES, ExternalLaunchSpec.Kind.MAPS_QUERY -> "geo"
            ExternalLaunchSpec.Kind.PHONE_DIAL -> "tel"
            ExternalLaunchSpec.Kind.EMAIL_COMPOSE -> "mailto"
            ExternalLaunchSpec.Kind.SMS_COMPOSE -> "smsto"
            ExternalLaunchSpec.Kind.CALENDAR_INSERT -> error("Calendar uses a fixed MIME type")
        }
        private fun view(spec: ExternalLaunchSpec, discovery: Boolean = false): Intent {
            if (spec.kind == ExternalLaunchSpec.Kind.CALENDAR_INSERT) {
                return Intent(Intent.ACTION_INSERT).setDataAndType(CalendarContract.Events.CONTENT_URI, EVENTS_MIME)
                    .addCategory(Intent.CATEGORY_DEFAULT).apply {
                        if (!discovery) {
                            val event = spec.calendar
                            putExtra(CalendarContract.Events.TITLE, event.title)
                            putExtra(CalendarContract.Events.EVENT_LOCATION, event.location)
                            putExtra(CalendarContract.Events.DESCRIPTION, event.description)
                            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startTimeMillis)
                            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endTimeMillis)
                            putExtra(CalendarContract.Events.EVENT_TIMEZONE, event.timeZone)
                            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
                        }
                    }
            }
            val uri = if (!discovery) spec.uri else when (spec.kind) {
                ExternalLaunchSpec.Kind.MAPS_COORDINATES, ExternalLaunchSpec.Kind.MAPS_QUERY -> "geo:0,0"
                ExternalLaunchSpec.Kind.PHONE_DIAL -> "tel:0"
                ExternalLaunchSpec.Kind.EMAIL_COMPOSE -> "mailto:fixture@example.invalid"
                ExternalLaunchSpec.Kind.SMS_COMPOSE -> "smsto:0"
                ExternalLaunchSpec.Kind.CALENDAR_INSERT -> error("Calendar handled above")
            }
            return Intent(action(spec), uri.toUri()).addCategory(Intent.CATEGORY_DEFAULT).apply {
                if (!discovery) when (spec.kind) {
                    ExternalLaunchSpec.Kind.EMAIL_COMPOSE -> {
                        putExtra(Intent.EXTRA_SUBJECT, spec.subject)
                        putExtra(Intent.EXTRA_TEXT, spec.body)
                    }
                    ExternalLaunchSpec.Kind.SMS_COMPOSE -> putExtra("sms_body", spec.body)
                    ExternalLaunchSpec.Kind.MAPS_COORDINATES, ExternalLaunchSpec.Kind.MAPS_QUERY, ExternalLaunchSpec.Kind.PHONE_DIAL, ExternalLaunchSpec.Kind.CALENDAR_INSERT -> Unit
                }
            }
        }
        internal fun broadTypedFilter(filter: IntentFilter?, spec: ExternalLaunchSpec): Boolean = filter != null &&
            filter.hasAction(action(spec)) && filter.hasCategory(Intent.CATEGORY_DEFAULT) &&
            (if (spec.kind == ExternalLaunchSpec.Kind.CALENDAR_INSERT)
                filter.countDataSchemes() == 0 && filter.countDataTypes() == 1 && filter.getDataType(0) == EVENTS_MIME
             else filter.hasDataScheme(scheme(spec)) && filter.countDataTypes() == 0) &&
            filter.countDataAuthorities() == 0 && filter.countDataPaths() == 0 && filter.countDataSchemeSpecificParts() == 0 &&
            (Build.VERSION.SDK_INT < 35 || filter.countUriRelativeFilterGroups() == 0)
    }
}
