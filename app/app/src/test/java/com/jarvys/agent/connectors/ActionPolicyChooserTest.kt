package com.jarvys.agent.connectors

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jarvys.agent.MemoryUiAutomationGuard
import com.jarvys.agent.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ActionPolicyChooserTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun onlyOpenChooserIsProtectedAndOneSelectionCommitsOnce() {
        var writes = 0
        compose.setContent { MaterialTheme { ConnectorPolicySelector(R.string.connector_policy_allow,
            listOf(ConnectorPolicyChoice(R.string.connector_policy_deny) { writes++ }),
            subjectKey = "one/action", modifier = Modifier.testTag("chooser")) } }
        assertFalse(MemoryUiAutomationGuard.isProtected())
        compose.onNodeWithTag("chooser").performClick()
        assertTrue(MemoryUiAutomationGuard.isProtected())
        compose.onNode(hasText(compose.activity.getString(R.string.connector_policy_deny)) and hasAnyAncestor(isPopup())).performClick()
        compose.waitForIdle()
        assertEquals(1, writes); assertFalse(MemoryUiAutomationGuard.isProtected())
    }
    @Test fun automatedEntryCannotLiftSavedRestriction() {
        var writes = 0
        compose.setContent { MaterialTheme { ConnectorPolicySelector(R.string.connector_policy_deny,
            listOf(ConnectorPolicyChoice(R.string.connector_policy_allow) { writes++ }),
            subjectKey = "one/action", modifier = Modifier.testTag("chooser")) } }
        MemoryUiAutomationGuard.beginAsyncAction(MemoryUiAutomationGuard.captureAutomationEpoch()).use {
            compose.onNodeWithTag("chooser").performClick(); compose.waitForIdle()
            compose.onAllNodes(isPopup()).assertCountEquals(0)
            assertEquals(0, writes); assertFalse(MemoryUiAutomationGuard.isProtected())
        }
        compose.onNodeWithTag("chooser").performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.connector_policy_allow)) and hasAnyAncestor(isPopup())).performClick()
        assertEquals(1, writes)
    }
    @Test fun changingSubjectClosesOldAdministrativeChoice() {
        val subject = mutableStateOf("first")
        compose.setContent { MaterialTheme { ConnectorPolicySelector(R.string.connector_policy_allow,
            listOf(ConnectorPolicyChoice(R.string.connector_policy_deny) {}),
            subjectKey = subject.value, modifier = Modifier.testTag("chooser")) } }
        compose.onNodeWithTag("chooser").performClick(); assertTrue(MemoryUiAutomationGuard.isProtected())
        compose.runOnIdle { subject.value = "second" }; compose.waitForIdle()
        compose.onAllNodes(isPopup()).assertCountEquals(0); assertFalse(MemoryUiAutomationGuard.isProtected())
    }
}
