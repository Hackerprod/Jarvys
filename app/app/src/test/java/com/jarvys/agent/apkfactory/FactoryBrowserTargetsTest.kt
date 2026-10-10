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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Actual public-PackageManager verification against synthetic installed browser packages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 32, 35], application = android.app.Application::class)
class FactoryBrowserTargetsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val targets get() = FactoryBrowserTargets(context)
    private fun denied(block: () -> Unit) = assertTrue("Expected recipient rejection", runCatching(block).isFailure)
    @Test fun broadHttpsFilterMustHaveEveryBrowserField() {
        assertTrue(FactoryBrowserTargets.broadHttpsFilter(FactoryBrowserTestPackages.filter()))
        assertFalse(FactoryBrowserTargets.broadHttpsFilter(null))
        for (filter in listOf(IntentFilter(Intent.ACTION_SEND), IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_BROWSABLE); addDataScheme("https") },
            IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https") },
            IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_BROWSABLE); addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("http") })) assertFalse(FactoryBrowserTargets.broadHttpsFilter(filter))
    }
    @Test fun deepLinksMimePathsAuthoritiesAndSchemePartsCannotMasqueradeAsBrowser() {
        for (filter in listOf(FactoryBrowserTestPackages.filter().apply { addDataAuthority("example.invalid", null) },
            FactoryBrowserTestPackages.filter().apply { addDataPath("/only", PatternMatcher.PATTERN_PREFIX) },
            FactoryBrowserTestPackages.filter().apply { addDataSchemeSpecificPart("//example.invalid/", PatternMatcher.PATTERN_PREFIX) },
            FactoryBrowserTestPackages.filter().apply { addDataType("text/html") })) assertFalse(FactoryBrowserTargets.broadHttpsFilter(filter))
    }
    @Test @Config(sdk = [35]) fun uriRelativeFilterGroupsAreNotGeneralBrowserFiltersOnApi35() {
        val filter = FactoryBrowserTestPackages.filter()
        filter.addUriRelativeFilterGroup(UriRelativeFilterGroup(UriRelativeFilterGroup.ACTION_ALLOW).apply {
            addUriRelativeFilter(UriRelativeFilter(UriRelativeFilter.PATH, PatternMatcher.PATTERN_PREFIX, "/restricted"))
        })
        assertFalse(FactoryBrowserTargets.broadHttpsFilter(filter))
    }
    @Test fun exactExplicitLaunchHasReviewedUnchangedUrlAndNoGrantsExtrasOrChooser() {
        val fixture = FactoryBrowserTestPackages.install(context)
        val target = targets.discover().single(); val intent = targets.verifiedIntent(target, FactoryBrowserTestPackages.URL)
        assertEquals(fixture.component, target.component); assertEquals(64, target.certificate.length); assertEquals(7L, target.version); assertEquals(99L, target.updated); assertEquals(fixture.uid, target.uid)
        assertEquals(Intent.ACTION_VIEW, intent.action); assertEquals(FactoryBrowserTestPackages.URL, intent.dataString)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories); assertEquals(fixture.component, intent.component)
        assertEquals(0, intent.flags); assertNull(intent.clipData); assertNull(intent.selector); assertNull(intent.extras)
    }
    @Test fun unsignedAndMultipleSignerRecipientsAreUnavailable() {
        val fixture = FactoryBrowserTestPackages.install(context)
        fixture.signatures(emptyArray()); assertTrue(targets.discover().isEmpty())
        fixture.signatures(arrayOf(Signature(byteArrayOf(1, 2)), Signature(byteArrayOf(3, 4)))); assertTrue(targets.discover().isEmpty())
    }
    @Test fun certificateVersionUpdateTimeUidAndPackageIdentityMismatchRejectReviewedRecipient() {
        val fixture = FactoryBrowserTestPackages.install(context); val reviewed = targets.discover().single()
        fixture.signatures(arrayOf(Signature(byteArrayOf(9, 8, 7)))); denied { targets.verifiedIntent(reviewed, FactoryBrowserTestPackages.URL) }
        fixture.signatures(arrayOf(Signature(FactoryBrowserTestPackages.CERT))); fixture.installed.versionCode = 8
        denied { targets.verifiedIntent(reviewed, FactoryBrowserTestPackages.URL) }; fixture.installed.versionCode = 7
        fixture.installed.lastUpdateTime = 100; denied { targets.verifiedIntent(reviewed, FactoryBrowserTestPackages.URL) }; fixture.installed.lastUpdateTime = 99
        fixture.installed.applicationInfo!!.uid = fixture.uid + 1; denied { targets.verifiedIntent(reviewed, FactoryBrowserTestPackages.URL) }
        fixture.installed.applicationInfo!!.uid = fixture.uid; fixture.installed.packageName = "example.other.browser"
        denied { targets.verifiedIntent(reviewed, FactoryBrowserTestPackages.URL) }
    }
    @Test fun disabledUnexportedPermissionGuardedSuspendedAndWrongPackageRecipientsAreRejected() {
        val fixture = FactoryBrowserTestPackages.install(context); val info = fixture.resolve.activityInfo
        info.enabled = false; assertTrue(targets.discover().isEmpty()); info.enabled = true
        info.exported = false; assertTrue(targets.discover().isEmpty()); info.exported = true
        info.permission = "example.browser.PRIVATE"; assertTrue(targets.discover().isEmpty()); info.permission = null
        info.applicationInfo.enabled = false; assertTrue(targets.discover().isEmpty()); info.applicationInfo.enabled = true
        info.applicationInfo.flags = ApplicationInfo.FLAG_SUSPENDED; assertTrue(targets.discover().isEmpty()); info.applicationInfo.flags = 0
        info.applicationInfo.packageName = "example.wrong.browser"; assertTrue(targets.discover().isEmpty())
    }
    @Test fun declaredSharedUidIsRejectedEvenWhenPackageVisibilityExposesOnlyTheBrowser() {
        val fixture = FactoryBrowserTestPackages.install(context)
        val reviewed = targets.discover().single()
        assertArrayEquals(arrayOf(fixture.packageName), context.packageManager.getPackagesForUid(fixture.uid))
        fixture.installed.sharedUserId = "example.synthetic.shared"
        assertArrayEquals("Visible UID membership alone does not prove absence of shared UID", arrayOf(fixture.packageName), context.packageManager.getPackagesForUid(fixture.uid))
        assertTrue(targets.discover().isEmpty())
        denied { targets.verifiedIntent(reviewed, FactoryBrowserTestPackages.URL) }
    }
    @Test fun sharedUidAndDifferentAndroidProfileAreUnavailable() {
        val fixture = FactoryBrowserTestPackages.install(context); val pm = shadowOf(context.packageManager)
        pm.setPackagesForUid(fixture.uid, fixture.packageName, "example.shared.browser"); assertTrue(targets.discover().isEmpty())
        pm.setPackagesForUid(fixture.uid, fixture.packageName)
        fixture.resolve.activityInfo.applicationInfo.uid = 120003; fixture.installed.applicationInfo!!.uid = 120003
        pm.setPackagesForUid(120003, fixture.packageName); assertTrue(targets.discover().isEmpty())
    }
    @Test fun hostPackageAndUnboundedComponentNamesCannotBeRecipients() {
        val fixture = FactoryBrowserTestPackages.install(context); val activity = fixture.resolve.activityInfo
        activity.name = "example.browser.\nHidden"; assertTrue(targets.discover().isEmpty()); activity.name = "x".repeat(256); assertTrue(targets.discover().isEmpty())
        activity.name = fixture.component.className; activity.packageName = "com.jarvys.agent"; activity.applicationInfo.packageName = "com.jarvys.agent"; assertTrue(targets.discover().isEmpty())
    }
    @Test fun nonBrowserAndMissingExactUrlHandlerCannotPassVerification() {
        val fixture = FactoryBrowserTestPackages.install(context); val reviewed = targets.discover().single()
        fixture.resolve.filter = FactoryBrowserTestPackages.filter().apply { addDataAuthority("example.invalid", null) }
        assertTrue(targets.discover().isEmpty()); denied { targets.verifiedIntent(reviewed, FactoryBrowserTestPackages.URL) }
        fixture.resolve.filter = FactoryBrowserTestPackages.filter()
        shadowOf(context.packageManager).setResolveInfosForIntent(FactoryBrowserTestPackages.view(FactoryBrowserTestPackages.URL).setPackage(fixture.packageName), emptyList())
        denied { targets.verifiedIntent(reviewed, FactoryBrowserTestPackages.URL) }
    }
    @Test fun recipientDiscoveryIsSortedDeduplicatedAndBounded() {
        val second = FactoryBrowserTestPackages.install(context, "example.zbrowser", 20004)
        val first = FactoryBrowserTestPackages.install(context, "example.abrowser", 20005)
        val pm = shadowOf(context.packageManager)
        pm.setResolveInfosForIntent(FactoryBrowserTestPackages.view(FactoryBrowserTestPackages.DISCOVERY), listOf(second.resolve, first.resolve, second.resolve))
        assertEquals(listOf(first.component, second.component), targets.discover().map { it.component })
        pm.setResolveInfosForIntent(FactoryBrowserTestPackages.view(FactoryBrowserTestPackages.DISCOVERY), List(65) { first.resolve })
        denied { targets.discover() }
    }
    @Test fun moreThanSixteenDistinctInstalledBrowsersFailClosed() {
        val fixtures = (0..16).map { FactoryBrowserTestPackages.install(context, "example.browser$it", 21000 + it) }
        shadowOf(context.packageManager).setResolveInfosForIntent(FactoryBrowserTestPackages.view(FactoryBrowserTestPackages.DISCOVERY), fixtures.map { it.resolve })
        denied { targets.discover() }
    }
}

