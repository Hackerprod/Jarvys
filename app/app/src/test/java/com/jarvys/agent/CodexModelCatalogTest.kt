package com.jarvys.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class CodexModelCatalogTest {
    @Test
    fun offlineFallbackContainsCurrentlyAllowedCodexModels() {
        assertEquals(
            listOf("gpt-5.4", "gpt-5.3-codex-spark", "gpt-6-astra", "gpt-5.4-mini", "gpt-5.6-luna", "gpt-5.5", "gpt-6-luna", "gpt-5.6-terra", "gpt-5.6-sol", "gpt-6-sol"),
            CodexModelCatalog.currentModels().map { it.id },
        )
        CodexModelCatalog.currentModels().forEach { model ->
            assertTrue(model.contextLimit > 0)
            assertTrue(model.outputLimit > 0)
            assertTrue("image" in model.inputModalities)
            assertTrue(model.variants.isNotEmpty())
            assertTrue(model.variants.any { it.id == "medium" })
        }
    }

    @Test
    fun openCodeFilterIsAppliedInItsSpecifiedOrder() {
        assertTrue(CodexModelCatalog.shouldInclude("gpt-5.4"))
        assertTrue(CodexModelCatalog.shouldInclude("gpt-5.3-codex-spark"))
        assertFalse(CodexModelCatalog.shouldInclude("gpt-5.4", "pro"))
        assertFalse(CodexModelCatalog.shouldInclude("gpt-5.5-pro"))
        assertFalse(CodexModelCatalog.shouldInclude("gpt-5.6"))
        assertTrue(CodexModelCatalog.shouldInclude("gpt-5.4-mini"))
        assertTrue(CodexModelCatalog.shouldInclude("gpt-5.5"))
        assertTrue(CodexModelCatalog.shouldInclude("gpt-6-luna"))
        assertFalse(CodexModelCatalog.shouldInclude("o4-mini"))
    }

    @Test
    fun modelsDevParsesOpenRouterModelsAndPreservesOpenAiModelsWithoutVariants() {
        val root = JSONObject("""
            {"openai":{"models":{"gpt-5.8":{"id":"gpt-5.8","name":"GPT-5.8"}}},
             "openrouter":{"models":{"vendor/model":{"id":"vendor/model","name":"Known model",
               "limit":{"context":65536,"output":8192}}}}}
        """.trimIndent())
        val openAi = CodexModelCatalog.parseModelsDev(root)
        assertEquals(listOf("gpt-5.8"), openAi.map { it.id })
        assertTrue(openAi.single().variants.isEmpty())

        val openRouter = CodexModelCatalog.parseOpenRouterModelsDev(root)
        assertEquals(listOf(OpenRouterModelInfo("vendor/model", "Known model", 65536, 8192)), openRouter)
    }

    @Test
    fun apiKeyModelCatalogRequiresToolCallingAndTextInputAndOutput() {
        val root = JSONObject("""
            {"openai":{"models":{
              "gpt-4.1":{"id":"gpt-4.1","name":"GPT-4.1","tool_call":true,
                "modalities":{"input":["text","image"],"output":["text"]},"limit":{"context":1047576,"output":32768}},
              "no-tools":{"tool_call":false,"modalities":{"input":["text"],"output":["text"]}},
              "image-only":{"tool_call":true,"modalities":{"input":["image"],"output":["text"]}},
              "non-text-output":{"tool_call":true,"modalities":{"input":["text"],"output":["audio"]}}
            }}}}
        """.trimIndent())
        val models = CodexModelCatalog.parseOpenAiApiModelsDev(root)
        assertEquals(listOf("gpt-4.1"), models.map { it.id })
        assertEquals(1_047_576, models.single().contextLimit)
        assertEquals(32_768, models.single().outputLimit)
        assertTrue(models.single().variants.isEmpty())
    }

    @Test
    fun modelsDevApiKeyCatalogKeepsOnlyTextModelsWithToolCalling() {
        val root = JSONObject("""
            {"openai":{"models":{
              "gpt-4.1":{"id":"gpt-4.1","name":"GPT-4.1","tool_call":true,
                "modalities":{"input":["text","image"],"output":["text"]},"limit":{"context":1047576,"output":32768}},
              "text-no-tools":{"tool_call":false,"modalities":{"input":["text"],"output":["text"]}},
              "image-only":{"tool_call":true,"modalities":{"input":["image"],"output":["text"]}},
              "non-text-output":{"tool_call":true,"modalities":{"input":["text"],"output":["audio"]}}
            }}}}
        """.trimIndent())
        val models = CodexModelCatalog.parseOpenAiApiModelsDev(root)
        assertEquals(listOf("gpt-4.1"), models.map { it.id })
        assertEquals(1_047_576, models.single().contextLimit)
        assertEquals(32_768, models.single().outputLimit)
        assertTrue(models.single().variants.isEmpty())
    }

}
