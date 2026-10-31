package com.jarvys.agent.apkfactory

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.UriRelativeFilter
import android.content.UriRelativeFilterGroup
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Build
import android.os.PatternMatcher
import androidx.test.core.app.ApplicationProvider
import com.jarvys.factory.contract.ExternalLaunchSpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Synthetic fixed calendar intents and installed packages only; no calendar provider access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 32, 35], application = android.app.Application::class, shadows = [FactoryExternalLaunchPackageManagerShadow::class])
class FactoryCalendarEditorTargetsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val targets get() = FactoryExternalLaunchTargets(context)
    private fun denied(block: () -> Unit) = assertTrue("Expected recipient rejection", runCatching(block).isFailure)
    @Test fun exactExplicitInsertHasOnlyFixedEventUriMimeAndSevenTypedExtras() {
        for ((index, spec) in FactoryCalendarEditorTestPackages.SPECS.withIndex()) {
            val fixture = FactoryCalendarEditorTestPackages.install(context, "example.calendar$index", 24000 + index, spec)
            val target = targets.discover(spec).single { it.component == fixture.component }
            val exact = targets.verifiedIntent(target, spec)
            FactoryCalendarEditorTestPackages.assertExact(exact, spec)
            assertEquals(fixture.component, exact.component); assertNull(exact.`package`)
        }
    }
    @Test fun discoveryCarriesNoEventDataAndNoExtras() {
        val spec = FactoryCalendarEditorTestPackages.TIMED
        FactoryCalendarEditorTestPackages.install(context)
        val queries = mutableListOf<Intent>()
        FactoryExternalLaunchPackageManagerShadow.beforeQuery = { queries += Intent(it) }
        try {
            assertEquals(1, targets.discover(spec).size)
            val query = queries.single()
            assertEquals(Intent.ACTION_INSERT, query.action)
            assertEquals("content://com.android.calendar/events", query.dataString)
            assertEquals(FactoryCalendarEditorTestPackages.MIME, query.type); assertNull(query.extras)
            assertFalse(query.toString().contains(spec.calendar.title))
        } finally { FactoryExternalLaunchPackageManagerShadow.beforeQuery = null }
    }
    @Test fun onlyExactDirectoryEventMimeWithoutSchemeRestrictionsQualifies() {
        val spec = FactoryCalendarEditorTestPackages.TIMED
        val good = FactoryCalendarEditorTestPackages.filter(spec)
        assertTrue(FactoryExternalLaunchTargets.broadTypedFilter(good, spec))
        assertFalse(FactoryExternalLaunchTargets.broadTypedFilter(null, spec))
        for (mime in listOf("*/*", "vnd.android.cursor.dir/*", "vnd.android.cursor.item/event", "text/calendar", "text/plain")) {
            val filter = IntentFilter(Intent.ACTION_INSERT).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataType(mime) }
            assertFalse(FactoryExternalLaunchTargets.broadTypedFilter(filter, spec))
        }
        val filters = listOf(
            IntentFilter(Intent.ACTION_EDIT).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataType(FactoryCalendarEditorTestPackages.MIME) },
            IntentFilter(Intent.ACTION_INSERT).apply { addDataType(FactoryCalendarEditorTestPackages.MIME) },
            FactoryCalendarEditorTestPackages.filter(spec).apply { addDataScheme("content") },
            FactoryCalendarEditorTestPackages.filter(spec).apply { addDataAuthority("com.android.calendar", null) },
            FactoryCalendarEditorTestPackages.filter(spec).apply { addDataPath("/events", PatternMatcher.PATTERN_LITERAL) },
            FactoryCalendarEditorTestPackages.filter(spec).apply { addDataSchemeSpecificPart("events", PatternMatcher.PATTERN_PREFIX) },
            FactoryCalendarEditorTestPackages.filter(spec).apply { addDataType("text/plain") })
        filters.forEach { assertFalse(FactoryExternalLaunchTargets.broadTypedFilter(it, spec)) }
    }
    @Test @Config(sdk = [35]) fun relativeUriRestrictionsCannotQualify() {
        val spec = FactoryCalendarEditorTestPackages.TIMED
        val filter = FactoryCalendarEditorTestPackages.filter(spec)
        filter.addUriRelativeFilterGroup(UriRelativeFilterGroup(UriRelativeFilterGroup.ACTION_ALLOW).apply {
            addUriRelativeFilter(UriRelativeFilter(UriRelativeFilter.PATH, PatternMatcher.PATTERN_PREFIX, "/events"))
        })
        assertFalse(FactoryExternalLaunchTargets.broadTypedFilter(filter, spec))
    }
    @Test fun changedRecipientIdentityAndUnsafeAppsFailClosed() {
        val spec = FactoryCalendarEditorTestPackages.TIMED
        val fixture = FactoryCalendarEditorTestPackages.install(context)
        val reviewed = targets.discover(spec).single()
        fixture.signatures(arrayOf(Signature(byteArrayOf(9,8,7)))); denied { targets.verifiedIntent(reviewed, spec) }
        fixture.signatures(arrayOf(Signature(FactoryCalendarEditorTestPackages.CERT)))
        fixture.installed.versionCode = 8; denied { targets.verifiedIntent(reviewed, spec) }; fixture.installed.versionCode = 7
        fixture.installed.lastUpdateTime = 100; denied { targets.verifiedIntent(reviewed, spec) }; fixture.installed.lastUpdateTime = 99
        fixture.resolve.activityInfo.exported = false; assertTrue(targets.discover(spec).isEmpty()); fixture.resolve.activityInfo.exported = true
        fixture.resolve.activityInfo.permission = "android.permission.WRITE_CALENDAR"; assertTrue(targets.discover(spec).isEmpty()); fixture.resolve.activityInfo.permission = null
        fixture.installed.sharedUserId = "example.shared"; assertTrue(targets.discover(spec).isEmpty()); fixture.installed.sharedUserId = null
        shadowOf(context.packageManager).setPackagesForUid(fixture.uid, fixture.packageName, "example.shared")
        denied { targets.verifiedIntent(reviewed, spec) }
    }
}

