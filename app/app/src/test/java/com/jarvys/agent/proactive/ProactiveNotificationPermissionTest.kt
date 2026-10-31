package com.jarvys.agent.proactive

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProactiveNotificationPermissionTest {
    @Test fun runtimePermissionIsRequestedOnlyWhenSwitchIsTurnedOnAndNotAlreadyGranted() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(ProactiveNotificationPermission.shouldRequestOnOptIn(context, false))
        assertTrue(ProactiveNotificationPermission.shouldRequestOnOptIn(context, true))
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(ProactiveNotificationPermission.shouldRequestOnOptIn(context, true))
    }

    @Test @Config(sdk = [32]) fun olderAndroidDoesNotRequestRuntimeNotificationPermission() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(ProactiveNotificationPermission.shouldRequestOnOptIn(context, true))
    }
}
