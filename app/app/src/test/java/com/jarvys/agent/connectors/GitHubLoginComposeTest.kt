package com.jarvys.agent.connectors

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.R
import com.jarvys.agent.mcp.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitHubLoginComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Vault : McpCredentialVault {
        val values = mutableMapOf<Pair<String,String>,String>()
        override fun get(serverId: String,key: String)=values[serverId to key]
        override fun save(serverId: String,key: String,value: String) { values[serverId to key]=value }
        override fun clear(serverId: String) { values.keys.removeAll { it.first==serverId } }
    }
    private class Attempt(val code: (GitHubDeviceCode)->Unit,val complete:(Result<GitHubOAuthTokens>)->Unit):AutoCloseable {
        var closes=0
        override fun close(){closes++}
    }
    private val attempts=mutableListOf<Attempt>()
    private val vault=Vault()
    private lateinit var repository:McpServerRepository
    private lateinit var manager:McpConnectionManager
    private lateinit var oauth:McpOAuthManager
    private var visible by mutableStateOf(true)
    private var recompositions by mutableStateOf(0)
    private var connects=0
    private val code=GitHubDeviceCode("device-secret","WXYZ-4321",GitHubDeviceFlowProtocol.VERIFICATION_URI,900,5)
    private val tokens=GitHubOAuthTokens("access-secret","refresh-secret",90000,900000,setOf("read:user"))
    @Before fun setup(){
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("jarvys_mcp_servers",Context.MODE_PRIVATE).edit().clear().commit()
        repository=McpServerRepository(context,vault);oauth=McpOAuthManager(repository)
        val notifier=object:AutonomyActionNotifier {
            override fun canPost()=false
            override fun missingRuntimePermission():String?=null
            override fun post(record:AutonomyAuditRecord)=Unit
        }
        val presenter=object:ApprovalPresenter{override fun show(id:String,summary:ApprovalSummary)=Unit;override fun update(id:String,decision:ApprovalDecision)=Unit}
        manager=McpConnectionManager(repository,oauth,McpWriteApprovalCoordinator(InMemoryConnectorAutonomyStore(),ApprovalGate(100,presenter),notifier))
        compose.setContent { MaterialTheme { if(visible) {
            androidx.compose.material3.Text("revision $recompositions")
            RemoteServicesSection(repository,manager,oauth,selectedId="github",
                githubFlowStarter=GitHubDeviceFlowStarter{_,onCode,onComplete->Attempt(onCode,onComplete).also(attempts::add)},
                onGitHubConnected={connects++})
        } } }
    }
    @After fun cleanup(){manager.close()}
    private fun start(){compose.onNodeWithText(compose.activity.getString(R.string.github_device_connect)).performClick();compose.waitForIdle()}
    private fun showCode(){compose.runOnIdle{attempts.last().code(code)};compose.onNodeWithText(code.userCode).assertIsDisplayed()}
    @Test fun connectThenRepositoryRecompositionKeepsExactAttemptAlive(){
        start();assertEquals(1,attempts.size);assertEquals(0,attempts.single().closes)
        compose.runOnIdle{recompositions++};assertEquals(0,attempts.single().closes)
        showCode();assertEquals(0,attempts.single().closes)
        compose.onNodeWithText(compose.activity.getString(R.string.remote_service_connecting)).assertIsNotEnabled()
    }
    @Test fun cancelResetsSpinnerAndAllowsAFreshAttempt(){
        start();showCode();compose.onNodeWithText(compose.activity.getString(R.string.connector_cancel)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.github_device_connect)).assertIsEnabled()
        assertEquals(1,attempts.single().closes);start();assertEquals(2,attempts.size);showCode()
        compose.runOnIdle{attempts.first().complete(Result.failure(GitHubDeviceFlowException("cancelled")))}
        compose.onNodeWithText(code.userCode).assertIsDisplayed();assertEquals(0,attempts.last().closes)
    }
    @Test fun successFromCancelledAttemptCannotSaveGrantAfterRetry(){
        start();showCode();compose.onNodeWithText(compose.activity.getString(R.string.connector_cancel)).performClick();start()
        compose.runOnIdle{attempts.first().complete(Result.success(tokens))}
        assertEquals(0,connects);assertTrue(vault.values.values.none{it==tokens.accessToken});showCode()
        compose.runOnIdle{attempts.last().complete(Result.success(tokens))}
        assertEquals(1,connects);assertTrue(vault.values.values.any{it==tokens.accessToken})
        compose.onNodeWithText(code.userCode).assertDoesNotExist()
    }
    @Test fun leaveDetailDisposesAttemptAndLateCallbackCannotPersist(){
        start();showCode();compose.runOnIdle{visible=false};compose.waitForIdle()
        assertEquals(1,attempts.single().closes)
        compose.runOnIdle{attempts.single().complete(Result.success(tokens))}
        assertEquals(0,connects);assertTrue(vault.values.values.none{it==tokens.accessToken})
    }
    @Test fun deniedAuthorizationShowsSpecificErrorAndAllowsRetry(){
        start();compose.runOnIdle{attempts.single().complete(Result.failure(GitHubDeviceFlowException("access_denied")))}
        compose.onNodeWithText(compose.activity.getString(R.string.github_device_error_denied)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.github_device_connect)).assertIsEnabled()
    }
    @Test fun browserReturnRecompositionPreservesPollingAndFinishesOnce(){
        start();showCode();compose.runOnIdle{recompositions+=2};assertEquals(0,attempts.single().closes)
        compose.runOnIdle{attempts.single().complete(Result.success(tokens));attempts.single().complete(Result.success(tokens))}
        assertEquals(1,connects);assertEquals(1,attempts.single().closes)
    }
}
