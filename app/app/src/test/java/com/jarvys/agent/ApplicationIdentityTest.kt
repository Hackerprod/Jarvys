package com.jarvys.agent

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.JarvysNotificationListenerService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApplicationIdentityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val isRecoveryTest get() = BuildConfig.APPLICATION_ID == "com.jarvys.agent.recoverytest"

    @Test fun runtimeAndLauncherUseTheSelectedApplicationIdentity() {
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        assertTrue(context.packageName in setOf("com.jarvys.agent", "com.jarvys.agent.recoverytest"))
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        assertEquals(context.packageName, launch.component?.packageName)
        assertEquals(MainActivity::class.java.name, launch.component?.className)
        assertEquals(if (isRecoveryTest) "Jarvys Prueba" else "Jarvys",
            context.applicationInfo.loadLabel(context.packageManager).toString())
    }

    @Test fun generatedImagesHavePackageScopedAuthoritiesAndExplicitComponents() {
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, FileProvider::class.java), PackageManager.GET_META_DATA)
        assertEquals("${context.packageName}.generated-images", provider.authority)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertEquals(context.packageName, provider.packageName)
    }

    @Suppress("DEPRECATION")
    @Test fun dataIsPrivateWithoutSharedUidOrAndroidBackup() {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertNull(info.sharedUserId)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test fun notificationListenerBelongsToThisAppAndTestLabelIsDistinct() {
        val listener = context.packageManager.getServiceInfo(
            ComponentName(context, JarvysNotificationListenerService::class.java), 0)
        assertEquals(context.packageName, listener.packageName)
        assertFalse(listener.exported)
        if (isRecoveryTest) assertEquals("Jarvys Prueba: notificaciones",
            listener.loadLabel(context.packageManager).toString())
    }
}
