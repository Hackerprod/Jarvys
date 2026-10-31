package com.jarvys.agent.apkfactory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class AdaptiveIconSpecTest {
    private fun input()=JSONObject().put("schemaVersion",1).put("appId","org.example.adaptive").put("name","Adaptive canvas")
        .put("versionCode",1).put("versionName","1").put("capabilities",JSONArray()).put("webDir","web").put("icon","icon.json")
    private fun adaptive()=JSONObject().put("foreground","art/foreground.json").put("background","#123456")
    @Test fun optionalArtworkRetainsLegacySpecAndRuntimeBytes() {
        val old=FactorySpec.parse(input());assertNull(old.adaptiveIcon);assertFalse(old.toJson().has("adaptiveIcon"))
        val current=FactorySpec.parse(input().put("adaptiveIcon",adaptive()))
        assertEquals(0,current.adaptiveIcon!!.insetPercent)
        assertArrayEquals(old.runtimeConfig(),current.runtimeConfig())
        assertEquals(old.toJson().toString(),current.toJson().also{it.remove("adaptiveIcon")}.toString())
        assertEquals(current,FactorySpec.parse(current.toJson()))
    }
    @Test fun explicitInsetRoundTripsAndDefaultIsAbsent() {
        val value=FactorySpec.parse(input().put("adaptiveIcon",adaptive().put("insetPercent",20)))
        assertEquals(20,value.toJson().getJSONObject("adaptiveIcon").getInt("insetPercent"))
        assertEquals(value,FactorySpec.parse(value.toJson()))
        val zero=FactorySpec.parse(input().put("adaptiveIcon",adaptive().put("insetPercent",0)))
        assertFalse(zero.toJson().getJSONObject("adaptiveIcon").has("insetPercent"))
    }
    @Test fun noArbitraryXmlNativeCodeOrResourceNumbersAccepted() {
        for (field in listOf("xml","resourceId","roundIcon","monochrome","script"))
            assertThrows(Exception::class.java){FactorySpec.parse(input().put("adaptiveIcon",adaptive().put(field,"anything")))}
        for (path in listOf("../icon.png","a.xml","/a.png"))
            assertThrows(Exception::class.java){FactorySpec.parse(input().put("adaptiveIcon",adaptive().put("foreground",path)))}
        for (inset in listOf<Any>(-1,41,1.5,"20",JSONObject.NULL))
            assertThrows(Exception::class.java){FactorySpec.parse(input().put("adaptiveIcon",adaptive().put("insetPercent",inset)))}
        assertThrows(Exception::class.java){FactorySpec.parse(input().put("adaptiveIcon",JSONObject.NULL))}
    }
}
