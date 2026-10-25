package com.jarvys.agent

import android.app.Application
import android.content.Context
import com.jarvys.agent.device.ScreenData
import java.util.concurrent.CancellationException
import java.util.function.LongSupplier
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic providers only. Does not install or invoke the process crash handler. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class,
    shadows = [ArtifactOsShadow::class, ArtifactOsShadow.Descriptor::class])
class ProviderDiagnosticsCaptureTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val chatTestStore = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    private val instanceField = LocalDiagnostics::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val secretField = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
    private var previousStore: Any? = null
    private var previousSecrets: Any? = null
    private lateinit var store: LocalDiagnostics
    private lateinit var context: Application
    private lateinit var session: String
    private val history = listOf(ConversationTurn("user", "Synthetic earlier turn"))
    private val declarations = listOf(ToolSpec("synthetic_probe", "test", "Synthetic probe", "test",
        ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList()))

    @Before fun isolateStoreAndSecretsWithoutInstallingHandler() {
        context = RuntimeEnvironment.getApplication()
        session = "ux45-provider-${System.nanoTime()}"
        previousStore = instanceField.get(null)
        previousSecrets = secretField.get(null)
        store = LocalDiagnostics(temporary.newFolder("no-backup"), LongSupplier { 1_800_000_000_000L })
        instanceField.set(null, store)
        secretField.set(null, SecretStore(context.getSharedPreferences("ux45-provider-secrets", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }))
        assertEquals(0, records().size)
    }

    @After fun restoreSingletons() {
        instanceField.set(null, previousStore)
        secretField.set(null, previousSecrets)
        ArtifactOsShadow.reset()
    }

    @Test fun completeRethrowsTheIdenticalRuntimeExceptionAndRecordsOneRequest() =
        assertOriginalFailure(Entry.COMPLETE)

    @Test fun completeMainChatRethrowsTheIdenticalRuntimeExceptionAndRecordsOneRequest() =
        assertOriginalFailure(Entry.MAIN_CHAT)

    @Test fun completeSummaryRethrowsTheIdenticalRuntimeExceptionAndRecordsOneRequest() =
        assertOriginalFailure(Entry.SUMMARY)

    @Test fun successfulCallsKeepTheSameReplyAndProviderArgumentsWithoutCreatingRecords() {
        Entry.values().forEach { entry ->
            val expected = ModelReply("Synthetic answer", listOf(ModelReply.Call("call-1", "synthetic_probe", emptyMap())),
                "Synthetic private response body", 200, "synthetic-model", 47)
            val token = CancellationToken.cancellable()
            val provider = FakeProvider { request ->
                assertEquals("Synthetic instructions", request.instructions)
                assertEquals("Synthetic prompt", request.prompt)
                assertEquals(session, request.session)
                assertSame(token, request.token)
                assertTrue(request.images.isEmpty())
                if (entry == Entry.SUMMARY) {
                    assertTrue(request.history.isEmpty())
                    assertTrue(request.tools.isEmpty())
                } else {
                    assertEquals(history, request.history)
                    assertSame(declarations, request.tools)
                }
                expected
            }
            assertSame(expected, invoke(entry, CoreAgentModel(context, provider, session), token))
            assertEquals(1, provider.calls)
        }
        assertTrue(records().isEmpty())
    }

    @Test fun providerCancellationIsRethrownUnchangedWithoutDiagnosticForEveryEntryPoint() {
        Entry.values().forEach { entry ->
            val failure = CancellationException("Synthetic cancellation")
            val provider = FakeProvider { throw failure }
            val caught = runCatching { invoke(entry, CoreAgentModel(context, provider, session)) }.exceptionOrNull()
            assertSame(entry.name, failure, caught)
            assertEquals(1, provider.calls)
        }
        assertTrue(records().isEmpty())
    }

    @Test fun stopDuringProviderRequestSuppressesEvenANonCancellationFailure() {
        Entry.values().forEach { entry ->
            val token = CancellationToken.cancellable()
            val failure = IllegalStateException("Synthetic failure after STOP")
            val provider = FakeProvider { token.cancel(); throw failure }
            assertSame(failure, runCatching {
                invoke(entry, CoreAgentModel(context, provider, session), token)
            }.exceptionOrNull())
            assertEquals(1, provider.calls)
        }
        assertTrue(records().isEmpty())
    }

    @Test fun alreadyCancelledSummaryDoesNotCallProviderOrRecord() {
        val provider = FakeProvider { error("Cancelled summary must not call the provider") }
        val token = CancellationToken.cancellable().apply { cancel() }
        assertThrows(CancellationException::class.java) {
            invoke(Entry.SUMMARY, CoreAgentModel(context, provider, session), token)
        }
        assertEquals(0, provider.calls)
        assertTrue(records().isEmpty())
    }

    @Test fun attachmentsAndImagesNeverSuppressTheRequestRecordInTheRealRuntimeAdapter() {
        LocalRunStore(context).appendGeneratedImageFailure(session, "Synthetic image prompt", "Synthetic image error")
        val failure = IllegalStateException("Synthetic provider failure")
        val provider = FakeProvider { throw failure }
        val model = CoreAgentModel(context, provider, session)
        val runtime = diagnosticScopeRuntime(context, session)
        val adapter = runtime.requestModel(model, CoreToolRegistry(emptyList()), true, null)
        assertSame(failure, runCatching {
            adapter.complete(history, "Synthetic prompt", declarations, CancellationToken.uncancellable())
        }.exceptionOrNull())
        assertEquals(1, provider.calls)
        assertRequestRecord(records().single())
    }

    @Test fun existingImageRejectionMappingStillWrapsTheOriginalFailureAndIsRecorded() {
        val failure = ProviderHttpException("Synthetic image rejection", 415, 0, null)
        val provider = FakeProvider { throw failure }
        val prepared = listOf(ConversationTurn("user", "Synthetic image message")
            .withModelParts("Synthetic image metadata", listOf(ConversationTurn.Image("AA==", 1, 1))))
        val expected = AttachmentModelContext.providerFailure(AppLanguageRuntime.localizedContext(context), failure)
        val actual = runCatching {
            CoreAgentModel(context, provider, session).completeMainChat("Synthetic instructions", prepared,
                "Synthetic prompt", declarations, CancellationToken.uncancellable())
        }.exceptionOrNull()
        assertNotSame("Existing image rejection mapping must still run", failure, actual)
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
        assertSame(failure, actual?.cause)
        assertEquals(1, provider.calls)
        assertRequestRecord(records().single())
    }

    @Test fun generalCompletionStillStripsImageModelPartsWithoutMutatingHistory() {
        val original = ConversationTurn("user", "Synthetic plain text")
            .withModelParts("Synthetic attachment metadata", listOf(ConversationTurn.Image("AA==", 1, 1)))
        val expected = ModelReply("Synthetic success", emptyList())
        val provider = FakeProvider {
            assertEquals("Synthetic plain text", it.history.single().modelContent)
            assertTrue(it.history.single().images.isEmpty())
            expected
        }
        assertSame(expected, CoreAgentModel(context, provider, session).complete("Synthetic instructions",
            listOf(original), "Synthetic prompt", declarations, CancellationToken.uncancellable()))
        assertEquals("Synthetic attachment metadata", original.modelContent)
        assertEquals(1, original.images.size)
        assertTrue(records().isEmpty())
    }

    @Test fun rateLimitedRequestCanBeRecordedWhileTheRealLoopStillCompletesAfterRetry() {
        val failure = ProviderHttpException("Synthetic rate limit", 429, 0, null)
        var attempts = 0
        var waits = 0
        val provider = FakeProvider {
            if (++attempts == 1) throw failure
            ModelReply("Synthetic final success", emptyList())
        }
        val model = CoreAgentModel(context, provider, session)
        val adapter = object : CoreAgentLoop.Model {
            override fun complete(transcript: List<ConversationTurn>, prompt: String,
                tools: List<ToolSpec>, token: CancellationToken) =
                model.complete("Synthetic instructions", transcript, prompt, tools, token)
        }
        val loop = CoreAgentLoop(adapter, CoreToolRegistry(emptyList()), "Synthetic instructions", session,
            CorePromptBudget.standard(), null, CoreAgentLoop.ToolResultBudget.BOUNDED, null,
            CoreAgentLoop.RateLimitWaiter { error, retryNumber, _ ->
                assertSame(failure, error)
                assertEquals(0L, retryNumber)
                waits++ // Deliberately no sleep and no external operation.
            })
        val result = loop.run("Synthetic request", emptyList(), CancellationToken.uncancellable(), null)
        assertEquals(2, attempts)
        assertEquals(1, waits)
        assertEquals("COMPLETED", result.outcome)
        assertEquals(CoreAgentLoop.InterruptionReason.NONE, result.interruptionReason)
        assertEquals("Synthetic final success", result.text)
        result.throwIfProviderUnavailable()
        assertRequestRecord(records().single())
    }

    @Test fun existingProviderUnavailableOutcomeAndExactFailureCauseArePreserved() {
        val failure = ProviderHttpException("Synthetic service unavailable", 503, 0, null)
        val provider = FakeProvider { throw failure }
        val loop = CoreAgentLoop(CoreAgentModel(context, provider, session), CoreToolRegistry(emptyList()),
            "Synthetic instructions", session)
        val result = loop.run("Synthetic request", emptyList(), CancellationToken.uncancellable(), null)
        assertEquals("PARTIAL", result.outcome)
        assertEquals(CoreAgentLoop.InterruptionReason.PROVIDER_UNAVAILABLE, result.interruptionReason)
        assertSame(failure, runCatching { result.throwIfProviderUnavailable() }.exceptionOrNull())
        assertEquals(1, provider.calls)
        assertRequestRecord(records().single())
    }

    @Test fun unavailableDiagnosticStorageCannotReplaceOriginalProviderFailure() {
        instanceField.set(null, LocalDiagnostics(temporary.newFile("invalid-no-backup-root"),
            LongSupplier { 1_800_000_000_000L }))
        Entry.values().forEach { entry ->
            val failure = IllegalStateException("Synthetic provider failure while storage is unavailable")
            val provider = FakeProvider { throw failure }
            assertSame(entry.name, failure, runCatching {
                invoke(entry, CoreAgentModel(context, provider, session))
            }.exceptionOrNull())
            assertEquals(1, provider.calls)
        }
    }

    private fun assertOriginalFailure(entry: Entry) {
        val failure = IllegalStateException("Synthetic message must never enter metadata")
        val provider = FakeProvider { throw failure }
        assertSame(failure, runCatching {
            invoke(entry, CoreAgentModel(context, provider, session))
        }.exceptionOrNull())
        assertEquals(1, provider.calls)
        val record = records().single()
        assertRequestRecord(record)
        assertFalse(record.toString().contains(failure.message!!))
    }

    private fun assertRequestRecord(record: JSONObject) {
        assertEquals("PROVIDER", record.getString("origin"))
        assertEquals("recoverable", record.getString("severity"))
        assertEquals("exception", record.getString("kind"))
        assertTrue(record.getJSONArray("causes").length() > 0)
        for (key in listOf("outcome", "run_outcome", "run_status", "run_id", "session_id", "completed", "failed")) {
            assertFalse("Request capture must not invent final run state: $key", record.has(key))
        }
    }

    private fun records(): List<JSONObject> {
        val snapshot = store.snapshot(null, 10, true)
        assertEquals("ok", snapshot.getString("status"))
        val rows = snapshot.getJSONArray("records")
        return (0 until rows.length()).map(rows::getJSONObject)
    }

    private fun invoke(entry: Entry, model: CoreAgentModel,
        token: CancellationToken = CancellationToken.uncancellable()): ModelReply = when (entry) {
        Entry.COMPLETE -> model.complete("Synthetic instructions", history, "Synthetic prompt", declarations, token)
        Entry.MAIN_CHAT -> model.completeMainChat("Synthetic instructions", history, "Synthetic prompt", declarations, token)
        Entry.SUMMARY -> model.completeSummary("Synthetic instructions", "Synthetic prompt", token)
    }

    private enum class Entry { COMPLETE, MAIN_CHAT, SUMMARY }
    private data class Request(val instructions: String, val history: List<ConversationTurn>, val prompt: String,
        val images: List<ScreenData>, val tools: List<ToolSpec>, val session: String, val token: CancellationToken)
    private class FakeProvider(private val respond: (Request) -> ModelReply) : ModelProviderClient {
        var calls = 0
        override fun complete(systemPrompt: String, userPrompt: String, images: List<ScreenData>,
            tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply =
            error("Tests require the conversation provider boundary")
        override fun completeConversation(systemPrompt: String, history: List<ConversationTurn>, userPrompt: String,
            images: List<ScreenData>, tools: List<ToolSpec>, sessionId: String, token: CancellationToken): ModelReply {
            calls++
            return respond(Request(systemPrompt, history, userPrompt, images, tools, sessionId, token))
        }
    }
}
