package com.jarvys.agent.workspaces

import android.app.Application
import com.jarvys.agent.CoreAgentRuntime
import com.jarvys.agent.ChatTestStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceCatalogPageTest {
    @get:Rule val chatTestStore = ChatTestStore(runTestOnWorker = false, registerApplicationFilesDirectory = true)
    @Test fun catalogHasTwoBoundedPagesWithoutPreparingAnyCrewRuntime() {
        val context = RuntimeEnvironment.getApplication()
        WorkspaceRegistry(context).use { store ->
            val expected = List(51) { store.createWorkspace("catalog-$it", "Workspace $it") }
            expected.forEach { assertNull(CoreAgentRuntime.crewManagerForSession(it.executionSessionId)) }
            val first = WorkspaceProjection.sidebarPage(context)
            assertEquals(50, first.rows.size)
            val second = WorkspaceProjection.sidebarPage(context, requireNotNull(first.nextCursor))
            assertEquals(1, second.rows.size)
            assertNull(second.nextCursor)
            assertEquals(expected.map { it.workspaceId }.toSet(), (first.rows + second.rows).map { it.id }.toSet())
            assertEquals(first.rows.map { it.id }, WorkspaceProjection.sidebarPage(context).rows.map { it.id })
            expected.forEach { assertNull("Catalog must not initialize workers", CoreAgentRuntime.crewManagerForSession(it.executionSessionId)) }
        }
    }
}
