package com.jarvys.agent.apkfactory

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FactorySpecTest {
    private fun spec() = JSONObject().put("schemaVersion",1).put("appId","org.example.notes").put("name","Notas útiles")
        .put("versionCode",1).put("versionName","1.0").put("capabilities",JSONArray(listOf("storage","export")))
        .put("webDir","web").put("icon","icon.json")
    private fun rejects(change: (JSONObject)->Unit) {
        val json=spec();change(json)
        try { FactorySpec.parse(json); fail("Invalid input accepted: $json") } catch (_: IllegalArgumentException) { }
    }
    @Test fun validSpecHasStableCapabilitiesAndExactRuntimeContract() {
        val value=FactorySpec.parse(spec())
        assertEquals(listOf("export","storage"),value.capabilities)
        val config=JSONObject(String(value.runtimeConfig()))
        assertEquals("www/index.html",config.getString("entryPoint"))
        assertEquals(setOf("schemaVersion","appId","name","entryPoint","capabilities"),config.keys().asSequence().toSet())
    }
    @Test fun reservedAndInvalidPackageIdsAreRejected() {
        for(id in listOf("com.jarvys.agent","com.jarvys.factory.template","android.hidden","single","org.Example.x","org.a/evil","org..x","org."+"x".repeat(125))) rejects{it.put("appId",id)}
    }
    @Test fun unknownAndDuplicateCapabilitiesFailInsteadOfAddingPermissions() {
        rejects{it.put("capabilities",JSONArray(listOf("camera")))}
        rejects{it.put("capabilities",JSONArray(listOf("storage","storage")))}
        rejects{it.put("capabilities","storage")}
        rejects{it.put("permissions",JSONArray(listOf("android.permission.INTERNET")))}
    }
    @Test fun onlyFinitePositiveIntegerVersionAccepted() {
        for(n in listOf<Any>(0,-1,1.0,2147483648L,"1")) rejects{it.put("versionCode",n)}
    }
    @Test fun schemaAndUnknownFieldsAreStrict() {
        rejects{it.put("schemaVersion",2)};rejects{it.put("command","execute")};rejects{it.remove("icon")}
    }
    @Test fun namesAndPathsBounded() {
        rejects{it.put("name","x\nname")};rejects{it.put("name","x".repeat(81))};rejects{it.put("versionName","")}
        for(path in listOf("/tmp/web","../web","x/../web",".git","x//y","x\\y","x/./y")) rejects{it.put("webDir",path)}
        rejects{it.put("icon","icon.svg")}
    }
    @Test fun nativeCapabilitiesCannotBeInventedByManifestFlags() {
        rejects{it.put("nativeCode","classes.dex")};rejects{it.put("providerAuthority","shared")}
    }
    @Test fun projectJsonRejectsDeepNestingAndMalformedUtf8BeforeRecursiveParsing() {
        val nested=("{\"x\":"+"[".repeat(2000)+"0"+"]".repeat(2000)+"}").toByteArray()
        assertThrows(Exception::class.java){FactoryJson.objectFrom(nested,16*1024)}
        assertThrows(Exception::class.java){FactoryJson.objectFrom(byteArrayOf(123,34,120,34,58,34,0xc3.toByte(),34,125),1024)}
        val valid=FactoryJson.objectFrom(spec().put("name","Brackets [ ] { } \"quoted\"").toString().toByteArray(),16*1024)
        assertTrue(valid.getString("name").contains("[ ]"))
    }
    @Test fun strictJsonEnforcesAdjacentDepthBoundaryAndAsciiEscapes() {
        fun nested(levels:Int)=("{\"x\":"+"[".repeat(levels)+"0"+"]".repeat(levels)+"}").toByteArray()
        FactoryJson.objectFrom(nested(7),16*1024)
        assertThrows(Exception::class.java){FactoryJson.objectFrom(nested(8),16*1024)}
        val nonAsciiEscape="{\"x\":\""+'\\'+"uＦＦＦＦ\"}"
        assertThrows(Exception::class.java){FactoryJson.objectFrom(nonAsciiEscape.toByteArray(),16*1024)}
    }
    @Test fun emptyCapabilitiesDoNotGainNativeAccess() {
        val value=FactorySpec.parse(spec().put("capabilities",JSONArray()))
        assertTrue(value.capabilities.isEmpty())
    }
    @Test fun all32768SelectionsUseImmutableCatalogWithoutImpliedCapabilities() {
        assertEquals(15, FactorySpec.CAPABILITIES.size)
        for (mask in 0 until 32768) {
            val selected = FactorySpec.CAPABILITIES.filterIndexed { bit, _ -> mask and (1 shl bit) != 0 }
            val value = FactorySpec.parse(spec().put("capabilities", JSONArray(selected.reversed())))
            assertEquals(selected.sorted(), value.capabilities)
            assertEquals(1, JSONObject(String(value.runtimeConfig(if ("documents" in selected || "browser" in selected || "maps" in selected || "phone" in selected || "email" in selected || "sms" in selected || "contacts" in selected) broker() else null))).getInt("schemaVersion"))
            for (name in selected) {
                val capability = com.jarvys.factory.contract.CapabilityCatalog.CAPABILITIES[name]!!
                assertTrue(capability.permissions.isEmpty())
                assertTrue(capability.components.isEmpty())
                assertTrue(capability.dependencies.isEmpty())
                assertTrue(capability.conflicts.isEmpty())
                assertFalse(capability.methods.isEmpty())
            }
            assertThrows(UnsupportedOperationException::class.java) {
                (value.capabilities as MutableList<String>).add("camera")
            }
        }
    }

    @Test fun mapsAndPhoneOnlyGeneratedConfigPinsHostAndKeepsCapabilitiesIndependent() {
        for (capability in listOf("maps", "phone", "email", "sms", "contacts")) {
            val typed = FactorySpec.parse(spec().put("capabilities", JSONArray(listOf(capability))))
            assertThrows(Exception::class.java) { typed.runtimeConfig() }
            val configBytes = typed.runtimeConfig(broker())
            val parsed = com.jarvys.factory.runtime.FactoryConfig.parse(String(configBytes), typed.appId)
            assertEquals(setOf(capability), parsed.capabilities)
            assertEquals("com.jarvys.agent", parsed.documentBroker.packageName)
            assertEquals("a".repeat(64), parsed.documentBroker.certificateSha256)
        }
    }
    private fun broker() = JSONObject().put("packageName","com.jarvys.agent").put("certificateSha256","a".repeat(64))
    @Test fun documentBrokerIsBuildOwnedAndCannotBeDeclaredInProjectSpec() {
        rejects { it.put("documentBroker",broker()) }
        val docs = FactorySpec.parse(spec().put("capabilities",JSONArray(listOf("documents"))))
        assertThrows(Exception::class.java) { docs.runtimeConfig() }
        val config = JSONObject(String(docs.runtimeConfig(broker())))
        assertEquals("com.jarvys.agent",config.getJSONObject("documentBroker").getString("packageName"))
        assertThrows(Exception::class.java) { FactorySpec.parse(spec()).runtimeConfig(broker()) }
        assertThrows(Exception::class.java) { docs.runtimeConfig(broker().put("packageName","org.example.untrusted")) }
    }

}
