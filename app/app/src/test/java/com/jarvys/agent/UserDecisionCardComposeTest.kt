package com.jarvys.agent

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jarvys.agent.ui.chat.UserDecisionEventCard as JarvysUserDecisionEventCard
import java.nio.file.Files
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserDecisionCardComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cardRendersOneTwoAndManyScrollableOptionsWithPrimaryAndDestructiveActions() {
        val many = (1..32).map { UserDecisionOption("choice-$it", "Choice $it", "Description for choice $it",
            if (it == 1) UserDecisionRole.PRIMARY else if (it == 32) UserDecisionRole.DESTRUCTIVE
            else UserDecisionRole.DEFAULT) }
        val events = mutableStateOf(event("many", many))
        val resolutions = mutableListOf<UserDecisionResult>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp).height(420.dp)) {
                    JarvysUserDecisionEventCard(events.value) { _, result -> resolutions += result; true }
                }
            }
        }
        compose.onNodeWithTag("user-decision-card-many").assertIsDisplayed()
        compose.onNodeWithText("Choose an option").assertIsDisplayed()
        compose.onNodeWithTag("user-decision-option-many-choice-1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("user-decision-option-many-choice-32").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Description for choice 32").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("user-decision-option-many-choice-1").assertIsEnabled()
        compose.onNodeWithTag("user-decision-option-many-choice-32").assertIsEnabled()

        events.value = event("two", many.take(2))
        compose.waitForIdle()
        compose.onNodeWithTag("user-decision-option-two-choice-2").performScrollTo().assertIsDisplayed()
        events.value = event("one", many.take(1))
        compose.waitForIdle()
        compose.onNodeWithTag("user-decision-option-one-choice-1").assertIsDisplayed()
    }

    @Test fun selectedCardBecomesResolvedAndCannotSendAnotherChoice() {
        val initial = event("once", listOf(UserDecisionOption("go", "Continue", role = UserDecisionRole.PRIMARY)))
        val state = mutableStateOf(initial)
        val resolutions = mutableListOf<UserDecisionResult>()
        compose.setContent {
            MaterialTheme {
                JarvysUserDecisionEventCard(state.value) { _, result ->
                    resolutions += result
                    val selected = result as UserDecisionResult.Selected
                    state.value = state.value.copy(stage = "SELECTED", decisionStatus = "SELECTED",
                        decisionOptionId = selected.option.id, decisionOptionLabel = selected.option.label)
                    true
                }
            }
        }
        val option = compose.onNodeWithTag("user-decision-option-once-go")
        option.performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("user-decision-resolution-once", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("user-decision-option-once-go").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Choose one option.").fetchSemanticsNodes().isEmpty())
        val compact = compose.onNodeWithTag("user-decision-resolved-once", useUnmergedTree = true)
        assertTrue(compact.fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains(
            compose.activity.getString(R.string.user_decision_collapsed)))
        assertTrue(compact.fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("Continue"))
        compact.performClick()
        compose.waitForIdle()
        assertTrue(compact.fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains(
            compose.activity.getString(R.string.user_decision_expanded)))
        compose.onNodeWithText("Choose one option.").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("user-decision-option-once-go").fetchSemanticsNodes().isEmpty())
        compact.performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Choose one option.").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("user-decision-dismiss-once").fetchSemanticsNodes().isEmpty())
        assertEquals(1, resolutions.size)
    }

    @Test fun dismissCanBeDisabledAndResolvedStateShowsUnanswered() {
        val noDismiss = event("no-dismiss", listOf(UserDecisionOption("x", "Option")), allowDismiss = false)
        val state = mutableStateOf(noDismiss)
        compose.setContent { MaterialTheme { JarvysUserDecisionEventCard(state.value) } }
        assertTrue(compose.onAllNodesWithTag("user-decision-dismiss-no-dismiss").fetchSemanticsNodes().isEmpty())

        state.value = noDismiss.copy(stage = "UNANSWERED", decisionStatus = "UNANSWERED")
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.user_decision_unanswered)).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("user-decision-option-no-dismiss-x").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Choose one option.").fetchSemanticsNodes().isEmpty())
    }

    @Test fun dismissedCancelledAndUnavailableDecisionsAlsoRestoreCompactly() {
        val pending = event("terminals", listOf(UserDecisionOption("x", "Option", role = UserDecisionRole.PRIMARY)))
        val state = mutableStateOf(pending)
        compose.setContent { MaterialTheme { JarvysUserDecisionEventCard(state.value) } }
        for (status in listOf("DISMISSED", "CANCELLED", "UNAVAILABLE")) {
            state.value = pending.copy(stage = status, decisionStatus = status)
            compose.waitForIdle()
            compose.onNodeWithTag("user-decision-resolved-terminals").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithTag("user-decision-option-terminals-x").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithTag("user-decision-dismiss-terminals").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithText("Choose one option.").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun restoredPendingTimelineCardRendersAsNoResponseAndCannotBeReplayed() {
        val store = LocalRunStore(Files.createTempDirectory("e1-restored-decision").toFile())
        val session = "e1-restored-${System.nanoTime()}"
        store.appendUserDecisionRequest(session, "abandoned", "Choose", "Body",
            listOf(UserDecisionOption("one", "One")), true)
        val restored = store.readConversationTimeline(session).single()
        val app = compose.activity.applicationContext
        val spanish = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale("es"))
        })
        compose.setContent {
            CompositionLocalProvider(LocalContext provides spanish,
                LocalConfiguration provides spanish.resources.configuration) {
                MaterialTheme { JarvysUserDecisionEventCard(restored) }
            }
        }
        compose.onNodeWithTag("user-decision-resolved-abandoned").assertIsDisplayed()
        compose.onNodeWithText(spanish.getString(R.string.user_decision_unanswered)).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("user-decision-option-abandoned-one").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Body").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("user-decision-dismiss-abandoned").fetchSemanticsNodes().isEmpty())
        assertFalse(UserDecisionRequests.gate.isPending("abandoned"))
    }

    @Test fun englishSpanishAndFontScaleTwoRemainReadableAndLocalized() {
        val app = compose.activity.applicationContext
        val language = mutableStateOf("en")
        val scale = mutableStateOf(1f)
        val card = event("locale", listOf(
            UserDecisionOption("primary", "Recommended option", "A longer explanation to verify wrapping.", UserDecisionRole.PRIMARY),
            UserDecisionOption("delete", "Delete", "This cannot be undone.", UserDecisionRole.DESTRUCTIVE)))
        compose.setContent {
            val density = LocalDensity.current
            val localized = androidx.compose.runtime.remember(language.value) {
                app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                    setLocale(Locale(language.value))
                })
            }
            CompositionLocalProvider(LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(density.density, scale.value)) {
                MaterialTheme { JarvysUserDecisionEventCard(card) }
            }
        }
        for (locale in listOf("en", "es")) for (fontScale in listOf(1f, 2f)) {
            language.value = locale
            scale.value = fontScale
            compose.waitForIdle()
            compose.onNodeWithText(app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                setLocale(Locale(locale))
            }).getString(R.string.user_decision_heading)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("user-decision-option-locale-primary").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("user-decision-option-locale-delete").performScrollTo().assertIsDisplayed()
            val roleDescription = if (locale == "es") "Opción destructiva" else "Destructive option"
            val description = compose.onNodeWithTag("user-decision-option-locale-delete")
                .fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
            assertTrue(description.toString().contains(roleDescription))
        }
    }

    private fun event(id: String, options: List<UserDecisionOption>, allowDismiss: Boolean = true) =
        AgentRunUiEvent.userDecisionEvent(1, id, "Choose an option", "Choose one option.", options,
            allowDismiss, "PENDING", null, null, 0L)
}
