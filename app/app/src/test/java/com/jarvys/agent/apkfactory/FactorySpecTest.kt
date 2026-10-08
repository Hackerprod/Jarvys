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
    @Test fun emptyCapabilitiesDoNotGainNativeAccess() {
        val value=FactorySpec.parse(spec().put("capabilities",JSONArray()))
        assertTrue(value.capabilities.isEmpty())
    }
}
