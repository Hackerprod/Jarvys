package com.jarvys.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McpEditorNavigationPolicyTest {
    @Test fun cleanExitReturnsToOriginWithoutConfirmationButDirtyExitAsksFirst() {
        assertEquals(McpEditorBackDecision(false, McpEditorReturnTarget.SERVER_LIST),
            McpEditorNavigationPolicy.back(isDirty = false, editingExistingServer = false))
        assertEquals(McpEditorBackDecision(true, McpEditorReturnTarget.SERVER_LIST),
            McpEditorNavigationPolicy.back(isDirty = true, editingExistingServer = false))
        assertEquals(McpEditorBackDecision(false, McpEditorReturnTarget.SERVER_DETAIL),
            McpEditorNavigationPolicy.back(isDirty = false, editingExistingServer = true))
        assertEquals(McpEditorBackDecision(true, McpEditorReturnTarget.SERVER_DETAIL),
            McpEditorNavigationPolicy.back(isDirty = true, editingExistingServer = true))
    }

    @Test fun editorValidationCoversAliasUrlBearerAndEveryAuthMode() {
        assertEquals(McpServerEditorValidationError.ALIAS_REQUIRED,
            McpServerEditorValidation.validate(" ", "https://example.com/mcp", McpTransport.AUTO, McpAuthMode.NONE, false, false, false))
        assertEquals(McpServerEditorValidationError.URL_REQUIRED,
            McpServerEditorValidation.validate("Server", " ", McpTransport.AUTO, McpAuthMode.NONE, false, false, false))
        assertEquals(McpServerEditorValidationError.URL_INVALID,
            McpServerEditorValidation.validate("Server", "javascript:alert(1)", McpTransport.AUTO, McpAuthMode.NONE, false, false, false))
        assertEquals(McpServerEditorValidationError.BEARER_REQUIRED,
            McpServerEditorValidation.validate("Server", "https://example.com/mcp", McpTransport.STREAMABLE_HTTP, McpAuthMode.BEARER, false, false, false))
        assertEquals(McpServerEditorValidationError.TOKEN_REENTER,
            McpServerEditorValidation.validate("Server", "https://example.net/mcp", McpTransport.LEGACY_SSE, McpAuthMode.BEARER, false, true, true))
        assertEquals(listOf(McpTransport.AUTO, McpTransport.STREAMABLE_HTTP, McpTransport.LEGACY_SSE), McpServerEditorValidation.transports)
        assertEquals(listOf(McpAuthMode.NONE, McpAuthMode.BEARER, McpAuthMode.OAUTH), McpServerEditorValidation.authModes)
        McpServerEditorValidation.transports.forEach { transport ->
            McpServerEditorValidation.authModes.forEach { auth ->
                val token = auth == McpAuthMode.BEARER
                assertEquals(null, McpServerEditorValidation.validate(
                    "Server", "http://localhost:8000/mcp", transport, auth, token, false, false))
            }
        }
    }

    @Test fun mcpEditorIsRouteScopedContentWithOneDiscardBackHandler() {
        val source = File(sourceRoot(), "com/jarvys/agent/ui/mcp/McpWorkspaceScreens.kt").readText()
        assertFalse(source.contains("import androidx.compose.ui.window.Dialog"))
        assertFalse(source.contains("DialogProperties"))
        assertTrue(source.contains("private fun McpServerEditor"))
        assertTrue(File(sourceRoot(), "com/jarvys/agent/JarvysUiKit.kt").readText().contains("fillMaxSize().imePadding()"))
        assertFalse(source.contains("JarvysScreen("))
        assertFalse(source.contains("JarvysTopAppBar("))
        assertEquals(1, Regex("BackHandler\\(").findAll(source).count())
        assertTrue(source.contains("rememberSaveable(initial?.id)"))
        assertTrue(source.contains("JarvysTextField("))
        assertTrue(source.contains("JarvysChoiceGroup("))
        assertTrue(source.contains("JarvysSwitchRow("))
        assertTrue(source.contains("JarvysPrimaryButton("))
    }

    private fun sourceRoot(): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return sequenceOf(File(working, "src/main/java"), File(working, "app/src/main/java"),
            File(working.parentFile, "app/src/main/java")).firstOrNull(File::isDirectory)
            ?: error("Could not find app/src/main/java from ${working.path}")
    }
}
