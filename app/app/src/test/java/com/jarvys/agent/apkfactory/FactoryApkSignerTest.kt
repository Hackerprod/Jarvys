package com.jarvys.agent.apkfactory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.apksig.ApkVerifier
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
            System.getProperty("jarvys.factory.evidenceDir")?.let { path ->
                val evidence=File(path).apply{mkdirs()}
                input.copyTo(File(evidence,"$index-unsigned.apk"),overwrite=true)
                output.copyTo(File(evidence,"$index-signed.apk"),overwrite=true)
                File(evidence,"$index.json").writeText(JSONObject().put("appId",id).put("versionCode",version)
                    .put("certificateSha256",prints.last()).put("testKeyOnly",true).put("privateKeyPersisted",false)
                    .put("signedSha256",com.jarvys.agent.coding.ProjectScope.sha256(output.readBytes())).toString())
            }
        }
        assertEquals(prints[0],prints[2]);assertNotEquals(prints[0],prints[1])
    }
}
