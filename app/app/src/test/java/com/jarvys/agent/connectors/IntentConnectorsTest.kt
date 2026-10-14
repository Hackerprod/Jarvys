package com.jarvys.agent.connectors

import android.Manifest
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

    @Test fun phoneOpsUseStrictNumbersAndSavedAskCanDenyDialerLaunch() {
        val definition = DialerConnector.definition(FakePhoneGateway(), { false }, { false })
        val schema = DialerConnector.dialerSchema()
        assertEquals("string", schema.getJSONObject("properties").getJSONObject("phoneNumber").getString("type"))
        assertTrue(definition.readPermissions.isEmpty())
        assertTrue(definition.writePermissions.contains(Manifest.permission.CALL_PHONE))
        assertEquals(listOf(DialerConnector.OPEN_DIALER, DialerConnector.PLACE_CALL), definition.operations.map { it.name })
        assertTrue(definition.operations.first().autonomyAllowed)
        val summaries = mutableListOf<ApprovalSummary>()
        val registry = ConnectorRegistry.createForTests(FakeConnectorPreferences(), { false }, rejectingApprovalGate(summaries::add))
        registry.register(definition)
        registry.connect(DialerConnector.ID)
        val operation = definition.operations.first()
        registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.ASK)
        val rejected = runCatching {
            registry.invoke(definition, operation, JSONObject().put("phoneNumber", "+15551234567"), token)
        }.exceptionOrNull()
        assertTrue(rejected?.message.orEmpty().contains("do not retry automatically"))
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
        assertEquals("dialer_launch_requested", fallback.getString("status"))
        assertEquals("+15551234567", fallback.getString("phoneNumber"))
        assertEquals(false, fallback.getBoolean("callPlaced"))
        assertTrue(fallback.getString("message").contains("presentation is not confirmed"))
        assertTrue(fallback.getString("message").contains("press Call"))
        assertEquals(1, gateway.placed)
        assertFalse(fallback.has("callStarted"))
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

    @Test fun deniedCallPhonePermissionRequestsExactlyOneDialerLaunchFromTheRegistry() {
        val gateway = FakePhoneGateway()
        val definition = DialerConnector.definition(gateway, { false }, { false })
        var summary: ApprovalSummary? = null
        val launched = mutableListOf<ApprovalIntentSpec>()
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(1_000, object : ApprovalPresenter {
            override fun show(id: String, value: ApprovalSummary) {
                summary = value
                gate.resolve(id, ApprovalDecision.PERMISSION_DENIED)
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        val registry = ConnectorRegistry.createForTestsWithAutonomy(
            FakeConnectorPreferences(), { false }, gate, InMemoryConnectorAutonomyStore(),
            NoopAutonomyActionNotifier, launchIntent = { launched += it },
        )
        registry.register(definition)
        registry.connect(DialerConnector.ID)
        registry.setAutonomyPolicy(definition, definition.operations.last(), AutonomyPolicy.ASK)
        val result = registry.invoke(definition, definition.operations.last(),
            JSONObject().put("phoneNumber", "+15551234567"), token)
        assertEquals(Manifest.permission.CALL_PHONE, summary?.permission)
        assertEquals(ApprovalIntentKind.DIAL, summary?.permissionDeniedIntent?.kind)
        assertEquals("dialer_launch_requested", result.getString("status"))
        assertFalse(result.getBoolean("callPlaced"))
        assertEquals(0, gateway.placed)
        assertEquals(0, gateway.opened)
        assertEquals(listOf(summary?.permissionDeniedIntent), launched)
        assertEquals("tel:+15551234567", launched.single().dataUri)
        assertTrue(result.getString("message").contains("presentation is not confirmed"))
    }

    @Test fun sharedEmailDraftPolicyBuildsMailtoAndValidatesRecipients() {
        val parsed = EmailIntentPolicy.parse("a@example.com", "Hello world", "Line one\nLine two",
            "b@example.org", "c@example.net")
        val mailto = EmailIntentPolicy.mailto(parsed)
        assertTrue(mailto.startsWith("mailto:a@example.com?"))
        assertTrue(mailto.contains("cc=b%40example.org"))
        assertTrue(mailto.contains("bcc=c%40example.net"))
        assertTrue(mailto.contains("subject=Hello%20world"))
        assertTrue(mailto.contains("body=Line%20one%0ALine%20two"))
        assertTrue(runCatching { EmailIntentPolicy.parse("not-an-address", "", "x") }.isFailure)
        assertTrue(runCatching { EmailIntentPolicy.parse("a@example.com\r\nBcc:x@y.com", "", "x") }.isFailure)
    }

    @Test fun defaultAllowDialerLaunchIsDispatchedExactlyOnceWithoutPlacingACall() {
        val gateway = FakePhoneGateway()
        val definition = DialerConnector.definition(gateway, { false }, { false })
        val operation = definition.operations.first { it.name == DialerConnector.OPEN_DIALER }
        val launched = mutableListOf<ApprovalIntentSpec>()
        val registry = ConnectorRegistry.createForTestsWithAutonomy(
            FakeConnectorPreferences(), { false }, rejectingApprovalGate { error("Default ALLOW must not ask") },
            InMemoryConnectorAutonomyStore(), NoopAutonomyActionNotifier, launchIntent = { launched += it },
        )
        registry.register(definition)
        registry.connect(definition.id)
        assertEquals(AutonomyPolicy.ALLOW, registry.autonomyPolicy(definition, operation))
        val result = registry.invoke(definition, operation, JSONObject().put("phoneNumber", "+15551234567"), token)
        assertEquals(listOf(ApprovalIntentSpec(ApprovalIntentKind.DIAL, "tel:+15551234567")), launched)
        assertEquals(0, gateway.placed)
        assertEquals(0, gateway.opened)
        assertEquals("dialer_launch_requested", result.getString("status"))
        assertFalse(result.getBoolean("callPlaced"))
        assertTrue(result.getString("message").contains("presentation is not confirmed"))
    }

    @Test fun legacyUiLaunchReceiptCannotCauseASecondDispatch() {
        val gateway = FakePhoneGateway()
        val definition = DialerConnector.definition(gateway, { false }, { false })
        val operation = definition.operations.first { it.name == DialerConnector.PLACE_CALL }
        val launched = mutableListOf<ApprovalIntentSpec>()
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(1_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                gate.resolve(id, ApprovalDecision.PERMISSION_FALLBACK_LAUNCHED)
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        val registry = ConnectorRegistry.createForTestsWithAutonomy(
            FakeConnectorPreferences(), { false }, gate, InMemoryConnectorAutonomyStore(),
            NoopAutonomyActionNotifier, launchIntent = { launched += it },
        )
        registry.register(definition)
        registry.connect(definition.id)
        registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.ASK)
        val failure = runCatching {
            registry.invoke(definition, operation, JSONObject().put("phoneNumber", "+15551234567"), token)
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("Legacy UI effect receipt"))
        assertTrue(launched.isEmpty())
        assertEquals(0, gateway.placed)
        assertEquals(0, gateway.opened)
    }

}
