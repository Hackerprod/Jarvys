package com.jarvys.agent.connectors

import android.Manifest
import android.content.Intent
import android.net.Uri
import com.jarvys.agent.approvalIntent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntentConnectorsTest {
    private val token = com.jarvys.agent.CancellationToken.uncancellable()

    private class FakePhoneGateway : PhoneCallGateway {
        var placed = 0
        var opened = 0
        var placeFailure: RuntimeException? = null
        override fun placeCall(phoneNumber: String) {
            placeFailure?.let { throw it }
            placed++
        }
        override fun openDialer(phoneNumber: String) { opened++ }
    }

    @Test fun phoneOpsUseStrictNumbersAndKeepDialerInteractionUnallowable() {
        val definition = DialerConnector.definition(FakePhoneGateway(), { false }, { false })
        val schema = DialerConnector.dialerSchema()
        assertEquals("string", schema.getJSONObject("properties").getJSONObject("phoneNumber").getString("type"))
        assertTrue(definition.readPermissions.isEmpty())
        assertTrue(definition.writePermissions.contains(Manifest.permission.CALL_PHONE))
        assertEquals(listOf(DialerConnector.OPEN_DIALER, DialerConnector.PLACE_CALL), definition.operations.map { it.name })
        assertFalse(definition.operations.first().autonomyAllowed)
        val summaries = mutableListOf<ApprovalSummary>()
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, rejectingApprovalGate(summaries::add))
        registry.register(definition)
        registry.connect(DialerConnector.ID)
        val operation = definition.operations.first()
        val rejected = runCatching {
            registry.invoke(definition, operation, JSONObject().put("phoneNumber", "+15551234567"), token)
        }.exceptionOrNull()
        assertTrue(rejected?.message.orEmpty().contains("no la reintentes"))
        assertEquals(ApprovalIntentKind.DIAL, summaries.single().activityIntent?.kind)
        assertEquals("tel:+15551234567", summaries.single().activityIntent?.dataUri)
        assertTrue(runCatching { DialerConnector.normalizePhone("12+3") }.isFailure)
        assertTrue(runCatching { DialerConnector.normalizePhone("*123") }.isFailure)
        assertTrue(runCatching { DialerConnector.normalizePhone("#31#") }.isFailure)
        assertTrue(runCatching { DialerConnector.normalizePhone("911") }.isFailure)
    }

    @Test fun placeCallUsesTelecomAndFallsBackToDialerWithoutClaimingTheCallWasPlaced() {
        val gateway = FakePhoneGateway()
        val connector = DialerConnector(gateway, { true }, { it == "911" })
        val args = JSONObject().put("phoneNumber", "+15551234567")
        val operation = DialerConnector.PLACE_CALL
        val preparation = connector.prepareWrite(operation, args, token)
        val telecomResult = connector.invokePrepared(operation, args, preparation, token)
        assertEquals(1, gateway.placed)
        assertEquals("call_requested", telecomResult.getString("status"))
        assertEquals("+15551234567", telecomResult.getString("phoneNumber"))
        assertEquals("Call request accepted by Android Telecom.", telecomResult.getString("message"))
        assertFalse(telecomResult.has("callStarted"))

        gateway.placeFailure = SecurityException("Telecom rejected request")
        val fallback = connector.invokePrepared(operation, args, connector.prepareWrite(operation, args, token), token)
        assertEquals(1, gateway.opened)
        assertEquals("dialer_opened", fallback.getString("status"))
        assertEquals("+15551234567", fallback.getString("phoneNumber"))
        assertEquals(false, fallback.getBoolean("callPlaced"))
        assertTrue(fallback.getString("message").contains("press Call"))
    }

    @Test fun successfulCallGuidanceAvoidsRepeatingConnectionCaveatWithoutClaimingConnection() {
        val definition = DialerConnector.definition(FakePhoneGateway(), { true }, { false })
        val note = definition.usageNote()

        assertTrue(note.contains("one short sentence"))
        assertTrue(note.contains("Never claim the call connected or was answered"))
        assertTrue(note.contains("unless the user asks"))
        assertTrue(note.contains("press Call"))
        assertFalse(note.contains("cannot confirm that the call connected"))
        assertFalse(note.contains("such as"))
        val description = definition.operations.first { it.name == DialerConnector.PLACE_CALL }.description
        assertTrue(description.contains("press Call"))
        assertFalse(description.contains("such as"))
    }

    @Test fun deniedCallPhonePermissionLaunchesDialerFallbackFromTheApprovalCard() {
        val gateway = FakePhoneGateway()
        val definition = DialerConnector.definition(gateway, { false }, { false })
        var summary: ApprovalSummary? = null
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(1_000, object : ApprovalPresenter {
            override fun show(id: String, value: ApprovalSummary) {
                summary = value
                gate.resolve(id, ApprovalDecision.PERMISSION_FALLBACK_LAUNCHED)
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, gate)
        registry.register(definition)
        registry.connect(DialerConnector.ID)
        val result = registry.invoke(definition, definition.operations.last(),
            JSONObject().put("phoneNumber", "+15551234567"), token)
        assertEquals(Manifest.permission.CALL_PHONE, summary?.permission)
        assertEquals(ApprovalIntentKind.DIAL, summary?.permissionDeniedIntent?.kind)
        assertEquals("dialer_opened", result.getString("status"))
        assertFalse(result.getBoolean("callPlaced"))
        assertEquals(0, gateway.placed)
    }

    @Test fun smsSchemaAndIntentOnlyPrefillDraftAfterApproval() {
        val definition = SmsComposerConnector.definition()
        val schema = SmsComposerConnector.smsSchema()
        assertEquals("string", schema.getJSONObject("properties").getJSONObject("to").getString("type"))
        assertEquals(SmsComposerConnector.MAX_BODY_CHARS,
            schema.getJSONObject("properties").getJSONObject("body").getInt("maxLength"))
        assertTrue(definition.description.contains("does not read SMS", ignoreCase = true))
        assertTrue(definition.readPermissions.isEmpty())
        assertFalse(definition.writePermissions.contains(Manifest.permission.SEND_SMS))
        assertEquals("send_sms", definition.operations.single().name)
        assertFalse(definition.operations.single().autonomyAllowed)
        assertFalse(com.jarvys.agent.proactive.ProactiveReadOnlyToolFactory.connectorAllowlist.values
            .flatten().contains("send_sms"))

        val summaries = mutableListOf<ApprovalSummary>()
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, rejectingApprovalGate(summaries::add))
        registry.register(definition)
        registry.connect(SmsComposerConnector.ID)
        val operation = definition.operations.single()
        val body = "Please call me after lunch. " + "Review the details carefully. ".repeat(60)
        val rejected = runCatching {
            registry.invoke(definition, operation, JSONObject().put("to", "+15551234567").put("body", body), token)
        }.exceptionOrNull()
        assertTrue(rejected?.message.orEmpty().contains("no la reintentes"))
        val intent = summaries.single().activityIntent
        assertEquals(ApprovalIntentKind.SMS_COMPOSE, intent?.kind)
        assertEquals("smsto:+15551234567", intent?.dataUri)
        assertEquals(body, intent?.extras?.get("sms_body"))
        assertEquals("Message: $body", summaries.single().lines.last())
        val externalIntent = approvalIntent(requireNotNull(intent))
        assertEquals(Intent.ACTION_SENDTO, externalIntent.action)
        assertEquals(Uri.parse("smsto:+15551234567"), externalIntent.data)
        assertEquals(body, externalIntent.getStringExtra("sms_body"))
        val prepared = SmsComposerConnector().prepareWrite(SmsComposerConnector.COMPOSE,
            JSONObject().put("to", "+15551234567").put("body", body), token)
        val result = SmsComposerConnector().invokePrepared(SmsComposerConnector.COMPOSE,
            JSONObject(), prepared, token)
        assertFalse(result.getBoolean("sent"))
        assertEquals("sms_draft_opened", result.getString("status"))
        assertTrue(runCatching {
            SmsComposerConnector().prepareWrite(SmsComposerConnector.COMPOSE,
                JSONObject().put("to", "555123").put("body", ""), token)
        }.isFailure)
    }

    @Test fun emailDraftBuildsMailtoIntentAndCanNeverUsePersistentAllow() {
        val definition = EmailIntentConnector.definition()
        val op = definition.operations.single()
        assertEquals(EmailIntentConnector.COMPOSE, op.name)
        assertTrue(op.write)
        assertFalse(op.autonomyAllowed)
        val parsed = EmailIntentPolicy.parse("a@example.com", "Hello world", "Line one\nLine two",
            "b@example.org", "c@example.net")
        val mailto = EmailIntentPolicy.mailto(parsed)
        assertTrue(mailto.startsWith("mailto:a@example.com?"))
        assertTrue(mailto.contains("cc=b%40example.org"))
        assertTrue(mailto.contains("bcc=c%40example.net"))
        assertTrue(mailto.contains("subject=Hello%20world"))
        assertTrue(mailto.contains("body=Line%20one%0ALine%20two"))

        val summaries = mutableListOf<ApprovalSummary>()
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, rejectingApprovalGate(summaries::add))
        registry.register(definition)
        registry.connect(EmailIntentConnector.ID)
        val failure = runCatching {
            registry.invoke(definition, op, JSONObject().put("to", "a@example.com")
                .put("subject", "Sensitive subject").put("body", "A body"), token)
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("no la reintentes"))
        assertEquals(ApprovalIntentKind.EMAIL_COMPOSE, summaries.single().activityIntent?.kind)
        assertEquals(false, summaries.single().allowAlwaysAvailable)
        assertTrue(summaries.single().lines.any { it.contains("Sensitive subject") })
        assertTrue(runCatching { EmailIntentPolicy.parse("not-an-address", "", "x") }.isFailure)
        assertTrue(runCatching { EmailIntentPolicy.parse("a@example.com\r\nBcc:x@y.com", "", "x") }.isFailure)
    }
}
