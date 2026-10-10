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

/** Synthetic installed packages only; no external application is dispatched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 32, 35], application = android.app.Application::class, shadows = [FactoryExternalLaunchPackageManagerShadow::class])
class FactoryMessageEditorTargetsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val targets get() = FactoryExternalLaunchTargets(context)
    private fun denied(block: () -> Unit) = assertTrue("Expected recipient rejection", runCatching(block).isFailure)
    @Test fun emailAndSmsUseExactExplicitSendToWithOnlyFixedTextExtras() {
        for ((index, spec) in FactoryMessageEditorTestPackages.SPECS.withIndex()) {
            val fixture = FactoryMessageEditorTestPackages.install(context, "example.editor$index", 24000 + index, spec)
            val target = targets.discover(spec).single { it.component == fixture.component }
            val exact = targets.verifiedIntent(target, spec)
            assertEquals(Intent.ACTION_SENDTO, exact.action); assertEquals(spec.uri, exact.dataString)
            assertEquals(fixture.component, exact.component); assertEquals(setOf(Intent.CATEGORY_DEFAULT), exact.categories)
            assertEquals(0, exact.flags); assertNull(exact.clipData); assertNull(exact.selector); assertNull(exact.`package`)
            if (spec.capability == "email") {
                assertEquals("mailto:fixture%2Breview@example.invalid", exact.dataString)
                assertEquals(setOf(Intent.EXTRA_SUBJECT, Intent.EXTRA_TEXT), exact.extras!!.keySet())
                assertEquals(spec.subject, exact.getStringExtra(Intent.EXTRA_SUBJECT)); assertEquals(spec.body, exact.getStringExtra(Intent.EXTRA_TEXT))
            } else {
                assertEquals("smsto:+12025550123", exact.dataString)
                assertEquals(setOf("sms_body"), exact.extras!!.keySet()); assertEquals(spec.body, exact.getStringExtra("sms_body"))
            }
            assertNull(exact.getStringArrayExtra(Intent.EXTRA_EMAIL)); assertNull(exact.getStringArrayExtra(Intent.EXTRA_CC)); assertNull(exact.getStringArrayExtra(Intent.EXTRA_BCC))
        }
    }
    @Test fun discoveryUsesOnlyFixedNonSensitiveFixtureAndNoExtras() {
        val queries = mutableListOf<Intent>()
        FactoryExternalLaunchPackageManagerShadow.beforeQuery = { queries += Intent(it) }
        try {
            for ((index, spec) in FactoryMessageEditorTestPackages.SPECS.withIndex()) {
                FactoryMessageEditorTestPackages.install(context, "example.discovery$index", 24200 + index, spec)
                queries.clear(); assertEquals(1, targets.discover(spec).size)
                val discovery = queries.single()
                assertEquals(Intent.ACTION_SENDTO, discovery.action)
                assertEquals(if (spec.capability == "email") "mailto:fixture@example.invalid" else "smsto:0", discovery.dataString)
                assertNull(discovery.extras); assertFalse(discovery.dataString!!.contains(spec.recipient))
                assertFalse(discovery.toString().contains(spec.body))
                assertTrue(FactoryExternalLaunchTargets.broadTypedFilter(FactoryMessageEditorTestPackages.filter(spec), spec))
            }
        } finally { FactoryExternalLaunchPackageManagerShadow.beforeQuery = null }
    }
    @Test fun emailAndSmsRecipientCannotBeReusedAcrossSchemes() {
        val email = FactoryMessageEditorTestPackages.install(context, "example.email", 24001, FactoryMessageEditorTestPackages.EMAIL)
        val sms = FactoryMessageEditorTestPackages.install(context, "example.sms", 24002, FactoryMessageEditorTestPackages.SMS)
        val e = targets.discover(FactoryMessageEditorTestPackages.EMAIL).single { it.component == email.component }
        val s = targets.discover(FactoryMessageEditorTestPackages.SMS).single { it.component == sms.component }
        denied { targets.verifiedIntent(e, FactoryMessageEditorTestPackages.SMS) }
        denied { targets.verifiedIntent(s, FactoryMessageEditorTestPackages.EMAIL) }
    }
    @Test fun genericSendWrongSchemeAndRestrictedFiltersNeverQualify() {
        for (spec in FactoryMessageEditorTestPackages.SPECS) {
            for (filter in listOf(IntentFilter(Intent.ACTION_SEND).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme(FactoryMessageEditorTestPackages.scheme(spec)) },
                IntentFilter(Intent.ACTION_SENDTO).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("tel") },
                FactoryMessageEditorTestPackages.filter(spec).apply { addDataSchemeSpecificPart("fixture", PatternMatcher.PATTERN_PREFIX) },
                FactoryMessageEditorTestPackages.filter(spec).apply { addDataAuthority("example.invalid", null) },
                FactoryMessageEditorTestPackages.filter(spec).apply { addDataType("text/plain") })) {
                assertFalse(FactoryExternalLaunchTargets.broadTypedFilter(filter, spec))
            }
        }
    }
    @Test fun missingExactHandlerOrChangedSnapshotCannotLaunchReviewedRecipient() {
        for ((index, spec) in FactoryMessageEditorTestPackages.SPECS.withIndex()) {
            val fixture = FactoryMessageEditorTestPackages.install(context, "example.changed$index", 24100 + index, spec)
            val reviewed = targets.discover(spec).single { it.component == fixture.component }
            fixture.signatures(arrayOf(Signature(byteArrayOf(9,8,7)))); denied { targets.verifiedIntent(reviewed, spec) }
            fixture.signatures(arrayOf(Signature(FactoryMessageEditorTestPackages.CERT)))
            fixture.installed.versionCode = 8; denied { targets.verifiedIntent(reviewed, spec) }; fixture.installed.versionCode = 7
            fixture.installed.lastUpdateTime = 100; denied { targets.verifiedIntent(reviewed, spec) }; fixture.installed.lastUpdateTime = 99
            fixture.installed.sharedUserId = "example.shared"; denied { targets.verifiedIntent(reviewed, spec) }; fixture.installed.sharedUserId = null
            shadowOf(context.packageManager).setResolveInfosForIntent(FactoryMessageEditorTestPackages.view(spec).setPackage(fixture.packageName), emptyList())
            denied { targets.verifiedIntent(reviewed, spec) }
        }
    }
}

internal object FactoryMessageEditorTestPackages {
    const val TO = "fixture+review@example.invalid"
    const val SUBJECT = "Synthetic subject &? #intent"
    const val BODY = "Synthetic first line\nSecond line &bcc=other@example.invalid"
    const val NUMBER = "+12025550123"
    val EMAIL: ExternalLaunchSpec = ExternalLaunchSpec.email(TO, SUBJECT, BODY)
    val SMS: ExternalLaunchSpec = ExternalLaunchSpec.sms(NUMBER, BODY)
    val SPECS = listOf(EMAIL, SMS)
    val CERT = byteArrayOf(0x30, 0x04, 0x01, 0x02, 0x03, 0x04)
    fun action(spec: ExternalLaunchSpec) = Intent.ACTION_SENDTO
    fun scheme(spec: ExternalLaunchSpec) = if (spec.capability == "email") "mailto" else "smsto"
    fun filter(spec: ExternalLaunchSpec = EMAIL) = IntentFilter(action(spec)).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme(scheme(spec)) }
    fun view(spec: ExternalLaunchSpec = EMAIL, discovery: Boolean = false): Intent {
        val uri = if (discovery) { if (spec.capability == "email") "mailto:fixture@example.invalid" else "smsto:0" } else spec.uri
        return Intent(action(spec), Uri.parse(uri)).addCategory(Intent.CATEGORY_DEFAULT).apply {
            if (!discovery) {
                if (spec.capability == "email") { putExtra(Intent.EXTRA_SUBJECT, spec.subject); putExtra(Intent.EXTRA_TEXT, spec.body) }
                else putExtra("sms_body", spec.body)
            }
        }
    }
    fun method(spec: ExternalLaunchSpec) = "${spec.capability}.compose"
    fun args(spec: ExternalLaunchSpec) = org.json.JSONObject().apply {
        if (spec.capability == "email") { put("to", spec.recipient); put("subject", spec.subject) }
        else put("number", spec.recipient)
        put("body", spec.body)
    }.toString()
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
    fun install(context: Context, packageName: String = "example.synthetic.external", uid: Int = 20003, spec: ExternalLaunchSpec = EMAIL): Fixture {
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
