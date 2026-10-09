package com.jarvys.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.connectors.ApprovalDecision
import com.jarvys.agent.connectors.ApprovalSummary
import com.jarvys.agent.crew.CrewManager
import com.jarvys.agent.crew.CrewProfile
import com.jarvys.agent.crew.CrewProfileRepository
import com.jarvys.agent.proactive.ProactiveConversation
import com.jarvys.agent.tasks.ScheduledTaskConversation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CancellationException

/** Real product compiler→private store→catalog integration, without claiming Android playback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class BotMascotToolTest {
    @get:Rule val temporary=TemporaryFolder()
    private var previousSecrets: Any? = null
    @Before fun isolateSecretStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val field = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
        previousSecrets = field.get(null)
        field.set(null, SecretStore(context.getSharedPreferences("mascot-tools-fixture", Context.MODE_PRIVATE)))
    }
    @After fun restoreSecretStore() {
        SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }.set(null, previousSecrets)
    }
    private val repository by lazy { CrewProfileRepository(temporary.root) }
    private val store by lazy { BotMascotStoreTestSupport.store(temporary.root) }
    private var approvals=0
    private var decision=ApprovalDecision.APPROVED
    private var approvalEffect:((CancellationToken)->Unit)?=null
    private val summaries=mutableListOf<ApprovalSummary>()
    private fun source(name:String="nimbo")=requireNotNull(javaClass.getResourceAsStream("/bot-mascot-scenes/$name.json")).bufferedReader().use { it.readText() }
    private fun service(session:String="mascot-main")=BotMascotService(repository,store,session,{ summary,token ->
        approvals++;summaries+=summary;approvalEffect?.invoke(token);decision
    })
    private fun bot()=repository.create(CrewProfile("custom-mascot-tool",1,"Cloud","Custom helper","Follow the requested objective.",emptyList(),listOf("board_read")),listOf("board_read"),emptyList())
    private fun args(id:String="custom-mascot-tool",revision:Int=1,request:String="mascot-1",design:String="nimbo")=linkedMapOf<String,Any>(
        "request_id" to request,"bot_id" to id,"expected_revision" to revision,"visual_description" to "An original floating cloud with expressive dew drops and distinct state poses.","scene_json" to source(design))
    private fun createArgs()=linkedMapOf<String,Any>("request_id" to "create-mascot","name" to "Cloud","description" to "Custom helper",
        "instructions" to "Follow the user objective and explain evidence in English.","capabilities" to listOf("board_read"),"skill_ids" to emptyList<String>(),
        "workspace_mode" to "legacy_chat","icon_prompt" to "A friendly original cloud","visual_description" to "A cloud with moving droplets and distinct calm state poses.","mascot_scene_json" to source())
    private fun creator(failMascot:Boolean=false)=BotCreationTool(BotCreationService(repository,"mascot-main",{listOf("board_read")},{emptyList()},
        { summary,token->approvals++;summaries+=summary;approvalEffect?.invoke(token);decision },{false},
        { _,_,_,_->throw AssertionError("No image service should run") },
        { id,revision,request,description,scene,token->
            if(failMascot)throw IllegalStateException("private failure")
            service().assign(id,revision,request,description,scene,token,false).definition
        }))

    @Test fun compilesOriginalSourceLocallyAndPersistsHonestIndependentVisualMetadata() {
        val original=bot();val result=BotMascotTool(service()).execute(args(),CancellationToken.uncancellable())
        assertTrue(result.content,result.success);val value=JSONObject(result.content)
        assertTrue(value.getBoolean("mascot_compiled"));assertFalse(value.getBoolean("android_playback_verified"));assertEquals("LOCAL_COMPILED",value.getString("mascot_status"))
        val saved=CrewProfileRepository(temporary.root).definition(original.id)
        assertEquals(original.profile.version,saved.profile.version);assertEquals(original.profile.prompt,saved.profile.prompt);assertEquals(original.profile.capabilities,saved.profile.capabilities)
        assertEquals(2,saved.revision);assertNotNull(saved.mascot)
        val bytes=store.read(saved.id,saved.mascot)
        assertArrayEquals(source().toByteArray(Charsets.UTF_8),bytes.source());assertArrayEquals(BotMascotSceneCompiler.compileProduct(source()).bytes,bytes.riv())
        assertEquals(1,approvals);assertFalse(summaries.single().allowAlwaysAvailable)
    }
    @Test fun durableRetryDoesNotApproveOrAssignAgainEvenAfterLaterIconEdit() {
        bot();val tool=BotMascotTool(service());assertTrue(tool.execute(args(),CancellationToken.uncancellable()).success)
        val assigned=repository.definition("custom-mascot-tool");repository.setIcon(assigned.id,assigned.revision,"12345678-1234-1234-1234-123456789abc.png")
        val result=JSONObject(tool.execute(args(),CancellationToken.uncancellable()).content)
        assertTrue(result.getBoolean("already_existed"));assertEquals(3,result.getInt("revision"));assertEquals(2,result.getInt("assigned_revision"));assertEquals(1,approvals)
        assertEquals(1,repository.definition(assigned.id).mascotReceipts.size)
    }
    @Test fun reusedOperationWithChangedSourceOrDescriptionCannotOverwrite() {
        bot();val tool=BotMascotTool(service());assertTrue(tool.execute(args(),CancellationToken.uncancellable()).success)
        val first=repository.definition("custom-mascot-tool").mascot
        assertFalse(tool.execute(args(revision=2,design="folio"),CancellationToken.uncancellable()).success)
        assertFalse(tool.execute(args(revision=2)+("visual_description" to "Changed design request"),CancellationToken.uncancellable()).success)
        assertEquals(first,repository.definition("custom-mascot-tool").mascot);assertEquals(1,approvals)
    }
    @Test fun staleRevisionAndBuiltInOrDisabledBotDoNotCompileOrApproveAssignment() {
        val original=bot();repository.setEnabled(original.id,original.revision,false);val tool=BotMascotTool(service())
        assertFalse(tool.execute(args(),CancellationToken.uncancellable()).success)
        assertFalse(tool.execute(args(id="coding"),CancellationToken.uncancellable()).success);assertEquals(0,approvals)
    }
    @Test fun deniedOrCancelledReviewNeverChangesVisual() {
        bot();decision=ApprovalDecision.DENIED;assertFalse(BotMascotTool(service()).execute(args(),CancellationToken.uncancellable()).success)
        assertNull(repository.definition("custom-mascot-tool").mascot)
        decision=ApprovalDecision.APPROVED;approvalEffect={it.cancel()}
        assertThrows(CancellationException::class.java){BotMascotTool(service()).execute(args(),CancellationToken.cancellable())}
        assertNull(repository.definition("custom-mascot-tool").mascot)
    }
    @Test fun concurrentEditDuringReviewWinsAndNoOrphanPackageIsPublished() {
        val original=bot();approvalEffect={repository.setIcon(original.id,1,"12345678-1234-1234-1234-123456789abc.png")}
        assertFalse(BotMascotTool(service()).execute(args(),CancellationToken.uncancellable()).success)
        val saved=repository.definition(original.id);assertEquals(2,saved.revision);assertNull(saved.mascot);assertTrue(saved.iconRef.isNotEmpty())
    }
    @Test fun rejectsLegacySceneUnknownArgumentsAndNonintegralRevision() {
        bot();val tool=BotMascotTool(service())
        for(invalid in listOf(args()+("scene_json" to source("miga")),args()+("path" to "/untrusted"),args()+("expected_revision" to 1.5),args()+("visual_description" to "line\nbreak")))
            assertFalse(tool.execute(invalid,CancellationToken.uncancellable()).success)
        assertEquals(0,approvals);assertNull(repository.definition("custom-mascot-tool").mascot)
    }
    @Test fun scopeIsMainChatOnlyWithoutImageAccessOrDelegationAuthority() {
        val tool=BotMascotTool(service());assertFalse(tool.canDelegate());assertFalse(tool.execute(args(),CancellationToken.crewChild()).success)
        for(session in listOf("",ProactiveConversation.SESSION_ID,ScheduledTaskConversation.SESSION_ID))assertFalse(BotMascotTool(service(session)).execute(args(),CancellationToken.uncancellable()).success)
        assertTrue(CoreToolRegistry(listOf(tool)).forDelegatedAgent().names().isEmpty())
        assertTrue(CoreAgentRuntime.crewBotCapabilityScope(CoreToolRegistry(listOf(tool))).names().isEmpty());assertTrue(CrewManager.isCaptainOnly(BotMascotTool.NAME))
        val context=ApplicationProvider.getApplicationContext<Context>();assertTrue(CoreAgentRuntime(context,"main",emptyList()).createTools().names().contains(BotMascotTool.NAME))
        assertFalse(CoreAgentRuntime.profileCapabilities(context,"main").contains(BotMascotTool.NAME));assertEquals(0,approvals)
    }
    @Test fun auditResultAndCatalogOmitSourceDescriptionAndPrivatePaths() {
        bot();val tool=BotMascotTool(service());val result=tool.execute(args(),CancellationToken.uncancellable());assertTrue(result.success)
        val catalog=BotCatalogTool(repository,"main").execute(emptyMap(),CancellationToken.uncancellable())
        for(text in listOf(result.content,catalog.content,tool.auditDetail(args()))){assertFalse(text.contains(source()));assertFalse(text.contains(args()["visual_description"] as String));assertFalse(text.contains("bot_mascots"));assertFalse(text.contains("source.json"))}
        assertTrue(catalog.content.contains("LOCAL_COMPILED"));assertFalse(catalog.content.contains("Follow the requested objective"))
    }
    @Test fun createBotAuthorsMascotWithoutImageAccessAndReviewsOnlyOnce() {
        val result=creator().execute(createArgs(),CancellationToken.uncancellable());assertTrue(result.content,result.success)
        val value=JSONObject(result.content);assertTrue(value.getBoolean("saved"));assertTrue(value.getBoolean("mascot_compiled"));assertFalse(value.getBoolean("icon_complete"));assertEquals(1,approvals)
        val saved=repository.definition(value.getString("bot_id"));assertNotNull(saved.mascot);assertEquals(1,saved.profile.version);assertEquals(2,saved.revision)
        val retry=JSONObject(creator().execute(createArgs(),CancellationToken.uncancellable()).content);assertTrue(retry.getBoolean("already_existed"));assertEquals(1,approvals);assertEquals(1,repository.definitions().count{!it.builtIn})
    }
    @Test fun creationVisualFailureKeepsSavedBotAndExplicitIncompleteStatus() {
        val result=creator(true).execute(createArgs(),CancellationToken.uncancellable());assertTrue(result.success)
        val value=JSONObject(result.content);assertTrue(value.getBoolean("saved"));assertFalse(value.getBoolean("mascot_compiled"));assertEquals("failed",value.getString("mascot_status"));assertFalse(result.content.contains("private failure"));assertEquals(1,repository.definitions().count{!it.builtIn})
    }
    @Test fun changedCreationSceneCannotReuseOperationAndInvalidSceneSavesNothing() {
        assertFalse(creator().execute(createArgs()+("mascot_scene_json" to source("miga")),CancellationToken.uncancellable()).success)
        assertEquals(0,approvals);assertEquals(0,repository.definitions().count{!it.builtIn})
        assertTrue(creator().execute(createArgs(),CancellationToken.uncancellable()).success)
        assertFalse(creator().execute(createArgs()+("mascot_scene_json" to source("folio")),CancellationToken.uncancellable()).success)
        assertEquals(1,approvals);assertEquals(1,repository.definitions().count{!it.builtIn})
    }
    @Test fun mascotFailureAfterConcurrentEditNeverRebasesTheApprovedIconWrite() {
        var images=0
        val tool=BotCreationTool(BotCreationService(repository,"mascot-main",{listOf("board_read")},{emptyList()},
            { _,_->ApprovalDecision.APPROVED },{true},
            { _,_,_,_->images++;throw AssertionError("Concurrent icon must be preserved") },
            { id,revision,_,_,_,_->repository.setIcon(id,revision,"12345678-1234-1234-1234-123456789abc.png");throw IllegalStateException("CAS changed") }))
        val result=tool.execute(createArgs(),CancellationToken.uncancellable());assertTrue(result.content,result.success)
        val saved=repository.definitions().single{!it.builtIn};assertEquals(2,saved.revision);assertEquals("12345678-1234-1234-1234-123456789abc.png",saved.iconRef);assertEquals(0,images)
    }
    @Test fun invalidDescriptionControlsAndSurrogatesFailBeforeApprovalOrDefinitionSave() {
        for(description in listOf("Bad\u0085description","Bad\ud800description")) {
            assertFalse(creator().execute(createArgs()+("visual_description" to description),CancellationToken.uncancellable()).success)
        }
        assertEquals(0,approvals);assertEquals(0,repository.definitions().count{!it.builtIn})
        bot()
        for(description in listOf("Bad\u0085description","Bad\ud800description"))
            assertFalse(BotMascotTool(service()).execute(args()+("visual_description" to description),CancellationToken.uncancellable()).success)
        assertEquals(0,approvals)
    }

}
