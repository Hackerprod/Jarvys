package com.jarvys.agent.workspaces

import android.app.Application
import com.jarvys.agent.crew.CrewMissionAccess
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [com.jarvys.agent.ArtifactOsShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspacePendingProjectionQueryTest {
    @Test fun pendingQueryIsExactCurrentRevisionAndCannotHideOlderUncertainEffects() {
        val context = RuntimeEnvironment.getApplication()
        WorkspaceRegistry(context).use { store ->
            val scope = store.createWorkspace("pending-query", "Pending query")
            val prepared = store.prepareAssignment(scope.workspaceId, "assignment", "request", "Synthetic user task",
                "coding", "Synthetic mission", listOf("read"), "Worker", CrewMissionAccess.STANDARD)
            val started = store.markStarted(scope.workspaceId, prepared.assignmentId, prepared.revision, "process")
            val target = store.bindBot(scope.workspaceId, prepared.assignmentId, started.revision, "worker")
            val oldPending = store.nativeControls().submit(UUID.randomUUID().toString(), scope.workspaceId,
                target.assignmentId, target.botId!!, target.revision, WorkspaceControlType.CORRECT, "Pending correction")
            assertEquals(oldPending.requestId, store.getPendingControl(scope.workspaceId, target.assignmentId, target.revision)?.requestId)
            assertNull(store.getUnsettledControl(scope.workspaceId, target.assignmentId))
            val pause = store.nativeControls().submit(UUID.randomUUID().toString(), scope.workspaceId,
                target.assignmentId, target.botId, target.revision, WorkspaceControlType.PAUSE, "Pause")
            val admittedPause = store.markControlStarted(pause.requestId, pause.revision, "process")
            store.markControlUncertain(admittedPause.requestId, admittedPause.revision)
            val current = requireNotNull(store.getAssignment(scope.workspaceId, target.assignmentId))
            assertNull(store.getPendingControl(scope.workspaceId, target.assignmentId, target.revision))
            assertNull(store.getPendingControl(scope.workspaceId, target.assignmentId, current.revision))
            val newPending = store.nativeControls().submit(UUID.randomUUID().toString(), scope.workspaceId,
                current.assignmentId, current.botId!!, current.revision, WorkspaceControlType.CORRECT, "Saved later")
            assertEquals(newPending.requestId, store.getPendingControl(scope.workspaceId, current.assignmentId, current.revision)?.requestId)
            assertEquals(pause.requestId, store.getUnsettledControl(scope.workspaceId, target.assignmentId)?.requestId)
            assertEquals(WorkspaceControlState.PENDING, store.getControl(oldPending.requestId)?.state)
        }
    }
}
