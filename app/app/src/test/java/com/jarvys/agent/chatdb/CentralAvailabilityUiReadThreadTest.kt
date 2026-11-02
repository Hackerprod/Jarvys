package com.jarvys.agent.chatdb

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.*
import com.jarvys.agent.workspaces.WorkspaceRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],shadows=[ArtifactOsShadow::class])
class CentralAvailabilityUiReadThreadTest {
    @get:Rule val chat=ChatTestStore(runTestOnWorker=false,registerApplicationFilesDirectory=true)
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    @Test fun existingUiFixtureDispatchesReadOnlyInspectionWithoutCreatingEitherDatabase() {
        assertSame(Looper.getMainLooper(),Looper.myLooper())
        val file=java.io.File(StorageGeneration(context).chatRoot(),"agent.db")
        val registry=context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME)
        assertFalse(file.exists());assertFalse(registry.exists())
        val bridge=ChatDatabaseBridge.getInstance(context)
        assertFalse(bridge.centralSubmissionSchemaReadySync())
        val events=listOf(AgentRunUiEvent.messageEvent(1,"user","retained UI row",1))
        assertEquals(events,CentralAvailabilityUiProjection.project(context,"jarvys-central",events))
        assertFalse(file.exists());assertFalse(registry.exists())
    }
    @Test fun newInspectionEntryDoesNotRelaxExistingProductionMainThreadGuard() {
        assertSame(Looper.getMainLooper(),Looper.myLooper())
        val old=System.getProperty("jarvys.unitTests.mainThreadGuardOff")
        val disabled=ChatDatabaseBridge.mainThreadGuardDisabledForTests
        try {
            System.clearProperty("jarvys.unitTests.mainThreadGuardOff")
            ChatDatabaseBridge.mainThreadGuardDisabledForTests=false
            val bridge=ChatDatabaseBridge.getInstance(context)
            val error=assertThrows(IllegalStateException::class.java) {bridge.centralSubmissionSchemaReadySync()}
            assertEquals("ChatDatabaseBridge blocking methods must not be called from the main thread",error.message)
        } finally {
            ChatDatabaseBridge.mainThreadGuardDisabledForTests=disabled
            if(old==null)System.clearProperty("jarvys.unitTests.mainThreadGuardOff") else System.setProperty("jarvys.unitTests.mainThreadGuardOff",old)
        }
    }
}
