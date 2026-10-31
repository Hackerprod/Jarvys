package com.jarvys.agent

import com.jarvys.agent.chatdb.MemoryNamespace
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Current file/Engram separation; no legacy file-memory mutation implementation is restored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NativeMemoryActionRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val token = CancellationToken.uncancellable()
    private fun workspace() = WorkspaceStore(temporary.newFolder(), WorkspaceStore.projectIdForSession("session"))

    @Test fun fileToolsCannotWriteEditReadOrDeleteEngramThroughRemovedMemoryPaths() {
        val workspace = workspace()
        val tools = CoreToolRegistry(WorkspaceTools.createWithSearchForTests(workspace, null))
        for (name in listOf("write", "edit", "read")) {
            val result = tools.invoke(name, mapOf("path" to "/memory/new.md", "content" to "first",
                "old_text" to "first", "new_text" to "updated"), token)
            assertFalse("$name must not expose file memory", result.success)
        }
        assertFalse(tools.names().contains("delete"))
        workspace.write("ordinary.txt", "ordinary")
        assertEquals("ordinary", workspace.read("ordinary.txt"))
        assertThrows(IllegalArgumentException::class.java) { workspace.read("/memory/new.md") }
    }

    @Test fun currentEngramScopeSelectionCannotGrantUnknownProjectOwnership() {
        val namespace = MemoryNamespace.central()
        assertEquals(MemoryNamespace.CENTRAL, namespace.project)
        assertEquals("project", namespace.scope)
        assertThrows(IllegalArgumentException::class.java) { namespace.selectWrite("foreign-project", "project") }
        assertEquals(MemoryNamespace.CENTRAL, namespace.selectWrite(null, "global").project)
        assertThrows(IllegalArgumentException::class.java) { namespace.selectWrite("foreign-project", "global") }
        assertEquals(MemoryNamespace.CENTRAL, namespace.selectWrite(null, "personal").project)
        assertEquals("personal", namespace.selectWrite(null, "personal").scope)
    }

    @Test fun exactOrdinaryToolContentIsCapturedOnceBeforeEffect() {
        val workspace = workspace()
        var contentReads = 0
        val args = object : LinkedHashMap<String, Any>() {
            override fun get(key: String): Any? = if (key == "content") {
                contentReads++; if (contentReads == 1) "captured" else "changed"
            } else super.get(key)
        }.apply { put("path", "note.md"); put("content", "placeholder") }
        val tools = CoreToolRegistry(WorkspaceTools.createWithSearchForTests(workspace, null))
        val result = tools.invoke("write", args, token)
        assertTrue(result.content, result.success)
        assertEquals(1, contentReads)
        assertEquals("captured", workspace.read("note.md"))
    }

    @Test fun cancelledNativeFileActionDoesNotRun() {
        val workspace = workspace()
        val tools = CoreToolRegistry(WorkspaceTools.createWithSearchForTests(workspace, null))
        val cancelled = CancellationToken.cancellable().also { it.cancel() }
        assertThrows(CancellationException::class.java) {
            tools.invoke("write", mapOf("path" to "must-not-exist.md", "content" to "cancelled"), cancelled)
        }
        assertThrows(IllegalArgumentException::class.java) { workspace.read("must-not-exist.md") }
    }

    @Test fun currentMemoryViewAuthorityChangeInvalidatesQueuedActionWithoutEffect() {
        val selected = AtomicBoolean(true)
        val executed = AtomicBoolean(false)
        MemoryUiAutomationGuard.registerViewAuthority { selected.get() }.use {
            val epoch = MemoryUiAutomationGuard.captureAutomationEpoch()
            selected.set(false)
            MemoryUiAutomationGuard.invalidateQueuedAutomation()
            assertFalse(MemoryUiAutomationGuard.runAutomated(epoch) { executed.set(true) })
            assertFalse(executed.get())
        }
    }
}
