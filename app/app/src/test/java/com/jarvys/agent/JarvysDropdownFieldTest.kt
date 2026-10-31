package com.jarvys.agent

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JarvysDropdownFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun opensFiltersSelectsAndClosesWithAccessibleValueDescription() {
        val selected = mutableStateOf("gpt-5.4")
        compose.setContent {
            MaterialTheme {
                JarvysDropdownField(
                    label = "Model",
                    value = selected.value,
                    options = listOf(
                        JarvysDropdownOption("gpt-5.4", "GPT 5.4", "gpt-5.4 · 1,050K context"),
                        JarvysDropdownOption("gpt-6-luna", "GPT 6 Luna", "gpt-6-luna · 1,050K context"),
                    ),
                    onSelect = { selected.value = it },
                    filterLabel = "Search models",
                    noResultsLabel = "No matching models",
                    modifier = Modifier.testTag("model-field"),
                )
            }
        }
        val semantics = compose.onNodeWithTag("model-field").fetchSemanticsNode().config
        assertTrue(semantics[SemanticsProperties.ContentDescription].single().contains("Model: GPT 5.4"))
        assertTrue(semantics[SemanticsProperties.Role] == Role.Button)
        compose.onNodeWithTag("jarvys-dropdown-field").performClick()
        compose.onNodeWithTag("jarvys-dropdown-filter").assertIsDisplayed().performTextInput("luna")
        compose.onNodeWithText("GPT 6 Luna").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertTrue(selected.value == "gpt-6-luna")
        assertTrue(compose.onAllNodesWithTag("jarvys-dropdown-filter").fetchSemanticsNodes().isEmpty())
    }

    @Test fun disabledFieldCannotOpenItsMenu() {
        compose.setContent {
            MaterialTheme {
                JarvysDropdownField(
                    label = "Reasoning", value = "medium",
                    options = listOf(JarvysDropdownOption("medium", "medium")),
                    onSelect = {}, filterLabel = "Search models", noResultsLabel = "No matching models",
                    enabled = false,
                )
            }
        }
        compose.onNodeWithTag("jarvys-dropdown-field").assertIsNotEnabled().performClick()
        assertTrue(compose.onAllNodesWithTag("jarvys-dropdown-filter").fetchSemanticsNodes().isEmpty())
    }

    @Test fun englishAndSpanishFieldsRemainSeparatedAtFontScaleOneAndTwo() {
        val base = RuntimeEnvironment.getApplication()
        val language = mutableStateOf("en")
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            val localized = androidx.compose.runtime.remember(language.value) {
                base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    setLocale(Locale(language.value))
                })
            }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(density.density, fontScale.value),
            ) {
                MaterialTheme {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        JarvysDropdownField(
                            label = stringResource(R.string.provider_model), value = "gpt-5.4",
                            options = listOf(JarvysDropdownOption("gpt-5.4", "GPT-5.4", "gpt-5.4 · 1,050K")),
                            onSelect = {}, filterLabel = stringResource(R.string.provider_search_models),
                            noResultsLabel = stringResource(R.string.provider_no_models_found),
                            modifier = Modifier.testTag("localized-model"),
                        )
                        JarvysDropdownField(
                            label = stringResource(R.string.provider_reasoning), value = "medium",
                            options = listOf(JarvysDropdownOption("medium", "medium",
                                stringResource(R.string.provider_default_variant))),
                            onSelect = {}, filterLabel = stringResource(R.string.provider_search_models),
                            noResultsLabel = stringResource(R.string.provider_no_models_found),
                            modifier = Modifier.testTag("localized-reasoning"),
                        )
                    }
                }
            }
        }
        for (locale in listOf("en", "es")) {
            language.value = locale
            for (scale in listOf(1f, 2f)) {
                fontScale.value = scale
                compose.waitForIdle()
                val model = compose.onNodeWithTag("localized-model").fetchSemanticsNode().boundsInRoot
                val reasoning = compose.onNodeWithTag("localized-reasoning").fetchSemanticsNode().boundsInRoot
                assertTrue("dropdowns overlap at $locale/fontScale=$scale", model.bottom <= reasoning.top)
                compose.onNodeWithTag("localized-model").assertIsDisplayed()
                compose.onNodeWithTag("localized-reasoning").assertIsDisplayed()
            }
        }
    }
}
