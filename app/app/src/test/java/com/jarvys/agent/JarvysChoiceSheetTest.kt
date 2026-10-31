package com.jarvys.agent

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.jarvys.agent.ui.skills.SkillSourceSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JarvysChoiceSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun selectedOptionIsAnnouncedAndChoosingHidesThenCallsEachCallbackOnce() {
        var visible by mutableStateOf(true)
        val selected = mutableListOf<String>()
        var closes = 0
        val options = choices()
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                if (visible) JarvysChoiceSheet(
                    title = "Color mode", choices = options, selected = "dark",
                    onClose = { closes++; visible = false }, onSelect = selected::add,
                )
            }
        }

        compose.onNodeWithTag("jarvys-choice-sheet-title").assertIsDisplayed()
        compose.onAllNodesWithTag("jarvys-choice-row").assertCountEquals(options.size)
        compose.onAllNodesWithTag("jarvys-choice-row").get(2).assertIsSelected()
        compose.onAllNodesWithTag("jarvys-choice-row").get(1).performClick()
        compose.waitUntil(5_000) { closes == 1 && selected.size == 1 }
        assertEquals(listOf("light"), selected)
        assertTrue(compose.onAllNodesWithTag("jarvys-choice-row").fetchSemanticsNodes().isEmpty())
    }

    @Test fun systemBackAndScrimDismissWithoutSelecting() {
        var visible by mutableStateOf(true)
        val selected = mutableListOf<String>()
        var closes = 0
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                if (visible) JarvysChoiceSheet("Language", choices(), "en",
                    onClose = { closes++; visible = false }, onSelect = selected::add)
            }
        }
        compose.onNodeWithTag("jarvys-choice-sheet-title").assertIsDisplayed()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(5_000) { closes == 1 }
        assertTrue(selected.isEmpty())

        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("jarvys-choice-sheet-title").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("jarvys-choice-sheet-title").assertIsDisplayed()
        compose.onNode(isDialog()).performTouchInput { click(Offset(center.x, 24.dp.toPx())) }
        compose.waitUntil(5_000) { closes == 2 }
        assertTrue(selected.isEmpty())
    }

    @Test fun fontScaleTwoKeepsEveryChoiceScrollableAndTouchRowsAtLeastFortyEightDp() {
        var fontScale by mutableStateOf(2f)
        val options = (1..10).map { JarvysChoiceOption("option-$it", "Option $it — lengthy label for large text") }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                androidx.compose.material3.MaterialTheme {
                    JarvysChoiceSheet("Language options", options, "option-1", {}, {})
                }
            }
        }
        compose.onNodeWithTag("jarvys-choice-sheet-title").assertIsDisplayed()
        val rows = compose.onAllNodesWithTag("jarvys-choice-row")
        rows.assertCountEquals(options.size)
        rows.get(options.lastIndex).performScrollTo().assertIsDisplayed()
        val minimumHeightPx = with(compose.activity.resources.displayMetrics) { 48f * density }
        rows.fetchSemanticsNodes().forEach { node ->
            assertTrue("row height ${node.size.height} < 48dp at fontScale=2", node.size.height >= minimumHeightPx - 1f)
        }
        compose.runOnIdle { fontScale = 1f }
        compose.waitForIdle()
    }

    @Test fun sharedSheetRowKeepsSkillImportOptionsFunctional() {
        var dismissed = 0
        var selectedFile = 0
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                SkillSourceSheet(onDismiss = { dismissed++ }, onPasteMarkdown = {},
                    onFromFile = { selectedFile++ }, onFromGitHub = {})
            }
        }
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(app.getString(R.string.skills_import_file)).assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { dismissed == 1 && selectedFile == 1 }
        assertEquals(1, dismissed)
        assertEquals(1, selectedFile)
    }

    private fun choices() = listOf(
        JarvysChoiceOption("system", "System default"),
        JarvysChoiceOption("light", "Light"),
        JarvysChoiceOption("dark", "Dark"),
    )
}
