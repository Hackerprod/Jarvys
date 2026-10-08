package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AppLanguagePolicyTest {
    private class MemoryPreferenceStore : AppLanguagePreferenceStore {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }

    @Test fun firstLaunchPersistsEnglishAndChoicesSurviveStoreRecreation() {
        val store = MemoryPreferenceStore()
        val preference = AppLanguagePreference(store)
        assertEquals(AppLanguageChoice.ENGLISH, preference.current())
        assertEquals("en", store.value)

        preference.select(AppLanguageChoice.SPANISH)
        assertEquals(AppLanguageChoice.SPANISH, AppLanguagePreference(store).current())
        preference.select(AppLanguageChoice.SYSTEM)
        assertEquals(AppLanguageChoice.SYSTEM, AppLanguagePreference(store).current())
        preference.select(AppLanguageChoice.ENGLISH)
        assertEquals(AppLanguageChoice.ENGLISH, AppLanguagePreference(store).current())
    }

    @Test fun choicesMapToSupportedBcp47TagsIncludingSystemDefault() {
        assertEquals("en", AppLanguagePolicy.localeTags(AppLanguageChoice.ENGLISH))
        assertEquals("es", AppLanguagePolicy.localeTags(AppLanguageChoice.SPANISH))
        assertEquals("", AppLanguagePolicy.localeTags(AppLanguageChoice.SYSTEM))
        assertEquals(AppLanguageChoice.SPANISH, AppLanguagePolicy.fromLocaleTags("es-MX"))
        assertEquals(AppLanguageChoice.ENGLISH, AppLanguagePolicy.fromLocaleTags("en-US"))
        assertEquals(AppLanguageChoice.SYSTEM, AppLanguagePolicy.fromLocaleTags(""))
        assertEquals(AppLanguageChoice.ENGLISH, AppLanguagePolicy.fromStoredValue(null))
    }

    @Test fun defaultAndSpanishResourceTablesHaveMatchingTranslatableKeys() {
        val root = projectRoot()
        assertResourceParity(root.resolve("src/main/res/values/strings.xml"), root.resolve("src/main/res/values-es/strings.xml"))
        assertResourceParity(root.resolve("src/full/res/values/google_strings.xml"), root.resolve("src/full/res/values-es/google_strings.xml"))
    }

    @Test fun defaultValuesContainEnglishNotSpanishCopy() {
        val root = projectRoot()
        val terms = Regex("\\b(está|están|puedes|puede|añade|añadir|conectado|desconectado|ajustes|guardar|cancelar|permite|herramientas|ningún|ninguna|dispositivo|memoria|idioma|búsqueda|eliminar|habilitado|deshabilitado)\\b|[áéíóúñ¿¡]", RegexOption.IGNORE_CASE)
        root.resolve("src").toPath().let { sourceRoot ->
            java.nio.file.Files.walk(sourceRoot).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".xml") && Regex("/res/values/[^/]+$").containsMatchIn(it.toString()) }
                    .forEach { path ->
                        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile())
                        val nodes = document.getElementsByTagName("string")
                        for (index in 0 until nodes.length) {
                            val element = nodes.item(index) as Element
                            if (element.getAttribute("translatable") != "false") {
                                val value = element.textContent.orEmpty()
                                assertFalse("Spanish text in ${path.fileName}:${element.getAttribute("name")}: $value", terms.containsMatchIn(value))
                            }
                        }
                    }
            }
        }
    }

    @Test fun localeMetadataDeclaresEnglishBaseAndSpanishSupport() {
        val root = projectRoot()
        val properties = File(root, "src/main/res/resources.properties").readText()
        val gradle = File(root, "build.gradle.kts").readText()
        val manifest = File(root, "src/main/AndroidManifest.xml").readText()
        assertTrue(properties.contains("unqualifiedResLocale=en-US"))
        assertTrue(gradle.contains("generateLocaleConfig = true"))
        assertTrue(File(root, "src/main/res/values-es/strings.xml").isFile)
        assertTrue("Generated locale metadata must be added to the merged manifest by AGP", 
            manifest.contains("localeConfig") || mergedManifests(root).any { it.readText().contains("localeConfig") })
    }

    @Test fun activityRetainsConversationIdentityAndDraftAcrossLocaleRecreation() {
        val source = File(projectRoot(), "src/main/java/com/jarvys/agent/MainActivity.kt").readText()
        assertTrue(source.contains("outState.putString(STATE_SESSION_ID, conversationSessionId)"))
        assertTrue(source.contains("savedInstanceState?.getString(STATE_SESSION_ID)"))
        assertTrue(source.contains("persistConversationSession(conversationSessionId)"))
        assertTrue(source.contains("restoreConversationSession(conversationSessionId)"))
        assertTrue(source.contains("outState.putString(STATE_GOAL, goalInput)"))
    }

    @Test fun baseManifestPermissionSetIncludesOnlyScopedLegacyDownloadStorage() {
        val manifest = File(projectRoot(), "src/main/AndroidManifest.xml")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest)
        val nodes = document.getElementsByTagName("uses-permission")
        val actual = buildSet {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index) as Element
                if (node.getAttributeNS("http://schemas.android.com/tools", "node") != "remove") {
                    add(node.getAttributeNS("http://schemas.android.com/apk/res/android", "name"))
                }
            }
        }
        assertEquals(setOf(
            "android.permission.INTERNET",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.READ_CONTACTS",
            "android.permission.CALL_PHONE",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
        ), actual)
        // UX16 adds only the platform permission needed by explicit Downloads taps on Android 7–9.
        // Modern Android must never request a broad-storage grant for this flow.
        val legacyStorage = (0 until nodes.length).map { nodes.item(it) as Element }.filter {
            it.getAttributeNS("http://schemas.android.com/apk/res/android", "name") == "android.permission.WRITE_EXTERNAL_STORAGE"
        }
        assertEquals(1, legacyStorage.size)
        assertEquals("28", legacyStorage.single().getAttributeNS("http://schemas.android.com/apk/res/android", "maxSdkVersion"))
        assertFalse(actual.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
    }

    @Test fun languageUiHasNoInlineCopyAndKeepsGoogleRestOutOfPlaySources() {
        val root = projectRoot()
        val files = listOf(
            "src/main/java/com/jarvys/agent/JarvysSettingsScreen.kt",
            "src/main/java/com/jarvys/agent/ui/settings/SettingsWorkspace.kt",
            "src/main/java/com/jarvys/agent/MainActivity.kt",
            "src/main/java/com/jarvys/agent/ui/chat/ChatComposer.kt",
            "src/main/java/com/jarvys/agent/ui/chat/ConversationDrawer.kt",
            "src/main/java/com/jarvys/agent/ui/chat/ConversationTimeline.kt",
            "src/main/java/com/jarvys/agent/ui/shell/JarvysRouteTopBar.kt",
            "src/main/java/com/jarvys/agent/ui/shell/JarvysShellFrame.kt",
            "src/main/java/com/jarvys/agent/ui/shell/ModelSelectorSheet.kt",
            "src/main/java/com/jarvys/agent/AssistantMarkdown.kt",
            "src/main/java/com/jarvys/agent/WorkspacePreviewScreen.kt",
            "src/main/java/com/jarvys/agent/CodexOAuthManager.java",
            "src/main/java/com/jarvys/agent/StopOverlayService.java",
            "src/main/java/com/jarvys/agent/AgentForegroundService.java",
            "src/main/java/com/artemis/helper/ArtemisAccessibilityService.java",
            "src/main/java/com/jarvys/agent/connectors/ConnectorsScreen.kt",
            "src/main/java/com/jarvys/agent/connectors/RemoteServicesSection.kt",
            "src/main/java/com/jarvys/agent/mcp/McpServersScreen.kt",
            "src/main/java/com/jarvys/agent/ui/mcp/McpWorkspaceScreens.kt",
            "src/main/java/com/jarvys/agent/providers/ProvidersNavigation.kt",
            "src/main/java/com/jarvys/agent/providers/ProvidersRepository.kt",
            "src/main/java/com/jarvys/agent/providers/ProvidersScreens.kt",
            "src/main/java/com/jarvys/agent/providers/ProviderModelFields.kt",
            "src/main/java/com/jarvys/agent/providers/CodexDeviceCodeFlow.kt",
            "src/main/java/com/jarvys/agent/skills/SkillsScreen.kt",
            "src/main/java/com/jarvys/agent/ui/skills/SkillsWorkspace.kt",
        ).map { File(root, it) }
        val visibleLiteral = Regex("(?:Text|toast|setTitle|setText|setHint)\\(\\s*\"([^\"\\n]+)")
        val allowed = setOf("J", "Jarvys", "⌘")
        files.forEach { file ->
            val literals = visibleLiteral.findAll(file.readText()).map { it.groupValues[1] }
                .filter { it.any(Char::isLetter) && it !in allowed && !it.startsWith("http") && !it.startsWith("\$") }
                .toList()
            assertTrue("Inline visible copy remains in ${file.name}: $literals", literals.isEmpty())
        }
        val playSources = File(root, "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        listOf("gmail.googleapis.com/gmail/v1", "www.googleapis.com/drive/v3", "GoogleWorkspaceConnectors").forEach {
            assertFalse("Play common source contains Full-only Google API identifier $it", playSources.contains(it))
        }
    }

    private fun assertResourceParity(defaultFile: File, translatedFile: File) {
        val defaultKeys = resourceKeys(defaultFile)
        val translatedKeys = resourceKeys(translatedFile)
        assertEquals("Missing translated resources in ${translatedFile.path}", defaultKeys, translatedKeys)
    }

    private fun resourceKeys(file: File): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return buildSet {
            listOf("string", "plurals", "string-array").forEach { tag ->
                val nodes = document.getElementsByTagName(tag)
                for (index in 0 until nodes.length) {
                    val element = nodes.item(index) as Element
                    if (element.getAttribute("translatable") != "false") add("$tag:${element.getAttribute("name")}")
                }
            }
        }
    }

    private fun mergedManifests(root: File): List<File> {
        val directory = File(root, "build/intermediates/merged_manifests")
        if (!directory.exists()) return emptyList()
        return directory.walkTopDown().filter { it.isFile && it.name == "AndroidManifest.xml" }.toList()
    }

    private fun projectRoot(): File = File(requireNotNull(System.getProperty("user.dir")))
}
