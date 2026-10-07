package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.crew.CrewRoleTemplates
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
class UserDecisionToolTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun schemaDeclaresNestedOptionsRolesAndNoInventedOptionLimit() {
        val tool = tool(availability = { false })
        val schema = tool.declaration().jsonSchema()
        val optionItems = ((schema["properties"] as Map<*, *>)["options"] as Map<*, *>)
        assertEquals("array", optionItems["type"])
        assertEquals(1, optionItems["minItems"])
        assertFalse(optionItems.containsKey("maxItems"))
        assertEquals(false, schema["additionalProperties"])
        assertEquals(UserDecisionTool.NAME, tool.declaration().name)
        val many = (1..300).map { option("id-$it", "Option $it") }
        assertEquals("unavailable", JSONObject(tool.execute(args(many), CancellationToken.uncancellable()).content)
            .getString("status"))
    }

    @Test fun malformedArgumentsReturnClearValidationErrors() {
        val tool = tool(availability = { false })
        listOf(
            args(options = emptyList()) to "at least one option",
            args(options = listOf(option("x", "One"), option("x", "Two"))) to "duplicated",
            args(options = listOf(option("", "One"))) to "id must not be empty",
            args(options = listOf(option("x", " "))) to "label must not be empty",
            args(options = listOf(option("x", "One", role = "random"))) to "role must be primary, default, or destructive",
        ).forEach { (input, expected) ->
            val result = CoreToolRegistry(listOf(tool)).invoke(UserDecisionTool.NAME, input, CancellationToken.uncancellable())
            assertFalse(result.success)
            assertTrue("${result.content} did not mention $expected", result.content.contains(expected))
        }
    }

    @Test fun noInteractiveUiReturnsUnavailableWithoutCreatingPendingRequest() {
        val gate = UserDecisionGate()
        val tool = tool(gate, availability = { false })
        val result = JSONObject(tool.execute(args(), CancellationToken.uncancellable()).content)
        assertEquals("unavailable", result.getString("status"))
        assertTrue(gate.pendingIds().isEmpty())
    }

    @Test fun toolReturnsOnlySelectedIdAndLabelAndDismissedStatus() {
        val gate = UserDecisionGate()
        val presenter = FakePresenter()
        val tool = tool(gate, presenter = presenter)
        val selected = Executors.newSingleThreadExecutor()
        try {
            val future = selected.submit<CoreToolResult> { tool.execute(args(), CancellationToken.cancellable()) }
            assertTrue(presenter.shown.await(2, TimeUnit.SECONDS))
            val card = requireNotNull(presenter.spec.get())
            assertFalse(future.isDone)
            assertTrue(gate.resolve(presenter.id.get(), UserDecisionResult.Selected(card.options.last())))
            assertFalse(gate.resolve(presenter.id.get(), UserDecisionResult.Dismissed))
            assertEquals("""{"status":"selected","option_id":"second","option_label":"Second"}""",
                JSONObject(future.get(2, TimeUnit.SECONDS).content).toString())
            assertEquals("SELECTED", presenter.updated.get())
        } finally {
            selected.shutdownNow()
            assertTrue(selected.awaitTermination(2, TimeUnit.SECONDS))
        }

        val dismissGate = UserDecisionGate()
        val dismissPresenter = FakePresenter()
        val dismissTool = tool(dismissGate, presenter = dismissPresenter)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit<CoreToolResult> { dismissTool.execute(args(), CancellationToken.cancellable()) }
            assertTrue(dismissPresenter.shown.await(2, TimeUnit.SECONDS))
            assertTrue(dismissGate.resolve(dismissPresenter.id.get(), UserDecisionResult.Dismissed))
            assertEquals("dismissed", JSONObject(future.get(2, TimeUnit.SECONDS).content).getString("status"))
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun cancellationReleasesWaiterAndFirstResolutionWinsExactlyOnce() {
        val gate = UserDecisionGate()
        val presenter = FakePresenter()
        val token = CancellationToken.cancellable()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<UserDecisionResult> {
                gate.request(spec(), token, presenter)
            }
            assertTrue(presenter.shown.await(2, TimeUnit.SECONDS))
            assertFalse(future.isDone)
            assertTrue(token.cancel())
            assertEquals(UserDecisionResult.Cancelled, future.get(2, TimeUnit.SECONDS))
            assertFalse(gate.resolve(presenter.id.get(), UserDecisionResult.Dismissed))
            assertTrue(gate.pendingIds().isEmpty())
            assertEquals("CANCELLED", presenter.updated.get())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun dismissIsRejectedWhenTheSpecForbidsIt() {
        val gate = UserDecisionGate()
        val presenter = FakePresenter()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<UserDecisionResult> {
                gate.request(spec().copy(allowDismiss = false), CancellationToken.cancellable(), presenter)
            }
            assertTrue(presenter.shown.await(2, TimeUnit.SECONDS))
            assertFalse(gate.resolve(presenter.id.get(), UserDecisionResult.Dismissed))
            assertTrue(gate.resolve(presenter.id.get(), UserDecisionResult.Selected(requireNotNull(presenter.spec.get()).options.first())))
            assertTrue(future.get(2, TimeUnit.SECONDS) is UserDecisionResult.Selected)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun promptGuidanceIsGeneralAndToolIsScopedOutOfOtherRuntimes() {
        val guidance = AgentPrompts.USER_DECISION_GUIDANCE
        assertTrue(guidance.contains("request_user_decision"))
        assertTrue(guidance.contains("cost, risk, privacy, or scope"))
        assertFalse(guidance.contains("Linux", ignoreCase = true))
        listOf("ollama", "lm studio", "ollama", "android studio").forEach {
            assertFalse("prompt hardcodes capability '$it'", guidance.contains(it, ignoreCase = true))
        }
        val working = java.io.File(requireNotNull(System.getProperty("user.dir")))
        val sourceRoot = sequenceOf(working, java.io.File(working, "app"), java.io.File(working.parentFile, "app"))
            .first { java.io.File(it, "src/main/java/com/jarvys/agent/CoreAgentRuntime.java").isFile }
        val source = java.io.File(sourceRoot, "src/main/java/com/jarvys/agent/CoreAgentRuntime.java").readText()
        assertTrue(source.contains("private boolean userDecisionAvailable()"))
        val proactive = java.io.File(sourceRoot, "src/main/java/com/jarvys/agent/proactive/ProactiveAgentProcessor.kt").readText()
        assertFalse(proactive.contains("UserDecisionTool"))
        val reflection = java.io.File(sourceRoot, "src/main/java/com/jarvys/agent/MemoryReflectionWorker.java").readText()
        assertFalse(reflection.contains("UserDecisionTool"))

        val tool = tool(availability = { false })
        val captain = CoreToolRegistry(listOf(tool))
        assertTrue(UserDecisionTool.NAME in captain.names())
        CrewRoleTemplates.all(captain).forEach { role ->
            assertFalse("${role.id} received interactive decision tool", role.tools.contains(UserDecisionTool.NAME))
        }
    }

    private fun tool(
        gate: UserDecisionGate = UserDecisionGate(),
        availability: () -> Boolean = { true },
        presenter: UserDecisionPresenter? = null,
    ) = UserDecisionTool(context, "e1-${System.nanoTime()}", gate, availability, presenter)

    private fun args(options: List<Map<String, Any>> = listOf(option("first", "First"), option("second", "Second"))) =
        mapOf<String, Any>("title" to "Choose", "body" to "Choose one option.", "options" to options)

    private fun option(id: String, label: String, role: String? = null): Map<String, Any> = buildMap {
        put("id", id)
        put("label", label)
        if (role != null) put("role", role)
    }

    private fun spec() = UserDecisionSpec("Choose", "Choose one.", listOf(
        UserDecisionOption("first", "First"), UserDecisionOption("second", "Second")))

    private class FakePresenter : UserDecisionPresenter {
        val shown = CountDownLatch(1)
        val id = AtomicReference("")
        val spec = AtomicReference<UserDecisionSpec>()
        val updated = AtomicReference("")
        override fun isAvailable() = true
        override fun show(id: String, spec: UserDecisionSpec) {
            this.id.set(id)
            this.spec.set(spec)
            shown.countDown()
        }
        override fun update(id: String, result: UserDecisionResult) {
            updated.set(when (result) {
                is UserDecisionResult.Selected -> "SELECTED"
                UserDecisionResult.Dismissed -> "DISMISSED"
                UserDecisionResult.Cancelled -> "CANCELLED"
                UserDecisionResult.Unavailable -> "UNAVAILABLE"
            })
        }
    }
}
