package com.jarvys.agent

import org.junit.Assert.*
import org.junit.Test

class MascotAnimationToolTest {
    @Test fun animationIsRequiredAndUnknownNamesFailBeforeRequesting() {
        var requests = 0
        val tool = MascotAnimationTool("main-chat") { _, _ -> requests++; null }

        val invalidArguments = listOf<Map<String, Any>>(
            emptyMap(), mapOf("animation" to "unknown"), mapOf("animation" to "working", "extra" to true),
        )
        for (arguments in invalidArguments) {
            val result = tool.execute(arguments, CancellationToken.uncancellable())
            assertFalse(result.success)
        }
        assertTrue(tool.execute(mapOf("animation" to 42), CancellationToken.uncancellable()).content.contains("string"))
        assertEquals(0, requests)
    }

    @Test fun validAnimationReturnsRequestReceiptAndDeclaresExactEnumSchema() {
        val requested = mutableListOf<Pair<String, String>>()
        val tool = MascotAnimationTool("main-chat") { session, animation ->
            requested += session to animation
            null
        }
        val result = tool.execute(mapOf("animation" to "working"), CancellationToken.uncancellable())

        assertTrue(result.success)
        assertEquals("animation requested: working", result.content)
        assertEquals(listOf("main-chat" to "working"), requested)
        assertFalse(tool.canDelegate())
        val declaration = tool.declaration()
        assertEquals(MascotAnimationTool.NAME, declaration.name)
        assertEquals(listOf("animation"), declaration.required)
        val schema = declaration.jsonSchema()
        assertEquals(false, schema["additionalProperties"])
        val properties = schema["properties"] as Map<*, *>
        val animation = properties["animation"] as Map<*, *>
        assertEquals("string", animation["type"])
        assertEquals(MascotAnimationTool.ANIMATIONS, animation["enum"])
    }

    @Test fun unavailableMascotOrAssetReturnsTheUiRejectionWithoutAFalseReceipt() {
        val tool = MascotAnimationTool("main-chat") { _, _ -> "The main chat mascot is not visible." }
        val result = tool.execute(mapOf("animation" to "sad"), CancellationToken.uncancellable())

        assertFalse(result.success)
        assertEquals("The main chat mascot is not visible.", result.content)
    }

    @Test fun headerAvailabilityHonorsVisibilityResumeReducedMotionAndLoadedAssets() {
        assertEquals("The main chat mascot is not visible.", HeaderMascotAvailability("chat").failure("sad"))
        assertEquals("The main chat mascot is not active.", HeaderMascotAvailability("chat", true).failure("sad"))
        assertTrue(HeaderMascotAvailability("chat", true, true, reducedMotion = true,
            availableAnimationIds = setOf("sad")).failure("sad")!!.contains("reduced motion"))
        assertTrue(HeaderMascotAvailability("chat", true, true, availableAnimationIds = emptySet())
            .failure("sad")!!.contains("unavailable"))
        assertNull(HeaderMascotAvailability("chat", true, true, availableAnimationIds = setOf("sad")).failure("sad"))
    }

    @Test fun coreRegistryListsTheToolOnceAndDelegatedCatalogExcludesIt() {
        val tool = MascotAnimationTool("main-chat") { _, _ -> null }
        val registry = CoreToolRegistry(listOf(tool))

        assertEquals(listOf(MascotAnimationTool.NAME), registry.names())
        assertEquals(1, registry.declarations().count { it.name == MascotAnimationTool.NAME })
        assertTrue(registry.forDelegatedAgent().names().isEmpty())
    }
}
