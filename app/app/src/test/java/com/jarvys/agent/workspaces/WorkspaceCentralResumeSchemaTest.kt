package com.jarvys.agent.workspaces

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.jarvys.agent.ChatTestStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,shadows=[com.jarvys.agent.ArtifactOsShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceCentralResumeSchemaTest {
    @get:Rule val chat=ChatTestStore(registerApplicationFilesDirectory=true)
    private lateinit var context:Application
    private val opened=ArrayList<WorkspaceRegistry>()
    @Before fun setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)}
    @After fun cleanup(){opened.forEach{it.close()};context.deleteDatabase(WorkspaceRegistry.DATABASE_NAME)}
    private fun open()=WorkspaceRegistry(context).also{opened.add(it)}
    private fun raw()=SQLiteDatabase.openDatabase(context.getDatabasePath(WorkspaceRegistry.DATABASE_NAME).path,null,SQLiteDatabase.OPEN_READWRITE)
    private fun snapshots():Map<String,List<List<String?>>> = raw().use{db->
        val names=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite%' AND name!='android_metadata' AND name!='bot_tree_central_resume' ORDER BY name",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        names.associateWith{table-> db.rawQuery("SELECT * FROM $table",null).use{c->buildList{while(c.moveToNext())add((0 until c.columnCount).map{if(c.isNull(it))null else c.getString(it)})}}}
    }
    @Test fun offGateDoesNotUpgradeAndExplicitUpgradeConservesPopulatedV2(){
        val registry=open();val workspace=registry.createWorkspace("retained","Retained")
        registry.prepareAssignment(workspace.workspaceId,"old","request","user","generalist","mission",null,null,com.jarvys.agent.crew.CrewMissionAccess.STANDARD)
        registry.upgradeTreeSchema();val cached=open();assertEquals(2,cached.schemaVersion())
        assertFalse(com.jarvys.agent.CentralAvailabilityPolicy.registryMigrationEnabled())
        val before=snapshots();registry.upgradeCentralResumeSchema()
        assertEquals(before,snapshots());assertEquals(3,cached.schemaVersion())
        registry.upgradeCentralResumeSchema();assertEquals(before,snapshots())
        raw().use{db->db.rawQuery("SELECT count(*) FROM bot_tree_central_resume",null).use{assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}}
    }
    @Test fun directV1UpgradeRetainsWorkspaceAndDoesNotEnrollHistory(){
        val registry=open();val workspace=registry.createWorkspace("v1","Preserve")
        registry.upgradeCentralResumeSchema();assertEquals(workspace,open().getWorkspace(workspace.workspaceId))
        raw().use{db->for(table in listOf("bot_tree_root","bot_tree_node","bot_tree_central_resume"))db.rawQuery("SELECT count(*) FROM $table",null).use{it.moveToFirst();assertEquals(0,it.getInt(0))}}
    }
    @Test fun hiddenAndroidMetadataTriggerRejectedBeforeAnyUpgradeWrite(){rejectTrigger("android_metadata")}
    @Test fun sqliteWildcardLikeNameTriggerRejectedBeforeAnyUpgradeWrite(){rejectTrigger("sqliteXreview")}
    private fun rejectTrigger(name:String){
        val registry=open();val workspace=registry.createWorkspace("trigger","Retained")
        registry.upgradeTreeSchema();registry.close()
        raw().use{db->if(name=="android_metadata")db.execSQL("DROP TABLE IF EXISTS android_metadata")
            db.execSQL("CREATE TRIGGER $name AFTER INSERT ON bot_tree_event BEGIN DELETE FROM workspace; END")}
        try{open().upgradeCentralResumeSchema();fail("Unknown trigger must reject before write")}catch(expected:WorkspaceRegistryCorruptException){}
        raw().use{db->assertEquals(2,db.version);db.rawQuery("SELECT workspace_id FROM workspace",null).use{assertTrue(it.moveToFirst());assertEquals(workspace.workspaceId,it.getString(0))}
            db.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='bot_tree_central_resume'",null).use{it.moveToFirst();assertEquals(0,it.getInt(0))}}
    }
    @Test fun unknownResumeSchemaObjectFailsWithoutDeletion(){
        val registry=open();registry.createWorkspace("unknown","Retained");registry.upgradeTreeSchema();registry.close()
        raw().use{it.execSQL("CREATE TABLE bot_tree_central_resume(fake TEXT)")}
        try{open().upgradeCentralResumeSchema();fail("Unknown table must reject")}catch(expected:WorkspaceRegistryCorruptException){}
        raw().use{db->assertEquals(2,db.version);db.rawQuery("PRAGMA table_info(bot_tree_central_resume)",null).use{assertTrue(it.moveToFirst());assertEquals("fake",it.getString(1))}}
    }
}
