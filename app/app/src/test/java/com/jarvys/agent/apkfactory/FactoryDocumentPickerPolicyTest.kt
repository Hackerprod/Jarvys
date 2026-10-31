package com.jarvys.agent.apkfactory

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic package metadata only; no picker, real provider, grants, or device trust is exercised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 32])
class FactoryDocumentPickerPolicyTest {
    private fun picker(
        packageName: String = "example.system.documents",
        className: String = "example.system.documents.Picker",
        flags: Int = ApplicationInfo.FLAG_SYSTEM,
        exported: Boolean = true,
        activityEnabled: Boolean = true,
        appEnabled: Boolean = true,
    ) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = className
            this.exported = exported
            enabled = activityEnabled
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                this.flags = flags
                enabled = appEnabled
            }
        }
    }
    private fun rejects(vararg candidates: ResolveInfo) {
        assertTrue("Missing, ambiguous, or untrusted picker must fail closed",
            runCatching { FactoryDocumentActivity.selectTrustedPicker(candidates.toList()) }.isFailure)
    }
    private fun provider(packageName: String = "example.cloud.documents", uid: Int = 20002) = ProviderInfo().apply {
        this.packageName = packageName
        authority = "$packageName.provider"
        applicationInfo = ApplicationInfo().apply { this.packageName = packageName; this.uid = uid }
    }
    private fun allowed(value: ProviderInfo?) = FactoryDocumentActivity.allowedProvider("com.jarvys.agent", 10001, value)

    @Test fun missingAndOrdinaryThirdPartyHandlersFailClosed() {
        rejects()
        rejects(picker(flags = 0))
        rejects(ResolveInfo())
    }
    @Test fun uniqueEnabledExportedSystemPickerIsSelected() {
        assertEquals(ComponentName("example.system.documents", "example.system.documents.Picker"),
            FactoryDocumentActivity.selectTrustedPicker(listOf(picker())))
    }
    @Test fun updatedSystemPickerRemainsEligible() {
        assertEquals("example.system.documents",
            FactoryDocumentActivity.selectTrustedPicker(listOf(picker(flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))).packageName)
    }
    @Test fun untrustedAlternativeCannotReplaceSystemPicker() {
        val malicious = picker("example.untrusted", "example.untrusted.Picker", flags = 0)
        assertEquals("example.system.documents",
            FactoryDocumentActivity.selectTrustedPicker(listOf(malicious, picker())).packageName)
    }
    @Test fun twoDifferentTrustedComponentsAreAmbiguous() {
        rejects(picker(), picker("example.oem.documents", "example.oem.documents.Picker"))
        rejects(picker(), picker(className = "example.system.documents.OtherPicker"))
    }
    @Test fun duplicateMetadataForSameComponentDoesNotInventAmbiguity() {
        assertEquals("example.system.documents.Picker",
            FactoryDocumentActivity.selectTrustedPicker(listOf(picker(), picker())).className)
    }
    @Test fun disabledOrPrivateSystemHandlersCannotBeSelected() {
        rejects(picker(exported = false))
        rejects(picker(activityEnabled = false))
        rejects(picker(appEnabled = false))
    }
    @Test fun missingApplicationIdentityFailsClosed() {
        val value = picker()
        android.content.pm.ComponentInfo::class.java.getField("applicationInfo").set(value.activityInfo, null)
        rejects(value)
    }
    @Test fun missingProviderAndHostOwnedProvidersAreRejected() {
        assertFalse(allowed(null))
        assertFalse(allowed(provider("com.jarvys.agent")))
        assertFalse(allowed(provider("com.jarvys.agent.recoverytest")))
        assertFalse(allowed(provider(uid = 10001)))
        assertFalse(FactoryDocumentActivity.allowedProvider("example.custom.host", 10001, provider("example.custom.host")))
    }
    @Test fun providerWithoutApplicationIdentityIsRejected() {
        val value = provider()
        android.content.pm.ComponentInfo::class.java.getField("applicationInfo").set(value, null)
        assertFalse(allowed(value))
    }
    @Test fun independentlyOwnedCloudProviderRemainsEligible() {
        assertTrue(allowed(provider()))
        assertTrue(allowed(provider("example.local.documents", 20003)))
    }
    @Test fun plainContentAndTreeUrisAreNotAcceptedAsDocuments() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (uri in listOf("file:///data/private", "content://example.unknown/private", "content://example.unknown/tree/root")) {
            assertFalse(FactoryDocumentActivity.allowedDocument(context, Uri.parse(uri)))
        }
    }
    @Test fun crossUserAndMissingGrantResultsFailBeforeProviderInspection() {
        val crossUser = Intent().setData(Uri.parse("content://10@example.cloud.documents/document/one"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse(FactoryDocumentActivity.validResult(crossUser, Intent.FLAG_GRANT_READ_URI_PERMISSION))
        val missingGrant = Intent().setData(Uri.parse("content://example.cloud.documents/document/one"))
        assertFalse(FactoryDocumentActivity.validResult(missingGrant, Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
