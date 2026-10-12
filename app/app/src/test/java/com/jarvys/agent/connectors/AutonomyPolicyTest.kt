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
        override fun post(record: AutonomyAuditRecord) {
            check(enabled) { "Notifications are unavailable" }
            posted.add(record)
        }
    }

    private class WriteRuntime : ConnectorRuntime {
        var executions = 0
        var reads = 0
        override fun connect(configuration: Map<String, String>, secrets: Map<String, String>) = Unit
        override fun disconnect() = Unit
        override fun prepareWrite(operation: String, arguments: JSONObject, token: CancellationToken) =
            ConnectorWritePreparation(ApprovalSummary("Write", listOf("sensitive input")), arguments)
        override fun invoke(operation: String, arguments: JSONObject, token: CancellationToken): JSONObject {
            reads++
            return JSONObject().put("ok", true)
        }
        override fun invokePrepared(operation: String, arguments: JSONObject,
                                    preparation: ConnectorWritePreparation, token: CancellationToken): JSONObject {
            executions++
            return JSONObject().put("ok", true)
        }
    }

    @Test fun allowIsDefaultSavedAskShowsCardAndSavedDenyBlocks() {
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

        assertEquals(AutonomyPolicy.ALLOW, registry.autonomyPolicy(definition, operation))
        assertNull(store.savedPolicy("sample", "write"))
        registry.invoke(definition, operation, JSONObject(), CancellationToken.uncancellable())
        assertEquals(0, cards.get())
        assertEquals(1, runtime.executions)
        assertEquals("Completed automatically", store.recentAudit("sample", 50).single().summary)
        assertEquals(1, notifier.posted.size)

        registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.ASK)
        registry.invoke(definition, operation, JSONObject(), CancellationToken.uncancellable())
        assertEquals(AutonomyPolicy.ASK, store.savedPolicy("sample", "write"))
        assertEquals(1, cards.get())
        assertEquals(2, runtime.executions)
        assertEquals(1, store.recentAudit("sample", 50).size)
        assertEquals(1, notifier.posted.size)

        registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.ALLOW)
        registry.invoke(definition, operation, JSONObject(), CancellationToken.uncancellable())
        assertEquals(1, cards.get())
        assertEquals(3, runtime.executions)
        assertEquals(2, store.recentAudit("sample", 50).size)
        assertEquals(2, notifier.posted.size)

        registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.DENY)
        assertTrue(runCatching { registry.invoke(definition, operation, JSONObject(), CancellationToken.uncancellable()) }.isFailure)
        assertEquals(AutonomyPolicy.DENY, store.savedPolicy("sample", "write"))
        assertEquals(3, runtime.executions)
        assertEquals(1, cards.get())
        assertEquals(2, store.recentAudit("sample", 50).size)
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

    @Test fun explicitApproveAndAlwaysAllowContinuesWhenNotificationsAreDisabled() {
        val store = InMemoryConnectorAutonomyStore()
        val notifier = Notifier(enabled = false)
        val cards = AtomicInteger()
        val statuses = mutableListOf<ApprovalDecision>()
        var approvalId: String? = null
        lateinit var registry: ConnectorRegistry
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(1_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                cards.incrementAndGet()
                approvalId = id
                assertTrue(runCatching { gate.enableAlwaysFromUi("wrong-approval-id") }.isFailure)
                assertEquals(AutonomyPolicy.ASK, store.policy("sample", "write"))
                assertTrue(summary.allowAlwaysAvailable)
                assertEquals("sample", summary.autonomyConnectorId)
                assertEquals("write", summary.autonomyOperationName)
                approveAndAllowAlways(
                    enable = { assertTrue(gate.enableAlwaysFromUi(id)); null },
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
        registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.ASK)

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
        assertTrue(runCatching { gate.enableAlwaysFromUi(requireNotNull(approvalId)) }.isFailure)
        assertFalse(gate.resolve(requireNotNull(approvalId), ApprovalDecision.APPROVED_ALLOW_ALWAYS))
        assertEquals(101, runtime.executions)
    }

    @Test fun allowAlwaysPersistenceFailureLeavesThisExactAskActionApprovable() {
        val delegate = InMemoryConnectorAutonomyStore().apply { setPolicy("sample", "write", AutonomyPolicy.ASK) }
        val store = object : ConnectorAutonomyStore by delegate {
            override fun setPolicyAndSnapshot(connectorId: String, operationName: String,
                                              policy: AutonomyPolicy): ActionPolicySnapshot =
                error("Could not persist the exact action policy")
        }
        val runtime = WriteRuntime()
        val operation = ConnectorOperation("write", "Write", JSONObject(), write = true)
        val definition = ConnectorDefinition("sample", "Sample", "1", "Sample",
            operations = listOf(operation), runtime = runtime)
        var approvalPermission: String? = "unexpected"
        var allowAlwaysAvailable = false
        var statusDetail = ""
        val statuses = mutableListOf<ApprovalDecision>()
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(1_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                approvalPermission = summary.permission
                allowAlwaysAvailable = summary.allowAlwaysAvailable
                approveAndAllowAlways(
                    enable = {
                        runCatching { check(gate.enableAlwaysFromUi(id)) }.exceptionOrNull()
                            ?.let { ConnectorUiText(fallback = requireNotNull(it.message)) }
                    },
                    onStatusDetail = { statusDetail = it.fallback },
                    onResolve = { gate.resolve(id, it) },
                )
            }
            override fun update(id: String, decision: ApprovalDecision) { statuses += decision }
        })
        val registry = ConnectorRegistry.createForTestsWithAutonomy(
            Preferences(), { true }, gate, store, Notifier(enabled = false),
        )
        registry.register(definition)
        registry.connect("sample")

        registry.invoke(definition, operation, JSONObject(), CancellationToken.uncancellable())

        assertTrue(allowAlwaysAvailable)
        assertNull(approvalPermission)
        assertTrue(statusDetail.contains("This action was approved, but Allow mode was not enabled"))
        assertTrue(statusDetail.contains("Could not persist the exact action policy"))
        assertEquals(listOf(ApprovalDecision.APPROVED_ALLOW_FAILED), statuses)
        assertEquals(AutonomyPolicy.ASK, registry.configuredAutonomyPolicy(definition, operation))
        assertEquals(AutonomyPolicy.ASK, registry.autonomyPolicy(definition, operation))
        assertEquals(1, runtime.executions)
        assertTrue(store.recentAudit("sample", 50).isEmpty())
    }

    @Test fun legacySystemOperationMetadataDoesNotHideAlwaysAllowForSavedAsk() {
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
        registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.ASK)

        registry.invoke(definition, operation, JSONObject(), CancellationToken.uncancellable())

        assertEquals(true, allowAlwaysAvailable)
        assertEquals(1, runtime.executions)
    }

    @Test fun configuredAllowStillExecutesAndAuditsWhenNotificationsAreUnavailable() {
        val store = InMemoryConnectorAutonomyStore().apply { setPolicy("sample", "write", AutonomyPolicy.ALLOW) }
        val notifier = Notifier(enabled = false)
        val runtime = WriteRuntime()
        val operation = ConnectorOperation("write", "Write", JSONObject(), write = true)
        val definition = ConnectorDefinition("sample", "Sample", "1", "Sample",
            operations = listOf(operation), runtime = runtime)
        val gate = ApprovalGate(100, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) = error("ALLOW must not show an approval card")
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        val registry = ConnectorRegistry.createForTestsWithAutonomy(
            Preferences(), { true }, gate, store, notifier,
        )
        registry.register(definition)
        registry.connect("sample")

        assertEquals(AutonomyPolicy.ALLOW, registry.configuredAutonomyPolicy(definition, operation))
        assertEquals(AutonomyPolicy.ALLOW, registry.autonomyPolicy(definition, operation))
        assertNull(registry.autonomyPolicyUnavailableReason(definition, operation))
        assertFalse(registry.autonomyNotificationsAvailable())
        val result = registry.invoke(definition, operation, JSONObject(), CancellationToken.uncancellable())
        assertEquals(1, runtime.executions)
        assertEquals(1, store.recentAudit("sample", 50).size)
        assertTrue(result.has("notificationWarning"))
        assertTrue(notifier.posted.isEmpty())

        notifier.enabled = true
        assertEquals(AutonomyPolicy.ALLOW, registry.autonomyPolicy(definition, operation))
        assertNull(registry.autonomyPolicyUnavailableReason(definition, operation))
        val next = registry.invoke(definition, operation, JSONObject(), CancellationToken.uncancellable())
        assertEquals(2, runtime.executions)
        assertEquals(2, store.recentAudit("sample", 50).size)
        assertFalse(next.has("notificationWarning"))
        assertEquals(1, notifier.posted.size)
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

    @Test fun legacySystemIntentMetadataDoesNotOverrideAnExplicitAllowChoice() {
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
        registry.setAutonomyPolicy(definition, systemIntentOperation, AutonomyPolicy.ALLOW)
        assertEquals(AutonomyPolicy.ALLOW, registry.autonomyPolicy(definition, systemIntentOperation))
        assertNull(registry.autonomyPolicyUnavailableReason(definition, systemIntentOperation))
        registry.invoke(definition, systemIntentOperation, JSONObject(), CancellationToken.uncancellable())
        assertEquals(1, runtime.executions)
    }

    @Test fun readsDefaultToAllowAndHonorSavedAskAndDenyWithoutChangingSiblingPolicy() {
        val store = InMemoryConnectorAutonomyStore()
        val runtime = WriteRuntime()
        val read = ConnectorOperation("read", "Read", JSONObject(), write = false)
        val sibling = ConnectorOperation("other_read", "Read other", JSONObject(), write = false)
        val definition = ConnectorDefinition("sample", "Sample", "1", "Sample",
            operations = listOf(read, sibling), runtime = runtime)
        var cards = 0
        lateinit var gate: ApprovalGate
        gate = ApprovalGate(1_000, object : ApprovalPresenter {
            override fun show(id: String, summary: ApprovalSummary) {
                cards++
                assertEquals(read.name, summary.autonomyOperationName)
                gate.resolve(id, ApprovalDecision.APPROVED)
            }
            override fun update(id: String, decision: ApprovalDecision) = Unit
        })
        val registry = ConnectorRegistry.createForTestsWithAutonomy(
            Preferences(), { true }, gate, store, Notifier(),
        )
        registry.register(definition)
        registry.connect(definition.id)
        val token = CancellationToken.uncancellable()
        assertEquals(AutonomyPolicy.ALLOW, registry.autonomyPolicy(definition, read))
        registry.invoke(definition, read, JSONObject(), token)
        assertEquals(0, cards)
        assertEquals(1, runtime.reads)
        registry.setAutonomyPolicy(definition, read, AutonomyPolicy.ASK)
        registry.invoke(definition, read, JSONObject(), token)
        assertEquals(1, cards)
        assertEquals(2, runtime.reads)
        registry.setAutonomyPolicy(definition, read, AutonomyPolicy.DENY)
        assertTrue(runCatching { registry.invoke(definition, read, JSONObject(), token) }.isFailure)
        assertEquals(1, cards)
        assertEquals(2, runtime.reads)
        assertEquals(0, runtime.executions)
        assertEquals(AutonomyPolicy.ALLOW, registry.autonomyPolicy(definition, sibling))
        assertNull(store.savedPolicy(definition.id, sibling.name))
    }

    @Test fun allowChoiceDoesNotGrantAndroidOrProviderAccessAndRevocationStillBlocks() {
        val runtime = WriteRuntime()
        val operation = ConnectorOperation("write", "Write", JSONObject(), write = true,
            requiredPermissions = listOf("WRITE_SAMPLE"))
        var androidGranted = false
        var providerGranted = false
        val definition = ConnectorDefinition("sample", "Sample", "1", "Sample",
            operations = listOf(operation), runtime = runtime, operationAccessGranted = { providerGranted })
        val registry = ConnectorRegistry.createForTestsWithAutonomy(
            Preferences(), { androidGranted }, rejectingApprovalGate(), InMemoryConnectorAutonomyStore(), Notifier(),
        )
        registry.register(definition)
        registry.connect(definition.id)
        registry.setAutonomyPolicy(definition, operation, AutonomyPolicy.ALLOW)
        val token = CancellationToken.uncancellable()
        assertTrue(runCatching { registry.invoke(definition, operation, JSONObject(), token) }.isFailure)
        assertEquals(0, runtime.executions)
        providerGranted = true
        assertTrue(runCatching { registry.invoke(definition, operation, JSONObject(), token) }.isFailure)
        assertEquals(0, runtime.executions)
        assertTrue(registry.autonomyPolicyUnavailableReason(definition, operation).orEmpty().contains("WRITE_SAMPLE"))
        androidGranted = true
        registry.invoke(definition, operation, JSONObject(), token)
        assertEquals(1, runtime.executions)
        providerGranted = false
        assertTrue(runCatching { registry.invoke(definition, operation, JSONObject(), token) }.isFailure)
        assertEquals(1, runtime.executions)
        assertEquals(AutonomyPolicy.ALLOW, registry.autonomyPolicy(definition, operation))
    }
}
