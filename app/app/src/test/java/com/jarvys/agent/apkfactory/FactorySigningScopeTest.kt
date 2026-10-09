package com.jarvys.agent.apkfactory

import com.jarvys.factory.contract.ManifestPlan
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FactorySigningScopeTest {
    private val appId = "org.example.notebook"
    private val fingerprint = "a".repeat(64)
    private val sha = "b".repeat(64)
    private fun scope() = FactorySigningScope.fromPlan(ManifestPlan(appId,"Notebook",1,"1.0",0x7f010001,0x7f020001,listOf("storage")))
    private fun record() = JSONObject().put("schemaVersion",1).put("appId",appId)
        .put("certificateSha256",fingerprint).put("lastVersion",1).put("lastApkSha256",sha)
        .put("lastSignedScope",scope().anchored(appId,fingerprint,1,sha))
    private fun read(record:JSONObject) = FactorySigningScope.readBaseline(record,appId,fingerprint,1)
    @Test fun roundTripPreservesScopeAndDoesNotTreatLegacyAsEmpty() {
        assertEquals(scope(),read(record()))
        val legacy=record().apply{remove("lastSignedScope")}
        assertNull(read(legacy))
        assertTrue(scope().disclosure(read(legacy)).single().contains("cannot be determined"))
    }
    @Test fun wrongAppCertificateVersionAndApkAnchorsAreUnknown() {
        for ((field,value) in listOf("appId" to "org.other.app","certificateSha256" to "c".repeat(64),"versionCode" to 2,"apkSha256" to "d".repeat(64),"schemaVersion" to 2)) {
            val record=record();record.getJSONObject("lastSignedScope").put(field,value)
            assertNull("$field must match",read(record))
        }
        assertNull(read(record().apply{remove("lastApkSha256")}))
    }
    @Test fun corruptMissingUnboundedAndDuplicateScopeFieldsAreUnknown() {
        for (mutate in listOf<(JSONObject)->Unit>(
            { it.remove("permissions") },
            { it.put("permissions","none") },
            { it.put("permissions",JSONArray(listOf(1))) },
            { it.put("permissions",JSONArray(listOf("x","x"))) },
            { it.put("permissions",JSONArray(listOf("x".repeat(513)))) },
            { it.put("permissions",JSONArray((0..128).map{n->"permission$n"})) },
            { it.put("permissions",JSONArray(listOf("x\\n".replace("\\n","\n")))) },
            { it.put("unexpected",true) }
        )) {
            val record=record();mutate(record.getJSONObject("lastSignedScope").getJSONObject("scope"))
            assertNull(read(record))
            assertTrue(scope().disclosure(read(record)).single().contains("cannot be determined"))
        }
    }
    @Test fun everyEffectiveManifestDimensionParticipatesInDelta() {
        for(field in listOf("permissions","exportedComponents","features","queries","allowedHosts")) {
            val record=record();val json=record.getJSONObject("lastSignedScope").getJSONObject("scope")
            val original=json.getJSONArray(field)
            original.put("historical.$field")
            val historical=read(record)!!
            assertTrue(scope().disclosure(historical).any{it=="Scope removals ($field): historical.$field"})
            assertTrue(historical.disclosure(scope()).any{it=="Scope additions ($field): historical.$field"})
        }
    }
    @Test fun returnedJsonCannotMutateStoredSnapshot() {
        val scope=scope();scope.toJson().put("permissions",JSONArray(listOf("injected")))
        assertEquals(0,scope.toJson().getJSONArray("permissions").length())
    }
}
