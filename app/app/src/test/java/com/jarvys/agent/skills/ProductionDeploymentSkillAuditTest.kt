package com.jarvys.agent.skills

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductionDeploymentSkillAuditTest {
    @Test fun productionDeploymentDoesNotAddRuntimeApprovalBoundary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markdown = context.assets.open("skills/com.jarvys.production-deployment/SKILL.md")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

        assertFalse(markdown.contains("Tool and approval boundary"))
    }
}
