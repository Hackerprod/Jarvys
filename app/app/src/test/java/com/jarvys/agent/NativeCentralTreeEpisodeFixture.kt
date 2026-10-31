package com.jarvys.agent

import android.content.Context
import com.jarvys.agent.crew.*
import com.jarvys.agent.workspaces.WorkspaceIdentity
import java.util.UUID

/** Real USER/root/process authority and a distinct committed invocation for each scripted step. */
object NativeCentralTreeEpisodeFixture {
    class Frame(val invocation: EffectInvocation, val authority: CentralTreeAdmission, val token: CancellationToken) {
        var result: CoreToolResult? = null
    }
    @JvmStatic fun run(context: Context, token: CancellationToken, text: String, steps: List<(Frame) -> Unit>) {
        val store = LocalRunStore(context)
        val session = WorkspaceIdentity.CENTRAL_SESSION
        val user = store.appendConversationMessage(session, "user", text)
        val run = UUID.randomUUID().toString()
        val writer = MainChatTranscriptStore(context.filesDir, session, store)
        CentralTreeAdmission.beginUser(context, store, session, user, token).use { authority ->
            var step = 0
            var dispatched = 0
            var failure: Throwable? = null
            val probe = object : CoreTool {
                override fun declaration() = ToolSpec("native_tree_probe", "test", "Native tree fixture", "test", ToolSpec.Status.IMPLEMENTED, emptyMap(), emptyList())
                override fun effectClass() = CoreTool.EffectClass.WRITE
                override fun execute(args: Map<String, Any>?, token: CancellationToken): CoreToolResult = error("execute4 required")
                override fun execute(args: Map<String, Any>?, token: CancellationToken, progress: CoreTool.ProgressListener?, invocation: EffectInvocation?): CoreToolResult {
                    val actual = requireNotNull(invocation)
                    requireNotNull(actual.centralSource()).requireInvocationCurrent()
                    authority.requireInvocationCurrent(token)
                    val frame = Frame(actual, authority, token)
                    try { steps[dispatched++](frame) }
                    catch (caught: Throwable) { failure = caught }
                    return frame.result ?: CoreToolResult.failure("Fixture callback completed without accepted effect")
                }
            }
            val registry = CoreToolRegistry(listOf(probe)).withInvocationSourceProvider(writer.centralSourceProvider(user, run))
            val loop = CoreAgentLoop(CoreAgentLoop.Model { _, _, _, _ ->
                if (failure == null && step < steps.size) ModelReply("", listOf(ModelReply.Call("native-step-${step++}", "native_tree_probe", emptyMap())))
                else ModelReply("Fixture finished", emptyList())
            }, registry, "Use native fixture authority", session)
            writer.attach(loop, emptyList(), user, run)
            loop.run(text, emptyList(), emptyList(), token, null, user, run)
            failure?.let { throw it }
            check(dispatched == steps.size) { "Not every native invocation reached its assertion" }
        }
    }

    /** Preserve fake provider/barriers; install the same durable adapters as CoreAgentRuntime. */
    @JvmStatic fun installManager(context: Context, session: String, manager: CrewManager) {
        val role = CrewRole("native-fixture-generalist", "Synthetic worker", "general", "Synthetic general-purpose work", emptyList(), null,
            "Synthetic native profile", 1, emptyList(), CrewProfile.WorkspaceMode.LEGACY_CHAT)
        manager.configureProfiles({ id -> if (id == role.id) role else null }, emptyList())
        manager.configureCheckpoints(CrewCheckpointCoordinator(context, session) { bot -> bot.role })
        WorkspaceTreeWorkerCoordinator(context, session, manager).install()
    }
    @JvmStatic fun installCheckpoints(context: Context, session: String, manager: CrewManager) {
        manager.configureCheckpoints(CrewCheckpointCoordinator(context, session) { bot -> bot.role })
    }
}
