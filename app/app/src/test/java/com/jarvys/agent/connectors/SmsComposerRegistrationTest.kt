package com.jarvys.agent.connectors

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsComposerRegistrationTest {
    @Test fun bothFlavorsRegisterSendSmsForNormalConnectedToolsWithoutSendSmsPermission() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sms = ConnectorRegistry.deviceDefinitions(context).single { it.id == SmsComposerConnector.ID }
        val operation = sms.operations.single()
        assertEquals("send_sms", operation.name)
        assertEquals("send_sms", com.jarvys.agent.CoreConnectorTool.toolName(SmsComposerConnector.ID, operation.name))
        assertTrue(operation.write)
        assertFalse(operation.autonomyAllowed)
        assertTrue(sms.writePermissions.isEmpty())
        assertTrue(operation.requiredPermissions.isEmpty())
        assertFalse(com.jarvys.agent.proactive.ProactiveReadOnlyToolFactory.connectorAllowlist[SmsComposerConnector.ID]
            .orEmpty().contains("send_sms"))
    }
}
