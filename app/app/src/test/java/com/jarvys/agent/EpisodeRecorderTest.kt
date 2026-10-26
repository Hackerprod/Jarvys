package com.jarvys.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.chatdb.ChatDatabaseBridge
import com.jarvys.agent.chatdb.ChatDbResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpisodeRecorderTest {
    @get:Rule val chatStore = ChatTestStore()

    @Test fun argumentDependentWriteIsRecordedBeforeDispatchButReadsAndMemoryAreNot() {
        val files = chatStore.newFilesDirectory("episode-recorder")
        chatStore.withLocalRunStore(files) {
            val app = ApplicationProvider.getApplicationContext<Context>()
            val context = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = files
            }
            val database = ChatDatabaseBridge.getInstance(context)
            val session = "episode-registry-session"
            assertTrue(database.ensureConversationSync(session) is ChatDbResult.Success)
            val owner = "episode-request"
            var writes = 0
            val mixed = object : CoreTool {
                override fun declaration() = ToolSpec("mixed", "test", "Argument dependent effect", "test",
                    ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
                override fun effectClass() = CoreTool.EffectClass.READ
                override fun effectClass(arguments: Map<String, Any>) =
                    if (arguments["write"] == true) CoreTool.EffectClass.WRITE else CoreTool.EffectClass.READ
                override fun execute(arguments: Map<String, Any>, token: CancellationToken,
                    progress: CoreTool.ProgressListener?, invocation: EffectInvocation?): CoreToolResult {
                    val result = super<CoreTool>.execute(arguments, token, progress, invocation)
                    return if (arguments["write"] == true && invocation != null)
                        result.withEffectReceipt(invocation.receipt(EffectInvocation.Outcome.OK)) else result
                }
                override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
                    val episodes = database.personalAgentEpisodesForSourceMessagesSync(session, listOf(owner))
                    if (arguments["write"] == true) {
                        writes++
                        assertEquals(1, episodes.size)
                        val row = database.personalAgentEpisodeNarrationInputSync(episodes.single().id)!!.evidence.single()
                        assertEquals("started", row.outcome)
                        assertEquals("write-call", row.refId)
                        assertTrue(row.factsJson.contains("WRITE"))
                    } else assertTrue(episodes.isEmpty())
                    return CoreToolResult.success("done")
                }
            }
            val memory = object : CoreTool {
                override fun declaration() = ToolSpec("memory", "test", "Memory maintenance", "test",
                    ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
                override fun effectClass() = CoreTool.EffectClass.WRITE
                override fun persistenceEffect() = CoreTool.PersistenceEffect.MEMORY
                override fun execute(arguments: Map<String, Any>, token: CancellationToken) = CoreToolResult.success("saved")
            }
            val registry = CoreToolRegistry(listOf(mixed, memory))
                .withInvocationObserver(EpisodeRecorder(context, session, owner))
            assertTrue(registry.invoke("mixed", emptyMap(), CancellationToken.cancellable(), null, "read-call").success)
            assertTrue(registry.invoke("memory", emptyMap(), CancellationToken.cancellable(), null, "memory-call").success)
            assertTrue(database.personalAgentEpisodesForSourceMessagesSync(session, listOf(owner)).isEmpty())
            assertTrue(registry.invoke("mixed", mapOf("write" to true), CancellationToken.cancellable(), null, "write-call").success)
            assertEquals(1, writes)
            val episode = database.personalAgentEpisodesForSourceMessagesSync(session, listOf(owner)).single()
            val evidence = database.personalAgentEpisodeNarrationInputSync(episode.id)!!.evidence.single()
            assertEquals("ok", evidence.outcome)
            assertTrue(evidence.factsJson.contains("WRITE"))
        }
    }
    @Test fun observedWriteWithoutDurableOwnerOrCallIdFailsBeforeEffect() {
        val files = chatStore.newFilesDirectory("missing-episode-identity")
        chatStore.localRunStore(files)
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        var effects = 0
        val tool = object : CoreTool {
            override fun declaration() = ToolSpec("write", "test", "write", "test", ToolSpec.Status.IMPLEMENTED,
                emptyMap(), emptyList())
            override fun effectClass() = CoreTool.EffectClass.WRITE
            override fun execute(arguments: Map<String, Any>, token: CancellationToken): CoreToolResult {
                effects++
                return CoreToolResult.success("must not run")
            }
        }
        for ((owner, call) in listOf("" to "call", "owner" to "")) {
            val registry = CoreToolRegistry(listOf(tool)).withInvocationObserver(EpisodeRecorder(context, "identity-test", owner))
            assertFalse(registry.invoke("write", emptyMap(), CancellationToken.cancellable(), null, call).success)
        }
        assertEquals(0, effects)
    }

}