internal object FactoryCalendarEditorTestPackages {
    const val MIME = "vnd.android.cursor.dir/event"
    const val TITLE = "Synthetic title & timezone?fixture=calendar"
    const val LOCATION = "Synthetic room / Tokyo 東京"
    const val DESCRIPTION = "Synthetic description\n\tReview without saving"
    val TIMED: ExternalLaunchSpec = ExternalLaunchSpec.calendar(TITLE, LOCATION, DESCRIPTION, 1791351000000L, 1791354600000L, "America/New_York", false)
    val ALL_DAY: ExternalLaunchSpec = ExternalLaunchSpec.calendar(TITLE, LOCATION, DESCRIPTION, 1791244800000L, 1791417600000L, "UTC", true)
    val SPECS = listOf(TIMED, ALL_DAY)
    val CERT = byteArrayOf(0x30, 0x04, 0x01, 0x02, 0x03, 0x04)
    fun filter(spec: ExternalLaunchSpec = TIMED) = IntentFilter(Intent.ACTION_INSERT).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataType(MIME) }
    fun view(spec: ExternalLaunchSpec = TIMED, discovery: Boolean = false): Intent = Intent(Intent.ACTION_INSERT)
        .setDataAndType(android.provider.CalendarContract.Events.CONTENT_URI, MIME).addCategory(Intent.CATEGORY_DEFAULT)
    fun method(spec: ExternalLaunchSpec) = "calendar.insert"
    fun args(spec: ExternalLaunchSpec) = spec.calendar.let { event -> org.json.JSONObject()
        .put("title", event.title).put("location", event.location).put("description", event.description)
        .put("startTimeMillis", event.startTimeMillis).put("endTimeMillis", event.endTimeMillis)
        .put("timeZone", event.timeZone).put("allDay", event.allDay).toString() }
    fun assertExact(intent: Intent, spec: ExternalLaunchSpec) {
        val event = spec.calendar
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals("content://com.android.calendar/events", intent.dataString); assertEquals(MIME, intent.type)
        assertEquals(setOf(Intent.CATEGORY_DEFAULT), intent.categories)
        assertEquals(0, intent.flags); assertNull(intent.clipData); assertNull(intent.selector)
        val extras = intent.extras!!
        assertEquals(setOf("title", "eventLocation", "description", "beginTime", "endTime", "eventTimezone", "allDay"), extras.keySet())
        assertEquals(event.title, extras.get("title")); assertEquals(event.location, extras.get("eventLocation")); assertEquals(event.description, extras.get("description"))
        assertEquals(event.startTimeMillis, extras.get("beginTime")); assertEquals(event.endTimeMillis, extras.get("endTime"))
        assertEquals(event.timeZone, extras.get("eventTimezone")); assertEquals(event.allDay, extras.get("allDay"))
        assertTrue(extras.get("beginTime") is Long); assertTrue(extras.get("endTime") is Long); assertTrue(extras.get("allDay") is Boolean)
    }
    class Fixture(val context: Context, val packageName: String, val uid: Int, val resolve: ResolveInfo) {
        val component get() = ComponentName(packageName, "$packageName.ExternalHandler")
        val installed: PackageInfo get() = shadowOf(context.packageManager).getInternalMutablePackageInfo(packageName)
        fun signatures(values: Array<Signature>) {
            installed.signatures = values
            if (Build.VERSION.SDK_INT >= 28) {
                installed.signingInfo = ReflectionHelpers.newInstance(SigningInfo::class.java).also { shadowOf(it).setSignatures(values) }
            }
        }
    }
    fun install(context: Context, packageName: String = "example.synthetic.external", uid: Int = 20003, spec: ExternalLaunchSpec = TIMED): Fixture {
        val app = ApplicationInfo().apply { this.packageName = packageName; this.uid = uid; enabled = true; flags = 0 }
        val activity = ActivityInfo().apply { this.packageName = packageName; name = "$packageName.ExternalHandler"; applicationInfo = app; enabled = true; exported = true }
        val info = PackageInfo().apply { this.packageName = packageName; applicationInfo = app; activities = arrayOf(activity); versionCode = 7; lastUpdateTime = 99 }
        val pm = shadowOf(context.packageManager); pm.installPackage(info); pm.setPackagesForUid(uid, packageName)
        val resolve = ResolveInfo().apply { activityInfo = activity; filter = filter(spec); isDefault = true }
        val fixture = Fixture(context, packageName, uid, resolve); fixture.signatures(arrayOf(Signature(CERT)))
        pm.addResolveInfoForIntentNoDefaults(view(spec, discovery = true), resolve)
        pm.addResolveInfoForIntentNoDefaults(view(spec).setPackage(packageName), resolve)
        return fixture
    }
}
