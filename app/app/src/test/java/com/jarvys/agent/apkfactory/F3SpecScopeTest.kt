package com.jarvys.agent.apkfactory

import com.jarvys.factory.contract.BluetoothPolicy
import com.jarvys.factory.contract.ManifestPlan
import com.jarvys.factory.runtime.FactoryConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class F3SpecScopeTest {
    private val app="org.example.f3"
    private fun input(caps:List<String>)=JSONObject().put("schemaVersion",1).put("appId",app).put("name","F3").put("versionCode",1).put("versionName","1").put("capabilities",JSONArray(caps)).put("webDir","web").put("icon","icon.png")
    private fun read(scope:JSONObject):FactorySigningScope? {
        val anchor=JSONObject().put("schemaVersion",1).put("appId",app).put("certificateSha256","a".repeat(64)).put("versionCode",1).put("apkSha256","b".repeat(64)).put("scope",scope)
        return FactorySigningScope.readBaseline(JSONObject().put("lastApkSha256","b".repeat(64)).put("lastSignedScope",anchor),app,"a".repeat(64),1)
    }
    @Test fun projectRuntimeAndSigningScopeBindBluetoothPrivacyExactly() {
        for(privacy in listOf("full","neverForLocation")) {
            val spec=FactorySpec.parse(input(listOf("bluetooth")).put("bluetooth",JSONObject().put("version",1).put("scanPrivacy",privacy)))
            val broker=JSONObject().put("packageName","com.jarvys.agent").put("certificateSha256","a".repeat(64))
            assertEquals(spec.bluetooth,FactoryConfig.parse(String(spec.runtimeConfig(broker)),app).bluetooth)
            assertEquals(spec,FactorySpec.parse(spec.toJson()))
            val plan=ManifestPlan(app,"F3",1,"1",0x7f010001,0x7f020001,listOf("bluetooth"),null,null,spec.bluetooth,0)
            val scope=FactorySigningScope.fromPlan(plan)
            assertEquals(privacy,scope.toJson().getJSONObject("bluetooth").getString("scanPrivacy"));assertEquals(scope,read(scope.toJson()))
        }
        assertThrows(Exception::class.java){FactorySpec.parse(input(listOf("bluetooth")))}
        assertThrows(Exception::class.java){FactorySpec.parse(input(listOf("jobs")))}
        assertNotNull(FactorySpec.parse(input(listOf("jobs","network"))))
    }
    @Test fun everyHistoricalScopeFieldSetRetainsExactBytesAndAbsentNewDefaults() {
        val base=FactorySigningScope.fromPlan(ManifestPlan(app,"F3",1,"1",0x7f010001,0x7f020001,listOf("storage"))).toJson()
        for(fieldCount in listOf(6,8,9,10)) {
            val historic=JSONObject(base.toString()).apply {
                remove("bluetooth");remove("privateComponents")
                if(fieldCount<10)remove("notifications")
                if(fieldCount<9)remove("incomingShare")
                if(fieldCount==6){remove("resourceBudgets");remove("queryActions")}
                else { val resources=getJSONObject("resourceBudgets");for(name in listOf("nfc","bluetooth","widgets","jobs"))resources.remove(name) }
            }
            val bytes=JSONObject(historic.toString()).toString();val restored=read(JSONObject(bytes))!!
            assertEquals(fieldCount,restored.toJson().length());assertEquals(bytes,restored.toJson().toString())
            restored.resources();assertEquals(bytes,restored.toJson().toString())
            assertFalse(restored.toJson().has("bluetooth"));assertFalse(restored.toJson().has("privateComponents"))
        }
        val spec=FactorySpec.parse(input(listOf("storage")));assertFalse(spec.toJson().has("bluetooth"));assertFalse(JSONObject(String(spec.runtimeConfig())).has("bluetooth"))
    }
    @Test fun scanPrivacyChangesAreDisclosedAndCannotBeDroppedFromSignedSnapshot() {
        fun scope(privacy:String)=FactorySigningScope.fromPlan(ManifestPlan(app,"F3",1,"1",0x7f010001,0x7f020001,listOf("bluetooth"),null,null,BluetoothPolicy(privacy),0))
        val full=scope("full");val never=scope("neverForLocation")
        assertTrue(never.disclosure(full).any{it.contains("Bluetooth scan privacy changes: full -> neverForLocation")})
        val damaged=never.toJson();damaged.remove("bluetooth");assertNull(read(damaged))
        val oldShape=never.toJson();oldShape.remove("bluetooth");oldShape.remove("privateComponents");assertNull(read(oldShape))
    }
}
