package com.jarvys.agent

/** Shared write/edit/delete classes use this after their existing project/capability routing. */
internal object MemoryToolActions {
    @JvmStatic fun <T> run(workspace: WorkspaceStore, operation: NativeActionPolicy.Operation,
        path: String, arguments: List<String>, token: CancellationToken, effect: java.util.concurrent.Callable<T>): T {
        val selected = workspace.memoryStoreForNativeAction(path) ?: return effect.call()
        val exactPath = workspace.normalizedNativeMemoryPath(path)
        return MemoryNativeActions.forTool(selected).prepare(operation, selected, listOf(exactPath) + arguments, token).use {
            // No project/family lock is held during ASK. Store-lock admission occurs at mutateLocked.
            it.executeUnlocked({ check(workspace.memoryStoreForNativeAction(exactPath) === selected) }) { effect.call() }
        }
    }
}
