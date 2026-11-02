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
        // The real Application may already populate its own databases during startup.
        // Use untouched isolated paths; do not delete or suppress that normal bootstrap.
        val files=chat.newFilesDirectory("lazy-inspection")
        val isolated=object:android.content.ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir():java.io.File=files
            override fun getDatabasePath(name:String):java.io.File=java.io.File(java.io.File(files,"databases"),name)
        }
        val file=java.io.File(java.io.File(java.io.File(files,"jarvys"),StorageGeneration.NAME),"agent.db")
        val registry=isolated.getDatabasePath(WorkspaceRegistry.DATABASE_NAME)
        assertFalse("isolated Room fixture must start absent",file.exists())
        assertFalse("isolated Registry fixture must start absent",registry.exists())
        try {
            val bridge=ChatDatabaseBridge.getInstance(isolated)
            assertFalse(bridge.centralSubmissionSchemaReadySync())
            val events=listOf(AgentRunUiEvent.messageEvent(1,"user","retained UI row",1))
            assertEquals(events,CentralAvailabilityUiProjection.project(isolated,"jarvys-central",events))
            assertFalse("inspection created Room",file.exists())
            assertFalse("inspection created Registry",registry.exists())
        } finally {chat.onDatabaseWorker {ChatDatabaseBridge.closeInstanceForTests(files)}}
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
