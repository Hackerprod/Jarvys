package com.jarvys.agent

internal object MemoryToolActions {
    @JvmStatic fun <T> run(workspace: WorkspaceStore, path: String, token: CancellationToken,
        effect: java.util.concurrent.Callable<T>): T {
        val selected = workspace.memoryStoreForNativeAction(path) ?: return effect.call()
        val exactPath = workspace.normalizedNativeMemoryPath(path)
        return MemoryNativeActions.forTool(selected).run(selected, token,
            { check(workspace.memoryStoreForNativeAction(exactPath) === selected) }) { effect.call() }
    }
}
