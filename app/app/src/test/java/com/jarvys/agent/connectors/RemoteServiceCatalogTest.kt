package com.jarvys.agent.connectors

import com.jarvys.agent.mcp.McpAuthMode
import com.jarvys.agent.mcp.McpCatalogOAuthDiscovery
import com.jarvys.agent.mcp.McpServerConfig
import com.jarvys.agent.mcp.McpToolAccess
import com.jarvys.agent.mcp.McpToolAnnotations
import com.jarvys.agent.mcp.McpToolConfig
import com.jarvys.agent.mcp.McpToolSecurity
import com.jarvys.agent.mcp.McpTransport
import com.jarvys.agent.BrandIcons
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI

class RemoteServiceCatalogTest {
    @Test fun connectorRowModelCanExposeOnlyNameShortStatusAndChevron() {
        val statuses = listOf(ConnectorShortStatus.CONNECTED, ConnectorShortStatus.CONNECTING,
            ConnectorShortStatus.REAUTHORIZE, ConnectorShortStatus.ERROR, ConnectorShortStatus.PERMISSION_PENDING)
        statuses.forEach { status ->
            val row = ConnectorPresentation.row("GitHub", status)
            assertEquals("GitHub", row.name)
            assertEquals(status, row.status)
            assertTrue(row.hasChevron)
        }
        val disconnected = ConnectorPresentation.row("GitHub", null)
        assertNull(disconnected.status)
        assertEquals(setOf("name", "status", "hasChevron"), ConnectorRowPresentation::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }.map { it.name }.toSet())
        assertFalse(ConnectorRowPresentation::class.java.declaredFields.any { field ->
            listOf("url", "endpoint", "risk", "privacy", "description", "note").any { field.name.contains(it, true) }
        })
    }

    @Test fun detailModelKeepsMainPresentationConciseAndSeparatesSessionActions() {
        val disconnected = ConnectorPresentation.detail("GitHub", "Desconectado",
            "Conecta tu espacio de trabajo", connected = false)
        assertEquals("Desconectado", disconnected.subtitle)
        assertEquals("Conecta tu espacio de trabajo", disconnected.summary)
        assertEquals(ConnectorPrimaryAction.CONNECT, disconnected.primaryAction)
        assertFalse(disconnected.canDisconnect)

        val expired = ConnectorPresentation.detail("GitHub", "Reconnect", "Account access", connected = true, reauthorize = true)
        assertEquals(ConnectorPrimaryAction.REAUTHORIZE, expired.primaryAction)
        assertTrue(expired.canDisconnect)
        assertTrue(expired.advancedCollapsedByDefault)
        val ready = ConnectorPresentation.detail("GitHub", "Connected", "Account", connected = true)
        assertEquals(ConnectorPrimaryAction.NONE, ready.primaryAction)
        assertTrue(ready.canDisconnect)
        assertEquals(setOf("name", "subtitle", "summary", "connected", "primaryAction", "canDisconnect", "advancedCollapsedByDefault"),
            ConnectorDetailPresentation::class.java.declaredFields.filterNot { it.isSynthetic || it.name.startsWith("$") }
                .map { it.name }.toSet())
        assertFalse(ConnectorDetailPresentation::class.java.declaredFields.any { field ->
            listOf("url", "endpoint", "risk", "privacy", "scope").any { field.name.contains(it, true) }
        })
    }

    @Test fun everyRemoteCatalogBrandResolvesToAnOfficialIconOrTheCanvaLetterFallback() {
        RemoteServiceCatalog.services.forEach { service ->
            if (service.id == "canva") assertEquals("canva", service.brandIconId)
            else {
                val mark = requireNotNull(BrandIcons.forService(service.brandIconId)) { service.id }
                assertTrue(mark.color != androidx.compose.ui.graphics.Color.Unspecified)
                assertEquals(24f, mark.vector.viewportHeight, 0f)
            }
        }
        assertNull(BrandIcons.forService("canva"))
        assertTrue(listOf("github", "notion", "linear", "atlassian", "asana", "sentry", "vercel")
            .all { BrandIcons.forService(it) != null })
        val source = File(requireNotNull(System.getProperty("user.dir")), "src/main/java/com/jarvys/agent/BrandIcons.kt")
        assertFalse(source.readText().contains("import androidx.compose.material"))
    }

    @Test fun connectorScreenComposableSourcesUseResourcesForCopyAndBrandNoticesAreRecorded() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val sources = listOf(
            File(root, "src/main/java/com/jarvys/agent/connectors/ConnectorsScreen.kt"),
            File(root, "src/main/java/com/jarvys/agent/connectors/RemoteServicesSection.kt"),
            File(root, "src/full/java/com/jarvys/agent/flavor/FullRemoteServicesPanel.kt"),
        )
        val textCalls = Regex("\\bText\\(\\s*\"([^\"\\n]*)\"")
        sources.forEach { source ->
            val hardcoded = textCalls.findAll(source.readText()).map { it.groupValues[1] }
                .firstOrNull { literal -> !literal.startsWith("http://") && !literal.startsWith("https://") &&
                    '$' !in literal && literal.any(Char::isLetter) }
            assertNull("Hardcoded composable copy in ${source.name}: $hardcoded", hardcoded)
        }
        val notice = File(root.parentFile, "NOTICE.md").readText()
        assertTrue(notice.contains("Simple Icons 16.33.0"))
        assertTrue(notice.contains("CC0-1.0"))
        assertTrue(notice.contains("nominative"))
    }

    @Test fun playCommonSourcesDoNotContainGoogleRestEndpoints() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val common = File(root, "src/main/java")
        val forbidden = listOf("gmail.googleapis.com/gmail/v1", "www.googleapis.com/drive/v3", "GoogleRemoteServicesPanel")
        val text = common.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        forbidden.forEach { assertFalse("Play-common source included $it", text.contains(it)) }
    }

    @Test fun catalogHasOnlyLiveDcrProvidersAndTheExistingPatService() {
        assertEquals(listOf("github", "notion", "linear", "atlassian", "asana", "sentry", "vercel", "canva"),
            RemoteServiceCatalog.services.map { it.id })
        assertEquals(RemoteServiceCatalog.services.size, RemoteServiceCatalog.services.map { it.id }.toSet().size)
        RemoteServiceCatalog.services.forEach { service ->
            assertTrue("${service.id} endpoint must be exact HTTPS", service.endpoint.startsWith("https://"))
            assertTrue(service.nameResourceId != 0)
            assertTrue(service.documentationUrl.startsWith("https://"))
        }
        val github = requireNotNull(RemoteServiceCatalog.find("github"))
        assertEquals("https://api.githubcopilot.com/mcp/", github.endpoint)
        assertEquals(RemoteServiceAuthMode.PAT, github.authMode)
        assertTrue(github.verifiedReadToolNames?.contains("get_file_contents") == true)
        assertEquals(40, RemoteServiceCatalog.MAX_DEFAULT_READ_TOOLS_PER_SERVER)
        listOf("stripe", "cloudflare", "todoist", "slack", "figma", "supabase").forEach {
            assertNull("$it is deliberately not in the one-tap catalog", RemoteServiceCatalog.find(it))
        }
    }

    @Test fun catalogDcrEndpointsScopesAndTransportsMatchTheEvidenceMatrix() {
        val notion = requireNotNull(RemoteServiceCatalog.find("notion"))
        assertEquals("https://mcp.notion.com/mcp", notion.endpoint)
        assertEquals(setOf("default"), notion.requestedScopes)
        assertEquals(RemoteServiceAuthMode.OAUTH_DCR, notion.authMode)

        val linear = requireNotNull(RemoteServiceCatalog.find("linear"))
        assertEquals("https://mcp.linear.app/mcp", linear.endpoint)
        assertEquals(setOf("read"), linear.requestedScopes)

        val atlassian = requireNotNull(RemoteServiceCatalog.find("atlassian"))
        assertEquals("https://mcp.atlassian.com/v1/mcp", atlassian.endpoint)
        assertNull(atlassian.requestedScopes)
        assertEquals("https://mcp.atlassian.com/.well-known/oauth-authorization-server", atlassian.authorizationServerMetadataUrl)

        val asana = requireNotNull(RemoteServiceCatalog.find("asana"))
        assertEquals("https://mcp.asana.com/sse", asana.endpoint)
        assertEquals(McpTransport.AUTO, asana.transport)
        assertEquals(setOf("default"), asana.requestedScopes)

        assertEquals(setOf("org:read"), RemoteServiceCatalog.find("sentry")?.requestedScopes)
        assertEquals(setOf("openid"), RemoteServiceCatalog.find("vercel")?.requestedScopes)
        assertEquals(setOf("profile:read", "design:read", "folder:read", "brandtemplate:read", "comment:read",
            "asset:read", "brandkit:read", "help:read"), RemoteServiceCatalog.find("canva")?.requestedScopes)
        assertTrue(RemoteServiceCatalog.services.filter { it.authMode == RemoteServiceAuthMode.OAUTH_DCR }
            .all { it.verifiedReadToolNames.isNullOrEmpty() })
    }

    @Test fun atlassianDirectAuthorizationServerDiscoveryUsesFakeSameOriginMetadataServer() {
        class FakeMetadataServer {
            val requestedUrls = mutableListOf<String>()
            private val routes = mapOf(
                "https://mcp.atlassian.com/.well-known/oauth-authorization-server" to JSONObject()
                    .put("issuer", "https://mcp.atlassian.com")
                    .put("authorization_endpoint", "https://mcp.atlassian.com/v1/authorize")
                    .put("token_endpoint", "https://mcp.atlassian.com/v1/token")
                    .put("registration_endpoint", "https://mcp.atlassian.com/v1/register")
                    .put("code_challenge_methods_supported", JSONArray().put("S256"))
                    .put("token_endpoint_auth_methods_supported", JSONArray().put("none")),
            )
            fun get(url: String): JSONObject {
                requestedUrls += url
                return routes[url]?.let { JSONObject(it.toString()) }
                    ?: error("Fake AS metadata endpoint did not exist: $url")
            }
        }
        val fake = FakeMetadataServer()
        val metadata = McpCatalogOAuthDiscovery.directAuthorizationServerMetadata(
            "atlassian", URI("https://mcp.atlassian.com/v1/mcp"), fake::get,
        )
        assertNotNull(metadata)
        assertEquals(listOf("https://mcp.atlassian.com/.well-known/oauth-authorization-server"), fake.requestedUrls)
        assertEquals("https://mcp.atlassian.com", metadata?.optString("issuer"))
        assertNull(McpCatalogOAuthDiscovery.directAuthorizationServerMetadata(
            "notion", URI("https://mcp.notion.com/mcp"), fake::get,
        ))
        assertTrue(runCatching {
            McpCatalogOAuthDiscovery.directAuthorizationServerMetadata("atlassian", URI("https://evil.example/mcp"), fake::get)
        }.isFailure)
    }

    @Test fun oauthScopeOverrideIsCatalogOnlyAndCustomServersKeepTheirDiscoveredScopes() {
        val protected = JSONObject().put("scopes_supported", JSONArray().put("server:read").put("server:write"))
        assertEquals("read", McpCatalogOAuthDiscovery.requestedScope("linear", "Bearer scope=\"read write\"", protected))
        assertEquals("default", McpCatalogOAuthDiscovery.requestedScope("notion", "", protected))
        assertEquals("read write", McpCatalogOAuthDiscovery.requestedScope(null, "Bearer scope=\"read write\"", protected))
        assertEquals("server:read server:write", McpCatalogOAuthDiscovery.requestedScope("custom-id", "", protected))
        assertNull(McpCatalogOAuthDiscovery.requestedScope("custom-id", "", JSONObject()))
    }

    @Test fun allNewServicesClassifyAsDisabledWritesUntilNamesAreActuallyVerified() {
        val untrusted = McpToolConfig("fake_read", "", "untrusted tool", "{}",
            enabled = false, annotations = McpToolAnnotations(readOnlyHint = true))
        RemoteServiceCatalog.services.filter { it.id != "github" }.forEach { service ->
            val config = McpServerConfig(service.mcpServerId, service.id, service.endpoint,
                authMode = McpAuthMode.OAUTH, tools = emptyList(), catalogServiceId = service.id)
            val discovered = McpToolSecurity.mergeDiscoveredTools(config, listOf(untrusted)).single()
            assertFalse("${service.id}: new MCP tool must start disabled", discovered.enabled)
            assertEquals("${service.id}: annotations are hints, not a read allowlist", McpToolAccess.WRITE,
                McpToolSecurity.classify(service.id, untrusted.wireName, untrusted.annotations))
            assertTrue(service.verifiedReadToolNames.isNullOrEmpty())
        }
    }

    @Test fun providerUsageGuidanceHasNoQuotedPromptInjectionSamples() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val values = sequenceOf(File(root, "src/main/res/values/strings.xml"),
            File(root, "app/src/main/res/values/strings.xml")).first { it.isFile }
        val rows = values.readLines().filter { line ->
            Regex("name=\"remote_service_(?:github|notion|linear|atlassian|asana|sentry|vercel|canva)_usage_note\"").containsMatchIn(line)
        }
        assertEquals(RemoteServiceCatalog.services.size, rows.size)
        val exampleCue = Regex("(?i)\\b(?:such as|e\\.g\\.|for example)\\s+[‘'“\"]")
        rows.forEach { line ->
            val value = line.substringAfter('>').substringBefore("</string>")
            assertFalse("Usage note contains a quoted example: $line", exampleCue.containsMatchIn(value))
        }
    }
}
