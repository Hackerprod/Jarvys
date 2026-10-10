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

/** Actual public-PackageManager verification against synthetic maps/dialer packages; no dispatch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 32, 35], application = android.app.Application::class)
class FactoryExternalLaunchTargetsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val targets get() = FactoryExternalLaunchTargets(context)
    private val spec get() = FactoryExternalLaunchTestPackages.MAPS_QUERY
    private fun denied(block: () -> Unit) = assertTrue("Expected recipient rejection", runCatching(block).isFailure)
    @Test fun broadTypedFiltersRequireMatchingActionSchemeAndDefaultButNotBrowsable() {
        for (value in FactoryExternalLaunchTestPackages.SPECS) {
            assertTrue(FactoryExternalLaunchTargets.broadTypedFilter(FactoryExternalLaunchTestPackages.filter(value), value))
            assertFalse(FactoryExternalLaunchTargets.broadTypedFilter(null, value))
            val action = FactoryExternalLaunchTestPackages.action(value)
            val scheme = FactoryExternalLaunchTestPackages.scheme(value)
            for (filter in listOf(IntentFilter(Intent.ACTION_SEND), IntentFilter(action).apply { addDataScheme(scheme) },
                IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https") },
                IntentFilter(Intent.ACTION_CALL).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme(scheme) },
                IntentFilter(if (action == Intent.ACTION_VIEW) Intent.ACTION_DIAL else Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme(scheme) })) {
                assertFalse(FactoryExternalLaunchTargets.broadTypedFilter(filter, value))
            }
        }
    }
    @Test fun deepLinksMimePathsAuthoritiesAndSchemePartsCannotMasqueradeAsGeneralRecipients() {
        for (value in FactoryExternalLaunchTestPackages.SPECS) {
            for (filter in listOf(FactoryExternalLaunchTestPackages.filter(value).apply { addDataAuthority("example.invalid", null) },
                FactoryExternalLaunchTestPackages.filter(value).apply { addDataPath("/only", PatternMatcher.PATTERN_PREFIX) },
                FactoryExternalLaunchTestPackages.filter(value).apply { addDataSchemeSpecificPart("+123", PatternMatcher.PATTERN_PREFIX) },
                FactoryExternalLaunchTestPackages.filter(value).apply { addDataType("text/plain") })) {
                assertFalse(FactoryExternalLaunchTargets.broadTypedFilter(filter, value))
            }
        }
    }
    @Test @Config(sdk = [35]) fun uriRelativeFilterGroupsAreNotGeneralMapsOrDialerFiltersOnApi35() {
        for (value in FactoryExternalLaunchTestPackages.SPECS) {
            val filter = FactoryExternalLaunchTestPackages.filter(value)
            filter.addUriRelativeFilterGroup(UriRelativeFilterGroup(UriRelativeFilterGroup.ACTION_ALLOW).apply {
                addUriRelativeFilter(UriRelativeFilter(UriRelativeFilter.PATH, PatternMatcher.PATTERN_PREFIX, "/restricted"))
            })
            assertFalse(FactoryExternalLaunchTargets.broadTypedFilter(filter, value))
        }
    }
    @Test fun exactExplicitLaunchHasReviewedTypedUriAndNoGrantsExtrasOrChooser() {
        val fixture = FactoryExternalLaunchTestPackages.install(context)
        val target = targets.discover(spec).single(); val intent = targets.verifiedIntent(target, spec)
        assertEquals(fixture.component, target.component); assertEquals(64, target.certificate.length); assertEquals(7L, target.version); assertEquals(99L, target.updated); assertEquals(fixture.uid, target.uid)
        assertEquals(Intent.ACTION_VIEW, intent.action); assertEquals(spec.uri, intent.dataString)
        assertEquals(setOf(Intent.CATEGORY_DEFAULT), intent.categories); assertEquals(fixture.component, intent.component)
        assertEquals(0, intent.flags); assertNull(intent.clipData); assertNull(intent.selector); assertNull(intent.extras)
    }
    @Test fun mapsCoordinatesQueriesAndPhoneEachResolveOnlyTheirExactExplicitSafeAction() {
        for ((index, value) in FactoryExternalLaunchTestPackages.SPECS.withIndex()) {
            val fixture = FactoryExternalLaunchTestPackages.install(context, "example.typed$index", 22000 + index, value)
            val target = targets.discover(value).single { it.component == fixture.component }
            val intent = targets.verifiedIntent(target, value)
            assertEquals(FactoryExternalLaunchTestPackages.action(value), intent.action); assertNotEquals(Intent.ACTION_CALL, intent.action)
            assertEquals(value.uri, intent.dataString); assertEquals(setOf(Intent.CATEGORY_DEFAULT), intent.categories)
            assertEquals(fixture.component, intent.component); assertNull(intent.`package`); assertNull(intent.extras)
            assertNull(intent.selector); assertNull(intent.clipData); assertEquals(0, intent.flags)
            assertFalse(fixture.resolve.filter.hasCategory(Intent.CATEGORY_BROWSABLE))
        }
    }
    @Test fun mapRecipientCannotBeReusedAsDialerAndDialerCannotBeReusedAsMapRecipient() {
        val map = FactoryExternalLaunchTestPackages.install(context, "example.map", 23001, spec)
        val dial = FactoryExternalLaunchTestPackages.install(context, "example.dial", 23002, FactoryExternalLaunchTestPackages.PHONE)
        val mapTarget = targets.discover(spec).single { it.component == map.component }
        val dialTarget = targets.discover(FactoryExternalLaunchTestPackages.PHONE).single { it.component == dial.component }
        denied { targets.verifiedIntent(mapTarget, FactoryExternalLaunchTestPackages.PHONE) }
        denied { targets.verifiedIntent(dialTarget, spec) }
    }
    @Test fun dialerRejectsCallOnlyWrongSchemePrivateAndSharedUidRecipients() {
        val phone = FactoryExternalLaunchTestPackages.PHONE
        val fixture = FactoryExternalLaunchTestPackages.install(context, spec = phone)
        val reviewed = targets.discover(phone).single()
        for (filter in listOf(IntentFilter(Intent.ACTION_CALL).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("tel") },
            IntentFilter(Intent.ACTION_DIAL).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("geo") },
            FactoryExternalLaunchTestPackages.filter(phone).apply { addDataSchemeSpecificPart("+1", PatternMatcher.PATTERN_PREFIX) })) {
            fixture.resolve.filter = filter; assertTrue(targets.discover(phone).isEmpty()); denied { targets.verifiedIntent(reviewed, phone) }
        }
        fixture.resolve.filter = FactoryExternalLaunchTestPackages.filter(phone)
        fixture.resolve.activityInfo.permission = "android.permission.CALL_PHONE"; assertTrue(targets.discover(phone).isEmpty())
        fixture.resolve.activityInfo.permission = null; fixture.installed.sharedUserId = "example.shared.dialer"
        assertTrue(targets.discover(phone).isEmpty()); denied { targets.verifiedIntent(reviewed, phone) }
    }
    @Test fun unsignedAndMultipleSignerRecipientsAreUnavailable() {
        val fixture = FactoryExternalLaunchTestPackages.install(context)
        fixture.signatures(emptyArray()); assertTrue(targets.discover(spec).isEmpty())
        fixture.signatures(arrayOf(Signature(byteArrayOf(1, 2)), Signature(byteArrayOf(3, 4)))); assertTrue(targets.discover(spec).isEmpty())
    }
    @Test fun certificateVersionUpdateTimeUidAndPackageIdentityMismatchRejectReviewedRecipient() {
        val fixture = FactoryExternalLaunchTestPackages.install(context); val reviewed = targets.discover(spec).single()
        fixture.signatures(arrayOf(Signature(byteArrayOf(9, 8, 7)))); denied { targets.verifiedIntent(reviewed, spec) }
        fixture.signatures(arrayOf(Signature(FactoryExternalLaunchTestPackages.CERT))); fixture.installed.versionCode = 8
        denied { targets.verifiedIntent(reviewed, spec) }; fixture.installed.versionCode = 7
        fixture.installed.lastUpdateTime = 100; denied { targets.verifiedIntent(reviewed, spec) }; fixture.installed.lastUpdateTime = 99
        fixture.installed.applicationInfo!!.uid = fixture.uid + 1; denied { targets.verifiedIntent(reviewed, spec) }
        fixture.installed.applicationInfo!!.uid = fixture.uid; fixture.installed.packageName = "example.other.recipient"
        denied { targets.verifiedIntent(reviewed, spec) }
    }
    @Test fun disabledUnexportedPermissionGuardedSuspendedAndWrongPackageRecipientsAreRejected() {
        val fixture = FactoryExternalLaunchTestPackages.install(context); val info = fixture.resolve.activityInfo
        info.enabled = false; assertTrue(targets.discover(spec).isEmpty()); info.enabled = true
        info.exported = false; assertTrue(targets.discover(spec).isEmpty()); info.exported = true
        info.permission = "example.recipient.PRIVATE"; assertTrue(targets.discover(spec).isEmpty()); info.permission = null
        info.applicationInfo.enabled = false; assertTrue(targets.discover(spec).isEmpty()); info.applicationInfo.enabled = true
        info.applicationInfo.flags = ApplicationInfo.FLAG_SUSPENDED; assertTrue(targets.discover(spec).isEmpty()); info.applicationInfo.flags = 0
        info.applicationInfo.packageName = "example.wrong.recipient"; assertTrue(targets.discover(spec).isEmpty())
    }
    @Test fun declaredSharedUidIsRejectedEvenWhenPackageVisibilityExposesOnlyTheRecipient() {
        val fixture = FactoryExternalLaunchTestPackages.install(context)
        val reviewed = targets.discover(spec).single()
        assertArrayEquals(arrayOf(fixture.packageName), context.packageManager.getPackagesForUid(fixture.uid))
        fixture.installed.sharedUserId = "example.synthetic.shared"
        assertArrayEquals("Visible UID membership alone does not prove absence of shared UID", arrayOf(fixture.packageName), context.packageManager.getPackagesForUid(fixture.uid))
        assertTrue(targets.discover(spec).isEmpty())
        denied { targets.verifiedIntent(reviewed, spec) }
    }
    @Test fun sharedUidAndDifferentAndroidProfileAreUnavailable() {
        val fixture = FactoryExternalLaunchTestPackages.install(context); val pm = shadowOf(context.packageManager)
        pm.setPackagesForUid(fixture.uid, fixture.packageName, "example.shared.recipient"); assertTrue(targets.discover(spec).isEmpty())
        pm.setPackagesForUid(fixture.uid, fixture.packageName)
        fixture.resolve.activityInfo.applicationInfo.uid = 120003; fixture.installed.applicationInfo!!.uid = 120003
        pm.setPackagesForUid(120003, fixture.packageName); assertTrue(targets.discover(spec).isEmpty())
    }
    @Test fun hostPackageAndUnboundedComponentNamesCannotBeRecipients() {
        val fixture = FactoryExternalLaunchTestPackages.install(context); val activity = fixture.resolve.activityInfo
        activity.name = "example.recipient.\nHidden"; assertTrue(targets.discover(spec).isEmpty()); activity.name = "x".repeat(256); assertTrue(targets.discover(spec).isEmpty())
        activity.name = fixture.component.className; activity.packageName = "com.jarvys.agent"; activity.applicationInfo.packageName = "com.jarvys.agent"; assertTrue(targets.discover(spec).isEmpty())
    }
    @Test fun restrictedFilterAndMissingExactTypedHandlerCannotPassVerification() {
        val fixture = FactoryExternalLaunchTestPackages.install(context); val reviewed = targets.discover(spec).single()
        fixture.resolve.filter = FactoryExternalLaunchTestPackages.filter().apply { addDataAuthority("example.invalid", null) }
        assertTrue(targets.discover(spec).isEmpty()); denied { targets.verifiedIntent(reviewed, spec) }
        fixture.resolve.filter = FactoryExternalLaunchTestPackages.filter()
        shadowOf(context.packageManager).setResolveInfosForIntent(FactoryExternalLaunchTestPackages.view(spec).setPackage(fixture.packageName), emptyList())
        denied { targets.verifiedIntent(reviewed, spec) }
    }
    @Test fun recipientDiscoveryIsSortedDeduplicatedAndBounded() {
        val second = FactoryExternalLaunchTestPackages.install(context, "example.zrecipient", 20004)
        val first = FactoryExternalLaunchTestPackages.install(context, "example.arecipient", 20005)
        val pm = shadowOf(context.packageManager)
        pm.setResolveInfosForIntent(FactoryExternalLaunchTestPackages.view(spec, discovery = true), listOf(second.resolve, first.resolve, second.resolve))
        assertEquals(listOf(first.component, second.component), targets.discover(spec).map { it.component })
        pm.setResolveInfosForIntent(FactoryExternalLaunchTestPackages.view(spec, discovery = true), List(65) { first.resolve })
        denied { targets.discover(spec) }
    }
    @Test fun moreThanSixteenDistinctInstalledRecipientsFailClosed() {
        val fixtures = (0..16).map { FactoryExternalLaunchTestPackages.install(context, "example.recipient$it", 21000 + it) }
        shadowOf(context.packageManager).setResolveInfosForIntent(FactoryExternalLaunchTestPackages.view(spec, discovery = true), fixtures.map { it.resolve })
        denied { targets.discover(spec) }
    }
}

/** Dedicated external-launch fixture. Public PM queries resolve synthetic packages only. */
internal object FactoryExternalLaunchTestPackages {
    const val QUERY = "Synthetic park & museum?fixture=external"
    const val NUMBER = "+12025550123"
    val MAPS_QUERY: ExternalLaunchSpec = ExternalLaunchSpec.query(QUERY)
    val MAPS_COORDINATES: ExternalLaunchSpec = ExternalLaunchSpec.coordinates(37.5, -122.25)
    val PHONE: ExternalLaunchSpec = ExternalLaunchSpec.dial(NUMBER)
    val SPECS = listOf(MAPS_QUERY, MAPS_COORDINATES, PHONE)
    val CERT = byteArrayOf(0x30, 0x04, 0x01, 0x02, 0x03, 0x04)
    fun action(spec: ExternalLaunchSpec) = if (spec.capability == "phone") Intent.ACTION_DIAL else Intent.ACTION_VIEW
    fun scheme(spec: ExternalLaunchSpec) = if (spec.capability == "phone") "tel" else "geo"
    fun filter(spec: ExternalLaunchSpec = MAPS_QUERY) = IntentFilter(action(spec)).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme(scheme(spec)) }
    fun view(spec: ExternalLaunchSpec = MAPS_QUERY, discovery: Boolean = false): Intent {
        val uri = if (discovery) { if (spec.capability == "phone") "tel:0" else "geo:0,0" } else spec.uri
        return Intent(action(spec), Uri.parse(uri)).addCategory(Intent.CATEGORY_DEFAULT)
    }
    fun method(spec: ExternalLaunchSpec) = if (spec.capability == "phone") "phone.dial" else "maps.open"
    fun args(spec: ExternalLaunchSpec) = when (spec.kind) {
        ExternalLaunchSpec.Kind.MAPS_QUERY -> org.json.JSONObject().put("query", spec.display).toString()
        ExternalLaunchSpec.Kind.MAPS_COORDINATES -> spec.display.split(",").let { org.json.JSONObject().put("latitude", it[0].toDouble()).put("longitude", it[1].toDouble()).toString() }
        ExternalLaunchSpec.Kind.PHONE_DIAL -> org.json.JSONObject().put("number", spec.display).toString()
        else -> error("Unsupported synthetic external launch spec")
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
    fun install(context: Context, packageName: String = "example.synthetic.external", uid: Int = 20003, spec: ExternalLaunchSpec = MAPS_QUERY): Fixture {
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
