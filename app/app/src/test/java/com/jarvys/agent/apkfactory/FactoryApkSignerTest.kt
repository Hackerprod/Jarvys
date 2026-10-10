package com.jarvys.agent.apkfactory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.apksig.ApkVerifier
import com.jarvys.agent.coding.ProjectScope
import com.jarvys.factory.contract.CapabilityCatalog
import com.jarvys.factory.contract.ManifestPlan
import com.jarvys.factory.runtime.FactoryConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature

/** Keys are generated only in RAM for this test; no real identity or private-key fixture is shipped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FactoryApkSignerTest {
    @get:Rule val folder=TemporaryFolder()
    @Test fun exactSignerVerifiesTwoIdsAndSameKeyUpdateWithDistinctAppKeys() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val template=context.assets.open("apk_factory/template.apk").use{it.readBytes()}
        val icon=FactoryIcon.render("icon.json", """{"schemaVersion":1,"background":"#112233","shapes":[{"type":"circle","cx":96,"cy":96,"r":60,"fill":"#FFFFFF"}]}""".toByteArray())
        val keys=List(2){KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()}
        val certs=keys.map{pair -> EphemeralFactoryCertificate.create(pair.public.encoded){data->Signature.getInstance("SHA256withRSA").run{initSign(pair.private);update(data);sign()}}}
        val prints=mutableListOf<String>()
        for((index,entry) in listOf(Triple("org.example.first",1,0),Triple("org.example.second",1,1),Triple("org.example.first",2,0)).withIndex()) {
            val (id,version,key)=entry
            val config=JSONObject().put("schemaVersion",1).put("appId",id).put("name","Test $index").put("entryPoint","www/index.html").put("capabilities",org.json.JSONArray())
            val generated=TemplateApk.build(template,TemplateApk.Spec(id,"Test $index",version,"$version.0"),icon,
                mapOf("assets/factory-app.json" to config.toString().toByteArray(),"assets/www/index.html" to "<!doctype html><title>Test</title>".toByteArray()))
            val input=folder.newFile("$index-unsigned.apk").apply{writeBytes(generated)}
            val output=File(folder.root,"$index-signed.apk")
            prints+=FactoryApkSigner.sign(input,output,keys[key].private,certs[key])
            val result=ApkVerifier.Builder(output).setMinCheckedPlatformVersion(24).build().verify()
            assertTrue(result.isVerified);assertTrue(result.isVerifiedUsingV2Scheme);assertTrue(result.isVerifiedUsingV3Scheme)
            assertEquals(id,TemplateApk.inspect(output.readBytes()).appId)
            assertEquals(version,TemplateApk.inspect(output.readBytes()).versionCode)
            FactoryApkEvidence.write("$index-unsigned.apk",generated)
            FactoryApkEvidence.write("$index-signed.apk",output.readBytes())
            FactoryApkEvidence.write("$index.json",JSONObject().put("appId",id).put("versionCode",version)
                .put("certificateSha256",prints.last()).put("testKeyOnly",true).put("privateKeyPersisted",false)
                .put("signedSha256",ProjectScope.sha256(output.readBytes())).toString().toByteArray(Charsets.UTF_8))
        }
        assertEquals(prints[0],prints[2]);assertNotEquals(prints[0],prints[1])
    }

    @Test fun selectiveBiometricAndCombinedProfilesSignWithExactPermissionsAndUnchangedPayload() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val template=context.assets.open("apk_factory/template.apk").use { it.readBytes() }
        val icon=FactoryIcon.render("icon.json", """{"schemaVersion":1,"background":"#112233","shapes":[{"type":"circle","cx":96,"cy":96,"r":60,"fill":"#FFFFFF"}]}""".toByteArray())
        val key=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate=EphemeralFactoryCertificate.create(key.public.encoded) { data ->
            Signature.getInstance("SHA256withRSA").run { initSign(key.private);update(data);sign() }
        }
        val fingerprint=ProjectScope.sha256(certificate.encoded)
        for (broker in listOf(false,true)) {
            val name=if (broker) "biometrics-queries" else "biometrics"
            val capabilities=if (broker) listOf("biometrics","documents") else listOf("biometrics")
            val id=if (broker) "org.example.biometricqueries" else "org.example.biometrics"
            val spec=TemplateApk.Spec(id,"Biometric test",1,"1.0",capabilities)
            val config=JSONObject().put("schemaVersion",1).put("appId",id).put("name",spec.label)
                .put("entryPoint","www/index.html").put("capabilities",JSONArray(capabilities))
            if (broker) config.put("documentBroker",JSONObject().put("packageName","com.jarvys.agent")
                .put("certificateSha256","a".repeat(64)))
            FactoryConfig.parse(config.toString(),id)
            val generated=TemplateApk.build(template,spec,icon,mapOf(
                "assets/factory-app.json" to config.toString().toByteArray(Charsets.UTF_8),
                "assets/www/index.html" to "<!doctype html><title>Synthetic biometric profile</title>".toByteArray(Charsets.UTF_8)))
            val expected=TemplateApk.verify(generated,spec).plan
            val input=folder.newFile("$name-unsigned.apk").apply { writeBytes(generated) }
            val output=File(folder.root,"$name-signed.apk")
            assertEquals(fingerprint,FactoryApkSigner.sign(input,output,key.private,certificate))
            val verified=ApkVerifier.Builder(output).setMinCheckedPlatformVersion(24).build().verify()
            assertTrue(verified.isVerified);assertTrue(verified.isVerifiedUsingV2Scheme);assertTrue(verified.isVerifiedUsingV3Scheme)
            assertEquals(fingerprint,ProjectScope.sha256(verified.signerCertificates.single().encoded))
            val signed=output.readBytes()
            TemplateApk.verifyUnchangedPayload(generated,signed)
            val actual=TemplateApk.verifyAgainstPlan(signed,expected)
            assertEquals(capabilities,actual.plan.capabilities)
            assertEquals(listOf(CapabilityCatalog.BIOMETRIC_PERMISSION),actual.permissions)
            assertEquals(if (broker) listOf("com.jarvys.agent","com.jarvys.agent.recoverytest") else emptyList<String>(),actual.plan.queries)
            assertTrue(actual.plan.features.isEmpty());assertTrue(actual.plan.hosts.isEmpty())
            assertEquals(listOf(ManifestPlan.ACTIVITY),actual.plan.exportedComponents)
            assertEquals(TemplateApk.inspect(template).dexSha256,actual.dexSha256)
            FactoryApkEvidence.write("$name-unsigned.apk",generated)
            FactoryApkEvidence.write("$name-signed.apk",signed)
            FactoryApkEvidence.write("$name.json",JSONObject().put("schemaVersion",1).put("appId",id).put("versionCode",1)
                .put("capabilities",JSONArray(capabilities)).put("permissions",JSONArray(actual.permissions))
                .put("queries",JSONArray(actual.plan.queries)).put("features",JSONArray(actual.plan.features))
                .put("nodeCount",actual.plan.nodes.size).put("certificateSha256",fingerprint)
                .put("testKeyOnly",true).put("privateKeyPersisted",false).put("payloadUnchanged",true)
                .put("unsignedSha256",ProjectScope.sha256(generated)).put("signedSha256",ProjectScope.sha256(signed))
                .toString().toByteArray(Charsets.UTF_8))
        }
    }
}
