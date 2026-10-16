package com.jarvys.agent.modules

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.jarvys.agent.SecretStore
import com.jarvys.agent.CoreToolResult
import com.jarvys.agent.connectors.ActionPolicyDefaults
import com.jarvys.agent.connectors.ApprovalGate
import com.jarvys.agent.connectors.AutonomyPolicy
import com.jarvys.agent.connectors.ConnectorConnectionPreferences
import com.jarvys.agent.connectors.ConnectorDefinition
import com.jarvys.agent.connectors.ConnectorPresentationGroup
import com.jarvys.agent.connectors.ConnectorRegistry
import com.jarvys.agent.connectors.visibleConnectorCatalogEntries
import com.jarvys.agent.mcp.McpServerRepository
import com.jarvys.agent.skills.SkillMarkdownParser
import com.jarvys.agent.skills.SkillRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModuleBundleIntegrationTest {
    private val skillRepositoryField = SkillRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val mcpRepositoryField = McpServerRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val secretStoreField = SecretStore::class.java.getDeclaredField("singleton").apply { isAccessible = true }
    private var previousSkills: Any? = null
    private var previousMcp: Any? = null
    private var previousSecrets: Any? = null
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun isolateSkillDependencies() {
        previousSkills = skillRepositoryField.get(null)
        previousMcp = mcpRepositoryField.get(null)
        previousSecrets = secretStoreField.get(null)
        skillRepositoryField.set(null, null)
        mcpRepositoryField.set(null, null)
        val secretConstructor = SecretStore::class.java.getDeclaredConstructor(android.content.SharedPreferences::class.java)
            .apply { isAccessible = true }
        secretStoreField.set(null, secretConstructor.newInstance(
            context.getSharedPreferences("module-integration-secrets", Context.MODE_PRIVATE),
        ))
    }

    @After
    fun restoreSkillDependencies() {
        skillRepositoryField.set(null, previousSkills)
        mcpRepositoryField.set(null, previousMcp)
        secretStoreField.set(null, previousSecrets)
    }

    @Test
    fun bundledManifestsConnectorsAndSkillsAreDiscoveredGenerically() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = ModuleRegistry.fromAssets(context)
        val chart = registry.module("com.jarvys.module.chart")!!
        val presentation = registry.module("com.jarvys.module.presentation")!!

        assertEquals(listOf("create_chart"), chart.tools.map { it.name })
        assertEquals(listOf("create_presentation"), presentation.tools.map { it.name })
        assertEquals("viewer/chart.html", chart.artifactTypes.single().viewer)
        assertTrue(chart.artifactTypes.single().generatedThumbnail)
        assertEquals("viewer/presentation.html", presentation.artifactTypes.single().viewer)
        assertTrue(presentation.artifactTypes.single().generatedThumbnail)
        assertEquals(listOf("skills/chart/SKILL.md"), chart.skills)
        assertEquals(listOf("skills/presentation/SKILL.md"), presentation.skills)

        listOf(chart, presentation).forEach { module ->
            module.tools.forEach { tool ->
                val (handlerPath, exportName) = tool.handler.split('#', limit = 2)
                val handler = context.assets.open("${module.bundleAssetDirectory}/$handlerPath")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                assertTrue("${module.id} must export its manifest handler", handler.contains("global.$exportName"))
                val encodedDirectory = module.bundleAssetDirectory.split('/').joinToString("/") { Uri.encode(it) }
                val encodedHandler = handlerPath.split('/').joinToString("/") { Uri.encode(it) }
                val handlerUrl = Uri.parse(
                    "https://${ModuleWebViewSecurity.ASSET_AUTHORITY}/assets/$encodedDirectory/$encodedHandler",
                )
                assertEquals(
                    "${module.id} handler URL must pass the WebView asset filter",
                    handlerPath,
                    ModuleWebViewSecurity.moduleAssetPath(handlerUrl, module.bundleAssetDirectory),
                )
                assertTrue("${module.id} must deliver bytes through the host capability", handler.contains("artifactDeliverBytes"))
                assertFalse("${module.id} handler must not load remote scripts", Regex("https?://", RegexOption.IGNORE_CASE).containsMatchIn(handler))
            }
            val artifact = module.artifactTypes.single()
            context.assets.open("${module.bundleAssetDirectory}/${artifact.viewer}").use { assertTrue(it.read() >= 0) }
            assertFalse(context.assets.list("${module.bundleAssetDirectory}/viewer").orEmpty().contains("thumbnail.svg"))
            module.skills.forEach { path ->
                val markdown = context.assets.open("${module.bundleAssetDirectory}/$path")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                assertEquals(module.tools.map { it.name }, SkillMarkdownParser.parse(markdown).metadata.allowedTools)
            }
        }

        val connectors = ConnectorRegistry.createForTests(object : ConnectorConnectionPreferences {
            private val connected = mutableMapOf<String, Boolean>()
            override fun isConnected(id: String) = connected[id] == true
            override fun setConnected(id: String, connected: Boolean) { this.connected[id] = connected }
            override fun hasConnectionChoice(id: String) = id in connected
        }, { true }, ApprovalGate.INSTANCE)
        val installed = registry.installConnectorDefinitions(connectors, ModuleRuntime { _, _, _ ->
            CoreToolResult.success("ok")
        }).associateBy { it.id }

        val ordinaryConnector = ConnectorDefinition("fixture.connector", "Fixture connector", "1", "A regular connector")
        connectors.register(ordinaryConnector)

        assertEquals(registry.modules().map { it.id }.toSet(), installed.keys)
        assertEquals(ConnectorPresentationGroup.ON_DEVICE, installed.getValue(chart.id).presentationGroup)
        assertEquals("create_chart", installed.getValue(chart.id).operations.single().name)
        assertEquals("create_presentation", installed.getValue(presentation.id).operations.single().name)
        assertEquals(listOf(ordinaryConnector), visibleConnectorCatalogEntries(connectors.definitions.value))
        assertEquals(AutonomyPolicy.ALLOW, ActionPolicyDefaults.descriptor(chart.id, "create_chart").defaultPolicy)
        assertEquals(AutonomyPolicy.ALLOW, ActionPolicyDefaults.descriptor(presentation.id, "create_presentation").defaultPolicy)

        val discoveredSkills = SkillRepository.get(context).enabledForRun().associateBy { it.metadata.id }
        assertEquals(listOf("create_chart"), discoveredSkills.getValue("jarvys-chart").metadata.allowedTools)
        assertEquals(listOf("create_presentation"), discoveredSkills.getValue("jarvys-presentation").metadata.allowedTools)
    }

    @Test
    fun eachDeclaredHandlerRunsWithAJavaScriptEngineAndProducesItsArtifactContract() {
        val version = try {
            ProcessBuilder("node", "--version").redirectErrorStream(true).start().let { process ->
                if (!process.waitFor(10, TimeUnit.SECONDS)) return@let false
                process.exitValue() == 0
            }
        } catch (missing: IOException) {
            false
        }
        assumeTrue("Node.js is needed only for the JVM JavaScript contract test", version)

        val registry = ModuleRegistry.fromAssets(context)
        val script = File.createTempFile("module-handler-contract-", ".cjs", context.cacheDir).apply {
            ModuleBundleIntegrationTest::class.java.getResourceAsStream("/module-handler-contract.cjs")!!.use { input ->
                outputStream().use { output -> input.copyTo(output) }
            }
        }
        val tempRoot = Files.createTempDirectory(context.cacheDir.toPath(), "p2-module-js-").toFile()
        try {
            registry.modules().filter { it.id in setOf("com.jarvys.module.chart", "com.jarvys.module.presentation") }
                .forEach { module ->
                val moduleDirectory = File(tempRoot, module.id)
                copyAssetTree("${module.bundleAssetDirectory}", moduleDirectory)
                val log = File(tempRoot, "${module.id}.log")
                val process = ProcessBuilder(
                    "node", "--experimental-vm-modules", script.absolutePath, moduleDirectory.absolutePath,
                ).redirectErrorStream(true).redirectOutput(log).start()
                assertTrue("JavaScript handler for ${module.id} did not finish", process.waitFor(60, TimeUnit.SECONDS))
                assertEquals(log.readText(), 0, process.exitValue())
                assertTrue(log.readText(), log.readText().contains("node-module-ok ${module.id}"))
            }
        } finally {
            tempRoot.deleteRecursively()
            script.delete()
        }
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val entries = context.assets.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> destination.outputStream().use(input::copyTo) }
        } else {
            destination.mkdirs()
            entries.forEach { entry -> copyAssetTree("$assetPath/$entry", File(destination, entry)) }
        }
    }
}
