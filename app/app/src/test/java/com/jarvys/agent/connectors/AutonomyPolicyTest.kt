package com.jarvys.agent.connectors

import com.jarvys.agent.CancellationToken
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AutonomyPolicyTest {
    private class Preferences : ConnectorConnectionPreferences {
        private val connected = mutableMapOf<String, Boolean>()
        override fun isConnected(id: String) = connected[id] == true
        override fun setConnected(id: String, connected: Boolean) { this.connected[id] = connected }
    }

    private class Notifier(var enabled: Boolean = true) : AutonomyActionNotifier {
        val posted = mutableListOf<AutonomyAuditRecord>()
        override fun canPost() = enabled
        override fun missingRuntimePermission(): String? = if (enabled) null else "POST_NOTIFICATIONS"
        override fun post(record: AutonomyAuditRecord) { posted.add(record) }
    }

    private class WriteRuntime : ConnectorRuntime {
        var executions = 0
        override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
        override fun disconnect() = Unit
        override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken) =
            ConnectorWritePreparation(ApprovalSummary("Write", listOf("sensitive input")), arguments)
        override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken) = JSONObject()
        override fun invokePrepared(operation: String, arguments: JSONObject,
                                    preparation: ConnectorWritePreparation, token: CancellationToken): JSONObject {
            executions++
            return JSONObject().put("ok", true)
        }
    }

    @Test fun askIsDefaultAllowSkipsCardAndAuditsDenyBlocks() {
        val store = InMemoryConnectorAutonomyStore()
        val notifier = Notifier()
        val cards = AtomicInteger()
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(1_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                cards.incrementAndGet()
                gate.resolve(id, ApprovalDecision.APPROVED)
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        val runtime = WriteRuntime()
        val operation = ConnectorOperation("write", "Write", JSONObject(), write = true, displayLabel = "Write Something")
        val definition = ConnectorDefinition("sample", "Sample", "1", "Sample",
            operations = listOf(operation), runtime = runtime, readPermissions = listOf("READ_SAMPLE"))
        val registry = ConnectorRegistry.createForTestsWithAutonomy(
            Preferences(), { it == "READ_SAMPLE" }, gate, store, notifier, { 1_000L },
        )
        registry.register(definition)
        registry.connect("sample")

        assertEquals(AutonomyPolicy.ASK, registry.autonomyPolicy(definition, operation))
        registry.invoke(definition, operation, JSONObject(), CancellationToken.uncancellable())
        assertEquals(1, cards.get())
        assertEquals(1, runtime.executions)
        assertTrue(store.recentAudit("sample", 50).isEmpty())

        registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.ALLOW)
        registry.invoke(definition, operation, JSONObject(), CancellationToken.uncancellable())
        assertEquals(1, cards.get())
        assertEquals(2, runtime.executions)
        assertEquals(1, store.recentAudit("sample", 50).size)
        assertEquals("Completed automatically", store.recentAudit("sample", 50).single().summary)
        assertEquals(1, notifier.posted.size)

        registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.DENY)
        assertTrue(runCatching { registry.invoke(definition, operation, JSONObject(), CancellationToken.uncancellable()) }.isFailure)
        assertEquals(2, runtime.executions)
        assertEquals(1, cards.get())
    }

    @Test fun allowModeRunsOneHundredWritesWithoutExecutionOrHourlyThrottle() {
        val store = InMemoryConnectorAutonomyStore()
        val notifier = Notifier()
        val cards = AtomicInteger()
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(1_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                cards.incrementAndGet()
                gate.resolve(id, ApprovalDecision.APPROVED)
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        val runtime = WriteRuntime()
        val operation = ConnectorOperation("write", "Write", JSONObject(), write = true)
        val definition = ConnectorDefinition("sample", "Sample", "1", "Sample",
            operations = listOf(operation), runtime = runtime, readPermissions = listOf("READ_SAMPLE"))
        val registry = ConnectorRegistry.createForTestsWithAutonomy(
            Preferences(), { true }, gate, store, notifier, { 2_000L },
        )
        registry.register(definition)
        registry.connect("sample")
        registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.ALLOW)

        val token = CancellationToken.uncancellable()
        repeat(100) {
            registry.invoke(definition, operation, JSONObject(), token)
        }

        assertEquals(0, cards.get())
        assertEquals(100, runtime.executions)
        assertEquals(100, store.recentAudit("sample", 100).size)
    }

    @Test fun explicitApproveAndAlwaysAllowContinuesAutomaticallyWithoutCounterFallback() {
        val store = InMemoryConnectorAutonomyStore()
        val notifier = Notifier()
        val cards = AtomicInteger()
        val statuses = mutableListOf<ApprovalDecision>()
        lateinit var registry: ConnectorRegistry
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(1_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                cards.incrementAndGet()
                assertTrue(summary.allowAlwaysAvailable)
                assertEquals("sample", summary.autonomyConnectorId)
                assertEquals("write", summary.autonomyOperationName)
                approveAndAllowAlways(
                    registry, summary.autonomyConnectorId, summary.autonomyOperationName,
                    onStatusDetail = {}, onResolve = { gate.resolve(id, it) },
                )
            }
            override fun update(id: String, decision: ApprovalDecision) { statuses.add(decision) }
        })
        val runtime = WriteRuntime()
        val operation = ConnectorOperation("write", "Write", JSONObject(), write = true)
        val definition = ConnectorDefinition("sample", "Sample", "1", "Sample",
            operations = listOf(operation), runtime = runtime, readPermissions = listOf("READ_SAMPLE"))
        registry = ConnectorRegistry.createForTestsWithAutonomy(
            Preferences(), { true }, gate, store, notifier, { 2_000L },
        )
        registry.register(definition)
        registry.connect("sample")

        registry.invoke(definition, operation, JSONObject(), CancellationToken.uncancellable())
        assertEquals(AutonomyPolicy.ALLOW, registry.configuredAutonomyPolicy(definition, operation))
        val token = CancellationToken.uncancellable()
        repeat(100) {
            registry.invoke(definition, operation, JSONObject(), token)
        }

        assertEquals(1, cards.get())
        assertEquals(AutonomyPolicy.ALLOW, registry.configuredAutonomyPolicy(definition, operation))
        assertEquals(AutonomyPolicy.ALLOW, registry.autonomyPolicy(definition, operation))
        assertEquals(101, runtime.executions)
        assertEquals(100, store.recentAudit("sample", 100).size)
        assertTrue(statuses.contains(ApprovalDecision.APPROVED_ALLOW_ALWAYS))
    }

    @Test fun allowAlwaysFailureLeavesActionApprovableAndExplainsMissingCondition() {
        val store = InMemoryConnectorAutonomyStore()
        val notifier = Notifier(enabled = false)
        val runtime = WriteRuntime()
        val operation = ConnectorOperation("write", "Write", JSONObject(), write = true)
        val definition = ConnectorDefinition("sample", "Sample", "1", "Sample",
            operations = listOf(operation), runtime = runtime)
        var approvalPermission: String? = "unexpected"
        var allowAlwaysAvailable = false
        var statusDetail = ""
        lateinit var registry: ConnectorRegistry
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(1_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                approvalPermission = summary.permission
                allowAlwaysAvailable = summary.allowAlwaysAvailable
                approveAndAllowAlways(
                    registry = registry,
                    connectorId = summary.autonomyConnectorId,
                    operationName = summary.autonomyOperationName,
                    onStatusDetail = { statusDetail = it.fallback },
                    onResolve = { gate.resolve(id, it) },
                )
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        registry = ConnectorRegistry.createForTestsWithAutonomy(
            Preferences(), { true }, gate, store, notifier,
        )
        registry.register(definition)
        registry.connect("sample")

        registry.invoke(definition, operation, JSONObject(), CancellationToken.uncancellable())

        assertTrue(allowAlwaysAvailable)
        assertNull(approvalPermission)
        assertTrue(statusDetail.contains("This action was approved, but Allow mode was not enabled"))
        assertTrue(statusDetail.contains("notification", ignoreCase = true))
        assertEquals(AutonomyPolicy.ASK, registry.configuredAutonomyPolicy(definition, operation))
        assertEquals(AutonomyPolicy.ASK, registry.autonomyPolicy(definition, operation))
        assertEquals(1, runtime.executions)
    }

    @Test fun nonAutonomousSystemOperationsDoNotOfferAlwaysAllow() {
        val runtime = WriteRuntime()
        val operation = ConnectorOperation("insert", "Insert", JSONObject(), write = true, autonomyAllowed = false)
        val definition = ConnectorDefinition("contacts", "Contacts", "1", "Contacts",
            operations = listOf(operation), runtime = runtime)
        var allowAlwaysAvailable: Boolean? = null
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(1_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                allowAlwaysAvailable = summary.allowAlwaysAvailable
                gate.resolve(id, ApprovalDecision.APPROVED)
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        val registry = ConnectorRegistry.createForTestsWithAutonomy(
            Preferences(), { true }, gate, InMemoryConnectorAutonomyStore(), Notifier(),
        )
        registry.register(definition)
        registry.connect("contacts")

        registry.invoke(definition, operation, JSONObject(), CancellationToken.uncancellable())

        assertEquals(false, allowAlwaysAvailable)
    }

    @Test fun configuredAllowShowsEffectiveAskAndReasonWhenNotificationsAreUnavailable() {
        val store = InMemoryConnectorAutonomyStore().apply { setPolicy("sample", "write", AutonomyPolicy.ALLOW) }
        val notifier = Notifier(enabled = false)
        val operation = ConnectorOperation("write", "Write", JSONObject(), write = true)
        val definition = ConnectorDefinition("sample", "Sample", "1", "Sample",
            operations = listOf(operation), runtime = WriteRuntime())
        val registry = ConnectorRegistry.createForTestsWithAutonomy(
            Preferences(), { true }, ApprovalGate.INSTANCE, store, notifier,
        )
        registry.register(definition)
        registry.connect("sample")

        assertEquals(AutonomyPolicy.ALLOW, registry.configuredAutonomyPolicy(definition, operation))
        assertEquals(AutonomyPolicy.ASK, registry.autonomyPolicy(definition, operation))
        assertTrue(registry.autonomyPolicyUnavailableReason(definition, operation).orEmpty()
            .contains("notification permission", ignoreCase = true))

        notifier.enabled = true
        assertEquals(AutonomyPolicy.ALLOW, registry.autonomyPolicy(definition, operation))
        assertNull(registry.autonomyPolicyUnavailableReason(definition, operation))
    }

    @Test fun auditRetainsEveryRecordWhileEachRecordTextRemainsBounded() {
        val store = InMemoryConnectorAutonomyStore()
        repeat(200) { index ->
            store.appendAudit(AutonomyAuditRecord(
                timestampMillis = index.toLong(), connectorId = "calendar", connectorName = "C".repeat(150),
                operationName = "create", operationLabel = "L".repeat(150), summary = "S".repeat(200),
            ))
        }
        val recent = store.recentAudit("calendar", 200)
        assertEquals(200, recent.size)
        assertEquals(199L, recent.first().timestampMillis)
        assertEquals(0L, recent.last().timestampMillis)
        assertEquals(10, store.recentAudit("calendar", 10).size)
        assertEquals(100, recent.first().connectorName.length)
        assertEquals(120, recent.first().summary.length)
    }

    @Test fun systemIntentWritesCannotBeSetToAllowAndMissingNotificationsFallBackToAsk() {
        val runtime = WriteRuntime()
        val systemIntentOperation = ConnectorOperation("insert", "Insert", JSONObject(), write = true, autonomyAllowed = false)
        val definition = ConnectorDefinition("contacts", "Contacts", "1", "Contacts",
            operations = listOf(systemIntentOperation), runtime = runtime)
        val notifier = Notifier(false)
        val registry = ConnectorRegistry.createForTestsWithAutonomy(
            Preferences(), { true }, ApprovalGate.INSTANCE, InMemoryConnectorAutonomyStore(), notifier,
        )
        registry.register(definition)
        registry.connect("contacts")
        assertTrue(runCatching { registry.setAutonomyPolicy(definition, systemIntentOperation, AutonomyPolicy.ALLOW) }.isFailure)
        assertEquals(AutonomyPolicy.ASK, registry.autonomyPolicy(definition, systemIntentOperation))
    }
}
