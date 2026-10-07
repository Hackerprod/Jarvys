package com.jarvys.agent.ui.shell

import com.jarvys.agent.ModelInfo
import com.jarvys.agent.OpenRouterModelInfo
import com.jarvys.agent.ProviderSettings.Provider
import com.jarvys.agent.providers.CustomEndpointModel
import org.junit.Assert.*
import org.junit.Test

class ModelSelectionPresentationTest {
    private fun model(id: String, name: String) = ModelInfo(id, name, 0, 0, emptySet(), emptySet(), emptyList())

    @Test fun optionsStayWithinCurrentProvider() {
        val codex = listOf(model("codex", "Codex"))
        val api = listOf(model("api", "API"))
        val router = listOf(OpenRouterModelInfo("router", "Router", 0, 0))
        val custom = listOf(CustomEndpointModel("custom", "Custom", 0, 0))
        for ((provider, id) in listOf(Provider.OPENAI_CODEX to "codex", Provider.OPENAI_API to "api", Provider.OPENROUTER to "router", Provider.CUSTOM to "custom")) {
            assertEquals(listOf(id), quickModelOptions(provider, codex, api, router, custom).map { it.id })
        }
    }

    @Test fun optionsKeepFirstDuplicateAndFillBlankNames() {
        val options = quickModelOptions(Provider.OPENAI_CODEX,
            listOf(model("a", "First"), model("a", "Second"), model("b", "  ")), emptyList(), emptyList(), emptyList())
        assertEquals(listOf(QuickModelOption("a", "First"), QuickModelOption("b", "b")), options)
    }

    @Test fun displayNamePreservesUnknownAndManualModelIds() {
        val models = listOf(QuickModelOption("a", "Model A"), QuickModelOption("b", " "))
        assertEquals("Model A", quickModelDisplayName(models, "a"))
        assertEquals("b", quickModelDisplayName(models, "b"))
        assertEquals("manual/id", quickModelDisplayName(models, "manual/id"))
    }
}
