package com.jarvys.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryLanguageQ2Test {
    @get:Rule val compose = createComposeRule()

    @Test
    fun starterSeedsUseSelectedAppLanguageWithoutMixingAndKeepTheIndexLinks() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val previous = AppLanguageRuntime.current(context)
        try {
            listOf(AppLanguageChoice.ENGLISH to Locale.ENGLISH, AppLanguageChoice.SPANISH to Locale("es"))
                .forEach { (choice, locale) ->
                    AppLanguageRuntime.select(context, choice)
                    val store = MemoryStore(Files.createTempDirectory("q2-seed-${choice.storedValue}").toFile(), true,
                        MemorySeedTextProvider.fromAppLanguage(context)).forConversation("q2-seed-session")
                    store.ensureInitialized()
                    val localized = context.createConfigurationContext(
                        android.content.res.Configuration(context.resources.configuration).apply { setLocale(locale) })
                    assertEquals(localized.getString(R.string.memory_seed_root_index), store.readUserFile(MemoryConstants.ROOT_INDEX))
                    assertEquals(localized.getString(R.string.memory_seed_human), store.readUserFile("human.md"))
                    assertEquals(localized.getString(R.string.memory_seed_persona), store.readUserFile("persona.md"))
                    assertTrue(store.readUserFile(MemoryConstants.ROOT_INDEX).contains("(human.md)"))
                    assertTrue(store.readUserFile(MemoryConstants.ROOT_INDEX).contains("(persona.md)"))
                    if (choice == AppLanguageChoice.ENGLISH) {
                        assertTrue(store.readUserFile("human.md").contains("Information, interests"))
                        assertFalse(store.readUserFile("human.md").contains("Datos, gustos"))
                    } else {
                        assertTrue(store.readUserFile("human.md").contains("Datos, gustos"))
                        assertFalse(store.readUserFile("human.md").contains("Information, interests"))
                    }
                }
        } finally { AppLanguageRuntime.select(context, previous) }
    }

    @Test
    fun onlyExactUneditedSeedFilesMigrateAndSeedRevisionsStayInMemoryHistory() {
        val root = Files.createTempDirectory("q2-seed-migration").toFile()
        val spanish = MemoryStore(root, true, testMemorySeedProvider(AppLanguageChoice.SPANISH)).forConversation("q2-migration-session")
        spanish.ensureInitialized()
        val originalHuman = spanish.readUserFile("human.md")
        val editedPersona = spanish.readUserFile("persona.md") + "\nUser-authored note: keep this unchanged.\n"
        spanish.write("persona.md", editedPersona, MemoryStore.Actor.USER, null)

        val english = MemoryStore(root, true, testMemorySeedProvider(AppLanguageChoice.ENGLISH)).forConversation("q2-migration-session")
        english.setEnabled(false)
        english.ensureInitialized()
        val seeds = testMemorySeedProvider(AppLanguageChoice.ENGLISH).english()
        assertEquals(seeds.index, english.readUserFile(MemoryConstants.ROOT_INDEX))
        assertEquals(seeds.human, english.readUserFile("human.md"))
        assertEquals(editedPersona, english.readUserFile("persona.md"))
        assertNotEquals(originalHuman, english.readUserFile("human.md"))
        assertTrue(english.readUserFile(MemoryConstants.ROOT_INDEX).contains("human.md"))
        assertTrue(english.readUserFile(MemoryConstants.ROOT_INDEX).contains("persona.md"))

        val humanRevisions = english.listRevisions("human.md", null)
        assertEquals(2, humanRevisions.size)
        assertEquals("SEED", humanRevisions.last().operation)
        assertEquals(originalHuman, humanRevisions.first().previousContent)
        assertEquals(seeds.human, humanRevisions.first().newContent)
        assertTrue(humanRevisions.all { it.conversationId == "q2-migration-session" })
        assertEquals(MemoryStore.Actor.USER, english.listRevisions("persona.md", null).first().actor)
    }

    @Test
    fun legacyEnglishReflectionStatusesMapToCodesAndRenderInTheSelectedLanguage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val previous = AppLanguageRuntime.current(context)
        val prefs = context.getSharedPreferences("jarvys_memory_reflection", android.content.Context.MODE_PRIVATE)
        val session = "q2-status-${System.nanoTime()}"
        try {
            AppLanguageRuntime.select(context, AppLanguageChoice.SPANISH)
            prefs.edit()
                .putString("session_${session}_status", "Reflection failed; will retry later")
                .putString("global_status", "No changes worth remembering")
                .commit()
            val reflection = MemoryReflectionPreferences(context)
            assertEquals(MemoryReflectionStatus.FAILED_RETRY, reflection.status(session))
            assertEquals(MemoryReflectionStatus.NO_CHANGES, reflection.globalStatus())
            assertEquals(AppLanguageRuntime.localizedContext(context).getString(R.string.reflection_status_partial),
                reflection.localizedStatus(MemoryReflectionStatus.PARTIAL))
            val spanishContext = AppLanguageRuntime.localizedContext(context)
            assertEquals(spanishContext.getString(R.string.reflection_failed_backoff),
                reflection.localizedStatus(reflection.status(session)))
            assertEquals(MemoryReflectionStatus.FAILED_RETRY, prefs.getString("session_${session}_status", ""))
            assertEquals(MemoryReflectionStatus.NO_CHANGES, prefs.getString("global_status", ""))
        } finally { AppLanguageRuntime.select(context, previous) }
    }

    @Test
    fun reflectionControlsHaveNonOverlappingBoundsAtFontScalesAndLocalesAcrossAllStatuses() {
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cases = listOf("en", "es").flatMap { language ->
            listOf(1f, 2f).flatMap { scale ->
                listOf(
                    Triple(false, "", 0L),
                    Triple(false, if (language == "es") "Aún no hay reflexiones" else "No reflections yet", 0L),
                    Triple(true, "", 0L),
                    Triple(false, if (language == "es") "La reflexión falló y esperará antes de volver a intentarlo." else "Reflection failed and will back off before trying again.", 1_700_000_000_000L),
                ).map { state -> Quad(language, scale, state.first, state.second, state.third) }
            }
        }
        var current by mutableStateOf(cases.first())
        compose.setContent {
            val localeConfig = android.content.res.Configuration(base.resources.configuration).apply {
                setLocale(Locale(current.language))
            }
            val localized = base.createConfigurationContext(localeConfig)
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localeConfig,
                LocalDensity provides Density(density.density, current.fontScale),
            ) {
                MaterialTheme {
                    Box(Modifier.width(380.dp).height(1_400.dp)) {
                        AutomaticMemoryReflectionCard(
                            memoryEnabled = true,
                            reflectionEnabled = true,
                            reflectionStatus = current.status,
                            lastReflectionMillis = current.lastReflectionMillis,
                            reflecting = current.reflecting,
                            onReflectionEnabledChange = {},
                            onReflectionDisclosure = {},
                        )
                    }
                }
            }
        }
        cases.forEach { state ->
            current = state
            compose.waitForIdle()
            assertReflectionLayoutDoesNotOverlap(state)
        }
    }

    @Test
    fun fullMemoryScreenOpensInNavigationForEnglishAndSpanishWithoutNestedScrollCrash() {
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = MemoryStore(Files.createTempDirectory("q2-memory-screen").toFile(), true,
            testMemorySeedProvider(AppLanguageChoice.ENGLISH)).forConversation("q2-session").also { it.ensureInitialized() }
        var language by mutableStateOf("en")
        compose.setContent {
            val config = android.content.res.Configuration(base.resources.configuration).apply { setLocale(Locale(language)) }
            val localized = base.createConfigurationContext(config)
            val activityResultOwner = rememberMemoryActivityResultOwner()
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides activityResultOwner) {
                    MaterialTheme {
                        val navController = rememberNavController()
                        NavHost(navController, startDestination = "home") {
                            composable("home") {
                                androidx.compose.material3.TextButton(onClick = { navController.navigate("memory") }) {
                                    androidx.compose.material3.Text("Open memory")
                                }
                            }
                            composable("memory") {
                                MemorySettingsScreen(
                                    store = store, enabled = true, onEnabledChange = {}, conversationId = "q2-session",
                                    onShowDisclosure = {}, onMemoryChanged = {}, reflectionEnabled = true,
                                    reflectionStatus = "", lastReflectionMillis = 0L, reflecting = false,
                                    onReflectionEnabledChange = {}, onReflectionDisclosure = {},
                                    onOpenHistory = {}, onOpenFile = { _, _ -> },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Open memory").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("memory-reflection-card").assertIsDisplayed()
        language = "es"
        compose.waitForIdle()
        compose.onNodeWithTag("memory-reflection-card").assertIsDisplayed()
        compose.onNodeWithText("Reflexión automática de memoria").assertIsDisplayed()
    }

    @Test
    fun memoryScreensContainNoInlineVisibleEnglishOrSpanishCopy() {
        val source = File("src/main/java/com/jarvys/agent/MemorySettingsScreen.kt").readText()
        val inlineVisible = Regex("(?:Text|toast|setTitle|setText|setHint|contentDescription)\\(\\s*\"([^\"\\n]+)")
            .findAll(source).map { it.groupValues[1] }
            .filter { it.any(Char::isLetter) && !it.startsWith("jarvys") && !it.contains("\${") }
            .toList()
        assertTrue("Memory screen has hardcoded visible copy: $inlineVisible", inlineVisible.isEmpty())
    }

    private fun assertReflectionLayoutDoesNotOverlap(state: Quad) {
        val card = compose.onNodeWithTag("memory-reflection-card").fetchSemanticsNode().boundsInRoot
        val statusZone = compose.onNodeWithTag("memory-reflection-status-zone").fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithTag("memory-reflection-status").fetchSemanticsNode().boundsInRoot
        val lastStatus = compose.onNodeWithTag("memory-reflection-last-status").fetchSemanticsNode().boundsInRoot
        val disclosure = compose.onNodeWithTag("memory-reflection-privacy-details").fetchSemanticsNode().boundsInRoot
        assertTrue("status content $status and $lastStatus must stay inside $statusZone",
            status.top >= statusZone.top && lastStatus.bottom <= statusZone.bottom)
        assertTrue("status zone $statusZone must finish before disclosure $disclosure", statusZone.bottom <= disclosure.top)
        assertTrue(card.top <= statusZone.top && card.bottom >= disclosure.bottom)
    }

    @androidx.compose.runtime.Composable
    private fun rememberMemoryActivityResultOwner(): ActivityResultRegistryOwner {
        val registry = androidx.compose.runtime.remember {
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                                             input: I, options: ActivityOptionsCompat?) { }
            }
        }
        return androidx.compose.runtime.remember(registry) {
            object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        }
    }

    private data class Quad(
        val language: String,
        val fontScale: Float,
        val reflecting: Boolean,
        val status: String,
        val lastReflectionMillis: Long,
    )
}
