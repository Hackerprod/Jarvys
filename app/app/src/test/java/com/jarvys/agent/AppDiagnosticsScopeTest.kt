package com.jarvys.agent

import android.app.Application
import android.content.Context
import com.jarvys.agent.proactive.ProactiveConversation
import com.jarvys.agent.tasks.ScheduledTaskConversation
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Assembly only: no provider requests, Crew execution, or process handler. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppDiagnosticsScopeTest {
    private lateinit var context: Application
    private val secretField = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
    private var previousSecrets: Any? = null

    @Before fun useFakeSecretStore() {
        context = RuntimeEnvironment.getApplication()
        previousSecrets = secretField.get(null)
        secretField.set(null, SecretStore(context.getSharedPreferences("ux45-scope-secrets", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }))
    }

    @After fun restoreSecretStore() { secretField.set(null, previousSecrets) }

    @Test fun ordinaryMainChatRegistersExactlyOneDiagnosticsToolAndItsGuidance() {
        val runtime = runtime()
        val tools = runtime.createTools()
        assertEquals(1, tools.names().count { it == AppDiagnosticsTool.NAME })
        assertTrue(tools.get(AppDiagnosticsTool.NAME) is AppDiagnosticsTool)
        assertFalse(tools.get(AppDiagnosticsTool.NAME).canDelegate())
        assertEquals(1, tools.declarations().count { it.name == AppDiagnosticsTool.NAME })
        assertTrue(runtime.instructions().contains(AppDiagnosticsTool.GUIDANCE))
    }

    @Test fun mascotAnimationIsDeclaredOnlyForMainChatAndNeverDelegates() {
        val main = runtime()
        val tools = main.createTools()
        assertEquals(1, tools.names().count { it == MascotAnimationTool.NAME })
        assertEquals(1, tools.declarations().count { it.name == MascotAnimationTool.NAME })
        val animationSchema = tools.declarations().single { it.name == MascotAnimationTool.NAME }.jsonSchema()
        assertEquals(listOf("animation"), animationSchema["required"])
        val properties = animationSchema["properties"] as Map<*, *>
        assertEquals(MascotAnimationTool.ANIMATIONS, (properties["animation"] as Map<*, *>)["enum"])
        assertFalse(tools.get(MascotAnimationTool.NAME).canDelegate())
        assertTrue(main.instructions().contains(MascotAnimationTool.GUIDANCE))
        assertFalse(tools.forDelegatedAgent().names().contains(MascotAnimationTool.NAME))
        assertFalse(CoreAgentRuntime.crewBotCapabilityScope(tools).names().contains(MascotAnimationTool.NAME))
        for (includeDelegate in listOf(false, true)) {
            assertFalse(main.createToolsNames(tools.handlers(), includeDelegate).contains(MascotAnimationTool.NAME))
        }
        for (session in listOf(null, "", ProactiveConversation.SESSION_ID, ScheduledTaskConversation.SESSION_ID)) {
            assertFalse(diagnosticScopeRuntime(context, session, allowed = listOf("*")).createTools().names()
                .contains(MascotAnimationTool.NAME))
        }
        for (depth in listOf(1, 2)) {
            assertFalse(runtime(depth = depth, allowed = listOf("*")).createTools().names()
                .contains(MascotAnimationTool.NAME))
        }
        assertFalse(diagnosticScopeRuntime(null, "main", allowed = listOf("*")).createTools().names()
            .contains(MascotAnimationTool.NAME))
    }

    @Test fun noContextHasNeitherDiagnosticsDeclarationNorGuidance() {
        val noContext = CoreAgentRuntime(emptyList(), emptyList(), emptyList(), emptyList())
        assertExcluded(noContext)
        assertExcluded(diagnosticScopeRuntime(null, "ux45-no-context", allowed = listOf(AppDiagnosticsTool.NAME)))
    }

    @Test fun emptyAndNullSessionsCannotRegisterDiagnosticsEvenWithExplicitAllowList() {
        for (session in listOf(null, "")) {
            assertExcluded(diagnosticScopeRuntime(context, session, allowed = listOf(AppDiagnosticsTool.NAME)))
        }
    }

    @Test fun proactiveAndScheduledScopesCannotRegisterDiagnosticsEvenWithExplicitAllowList() {
        for (session in listOf(ProactiveConversation.SESSION_ID, ScheduledTaskConversation.SESSION_ID)) {
            for (allowed in listOf(null, listOf(AppDiagnosticsTool.NAME), listOf("*"))) {
                assertExcluded(diagnosticScopeRuntime(context, session, allowed = allowed))
            }
        }
    }

    @Test fun childDepthCannotRegainDiagnosticsFromAnExplicitNameOrWildcard() {
        for (depth in listOf(1, 2)) {
            for (allowed in listOf(null, listOf(AppDiagnosticsTool.NAME), listOf("*"))) {
                assertExcluded(runtime(depth = depth, allowed = allowed))
            }
        }
    }

    @Test fun trustedRestoredCrewFlagStillCannotRegainDiagnostics() {
        for (allowed in listOf(null, listOf(AppDiagnosticsTool.NAME), listOf("*"))) {
            val restored = runtime(depth = 1, allowed = allowed)
            CoreAgentRuntime::class.java.getDeclaredField("restoredCrewScope").apply {
                isAccessible = true
                setBoolean(restored, true)
                assertTrue(getBoolean(restored))
            }
            assertExcluded(restored)
        }
    }

    @Test fun mainChatAllowListMustIncludeDiagnosticsAndCallerMutationCannotExpandTheScope() {
        val allowed = mutableListOf(WebSearchTools.SEARCH)
        val narrowed = runtime(allowed = allowed)
        allowed.add(AppDiagnosticsTool.NAME)
        assertExcluded(narrowed)
        assertExcluded(runtime(allowed = emptyList()))
        assertTrue(runtime(allowed = listOf(AppDiagnosticsTool.NAME)).createTools().names().contains(AppDiagnosticsTool.NAME))
    }

    @Test fun delegatedAndCrewCapabilityRegistriesBothExcludeDiagnostics() {
        val main = runtime().createTools()
        assertTrue(main.names().contains(AppDiagnosticsTool.NAME))
        assertFalse(main.forDelegatedAgent().names().contains(AppDiagnosticsTool.NAME))
        assertFalse(CoreAgentRuntime.crewBotCapabilityScope(main).names().contains(AppDiagnosticsTool.NAME))
        for (includeDelegate in listOf(false, true)) {
            assertFalse(runtime().createToolsNames(main.handlers(), includeDelegate).contains(AppDiagnosticsTool.NAME))
        }
    }

    @Test fun crewNameFilterRejectsDiagnosticsEvenIfAnotherHandlerClaimsItIsDelegable() {
        val synthetic = object : CoreTool {
            override fun canDelegate() = true
            override fun declaration() = ToolSpec(AppDiagnosticsTool.NAME, "test", "Synthetic test declaration", "test",
                ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
            override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult =
                error("This assembly test must never execute a tool")
        }
        val registry = CoreToolRegistry(listOf(synthetic))
        assertTrue(registry.forDelegatedAgent().names().contains(AppDiagnosticsTool.NAME))
        assertFalse(CoreAgentRuntime.crewBotCapabilityScope(registry).names().contains(AppDiagnosticsTool.NAME))
    }

    @Test fun narrowedOrDelegatedRegistryCannotRestoreDiagnosticsByRequestingTheOriginalNames() {
        val main = runtime().createTools()
        val originalNames = main.names()
        val narrowed = main.subset(originalNames.filterNot { it == AppDiagnosticsTool.NAME })
        val delegated = main.forDelegatedAgent()
        for (restricted in listOf(narrowed, delegated, CoreAgentRuntime.crewBotCapabilityScope(main))) {
            assertFalse(restricted.subset(originalNames).names().contains(AppDiagnosticsTool.NAME))
            assertFalse(restricted.forRequester("Synthetic restored worker").subset(originalNames)
                .names().contains(AppDiagnosticsTool.NAME))
        }
    }

    @Test fun instructionsUseOnlyTheEffectiveDeclaredRegistryAfterNarrowing() {
        val runtime = runtime()
        val full = runtime.createTools()
        assertTrue(runtime.instructions(full, true).contains(AppDiagnosticsTool.GUIDANCE))
        val narrowed = full.subset(full.names().filterNot { it == AppDiagnosticsTool.NAME })
        assertFalse(runtime.instructions(narrowed, true).contains(AppDiagnosticsTool.GUIDANCE))
        assertFalse(runtime.instructions(CoreToolRegistry(emptyList()), true).contains(AppDiagnosticsTool.GUIDANCE))
        assertFalse(runtime.instructions(full.forDelegatedAgent(), false).contains(AppDiagnosticsTool.GUIDANCE))
        assertFalse(runtime.instructions(CoreAgentRuntime.crewBotCapabilityScope(full), false)
            .contains(AppDiagnosticsTool.GUIDANCE))
    }

    private fun runtime(depth: Int = 0, allowed: List<String>? = null) =
        diagnosticScopeRuntime(context, "ux45-scope-${System.nanoTime()}", depth, allowed)

    private fun assertExcluded(runtime: CoreAgentRuntime) {
        val tools = runtime.createTools()
        assertFalse(tools.names().contains(AppDiagnosticsTool.NAME))
        assertFalse(tools.declarations().any { it.name == AppDiagnosticsTool.NAME })
        assertNull(tools.get(AppDiagnosticsTool.NAME))
        assertFalse(runtime.instructions().contains(AppDiagnosticsTool.GUIDANCE))
    }
}

/** Existing assembly seam supplies empty plugin/connector inputs without executing a provider. */
internal fun diagnosticScopeRuntime(context: Context?, session: String?, depth: Int = 0,
    allowed: List<String>? = null): CoreAgentRuntime {
    val constructor = CoreAgentRuntime::class.java.declaredConstructors
        .single { it.parameterTypes.size == 12 }.apply { isAccessible = true }
    return constructor.newInstance(context, session, emptyList<Any>(), allowed,
        emptyList<CoreTool>(), emptyList<CoreTool>(), emptyList<CoreTool>(), null, depth,
        CorePromptBudget.standard(), false, null) as CoreAgentRuntime
}
