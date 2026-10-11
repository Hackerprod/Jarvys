package com.jarvys.agent.apkfactory

import com.jarvys.factory.contract.ManifestPlan
import com.jarvys.factory.contract.ResourceBudgets
import com.jarvys.factory.runtime.ResourceBudgetsJson
import com.jarvys.agent.coding.ProjectScope
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
        for(field in listOf("permissions","exportedComponents","features","queries","allowedHosts","queryActions")) {
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
    @Test fun biometricSigningAddsAndRemovesTheExactPermissionWithoutImplyingFeaturesOrQueries() {
        val baseline = scope()
        val biometrics = FactorySigningScope.fromPlan(ManifestPlan(appId,"Notebook",2,"2.0",0x7f010001,0x7f020001,listOf("storage","biometrics")))
        assertEquals(listOf("Scope additions (capabilities): biometrics", "Scope additions (permissions): android.permission.USE_BIOMETRIC"), biometrics.disclosure(baseline))
        assertEquals(listOf("Scope removals (capabilities): biometrics", "Scope removals (permissions): android.permission.USE_BIOMETRIC"), baseline.disclosure(biometrics))
        val permissions = biometrics.toJson().getJSONArray("permissions")
        assertEquals(listOf("android.permission.USE_BIOMETRIC"), (0 until permissions.length()).map { permissions.getString(it) })
        for (field in listOf("features", "queries", "allowedHosts")) assertEquals(0, biometrics.toJson().getJSONArray(field).length())
        assertTrue(biometrics.disclosure(biometrics).single().contains("Effective scope unchanged"))
        val anchored = record().put("lastSignedScope", biometrics.anchored(appId,fingerprint,1,sha))
        assertEquals(biometrics, read(anchored))
        assertEquals(biometrics.disclosure(baseline), read(anchored)!!.disclosure(baseline))
    }

    @Test fun legacySixFieldScopeRetainsItsExactCanonicalBytesAndDigest() {
        // The original writer's six-field order, with no new keys or synthesized empty queries.
        val legacy = JSONObject().put("capabilities", JSONArray(listOf("storage")))
            .put("permissions", JSONArray()).put("exportedComponents", JSONArray(listOf(ManifestPlan.ACTIVITY)))
            .put("features", JSONArray()).put("queries", JSONArray()).put("allowedHosts", JSONArray())
        val anchor = JSONObject().put("schemaVersion", 1).put("appId", appId).put("certificateSha256", fingerprint)
            .put("versionCode", 1).put("apkSha256", sha).put("scope", legacy)
        val bytes = anchor.toString().toByteArray(Charsets.UTF_8)
        val parsed = read(JSONObject().put("lastApkSha256", sha).put("lastSignedScope", anchor))!!
        val roundTrip = parsed.anchored(appId, fingerprint, 1, sha).toString().toByteArray(Charsets.UTF_8)
        assertArrayEquals(bytes, roundTrip)
        assertEquals(ProjectScope.sha256(bytes), ProjectScope.sha256(roundTrip))
        assertEquals(6, parsed.toJson().length())
        assertFalse(parsed.toJson().has("queryActions"))
        assertFalse(parsed.toJson().has("resourceBudgets"))
        assertEquals(ResourceBudgets.legacy(), parsed.resources())
        val disclosure = scope().disclosure(parsed)
        assertTrue(disclosure.any { it.contains("query action baseline unavailable") })
        assertTrue(disclosure.none { it.contains("Scope additions (queryActions)") || it.contains("Effective scope unchanged") })
        // Resolving historical limits and disclosure must not upgrade the authenticated encoding.
        assertArrayEquals(bytes, parsed.anchored(appId, fingerprint, 1, sha).toString().toByteArray(Charsets.UTF_8))
    }

    @Test fun currentScopesBindQueryActionsAndAllResolvedResourceCapacities() {
        val budgets = ResourceBudgets.parse(mapOf("version" to 1, "documents" to mapOf("openHandles" to 8)))
        val plan = ManifestPlan(appId, "Notebook", 1, "1.0", 0x7f010001, 0x7f020001, listOf("storage", "speech"))
        val current = FactorySigningScope.fromPlan(plan, budgets)
        assertEquals(plan.queryActions.sorted(), (0 until current.toJson().getJSONArray("queryActions").length())
            .map { current.toJson().getJSONArray("queryActions").getString(it) })
        assertTrue(plan.queryActions.isNotEmpty())
        assertEquals(budgets, current.resources())
        assertEquals(14, current.toJson().getJSONObject("resourceBudgets").length())
        val reloaded = read(record().put("lastSignedScope", current.anchored(appId, fingerprint, 1, sha)))!!
        assertEquals(current, reloaded)
        assertEquals(budgets, reloaded.resources())
        assertTrue(current.disclosure(scope()).any { it == "Resource capacity changes (documents.openHandles): 4 -> 8" })
        assertTrue(scope().disclosure(current).any { it == "Resource capacity changes (documents.openHandles): 8 -> 4" })
        for (action in plan.queryActions) assertTrue(current.disclosure(scope()).any { it == "Scope additions (queryActions): $action" })
        val otherInstance = ResourceBudgetsJson.parse(ResourceBudgetsJson.toJson(budgets))
        assertNotSame(budgets, otherInstance)
        assertEquals(current, FactorySigningScope.fromPlan(plan, otherInstance))
        assertTrue(current.disclosure(FactorySigningScope.fromPlan(plan, otherInstance)).single().contains("Effective scope unchanged"))
    }

    @Test fun incompleteMixedOrInvalidCurrentScopeNeverInventsAuthenticatedDefaults() {
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.remove("queryActions") },
            { it.remove("resourceBudgets") },
            { it.put("queryActions", JSONArray(listOf("duplicate", "duplicate"))) },
            { it.put("resourceBudgets", JSONObject().put("version", 1)) },
            { it.getJSONObject("resourceBudgets").getJSONObject("documents").remove("openHandles") },
            { it.getJSONObject("resourceBudgets").getJSONObject("documents").put("openHandles", "8") },
            { it.getJSONObject("resourceBudgets").getJSONObject("documents").put("openHandles", 17) },
            { it.getJSONObject("resourceBudgets").put("version", 2) }
        )) {
            val saved = record(); mutate(saved.getJSONObject("lastSignedScope").getJSONObject("scope"))
            assertNull(read(saved))
        }
    }
    @Test fun preServiceResourceScopePreservesAuthenticatedCanonicalBytes() {
        val prior = scope().toJson()
        val resources = prior.getJSONObject("resourceBudgets")
        for (section in listOf("secrets", "notifications", "incomingShare", "nfc", "bluetooth", "widgets", "jobs")) resources.remove(section)
        prior.remove("incomingShare"); prior.remove("notifications"); prior.remove("bluetooth"); prior.remove("privateComponents")
        val historic = JSONObject(prior.toString())
        val original = historic.toString()
        val saved = record()
        saved.getJSONObject("lastSignedScope").put("scope", historic)
        val loaded = read(saved)!!
        assertEquals(original, loaded.toJson().toString())
        assertEquals(32, loaded.resources().secrets.entries)
        assertEquals(128, loaded.resources().notifications.schedules)
        assertEquals(16, loaded.resources().incomingShare.queuedEnvelopes)
        assertEquals(7, loaded.toJson().getJSONObject("resourceBudgets").length())
    }

}