/** Shared host fixture. Installation and resolution are real Robolectric PM operations. */
internal object FactoryBrowserTestPackages {
    const val URL = "https://example.invalid/native/review?fixture=browser"
    const val DISCOVERY = "https://browser-discovery.invalid/"
    val CERT = byteArrayOf(0x30, 0x04, 0x01, 0x02, 0x03, 0x04)
    fun filter() = IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_BROWSABLE); addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https") }
    fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    class Fixture(val context: Context, val packageName: String, val uid: Int, val resolve: ResolveInfo) {
        val component get() = ComponentName(packageName, "$packageName.Browser")
        val installed: PackageInfo get() = shadowOf(context.packageManager).getInternalMutablePackageInfo(packageName)
        fun signatures(values: Array<Signature>) {
            installed.signatures = values
            if (Build.VERSION.SDK_INT >= 28) {
                installed.signingInfo = ReflectionHelpers.newInstance(SigningInfo::class.java).also { shadowOf(it).setSignatures(values) }
            }
        }
    }
    fun install(context: Context, packageName: String = "example.synthetic.browser", uid: Int = 20003): Fixture {
        val app = ApplicationInfo().apply { this.packageName = packageName; this.uid = uid; enabled = true; flags = 0 }
        val activity = ActivityInfo().apply { this.packageName = packageName; name = "$packageName.Browser"; applicationInfo = app; enabled = true; exported = true }
        val info = PackageInfo().apply { this.packageName = packageName; applicationInfo = app; activities = arrayOf(activity); versionCode = 7; lastUpdateTime = 99 }
        val pm = shadowOf(context.packageManager); pm.installPackage(info); pm.setPackagesForUid(uid, packageName)
        val resolve = ResolveInfo().apply { activityInfo = activity; filter = filter(); isDefault = true }
        val fixture = Fixture(context, packageName, uid, resolve); fixture.signatures(arrayOf(Signature(CERT)))
        pm.addResolveInfoForIntentNoDefaults(view(DISCOVERY), resolve)
        pm.addResolveInfoForIntentNoDefaults(view(URL).setPackage(packageName), resolve)
        return fixture
    }
}
