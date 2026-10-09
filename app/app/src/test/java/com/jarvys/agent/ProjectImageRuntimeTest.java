package com.jarvys.agent;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.jarvys.agent.coding.CodingAgentInstructions;
import com.jarvys.agent.crew.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.*;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProjectImageRuntimeTest {
    private Context context; private String session; private Object previous; private ProviderSettings settings;
    private final CoreToolRegistry empty = new CoreToolRegistry(Collections.emptyList());
    @Before public void setup() throws Exception {
        context=ApplicationProvider.getApplicationContext();session="ux36-runtime-"+UUID.randomUUID();
        Field field=SecretStore.class.getDeclaredField("singleton");field.setAccessible(true);previous=field.get(null);
        SecretStore secrets=new SecretStore(context.getSharedPreferences(session,Context.MODE_PRIVATE));
        secrets.saveCodexTokens("test-access","test-refresh",System.currentTimeMillis()+3600000,"test-account");field.set(null,secrets);
        settings=new ProviderSettings(context);settings.setProvider(ProviderSettings.Provider.OPENAI_CODEX);
    }
    @After public void cleanup() throws Exception {Field field=SecretStore.class.getDeclaredField("singleton");field.setAccessible(true);field.set(null,previous);settings.setProvider(ProviderSettings.Provider.OPENROUTER);}
    private CoreAgentRuntime runtime(){return new CoreAgentRuntime(context,session,Collections.emptyList());}
    private CrewRole role(){return runtime().resolveCrewProfile(CoreAgentRuntime.profileCapabilities(context,session),"coding");}
    private CrewManager manager(){return new CrewManager(session,empty,(bot,manager)->empty,(bot,tools,incoming)->{throw new AssertionError("No model execution in this fixture");},null);}
    private CrewManager.Bot saved(CrewManager manager,CrewRole role){
        CrewBotSnapshot snapshot=new CrewBotSnapshot("saved-image-worker",role.id,role.name,"Work",role.colorKey,"Implement", "INTERRUPTED","","","",role.tools,1,0);
        CrewMissionSnapshot mission=new CrewMissionSnapshot("saved-image-mission",session,"past",snapshot.mission,"INTERRUPTED","",1,0,Collections.singletonList(snapshot),Collections.emptyList());
        return manager.restoreBot(mission,snapshot,role,CoreAgentLoop.Checkpoint.empty(),new JSONObject(),"",Collections.emptyList(),0,Collections.emptyList(),Collections.emptyList(),"");
    }
    @Test(timeout=60000) public void supportedRuntimeAddsAndActuallyBindsProjectImageForCodingOnly() {
        CoreAgentRuntime runtime=runtime();assertTrue(CoreAgentRuntime.profileCapabilities(context,session).contains("project_image"));
        assertFalse(CrewProfile.codingDefault().capabilities.contains("project_image"));
        try(CrewManager manager=manager()){
            CrewManager.Bot bot=saved(manager,role());CoreToolRegistry tools=runtime.createCrewBotTools(context,session,empty,bot,manager);
            assertTrue(tools.names().contains("project_image"));assertFalse(tools.names().contains("generate_image"));assertFalse(tools.names().contains("list_image_references"));assertFalse(tools.names().contains("import_project_image"));
            assertTrue(tools.declarations().stream().filter(spec->spec.name.equals("project_image")).findFirst().get().description.contains("existing signed-in ChatGPT"));
            assertFalse(tools.forDelegatedAgent().names().contains("project_image"));
        }
    }
    @Test(timeout=60000) public void unsupportedProviderDoesNotDeclareOrPromiseProjectImages() {
        settings.setProvider(ProviderSettings.Provider.OPENROUTER);
        assertFalse(CoreAgentRuntime.profileCapabilities(context,session).contains("project_image"));assertFalse(role().tools.contains("project_image"));
        CoreTool crew=new Stub("crew_spawn");String prompt=runtime().instructions(new CoreToolRegistry(Collections.singletonList(crew)),true);
        assertTrue(prompt.contains("does not expose the project_image backend"));assertFalse(prompt.contains("Coding can currently receive project_image"));
    }
    @Test(timeout=60000) public void principalCapabilityStatementIsConditionalAndDoesNotForceImages() {
        String prompt=runtime().instructions(new CoreToolRegistry(Arrays.asList(new Stub("crew_spawn"),new ImportProjectImageTool(context,session))),true);
        assertTrue(prompt.contains("Coding can currently receive project_image"));assertTrue(prompt.contains("optional, not mandatory"));assertTrue(prompt.contains("coding_adopt"));
        assertTrue(CodingAgentInstructions.IMAGE_GUIDANCE.contains("only when project_image is actually declared"));assertTrue(CodingAgentInstructions.IMAGE_GUIDANCE.contains("not visual review"));
    }
    @Test(timeout=60000) public void readOnlyAndSavedNarrowScopeCannotAcquireNewImageCapability() {
        CoreAgentRuntime runtime=runtime();CrewRole narrow=role().withTools(Arrays.asList("ls","read","report_done"));
        try(CrewManager manager=manager()){
            CrewManager.Bot bot=saved(manager,narrow);assertEquals(narrow.tools,runtime.currentResumeRole(bot).tools);
            assertFalse(runtime.createCrewBotTools(context,session,empty,bot,manager).names().contains("project_image"));
        }
        try(CrewManager manager=manager()){
            CrewManager.Bot bot=saved(manager,role().withMissionAccess(CrewMissionAccess.READ_ONLY));assertFalse(bot.role.tools.contains("project_image"));
            assertFalse(runtime.createCrewBotTools(context,session,empty,bot,manager).names().contains("project_image"));
        }
    }
    @Test(timeout=60000) public void customProfilesNeedExplicitSelectionAndProjectWorkspace() {
        CrewProfile copy=CrewProfile.codingDefault().withIdentity("custom-image");
        BotDefinition definition=new BotDefinition(copy,1,true,false,"");
        assertFalse(CrewProfileRepository.runtimeProfile(definition,Arrays.asList("project_image","read"),Collections.emptyList()).capabilities.contains("project_image"));
        assertFalse(CrewProfile.isWorkspaceCapabilityCompatible(CrewProfile.WorkspaceMode.LEGACY_CHAT,"project_image"));
        assertTrue(CrewProfile.isWorkspaceCapabilityCompatible(CrewProfile.WorkspaceMode.CONVERSATION_PROJECT,"project_image"));
        assertTrue(CrewManager.isCaptainOnly("import_project_image"));
        CrewProfile explicit=new CrewProfile("custom-image",1,"Images","Project visuals","Authorized work",Collections.emptyList(),Collections.singletonList("project_image"),CrewProfile.WorkspaceMode.CONVERSATION_PROJECT);
        explicit.validateAvailability(Collections.singletonList("project_image"),Collections.emptyList());
        new CrewProfileRepository(context).create(explicit, Collections.singletonList("project_image"), Collections.emptyList());
        try(CrewManager manager=manager()){
            CoreToolRegistry tools=runtime().createCrewBotTools(context,session,empty,saved(manager,explicit.resolveRole(Collections.singletonList("project_image"),Collections.emptyList())),manager);
            assertTrue(tools.names().contains("project_image"));
            CoreToolResult result=tools.invoke("project_image",Collections.emptyMap(),CancellationToken.crewChild());
            assertFalse(result.success);assertTrue(result.content,result.content.contains("action requires text"));
        }
    }
    @Test(timeout=60000) public void parentCeilingRejectsSelectedProjectImageEvenWithValidAccount() throws Exception {
        Constructor<CoreAgentRuntime> constructor=CoreAgentRuntime.class.getDeclaredConstructor(Context.class,String.class,List.class,List.class,List.class,List.class,List.class,com.jarvys.agent.connectors.ConnectorRegistry.class,int.class,CorePromptBudget.class,boolean.class,boolean.class);constructor.setAccessible(true);
        CoreAgentRuntime restricted=constructor.newInstance(context,session,Collections.emptyList(),Arrays.asList("ls","read","crew_spawn"),Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),null,0,CorePromptBudget.standard(),true,false);
        try(CrewManager manager=manager()){
            CrewManager.Bot bot=saved(manager,role());assertThrows(IllegalArgumentException.class,()->restricted.createCrewBotTools(context,session,empty,bot,manager));
        }
    }
    @Test(timeout=60000) public void mainImportIsNotInheritedByGenericCrewScope() {
        CoreToolRegistry main=runtime().createTools();assertTrue(main.names().contains("import_project_image"));
        assertFalse(CoreAgentRuntime.crewBotCapabilityScope(main).names().contains("import_project_image"));
        assertFalse(main.forDelegatedAgent().names().contains("import_project_image"));
        assertFalse(main.declarations().stream().filter(spec->spec.name.equals("delegate_subtask")).findFirst().get().description.contains("import_project_image"));
    }
    @Test(timeout=60000) public void actualHistoryReconstructionExplicitlyResumesSelectedImageCapability() throws Exception {
        CoreAgentRuntime original=runtime(); CrewRole selected=role().withTools(Arrays.asList("project_image","report_done"));
        String id;
        try(CrewManager manager=manager()){
            CrewManager.Bot bot=saved(manager,selected);id=bot.id;
            new CrewCheckpointCoordinator(context,session,original::currentResumeRole).persist(bot,Collections.emptyList(),Collections.emptyList());
        }
        CrewManager restored=CoreAgentRuntime.prepareCrewHistory(context,session);
        try {
            CrewManager.Bot bot=restored.bot(id);assertNotNull(bot);assertEquals(selected.tools,bot.role.tools);assertTrue(bot.canResume());
            Field factoryField=CrewManager.class.getDeclaredField("toolFactory");factoryField.setAccessible(true);
            CrewManager.ToolFactory factory=(CrewManager.ToolFactory)factoryField.get(restored);
            java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
            restored.configure(empty,factory,(worker,tools,incoming)->new CoreAgentLoop((history,prompt,specs,token)->{
                assertTrue(tools.names().contains("project_image"));
                // A declared tool with the restored live guard reaches argument validation, without provider traffic.
                CoreToolResult observed=tools.invoke("project_image",Collections.emptyMap(),token);
                assertFalse(observed.success);assertTrue(observed.content,observed.content.contains("action requires text"));calls.incrementAndGet();
                return new ModelReply("Restored image scope verified",Collections.emptyList());
            },tools,"Synthetic restoration fixture",session,CorePromptBudget.standard(),null,CoreAgentLoop.Limits.UNBOUNDED,incoming,null),null);
            restored.resume(id);await(bot);assertEquals(bot.error(),1,calls.get());assertEquals(CrewManager.Status.DONE,bot.status());
            assertEquals(selected.tools,bot.role.tools);
        } finally {restored.close();}
    }
    @Test(timeout=60000) public void oldMainImageRestrictionsRemainIntact() {
        assertFalse(CoreAgentRuntime.crewBotCapabilityScope(new CoreToolRegistry(Arrays.asList(new Stub("generate_image"),new Stub("project_image"),new Stub("list_image_references")))).names().contains("generate_image"));
        assertFalse(ProjectImageTool.available(context,com.jarvys.agent.proactive.ProactiveConversation.SESSION_ID));
        assertFalse(ProjectImageTool.available(context,com.jarvys.agent.tasks.ScheduledTaskConversation.SESSION_ID));
    }
    private static void await(CrewManager.Bot bot) throws Exception {
        java.util.concurrent.CountDownLatch finished=new java.util.concurrent.CountDownLatch(1);
        Thread waiter=new Thread(()->{try {bot.awaitTermination();} catch (InterruptedException ignored) {Thread.currentThread().interrupt();} finally {finished.countDown();}});
        waiter.setDaemon(true);waiter.start();
        try {assertTrue("Worker did not terminate: "+bot.status()+" / "+bot.error(),finished.await(10,java.util.concurrent.TimeUnit.SECONDS));}
        finally {waiter.interrupt();}
    }
    private static final class Stub implements CoreTool {
        private final String name;Stub(String name){this.name=name;}
        public ToolSpec declaration(){return new ToolSpec(name,"fixture","fixture","test",ToolSpec.Status.IMPLEMENTED,Collections.emptyMap(),Collections.emptyList());}
        public CoreToolResult execute(Map<String,Object> args,CancellationToken token){throw new AssertionError("No execution");}
    }
}
