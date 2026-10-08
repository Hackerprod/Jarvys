package com.jarvys.agent.coding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.apkfactory.FactorySigningIdentity
import com.jarvys.agent.apkfactory.TemplateApk
import com.jarvys.agent.connectors.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FactoryProjectServiceTest {
    @get:Rule val folder=TemporaryFolder()
    private inner class Fixture {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val scope=ProjectScopeStore(folder.newFolder()).open("factory-${UUID.randomUUID()}")
        val root=scope.rootDirectory()
        @Volatile var active=true
        var requested=0
        var obtained=0
        var decision=ApprovalDecision.DENIED
        var duringApproval:()->Unit={}
        val identities=object:FactorySigningIdentity(context) {
            override fun state(appId:String)=State(false,null,0)
            override fun obtainAfterApproval(appId:String,approvedState:State):Identity { obtained++;error("No real keys are generated in this test") }
        }
        lateinit var gate:ApprovalGate
        val service:FactoryProjectService
        init {
            gate=ApprovalGate(presenter=object:ApprovalPresenter {
                override fun show(id:String,summary:ApprovalSummary) {
                    requested++;assertFalse(summary.allowAlwaysAvailable)
                    assertTrue(summary.lines.any{it.contains("non-exportable")})
                    assertTrue(summary.lines.any{it.contains("uninstalling Jarvys")})
                    duringApproval();gate.resolve(id,decision)
                }
                override fun update(id:String,decision:ApprovalDecision)=Unit
            })
            service=FactoryProjectService(context,scope,"test",gate,{active},identities)
            File(root,"web").mkdir()
            File(root,"web/index.html").writeText("<!doctype html><title>Notebook</title><script src='/factory-sdk.js'></script><script src='/www/app.js'></script>")
            File(root,"web/app.js").writeText("document.title='Notebook';")
            File(root,"icon.json").writeText("""{"schemaVersion":1,"background":"#223344","shapes":[{"type":"circle","cx":96,"cy":96,"r":64,"fill":"#FFFFFF"}]}""")
            spec("org.example.notebook",1)
        }
        fun spec(id:String,version:Int){File(root,"factory.json").writeText(JSONObject().put("schemaVersion",1).put("appId",id).put("name","Mi cuaderno")
            .put("versionCode",version).put("versionName","$version.0").put("capabilities",JSONArray(listOf("storage","export")))
            .put("webDir","web").put("icon","icon.json").toString())}
        fun build(path:String="notes.apk")=service.build("factory.json",path,scope.version(),CancellationToken.cancellable())
    }
    @Test fun completeOfflineBuildPreservesDexAndProvenanceWithNoAndroidPermissions() {
        val f=Fixture();val result=f.build();assertFalse(result.getBoolean("signed"));assertEquals(0,f.obtained)
        val apk=File(f.root,"notes.apk");assertEquals(result.getString("sha256"),ProjectScope.sha256(apk.readBytes()))
        val info=TemplateApk.inspect(apk.readBytes());assertEquals("org.example.notebook",info.appId);assertEquals("Mi cuaderno",info.label)
        assertTrue(info.permissions.isEmpty());assertEquals(1,f.scope.version())
        ZipFile(apk).use{zip->
            assertNotNull(zip.getEntry("assets/factory-provenance.json"));assertNotNull(zip.getEntry("assets/factory-sdk.js"))
            assertEquals("document.title='Notebook';",zip.getInputStream(zip.getEntry("assets/www/app.js")).bufferedReader().readText())
            val config=JSONObject(zip.getInputStream(zip.getEntry("assets/factory-app.json")).bufferedReader().readText())
            assertEquals("org.example.notebook",config.getString("appId"));assertEquals(2,config.getJSONArray("capabilities").length())
        }
    }
    @Test fun sameSourcesProduceSameUnsignedBytesAcrossDistinctOutputPaths() {
        val f=Fixture();val first=f.build("one.apk");val second=f.build("two.apk")
        assertEquals(first.getString("sha256"),second.getString("sha256"));assertArrayEquals(File(f.root,"one.apk").readBytes(),File(f.root,"two.apk").readBytes())
    }
    @Test fun twoPackageIdsAndAnUpdateHaveIndependentIdentityAndStableRuntimeDex() {
        val f=Fixture();f.build("a.apk");f.spec("org.example.other",1);f.build("b.apk");f.spec("org.example.notebook",2);f.build("a2.apk")
        val a=TemplateApk.inspect(File(f.root,"a.apk").readBytes());val b=TemplateApk.inspect(File(f.root,"b.apk").readBytes());val a2=TemplateApk.inspect(File(f.root,"a2.apk").readBytes())
        assertNotEquals(a.appId,b.appId);assertEquals(a.appId,a2.appId);assertEquals(2,a2.versionCode)
        fun dex(name:String)=ZipFile(File(f.root,name)).use{it.getInputStream(it.getEntry("classes.dex")).readBytes()}
        assertArrayEquals(dex("a.apk"),dex("b.apk"));assertArrayEquals(dex("a.apk"),dex("a2.apk"))
    }
    @Test fun collisionAndSymlinkInputsNeverOverwriteExistingContent() {
        val f=Fixture();File(f.root,"notes.apk").writeText("existing")
        assertThrows(Exception::class.java){f.build()};assertEquals("existing",File(f.root,"notes.apk").readText())
        val outside=folder.newFile().apply{writeText("outside")}
        Files.createSymbolicLink(File(f.root,"web/private.txt").toPath(),outside.toPath())
        assertThrows(Exception::class.java){f.build("new.apk")};assertFalse(File(f.root,"new.apk").exists())
    }
    @Test fun unsupportedCapabilityAndStaleProjectVersionFailBeforePublication() {
        val f=Fixture();val file=File(f.root,"factory.json");file.writeText(JSONObject(file.readText()).put("capabilities",JSONArray(listOf("camera"))).toString())
        assertThrows(Exception::class.java){f.build()};assertFalse(File(f.root,"notes.apk").exists())
        f.spec("org.example.notebook",1)
        assertThrows(Exception::class.java){f.service.build("factory.json","notes.apk",f.scope.version()+1,CancellationToken.cancellable())}
    }
    @Test fun cancelledAndRevokedRunsCannotBuild() {
        val f=Fixture();val token=CancellationToken.cancellable();token.cancel()
        assertThrows(Exception::class.java){f.service.build("factory.json","notes.apk",0,token)}
        f.active=false;assertThrows(Exception::class.java){f.build()};assertFalse(File(f.root,"notes.apk").exists())
    }
    @Test fun signingDenialDoesNotCreateAKeyOrOutputAndKeepsUnsignedApk() {
        val f=Fixture();val build=f.build()
        assertThrows(Exception::class.java){f.service.sign("notes.apk",build.getString("sha256"),"signed.apk",f.scope.version(),CancellationToken.cancellable())}
        assertEquals(1,f.requested);assertEquals(0,f.obtained);assertFalse(File(f.root,"signed.apk").exists());assertTrue(File(f.root,"notes.apk").isFile)
    }
    @Test fun modifiedArtifactOrWrongProjectCannotBorrowBuildReceipt() {
        val f=Fixture();val build=f.build();File(f.root,"notes.apk").appendText("changed")
        assertThrows(Exception::class.java){f.service.sign("notes.apk",build.getString("sha256"),"signed.apk",f.scope.version(),CancellationToken.cancellable())}
        assertEquals(0,f.requested)
        val other=Fixture();File(f.root,"notes.apk").copyTo(File(other.root,"copied.apk"))
        assertThrows(Exception::class.java){other.service.sign("copied.apk",build.getString("sha256"),"signed.apk",other.scope.version(),CancellationToken.cancellable())}
        assertEquals(0,other.requested)
    }
    @Test fun revocationDuringApprovalStopsBeforeKeyCreation() {
        val f=Fixture();val build=f.build();f.decision=ApprovalDecision.APPROVED;f.duringApproval={f.active=false}
        assertThrows(Exception::class.java){f.service.sign("notes.apk",build.getString("sha256"),"signed.apk",f.scope.version(),CancellationToken.cancellable())}
        assertEquals(1,f.requested);assertEquals(0,f.obtained);assertFalse(File(f.root,"signed.apk").exists())
    }
    @Test fun cancelledWhileWaitingForGlobalSigningLockCannotCreateIdentity() {
        val f=Fixture(); val build=f.build(); f.decision=ApprovalDecision.APPROVED
        val lock=FactoryProjectService::class.java.getDeclaredField("SIGN_LOCK").apply{isAccessible=true}.get(null)
        val held=java.util.concurrent.CountDownLatch(1); val release=java.util.concurrent.CountDownLatch(1)
        val holder=java.util.concurrent.Executors.newSingleThreadExecutor()
        val signer=java.util.concurrent.Executors.newSingleThreadExecutor()
        val token=CancellationToken.cancellable()
        f.duringApproval={holder.submit{ synchronized(lock) {held.countDown(); release.await()} }; assertTrue(held.await(5,java.util.concurrent.TimeUnit.SECONDS))}
        try {
            val future=signer.submit<Boolean>{
                try {f.service.sign("notes.apk",build.getString("sha256"),"signed.apk",f.scope.version(),token);false}
                catch (_:Exception){true}
            }
            assertTrue(held.await(5,java.util.concurrent.TimeUnit.SECONDS)); token.cancel();release.countDown()
            assertTrue(future.get(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,f.obtained)
            assertFalse(File(f.root,"signed.apk").exists())
        } finally {release.countDown();holder.shutdownNow();signer.shutdownNow()}
    }
    @Test fun emptyDirectoryTreeCannotBypassTraversalQuota() {
        val f=Fixture()
        for(i in 0..256) File(f.root,"web/dir$i").mkdir()
        assertThrows(Exception::class.java){f.build()};assertFalse(File(f.root,"notes.apk").exists())
    }
    @Test fun revalidationHashRemainsBoundedWhenFileGrows() {
        val f=Fixture();File(f.root,"grow.txt").writeBytes(ByteArray(1025))
        assertThrows(Exception::class.java){FactoryProjectFiles.sha(f.scope,"grow.txt",1024,CancellationToken.cancellable())}
    }
    @Test fun toolSchemaRejectsExtraFieldsAndCannotDelegate() {
        val f=Fixture();val tool=ApkFactoryTools.Tool(f.service)
        assertFalse(tool.canDelegate());assertFalse(tool.execute(mapOf("action" to "inspect","command" to "echo bad"),CancellationToken.cancellable()).success)
        assertTrue(tool.execute(mapOf("action" to "inspect"),CancellationToken.cancellable()).success)
    }
}
