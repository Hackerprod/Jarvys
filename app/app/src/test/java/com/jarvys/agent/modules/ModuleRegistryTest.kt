package com.jarvys.agent.modules

import com.jarvys.agent.connectors.ActionPolicyDefaults
import com.jarvys.agent.connectors.AutonomyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ModuleRegistryTest {
    @Test fun loadsBundledManifestsAndKeepsTheirAssetDirectories() {
        val registry = ModuleRegistry.fromBundleManifests(listOf(
            ModuleManifestSource("modules/html", manifest("com.jarvys.module.html", "HTML")),
            ModuleManifestSource("modules/image", manifest("com.jarvys.module.image", "Image")),
            ModuleManifestSource("modules/file", manifest("com.jarvys.module.file", "File")),
        ))

        assertEquals("modules/html", registry.module("com.jarvys.module.html")?.bundleAssetDirectory)
        assertEquals("modules/image", registry.module("com.jarvys.module.image")?.bundleAssetDirectory)
        assertEquals("modules/file", registry.module("com.jarvys.module.file")?.bundleAssetDirectory)
    }

    @Test fun resolvesExactThenMostSpecificWildcardArtifactTypes() {
        val registry = ModuleRegistry.fromManifests(listOf(
            manifest("com.test.module.files", "Files", artifacts = """[{"type":"*/*"}]"""),
            manifest("com.test.module.images", "Images", artifacts = """[{"type":"image/*"}]"""),
            manifest("com.test.module.png", "PNG", artifacts = """[{"type":"image/png"}]"""),
        ))

        assertEquals("com.test.module.png", registry.moduleIdForArtifactType("IMAGE/PNG; charset=binary"))
        assertEquals("com.test.module.images", registry.moduleIdForArtifactType("image/jpeg"))
        assertEquals("com.test.module.files", registry.moduleIdForArtifactType("application/pdf"))
        assertNull(registry.moduleIdForArtifactType(""))
    }

    @Test fun parsesDeclarationsAndRegistersAccessDerivedPolicyDefaults() {
        val registry = ModuleRegistry.fromManifests(listOf(toolManifest()))
        val module = registry.module("com.test.module.tools")

        assertNotNull(module)
        assertEquals(listOf("read_items", "write_items"), module!!.tools.map { it.name })
        assertEquals(AutonomyPolicy.ALLOW,
            ActionPolicyDefaults.descriptor(module.id, "read_items").defaultPolicy)
        assertEquals(AutonomyPolicy.ASK,
            ActionPolicyDefaults.descriptor(module.id, "write_items").defaultPolicy)
    }

    @Test fun rejectsInvalidManifestSchemaAndUnsafeBundlePaths() {
        assertThrows(IllegalArgumentException::class.java) {
            ModuleRegistry.fromManifests(listOf(manifest("com.test.invalid", "Invalid", tools = """[
                {"name":"make_item","description":"Make an item","inputSchema":{"type":"array"},
                 "access":"write","handler":"logic/tool.js#makeItem"}
            ]""")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModuleRegistry.fromManifests(listOf(manifest("com.test.unsafe", "Unsafe", tools = """[
                {"name":"make_item","description":"Make an item","inputSchema":{"type":"object"},
                 "access":"write","handler":"../outside.js#makeItem"}
            ]""")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModuleRegistry.fromManifests(listOf(manifest("com.test.unknown-capability", "Unknown",
                capabilities = "[\"device.credentials\"]")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModuleRegistry.fromManifests(listOf(manifest("com.test.unenforced-schema", "Unenforced", tools = """[
                {"name":"make_item","description":"Make an item","inputSchema":{"type":"object",
                 "properties":{"count":{"type":"integer","minimum":1}}},
                 "access":"write","handler":"logic/tool.js#makeItem"}
            ]""")))
        }
    }

    @Test fun rejectsAmbiguousArtifactDeclarationAndCrossModuleToolCollision() {
        assertThrows(IllegalArgumentException::class.java) {
            ModuleRegistry.fromManifests(listOf(
                manifest("com.test.one", "One", artifacts = """[{"type":"image/*"}]"""),
                manifest("com.test.two", "Two", artifacts = """[{"type":"IMAGE/*"}]"""),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModuleRegistry.fromManifests(listOf(
                toolManifest("com.test.one"), toolManifest("com.test.two"),
            ))
        }
    }

    private fun manifest(
        id: String,
        name: String,
        artifacts: String = "[]",
        tools: String = "[]",
        capabilities: String = "[]",
    ) = """{"schemaVersion":1,"id":"$id","version":"1.0.0","name":"$name",
        "artifactTypes":$artifacts,"tools":$tools,"skills":[],"capabilities":$capabilities}"""

    private fun toolManifest(id: String = "com.test.module.tools") = manifest(
        id,
        "Tools",
        tools = """[
            {"name":"read_items","description":"Read items",
             "inputSchema":{"type":"object","properties":{"count":{"type":"integer"}},"required":["count"]},
             "access":"read","handler":"logic/tools.js#readItems"},
            {"name":"write_items","description":"Write items",
             "inputSchema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]},
             "access":"write","handler":"logic/tools.js#writeItems"}
        ]""",
    )
}
