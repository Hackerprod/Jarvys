package com.jarvys.agent.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jarvys.agent.AgentRunUiState
import com.jarvys.agent.CancellationToken
import com.jarvys.agent.JarvysThemeMode
import com.jarvys.agent.R
import com.jarvys.agent.connectors.*
import com.jarvys.agent.linux.LinuxExecAutonomy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CodingAutonomyControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun setupAndRemovalHaveIndependentThreeStateControlsAndSavedProvenance() {
        val autonomy = LinuxExecAutonomy(InMemoryConnectorAutonomyStore(), { false })
        controls(autonomy)
        for (operation in LinuxExecAutonomy.MAINTENANCE_OPERATIONS) {
            compose.onNodeWithTag("$operation-policy-control").performScrollTo()
                .assertTextContains(policyLabel(AutonomyPolicy.ALLOW))
            for (choice in listOf(AutonomyPolicy.DENY, AutonomyPolicy.ASK, AutonomyPolicy.ALLOW)) {
                choosePolicy("$operation-policy-control", choice)
                compose.runOnIdle {
                    val authorization = autonomy.maintenanceAuthorization(operation)
                    assertEquals(choice, authorization.policy)
                    assertEquals(ActionPolicyOrigin.USER, authorization.snapshot.origin)
                    assertEquals(AutonomyPolicy.ALLOW, autonomy.policy())
                    assertEquals(AutonomyPolicy.ALLOW, autonomy.projectPolicy())
                }
            }
        }
    }

    @Test fun principalThreeStateControlsRemainEditableWithoutInstallation() {
        val autonomy = LinuxExecAutonomy(InMemoryConnectorAutonomyStore(), { false })
        controls(autonomy)
        compose.onNodeWithTag("linux-policy-unavailable").performScrollTo().assertIsDisplayed()
        for (choice in listOf(AutonomyPolicy.DENY, AutonomyPolicy.ASK, AutonomyPolicy.ALLOW)) {
            choosePolicy("linux-policy-control", choice)
            compose.runOnIdle { assertEquals(choice, autonomy.policy()); assertFalse(autonomy.installed()) }
        }
    }

    @Test fun freshCodingAllowCanBeChangedToDenyAskAndAllowWithoutChangingPrincipal() {
        val autonomy = LinuxExecAutonomy(InMemoryConnectorAutonomyStore(), { true })
        controls(autonomy)
        compose.onNodeWithTag("coding-policy-control").performScrollTo().assertTextContains(policyLabel(AutonomyPolicy.ALLOW))
        for (choice in listOf(AutonomyPolicy.DENY, AutonomyPolicy.ASK, AutonomyPolicy.ALLOW)) {
            choosePolicy("coding-policy-control", choice)
            compose.runOnIdle {
                assertEquals(choice, autonomy.projectPolicy())
                assertEquals(AutonomyPolicy.ALLOW, autonomy.policy())
            }
        }
    }

    @Test fun inheritedLinuxDenyIsVisibleAndCodingOverrideIsSeparate() {
        val autonomy = LinuxExecAutonomy(InMemoryConnectorAutonomyStore(), { true })
        autonomy.setPolicyFromUi(AutonomyPolicy.DENY)
        controls(autonomy)
        compose.onNodeWithTag("coding-policy-control").performScrollTo().assertTextContains(policyLabel(AutonomyPolicy.DENY))
        choosePolicy("coding-policy-control", AutonomyPolicy.ALLOW)
        compose.runOnIdle {
            assertEquals(AutonomyPolicy.ALLOW, autonomy.projectPolicy())
            assertEquals(AutonomyPolicy.DENY, autonomy.policy())
        }
    }

    @Test fun codingAllowAlwaysCardSavesOnlyCodingAndCannotRepeatConsentAfterResolution() {
        approvalCard(cancelBeforeClick = false)
    }

    @Test fun stoppedCodingApprovalCannotEnableAlwaysAllow() {
        approvalCard(cancelBeforeClick = true)
    }

    private fun policyLabel(choice: AutonomyPolicy): String = compose.activity.getString(when (choice) {
        AutonomyPolicy.ALLOW -> R.string.connector_policy_allow_automatically
        AutonomyPolicy.ASK -> R.string.connector_policy_ask_each_time
        AutonomyPolicy.DENY -> R.string.connector_policy_deny_action
    })

    private fun choosePolicy(tag: String, choice: AutonomyPolicy) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.onNode(hasText(policyLabel(choice)) and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithTag(tag).assertTextContains(policyLabel(choice))
    }

    private fun controls(autonomy: LinuxExecAutonomy) {
        compose.setContent {
            JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                CompositionLocalProvider(LocalFlavorAutonomy provides autonomy) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { FlavorAutonomyUi.ConnectorControls() }
                }
            }
        }
    }

    private fun approvalCard(cancelBeforeClick: Boolean) {
        val autonomy = LinuxExecAutonomy(InMemoryConnectorAutonomyStore(), { true })
        autonomy.setProjectPolicyFromUi(AutonomyPolicy.ASK)
        val token = CancellationToken.cancellable()
        val decision = AtomicReference<ApprovalDecision?>()
        val failure = AtomicReference<Throwable?>()
        AgentRunUiState.resetSession("coding-policy-card")
        AgentRunUiState.beginRun("coding-policy-card", "Run a project check")
        val worker = Thread {
            try {
                decision.set(ApprovalGate.INSTANCE.request(ApprovalSummary("Project command", listOf("test"),
                    allowAlwaysAvailable = true, autonomyConnectorId = LinuxExecAutonomy.PROJECT_CONNECTOR_ID,
                    autonomyOperationName = LinuxExecAutonomy.PROJECT_OPERATION_NAME), token))
            } catch (cancelled: CancellationException) {
                if (!cancelBeforeClick) failure.set(cancelled)
            } catch (error: Throwable) { failure.set(error) }
        }.apply { isDaemon = true; start() }
        try {
            compose.waitUntil(5_000) { AgentRunUiState.state.value.events.any { it.kind == "approval" } }
            val event = AgentRunUiState.state.value.events.single { it.kind == "approval" }
            assertTrue(FlavorAutonomyUi.handlesApproval(event))
            assertFalse(FlavorAutonomyUi.handlesApproval(event.copy(approvalAutonomyOperationName = "linux_exec")))
            compose.setContent {
                JarvysOwnTheme(JarvysThemeMode.LIGHT) {
                    CompositionLocalProvider(LocalFlavorAutonomy provides autonomy) {
                        Column { FlavorAutonomyUi.ApprovalAction(event) { } }
                    }
                }
            }
            compose.onNodeWithTag("coding-allow-warning").assertIsDisplayed()
            if (cancelBeforeClick) { token.cancel(); worker.join(3_000) }
            compose.onNodeWithTag("coding-allow-always").assertIsDisplayed().performClick()
            worker.join(3_000)
            assertFalse("Approval worker must finish", worker.isAlive)
            assertNull(failure.get())
            assertEquals(if (cancelBeforeClick) AutonomyPolicy.ASK else AutonomyPolicy.ALLOW, autonomy.projectPolicy())
            assertEquals(AutonomyPolicy.ALLOW, autonomy.policy())
            if (!cancelBeforeClick) assertEquals(ApprovalDecision.APPROVED_ALLOW_ALWAYS, decision.get())
            val saved = autonomy.projectAuthorization()
            compose.onNodeWithTag("coding-allow-always").performClick()
            assertEquals(saved, autonomy.projectAuthorization())
        } finally {
            token.cancel(); worker.join(3_000)
            AgentRunUiState.resetSession("coding-policy-card-cleanup")
        }
    }
}
