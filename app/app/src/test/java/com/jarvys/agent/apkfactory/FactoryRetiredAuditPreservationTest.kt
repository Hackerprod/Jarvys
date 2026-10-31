package com.jarvys.agent.apkfactory

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FactoryRetiredAuditPreservationTest {
    @Test fun policyCleanupNeverParsesOrRewritesLegacyAudit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("jarvys_connectors", Context.MODE_PRIVATE)
        val original = "[\"synthetic invalid audit row\"]"
        preferences.edit().putString("autonomy_audit", original)
            .putString("autonomy_native_install", "ALLOW").commit()
        try {
            // Attach only; do not start unrelated application coordinators for this cleanup test.
            val application = FactoryInstallApplication()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(application, context)
            FactoryInstallApplication::class.java.getDeclaredMethod("clearRetiredOperationPolicyState")
                .apply { isAccessible = true }.invoke(application)
            assertEquals(original, preferences.getString("autonomy_audit", null))
            assertFalse(preferences.contains("autonomy_native_install"))
        } finally {
            preferences.edit().remove("autonomy_audit").remove("autonomy_native_install").commit()
        }
    }
}
