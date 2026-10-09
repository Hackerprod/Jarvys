package com.jarvys.agent.crew

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jarvys.agent.*
import com.jarvys.agent.R
import com.jarvys.agent.ui.jarvysColorScheme
import com.jarvys.agent.ui.motion.LocalReducedMotion
import com.jarvys.agent.ui.chat.awaitReactionDrawIdle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="en-rUS-w360dp-h800dp-port-mdpi")
class ToolActivityComposeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun activity(stage:String="tool_call",detail:String="",skill:Boolean=false)=ToolActivity("event-$stage","execution","raw-call",
        if(skill) "read_skill" else "read", "Read",if(skill) "skill.garden" else "",if(skill) "Garden Planning" else "",
        stage,detail,"","","workspace",1)
    private fun bot(state:String="RUNNING")=CrewBotSnapshot("bot","coding","Coding","Coding","coding","Inspect",state,"","","",emptyList(),0,0)
    private fun local(a:ToolActivity,id:String="local")=CrewMessage(id,"chat","bot","bot",CrewMessage.Type.STATUS,"Read",emptyList(),1,a)
    private fun snapshot(state:String="RUNNING",messages:List<CrewMessage>)=CrewMissionSnapshot("mission","chat","process","Inspect source",state,"",0,0,listOf(bot(state)),messages)
    private val fixtureBoard by lazy {
        val constructor=WorkspaceStore::class.java.getDeclaredConstructor(File::class.java,String::class.java).apply { isAccessible=true }
        CrewBoard(constructor.newInstance(TestCaptureDirectories.create("ux37-board"),WorkspaceStore.projectIdForSession("chat")))
    }
    private fun board()=fixtureBoard

    @Test fun oneCrewRowTransitionsAndOnlyRealMessageHasDirection() {
        val start=local(activity());var value by mutableStateOf(snapshot(messages=listOf(start,
            CrewMessage("real","chat","bot","chief",CrewMessage.Type.STATUS,"Ready for review",emptyList(),2))))
        compose.setContent { MaterialTheme { CrewMissionScreen(value,board(),true,{},{_,_->},{}) } }
        compose.onAllNodesWithText("To Jarvys").assertCountEquals(1)
        compose.onNodeWithText("Using Read").assertIsDisplayed()
        compose.runOnIdle { value=snapshot(messages=value.messages+local(activity("tool_result","Read result"),"done")) }
        compose.onNodeWithText("Used Read").assertIsDisplayed();compose.onAllNodesWithText("Using Read").assertCountEquals(0)
        compose.onAllNodesWithTag("activity-execution").assertCountEquals(1)
        compose.onNodeWithTag("activity-details-execution").performClick();compose.onNodeWithText("Read result").assertIsDisplayed()
        compose.onAllNodesWithText("To Jarvys").assertCountEquals(1)
    }
    @Test fun botStatusOnlyChangeMarksPendingOperationUnconfirmed() {
        val messages=listOf(local(activity()));var value by mutableStateOf(snapshot(messages=messages))
        compose.setContent { MaterialTheme { CrewBotDetailScreen(value,"bot",board(),true,{_,_->},{_,_->},{},{}) } }
        compose.onNodeWithText("Using Read").assertExists()
        compose.runOnIdle { value=snapshot("STOPPED",messages) }
        compose.onAllNodesWithText("Using Read").assertCountEquals(0)
        compose.onNodeWithText(compose.activity.getString(R.string.connector_tool_unconfirmed,"Read")).assertExists()
    }
    @Test fun principalUsesOriginalMascotOnApi34WithoutClickAuthority() {
        var role by mutableStateOf("chief")
        compose.setContent { MaterialTheme { CrewBotAvatar("Jarvys",role,"periwinkle","IDLE",modifier=Modifier.size(72.dp),testTag="principal-identity") } }
        fun sources(node:androidx.compose.ui.semantics.SemanticsNode):List<String> =
            (if(node.config.contains(BotIconSourceKey)) listOf(node.config[BotIconSourceKey]) else emptyList())+node.children.flatMap(::sources)
        for(id in listOf("chief","captain")) {
            compose.runOnIdle { role=id }
            compose.onNodeWithTag("principal-identity").assertIsDisplayed()
            assertTrue(sources(compose.onRoot(useUnmergedTree=true).fetchSemanticsNode()).contains("principal:jarvys-mascot"))
            compose.onNodeWithTag("principal-identity").assertHasNoClickAction()
        }
    }
    @Test fun principalMissionHeaderUsesOriginalMascotWithoutAddingNewBotEntries() {
        compose.setContent { MaterialTheme { CrewMissionScreen(snapshot(messages=emptyList()),board(),true,{},{_,_->},{}) } }
        fun sources(node:androidx.compose.ui.semantics.SemanticsNode):List<String> =
            (if(node.config.contains(BotIconSourceKey)) listOf(node.config[BotIconSourceKey]) else emptyList())+node.children.flatMap(::sources)
        assertTrue(sources(compose.onRoot(useUnmergedTree=true).fetchSemanticsNode()).contains("principal:jarvys-mascot"))
        compose.onNodeWithTag("crew-tab-bots").performClick()
        compose.onNodeWithTag("crew-bot-row-bot").assertIsDisplayed()
        compose.onNodeWithTag("crew-bot-principal").assertDoesNotExist()
    }
    @Test fun mainChatPartialEvidenceRemainsReachableAfterInterruption() {
        val projected=ToolActivity.project(listOf(activity(),activity("tool_progress","Partial evidence"),activity("tool_interrupted")),false)
        val registry=com.jarvys.agent.connectors.ConnectorRegistry.createForTests(object:com.jarvys.agent.connectors.ConnectorConnectionPreferences {
            override fun isConnected(id:String)=false
            override fun setConnected(id:String,connected:Boolean)=Unit
        },{true})
        compose.setContent { MaterialTheme {
            AgentRunScreen(conversationKey="chat",events=listOf(AgentRunUiEvent.activityEvent(1,projected)),isRunning=false,
                emptyReport=null,connectorRegistry=registry,onOpenPreview={},onOpenSkillFile={},chatWithoutMemory=false)
        } }
        compose.onNodeWithTag("tool-output-toggle-execution").performClick()
        compose.onNodeWithText("Partial evidence").assertIsDisplayed()
    }
    @Test fun englishLightNormal()=matrix("en",false,1f)
    @Test fun englishDarkNormal()=matrix("en",true,1f)
    @Test fun englishLightLarge()=matrix("en",false,2f)
    @Test fun englishDarkLarge()=matrix("en",true,2f)
    @Test @Config(qualifiers="es-rES-w360dp-h800dp-port-mdpi") fun spanishLightNormal()=matrix("es",false,1f)
    @Test @Config(qualifiers="es-rES-w360dp-h800dp-port-mdpi") fun spanishDarkNormal()=matrix("es",true,1f)
    @Test @Config(qualifiers="es-rES-w360dp-h800dp-port-mdpi") fun spanishLightLarge()=matrix("es",false,2f)
    @Test @Config(qualifiers="es-rES-w360dp-h800dp-port-mdpi") fun spanishDarkLarge()=matrix("es",true,2f)
    private fun matrix(language:String,dark:Boolean,font:Float) {
        var state by mutableStateOf(activity(skill=true))
        compose.setContent { CompositionLocalProvider(LocalReducedMotion provides true,LocalDensity provides Density(1f,font)) {
            MaterialTheme(colorScheme=jarvysColorScheme(dark)) { Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                CrewBotAvatar("Jarvys","chief","periwinkle","RUNNING",modifier=Modifier.size(64.dp))
                ToolActivityLine(state)
            } }
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.activity_skill_loading,"Garden Planning")).assertIsDisplayed()
        compose.runOnIdle { state=activity("tool_result","Synthetic instructions available for selection.",true) }
        val label=compose.activity.getString(R.string.activity_skill_loaded,"Garden Planning")
        compose.onNodeWithText(label).assertIsDisplayed()
        val text=compose.onNodeWithText(label).fetchSemanticsNode()
        assertEquals(label,text.config[SemanticsProperties.StateDescription])
        compose.onNodeWithTag("activity-details-execution").performClick()
        compose.onNodeWithText("Synthetic instructions available for selection.").assertIsDisplayed()
        val bounds=compose.onNodeWithTag("activity-details-execution").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width>=48f && bounds.height>=48f)
        awaitReactionDrawIdle(compose)
        val root=compose.activity.findViewById<View>(android.R.id.content)
        val pixels=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        compose.runOnIdle { root.draw(Canvas(pixels)) }
        val file=File(TestCaptureDirectories.named("ux37-${BuildConfig.FLAVOR}"),"$language-${if(dark) "dark" else "light"}-$font.png")
        file.outputStream().use { pixels.compress(Bitmap.CompressFormat.PNG,100,it) };pixels.recycle()
        println("UX37_CAPTURE=${file.absolutePath}")
    }
}
