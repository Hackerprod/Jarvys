package com.jarvys.agent.apkfactory

import com.jarvys.factory.contract.IncomingSharePolicy
import com.jarvys.factory.contract.NotificationPolicy
import com.jarvys.factory.contract.ManifestPlan
import com.jarvys.factory.contract.ResourceBudgets
import com.jarvys.factory.runtime.FactoryConfig
import com.jarvys.factory.runtime.ResourceBudgetsJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePolicySpecScopeTest {
    private val app = "org.example.policies"
    private val fingerprint = "a".repeat(64)
    private val digest = "b".repeat(64)
    private fun input(caps: List<String>) = JSONObject().put("schemaVersion",1).put("appId",app).put("name","Policies")
        .put("versionCode",1).put("versionName","1").put("capabilities",JSONArray(caps)).put("webDir","web").put("icon","icon.png")
    private fun share(multiple: Boolean = true) = JSONObject().put("mimeTypes",JSONArray(listOf("image/*","text/plain"))).put("multiple",multiple)
    private fun notification(scheduling: Boolean, exact: Boolean) = JSONObject().put("scheduling",scheduling).put("exactAlarms",exact)
    private fun broker() = JSONObject().put("packageName","com.jarvys.agent").put("certificateSha256",fingerprint)
    private fun scope(share: IncomingSharePolicy? = null, notification: NotificationPolicy? = null): FactorySigningScope {
        val caps = mutableListOf("documents","notifications")
        if (share != null) caps += "incomingShare"
        return FactorySigningScope.fromPlan(ManifestPlan(app,"Policies",1,"1",0x7f010001,0x7f020001,caps,share,notification))
    }
    private fun read(scope: JSONObject): FactorySigningScope? {
        val anchor = JSONObject().put("schemaVersion",1).put("appId",app).put("certificateSha256",fingerprint)
            .put("versionCode",1).put("apkSha256",digest).put("scope",scope)
        return FactorySigningScope.readBaseline(JSONObject().put("lastApkSha256",digest).put("lastSignedScope",anchor),app,fingerprint,1)
    }
    @Test fun signedProjectAndRuntimeCarryExactIndependentPolicies() {
        val value = FactorySpec.parse(input(listOf("documents","incomingShare","notifications"))
            .put("incomingShare",share()).put("notifications",notification(true,true)))
        val parsed = FactoryConfig.parse(String(value.runtimeConfig(broker())),app)
        assertEquals(value.incomingShare,parsed.incomingShare); assertEquals(value.notifications,parsed.notifications)
        assertEquals(value,FactorySpec.parse(value.toJson()))
        assertEquals(share().toString(),value.toJson().getJSONObject("incomingShare").toString())
        assertEquals(notification(true,true).toString(),value.toJson().getJSONObject("notifications").toString())
    }
    @Test fun requiredExplicitCapabilitiesAndTypedDeclarationsRejectHiddenAuthority() {
        for (caps in listOf(emptyList(),listOf("incomingShare"),listOf("documents")))
            assertThrows(Exception::class.java) { FactorySpec.parse(input(caps).put("incomingShare",share())) }
        assertThrows(Exception::class.java) { FactorySpec.parse(input(listOf("incomingShare","documents"))) }
        assertThrows(Exception::class.java) { FactorySpec.parse(input(emptyList()).put("notifications",notification(false,false))) }
        assertThrows(Exception::class.java) { FactorySpec.parse(input(listOf("notifications")).put("notifications",notification(false,true))) }
        assertThrows(Exception::class.java) { FactorySpec.parse(input(listOf("notifications")).put("notifications",notification(true,true).put("extra",true))) }
    }
    @Test fun policyAbsenceStaysAbsentInProjectAndRuntimeJson() {
        val value=FactorySpec.parse(input(listOf("storage")))
        assertNull(value.incomingShare);assertNull(value.notifications)
        assertFalse(value.toJson().has("incomingShare"));assertFalse(value.toJson().has("notifications"))
        val runtime=JSONObject(String(value.runtimeConfig()));assertFalse(runtime.has("incomingShare"));assertFalse(runtime.has("notifications"))
        assertEquals(NotificationPolicy.disabled(),FactoryConfig.parse(runtime.toString(),app).notifications)
    }
    @Test fun newSigningEvidenceBindsMimesMultipleAndScheduling() {
        val base=scope()
        val single=scope(IncomingSharePolicy(listOf("image/*","text/plain"),false))
        val multiple=scope(IncomingSharePolicy(listOf("image/*","text/plain"),true),NotificationPolicy(true,false))
        val exact=scope(IncomingSharePolicy(listOf("*/*"),true),NotificationPolicy(true,true))
        assertEquals(exact,read(exact.toJson()))
        assertTrue(single.disclosure(base).any { it.contains("Incoming sharing changes") })
        assertTrue(multiple.disclosure(single).any { it.contains("ACTION_SEND_MULTIPLE=true") })
        assertTrue(exact.disclosure(multiple).any { it.contains("MIME patterns [*/*]") })
        assertTrue(exact.disclosure(multiple).any { it.contains("exactAlarms=true") })
    }
    @Test fun priorScopeFieldSetsAndResourceSectionsRetainExactBytes() {
        val resources=ResourceBudgetsJson.toJson(ResourceBudgets.legacy()).apply { remove("incomingShare"); remove("secrets"); remove("notifications") }
        val prior=JSONObject().put("capabilities",JSONArray(listOf("documents","notifications")))
            .put("permissions",JSONArray()).put("exportedComponents",JSONArray(listOf(ManifestPlan.ACTIVITY)))
            .put("features",JSONArray()).put("queries",JSONArray()).put("allowedHosts",JSONArray())
            .put("queryActions",JSONArray()).put("resourceBudgets",resources)
        val bytes=JSONObject(prior.toString()).toString();val baseline=read(JSONObject(bytes))!!
        assertEquals(bytes,baseline.toJson().toString());assertFalse(baseline.toJson().has("incomingShare"));assertFalse(baseline.toJson().has("notifications"))
        assertFalse(baseline.toJson().getJSONObject("resourceBudgets").has("incomingShare"))
        val old=JSONObject(bytes).apply { remove("queryActions");remove("resourceBudgets") }
        assertEquals(old.toString(),read(old)!!.toJson().toString())
    }
    @Test fun signingEvidenceCannotHideIncomingOrUnconfiguredNotificationAuthority() {
        val incoming=scope(IncomingSharePolicy(listOf("text/plain"),false)).toJson()
        incoming.remove("incomingShare");incoming.remove("notifications");assertNull(read(incoming))
        val malformed=scope().toJson().put("notifications",notification(false,true));assertNull(read(malformed))
        val noCapability=scope().toJson().put("capabilities",JSONArray(listOf("documents"))).put("notifications",notification(true,false))
        assertNull(read(noCapability))
    }
}
