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
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Keys are generated only in RAM for this test; no real identity or private-key fixture is shipped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FactoryApkSignerTest {
    @get:Rule val folder=TemporaryFolder()
    @Test fun exactSignerVerifiesTwoIdsAndSameKeyUpdateWithDistinctAppKeys() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val template=context.assets.open("apk_factory/template.apk").use{it.readBytes()}
        val icon=context.assets.open("apk_factory/template.apk").use{ /* template's PNG is found from ZIP */
            java.util.zip.ZipInputStream(it).use { zip ->
                var found:ByteArray?=null
                while(true){val entry=zip.nextEntry ?: break;if(entry.name.startsWith("res/")&&entry.name.endsWith(".png")){found=zip.readBytes();break}}
                found ?: error("Template launcher PNG missing")
            }
        }
        val keys=List(2){KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()}
        val certs=keys.map{pair -> certificate(pair.public.encoded){data->Signature.getInstance("SHA256withRSA").run{initSign(pair.private);update(data);sign()}}}
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
        }
        assertEquals(prints[0],prints[2]);assertNotEquals(prints[0],prints[1])
    }
    private fun certificate(publicKey:ByteArray,sign:(ByteArray)->ByteArray):X509Certificate {
        val algorithm=der(0x30,hex("06092a864886f70d01010b"),der(5,byteArrayOf()))
        val name=der(0x30,der(0x31,der(0x30,hex("0603550403"),der(12,"Ephemeral factory test".toByteArray()))))
        val time=der(0x30,der(23,"260101000000Z".toByteArray()),der(23,"460101000000Z".toByteArray()))
        val tbs=der(0x30,der(0xa0,der(2,byteArrayOf(2))),der(2,BigInteger.ONE.toByteArray()),algorithm,name,time,name,publicKey)
        val encoded=der(0x30,tbs,algorithm,der(3,byteArrayOf(0)+sign(tbs)))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
    }
    private fun der(tag:Int,vararg pieces:ByteArray):ByteArray {
        val body=pieces.fold(byteArrayOf()){acc,item->acc+item}
        val length=when {body.size<128->byteArrayOf(body.size.toByte());body.size<256->byteArrayOf(0x81.toByte(),body.size.toByte());else->byteArrayOf(0x82.toByte(),(body.size ushr 8).toByte(),body.size.toByte())}
        return byteArrayOf(tag.toByte())+length+body
    }
    private fun hex(value:String)=value.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
}
